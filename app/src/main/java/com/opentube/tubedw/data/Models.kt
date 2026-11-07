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
    val related: List<VideoItem>
)
