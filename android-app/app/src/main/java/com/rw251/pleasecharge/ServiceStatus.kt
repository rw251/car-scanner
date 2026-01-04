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
    
    // Track whether we've ever successfully connected (for "Reconnecting" vs "Connecting" UI)
    private val _hasConnectedBefore = MutableStateFlow(false)
    val hasConnectedBefore: StateFlow<Boolean> = _hasConnectedBefore.asStateFlow()
    
    fun setTimeoutSeconds(seconds: Long?) {
        _timeoutSecondsRemaining.value = seconds
    }
    
    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
    
    fun setHasConnectedBefore(hasConnected: Boolean) {
        _hasConnectedBefore.value = hasConnected
    }
    
    /**
     * Reset all state when app restarts
     */
    fun reset() {
        _timeoutSecondsRemaining.value = null
        _isServiceRunning.value = true
        _hasConnectedBefore.value = false
    }
}
