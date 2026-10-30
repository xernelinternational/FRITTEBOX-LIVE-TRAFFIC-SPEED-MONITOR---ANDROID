package com.fritte.speedmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Shows the live speed in an ongoing notification (the Android counterpart of the tray icon). */
class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            notificationManager().notify(NOTIF_ID, buildNotification())
            handler.postDelayed(this, 1000)
        }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Live speed", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        notificationManager().createNotificationChannel(ch)
        Monitor.acquire(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setNotif(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        Monitor.release()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val s = Monitor.snapshot()
        val title: String
        val text: String
        if (s.down.isEmpty()) {
            title = if (s.state == ConnState.ERROR) "No connection" else "Connecting …"
            text = APP_NAME
        } else {
            title = "▼ ${fmtRateText(s.down.last())}   ▲ ${fmtRateText(s.up.last())}"
            text = (if (s.model != null) "Connected to ${s.model}" else "Connected") +
                " · " + Router.connLabel(s.accessType)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop
                ).build()
            )
            .build()
    }

    companion object {
        const val CHANNEL = "live_speed"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.fritte.speedmonitor.STOP"
    }
}
