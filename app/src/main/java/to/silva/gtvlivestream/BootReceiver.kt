package to.silva.gtvlivestream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Brings [OverlayService] back after a reboot or a quick-boot power cycle, but only when the user
 * has asked for it and the overlay permission is still granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) {
            return
        }
        val prefs = Prefs(context)
        if (!prefs.startOnBoot) {
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "not restarting service: overlay permission missing")
            return
        }
        try {
            OverlayService.start(context)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "could not restart service", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "restart denied", e)
        }
    }

    private companion object {
        const val TAG: String = "BootReceiver"
        const val ACTION_QUICKBOOT_POWERON: String = "android.intent.action.QUICKBOOT_POWERON"
    }
}
