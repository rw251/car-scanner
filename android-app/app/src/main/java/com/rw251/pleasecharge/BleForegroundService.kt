package com.rw251.pleasecharge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rw251.pleasecharge.ble.BleObdManager

/**
 * Foreground service to keep BLE connection alive when the app is backgrounded
 * or the phone is locked. It reuses the shared BleConnectionManager and keeps
 * the process in the foreground state with a lightweight notification.
 */
class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pleasecharge_ble"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rw251.pleasecharge.action.START"
        const val ACTION_STOP = "com.rw251.pleasecharge.action.STOP"
    }

    private var bleManager: BleObdManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundWithNotification()
            ACTION_STOP -> stopSelf()
            else -> startForegroundWithNotification()
        }

        // Ensure manager exists so BLE can persist
        bleManager = BleConnectionManager.getOrCreateManager(
            context = this,
            listener = object : BleObdManager.Listener {
                override fun onStatus(text: String) { /* no-op */ }
                override fun onReady() { /* no-op */ }
                override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) { /* no-op */ }
                override fun onTemp(celsius: Double, timestamp: Long) { /* no-op */ }
                override fun onError(msg: String, ex: Throwable?) { /* no-op */ }
                override fun onLog(line: String) { /* no-op */ }
                override fun onStateChanged(state: BleObdManager.State) { /* no-op */ }
            },
            updateListener = false,
        )

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PleaseCharge connected")
            .setContentText("Keeping BLE connection active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PleaseCharge BLE",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps BLE connection active in background"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
