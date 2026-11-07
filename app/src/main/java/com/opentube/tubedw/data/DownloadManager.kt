package com.opentube.tubedw.data

import android.content.Context
import android.os.Environment
import com.opentube.tubedw.download.DownloadService
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED }

data class DownloadTask(
    val id: String,
    val videoUrl: String,
    val title: String,
    val kind: DownloadKind,
    val formatLabel: String,
    val language: String?,
    val selector: String,
    val progress: Float = 0f,
    val eta: Long = -1L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val path: String? = null,
    val error: String? = null
) {
    val active: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
}

/**
 * Cola de descargas. Vive en el proceso para que el servicio en primer plano
 * pueda mantenerla viva aunque la actividad desaparezca.
 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ArrayList<DownloadTask>()
    private val canceled = HashSet<String>()

    private val _state = MutableStateFlow<List<DownloadTask>>(emptyList())
    val state: StateFlow<List<DownloadTask>> = _state

    /** /sdcard/Download/TubeDW/Video o /sdcard/Download/TubeDW/Audio */
    fun dirFor(context: Context, kind: DownloadKind): File {
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "TubeDW"), if (kind == DownloadKind.VIDEO) "Video" else "Audio")
    }

    /** Archivos ya terminados, del más reciente al más antiguo. */
    fun completedIn(context: Context, kind: DownloadKind): List<File> {
        val files = dirFor(context, kind).listFiles() ?: return emptyList()
        return files.filter {
            it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
        }.sortedByDescending { it.lastModified() }
    }

    fun enqueue(
        context: Context,
        videoUrl: String,
        title: String,
        kind: DownloadKind,
        formatLabel: String,
        language: String?,
        selector: String
    ) {
        val app = context.applicationContext
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            videoUrl = videoUrl,
            title = title,
            kind = kind,
            formatLabel = formatLabel,
            language = language,
            selector = selector
        )
        synchronized(tasks) {
            canceled.remove(task.id)
            tasks.add(task)
            publish()
        }
        scope.launch { executeTask(app, task) }
        DownloadService.start(app)
    }

    fun cancel(id: String) {
        synchronized(tasks) {
            canceled.add(id)
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0 && tasks[index].active) {
                tasks[index] = tasks[index].copy(status = DownloadStatus.CANCELED)
                publish()
            }
        }
        YtDlp.cancel(id)
    }

    /** Quita de la lista una descarga fallida o cancelada. */
    fun dismiss(id: String) {
        synchronized(tasks) {
            if (tasks.removeAll { it.id == id }) publish()
        }
    }

    private fun publish() {
        _state.value = tasks.toList()
    }

    private fun mutate(id: String, block: (DownloadTask) -> DownloadTask) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val updated = block(tasks[index])
            if (updated == tasks[index]) return
            tasks[index] = updated
            publish()
        }
    }

    private suspend fun executeTask(app: Context, task: DownloadTask) {
        try {
            YtDlp.ensureInit(app)

            if (isCanceled(task.id)) {
                mutate(task.id) { it.copy(status = DownloadStatus.CANCELED) }
                return
            }

            val dir = dirFor(app, task.kind)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("No se pudo crear ${dir.absolutePath}")
            }

            mutate(task.id) { it.copy(status = DownloadStatus.RUNNING) }

            val request = YoutubeDLRequest(task.videoUrl)
            request.addOption("--ignore-config")
            request.addOption("-f", task.selector)
            request.addOption("-o", File(dir, "%(title)s.%(ext)s").absolutePath)
            request.addOption("--no-playlist")
            request.addOption("--newline")
            // Nada de metadatos, miniaturas ni archivos auxiliares:
            // solo el título como nombre y el archivo de medios.
            request.addOption("--no-embed-metadata")
            request.addOption("--no-embed-thumbnail")
            request.addOption("--no-write-info-json")
            request.addOption("--no-write-description")
            request.addOption("--no-write-comments")
            request.addOption("--no-write-thumbnail")
            request.addOption("--no-write-subs")
            if (task.kind == DownloadKind.VIDEO) {
                request.addOption("--merge-output-format", "mp4")
            }

            YtDlp.execute(request, task.id) { progress, eta, line ->
                val destination = parseDestination(line)
                mutate(task.id) { current ->
                    var next = current
                    if (progress >= 0f && progress != next.progress) {
                        next = next.copy(progress = progress)
                    }
                    if (eta >= 0L && eta != next.eta) next = next.copy(eta = eta)
                    if (destination != null && destination != next.path) {
                        next = next.copy(path = destination)
                    }
                    next
                }
            }

            // La descarga terminó: el archivo ya está en la carpeta, así que
            // fuera de la lista para que aparezca en "Descargas" sin duplicarse.
            synchronized(tasks) {
                canceled.remove(task.id)
                tasks.removeAll { it.id == task.id }
                publish()
            }
        } catch (e: Exception) {
            val wasCanceled = isCanceled(task.id)
            mutate(task.id) {
                if (wasCanceled) {
                    it.copy(status = DownloadStatus.CANCELED)
                } else {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: e.javaClass.simpleName
                    )
                }
            }
        }
    }

    private fun isCanceled(id: String): Boolean = synchronized(canceled) { canceled.contains(id) }

    /** yt-dlp anuncia la ruta final con "Destination:" o "Merging formats into". */
    private fun parseDestination(line: String): String? {
        val text = line.trim()
        val path = when {
            text.startsWith("[download] Destination:") ->
                text.removePrefix("[download] Destination:").trim()
            text.startsWith("[ExtractAudio] Destination:") ->
                text.removePrefix("[ExtractAudio] Destination:").trim()
            text.startsWith("[Merger] Merging formats into") ->
                text.substringAfter('"').substringBeforeLast('"')
            else -> return null
        }
        return path.takeIf { it.startsWith("/") && !it.endsWith(".part") }
    }
}
