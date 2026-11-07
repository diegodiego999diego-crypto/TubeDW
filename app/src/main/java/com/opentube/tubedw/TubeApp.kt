package com.opentube.tubedw

import android.app.Application
import com.opentube.tubedw.data.AppDatabase
import com.opentube.tubedw.data.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe

class TubeApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        // Inicializa NewPipe Extractor con nuestro propio Downloader.
        NewPipe.init(DownloaderImpl())
        database = AppDatabase(this)
    }
}
