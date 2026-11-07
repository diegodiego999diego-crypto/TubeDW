package com.opentube.tubedw.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import com.opentube.tubedw.R
import com.opentube.tubedw.TubeApp
import com.opentube.tubedw.data.AppDatabase
import com.opentube.tubedw.data.Repository
import com.opentube.tubedw.data.StreamData
import com.opentube.tubedw.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase

    private lateinit var adapter: VideoAdapter
    private lateinit var relatedAdapter: VideoAdapter

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

    private var exoPlayer: ExoPlayer? = null
    private var currentStream: StreamData? = null
    private var currentItem: VideoItem? = null
    private var startDownY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = (application as TubeApp).database
        bindViews()
        setupList()
        setupSearch()
        setupGestures()
        setupOptionButtons()
        setupBackButton()

        loadFeed()
    }

    /* ------------------------------------------------------------------ */
    /* Configuración inicial                                              */
    /* ------------------------------------------------------------------ */

    private fun bindViews() {
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
    }

    private fun setupList() {
        adapter = VideoAdapter { item -> openVideo(item) }
        feedList.layoutManager = LinearLayoutManager(this)
        feedList.adapter = adapter

        relatedAdapter = VideoAdapter { item -> openVideo(item) }
        relatedList.layoutManager = LinearLayoutManager(this)
        relatedList.adapter = relatedAdapter
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
        btnDownload.setOnClickListener { showToast(getString(R.string.download_soon)) }
        channelBack.setOnClickListener { loadFeed() }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playerFull.visibility == View.VISIBLE -> collapsePlayer()
                    channelHeader.visibility == View.VISIBLE -> loadFeed()
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
            showToast("Canal no disponible")
            return
        }
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
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
