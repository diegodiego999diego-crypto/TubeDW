package com.opentube.tubedw.data

import org.json.JSONArray
import org.json.JSONObject
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

/** Conjunto de opciones extraído de `yt-dlp -J`. */
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
}

object Formats {

    fun parse(json: String): FormatList {
        val root = JSONObject(json)
        val array: JSONArray = root.optJSONArray("formats") ?: JSONArray()

        // height -> mejor formato de video solo
        val videos = HashMap<Int, VideoOption>()
        // clave (bitrate|ext|idioma) -> mejor formato de audio
        val audios = LinkedHashMap<String, AudioOption>()
        val languages = LinkedHashSet<String>()

        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            val id = f.optString("format_id")
            if (id.isEmpty()) continue
            // storyboards y otros formatos que no son medios
            if (id.startsWith("sb") || id.startsWith("nn")) continue
            val ext = f.optString("ext")
            if (ext == "mhtml") continue

            val vcodec = f.optString("vcodec")
            val acodec = f.optString("acodec")
            val hasVideo = vcodec.isNotEmpty() && vcodec != "none"
            val hasAudio = acodec.isNotEmpty() && acodec != "none"
            val language = f.optString("language")
                .takeIf { it.isNotEmpty() && it != "und" }

            if (hasVideo && !hasAudio) {
                // Stream de video solo: se une después con el audio elegido.
                val height = f.optInt("height", 0)
                if (height > 0) {
                    val fps = f.optInt("fps", 0)
                    val tbr = f.optDouble("tbr", 0.0)
                    val current = videos[height]
                    if (current == null || isBetterVideo(fps, tbr, ext, current)) {
                        videos[height] = VideoOption(
                            height = height,
                            fps = fps,
                            tbr = tbr,
                            ext = ext,
                            formatId = id,
                            label = videoLabel(height, fps)
                        )
                    }
                }
            } else if (!hasVideo && hasAudio) {
                // Stream de audio solo: se puede guardar tal cual.
                val bitrate = f.optInt("abr", 0).let {
                    if (it > 0) it else f.optInt("tbr", 0)
                }
                val bytes = f.optLong("filesize", 0L).let {
                    if (it > 0) it else f.optLong("filesize_approx", 0L)
                }
                val key = "$bitrate|$ext|${language ?: ""}"
                val current = audios[key]
                if (current == null || bytes > current.bytes) {
                    audios[key] = AudioOption(
                        formatId = id,
                        bitrate = bitrate,
                        ext = ext,
                        language = language,
                        bytes = bytes,
                        label = audioLabel(bitrate, ext, acodec, bytes)
                    )
                }
                if (language != null) languages.add(language)
            }
        }

        val videoList = videos.values.sortedByDescending { it.height }
        val audioList = audios.values.sortedWith(
            compareByDescending<AudioOption> { it.bitrate }.thenBy { it.ext }
        )

        return FormatList(
            video = videoList,
            audio = audioList,
            languages = languages.sorted()
        )
    }

    /** Prefiere más fps, luego contenedor mp4, luego más bitrate. */
    private fun isBetterVideo(fps: Int, tbr: Double, ext: String, current: VideoOption): Boolean {
        if (fps != current.fps) return fps > current.fps
        val thisMp4 = ext == "mp4"
        val currentMp4 = current.ext == "mp4"
        if (thisMp4 != currentMp4) return thisMp4
        return tbr > current.tbr
    }

    private fun videoLabel(height: Int, fps: Int): String =
        if (fps > 30) "${height}p$fps" else "${height}p"

    private fun audioLabel(bitrate: Int, ext: String, acodec: String, bytes: Long): String {
        val codec = when {
            acodec.contains("opus", ignoreCase = true) -> "opus"
            acodec.contains("mp4a", ignoreCase = true) -> "aac"
            acodec.contains("vorbis", ignoreCase = true) -> "vorbis"
            else -> ext
        }
        val parts = mutableListOf<String>()
        if (bitrate > 0) parts.add("$bitrate kbps") else parts.add(codec)
        if (bitrate > 0) parts.add(codec)
        if (bytes > 0) parts.add(formatSize(bytes))
        return parts.joinToString(" · ")
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
