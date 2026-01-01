package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared location tracker that emits distance travelled and average speed over the last 10 seconds.
 * Includes simple outlier filtering to smooth rogue GPS samples.
 * GPS update frequency increases when the vehicle is moving.
 */
object LocationTracker {
    private const val MAX_SPEED_MPH = 90.0               // Hard cap, cars in this app should stay <= 80mph
    private const val MAX_ACCEL_MPH_PER_SEC = 12.0       // Roughly 0-60mph in 5 seconds
    private const val MEDIAN_TOLERANCE_MPH = 25.0        // Reject samples far from recent median speed
    private const val TRIM_WINDOW_MS = 30_000L           // Keep 30s of history for smoothing
    private const val METERS_PER_MILE = 1609.344
    private const val STUCK_FILTER_THRESHOLD = 4         // Number of consecutive fallbacks before reset
    private const val GPS_MOVEMENT_THRESHOLD_METERS = 5.0 // Minimum GPS movement to consider valid
    
    // GPS update intervals - faster when moving for better tracking
    private const val GPS_INTERVAL_STATIONARY_MS = 10_000L  // 10 seconds when stationary
    private const val GPS_INTERVAL_MOVING_MS = 3_000L       // 3 seconds when moving
    private const val GPS_MIN_INTERVAL_MS = 2_000L          // Minimum 2 seconds between updates
    private const val MOVING_THRESHOLD_MPH = 5.0            // Consider moving if speed > 5 mph

    data class Metrics(
        val distanceMiles: Double,         // Distance in last 10s window (for speed calc)
        val totalTripDistanceMiles: Double, // Cumulative trip distance
        val averageSpeedMph: Double,
        val timestampMs: Long,
        val lat: Double,
        val lon: Double
    )

