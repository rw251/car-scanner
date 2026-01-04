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
import com.rw251.pleasecharge.car.TileCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service to keep BLE connection and location tracking alive when the app 
 * is backgrounded or the phone is locked. It reuses the shared BleConnectionManager 
 * and LocationTracker, and keeps the process in the foreground state with a 
 * lightweight notification.
 * 
 * Auto-shutdown: If BLE is disconnected for 10 minutes, the service will stop itself
 * to conserve battery.
 */
class BleForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "pleasecharge_ble"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.rw251.pleasecharge.action.START"
        const val ACTION_STOP = "com.rw251.pleasecharge.action.STOP"
        const val BLE_DISCONNECT_TIMEOUT_MS = 30 * 1000L  // 10 minutes
    }

    private var bleManager: BleObdManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var locationJob: Job? = null
    private var bleTimeoutJob: Job? = null
    private var lastBleConnectedState: Boolean = false
    private var lastPreloadLat: Double? = null
    private var lastPreloadLon: Double? = null
    private val PRELOAD_THRESHOLD_KM = 1.0  // Preload when moved 1km from last preload location

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
        
        // START GPS IMMEDIATELY - don't wait for BLE
        // GPS should be collecting data in parallel with BLE connection attempts
        startLocationTracking()

        // Start BLE in parallel (non-blocking)
        startBleManagerAsync()

        return START_STICKY
    }
    
    /**
     * Start BLE manager asynchronously so it doesn't block location tracking startup.
     * BLE connection and location tracking should happen in parallel.
     */
    private fun startBleManagerAsync() {
        // Guard: only start BLE manager once
        if (bleManager != null) {
            AppLogger.d("BLE manager already initialized - skipping", "BleForegroundService")
            return
        }
        
        serviceScope.launch {
            // Ensure manager exists so BLE can persist
            val manager = BleConnectionManager.getOrCreateManager(
                context = this@BleForegroundService,
                listener = object : BleObdManager.Listener {
                    override fun onStatus(text: String) { /* no-op */ }
                    override fun onReady() {
                        onBleConnectionChanged(true)
                    }
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
                    override fun onStateChanged(state: BleObdManager.State) {
                        // Track connection state for auto-shutdown timeout
                        val isConnected = state == BleObdManager.State.READY
                        onBleConnectionChanged(isConnected)
                    }
                },
                updateListener = true, // Update listener to capture data
            )
            bleManager = manager
            AppLogger.i("BleForegroundService: BLE manager initialized asynchronously")
            
            // Start the disconnect timeout since we're not connected yet
            startBleDisconnectTimeout()
        }
    }
    
    /**
     * Called when BLE connection state changes.
     * Manages the auto-shutdown timeout: starts countdown when disconnected, cancels when connected.
     */
    private fun onBleConnectionChanged(isConnected: Boolean) {
        if (isConnected == lastBleConnectedState) return
        lastBleConnectedState = isConnected
        
        if (isConnected) {
            // Connected - cancel any pending timeout
            bleTimeoutJob?.cancel()
            bleTimeoutJob = null
            ServiceStatus.setTimeoutSeconds(null)
            AppLogger.i("BleForegroundService: BLE connected - cancelled auto-shutdown timeout")
        } else {
            // Disconnected - start the 10-minute countdown
            startBleDisconnectTimeout()
        }
    }
    
    /**
     * Start a 10-minute countdown. If BLE doesn't reconnect within this time,
     * stop the foreground service to conserve battery.
     * Updates ServiceStatus every second so UI can display countdown.
     */
    private fun startBleDisconnectTimeout() {
        // Cancel any existing timeout
        bleTimeoutJob?.cancel()
        
        bleTimeoutJob = serviceScope.launch {
            AppLogger.i("BleForegroundService: Starting 10-minute auto-shutdown countdown (BLE disconnected)")
            
            val startTimeMs = System.currentTimeMillis()
            val endTimeMs = startTimeMs + BLE_DISCONNECT_TIMEOUT_MS
            
            while (System.currentTimeMillis() < endTimeMs) {
                val remainingMs = endTimeMs - System.currentTimeMillis()
                val remainingSeconds = (remainingMs / 1000).coerceAtLeast(0)
                ServiceStatus.setTimeoutSeconds(remainingSeconds)
                
                delay(1000)  // Update every second
            }
            
            // Timeout expired and still not connected
            ServiceStatus.setTimeoutSeconds(0)
            AppLogger.i("BleForegroundService: 10 minutes without BLE connection - stopping service")
            ServiceStatus.setServiceRunning(false)  // Signal to UI that service is stopping
            stopLocationTracking()
            stopSelf()
        }
    }
    
    private fun startLocationTracking() {
        // Guard: only start once per service instance to avoid duplicate location logging
        if (locationJob != null) {
            AppLogger.d("Location tracking already started in this service instance - skipping", "LocationTracker-Service")
            return
        }
        
        if (!LocationTracker.hasPermission(this)) {
            AppLogger.w("Location permission not granted - cannot track in background")
            return
        }
        
        AppLogger.i("BleForegroundService: Starting location tracking")
        
        // Start LocationTracker (has its own guard against duplicate starts)
        LocationTracker.start(applicationContext) { msg ->
            AppLogger.d(msg, "LocationTracker-Service")
        }
        
        // Collect metrics and log/preload
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
                    
                    // Preload map tiles around current location
                    maybePreloadTiles(it.lat, it.lon)
                }
            }
        }
        AppLogger.i("BleForegroundService: Location tracking job launched")
    }
    
    private fun stopLocationTracking() {
        locationJob?.cancel()
        locationJob = null
        LocationTracker.stop()
        AppLogger.i("Location tracking stopped in foreground service")
    }
    
    /**
     * Preload map tiles around the current location if we've moved significantly.
     * Uses a simple distance check to avoid excessive preloading.
     */
    private fun maybePreloadTiles(lat: Double, lon: Double) {
        val lastLat = lastPreloadLat
        val lastLon = lastPreloadLon
        
        // Calculate distance from last preload location
        val shouldPreload = if (lastLat == null || lastLon == null) {
            true  // First location, always preload
        } else {
            val distanceKm = haversineKm(lastLat, lastLon, lat, lon)
            distanceKm >= PRELOAD_THRESHOLD_KM
        }
        
        if (shouldPreload) {
            lastPreloadLat = lat
            lastPreloadLon = lon
            TileCache.backgroundPreload(lat, lon)
            AppLogger.d("Preloading tiles around $lat, $lon - ${TileCache.getCacheStats()}", "TileCache")
        }
    }
    
    /**
     * Calculate distance between two points in km using Haversine formula
     */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0  // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bleTimeoutJob?.cancel()
        stopLocationTracking()
        serviceScope.cancel()
        AppLogger.i("BleForegroundService: Service destroyed")
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
