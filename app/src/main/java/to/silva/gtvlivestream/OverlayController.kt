package to.silva.gtvlivestream

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the single floating window: one FrameLayout, one PlayerView and one ExoPlayer, all created
 * lazily and reused across shows. Public entry points are safe to call from any thread; every
 * WindowManager and view touch is marshalled onto the main looper.
 */
class OverlayController(private val context: Context) {

    private val TAG: String = "OverlayController"

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val timeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var player: ExoPlayer? = null
    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var clockView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var currentRequest: ShowRequest? = null
    private var onClosedCallback: (() -> Unit)? = null
    private var videoWidthPx: Int = 0
    private var videoHeightPx: Int = 0

    private var playbackState: String = "idle"
    private var renderedFirstFrame: Boolean = false
    private var lastError: String? = null

    @Volatile
    private var attached: Boolean = false

    val isShowing: Boolean
        get() = attached

    /** Snapshot for /health: tells a remote caller whether frames actually rendered. */
    fun status(): Map<String, Any?> = mapOf(
        "playing" to attached,
        "state" to playbackState,
        "videoWidth" to videoWidthPx,
        "videoHeight" to videoHeightPx,
        "renderedFirstFrame" to renderedFirstFrame,
        "lastError" to lastError,
    )

    private val hideRunnable: Runnable = Runnable { hideInternal(notify = true) }