    private data class LocationSample(
        val lat: Double,
        val lon: Double,
        val timestampMs: Long,
        val segmentDistanceMeters: Double,
        val segmentSpeedMph: Double,
        val wasFallback: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val samples = ArrayDeque<LocationSample>()
    private val _metrics = MutableStateFlow<Metrics?>(null)
    val metrics: StateFlow<Metrics?> = _metrics.asStateFlow()

    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private val lock = Any()
    private var consecutiveFallbacks = 0
    private var devMode = false  // When true, ignore real GPS and only use injected locations
    private var isMoving = false  // Track whether we're currently moving
    private var savedContext: Context? = null  // Save context for reconfiguring GPS
    private var savedOnLog: ((String) -> Unit)? = null  // Save log callback
    private var totalTripDistanceMeters = 0.0  // Cumulative trip distance

    fun hasPermission(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, onLog: (String) -> Unit = {}) {
        if (callback != null) {
            AppLogger.i("LocationTracker.start() called but already started - ignoring")
            return
        }
        if (!hasPermission(context)) {
            AppLogger.w("Location permission missing - cannot start location tracking")
            onLog("Location permission missing - cannot start location tracking")
            return
        }
        
        AppLogger.i("LocationTracker.start() - starting location tracking")
        savedContext = context.applicationContext
        savedOnLog = onLog

        client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val request = createLocationRequest(isMoving)

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                AppLogger.i("LocationTracker received location: lat=${loc.latitude}, lon=${loc.longitude}")
                scope.launch {
                    handleLocation(loc, onLog)
                }
            }
        }
        callback = cb
        client?.requestLocationUpdates(request, cb, Looper.getMainLooper())
        AppLogger.i("Location tracking started (stationary mode)")
        onLog("Location tracking started (stationary mode)")
    }
    
    /**
     * Create a location request with appropriate interval based on movement state
     */
    private fun createLocationRequest(moving: Boolean): LocationRequest {
        val interval = if (moving) GPS_INTERVAL_MOVING_MS else GPS_INTERVAL_STATIONARY_MS
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
            .setWaitForAccurateLocation(true)
            .build()
    }
    
    /**
     * Update GPS frequency based on current movement state
     */
    @SuppressLint("MissingPermission")
    private fun updateGpsFrequency(moving: Boolean) {
        if (isMoving == moving) return  // No change needed
        isMoving = moving
        
        val ctx = savedContext ?: return
        val cb = callback ?: return
        val onLog = savedOnLog ?: {}
        
        // Remove old updates and start new ones with updated frequency
        try {
            client?.removeLocationUpdates(cb)
            val request = createLocationRequest(moving)
            client?.requestLocationUpdates(request, cb, Looper.getMainLooper())
            onLog("GPS frequency updated: ${if (moving) "moving (3s)" else "stationary (10s)"}")
        } catch (e: Exception) {
            onLog("Failed to update GPS frequency: ${e.message}")
        }
    }

    fun stop() {
        callback?.let { cb -> client?.removeLocationUpdates(cb) }
        callback = null
        client = null
        savedContext = null
        savedOnLog = null
        isMoving = false
        synchronized(lock) {
            samples.clear()
            _metrics.value = null
            consecutiveFallbacks = 0
            totalTripDistanceMeters = 0.0  // Reset trip distance when stopping
        }
    }
    
    /**
     * Enable or disable dev mode. When enabled, real GPS updates are ignored.
     */
    fun setDevMode(enabled: Boolean, onLog: (String) -> Unit = {}) {
        if (devMode != enabled) {
            devMode = enabled
            onLog(if (enabled) "Dev mode enabled - using simulated GPS" else "Dev mode disabled - using real GPS")
            if (enabled) {
                // Clear existing samples when switching to dev mode
                synchronized(lock) {
                    samples.clear()
                    consecutiveFallbacks = 0
                }
            }
        }
    }
    
    /**
     * Inject a simulated GPS location (for dev mode when using BLE simulator).
     * This bypasses the real GPS and directly adds a location sample.
     * Automatically enables dev mode when called.
     */
    fun injectSimulatedLocation(lat: Double, lon: Double, speedKmh: Double, onLog: (String) -> Unit = {}) {
        // Auto-enable dev mode when receiving simulated GPS
        if (!devMode) {
            setDevMode(true, onLog)
        }
        
        val timestamp = System.currentTimeMillis()
        val speedMph = speedKmh * 0.621371 // Convert km/h to mph
        
        synchronized(lock) {
            val prev = samples.lastOrNull()
            val deltaSec = if (prev != null) max(0.001, (timestamp - prev.timestampMs) / 1000.0) else 0.0
            val distanceMeters = if (prev != null) haversineMeters(prev.lat, prev.lon, lat, lon) else 0.0
            
            samples.add(
                LocationSample(
                    lat = lat,
                    lon = lon,
                    timestampMs = timestamp,
                    segmentDistanceMeters = distanceMeters,
                    segmentSpeedMph = min(speedMph, MAX_SPEED_MPH),
                    wasFallback = false
                )
            )
            
            // Add to cumulative trip distance
            totalTripDistanceMeters += distanceMeters
            
            trimSamplesLocked()
            publishMetricsLocked()
            
            onLog("Injected simulated GPS: $lat, $lon @ ${speedKmh.toInt()} km/h")
        }
    }

    private fun handleLocation(location: Location, onLog: (String) -> Unit) {
        // In dev mode, ignore real GPS updates - only use injected simulated locations
        if (devMode) return
        
        val timestamp = if (location.time > 0) location.time else System.currentTimeMillis()
        val lat = location.latitude
        val lon = location.longitude

        synchronized(lock) {
            val prev = samples.lastOrNull()
            val deltaSec = if (prev != null) max(0.001, (timestamp - prev.timestampMs) / 1000.0) else 0.0
            val rawDistance = if (prev != null) haversineMeters(prev.lat, prev.lon, lat, lon) else 0.0
            val rawSpeedMph = if (deltaSec > 0) mpsToMph(rawDistance / deltaSec) else 0.0
            val prevSpeed = prev?.segmentSpeedMph
            val medianSpeed = medianRecentSpeed()
            val accel = if (prevSpeed != null && deltaSec > 0) (rawSpeedMph - prevSpeed) / deltaSec else 0.0

            // Check if we're stuck in a fallback loop - GPS is clearly moving but filter keeps rejecting
            val gpsShowsRealMovement = rawDistance > GPS_MOVEMENT_THRESHOLD_METERS && rawSpeedMph < MAX_SPEED_MPH
            val filterIsStuck = consecutiveFallbacks >= STUCK_FILTER_THRESHOLD && gpsShowsRealMovement
            
            val looksRogue = prev != null && !filterIsStuck && (
                rawSpeedMph > MAX_SPEED_MPH ||
                abs(accel) > MAX_ACCEL_MPH_PER_SEC ||
                (medianSpeed != null && abs(rawSpeedMph - medianSpeed) > MEDIAN_TOLERANCE_MPH)
            )

            val distanceMeters: Double
            val speedMph: Double
            val wasFallback: Boolean
            
            if (filterIsStuck) {
                // Filter has been stuck - trust the GPS and reset
                distanceMeters = rawDistance
                speedMph = min(rawSpeedMph, MAX_SPEED_MPH)
                wasFallback = false
                consecutiveFallbacks = 0
                onLog("Filter reset - GPS shows movement at ${rawSpeedMph.toInt()} mph")
            } else if (looksRogue) {
                val fallbackSpeed = medianSpeed ?: prevSpeed ?: 0.0
                distanceMeters = mphToMps(fallbackSpeed) * deltaSec
                speedMph = fallbackSpeed
                wasFallback = true
                consecutiveFallbacks++
                onLog("Filtered rogue location sample (raw ${rawSpeedMph.toInt()} mph, fallback count: $consecutiveFallbacks)")
            } else {
                distanceMeters = rawDistance
                speedMph = min(rawSpeedMph, MAX_SPEED_MPH)
                wasFallback = false
                consecutiveFallbacks = 0
            }

            samples.add(
                LocationSample(
                    lat = lat,
                    lon = lon,
                    timestampMs = timestamp,
                    segmentDistanceMeters = distanceMeters,
                    segmentSpeedMph = speedMph,
                    wasFallback = wasFallback
                )
            )
            
            // Add to cumulative trip distance
            totalTripDistanceMeters += distanceMeters
            
            trimSamplesLocked()
            publishMetricsLocked()
        }
    }

    private fun medianRecentSpeed(): Double? {
        if (samples.isEmpty()) return null
        val speeds = samples.toList().takeLast(3).map { it.segmentSpeedMph }.sorted()
        if (speeds.isEmpty()) return null
        val mid = speeds.size / 2
        return if (speeds.size % 2 == 0) {
            (speeds[mid - 1] + speeds[mid]) / 2.0
        } else {
            speeds[mid]
        }
    }

    private fun trimSamplesLocked() {
        val latestTs = samples.lastOrNull()?.timestampMs ?: return
        val cutoff = latestTs - TRIM_WINDOW_MS
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    private fun publishMetricsLocked() {
        if (samples.size < 2) return
        val latestTs = samples.last().timestampMs
        val windowStart = latestTs - 10_000L
        val windowSamples = samples.filter { it.timestampMs >= windowStart }
        if (windowSamples.size < 2) return

        var distanceMeters = 0.0
        for (i in 1 until windowSamples.size) {
            distanceMeters += windowSamples[i].segmentDistanceMeters
        }
        val durationSec = max(1.0, (windowSamples.last().timestampMs - windowSamples.first().timestampMs) / 1000.0)
        val avgSpeedMps = distanceMeters / durationSec
        val avgMph = mpsToMph(avgSpeedMps)
        
        // Update GPS frequency based on current speed (not inside synchronized block)
        val nowMoving = avgMph > MOVING_THRESHOLD_MPH
        if (nowMoving != isMoving) {
            scope.launch(Dispatchers.Main) {
                updateGpsFrequency(nowMoving)
            }
        }

        _metrics.value = Metrics(
            distanceMiles = distanceMeters / METERS_PER_MILE,
            totalTripDistanceMiles = totalTripDistanceMeters / METERS_PER_MILE,
            averageSpeedMph = min(avgMph, MAX_SPEED_MPH),
            timestampMs = latestTs,
            lat = windowSamples.last().lat,
            lon = windowSamples.last().lon
        )
        AppLogger.i("LocationTracker metrics updated: lat=${windowSamples.last().lat}, lon=${windowSamples.last().lon}, " +
            "speed=${avgMph}, samples=${windowSamples.size}")
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun mpsToMph(mps: Double): Double = mps * 2.2369362920544
    private fun mphToMps(mph: Double): Double = mph * 0.44704
}
