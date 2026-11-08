package com.opentube.tubedw.data

import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/** Opción de calidad de video (se descarga uniendo video + audio). */
data class VideoOption(
    val height: Int,
    val fps: Int,
    val tbr: Double,
    val ext: String,
    val formatId: String,
    val label: String
)

/** Opción de audio ya independiente (sin video). */
data class AudioOption(
    val formatId: String,
    val bitrate: Int,
    val ext: String,
    val language: String?,
    val bytes: Long,
    val label: String
)

/**
 * Conjunto de opciones de descarga.
 *
 * Lo rellena [Formats.fromNewPipe] con la información que NewPipe Extractor ya
 * ha descargado al abrir el video, así que no hace falta volver a pedirle los
 * formatos a yt-dlp (era lo que hacía que el menú tardase tanto).
 */
data class FormatList(
    val video: List<VideoOption>,
    val audio: List<AudioOption>,
    val languages: List<String>
) {
    /** Audio disponible para un idioma (si no hay, devuelve todos). */
    fun audioFor(language: String?): List<AudioOption> {
        if (language == null) return audio
        val filtered = audio.filter { it.language == language }
        return filtered.ifEmpty { audio }
    }

    /** Mejor audio para un idioma, o null si no hay ninguno. */
    fun bestAudio(language: String?): AudioOption? = audioFor(language).firstOrNull()

    companion object {
        val EMPTY = FormatList(emptyList(), emptyList(), emptyList())
    }
}

object Formats {

    /**
     * Construye las opciones de descarga a partir de la información de NewPipe.
     *
     * @param info ya extraído por el reproductor: no se hace ninguna red.
     */
    fun fromNewPipe(info: StreamInfo): FormatList {
        // height -> mejor stream de video solo (el que se une con el audio)
        val videos = LinkedHashMap<Int, VideoOption>()

        for (stream in info.videoOnlyStreams.orEmpty()) {
            val height = stream.height
            val id = formatIdOf(stream)
            if (height <= 0 || id.isEmpty()) continue
            val current = videos[height]
            if (current == null || isBetterVideo(stream, current)) {
                videos[height] = VideoOption(
                    height = height,
                    fps = stream.fps,
                    tbr = stream.bitrate / 1000.0,
                    ext = "mp4",
                    formatId = id,
                    label = videoLabel(height, stream.fps)
                )
            }
        }

        // Sin DASH se recurre a los progresivos, que ya llevan audio incluido.
        if (videos.isEmpty()) {
            for (stream in info.videoStreams.orEmpty()) {
                val height = stream.height
                val id = formatIdOf(stream)
                if (height <= 0 || id.isEmpty()) continue
                val current = videos[height]
                if (current == null || isBetterVideo(stream, current)) {
                    videos[height] = VideoOption(
                        height = height,
                        fps = stream.fps,
                        tbr = stream.bitrate / 1000.0,
                        ext = "mp4",
                        formatId = id,
                        label = videoLabel(height, stream.fps)
                    )
                }
            }
        }

        // clave (bitrate|ext|idioma) -> mejor stream de audio
        val audios = LinkedHashMap<String, AudioOption>()
        val languages = LinkedHashSet<String>()

        for (stream in info.audioStreams.orEmpty()) {
            val id = formatIdOf(stream)
            if (id.isEmpty()) continue
            val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate
            val codec = stream.codec.orEmpty()
            val ext = if (codec.contains("opus", ignoreCase = true)) "webm" else "m4a"
            val language = audioLanguage(stream)
            val key = "$bitrate|$ext|${language ?: ""}"
            val current = audios[key]
            if (current == null || bitrate > current.bitrate) {
                audios[key] = AudioOption(
                    formatId = id,
                    bitrate = bitrate,
                    ext = ext,
                    language = language,
                    bytes = 0L,
                    label = audioLabel(bitrate, codec, ext)
                )
            }
            if (language != null) languages.add(language)
        }

        return FormatList(
            video = videos.values.sortedByDescending { it.height },
            audio = audios.values.sortedByDescending { it.bitrate },
            languages = languages.sorted()
        )
    }

    /**
     * Id que entiende yt-dlp para YouTube: el itag ("137", "140", "251"…).
     * NewPipe lo guarda como `id` del stream, así que si no viene vacío ya es
     * el identificador exacto que espera yt-dlp.
     */
    private fun formatIdOf(stream: Stream): String {
        val id = stream.id
        return if (id.isNullOrBlank()) "" else id
    }

    /** Idioma del audio, si el stream lo trae ("es", "en-US", "English"…). */
    private fun audioLanguage(stream: AudioStream): String? {
        val locale = stream.audioLocale?.toLanguageTag()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("und", ignoreCase = true) }
        if (locale != null) return locale
        return stream.audioTrackName?.takeIf { it.isNotEmpty() }
    }

    /** Prefiere más fps, luego más bitrate. */
    private fun isBetterVideo(stream: VideoStream, current: VideoOption): Boolean {
        if (stream.fps != current.fps) return stream.fps > current.fps
        return stream.bitrate / 1000.0 > current.tbr
    }

    fun videoLabel(height: Int, fps: Int): String =
        if (fps > 30) "${height}p$fps" else "${height}p"

    private fun audioLabel(bitrate: Int, codec: String, ext: String): String {
        val kind = when {
            codec.contains("opus", ignoreCase = true) -> "opus"
            codec.contains("mp4a", ignoreCase = true) -> "aac"
            codec.contains("vorbis", ignoreCase = true) -> "vorbis"
            codec.isEmpty() -> ext
            else -> ext
        }
        return if (bitrate > 0) "$bitrate kbps · $kind" else kind
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 ->
            String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 ->
            String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 ->
            String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    /** Nombre legible de un idioma tipo "en", "es", "pt-BR". */
    fun languageName(tag: String): String = try {
        val locale = Locale(tag.replace('-', '_'))
        locale.getDisplayLanguage(locale).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
        }
    } catch (e: Exception) {
        tag
    }
}
