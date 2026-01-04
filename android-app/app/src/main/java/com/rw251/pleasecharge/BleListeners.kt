package com.rw251.pleasecharge

import com.rw251.pleasecharge.ble.BleObdManager
import java.util.Locale

/**
 * Shared BLE listener implementation to keep formatting/logging consistent across
 * the phone app, foreground service, and Android Auto UI.
 */
class CommonBleListener(
    private val tag: String,
    private val callbacks: Callbacks
) : BleObdManager.Listener {

    data class Callbacks(
        val onStatus: (String) -> Unit = {},
        val onReady: () -> Unit = {},
        val onSoc: (raw: Int, pct: Double, timestamp: Long) -> Unit = { _, _, _ -> },
        val onTemp: (celsius: Double, timestamp: Long) -> Unit = { _, _ -> },
        val onError: (String, Throwable?) -> Unit = { _, _ -> },
        val onLog: (String) -> Unit = {},
        val onStateChanged: (BleObdManager.State) -> Unit = {}
    )

    override fun onStatus(text: String) {
        AppLogger.i("$tag status: $text")
        callbacks.onStatus(text)
    }

    override fun onReady() {
        AppLogger.i("$tag ready")
        callbacks.onReady()
    }

    override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
        val pct = pct95 ?: pct93 ?: pct97 ?: (raw / 9.5)
        AppLogger.i("$tag SOC: raw=$raw, pct=${String.format(Locale.US, "%.1f", pct)}")
        callbacks.onSoc(raw, pct, timestamp)
    }

    override fun onTemp(celsius: Double, timestamp: Long) {
        AppLogger.i("$tag Temp: ${String.format(Locale.US, "%.1f", celsius)}°C")
        callbacks.onTemp(celsius, timestamp)
    }

    override fun onError(msg: String, ex: Throwable?) {
        AppLogger.e("$tag error: $msg", ex)
        callbacks.onError(msg, ex)
    }

    override fun onLog(line: String) {
        AppLogger.d(line, tag)
        callbacks.onLog(line)
    }

    override fun onStateChanged(state: BleObdManager.State) {
        callbacks.onStateChanged(state)
    }
}
