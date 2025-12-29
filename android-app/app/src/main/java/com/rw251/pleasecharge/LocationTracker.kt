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
 */
object LocationTracker {
    private const val MAX_SPEED_MPH = 90.0               // Hard cap, cars in this app should stay <= 80mph
    private const val MAX_ACCEL_MPH_PER_SEC = 12.0       // Roughly 0-60mph in 5 seconds
    private const val MEDIAN_TOLERANCE_MPH = 25.0        // Reject samples far from recent median speed
    private const val TRIM_WINDOW_MS = 30_000L           // Keep 30s of history for smoothing
    private const val METERS_PER_MILE = 1609.344
    private const val STUCK_FILTER_THRESHOLD = 4         // Number of consecutive fallbacks before reset
    private const val GPS_MOVEMENT_THRESHOLD_METERS = 5.0 // Minimum GPS movement to consider valid

    data class Metrics(
        val distanceMiles: Double,
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

    fun hasPermission(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, onLog: (String) -> Unit = {}) {
        if (callback != null) return
        if (!hasPermission(context)) {
            onLog("Location permission missing - cannot start location tracking")
            return
        }

        client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setWaitForAccurateLocation(true)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                scope.launch {
                    handleLocation(loc, onLog)
                }
            }
        }
        callback = cb
        client?.requestLocationUpdates(request, cb, Looper.getMainLooper())
        onLog("Location tracking started")
    }

    fun stop() {
        callback?.let { cb -> client?.removeLocationUpdates(cb) }
        callback = null
        client = null
        synchronized(lock) {
            samples.clear()
            _metrics.value = null
            consecutiveFallbacks = 0
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

        _metrics.value = Metrics(
            distanceMiles = distanceMeters / METERS_PER_MILE,
            averageSpeedMph = min(avgMph, MAX_SPEED_MPH),
            timestampMs = latestTs,
            lat = windowSamples.last().lat,
            lon = windowSamples.last().lon
        )
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
