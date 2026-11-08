package com.opentube.tubedw.data

import android.content.Context
import android.os.Environment
import com.opentube.tubedw.download.DownloadService
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELED, PAUSED }

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
    val error: String? = null,
    /** Reintentos automáticos ya consumidos en esta sesión. */
    val attempts: Int = 0,
    /** Si el arranque actual continuó desde archivos parciales. */
    val resumed: Boolean = false,
    /** Destinos que yt-dlp ha anunciado: sirven para localizar los `.part`. */
    val parts: List<String> = emptyList()
) {
    /** Está en marcha o esperando turno. */
    val active: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    /** Se muestra en la zona de descargas en curso (incluye las pausadas). */
    val inQueue: Boolean
        get() = active || status == DownloadStatus.PAUSED
}

/**
 * Cola de descargas. Vive en el proceso para que el servicio en primer plano
 * pueda mantenerla viva aunque la actividad desaparezca.
 *
 * La cola se guarda en disco: si la app se cierra o una descarga falla, al
 * volver a abrirla se recuperan la URL y las opciones elegidas y se reanudan
 * desde donde quedaron (o desde cero si reanudar ya no es posible).
 */
object DownloadManager {

    /** Reintentos automáticos antes de dejar la descarga en «fallida». */
    private const val MAX_AUTO_RETRIES = 6
    private const val PREFS = "tubedw"
    private const val KEY_QUEUE = "download_queue"

