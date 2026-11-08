package com.opentube.tubedw.data

/**
 * Modelo de un video tal como lo usa la interfaz.
 */
data class VideoItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val uploader: String?,
    val uploaderUrl: String?,
    val duration: Long,
    val viewCount: Long,
    val url: String
)

/**
 * Información básica de un canal.
 */
data class ChannelInfo(
    val id: String,
    val name: String,
    val thumbnail: String?,
    val url: String
)

/**
 * Calidad que se puede elegir al reproducir.
 *
 * [url] solo tiene valor cuando la fuente es progresiva (video con audio ya
 * unido); si es null la fuente es adaptativa (DASH/HLS) y la calidad se aplica
 * limitando el reproductor, sin recargar el video.
 */
data class QualityOption(
    val height: Int,
    val fps: Int,
    val label: String,
    val url: String? = null,
    val mimeType: String? = null
)

/**
 * Datos listos para reproducir un video.
 */
data class StreamData(
    val id: String,
    val title: String,
    val uploader: String?,
    val uploaderUrl: String?,
    val thumbnail: String?,
    val duration: Long,
    val viewCount: Long,
    val likeCount: Long,
    val playUrl: String,
    val playMimeType: String?,
    /** Calidades e idiomas disponibles, ya extraídos con NewPipe Extractor. */
    val formats: FormatList,
    /** Calidades que se pueden elegir mientras se reproduce. */
    val qualities: List<QualityOption>,
    val related: List<VideoItem>
)