    private val clockRunnable: Runnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, CLOCK_REFRESH_MS)
        }
    }

    private val playerListener: Player.Listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidthPx = videoSize.width
                videoHeightPx = videoSize.height
                updateWindowLayout()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            playbackState = when (state) {
                Player.STATE_IDLE -> "idle"
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> "ready"
                Player.STATE_ENDED -> "ended"
                else -> "unknown"
            }
            if (state == Player.STATE_IDLE) {
                renderedFirstFrame = false
            }
        }

        override fun onRenderedFirstFrame() {
            renderedFirstFrame = true
        }

        override fun onPlayerError(error: PlaybackException) {
            lastError = error.errorCodeName + ": " + (error.message ?: "").take(180)
            Log.w(TAG, "playback error: " + error.errorCodeName)
        }
    }

    /** Shows [request], replacing whatever is on screen. Safe from any thread. */
    fun show(request: ShowRequest, onClosed: () -> Unit) {
        onMain { showInternal(request, onClosed) }
    }

    /** Fades the window out and removes it, keeping the player warm for the next show. */
    fun hide() {
        onMain { hideInternal(notify = true) }
    }

    /** Removes the window and releases the player. The controller is unusable afterwards. */
    fun release() {
        onMain {
            handler.removeCallbacksAndMessages(null)
            val view = root
            if (view != null) {
                view.animate().cancel()
                removeFromWindow(view)
            }
            attached = false
            currentRequest = null
            onClosedCallback = null
            player?.let { active ->
                textureView?.let { view ->
                    try {
                        active.clearVideoTextureView(view)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "clearVideoTextureView failed", e)
                    }
                }
            }
            player?.removeListener(playerListener)
            player?.release()
            player = null
            textureView = null
            titleView = null
            subtitleView = null
            clockView = null
            root = null
            layoutParams = null
            videoWidthPx = 0
            videoHeightPx = 0
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(Runnable { block() })
        }
    }

    private fun showInternal(request: ShowRequest, onClosed: () -> Unit) {
        currentRequest = request
        onClosedCallback = onClosed
        lastError = null
        renderedFirstFrame = false
        playbackState = "idle"

        val exoPlayer = ensurePlayer()
        val view = ensureViews()
        if (exoPlayer == null || view == null) {
            failShow("overlay view or player unavailable")
            return
        }

        titleView?.text = request.title
        val subtitle = request.subtitle
        subtitleView?.text = subtitle ?: ""
        subtitleView?.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        updateClock()

        if (attached) {
            updateWindowLayout()
        } else if (!attach(view, request)) {
            return
        }

        if (!startPlayback(exoPlayer, request)) {
            return
        }

        restartAutoHide(request.durationS)
        handler.removeCallbacks(clockRunnable)
        handler.postDelayed(clockRunnable, CLOCK_REFRESH_MS)
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        return try {
            val created = PlayerFactory.build(context)
            created.addListener(playerListener)
            player = created
            textureView?.let { created.setVideoTextureView(it) }
            created
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not create player", e)
            null
        }
    }

    private fun ensureViews(): FrameLayout? {
        root?.let { return it }

        val density = context.resources.displayMetrics.density
        val card = GradientDrawable().apply {
            setColor(CARD_COLOR)
            cornerRadius = dp(density, CARD_RADIUS_DP).toFloat()
            setStroke(dp(density, CARD_STROKE_DP), ACCENT_COLOR)
        }

        val rootLayout = FrameLayout(context).apply {
            background = card
            elevation = dp(density, CARD_ELEVATION_DP).toFloat()
            setPadding(
                dp(density, CARD_PADDING_DP),
                dp(density, CARD_PADDING_DP),
                dp(density, CARD_PADDING_DP),
                dp(density, CARD_PADDING_DP),
            )
            isClickable = true
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(density, HEADER_HEIGHT_DP),
            ),
        )

        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(LIVE_COLOR)
            }
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(density, LIVE_DOT_DP), dp(density, LIVE_DOT_DP)))

        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleView = title
        header.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(density, HEADER_GAP_DP) },
        )

        val subtitle = TextView(context).apply {
            setTextColor(DIM_COLOR)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = subtitle
        header.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(density, HEADER_GAP_DP) },
        )

        header.addView(
            View(context),
            LinearLayout.LayoutParams(0, 1).apply { weight = 1f },
        )

        val clock = TextView(context).apply {
            setTextColor(DIM_COLOR)
            textSize = 11f
            gravity = Gravity.END
        }
        clockView = clock
        header.addView(
            clock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // TextureView, not SurfaceView: SurfaceView inside a TYPE_APPLICATION_OVERLAY window
        // renders black on several Android TV devices.
        val video = TextureView(context).apply {
            isClickable = true
        }
        textureView = video
        column.addView(
            video,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = dp(density, HEADER_GAP_DP) },
        )

        rootLayout.setOnClickListener { hideInternal(notify = true) }
        video.setOnClickListener {
            toggleMute()
            currentRequest?.let { restartAutoHide(it.durationS) }
        }

        root = rootLayout
        player?.let { it.setVideoTextureView(video) }
        return rootLayout
    }

    private fun attach(view: FrameLayout, request: ShowRequest): Boolean {
        val params = buildLayoutParams(request) ?: return false
        view.animate().cancel()
        view.alpha = 0f
        if (view.isAttachedToWindow) {
            removeFromWindow(view)
        }
        return try {
            windowManager.addView(view, params)
            layoutParams = params
            attached = true
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "addView denied", e)
            failShow("overlay permission missing")
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "addView rejected", e)
            failShow("could not attach overlay window")
            false
        }
    }

    private fun startPlayback(exoPlayer: ExoPlayer, request: ShowRequest): Boolean {
        return try {
            exoPlayer.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ !request.mute)
            exoPlayer.volume = if (request.mute) 0f else 1f
            val item = MediaItem.fromUri(Uri.parse(request.url))
            val source = if (request.url.lowercase(Locale.US).startsWith("rtsp")) {
                // Most cameras/TVs drop RTP over UDP; TCP interleaving is the reliable path.
                RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .createMediaSource(item)
            } else {
                DefaultMediaSourceFactory(context).createMediaSource(item)
            }
            exoPlayer.setMediaSource(source)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "could not start playback", e)
            failShow("could not play stream")
            false
        }
    }

    private fun toggleMute() {
        val exoPlayer = player ?: return
        exoPlayer.volume = if (exoPlayer.volume == 0f) 1f else 0f
    }

    private fun hideInternal(notify: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(clockRunnable)

        val view = root
        if (view == null || !attached) {
            return
        }
        attached = false

        val callback = onClosedCallback
        onClosedCallback = null

        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (e: RuntimeException) {
            Log.w(TAG, "player stop failed", e)
        }

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                removeFromWindow(view)
                view.alpha = 1f
                if (notify) {
                    callback?.invoke()
                }
            }
            .start()
    }

    private fun updateWindowLayout() {
        val request = currentRequest ?: return
        val view = root ?: return
        if (!attached) {
            return
        }
        applyWindowLayout(view, request)
    }

    private fun applyWindowLayout(view: FrameLayout, request: ShowRequest) {
        val params = layoutParams ?: buildLayoutParams(request) ?: return
        positionWindow(params, request)
        layoutParams = params
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "updateViewLayout rejected", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "updateViewLayout denied", e)
        }
    }

    private fun buildLayoutParams(request: ShowRequest): WindowManager.LayoutParams? {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // No FLAG_LAYOUT_NO_LIMITS: the window must stay inside the screen, and absolute
        // top-left offsets keep the right/bottom maths unambiguous.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = WindowManager.LayoutParams(0, 0, type, flags, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.setTitle("gtv-live-stream-overlay")
        params.packageName = context.packageName
        params.windowAnimations = 0
        params.alpha = 1f
        positionWindow(params, request)
        return params
    }

    /**
     * Places the window with [Gravity.TOP] + [Gravity.LEFT] and absolute offsets, so positive
     * x/y always move inward. Using RIGHT/BOTTOM gravity with offsets is what pushed an early
     * build partly off-screen on TVs that report overscan.
     */
    private fun positionWindow(params: WindowManager.LayoutParams, request: ShowRequest) {
        val screen = screenSize()
        val density = context.resources.displayMetrics.density
        // TV overscan eats the outer ~2% on many panels; keep the card inside that safe area.
        val margin = maxOf(dp(density, WINDOW_MARGIN_DP), (minOf(screen.first, screen.second) * 0.02f).toInt())

        val width = windowWidthPx(screen.first, request.widthPct, margin)
        val height = windowHeightPx(screen.second, request.heightPct, width, margin)

        params.width = width
        params.height = height
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.x = when (request.position) {
            "top-left", "bottom-left" -> margin
            "top-right", "bottom-right" -> screen.first - width - margin
            else -> (screen.first - width) / 2
        }
        params.y = when (request.position) {
            "top-left", "top-right" -> margin
            "bottom-left", "bottom-right" -> screen.second - height - margin
            else -> (screen.second - height) / 2
        }
    }

    private fun windowWidthPx(screenWidth: Int, widthPct: Int, margin: Int): Int {
        val density = context.resources.displayMetrics.density
        val minWidth = dp(density, MIN_WIDTH_DP)
        val maxWidth = (screenWidth - 2 * margin).coerceAtLeast(minWidth)
        return (screenWidth * widthPct / 100).coerceIn(minWidth, maxWidth)
    }

    /**
     * Height follows the media aspect ratio, capped by the requested percentage of the screen.
     * Card chrome (header, padding, gap) is added on top so the video area itself stays 16:9.
     */
    private fun windowHeightPx(screenHeight: Int, heightPct: Int, width: Int, margin: Int): Int {
        val density = context.resources.displayMetrics.density
        val chrome = dp(density, CARD_PADDING_DP * 2 + HEADER_HEIGHT_DP + HEADER_GAP_DP)
        val minVideo = dp(density, MIN_VIDEO_HEIGHT_DP)
        val maxWindow = minOf(screenHeight * heightPct / 100, screenHeight - 2 * margin)
            .coerceAtLeast(chrome + minVideo)
        val aspect = if (videoWidthPx > 0 && videoHeightPx > 0) {
            videoHeightPx.toFloat() / videoWidthPx.toFloat()
        } else {
            9f / 16f
        }
        val videoHeight = (width * aspect).toInt()
            .coerceAtMost(maxWindow - chrome)
            .coerceAtLeast(minVideo)
        return videoHeight + chrome
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun updateClock() {
        clockView?.text = timeFormat.format(Date())
    }

    private fun restartAutoHide(durationS: Int) {
        handler.removeCallbacks(hideRunnable)
        if (durationS > 0) {
            handler.postDelayed(hideRunnable, durationS * 1000L)
        }
    }

    private fun removeFromWindow(view: View) {
        if (!view.isAttachedToWindow) {
            return
        }
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "removeView ignored", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeView denied", e)
        }
    }

    private fun failShow(reason: String) {
        attached = false
        Log.w(TAG, "show failed: $reason")
        val callback = onClosedCallback
        onClosedCallback = null
        callback?.invoke()
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()

    private companion object {
        // These are non-const because the leading-alpha hex literals are Long values in Kotlin.
        val CARD_COLOR: Int = 0xEB0B0F14.toInt()
        val ACCENT_COLOR: Int = 0xFF34D399.toInt()
        val LIVE_COLOR: Int = 0xFFEF4444.toInt()
        val DIM_COLOR: Int = 0xFF94A3B8.toInt()

        const val CARD_RADIUS_DP: Int = 16
        const val CARD_STROKE_DP: Int = 2
        const val CARD_PADDING_DP: Int = 12
        const val CARD_ELEVATION_DP: Int = 8
        const val HEADER_HEIGHT_DP: Int = 28
        const val HEADER_GAP_DP: Int = 6
        const val LIVE_DOT_DP: Int = 8
        const val WINDOW_MARGIN_DP: Int = 24
        const val MIN_WIDTH_DP: Int = 160
        const val MIN_VIDEO_HEIGHT_DP: Int = 90

        const val FADE_IN_MS: Long = 150
        const val FADE_OUT_MS: Long = 200
        const val CLOCK_REFRESH_MS: Long = 30_000
    }
}
