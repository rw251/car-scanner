package com.rw251.pleasecharge

import android.content.Context
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference

/**
 * Singleton to manage shared BLE connection between phone app and Android Auto.
 * Supports multiple listeners so both UIs stay in sync.
 */
object BleConnectionManager {
    // Keep a weak reference so the manager (and any contexts it holds) can be garbage collected.
    private var bleManagerRef: WeakReference<BleObdManager>? = null
    private val listeners = mutableSetOf<BleObdManager.Listener>()

    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getOrCreateManager(
        context: Context,
        listener: BleObdManager.Listener,
        updateListener: Boolean = true,
    ): BleObdManager {
        if (updateListener) {
            synchronized(listeners) {
                listeners.add(listener)
            }
        }

        // Try to obtain an existing manager
        val existing = bleManagerRef?.get()
        if (existing != null) {
            return existing
        }

        // Create a new manager using application context (very important)
        val created = BleObdManager(
            context = context.applicationContext,
            listener = ProxyListener(),
            scope = managerScope
        )
        bleManagerRef = WeakReference(created)
        return created
    }

    fun removeListener(listener: BleObdManager.Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

//    fun updateListener(listener: BleObdManager.Listener) {
//        synchronized(listeners) {
//            listeners.add(listener)
//        }
//    }

    /**
     * Proxy listener that forwards to all registered listeners
     * This keeps both phone and car UI in sync
     */
    private class ProxyListener : BleObdManager.Listener {
        override fun onStatus(text: String) {
            synchronized(listeners) {
                listeners.forEach { it.onStatus(text) }
            }
        }

        override fun onReady() {
            synchronized(listeners) {
                listeners.forEach { it.onReady() }
            }
        }

        override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
            synchronized(listeners) {
                listeners.forEach { it.onSoc(raw, pct93, pct95, pct97, timestamp) }
            }
        }

        override fun onTemp(celsius: Double, timestamp: Long) {
            synchronized(listeners) {
                listeners.forEach { it.onTemp(celsius, timestamp) }
            }
        }

        override fun onError(msg: String, ex: Throwable?) {
            synchronized(listeners) {
                listeners.forEach { it.onError(msg, ex) }
            }
        }

        override fun onLog(line: String) {
            synchronized(listeners) {
                listeners.forEach { it.onLog(line) }
            }
        }

        override fun onStateChanged(state: BleObdManager.State) {
            synchronized(listeners) {
                listeners.forEach { it.onStateChanged(state) }
            }
        }
    }
}
