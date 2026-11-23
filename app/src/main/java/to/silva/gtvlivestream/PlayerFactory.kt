package to.silva.gtvlivestream

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds the shared, fast-starting ExoPlayer used by [OverlayController]. No MediaSource is set
 * here: playback is driven by the controller, which knows whether the URL is RTSP, HLS or
 * progressive.
 */
object PlayerFactory {

    fun build(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 8000, 500, 800)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()

        player.setHandleAudioBecomingNoisy(false)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.setVolume(0f)
        return player
    }
}
