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
    override fun onCreateScreen(intent: Intent): Screen {
        return SocDisplayScreen(carContext)
    }
}
