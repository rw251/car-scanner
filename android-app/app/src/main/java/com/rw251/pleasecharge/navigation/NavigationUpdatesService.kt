package com.rw251.pleasecharge.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.rw251.pleasecharge.AppLogger

class NavigationUpdatesService : Service() {
    private val turnByTurnManager = TurnByTurnManager.createInstance()

    private val handler = Handler(Looper.getMainLooper()) { message ->
        handleNavMessage(message)
        true
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder {
        return messenger.binder
    }

    private fun handleNavMessage(message: Message) {
        if (message.what != TurnByTurnManager.MSG_NAV_INFO) {
            return
        }

        val navInfo = try {
            turnByTurnManager.readNavInfoFromBundle(message.data)
        } catch (e: Exception) {
            AppLogger.w("NavigationUpdatesService: Failed to parse nav info: ${e.message}")
            null
        }

        NavigationSdkManager.updateNavInfo(navInfo)
    }
}
