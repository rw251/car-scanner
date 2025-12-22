package com.rw251.pleasecharge.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rw251.pleasecharge.BleConnectionManager
import com.rw251.pleasecharge.LocationTracker
import com.rw251.pleasecharge.MainActivity
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Navigation screen that displays battery stats with map.
 */
class SocDisplayScreen(carContext: CarContext, private val mapRenderer: SimpleMapRenderer) : Screen(carContext), DefaultLifecycleObserver {
    private var bleManager: BleObdManager? = null
    private var listener: BleObdManager.Listener? = null
    
    private var socPercent: String = "--"
    private var socRaw: String = "--"
    private var tempCelsius: String = "--"
    private var lastUpdateTime: String = "--:--:--"
    private var connectionStatus: String = "DISCONNECTED"
    private var lastError: String? = null
    private var permissionsMissing: Boolean = false
    private var distanceMiles: Double? = null
    private var avgSpeedMph: Double? = null
    private var locationJob: Job? = null

    init {
        lifecycle.addObserver(this)
        locationJob = lifecycleScope.launch {
            LocationTracker.metrics.collect { metrics ->
                metrics?.let {
                    distanceMiles = it.distanceMiles
                    avgSpeedMph = it.averageSpeedMph
                    mapRenderer.updateLocation(it.lat, it.lon)
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        // Initialize BLE manager if needed to check permissions
        if (bleManager == null) {
            listener = createBleListener()
            bleManager = BleConnectionManager.getOrCreateManager(
                context = carContext,
                listener = listener!!
            )
        }
        
        // Check if permissions are missing
        if (bleManager?.hasAllPermissions() == false && !permissionsMissing) {
            permissionsMissing = true
            lastError = "Permissions needed - open phone app to grant access"
        }

        LocationTracker.start(carContext) { /* Car UI stays quiet; logs handled on phone */ }
        
        val builder = NavigationTemplate.Builder()

        // Simple message info for now
        val statsText = "Battery: $socPercent% ($socRaw) | Temp: ${tempCelsius}°C | Status: $connectionStatus"
        val messageInfo = MessageInfo.Builder(statsText).build()
        builder.setNavigationInfo(messageInfo)
        
        // Add action strip for BLE controls
        val actionStrip = ActionStrip.Builder()
        
        if (permissionsMissing) {
            actionStrip.addAction(
                Action.Builder()
                    .setTitle("Open Phone")
                    .setOnClickListener { openPhoneApp() }
                    .build()
            )
        } else {
            // Connect/Disconnect button
            actionStrip.addAction(
                Action.Builder()
                    .setTitle(if (connectionStatus == "DISCONNECTED") "Connect" else "Disconnect")
                    .setOnClickListener {
                        if (connectionStatus == "DISCONNECTED") {
                            connectToBle()
                        } else {
                            disconnectBle()
                        }
                    }
                    .build()
            )
            
            // Refresh button when connected
            if (connectionStatus == "READY" || connectionStatus == "READY (DEV)") {
                actionStrip.addAction(
                    Action.Builder()
                        .setTitle("Refresh")
                        .setOnClickListener { bleManager?.requestSoc() }
                        .build()
                )
            }
        }
        
        builder.setActionStrip(actionStrip.build())
        
        // Add map action strip with recenter button
        val mapActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, com.rw251.pleasecharge.R.drawable.ic_my_location)
                        ).build()
                    )
                    .setOnClickListener { mapRenderer.handleRecenter() }
                    .build()
            )
            .build()
        
        builder.setMapActionStrip(mapActionStrip)

        return builder.build()
    }

    private fun connectToBle() {
        if (bleManager == null) {
            listener = createBleListener()
            bleManager = BleConnectionManager.getOrCreateManager(
                context = carContext,
                listener = listener!!
            )
        } else {
            // Manager exists, just update the listener
            listener = createBleListener()
            BleConnectionManager.updateListener(listener!!)
        }
        
        // Check permissions
        if (bleManager?.hasAllPermissions() == false) {
            permissionsMissing = true
            lastError = "Permissions needed - open phone app to grant access"
            invalidate()
            return
        }
        
        permissionsMissing = false
        
        // Only start if not already connected - check current state
        val currentState = bleManager?.getState()
        if (currentState != BleObdManager.State.READY) {
            // Not ready yet, start connection
            bleManager?.start()
        }
        // Try to request data immediately in case already connected
        bleManager?.requestSoc()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun disconnectBle() {
        bleManager?.stop()
    }

    private fun createBleListener(): BleObdManager.Listener {
        return object : BleObdManager.Listener {
            override fun onStatus(text: String) {
                connectionStatus = text
                invalidate()
            }

            override fun onReady() {
                // Status already set via onStatus
                invalidate()
            }

            override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
                val pct = pct95 ?: (raw / 9.5)
                socPercent = String.format(Locale.getDefault(), "%.1f", pct)
                socRaw = raw.toString()
                lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                lastError = null
                invalidate()
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                tempCelsius = String.format(Locale.getDefault(), "%.1f", celsius)
                // Update timestamp if temp is newer than SOC timestamp
                if (lastUpdateTime == "--:--:--") {
                    lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                }
                invalidate()
            }

            override fun onError(msg: String, ex: Throwable?) {
                lastError = msg
                invalidate()
            }

            override fun onLog(line: String) {
                // Logs not displayed in car UI
            }

            override fun onStateChanged(state: BleObdManager.State) {
                // State changes handled via onStatus
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        listener?.let { BleConnectionManager.removeListener(it) }
        bleManager?.stop()
        locationJob?.cancel()
    }

    private fun formatMiles(value: Double?): String {
        return value?.let { String.format(Locale.getDefault(), "%.2f mi", it) } ?: "--"
    }

    private fun formatSpeed(value: Double?): String {
        return value?.let { String.format(Locale.getDefault(), "%.1f mph", it) } ?: "--"
    }

    private fun openPhoneApp() {
        // Launch the MainActivity on the phone to request permissions
        val intent = Intent(carContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        carContext.startActivity(intent)
    }
}
