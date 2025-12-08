package com.rw251.pleasecharge.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rw251.pleasecharge.BleConnectionManager
import com.rw251.pleasecharge.MainActivity
import com.rw251.pleasecharge.ble.BleObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen that displays battery state (SOC + temp) in Android Auto
 */
class SocDisplayScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var bleManager: BleObdManager? = null
    private var listener: BleObdManager.Listener? = null
    
    private var socPercent: String = "--"
    private var socRaw: String = "--"
    private var tempCelsius: String = "--"
    private var lastUpdateTime: String = "--:--:--"
    private var connectionStatus: String = "DISCONNECTED"
    private var lastError: String? = null
    private var permissionsMissing: Boolean = false

    init {
        lifecycle.addObserver(this)
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
        
        val paneBuilder = Pane.Builder()

        // Row 1: Status and Battery info combined
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Status: $connectionStatus")
                .addText("Battery: $socPercent% ($socRaw)")
                .build()
        )

        // Row 2: Temperature and Last Updated combined
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Temp: $tempCelsius°C")
                .addText("Updated: $lastUpdateTime")
                .build()
        )

        // Error row (if any)
        lastError?.let { error ->
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Error")
                    .addText(error)
                    .build()
            )
        }

        // Add action buttons
        if (permissionsMissing) {
            // Permissions not granted - show action to open phone app
            paneBuilder.addAction(
                Action.Builder()
                    .setTitle("Open Phone App to Grant Permissions")
                    .setOnClickListener {
                        openPhoneApp()
                    }
                    .build()
            )
        } else {
            // Normal connect/disconnect button
            paneBuilder.addAction(
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

            if (connectionStatus == "READY" || connectionStatus == "READY (DEV)") {
                paneBuilder.addAction(
                    Action.Builder()
                        .setTitle("Refresh")
                        .setOnClickListener {
                            bleManager?.requestSoc()
                        }
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("PleaseCharge")
            .setHeaderAction(Action.APP_ICON)
            .build()
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

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        listener?.let { BleConnectionManager.removeListener(it) }
        bleManager?.stop()
    }

    private fun openPhoneApp() {
        // Launch the MainActivity on the phone to request permissions
        val intent = Intent(carContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        carContext.startActivity(intent)
    }
}
