package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != ACTION_QUICKBOOT_POWERON) return
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

    private companion object {
        const val TAG = "xcertplay-boot"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
