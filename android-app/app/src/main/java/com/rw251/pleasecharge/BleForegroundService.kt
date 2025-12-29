package com.rw251.pleasecharge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service to keep BLE connection and location tracking alive when the app 
 * is backgrounded or the phone is locked. It reuses the shared BleConnectionManager 
 * and LocationTracker, and keeps the process in the foreground state with a 
 * lightweight notification.
 */
class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pleasecharge_ble"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rw251.pleasecharge.action.START"
        const val ACTION_STOP = "com.rw251.pleasecharge.action.STOP"
    }

    private var bleManager: BleObdManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundWithNotification()
            ACTION_STOP -> {
                stopLocationTracking()
                stopSelf()
            }
            else -> startForegroundWithNotification()
        }

        // Initialize DataCapture if not already done
        DataCapture.init(applicationContext)
        
        // Start location tracking
        startLocationTracking()

        // Ensure manager exists so BLE can persist
        bleManager = BleConnectionManager.getOrCreateManager(
            context = this,
            listener = object : BleObdManager.Listener {
                override fun onStatus(text: String) { /* no-op */ }
                override fun onReady() { /* no-op */ }
                override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
                    // Log SOC data even when in background
                    val pct = pct95 ?: (raw / 9.5)
                    DataCapture.logSoc(raw, pct, timestamp)
                }
                override fun onTemp(celsius: Double, timestamp: Long) {
                    // Log temp data even when in background
                    DataCapture.logTemp(celsius, timestamp)
                }
                override fun onError(msg: String, ex: Throwable?) { /* no-op */ }
                override fun onLog(line: String) { /* no-op */ }
                override fun onStateChanged(state: BleObdManager.State) { /* no-op */ }
            },
            updateListener = true, // Update listener to capture data
        )

        return START_STICKY
    }
    
    private fun startLocationTracking() {
        if (locationJob != null) return
        
        if (LocationTracker.hasPermission(this)) {
            LocationTracker.start(applicationContext) { msg ->
                AppLogger.d(msg, "LocationTracker-Service")
            }
            
            locationJob = serviceScope.launch {
                LocationTracker.metrics.collect { metrics ->
                    metrics?.let {
                        DataCapture.logLocation(
                            lat = it.lat,
                            lon = it.lon,
                            speedMph = it.averageSpeedMph,
                            distanceMiles = it.distanceMiles,
                            timestamp = it.timestampMs
                        )
                    }
                }
            }
            AppLogger.i("Location tracking started in foreground service")
        } else {
            AppLogger.w("Location permission not granted - cannot track in background")
        }
    }
    
    private fun stopLocationTracking() {
        locationJob?.cancel()
        locationJob = null
        LocationTracker.stop()
        AppLogger.i("Location tracking stopped in foreground service")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        
        // On Android 14+, we need to specify foreground service types
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PleaseCharge active")
            .setContentText("Recording battery & location data")
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
