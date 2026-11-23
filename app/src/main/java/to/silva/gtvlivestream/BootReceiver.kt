package to.silva.gtvlivestream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Brings [OverlayService] up after a reboot, a quick-boot power cycle, or an app update, but only
 * when the user has asked for it and the overlay permission is still granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in START_ACTIONS) {
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
            Log.i(TAG, "service start requested after $action")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "could not restart service", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "restart denied", e)
        }
    }

    private companion object {
        const val TAG: String = "BootReceiver"
        const val ACTION_QUICKBOOT_POWERON: String = "android.intent.action.QUICKBOOT_POWERON"

        val START_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
        )
    }
}
