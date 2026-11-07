package com.opentube.tubedw.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Punto de acceso a los datos. Usa NewPipe Extractor para obtener la información
 * de YouTube. Todas las funciones hacen red, así que deben llamarse desde un
 * hilo de fondo (por ejemplo `withContext(Dispatchers.IO)`).
 */
object Repository {

    const val MIME_DASH = "application/dash+xml"
    const val MIME_HLS = "application/x-mpegURL"

    private val service: StreamingService
        get() = ServiceList.YouTube

    /** Convierte un elemento de lista de NewPipe en un [VideoItem] (solo videos). */
    private fun InfoItem.asVideo(): VideoItem? {
        if (this !is StreamInfoItem) return null
        return VideoItem(
            id = url,
            title = name,
            thumbnail = thumbnails.firstOrNull()?.url,
            uploader = uploaderName,
            uploaderUrl = uploaderUrl,
            duration = duration,
            viewCount = viewCount,
            url = url
        )
    }

    /** Videos en tendencia (feed principal). */
    suspend fun loadTrending(): List<VideoItem> = withContext(Dispatchers.IO) {
        val kioskList = service.kioskList
        val extractor = kioskList.defaultKioskExtractor
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.asVideo() }
    }

    /** Resultados de búsqueda. */
    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.asVideo() }
    }

    /** Videos relacionados con una URL de video (usado para recomendaciones). */
    suspend fun loadRelated(videoUrl: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, videoUrl)
        info.relatedItems.mapNotNull { it.asVideo() }
    }

    /** Información de un canal y sus videos (pestaña "videos"). */
    suspend fun loadChannel(channelUrl: String): Pair<ChannelInfo, List<VideoItem>> =
        withContext(Dispatchers.IO) {
            val extractor = service.getChannelExtractor(channelUrl)
            extractor.fetchPage()

            val info = ChannelInfo(
                id = extractor.id,
                name = extractor.name,
                thumbnail = extractor.avatars.firstOrNull()?.url,
                url = extractor.url
            )

            val videos = try {
                val tab = service.getChannelTabExtractorFromId(extractor.id, "videos")
                tab.fetchPage()
                tab.initialPage.items.mapNotNull { it.asVideo() }
            } catch (e: Exception) {
                emptyList<VideoItem>()
            }

            info to videos
        }

    /** Información completa de un video, lista para reproducir. */
    suspend fun loadStream(videoUrl: String): StreamData = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, videoUrl)
        val source = pickPlaySource(info)

        StreamData(
            id = info.id,
            title = info.name,
            uploader = info.uploaderName,
            uploaderUrl = info.uploaderUrl,
            thumbnail = info.thumbnails.firstOrNull()?.url,
            duration = info.duration,
            viewCount = info.viewCount,
            likeCount = info.likeCount,
            playUrl = source.first,
            playMimeType = source.second,
            related = info.relatedItems.mapNotNull { it.asVideo() }
        )
    }

    /**
     * Elige la fuente de reproducción: primero DASH (mejor calidad, adaptativa),
     * luego HLS y por último un stream progresivo con audio incluido.
     */
    private fun pickPlaySource(info: StreamInfo): Pair<String, String?> {
        val dash = info.dashMpdUrl
        if (!dash.isNullOrEmpty()) {
            return dash to MIME_DASH
        }

        val hls = info.hlsUrl
        if (!hls.isNullOrEmpty()) {
            return hls to MIME_HLS
        }

        val progressive = info.videoStreams.filter { !it.url.isNullOrEmpty() }
        val best = progressive.maxByOrNull { it.height } ?: progressive.firstOrNull()
        return (best?.url ?: "") to null
    }
}
