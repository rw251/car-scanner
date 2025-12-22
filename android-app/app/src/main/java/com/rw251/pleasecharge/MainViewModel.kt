package com.rw251.pleasecharge

import androidx.lifecycle.ViewModel
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the main BLE OBD screen.
 * Exposes StateFlows for status, logs, SOC display, and ready state.
 */
class MainViewModel : ViewModel() {

    companion object {
        private const val MAX_LOG_LINES = 200
    }

    private val _status = MutableStateFlow("DISCONNECTED")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _socDisplay = MutableStateFlow<String?>(null)
    val socDisplay: StateFlow<String?> = _socDisplay.asStateFlow()

    private val _socTime = MutableStateFlow<String?>(null)
    val socTime: StateFlow<String?> = _socTime.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _state = MutableStateFlow(BleObdManager.State.DISCONNECTED)
    val state: StateFlow<BleObdManager.State> = _state.asStateFlow()

    private val _distanceMiles = MutableStateFlow<Double?>(null)
    val distanceMiles: StateFlow<Double?> = _distanceMiles.asStateFlow()

    private val _avgSpeedMph = MutableStateFlow<Double?>(null)
    val avgSpeedMph: StateFlow<Double?> = _avgSpeedMph.asStateFlow()

    fun setStatus(text: String) {
        _status.value = text
    }

    fun appendLog(line: String) {
        val current = _logs.value.toMutableList()
        current.add(line)
        // Cap buffer to prevent memory issues
        if (current.size > MAX_LOG_LINES) {
            _logs.value = current.takeLast(MAX_LOG_LINES)
        } else {
            _logs.value = current
        }
    }

    fun setSoc(display: String, time: String) {
        _socDisplay.value = display
        _socTime.value = time
    }

    fun setReady(ready: Boolean) {
        _isReady.value = ready
    }

    fun setState(state: BleObdManager.State) {
        _state.value = state
    }

    fun setLocationStats(distanceMiles: Double, avgSpeedMph: Double) {
        _distanceMiles.value = distanceMiles
        _avgSpeedMph.value = avgSpeedMph
    }
}
