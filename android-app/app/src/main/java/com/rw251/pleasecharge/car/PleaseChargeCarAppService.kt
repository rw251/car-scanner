package com.rw251.pleasecharge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

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
    private var mapRenderer: SimpleMapRenderer? = null
    
    override fun onCreateScreen(intent: Intent): Screen {
        android.util.Log.i("PleaseChargeSession", "onCreateScreen called")
        
        // Create mapRenderer here where carContext is guaranteed to be available
        if (mapRenderer == null) {
            android.util.Log.i("PleaseChargeSession", "Creating SimpleMapRenderer")
            mapRenderer = SimpleMapRenderer(carContext, lifecycle)
        }
        
        // Register the surface callback directly here instead of relying on lifecycle
        android.util.Log.i("PleaseChargeSession", "Registering surface callback directly")
        try {
            carContext.getCarService(androidx.car.app.AppManager::class.java)
                .setSurfaceCallback(mapRenderer!!.surfaceCallback)
            android.util.Log.i("PleaseChargeSession", "Surface callback registered successfully from Session")
        } catch (e: SecurityException) {
            android.util.Log.w("PleaseChargeSession", "Surface callback not available: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("PleaseChargeSession", "Failed to register surface callback: ${e.message}", e)
        }
        
        return SocDisplayScreen(carContext, mapRenderer!!)
    }
}
