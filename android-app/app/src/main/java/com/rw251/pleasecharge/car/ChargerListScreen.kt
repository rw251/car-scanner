package com.rw251.pleasecharge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rw251.pleasecharge.ChargerManager
import com.rw251.pleasecharge.ChargingPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Screen showing list of upcoming chargers on Android Auto
 */
class ChargerListScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var chargers: List<ChargingPoint> = emptyList()
    private var chargersJob: Job? = null

    init {
        lifecycle.addObserver(this)
        
        chargersJob = lifecycleScope.launch {
            ChargerManager.chargers.collect { updatedChargers ->
                chargers = updatedChargers
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        
        if (chargers.isEmpty()) {
            listBuilder.setNoItemsMessage("No chargers on route")
        } else {
            chargers.forEach { charger ->
                val remainingMiles = (charger.distanceAlongRoute / 1609.344).coerceAtLeast(0.0)
                val deviationMinutes = (charger.deviationSeconds ?: 0) / 60
                
                val distanceText = if (remainingMiles < 10.0) {
                    String.format(Locale.US, "%.1f mi", remainingMiles)
                } else {
                    "${remainingMiles.toInt()} mi"
                }
                
                val deviationText = if (deviationMinutes > 0) "+${deviationMinutes}m" else "${deviationMinutes}m"
                
                val row = Row.Builder()
                    .setTitle(charger.title ?: "Unknown")
                    .addText("${charger.operator} • ${charger.ccsPoints} CCS")
                    .addText("$distanceText • $deviationText")
                    .build()
                
                listBuilder.addItem(row)
            }
        }
        
        return ListTemplate.Builder()
            .setSingleList(listBuilder.build())
            .setTitle("Upcoming Chargers")
            .setHeaderAction(Action.BACK)
            .build()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        chargersJob?.cancel()
    }
}
