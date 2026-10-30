package com.fritte.speedmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the speed notification after the phone restarts (only if the user switched it on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs.boot(context) && Prefs.notif(context)
        ) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }
    }
}
