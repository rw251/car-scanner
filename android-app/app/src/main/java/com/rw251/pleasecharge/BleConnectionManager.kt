package com.rw251.pleasecharge

import android.content.Context
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Singleton to manage shared BLE connection between phone app and Android Auto
 */
object BleConnectionManager {
    private var bleManager: BleObdManager? = null
    private var activeListener: BleObdManager.Listener? = null

    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    fun getOrCreateManager(
        context: Context,
        listener: BleObdManager.Listener,
        updateListener: Boolean = true,
    ): BleObdManager {
        if (updateListener) {
            activeListener = listener
        } else if (activeListener == null) {
            // Ensure we always have some listener available for the proxy
            activeListener = listener
        }
        
        if (bleManager == null) {
            bleManager = BleObdManager(
                context = context.applicationContext,
                listener = ProxyListener(),
                scope = managerScope
            )
        }
        
        return bleManager!!
    }

    fun updateListener(listener: BleObdManager.Listener) {
        activeListener = listener
    }

    /**
     * Proxy listener that forwards to the currently active listener
     * This allows switching between phone and car UI
     */
    private class ProxyListener : BleObdManager.Listener {
        override fun onStatus(text: String) {
            activeListener?.onStatus(text)
        }

        override fun onReady() {
            activeListener?.onReady()
        }

        override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
            activeListener?.onSoc(raw, pct93, pct95, pct97, timestamp)
        }

        override fun onTemp(celsius: Double, timestamp: Long) {
            activeListener?.onTemp(celsius, timestamp)
        }

        override fun onError(msg: String, ex: Throwable?) {
            activeListener?.onError(msg, ex)
        }

        override fun onLog(line: String) {
            activeListener?.onLog(line)
        }

        override fun onStateChanged(state: BleObdManager.State) {
            activeListener?.onStateChanged(state)
        }
    }
}
