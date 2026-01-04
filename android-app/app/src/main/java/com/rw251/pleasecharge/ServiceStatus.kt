package com.rw251.pleasecharge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton to share service status between BleForegroundService and UI.
 * Used to communicate timeout countdown to MainActivity.
 */
object ServiceStatus {
    private val _timeoutSecondsRemaining = MutableStateFlow<Long?>(null)
    val timeoutSecondsRemaining: StateFlow<Long?> = _timeoutSecondsRemaining.asStateFlow()
    
    private val _isServiceRunning = MutableStateFlow(true)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    
    fun setTimeoutSeconds(seconds: Long?) {
        _timeoutSecondsRemaining.value = seconds
    }
    
    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
