package com.rw251.pleasecharge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared charger data for MainActivity and Android Auto
 */
data class ChargingPoint(
    val id: Int,
    val title: String?,
    val latitude: Double,
    val longitude: Double,
    val operatorId: Int?,
    val statusTypeId: Int?,
    val connections: List<Map<String, Any>> = emptyList(),
    val operator: String = "Unknown",
    val status: String = "Unknown",
    val ccsPoints: Int = 0,
    var distanceAlongRoute: Double = 0.0, // meters from route start
    var deviationSeconds: Long? = null, // additional time vs direct route
    var routeToChargerSeconds: Long? = null,
    var routeFromChargerSeconds: Long? = null
)

/**
 * Singleton to share charger list between MainActivity and Android Auto screens
 */
object ChargerManager {
    private val _chargers = MutableStateFlow<List<ChargingPoint>>(emptyList())
    val chargers: StateFlow<List<ChargingPoint>> = _chargers.asStateFlow()
    
    fun updateChargers(chargers: List<ChargingPoint>) {
        _chargers.value = chargers
    }
    
    fun clearChargers() {
        _chargers.value = emptyList()
    }
}
