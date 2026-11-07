package com.opentube.tubedw.data

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Punto de acceso a yt-dlp (la misma librería que usa Seal).
 *
 * La inicialización extrae python, yt-dlp y ffmpeg a disco, así que se hace
 * siempre en segundo plano con [ensureInit].
 */
object YtDlp {

    private const val KEY_YTDLP_CHECK = "ytdlp_last_check"

    @Volatile
    private var initialized = false

    private val noop: (Float, Long, String) -> Unit = { _, _, _ -> }

    /** Extrae los binarios si aún no se ha hecho. Bloqueante: usar en IO. */
    suspend fun ensureInit(context: Context) {
        if (initialized) return
        withContext(Dispatchers.IO) {
            // Los init() de la librería ya son idempotentes y sincronizados.
            if (!initialized) {
                val app = context.applicationContext
                FFmpeg.init(app)
                YoutubeDL.init(app)
                ensureExecutable(app)
                initialized = true
            }
        }
    }

    /**
     * python, ffmpeg y quickjs se ejecutan como programas, así que necesitan
     * el bit de ejecución. Algunos gestores de paquetes extraen las .so sin él.
     */
    private fun ensureExecutable(context: Context) {
        try {
            val dir = java.io.File(context.applicationInfo.nativeLibraryDir)
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("lib") && file.name.endsWith(".so")) {
                    file.setExecutable(true, false)
                }
            }
        } catch (e: Exception) {
            // si no se puede, ya vendrá resuelto de fábrica
        }
    }

    /**
     * Ejecuta yt-dlp. Se pasan siempre los tres argumentos porque la librería
     * tiene dos sobrecargas de [YoutubeDL.execute] que se solapan.
     */
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        check(initialized) { "yt-dlp no inicializado" }
        return YoutubeDL.execute(request, processId, callback ?: noop)
    }

    /** Versión real del binario instalado (o la actualizada). */
    suspend fun currentVersion(context: Context): String = withContext(Dispatchers.IO) {
        ensureInit(context)
        try {
            val request = YoutubeDLRequest(emptyList<String>())
            request.addOption("--version")
            val response = execute(request, null, null)
            response.out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: storedVersion(context)
        } catch (e: Exception) {
            storedVersion(context)
        }
    }

    private fun storedVersion(context: Context): String =
        YoutubeDL.versionName(context.applicationContext)
            ?: YoutubeDL.version(context.applicationContext)
            ?: ""

    /**
     * Comprueba si hay una versión nueva de yt-dlp como mucho una vez al día.
     *
     * La versión que viene empaquetada en la APK envejece rápido y YouTube
     * cambia a menudo, así que sin esto las descargas dejarían de funcionar.
     */
    suspend fun updateIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("tubedw", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_YTDLP_CHECK, 0L)
        val dayMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - lastCheck < dayMs) return@withContext

        try {
            ensureInit(app)
            YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            // Solo se marca si no ha fallado, para reintentar en el próximo inicio.
            prefs.edit().putLong(KEY_YTDLP_CHECK, System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // sin conexión: se vuelve a intentar la próxima vez
        }
    }

    /** Descarga la última versión estable de yt-dlp desde GitHub. */
    suspend fun update(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        ensureInit(context)
        try {
            val status = YoutubeDL.updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE
            )
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> UpdateResult.UPDATED
                else -> UpdateResult.UP_TO_DATE
            }
        } catch (e: Exception) {
            UpdateResult.FAILED
        }
    }

    /** Lee todas las calidades disponibles con `yt-dlp -J`. */
    suspend fun fetchFormats(context: Context, url: String): FormatList =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-single-json")
            request.addOption("--no-playlist")
            val response = execute(request, null, null)
            Formats.parse(response.out)
        }

    /** Cancela una descarga en marcha. */
    fun cancel(processId: String) {
        try {
            YoutubeDL.destroyProcessById(processId)
        } catch (e: Exception) {
            // el proceso ya había terminado
        }
    }
}

enum class UpdateResult { UPDATED, UP_TO_DATE, FAILED }
