package com.rw251.pleasecharge.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
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
 * Navigation screen that displays battery stats with full-screen map and collapsible side panel.
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
    private var panelExpanded: Boolean = false

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

    @SuppressLint("MissingPermission")
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
        
        // Update renderer with current stats
        mapRenderer.isPanelExpanded = panelExpanded
        mapRenderer.socPercent = socPercent
        mapRenderer.batteryTempC = tempCelsius
        mapRenderer.connectionStatus = connectionStatus
        // Check if we're in a connected/ready state
        val isConnected = connectionStatus == "READY" || connectionStatus == "READY (DEV)"
        
        // Force panel expanded when not connected
        if (!isConnected) {
            panelExpanded = true
        }
        
        mapRenderer.distanceMiles = distanceMiles
        mapRenderer.avgSpeedMph = avgSpeedMph
        mapRenderer.isConnected = isConnected
        
        // Use NavigationTemplate for full-screen map
        val builder = NavigationTemplate.Builder()
        
        // Disable background mode to keep action strips always visible
        builder.setBackgroundColor(androidx.car.app.model.CarColor.DEFAULT)

        // Only show panel toggle button when connected
        if (isConnected) {
            val mapActionStrip = ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(
                            CarIcon.Builder(
                                IconCompat.createWithResource(
                                    carContext, 
                                    if (panelExpanded) com.rw251.pleasecharge.R.drawable.ic_close
                                    else com.rw251.pleasecharge.R.drawable.ic_panel_expand
                                )
                            ).build()
                        )
                        .setOnClickListener { 
                            panelExpanded = !panelExpanded
                            invalidate()
                        }
                        .build()
                )
                .build()
            
            builder.setMapActionStrip(mapActionStrip)
        }
        
        // NavigationTemplate requires an action strip - use Connect button when disconnected
        val actionStrip = if (permissionsMissing) {
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Open Phone")
                        .setOnClickListener { openPhoneApp() }
                        .build()
                )
                .build()
        } else if (!isConnected) {
            // Show Connect button when not connected
            ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setTitle("Connect")
                        .setOnClickListener { connectToBle() }
                        .build()
                )
                .build()
        } else {
            // Use PAN action which shouldn't show a visible icon
            ActionStrip.Builder()
                .addAction(Action.PAN)
                .build()
        }
        builder.setActionStrip(actionStrip)

        return builder.build()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
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

    @SuppressLint("MissingPermission")
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        listener?.let { BleConnectionManager.removeListener(it) }
        bleManager?.stop()
        locationJob?.cancel()
    }

    private fun openPhoneApp() {
        // Launch the MainActivity on the phone to request permissions
        val intent = Intent(carContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        carContext.startActivity(intent)
    }
}
