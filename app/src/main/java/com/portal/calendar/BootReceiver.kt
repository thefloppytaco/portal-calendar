package com.portal.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the idle takeover after a reboot or an app update (no Mac needed). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if ((action == Intent.ACTION_BOOT_COMPLETED ||
             action == Intent.ACTION_MY_PACKAGE_REPLACED) && Screensaver.isEnabled(context))
            KeepAliveService.start(context)
    }
}
