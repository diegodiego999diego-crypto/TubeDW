package com.opentube.tubedw.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.opentube.tubedw.R
import com.opentube.tubedw.TubeApp
import com.opentube.tubedw.data.AppDatabase
import com.opentube.tubedw.data.AudioOption
import com.opentube.tubedw.data.DownloadKind
import com.opentube.tubedw.data.DownloadManager
import com.opentube.tubedw.data.FormatList
import com.opentube.tubedw.data.Formats
import com.opentube.tubedw.data.Repository
import com.opentube.tubedw.data.StreamData
import com.opentube.tubedw.data.UpdateResult
import com.opentube.tubedw.data.VideoItem
import com.opentube.tubedw.data.VideoOption
import com.opentube.tubedw.data.YtDlp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private enum class Tab { HOME, LIKES, DOWNLOADS }

    private lateinit var db: AppDatabase

    private lateinit var adapter: VideoAdapter
    private lateinit var relatedAdapter: VideoAdapter
    private lateinit var likesAdapter: VideoAdapter
    private lateinit var downloadAdapter: DownloadAdapter

    private lateinit var feedScreen: View
    private lateinit var likesScreen: View
    private lateinit var downloadsScreen: View
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var searchButton: ImageButton

    private lateinit var channelHeader: View
    private lateinit var channelBack: ImageButton
    private lateinit var channelThumb: ImageView
    private lateinit var channelName: TextView

    private lateinit var feedList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView

    private lateinit var likesList: RecyclerView
    private lateinit var likesEmpty: TextView
    private lateinit var downloadsList: RecyclerView
    private lateinit var downloadsEmpty: TextView

    private lateinit var playerFull: View
    private lateinit var playerHeader: View
    private lateinit var btnCollapse: ImageButton
    private lateinit var playerView: PlayerView
    private lateinit var videoTitle: TextView
    private lateinit var videoChannel: TextView
    private lateinit var btnLike: View
    private lateinit var btnChannel: View
    private lateinit var btnDownload: View
    private lateinit var likeIcon: ImageView
    private lateinit var likeText: TextView
    private lateinit var relatedList: RecyclerView

    private lateinit var miniPlayer: View
    private lateinit var miniBody: View
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniClose: ImageButton

    // Menú de descarga
    private lateinit var sheetScrim: View
    private lateinit var downloadSheet: View
    private lateinit var sheetTitle: TextView
    private lateinit var sheetColumns: View
    private lateinit var sheetLoading: ProgressBar
    private lateinit var sheetError: TextView
    private lateinit var videoOptions: android.widget.LinearLayout
    private lateinit var audioOptions: android.widget.LinearLayout
    private lateinit var videoEmpty: TextView
    private lateinit var audioEmpty: TextView
    private lateinit var videoLangRow: View
    private lateinit var audioLangRow: View
    private lateinit var videoLangSpinner: Spinner
    private lateinit var audioLangSpinner: Spinner
    private lateinit var videoLangText: TextView
    private lateinit var audioLangText: TextView

    // Ajustes
    private lateinit var settingsScreen: View
    private lateinit var settingsYtdlpVersion: TextView
    private lateinit var settingsBatteryState: TextView
    private lateinit var settingsNotifyState: TextView
    private lateinit var btnUpdateYtdlp: TextView
    private lateinit var btnBattery: TextView
    private lateinit var btnNotify: TextView

    private var exoPlayer: ExoPlayer? = null
    private var currentStream: StreamData? = null
    private var currentItem: VideoItem? = null
    private var startDownY = 0f

    private var currentTab = Tab.HOME
    private var sheetFormats: FormatList? = null
    private var sheetVideoLang: String? = null
    private var sheetAudioLang: String? = null
    private var sheetJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = (application as TubeApp).database
        bindViews()
        setupList()
        setupSearch()
        setupGestures()
        setupOptionButtons()
        setupTabs()
        setupDownloadSheet()
        setupSettings()
        setupBackButton()

        lifecycleScope.launch {
            DownloadManager.state.collect {
                if (downloadsScreen.visibility == View.VISIBLE) refreshDownloads()
            }
        }

        loadFeed()
    }

    /* ------------------------------------------------------------------ */
    /* Configuración inicial                                              */
    /* ------------------------------------------------------------------ */

    private fun bindViews() {
        feedScreen = findViewById(R.id.feedScreen)
        likesScreen = findViewById(R.id.likesScreen)
        downloadsScreen = findViewById(R.id.downloadsScreen)
        bottomNav = findViewById(R.id.bottomNav)

        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchButton = findViewById(R.id.searchButton)

        channelHeader = findViewById(R.id.channelHeader)
        channelBack = findViewById(R.id.channelBack)
        channelThumb = findViewById(R.id.channelThumb)
        channelName = findViewById(R.id.channelName)

        feedList = findViewById(R.id.feedList)
        loading = findViewById(R.id.loading)
        errorText = findViewById(R.id.errorText)

        likesList = findViewById(R.id.likesList)
        likesEmpty = findViewById(R.id.likesEmpty)
        downloadsList = findViewById(R.id.downloadsList)
        downloadsEmpty = findViewById(R.id.downloadsEmpty)

        playerFull = findViewById(R.id.playerFull)
        playerHeader = findViewById(R.id.playerHeader)
        btnCollapse = findViewById(R.id.btnCollapse)
        playerView = findViewById(R.id.playerView)
        videoTitle = findViewById(R.id.videoTitle)
        videoChannel = findViewById(R.id.videoChannel)
        btnLike = findViewById(R.id.btnLike)
        btnChannel = findViewById(R.id.btnChannel)
        btnDownload = findViewById(R.id.btnDownload)
        likeIcon = findViewById(R.id.likeIcon)
        likeText = findViewById(R.id.likeText)
        relatedList = findViewById(R.id.relatedList)

        miniPlayer = findViewById(R.id.miniPlayer)
        miniBody = findViewById(R.id.miniBody)
        miniThumb = findViewById(R.id.miniThumb)
        miniTitle = findViewById(R.id.miniTitle)
        miniPlayPause = findViewById(R.id.miniPlayPause)
        miniClose = findViewById(R.id.miniClose)

        sheetScrim = findViewById(R.id.sheetScrim)
        downloadSheet = findViewById(R.id.downloadSheet)
        sheetTitle = findViewById(R.id.sheetTitle)
        sheetColumns = findViewById(R.id.sheetColumns)
        sheetLoading = findViewById(R.id.sheetLoading)
        sheetError = findViewById(R.id.sheetError)
        videoOptions = findViewById(R.id.videoOptions)
        audioOptions = findViewById(R.id.audioOptions)
        videoEmpty = findViewById(R.id.videoEmpty)
        audioEmpty = findViewById(R.id.audioEmpty)
        videoLangRow = findViewById(R.id.videoLangRow)
        audioLangRow = findViewById(R.id.audioLangRow)
        videoLangSpinner = findViewById(R.id.videoLangSpinner)
        audioLangSpinner = findViewById(R.id.audioLangSpinner)
        videoLangText = findViewById(R.id.videoLangText)
        audioLangText = findViewById(R.id.audioLangText)

        settingsScreen = findViewById(R.id.settingsScreen)
        settingsYtdlpVersion = findViewById(R.id.settingsYtdlpVersion)
        settingsBatteryState = findViewById(R.id.settingsBatteryState)
        settingsNotifyState = findViewById(R.id.settingsNotifyState)
        btnUpdateYtdlp = findViewById(R.id.btnUpdateYtdlp)
        btnBattery = findViewById(R.id.btnBattery)
        btnNotify = findViewById(R.id.btnNotify)
    }

    private fun setupList() {
        adapter = VideoAdapter { item -> openVideo(item) }
        feedList.layoutManager = LinearLayoutManager(this)
        feedList.adapter = adapter

        relatedAdapter = VideoAdapter { item -> openVideo(item) }
        relatedList.layoutManager = LinearLayoutManager(this)
        relatedList.adapter = relatedAdapter

        likesAdapter = VideoAdapter { item -> openVideo(item) }
        likesList.layoutManager = LinearLayoutManager(this)
        likesList.adapter = likesAdapter

        downloadAdapter = DownloadAdapter(
            onCancel = { task -> DownloadManager.cancel(task.id) },
            onDismiss = { task -> DownloadManager.dismiss(task.id) },
            onOpen = { file -> openDownloadedFile(file) }
        )
        downloadsList.layoutManager = LinearLayoutManager(this)
        downloadsList.adapter = downloadAdapter
    }

    private fun setupSearch() {
        searchButton.setOnClickListener { performSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Deslizar hacia abajo sobre el reproductor -> minimizar al mini player.
        playerHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startDownY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (event.y - startDownY > 60f) collapsePlayer()
                    true
                }
                else -> false
            }
        }

        // Deslizar hacia arriba (o tocar) el mini player -> volver al reproductor.
        miniBody.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startDownY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = event.y - startDownY
                    if (dy < -60f || abs(dy) < 20f) expandPlayer()
                    true
                }
                else -> false
            }
        }

        btnCollapse.setOnClickListener { collapsePlayer() }
        miniClose.setOnClickListener { closePlayer() }
        miniPlayPause.setOnClickListener { togglePlayPause() }
    }

    private fun setupOptionButtons() {
        btnLike.setOnClickListener { toggleLike() }
        btnChannel.setOnClickListener { openCurrentChannel() }
        videoChannel.setOnClickListener { openCurrentChannel() }
        btnDownload.setOnClickListener { openDownloadSheet() }
        channelBack.setOnClickListener { loadFeed() }
        findViewById<View>(R.id.settingsGear).setOnClickListener { openSettings() }
    }

    private fun setupTabs() {
        bottomNav.setOnItemSelectedListener { item ->
            val tab = when (item.itemId) {
                R.id.nav_likes -> Tab.LIKES
                R.id.nav_downloads -> Tab.DOWNLOADS
                else -> Tab.HOME
            }
            if (currentTab != tab) showTab(tab)
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> feedList.scrollToPosition(0)
                R.id.nav_likes -> refreshLikes()
                R.id.nav_downloads -> refreshDownloads()
            }
        }
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        feedScreen.visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        likesScreen.visibility = if (tab == Tab.LIKES) View.VISIBLE else View.GONE
        downloadsScreen.visibility = if (tab == Tab.DOWNLOADS) View.VISIBLE else View.GONE

        val itemId = when (tab) {
            Tab.HOME -> R.id.nav_home
            Tab.LIKES -> R.id.nav_likes
            Tab.DOWNLOADS -> R.id.nav_downloads
        }
        if (bottomNav.selectedItemId != itemId) bottomNav.selectedItemId = itemId

        when (tab) {
            Tab.LIKES -> refreshLikes()
            Tab.DOWNLOADS -> refreshDownloads()
            else -> Unit
        }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    downloadSheet.visibility == View.VISIBLE -> closeDownloadSheet()
                    settingsScreen.visibility == View.VISIBLE -> closeSettings()
                    playerFull.visibility == View.VISIBLE -> collapsePlayer()
                    channelHeader.visibility == View.VISIBLE -> loadFeed()
                    currentTab != Tab.HOME -> showTab(Tab.HOME)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    /* ------------------------------------------------------------------ */
    /* Feed / búsqueda / canal                                            */
    /* ------------------------------------------------------------------ */

    private fun loadFeed() {
        lifecycleScope.launch {
            showLoading(true)
            errorText.visibility = View.GONE
            try {
                val trending = Repository.loadTrending()

                // Recomendaciones a partir del último video visto.
                val history = withContext(Dispatchers.IO) { db.getHistory() }
                val recommendations: List<VideoItem> = if (history.isNotEmpty()) {
                    try {
                        Repository.loadRelated(history.first().url)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                val combined = (recommendations + trending).distinctBy { it.url }
                showChannelMode(false)
                adapter.submit(combined)
                feedList.scrollToPosition(0)
            } catch (e: Exception) {
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.error_feed)
            } finally {
                showLoading(false)
            }
        }
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        hideKeyboard()
        lifecycleScope.launch {
            showLoading(true)
            errorText.visibility = View.GONE
            try {
                val results = Repository.search(query)
                showChannelMode(false)
                adapter.submit(results)
                feedList.scrollToPosition(0)
            } catch (e: Exception) {
                showToast(getString(R.string.error_feed))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun openChannel(url: String) {
        lifecycleScope.launch {
            showLoading(true)
            errorText.visibility = View.GONE
            try {
                val (info, videos) = Repository.loadChannel(url)
                withContext(Dispatchers.IO) { db.saveChannel(info) }
                showChannelMode(true)
                channelName.text = info.name
                channelThumb.load(info.thumbnail)
                adapter.submit(videos)
                feedList.scrollToPosition(0)
            } catch (e: Exception) {
                showToast(getString(R.string.error_feed))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun openCurrentChannel() {
        val url = currentStream?.uploaderUrl ?: currentItem?.uploaderUrl
        if (url.isNullOrEmpty()) {
            showToast(getString(R.string.channel_unavailable))
            return
        }
        // El reproductor se minimiza y el canal queda visible sobre el feed.
        collapsePlayer()
        showTab(Tab.HOME)
        openChannel(url)
    }

    private fun showChannelMode(on: Boolean) {
        searchBar.visibility = if (on) View.GONE else View.VISIBLE
        channelHeader.visibility = if (on) View.VISIBLE else View.GONE
    }

    /* ------------------------------------------------------------------ */
    /* Reproductor                                                         */
    /* ------------------------------------------------------------------ */

    private fun openVideo(item: VideoItem) {
        currentItem = item
        lifecycleScope.launch {
            showLoading(true)
            try {
                val stream = Repository.loadStream(item.url)
                if (stream.playUrl.isEmpty()) {
                    showToast(getString(R.string.error_video))
                    return@launch
                }
                currentStream = stream
                val liked = withContext(Dispatchers.IO) { db.isLiked(item.url) }
                withContext(Dispatchers.IO) { db.saveHistory(item) }
                showPlayer(stream, liked)
            } catch (e: Exception) {
                showToast(getString(R.string.error_video))
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showPlayer(stream: StreamData, liked: Boolean) {
        videoTitle.text = stream.title
        videoChannel.text = stream.uploader ?: ""
        miniTitle.text = stream.title
        miniThumb.load(stream.thumbnail)

        relatedAdapter.submit(stream.related)
        updateLikeButton(liked)
        setupPlayer(stream)
        updatePlayPauseIcon(true)

        playerFull.visibility = View.VISIBLE
        miniPlayer.visibility = View.GONE
        playerFull.alpha = 1f
    }

    private fun setupPlayer(stream: StreamData) {
        releasePlayer()

        val httpFactory = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        val builder = MediaItem.Builder().setUri(stream.playUrl)
        stream.playMimeType?.let { builder.setMimeType(it) }
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
            }
        })

        playerView.player = player
        exoPlayer = player
    }

    private fun releasePlayer() {
        playerView.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        miniPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toggleLike() {
        val item = currentItem ?: return
        lifecycleScope.launch {
            val liked = withContext(Dispatchers.IO) {
                if (db.isLiked(item.url)) {
                    db.removeLike(item.url)
                    false
                } else {
                    db.saveLike(item)
                    true
                }
            }
            updateLikeButton(liked)
            if (currentTab == Tab.LIKES) refreshLikes()
        }
    }

    private fun updateLikeButton(liked: Boolean) {
        if (liked) {
            likeIcon.setColorFilter(getColor(R.color.red))
            likeText.text = getString(R.string.liked)
            likeText.setTextColor(getColor(R.color.red))
        } else {
            likeIcon.setColorFilter(getColor(R.color.black))
            likeText.text = getString(R.string.like)
            likeText.setTextColor(getColor(R.color.black))
        }
    }

    /* ------------------------------------------------------------------ */
    /* Mini player / gestos de pantalla                                    */
    /* ------------------------------------------------------------------ */

    private fun collapsePlayer() {
        if (playerFull.visibility != View.VISIBLE) return
        miniPlayer.visibility = View.VISIBLE
        miniPlayer.alpha = 0f
        miniPlayer.animate().alpha(1f).setDuration(150).start()

        playerFull.animate().alpha(0f).setDuration(150).withEndAction {
            playerFull.visibility = View.GONE
            playerFull.alpha = 1f
        }.start()
    }

    private fun expandPlayer() {
        playerFull.visibility = View.VISIBLE
        playerFull.alpha = 0f
        playerFull.animate().alpha(1f).setDuration(150).start()

        miniPlayer.animate().alpha(0f).setDuration(150).withEndAction {
            miniPlayer.visibility = View.GONE
            miniPlayer.alpha = 1f
        }.start()
    }

    private fun closePlayer() {
        releasePlayer()
        currentStream = null
        currentItem = null
        playerFull.visibility = View.GONE
        miniPlayer.visibility = View.GONE
    }

    /* ------------------------------------------------------------------ */
    /* Pestañas                                                            */
    /* ------------------------------------------------------------------ */

    private fun refreshLikes() {
        lifecycleScope.launch {
            val likes = withContext(Dispatchers.IO) { db.getLikes() }
            likesAdapter.submit(likes)
            likesEmpty.visibility = if (likes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private var downloadsSignature: String? = null
    private var cachedFiles: List<File> = emptyList()

    private fun refreshDownloads() {
        val tasks = DownloadManager.state.value
        val rows = ArrayList<DownloadRow>()

        tasks.filter { it.active }.forEach { rows.add(DownloadRow.Active(it)) }
        tasks.filter { !it.active }.forEach { rows.add(DownloadRow.Problem(it)) }

        // El progreso cambia unas 10 veces por segundo: solo se vuelve a leer el
        // disco cuando cambia el estado de alguna descarga, no en cada tick.
        val signature = tasks.joinToString(",") { "${it.id}:${it.status}" }
        if (signature != downloadsSignature) {
            downloadsSignature = signature
            cachedFiles = (
                DownloadManager.completedIn(this, DownloadKind.VIDEO) +
                    DownloadManager.completedIn(this, DownloadKind.AUDIO)
                ).sortedByDescending { it.lastModified() }
        }
        cachedFiles.forEach { rows.add(DownloadRow.Done(it)) }

        downloadAdapter.submit(rows)
        downloadsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDownloadedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: guessMime(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            showToast(getString(R.string.error_generic))
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".opus", true) -> "audio/ogg"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        else -> "*/*"
    }

    /* ------------------------------------------------------------------ */
    /* Menú de descarga                                                   */
    /* ------------------------------------------------------------------ */

    private fun setupDownloadSheet() {
        sheetScrim.setOnClickListener { closeDownloadSheet() }
        findViewById<View>(R.id.sheetClose).setOnClickListener { closeDownloadSheet() }
    }

    private fun openDownloadSheet() {
        val item = currentItem ?: return
        sheetFormats = null
        sheetVideoLang = null
        sheetAudioLang = null

        sheetTitle.text = item.title
        videoOptions.removeAllViews()
        audioOptions.removeAllViews()
        videoEmpty.visibility = View.GONE
        audioEmpty.visibility = View.GONE
        videoLangRow.visibility = View.GONE
        audioLangRow.visibility = View.GONE
        sheetError.visibility = View.GONE
        sheetLoading.visibility = View.VISIBLE
        sheetColumns.visibility = View.GONE

        sheetScrim.visibility = View.VISIBLE
        downloadSheet.visibility = View.VISIBLE

        sheetJob?.cancel()
        sheetJob = lifecycleScope.launch {
            try {
                val formats = YtDlp.fetchFormats(this@MainActivity, item.url)
                sheetFormats = formats
                renderSheet(formats)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                sheetLoading.visibility = View.GONE
                sheetError.visibility = View.VISIBLE
            }
        }
    }

    private fun closeDownloadSheet() {
        sheetJob?.cancel()
        sheetJob = null
        downloadSheet.visibility = View.GONE
        sheetScrim.visibility = View.GONE
    }

    private fun renderSheet(formats: FormatList) {
        sheetLoading.visibility = View.GONE
        sheetError.visibility = View.GONE
        sheetColumns.visibility = View.VISIBLE

        val languages = formats.languages
        setupLanguageRow(videoLangRow, videoLangSpinner, videoLangText, languages) {
            sheetVideoLang = it
        }
        setupLanguageRow(audioLangRow, audioLangSpinner, audioLangText, languages) { lang ->
            sheetAudioLang = lang
            renderAudioOptions(formats, lang)
        }

        videoOptions.removeAllViews()
        if (formats.video.isEmpty()) {
            videoEmpty.visibility = View.VISIBLE
        } else {
            videoEmpty.visibility = View.GONE
            formats.video.forEach { option ->
                addOptionRow(videoOptions, option.label) { startVideoDownload(option) }
            }
        }
        renderAudioOptions(formats, sheetAudioLang)
    }

    private fun renderAudioOptions(formats: FormatList, language: String?) {
        audioOptions.removeAllViews()
        val list = formats.audioFor(language)
        if (list.isEmpty()) {
            audioEmpty.visibility = View.VISIBLE
        } else {
            audioEmpty.visibility = View.GONE
            list.forEach { option ->
                addOptionRow(audioOptions, option.label) { startAudioDownload(option) }
            }
        }
    }

    private fun setupLanguageRow(
        row: View,
        spinner: Spinner,
        label: TextView,
        languages: List<String>,
        onChange: (String?) -> Unit
    ) {
        if (languages.isEmpty()) {
            row.visibility = View.GONE
            onChange(null)
            return
        }
        row.visibility = View.VISIBLE
        if (languages.size <= 1) {
            spinner.visibility = View.GONE
            label.visibility = View.VISIBLE
            label.text = languages.firstOrNull()
                ?.let { Formats.languageName(it) }
                ?: getString(R.string.sheet_language)
            onChange(null)
            return
        }

        label.visibility = View.GONE
        spinner.visibility = View.VISIBLE
        val names = listOf(getString(R.string.lang_auto)) +
            languages.map { Formats.languageName(it) }
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        spinner.setSelection(0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                onChange(if (position == 0) null else languages[position - 1])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun addOptionRow(
        container: android.widget.LinearLayout,
        label: String,
        onClick: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.item_option, container, false)
        view.findViewById<TextView>(R.id.optionLabel).text = label
        view.setOnClickListener {
            closeDownloadSheet()
            onClick()
        }
        container.addView(view)
    }

    private fun startVideoDownload(option: VideoOption) {
        val item = currentItem ?: return
        val formats = sheetFormats ?: return
        val audio = formats.bestAudio(sheetVideoLang)
        val selector = buildVideoSelector(option, audio)
        enqueueDownload(item, DownloadKind.VIDEO, option.label, sheetVideoLang, selector)
    }

    private fun startAudioDownload(option: AudioOption) {
        val item = currentItem ?: return
        enqueueDownload(
            item, DownloadKind.AUDIO, option.label, option.language, option.formatId
        )
    }

    /**
     * Une el stream de video elegido con el audio del idioma elegido.
     * La barra "/" son respaldos por si un formato no está disponible.
     */
    private fun buildVideoSelector(video: VideoOption, audio: AudioOption?): String {
        val parts = mutableListOf<String>()
        if (audio != null) parts.add("${video.formatId}+${audio.formatId}")
        parts.add("${video.formatId}+ba")
        parts.add("b[height<=${video.height}]")
        return parts.joinToString("/")
    }

    private fun enqueueDownload(
        item: VideoItem,
        kind: DownloadKind,
        label: String,
        language: String?,
        selector: String
    ) {
        DownloadManager.enqueue(this, item.url, item.title, kind, label, language, selector)
        showToast(getString(R.string.download_started))
        prepareBackgroundPrompts()
    }

    /* ------------------------------------------------------------------ */
    /* Permisos para segundo plano                                        */
    /* ------------------------------------------------------------------ */

    private fun prepareBackgroundPrompts() {
        val needsNotification = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

        if (needsNotification) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // La pregunta de batería se hace un poco después para no solapar diálogos.
        mainHandler.postDelayed({ askBatteryOptimization() }, 700)
    }

    private val notificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> }

    private fun askBatteryOptimization() {
        val prefs = getSharedPreferences("tubedw", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        if (isIgnoringBattery()) return
        prefs.edit().putBoolean("battery_asked", true).apply()

        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // sin pantalla de batería disponible
            }
        }
    }

    private fun isIgnoringBattery(): Boolean {
        val power = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        return power?.isIgnoringBatteryOptimizations(packageName) ?: false
    }

    /* ------------------------------------------------------------------ */
    /* Ajustes                                                             */
    /* ------------------------------------------------------------------ */

    private fun setupSettings() {
        findViewById<View>(R.id.settingsBack).setOnClickListener { closeSettings() }

        btnUpdateYtdlp.setOnClickListener {
            btnUpdateYtdlp.isEnabled = false
            btnUpdateYtdlp.text = getString(R.string.settings_updating)
            lifecycleScope.launch {
                val result = YtDlp.update(this@MainActivity)
                btnUpdateYtdlp.isEnabled = true
                btnUpdateYtdlp.text = getString(R.string.settings_update)
                showToast(
                    when (result) {
                        UpdateResult.UPDATED -> getString(R.string.settings_updated)
                        UpdateResult.UP_TO_DATE -> getString(R.string.settings_uptodate)
                        UpdateResult.FAILED -> getString(R.string.settings_update_error)
                    }
                )
                if (result != UpdateResult.FAILED) loadYtDlpVersion()
            }
        }

        btnBattery.setOnClickListener { askBatteryOptimizationDirect() }
        btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun askBatteryOptimizationDirect() {
        getSharedPreferences("tubedw", MODE_PRIVATE)
            .edit().putBoolean("battery_asked", false).apply()
        askBatteryOptimization()
        updateSettingsStates()
    }

    private fun openSettings() {
        settingsScreen.visibility = View.VISIBLE
        settingsYtdlpVersion.text = getString(R.string.loading)
        updateSettingsStates()
        loadYtDlpVersion()
    }

    private fun closeSettings() {
        settingsScreen.visibility = View.GONE
    }

    private fun loadYtDlpVersion() {
        lifecycleScope.launch {
            val version = YtDlp.currentVersion(this@MainActivity)
            settingsYtdlpVersion.text = getString(
                R.string.settings_version,
                version.ifEmpty { getString(R.string.settings_unknown_version) }
            )
        }
    }

    private fun updateSettingsStates() {
        settingsBatteryState.text = getString(
            if (isIgnoringBattery()) R.string.settings_battery_on
            else R.string.settings_battery_off
        )
        btnBattery.visibility =
            if (isIgnoringBattery()) View.GONE else View.VISIBLE

        val notified = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        settingsNotifyState.text = getString(
            if (notified) R.string.settings_notify_on else R.string.settings_notifications_desc
        )
        btnNotify.visibility = if (notified) View.GONE else View.VISIBLE
    }

    /* ------------------------------------------------------------------ */
    /* Utilidades                                                          */
    /* ------------------------------------------------------------------ */

    private fun showLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
