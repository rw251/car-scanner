package com.rw251.pleasecharge.car

import android.annotation.SuppressLint
import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.model.CarText
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import android.text.SpannableString
import android.text.Spanned
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as NavManeuver
import com.rw251.pleasecharge.AppLogger
import com.rw251.pleasecharge.ChargerManager
import com.rw251.pleasecharge.ChargingPoint
import com.rw251.pleasecharge.CommonBleListener
import com.rw251.pleasecharge.CommonBleListener.Callbacks
import com.rw251.pleasecharge.BleConnectionManager
import com.rw251.pleasecharge.MainActivity
import com.rw251.pleasecharge.ble.BleObdManager
import com.rw251.pleasecharge.navigation.NavigationSdkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Navigation screen for Android Auto - displays navigation with battery SOC indicator.
 */
class SocDisplayScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var listener: BleObdManager.Listener? = null
    private var navInfo: NavInfo? = null
    private var chargers: List<ChargingPoint> = emptyList()
    private var socPercent: String = "--%"
    private var navInfoJob: Job? = null
    private var chargersJob: Job? = null

    init {
        lifecycle.addObserver(this)
        NavigationSdkManager.initialize(carContext)
        
        navInfoJob = lifecycleScope.launch {
            NavigationSdkManager.navInfo.collect { info ->
                navInfo = info
                invalidate()
            }
        }
        
        chargersJob = lifecycleScope.launch {
            ChargerManager.chargers.collect { updatedChargers ->
                chargers = updatedChargers
                invalidate()
            }
        }
        
        // Listen to BLE SOC updates
        listener = CommonBleListener(
            tag = "SocDisplayScreen",
            callbacks = Callbacks(
                onStatus = { },
                onReady = { },
                onSoc = { _, pct, _ ->
                    socPercent = String.format(Locale.US, "%.0f%%", pct)
                    invalidate()
                },
                onTemp = { _, _ -> },
                onError = { _, _ -> },
                onLog = { }
            )
        )
        
        BleConnectionManager.getOrCreateManager(carContext, listener!!)
    }


    override fun onGetTemplate(): Template {
        val navInfo = navInfo
        
        // If we have chargers and navigation, show PlaceListNavigationTemplate
        if (chargers.isNotEmpty() && navInfo?.navState == NavState.ENROUTE && navInfo.currentStep != null) {
            val itemListBuilder = ItemList.Builder()
            
            chargers.take(3).forEach { charger ->
                val distanceMeters = charger.distanceAlongRoute * 1609.34 // convert miles to meters
                
                val deviationText = charger.deviationSeconds?.let { dev ->
                    if (dev > 0) {
                        "+${dev / 60}m"
                    } else {
                        "0m"
                    }
                } ?: "0m"
                
                // Create SpannableString with DistanceSpan for the text
                val text = "${charger.operator} • ${charger.ccsPoints} CCS"
                val spannableText = SpannableString(text)
                spannableText.setSpan(
                    DistanceSpan.create(Distance.create(distanceMeters, Distance.UNIT_METERS)),
                    0,
                    text.length,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE
                )
                
                val row = Row.Builder()
                    .setTitle(charger.title ?: "Unknown Charger")
                    .addText(spannableText)
                    .addText("$deviationText deviation")
                    .setMetadata(
                        androidx.car.app.model.Metadata.Builder()
                            .setPlace(
                                Place.Builder(
                                    androidx.car.app.model.CarLocation.create(
                                        charger.latitude,
                                        charger.longitude
                                    )
                                ).setMarker(PlaceMarker.Builder().build()).build()
                            )
                            .build()
                    )
                    .build()
                
                itemListBuilder.addItem(row)
            }

            return PlaceListNavigationTemplate.Builder()
                .setItemList(itemListBuilder.build())
                .setHeaderAction(Action.BACK)
                .setActionStrip(buildActionStrip())
                .setMapActionStrip(buildMapActionStrip())
                .setTitle("Upcoming Chargers")
                .build()
        }
        
        // Otherwise show regular navigation template
        return NavigationTemplate.Builder()
            .setNavigationInfo(
                when (navInfo?.navState) {
                    NavState.ENROUTE -> buildRouting(navInfo)
                    NavState.REROUTING -> RoutingInfo.Builder().setLoading(true).build()
                    NavState.STOPPED -> buildMessage("Navigation", "Stopped")
                    else -> buildMessage("Navigation", "Start navigation on phone")
                }
            )
            .setActionStrip(buildActionStrip())
            .setMapActionStrip(buildMapActionStrip())
            .build()
    }
    
    private fun buildActionStrip(): ActionStrip {
        val builder = ActionStrip.Builder()
            .addAction(Action.PAN)
        
        // Add SOC display
        builder.addAction(
            Action.Builder()
                .setTitle("🔋 $socPercent")
                .build()
        )
        
        // No need for chargers button - they're shown in place list instead
        
        return builder.build()
    }
    
    private fun buildMapActionStrip(): ActionStrip {
        val builder = ActionStrip.Builder()
            .addAction(Action.PAN)
        
        // MapActionStrip doesn't allow custom titles, only icons
        // So we'll just use PAN action for now
        
        return builder.build()
    }

    private fun buildRouting(navInfo: NavInfo): RoutingInfo? {
        val step = navInfo.currentStep ?: return null
        val distance = navInfo.distanceToCurrentStepMeters?.coerceAtLeast(0) ?: 0
        
        val builder = RoutingInfo.Builder()
            .setCurrentStep(buildStep(step), androidx.car.app.model.Distance.create(distance.toDouble(), androidx.car.app.model.Distance.UNIT_METERS))
        
        navInfo.remainingSteps?.firstOrNull()?.let {
            builder.setNextStep(buildStep(it))
        }
        
        return try {
            builder.build()
        } catch (e: Exception) {
            AppLogger.w("Failed to build routing: ${e.message}")
            null
        }
    }

    private fun buildStep(stepInfo: StepInfo): Step {
        val text = stepInfo.fullInstructionText ?: stepInfo.simpleRoadName ?: "Continue"
        val builder = Step.Builder(text)
        
        stepInfo.fullRoadName?.let { builder.setRoad(it) }
        
        val maneuverType = mapManeuver(stepInfo.maneuver)
        if (maneuverType != null) {
            val maneuverBuilder = Maneuver.Builder(maneuverType)
            if (isRoundabout(maneuverType)) {
                maneuverBuilder.setRoundaboutExitNumber(1)
            }
            builder.setManeuver(maneuverBuilder.build())
        }
        
        return builder.build()
    }

    private fun buildMessage(title: String, text: String): MessageInfo {
        return MessageInfo.Builder(title).setText(text).build()
    }

    private fun mapManeuver(navManeuver: Int): Int? = when (navManeuver) {
        NavManeuver.DEPART -> Maneuver.TYPE_DEPART
        NavManeuver.DESTINATION -> Maneuver.TYPE_DESTINATION
        NavManeuver.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
        NavManeuver.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
        NavManeuver.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
        NavManeuver.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        NavManeuver.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        NavManeuver.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        NavManeuver.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
        NavManeuver.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
        NavManeuver.TURN_KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
        NavManeuver.TURN_KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
        NavManeuver.TURN_U_TURN_CLOCKWISE -> Maneuver.TYPE_U_TURN_RIGHT
        NavManeuver.TURN_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_U_TURN_LEFT
        NavManeuver.ON_RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        NavManeuver.ON_RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        NavManeuver.ON_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
        NavManeuver.ON_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        NavManeuver.ON_RAMP_SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
        NavManeuver.ON_RAMP_SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
        NavManeuver.ON_RAMP_KEEP_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
        NavManeuver.ON_RAMP_KEEP_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        NavManeuver.ON_RAMP_U_TURN_CLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
        NavManeuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
        NavManeuver.OFF_RAMP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        NavManeuver.OFF_RAMP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        NavManeuver.OFF_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        NavManeuver.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        NavManeuver.OFF_RAMP_SHARP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        NavManeuver.OFF_RAMP_SHARP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        NavManeuver.OFF_RAMP_KEEP_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        NavManeuver.OFF_RAMP_KEEP_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        NavManeuver.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
        NavManeuver.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
        NavManeuver.MERGE_UNSPECIFIED -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        NavManeuver.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
        NavManeuver.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
        NavManeuver.ROUNDABOUT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
        NavManeuver.ROUNDABOUT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        NavManeuver.ROUNDABOUT_EXIT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_EXIT_CW
        NavManeuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        NavManeuver.ROUNDABOUT_STRAIGHT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_LEFT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_RIGHT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.ROUNDABOUT_U_TURN_CLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        NavManeuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
        NavManeuver.FERRY_BOAT -> Maneuver.TYPE_FERRY_BOAT
        NavManeuver.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
        NavManeuver.STRAIGHT -> Maneuver.TYPE_STRAIGHT
        NavManeuver.UNKNOWN -> Maneuver.TYPE_UNKNOWN
        else -> null
    }

    private fun isRoundabout(type: Int): Boolean = type in listOf(
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
        Maneuver.TYPE_ROUNDABOUT_EXIT_CW,
        Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
    )

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        listener?.let { BleConnectionManager.removeListener(it) }
        navInfoJob?.cancel()
        chargersJob?.cancel()
    }
}
