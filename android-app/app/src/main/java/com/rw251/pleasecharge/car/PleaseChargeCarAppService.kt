package com.rw251.pleasecharge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.rw251.pleasecharge.AppLogger
import com.rw251.pleasecharge.BleForegroundService
import com.rw251.pleasecharge.ServiceStatus

/**
 * Android Auto Car App Service
 * Entry point for the Android Auto experience
 */
class PleaseChargeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allow any host during development
        // For production, use HostValidator.ALLOW_ALL_HOSTS_VALIDATOR or allowlist specific hosts
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return PleaseChargeSession()
    }
}

class PleaseChargeSession : Session() {
    private var mapRenderer: CarMapRenderer? = null
    
    override fun onCreateScreen(intent: Intent): Screen {
        android.util.Log.i("PleaseChargeSession", "onCreateScreen called")
        AppLogger.i("PleaseChargeSession: onCreateScreen called")
        
        // Reset service status for fresh start (in case launched directly from Android Auto)
        ServiceStatus.reset()
        
        // Start the foreground service to initialize location tracking and BLE
        // This ensures GPS works even when only Android Auto is launched (no phone app)
        startBleForegroundService()
        
        // Create mapRenderer here where carContext is guaranteed to be available
        if (mapRenderer == null) {
            android.util.Log.i("PleaseChargeSession", "Creating CarMapRenderer")
            AppLogger.i("PleaseChargeSession: Creating CarMapRenderer")
            mapRenderer = CarMapRenderer(carContext, lifecycle)
        }
        
        // Register the surface callback for map rendering
        android.util.Log.i("PleaseChargeSession", "Registering surface callback")
        try {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(mapRenderer)
            android.util.Log.i("PleaseChargeSession", "Surface callback registered successfully")
            AppLogger.i("PleaseChargeSession: Surface callback registered successfully")
        } catch (e: SecurityException) {
            android.util.Log.w("PleaseChargeSession", "Surface callback not available: ${e.message}")
            AppLogger.w("PleaseChargeSession: Surface callback not available: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("PleaseChargeSession", "Failed to register surface callback: ${e.message}", e)
            AppLogger.e("PleaseChargeSession: Failed to register surface callback", e)
        }
        
        return SocDisplayScreen(carContext)
    }
    
    private fun startBleForegroundService() {
        val intent = Intent(carContext, BleForegroundService::class.java).apply {
            action = BleForegroundService.ACTION_START
        }
        carContext.startForegroundService(intent)
        AppLogger.i("PleaseChargeSession: Started BleForegroundService from Android Auto")
    }
}