    /** Archivos temporales de un formato concreto, tipo `Título.f137.mp4`. */
    private val FORMAT_FILE = Regex(""".*\.f\d+\.[A-Za-z0-9]+$""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ArrayList<DownloadTask>()
    private val canceled = HashSet<String>()
    private val paused = HashSet<String>()

    @Volatile
    private var appContext: Context? = null

    private val _state = MutableStateFlow<List<DownloadTask>>(emptyList())
    val state: StateFlow<List<DownloadTask>> = _state

    private fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** /sdcard/Download/TubeDW/Video o /sdcard/Download/TubeDW/Audio */
    fun dirFor(context: Context, kind: DownloadKind): File {
        attach(context)
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
        attach(app)
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
            paused.remove(task.id)
            tasks.add(task)
            publish()
            persist()
        }
        scope.launch { executeTask(app, task) }
        DownloadService.start(app)
    }

    /** Detiene la descarga sin borrar nada: los `.part` se quedan para luego. */
    fun pause(id: String) {
        synchronized(tasks) {
            paused.add(id)
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0 && tasks[index].status == DownloadStatus.QUEUED) {
                tasks[index] = tasks[index].copy(status = DownloadStatus.PAUSED)
                publish()
                persist()
            }
        }
        // Si el proceso ya corre, se corta y el "catch" lo deja en pausa;
        // si aún no existía, executeTask lo verá al arrancar.
        YtDlp.cancel(id)
    }

    /** Continúa una descarga pausada o reintentará una fallida o cancelada. */
    fun resume(id: String) {
        val app = appContext ?: return
        val task = synchronized(tasks) {
            paused.remove(id)
            canceled.remove(id)
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = tasks[index]
            if (current.status == DownloadStatus.QUEUED ||
                current.status == DownloadStatus.RUNNING ||
                current.status == DownloadStatus.DONE
            ) return

            val updated = current.copy(
                status = DownloadStatus.QUEUED,
                attempts = 0,
                resumed = false,
                error = null
            )
            tasks[index] = updated
            publish()
            persist()
            updated
        }
        scope.launch { executeTask(app, task) }
        DownloadService.start(app)
    }

    fun cancel(id: String) {
        synchronized(tasks) {
            canceled.add(id)
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0 && tasks[index].inQueue) {
                tasks[index] = tasks[index].copy(status = DownloadStatus.CANCELED)
                publish()
                persist()
            }
        }
        YtDlp.cancel(id)
    }

    /** Quita de la lista una descarga fallida o cancelada. */
    fun dismiss(id: String) {
        synchronized(tasks) {
            canceled.remove(id)
            paused.remove(id)
            if (tasks.removeAll { it.id == id }) {
                publish()
                persist()
            }
        }
    }

    /**
     * Recupera de disco las descargas que no terminaron y las arranca otra vez.
     * Se llama al abrir la app.
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        attach(app)
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, null)
        if (raw.isNullOrEmpty()) return

        val restored = ArrayList<DownloadTask>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val task = taskFromJson(obj) ?: continue
                if (task.status == DownloadStatus.DONE ||
                    task.status == DownloadStatus.CANCELED
                ) continue
                restored.add(task)
            }
        } catch (e: Exception) {
            return
        }
        if (restored.isEmpty()) return

        synchronized(tasks) {
            restored.forEach { incoming ->
                if (tasks.none { it.id == incoming.id }) tasks.add(incoming)
            }
            publish()
        }
        persist()

        // Las pausadas se quedan como están (fue decisión del usuario);
        // el resto vuelve a ponerse en marcha.
        val toStart = restored.filter { it.status != DownloadStatus.PAUSED }
        if (toStart.isEmpty()) return
        toStart.forEach { incoming -> scope.launch { executeTask(app, incoming) } }
        DownloadService.start(app)
    }

    /* ------------------------------------------------------------------ */
    /* Ejecución                                                           */
    /* ------------------------------------------------------------------ */

    private suspend fun executeTask(app: Context, task: DownloadTask) {
        try {
            YtDlp.ensureInit(app)

            val current = synchronized(tasks) { tasks.firstOrNull { it.id == task.id } } ?: return

            if (isCanceled(task.id)) {
                mutate(task.id) { it.copy(status = DownloadStatus.CANCELED) }
                return
            }
            if (isPaused(task.id)) {
                mutate(task.id) { it.copy(status = DownloadStatus.PAUSED) }
                return
            }

            val dir = dirFor(app, current.kind)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("No se pudo crear ${dir.absolutePath}")
            }

            // ¿Quedan descargas parciales? Entonces este arranque continúa
            // desde donde se quedó; si no, empieza desde cero.
            val willResume = hasPartialFiles(current)
            mutate(task.id) {
                it.copy(status = DownloadStatus.RUNNING, resumed = willResume, error = null)
            }

            val request = YoutubeDLRequest(current.videoUrl)
            request.addOption("--ignore-config")
            request.addOption("-f", current.selector)
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
            if (current.kind == DownloadKind.VIDEO) {
                request.addOption("--merge-output-format", "mp4")
            }

            var pauseAsked = false
            YtDlp.execute(request, task.id) { progress, eta, line ->
                // Si se pidió pausar mientras el proceso arrancaba, se corta aquí.
                if (!pauseAsked && isPaused(task.id)) {
                    pauseAsked = true
                    YtDlp.cancel(task.id)
                }
                val destination = parseDestination(line)
                mutate(task.id) { state ->
                    var next = state
                    if (progress >= 0f && progress != next.progress) {
                        next = next.copy(progress = progress)
                    }
                    if (eta >= 0L && eta != next.eta) next = next.copy(eta = eta)
                    if (destination != null && !next.parts.contains(destination)) {
                        next = next.copy(parts = next.parts + destination)
                    }
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
                paused.remove(task.id)
                tasks.removeAll { it.id == task.id }
                publish()
            }
            persist()
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            when {
                isCanceled(task.id) ->
                    mutate(task.id) { it.copy(status = DownloadStatus.CANCELED) }
                isPaused(task.id) ->
                    mutate(task.id) { it.copy(status = DownloadStatus.PAUSED) }
                else -> scheduleRetry(app, task.id, message)
            }
        }
    }

    /**
     * Reintenta solo, sin cancelar nunca la descarga.
     *
     * Si el intento que acaba de fallar era una reanudación, se borran los
     * parciales y el siguiente arranca desde cero; si era un arranque limpio,
     * se dejan los parciales para que el siguiente continúe donde quedó.
     */
    private fun scheduleRetry(app: Context, id: String, message: String) {
        var attempt = 0
        var giveUp = false

        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val current = tasks[index]

            if (current.resumed) deletePartials(current)

            attempt = current.attempts + 1
            giveUp = attempt > MAX_AUTO_RETRIES
            tasks[index] = current.copy(
                status = if (giveUp) DownloadStatus.FAILED else DownloadStatus.QUEUED,
                attempts = attempt,
                resumed = false,
                error = message
            )
            publish()
            persist()
        }

        if (giveUp) return

        val waitMs = (3000L * attempt).coerceAtMost(60_000L)
        scope.launch {
            delay(waitMs)
            val current = synchronized(tasks) { tasks.firstOrNull { it.id == id } } ?: return@launch
            if (current.status != DownloadStatus.QUEUED) return@launch
            if (isCanceled(id) || isPaused(id)) return@launch
            executeTask(app, current)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Archivos parciales                                                  */
    /* ------------------------------------------------------------------ */

    private fun hasPartialFiles(task: DownloadTask): Boolean = task.parts.any { base ->
        File("$base.part").exists() || File("$base.ytdl").exists()
    }

    /** Borra lo que quedó a medias para forzar un arranque desde cero. */
    private fun deletePartials(task: DownloadTask) {
        task.parts.forEach { base ->
            try {
                File("$base.part").delete()
                File("$base.ytdl").delete()
                // Los ficheros intermedios por formato también cuentan como
                // «a medias» para el usuario: si no se borran, yt-dlp los
                // reutilizaría y no sería un reinicio real.
                if (FORMAT_FILE.matches(base)) File(base).delete()
            } catch (e: Exception) {
                // si algo no se puede borrar, el reintento seguirá como antes
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Estado                                                              */
    /* ------------------------------------------------------------------ */

    private fun publish() {
        _state.value = tasks.toList()
    }

    private fun mutate(id: String, block: (DownloadTask) -> DownloadTask) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val previous = tasks[index]
            val updated = block(previous)
            if (updated == previous) return
            tasks[index] = updated
            publish()
            // Solo se toca disco cuando cambia algo que interesa recuperar
            // después (estado o destinos), nunca en cada tick de progreso.
            if (updated.status != previous.status || updated.parts != previous.parts) persist()
        }
    }

    private fun isCanceled(id: String): Boolean = synchronized(canceled) { canceled.contains(id) }

    private fun isPaused(id: String): Boolean = synchronized(paused) { paused.contains(id) }

    /* ------------------------------------------------------------------ */
    /* Persistencia                                                        */
    /* ------------------------------------------------------------------ */

    private fun persist() {
        val app = appContext ?: return
        val array = JSONArray()
        synchronized(tasks) {
            tasks.forEach { array.put(taskToJson(it)) }
        }
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_QUEUE, array.toString())
                .apply()
        } catch (e: Exception) {
            // sin almacenamiento no se puede guardar, pero nada más falla
        }
    }

    private fun taskToJson(task: DownloadTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("url", task.videoUrl)
        put("title", task.title)
        put("kind", task.kind.name)
        put("label", task.formatLabel)
        put("language", task.language ?: JSONObject.NULL)
        put("selector", task.selector)
        put("status", task.status.name)
        put("path", task.path ?: JSONObject.NULL)
        put("parts", JSONArray(task.parts))
    }

    private fun taskFromJson(obj: JSONObject): DownloadTask? {
        val id = obj.optString("id")
        val url = obj.optString("url")
        val selector = obj.optString("selector")
        if (id.isEmpty() || url.isEmpty() || selector.isEmpty()) return null

        val status = try {
            DownloadStatus.valueOf(obj.optString("status"))
        } catch (e: Exception) {
            DownloadStatus.QUEUED
        }

        val parts = ArrayList<String>()
        obj.optJSONArray("parts")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i)?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
        }

        return DownloadTask(
            id = id,
            videoUrl = url,
            title = obj.optString("title"),
            kind = if (obj.optString("kind") == DownloadKind.AUDIO.name) {
                DownloadKind.AUDIO
            } else {
                DownloadKind.VIDEO
            },
            formatLabel = obj.optString("label"),
            language = obj.optString("language")
                .takeIf { it.isNotEmpty() && it != "null" },
            selector = selector,
            status = status,
            path = obj.optString("path").takeIf { it.isNotEmpty() && it != "null" },
            // Cada arranque de la app vuelve a tener los reintentos completos.
            attempts = 0,
            parts = parts
        )
    }

    /* ------------------------------------------------------------------ */
    /* Salida de yt-dlp                                                     */
    /* ------------------------------------------------------------------ */

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
        // Los parciales se guardan sin el sufijo para poder reconstruirlos.
        return path.removeSuffix(".part")
            .takeIf { it.startsWith("/") && it.isNotEmpty() }
    }
}
