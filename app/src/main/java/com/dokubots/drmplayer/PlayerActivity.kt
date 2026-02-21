package com.dokubots.drmplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.URLUtil
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import com.dokubots.drmplayer.databinding.ActivityPlayerBinding
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private var rawUrl: String = ""
    
    // Gestures
    private lateinit var audioManager: AudioManager
    private var brightness = -1.0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        rawUrl = intent.getStringExtra("URL") ?: return finish()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupCustomControls()
        setupGestures()
        initializePlayer()
    }

    private fun initializePlayer() {
        val config = StreamParser.parse(rawUrl)
        
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(config.headers)
            config.headers["User-Agent"]?.let { setUserAgent(it) }
        }

        trackSelector = DefaultTrackSelector(this)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()

        binding.playerView.player = player

        var fileName = URLUtil.guessFileName(config.url, null, null)
        if (fileName.contains("?") || fileName.endsWith(".bin")) fileName = "Live Stream"
        HistoryManager.saveHistory(this, rawUrl, fileName)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(config.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(fileName).build())

        if (config.drmType != "none" && config.drmLicense.isNotEmpty()) {
            val drmScheme = when (config.drmType) {
                "widevine" -> C.WIDEVINE_UUID
                "playready" -> C.PLAYREADY_UUID
                "clearkey" -> C.CLEARKEY_UUID
                else -> C.UUID_NIL
            }
            if (drmScheme != C.UUID_NIL) {
                mediaItemBuilder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(drmScheme)
                        .setLicenseUri(config.drmLicense)
                        .setLicenseRequestHeaders(config.headers)
                        .build()
                )
            }
        }

        player?.setMediaItem(mediaItemBuilder.build())
        
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val prefs = getSharedPreferences("PlayerHistory", Context.MODE_PRIVATE)
                    val savedPosition = prefs.getLong(config.url, 0L)
                    if (savedPosition > 0 && player?.isCurrentMediaItemLive == false) {
                        player?.seekTo(savedPosition)
                    }
                    player?.removeListener(this)
                }
            }
        })
        
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun setupCustomControls() {
        val btnScale = binding.playerView.findViewById<ImageButton>(R.id.exo_scale)
        val btnRotate = binding.playerView.findViewById<ImageButton>(R.id.exo_rotate)
        
        var scaleMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        btnScale.setOnClickListener {
            scaleMode = if (scaleMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.resizeMode = scaleMode
        }

        btnRotate.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val deltaY = e1.y - e2.y
                val deltaX = e1.x - e2.x
                
                if (abs(deltaX) > abs(deltaY)) return false 

                val screenWidth = binding.playerView.width
                if (e1.x < screenWidth / 2) {
                    brightness = window.attributes.screenBrightness
                    if (brightness == -1.0f) brightness = 0.5f
                    brightness += deltaY / binding.playerView.height
                    brightness = brightness.coerceIn(0.01f, 1.0f)
                    val layoutParams = window.attributes
                    layoutParams.screenBrightness = brightness
                    window.attributes = layoutParams
                } else {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val volumeChange = (deltaY / binding.playerView.height) * maxVolume
                    val newVolume = (currentVolume + volumeChange).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onStop() {
        super.onStop()
        player?.let {
            if (!it.isCurrentMediaItemLive && StreamParser.parse(rawUrl).url.isNotEmpty()) {
                val prefs = getSharedPreferences("PlayerHistory", Context.MODE_PRIVATE)
                prefs.edit().putLong(StreamParser.parse(rawUrl).url, it.currentPosition).apply()
            }
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
