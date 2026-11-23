package to.silva.gtvlivestream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * Foreground service that owns the HTTP server and the overlay lifecycle. START_STICKY, and
 * restarted by [BootReceiver] after a reboot when the user has enabled it.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "to.silva.gtvlivestream.START"
        const val ACTION_STOP = "to.silva.gtvlivestream.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastError: String? = null

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w("OverlayService", "stop requested while service unavailable", e)
            } catch (e: SecurityException) {
                Log.w("OverlayService", "stop denied", e)
            }
        }

        private const val CHANNEL_ID: String = "gtv_live_stream"
        private const val NOTIFICATION_ID: Int = 1
    }

    private val TAG: String = "OverlayService"

    private var prefs: Prefs? = null
    private var overlay: OverlayController? = null
    private var httpServer: HttpApiServer? = null

    override fun onCreate() {
        super.onCreate()
        val preferences = Prefs(this)
        prefs = preferences
        val controller = OverlayController(this)
        overlay = controller

        createNotificationChannel()
        startForegroundSafely()
        startHttpServer(preferences, controller)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start request (boot, app launch, sticky restart) must re-assert the
        // foreground notification, or Android 12+ kills the service.
        startForegroundSafely()
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        httpServer?.let { server ->
            try {
                server.stop()
            } catch (e: Exception) {
                Log.w(TAG, "server stop failed", e)
            }
        }
        httpServer = null

        overlay?.release()
        overlay = null

        stopForeground(true)
        isRunning = false
        prefs = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundSafely() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: RuntimeException) {
            lastError = "could not start foreground service"
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val port = prefs?.port ?: 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.label_port) + " " + port)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startHttpServer(preferences: Prefs, controller: OverlayController) {
        val server = HttpApiServer(
            port = preferences.port,
            tokenProvider = { preferences.token },
            isPlaying = { controller.isShowing },
            onShow = { request, _ -> controller.show(request) { } },
            onHide = { controller.hide() },
            defaultsProvider = { preferences.defaults() },
            appVersion = BuildConfig.VERSION_NAME,
            overlayPermission = { android.provider.Settings.canDrawOverlays(this) },
        )
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            lastError = null
        } catch (e: IOException) {
            lastError = "HTTP server could not bind port " + preferences.port
            Log.e(TAG, "server start failed on port " + preferences.port, e)
        }
        httpServer = server
    }
}
