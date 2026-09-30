package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Opens CarPlay after boot when the user has enabled the startup option.
 *
 * The launch carries [DiPlayActivity.EXTRA_AUTO_OPEN_CARPLAY] so the connection starts directly:
 * without it DiPlay only sat on its home page and waited for the separate "connect when DiPlay
 * opens" toggle, which is not what "open after the car starts" promises. Many head units also
 * broadcast a vendor `QUICKBOOT_POWERON` instead of (or alongside) `BOOT_COMPLETED`, so both are
 * accepted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return
        if (action == ACTION_QUICKBOOT_POWERON && !isPlausiblePowerOn()) {
            // Unlike BOOT_COMPLETED, the vendor action is not a protected broadcast: any app could
            // send it. Only honor it as a real power-on, never as a mid-drive remote start.
            Log.w(TAG, "ignoring ${ACTION_QUICKBOOT_POWERON} that does not look like a real power-on")
            return
        }
        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(DiPlayActivity.EXTRA_AUTO_OPEN_CARPLAY, true)
        }
        try {
            context.startActivity(launch)
        } catch (error: RuntimeException) {
            // Android 10+ can refuse background activity starts; the settings page points at the
            // overlay permission that exempts this launch.
            Log.w(TAG, "Boot auto-start could not open DiPlay", error)
        }
    }

    /**
     * True when this really looks like the system announcing power-on, not an arbitrary app.
     *
     * On Android 14+ the sender's uid is known and must be the system's. On older releases that
     * cannot be checked, so accept the vendor action only while the unit is still booting — the
     * real broadcast arrives within a couple of minutes of power-on, which also bounds how long a
     * spoofed one could matter.
     */
    private fun isPlausiblePowerOn(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val senderUid = sentFromUid
            if (senderUid != Process.SYSTEM_UID && senderUid != Process.ROOT_UID) return false
        }
        return SystemClock.elapsedRealtime() <= POWER_ON_WINDOW_MILLIS
    }

    private companion object {
        const val TAG = "xcertplay-boot"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val POWER_ON_WINDOW_MILLIS = 3 * 60 * 1000L
    }
}
