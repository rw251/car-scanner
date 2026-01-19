package com.rw251.pleasecharge.navigation

import android.app.Application
import android.content.Context
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.rw251.pleasecharge.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

object NavigationSdkManager {
    private const val TAG = "NavigationSdkManager"

    private val navInfoFlow = MutableStateFlow<NavInfo?>(null)
    val navInfo: StateFlow<NavInfo?> = navInfoFlow.asStateFlow()

    private val initStarted = AtomicBoolean(false)
    private val navUpdatesRegistered = AtomicBoolean(false)

    @Volatile
    private var navigator: Navigator? = null

    fun initialize(context: Context) {
        if (navigator != null) {
            registerNavUpdates(context)
            return
        }
        if (!initStarted.compareAndSet(false, true)) {
            return
        }

        val application = context.applicationContext as? Application
        if (application == null) {
            AppLogger.w("$TAG: Application context not available")
            initStarted.set(false)
            return
        }

        NavigationApi.getNavigator(application, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(navigator: Navigator) {
                AppLogger.i("$TAG: Navigator ready")
                this@NavigationSdkManager.navigator = navigator
                registerNavUpdates(context)
            }

            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                AppLogger.w("$TAG: Navigator error $errorCode")
                initStarted.set(false)
            }
        })
    }

    fun setNavigator(navigator: Navigator, context: Context) {
        this.navigator = navigator
        registerNavUpdates(context)
    }

    fun updateNavInfo(info: NavInfo?) {
        navInfoFlow.value = info
    }

    fun clearNavInfo() {
        navInfoFlow.value = null
    }

    private fun registerNavUpdates(context: Context) {
        val navigator = navigator ?: return
        if (!navUpdatesRegistered.compareAndSet(false, true)) {
            return
        }

        val options = NavigationUpdatesOptions.builder()
            .setDisplayMetrics(context.resources.displayMetrics)
            .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.NONE)
            .setNumNextStepsToPreview(1)
            .build()

        val registered = try {
            navigator.registerServiceForNavUpdates(
                context.packageName,
                NavigationUpdatesService::class.java.name,
                options
            )
        } catch (e: SecurityException) {
            AppLogger.w("$TAG: Nav updates registration blocked: ${e.message}")
            false
        } catch (e: Exception) {
            AppLogger.e("$TAG: Failed to register nav updates", e)
            false
        }

        if (!registered) {
            navUpdatesRegistered.set(false)
        }
    }
}
