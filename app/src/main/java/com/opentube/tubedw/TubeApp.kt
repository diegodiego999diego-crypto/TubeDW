package com.opentube.tubedw

import android.app.Application
import com.opentube.tubedw.data.AppDatabase
import com.opentube.tubedw.data.DownloaderImpl
import com.opentube.tubedw.data.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class TubeApp : Application() {

    lateinit var database: AppDatabase
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Inicializa NewPipe Extractor con nuestro propio Downloader.
        NewPipe.init(DownloaderImpl())
        database = AppDatabase(this)

        // Precarga python, yt-dlp y ffmpeg en segundo plano para que la primera
        // vez que se abra el menú de descarga no haya que esperar, y comprueba
        // si hay una versión nueva de yt-dlp (la incluida envejece rápido).
        appScope.launch {
            try {
                YtDlp.ensureInit(this@TubeApp)
            } catch (e: Exception) {
                // Si falla, se reintenta al usarlo.
            }
            try {
                YtDlp.updateIfNeeded(this@TubeApp)
            } catch (e: Exception) {
                // Sin red: se reintenta en el próximo inicio.
            }
        }
    }
}
