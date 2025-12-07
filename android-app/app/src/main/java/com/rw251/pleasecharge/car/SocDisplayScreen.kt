package com.rw251.pleasecharge.car

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
import com.rw251.pleasecharge.ble.BleObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen that displays State of Charge (SOC) in Android Auto
 */
class SocDisplayScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var bleManager: BleObdManager? = null
    
    private var socPercent: String = "--"
    private var socRaw: String = "--"
    private var socTime: String = "--:--:--"
    private var connectionStatus: String = "DISCONNECTED"
    private var lastError: String? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        // Connection status row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Connection Status")
                .addText(connectionStatus)
                .build()
        )

        // SOC percentage row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("State of Charge")
                .addText("$socPercent%")
                .build()
        )

        // Raw value row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Raw Value")
                .addText(socRaw)
                .build()
        )

        // Last update time row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Last Updated")
                .addText(socTime)
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
                    .setTitle("Refresh SOC")
                    .setOnClickListener {
                        bleManager?.requestSoc()
                    }
                    .build()
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("PleaseCharge")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun connectToBle() {
        if (bleManager == null) {
            bleManager = BleConnectionManager.getOrCreateManager(
                context = carContext,
                listener = createBleListener()
            )
        } else {
            // Manager exists, just update the listener
            BleConnectionManager.updateListener(createBleListener())
        }
        
        // Check permissions - in a real app, you'd need to handle this properly
        // For now, we assume permissions are granted from the phone app
        if (bleManager?.hasAllPermissions() == true) {
            bleManager?.start()
        } else {
            lastError = "Missing BLE permissions - grant in phone app"
            invalidate()
        }
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
                socTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                lastError = null
                invalidate()
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                // Not displayed in car UI currently
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
        bleManager?.stop()
    }
}
