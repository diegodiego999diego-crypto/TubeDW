package com.opentube.tubedw.data

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Implementación del [Downloader] de NewPipe Extractor basada en [HttpURLConnection],
 * para no tener que añadir OkHttp u otras dependencias de red.
 */
class DownloaderImpl : Downloader() {

    override fun execute(request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.httpMethod()
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true

            // Cabeceras que pidió el extractor
            for ((name, values) in request.headers()) {
                for (value in values) {
                    connection.addRequestProperty(name, value)
                }
            }

            // User-Agent por defecto (navegador) si el extractor no envió uno
            val hasUserAgent = request.headers().keys.any { it.equals("User-Agent", ignoreCase = true) }
            if (!hasUserAgent) {
                connection.setRequestProperty("User-Agent", USER_AGENT)
            }

            val data = request.dataToSend()
            if (data != null && data.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(data) }
            }

            val code = connection.responseCode
            val message = connection.responseMessage ?: ""

            val headers = HashMap<String, List<String>>()
            val rawHeaders = connection.headerFields
            if (rawHeaders != null) {
                for (entry in rawHeaders) {
                    val key = entry.key
                    val value = entry.value
                    if (key != null && value != null) {
                        headers[key] = value.filterNotNull()
                    }
                }
            }

            val body = try {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: IOException) {
                connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: ""
            }

            val latestUrl = connection.url.toString()
            return Response(code, message, headers, body, latestUrl)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
