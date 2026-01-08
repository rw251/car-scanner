package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rw251.pleasecharge.ble.BleObdManager
import com.rw251.pleasecharge.CommonBleListener.Callbacks
import com.rw251.pleasecharge.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.RoadSnappedLocationProvider
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.CustomRoutesOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.isActive
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// Extension function to convert JSONObject to Map
fun JSONObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    keys().forEach { key ->
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) {
            map[key] = value
        }
    }
    return map
}

/**
 * Full-screen map activity with collapsible stats overlay.
 * - Full-screen OSM map with current location marker
 * - Collapsible bottom sheet showing SOC, temp, speed
 * - View Logs and Export buttons in expanded panel
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var manager: BleObdManager? = null
    private var locationJob: Job? = null
    private var durationJob: Job? = null
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    
    // Place pickers
    private var originPlacePicker: AutocompleteSupportFragment? = null
    private var destinationPlacePicker: AutocompleteSupportFragment? = null
    private var originPlace: Place? = null
    private var destinationPlace: Place? = null
    private var currentLocation: LatLng? = null
    private var originLatLng: LatLng? = null
    private var navigationActive: Boolean = false
    private var routePanelVisible: Boolean = false
    
    // Track current stats for display
    private var currentSocPct: Double? = null
    private var currentTempC: Double? = null
    private var currentSpeedMph: Double? = null
    private var currentDistanceMiles: Double? = null
    private var journeyStartTime: Long? = null
    private var currentRouteToken: String? = null
    private var currentRoutePolyline: String? = null

    private lateinit var mRoadSnappedLocationProvider: RoadSnappedLocationProvider
    private lateinit var mLocationListener: RoadSnappedLocationProvider.LocationListener

    // Make navigator and routing options nullable as they're initialized later
    var mNavigator: Navigator? = null
    var mRoutingOptions: RoutingOptions? = null
    
    // OpenChargeMap reference data
    private var openChargeMapRefData: OpenChargeMapReferenceData? = null
    private data class OpenChargeMapReferenceData(
        val connectionTypes: List<Map<String, Any>> = emptyList(),
        val operators: List<Map<String, Any>> = emptyList(),
        val statusTypes: List<Map<String, Any>> = emptyList(),
        val countries: List<Map<String, Any>> = emptyList(),
        val usageTypes: List<Map<String, Any>> = emptyList(),
        val chargerTypes: List<Map<String, Any>> = emptyList(),
        val currentTypes: List<Map<String, Any>> = emptyList(),
        val powerLevels: List<Map<String, Any>> = emptyList()
    )
    
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
        val type2Points: Int = 0
    )
    /**
     * Permission launcher for BLE and Location permissions.
     * Only called if permissions are not already granted.
     */
    @SuppressLint("MissingPermission")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            AppLogger.i("All permissions granted")
            // Now request background location if needed (Android 10+)
            maybeRequestBackgroundLocation()
            startLocationTracking()
            startBleManager()
        } else {
            AppLogger.w("Permissions denied: $results")
        }
    }
    
    /**
     * Permission launcher for background location permission.
     * Only called if permission is not already granted.
     */
    @SuppressLint("MissingPermission")
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLogger.i("Background location permission granted")
        } else {
            AppLogger.w("Background location permission denied")
        }
    }

    /**
     * Activity lifecycle - onCreate
     * Called when the phone app (main activity) is created (i.e. when the
     * user launches the app).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge drawing so we can control safe padding ourselves
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Initialize logging and data capture
        AppLogger.init(this)
        DataCapture.init(this)
        AppLogger.i("MainActivity created")
        
        // Reset service status for fresh start
        ServiceStatus.reset()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Without this you get a purple bar with the app name at the top
        supportActionBar?.hide()


        // --- APPLY SAFE PADDING TO FRAGMENT CONTAINER (prevents overlap with status/nav bars) ---
        // Make sure this is the container that hosts your SupportNavigationFragment.
        val navContainer = findViewById<View>(R.id.mainLayout)
        ViewCompat.setOnApplyWindowInsetsListener(navContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setBackgroundColor(Color.TRANSPARENT)
            // Apply inset padding so child fragment/content is not obscured by system bars
            view.setPadding(
                systemBars.left,
                0,
                systemBars.right,
                systemBars.bottom
            )

            // If you prefer only top & bottom padding, uncomment below and comment the above:
            // view.setPadding(0, systemBars.top, 0, systemBars.bottom)

            insets
        }
        // Request an immediate apply of insets (useful if called before layout pass)
        ViewCompat.requestApplyInsets(navContainer)

        // Start foreground service EARLY - ensures location tracking starts
        // regardless of permission state. It will wait for permissions but won't lose time.
        startBleForegroundService()

        initializeNavigator()
        initializePlacePickers()
        loadOpenChargeMapReferenceData()
        setupBottomSheet()
        setupUI()
        observeViewModel()
        // ensurePermissionsAndStart() is called in setupUI(), s/local.propertiesnao no need for duplicate maybeStartLocationTracking()
    }
    
    fun initializeNavigator() {
        // Initialize the Navigation SDK
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            /**
             * Sets up the navigation UI when the navigator is ready for use.
             */
            override fun onNavigatorReady(navigator: Navigator) {
                displayMessage("Navigator ready.")
                mNavigator = navigator

                // Set the travel mode.
                mRoutingOptions = RoutingOptions().travelMode(RoutingOptions.TravelMode.DRIVING)
            }

            /**
             * Handles errors from the Navigation SDK.
             * @param errorCode The error code returned by the navigator.
             */
            override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> displayMessage("Error: Your API key is invalid or not authorized.")
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> displayMessage("Error: User did not accept the Navigation Terms of Use.")
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> displayMessage("Error: Location permission is missing.")
                    else -> displayMessage("Error loading Navigation SDK: $errorCode")
                }
            }
        })
    }

    private fun initializePlacePickers() {
        // Initialize Places SDK (uses API key from AndroidManifest.xml meta-data)
        if (!Places.isInitialized()) {
            try {
                Places.initialize(applicationContext, BuildConfig.NAVIGATOR_API_KEY)
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize Places SDK", e)
                return
            }
        }

        // Get the place picker fragments
        originPlacePicker = supportFragmentManager.findFragmentById(R.id.originPlacePickerFragment) as? AutocompleteSupportFragment
        destinationPlacePicker = supportFragmentManager.findFragmentById(R.id.destinationPlacePickerFragment) as? AutocompleteSupportFragment

        originPlacePicker?.apply {
            setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
            setOnPlaceSelectedListener(object : com.google.android.libraries.places.widget.listener.PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    originPlace = place
                    AppLogger.i("Origin selected: ${place.displayName}")
                    updateRoute()
                }

                override fun onError(status: com.google.android.gms.common.api.Status) {
                    AppLogger.w("Origin picker error: ${status.statusMessage}")
                }
            })
        }

        destinationPlacePicker?.apply {
            setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS))
            setOnPlaceSelectedListener(object : com.google.android.libraries.places.widget.listener.PlaceSelectionListener {
                override fun onPlaceSelected(place: Place) {
                    destinationPlace = place
                    AppLogger.i("Destination selected: ${place.displayName}")
                    updateRoute()
                }

                override fun onError(status: com.google.android.gms.common.api.Status) {
                    AppLogger.w("Destination picker error: ${status.statusMessage}")
                }
            })
        }
    }

    private fun updateRoute() {
        val origin = originLatLng ?: currentLocation
        val destination = destinationPlace?.latLng
        AppLogger.i("updateRoute: origin=${origin?.latitude},${origin?.longitude} destination=${destination?.latitude},${destination?.longitude}")

        if (origin != null && destination != null) {
            calculateAndDisplayRoute(origin, destination)
        } else {
            AppLogger.w("updateRoute: Cannot update route - origin or destination null")
        }
    }

    private fun calculateAndDisplayRoute(origin: LatLng, destination: LatLng) {
        AppLogger.i("calculateAndDisplayRoute started")
        lifecycleScope.launch {
            try {
                val routeResult = fetchRouteTokenAndPolyline(origin, destination)
                if (routeResult == null) {
                    AppLogger.w("Routes API returned no routes")
                    binding.routeInfoPanel.visibility = View.GONE
                    return@launch
                }

                currentRouteToken = routeResult.first
                currentRoutePolyline = routeResult.second
                AppLogger.i("Route received: token=${currentRouteToken?.take(20)}... polyline=${currentRoutePolyline?.take(20)}...")
                displayRouteInfo(origin, destination)
            } catch (e: Exception) {
                AppLogger.e("Error calculating route", e)
            }
        }
    }

    private fun displayRouteInfo(origin: LatLng, destination: LatLng) {
        // Calculate distance and duration estimate using Haversine formula and average speed
        val distance = haversineDistance(origin.latitude, origin.longitude, destination.latitude, destination.longitude)
        val durationHours = distance / 60.0 // Assume average 60 km/h
        val durationMinutes = (durationHours * 60).roundToInt()
        AppLogger.i("displayRouteInfo: distance=%.1f km, duration=%d min".format(distance, durationMinutes))

        binding.routeDistance.text = String.format(Locale.getDefault(), "%.1f km", distance)
        binding.routeDuration.text = if (durationMinutes < 60) {
            "${durationMinutes} min"
        } else {
            val hours = durationMinutes / 60
            val mins = durationMinutes % 60
            "${hours}h ${mins}min"
        }

        binding.routeInfoPanel.visibility = View.VISIBLE
        binding.startNavigationButton.setOnClickListener {
            startNavigation(destination)
        }
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun startNavigation(destination: LatLng) {
        AppLogger.i("startNavigation: destination=${destination.latitude},${destination.longitude}")
        navigateToPlace(destination)
    }

    private fun displayMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toggleRoutePanel() {
        routePanelVisible = !routePanelVisible
        binding.routePlanningPanel.visibility = if (routePanelVisible) View.VISIBLE else View.GONE
        if (routePanelVisible) {
            binding.routeToggleButton.text = "Hide planner"
        } else {
            binding.routeToggleButton.text = if (navigationActive) "Stop navigation" else "Plan route"
        }
    }

    private fun setNavigationActive(active: Boolean) {
        navigationActive = active
        binding.navigationStatusChip.visibility = if (active) View.VISIBLE else View.GONE
        binding.routeToggleButton.text = if (active) "Stop navigation" else "Plan route"
        if (active) {
            binding.routePlanningPanel.visibility = View.GONE
            routePanelVisible = false
        }
    }

    private fun stopNavigation() {
        try {
            // Stop any running simulation (used in debug builds to fake movement)
            mNavigator?.simulator?.unsetUserLocation()

            mNavigator?.stopGuidance()
            // Clear the route from the map
            mNavigator?.clearDestinations()
            if(::mRoadSnappedLocationProvider.isInitialized) {
                mRoadSnappedLocationProvider.removeLocationListener(mLocationListener)
            }
            AppLogger.i("Navigation stopped")
        } catch (e: Exception) {
            AppLogger.e("Failed to stop navigation", e)
        }
        setNavigationActive(false)
    }

    fun navigateToPlace(destination: LatLng) {
        AppLogger.i("navigateToPlace called for ${destination.latitude},${destination.longitude}")
        val navigator = mNavigator
        
        if (navigator == null) {
            AppLogger.e("navigateToPlace: Navigator not initialized")
            displayMessage("Navigator not initialized yet.")
            return
        }

        val waypoint: Waypoint
        try {
            waypoint = Waypoint.builder()
                .setTitle("Destination")
                .setLatLng(destination.latitude, destination.longitude)
                .build()
        } catch (e: Waypoint.UnsupportedPlaceIdException) {
            displayMessage("Error creating waypoint: ${e.message}")
            return
        }

        val routeToken = currentRouteToken
        if (routeToken.isNullOrBlank()) {
            AppLogger.w("navigateToPlace: Route token not ready")
            displayMessage("Route not ready yet. Please retry.")
            return
        }
        AppLogger.i("navigateToPlace: Using route token=${routeToken.take(20)}...")

        val customRoutesOptions = CustomRoutesOptions.builder()
            .setRouteToken(routeToken)
            .setTravelMode(CustomRoutesOptions.TravelMode.DRIVING)
            .build()

        // Set the destination and start navigation
        val pendingRoute = navigator.setDestinations(listOf(waypoint), customRoutesOptions)
        pendingRoute.setOnResultListener { code ->
            when (code) {
                Navigator.RouteStatus.OK -> {
                    AppLogger.i("navigateToPlace: RouteStatus.OK")
                    // Register some listeners for navigation events.
                    registerNavigationListeners()

                    displayMessage("Route calculated successfully.")
                    if (BuildConfig.DEBUG) {
                        AppLogger.i("navigateToPlace: Starting location simulation at 5x speed")
                        navigator
                            .getSimulator()
                            .simulateLocationsAlongExistingRoute(
                                SimulationOptions().speedMultiplier(5f))
                    }
                    // Start guidance
                    navigator.startGuidance()
                    displayMessage("Navigation started.")
                    setNavigationActive(true)
                    AppLogger.i("Navigation started")
                }
                Navigator.RouteStatus.NO_ROUTE_FOUND -> {
                    AppLogger.w("navigateToPlace: No route found")
                    displayMessage("No route found.")
                }
                Navigator.RouteStatus.NETWORK_ERROR -> {
                    AppLogger.e("navigateToPlace: Network error while calculating route")
                    displayMessage("Network error while calculating route.")
                }
                Navigator.RouteStatus.ROUTE_CANCELED -> {
                    AppLogger.w("navigateToPlace: Route calculation canceled")
                    displayMessage("Route calculation canceled.")
                }
                else -> {
                    AppLogger.e("navigateToPlace: Error calculating route: $code")
                    displayMessage("Error calculating route: $code")
                }
            }
        }
    }


    fun registerNavigationListeners() {
        mRoadSnappedLocationProvider = NavigationApi.getRoadSnappedLocationProvider(application)!!

        // Create and register location listener
        mLocationListener = object : RoadSnappedLocationProvider.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                // Update location in navigator
                AppLogger.i("Navigator location update: lat=${location.latitude}, lon=${location.longitude}")
            }
        }
        mRoadSnappedLocationProvider.addLocationListener(mLocationListener)
    }

    private suspend fun fetchRouteTokenAndPolyline(
        origin: LatLng,
        destination: LatLng
    ): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        AppLogger.i("fetchRouteTokenAndPolyline: origin=${origin.latitude},${origin.longitude} dest=${destination.latitude},${destination.longitude}")
        val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", BuildConfig.NAVIGATOR_API_KEY)
            setRequestProperty(
                "X-Goog-FieldMask",
                "routes.polyline.encodedPolyline,routes.routeToken"
            )
            setRequestProperty("X-Android-Package", "com.rw251.pleasecharge")
            setRequestProperty("X-Android-Cert", BuildConfig.RELEASE_SHA1.replace(":", ""))
            connectTimeout = 10000
            readTimeout = 10000
            doOutput = true
        }
        AppLogger.d("Routes API request prepared")      

        val requestBody = JSONObject()
            .put(
                "origin",
                JSONObject().put(
                    "location",
                    JSONObject().put(
                        "latLng",
                        JSONObject()
                            .put("latitude", origin.latitude)
                            .put("longitude", origin.longitude)
                    )
                )
            )
            .put(
                "destination",
                JSONObject().put(
                    "location",
                    JSONObject().put(
                        "latLng",
                        JSONObject()
                            .put("latitude", destination.latitude)
                            .put("longitude", destination.longitude)
                    )
                )
            )
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
            .toString()

        connection.outputStream.use { output ->
            output.write(requestBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        AppLogger.d("Routes API response code: $responseCode")
        val responseBody = (if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        })?.bufferedReader()?.use { it.readText() } ?: ""

        if (responseCode !in 200..299) {
            AppLogger.e("Routes API error $responseCode: $responseBody")
            return@withContext null
        }
        AppLogger.d("Routes API success: response length=${responseBody.length}")

        val json = JSONObject(responseBody)
        val routes = json.optJSONArray("routes") ?: run {
            AppLogger.w("fetchRouteTokenAndPolyline: No routes in response")
            return@withContext null
        }
        if (routes.length() == 0) {
            AppLogger.w("fetchRouteTokenAndPolyline: Routes array is empty")
            return@withContext null
        }
        AppLogger.d("fetchRouteTokenAndPolyline: Found ${routes.length()} route(s)")

        val route = routes.getJSONObject(0)
        val routeToken = if (route.has("routeToken")) route.getString("routeToken") else null
        val polyline = route.optJSONObject("polyline")?.optString("encodedPolyline", "")
        AppLogger.i("fetchRouteTokenAndPolyline: Success - token present=${!routeToken.isNullOrEmpty()}, polyline length=${polyline?.length ?: 0}")

        fetchChargingPoints(polyline, includeCCS = true, includeType2 = false)

        Pair(routeToken, polyline)
    }

    /**
     * Get operator name by ID from OpenChargeMap reference data
     */
    private fun getOperatorName(operatorId: Int?): String {
        if (operatorId == null) return "Unknown"
        return openChargeMapRefData?.operators?.find { 
            (it["ID"] as? Int) == operatorId 
        }?.let { 
            it["Title"] as? String ?: "Unknown"
        } ?: "Unknown"
    }

    /**
     * Get status type by ID from OpenChargeMap reference data
     */
    private fun getStatusType(statusTypeId: Int?): String {
        if (statusTypeId == null) return "Unknown"
        return openChargeMapRefData?.statusTypes?.find { 
            (it["ID"] as? Int) == statusTypeId 
        }?.let { 
            it["Title"] as? String ?: "Unknown"
        } ?: "Unknown"
    }

    /**
     * Load reference data from OpenChargeMap API
     * This helps hydrate the compact response from later API calls
     */
    private fun loadOpenChargeMapReferenceData() {
        lifecycleScope.launch {
            try {
                AppLogger.i("Loading OpenChargeMap reference data...")
                val apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY
                if (apiKey.isEmpty()) {
                    AppLogger.w("OpenChargeMap API key not configured in BuildConfig")
                    return@launch
                }

                val refData = withContext(Dispatchers.IO) {
                    val url = URL("https://api.openchargemap.io/v3/referencedata")
                    val params = mapOf("key" to apiKey)
                    val urlWithParams = "$url?${params.entries.joinToString("&") { "${it.key}=${it.value}" }}"
                    
                    val connection = (URL(urlWithParams).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 10000
                    }

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        AppLogger.e("OpenChargeMap reference data API error: $responseCode")
                        return@withContext null
                    }

                    val responseBody = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (responseBody.contains("REJECTED_APIKEY_INVALID")) {
                        AppLogger.e("OpenChargeMap API key invalid")
                        return@withContext null
                    }

                    val json = JSONObject(responseBody)
                    OpenChargeMapReferenceData(
                        connectionTypes = parseJsonArray(json.optJSONArray("ConnectionTypes")),
                        operators = parseJsonArray(json.optJSONArray("Operators")),
                        statusTypes = parseJsonArray(json.optJSONArray("StatusTypes")),
                        countries = parseJsonArray(json.optJSONArray("Countries")),
                        usageTypes = parseJsonArray(json.optJSONArray("UsageTypes")),
                        chargerTypes = parseJsonArray(json.optJSONArray("ChargerTypes")),
                        currentTypes = parseJsonArray(json.optJSONArray("CurrentTypes")),
                        powerLevels = parseJsonArray(json.optJSONArray("PowerLevels"))
                    )
                }

                if (refData != null) {
                    openChargeMapRefData = refData
                    AppLogger.i("OpenChargeMap reference data loaded successfully")
                } else {
                    AppLogger.w("Failed to load OpenChargeMap reference data")
                }
            } catch (e: Exception) {
                AppLogger.e("Error loading OpenChargeMap reference data", e)
            }
        }
    }

    /**
     * Helper to parse JSON array to list of maps
     */
    private fun parseJsonArray(array: org.json.JSONArray?): List<Map<String, Any>> {
        if (array == null) return emptyList()
        val result = mutableListOf<Map<String, Any>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val map = mutableMapOf<String, Any>()
            obj.keys().forEach { key ->
                val value = obj.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    map[key] = value
                }
            }
            if (map.isNotEmpty()) result.add(map)
        }
        return result
    }

    /**
     * Fetch charging points from OpenChargeMap API using the route polyline
     */
    suspend fun fetchChargingPoints(
        polylineString: String?,
        includeCCS: Boolean = true,
        includeType2: Boolean = false
    ): List<ChargingPoint> = withContext(Dispatchers.IO) {
        AppLogger.i("fetchChargingPoints: polyline length=${polylineString?.length}, CCS=$includeCCS, Type2=$includeType2")

        try {
            val apiKey = BuildConfig.OPEN_CHARGE_MAP_API_KEY
            if (apiKey.isEmpty()) {
                AppLogger.w("OpenChargeMap API key not configured")
                return@withContext emptyList()
            }

            val url = URL("https://api.openchargemap.io/v3/poi")
            val params = mutableMapOf(
                "key" to apiKey,
                "polyline" to polylineString,
                "distance" to "0.4",
                "maxresults" to "500",
                "compact" to "true",
                "verbose" to "false"
            )

            // Build connector type filters
            val ccsIds = mutableListOf<Int>()
            val type2Ids = mutableListOf<Int>()
            openChargeMapRefData?.connectionTypes?.forEach { ct ->
                val title = (ct["Title"] as? String ?: "").lowercase()
                val id = ct["ID"] as? Int ?: return@forEach
                when {
                    title.contains("ccs") -> ccsIds.add(id)
                    title.contains("type 2") -> type2Ids.add(id)
                }
            }

            val connectorIds = mutableListOf<Int>()
            if (includeCCS) connectorIds.addAll(ccsIds)
            if (includeType2) connectorIds.addAll(type2Ids)

            if (connectorIds.isNotEmpty()) {
                params["connectiontypeid"] = connectorIds.distinct().joinToString(",")
            } else {
                AppLogger.w("fetchChargingPoints: No connector filters enabled")
            }

            val urlWithParams = "$url?${params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }}"
            val connection = (URL(urlWithParams).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            AppLogger.d("OpenChargeMap API response code: $responseCode")

            if (responseCode !in 200..299) {
                AppLogger.e("OpenChargeMap API error: $responseCode")
                return@withContext emptyList()
            }

            val responseBody = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            AppLogger.d("OpenChargeMap response length: ${responseBody.length}")

            val array = org.json.JSONArray(responseBody)
            val chargingPoints = mutableListOf<ChargingPoint>()

            for (i in 0 until array.length()) {
                val pointJson = array.optJSONObject(i) ?: continue
                
                val addressInfo = pointJson.optJSONObject("AddressInfo") ?: JSONObject()
                val connections = mutableListOf<Map<String, Any>>()
                pointJson.optJSONArray("Connections")?.let { connArray ->
                    for (j in 0 until connArray.length()) {
                        connArray.optJSONObject(j)?.let { connJson ->
                            connections.add(connJson.toMap())
                        }
                    }
                }

                // Calculate CCS and Type2 points
                val ccsPoints = connections
                    .filter { (it["ConnectionTypeID"] as? Int) in ccsIds }
                    .sumOf { (it["Quantity"] as? Int) ?: 1 }

                val type2Points = connections
                    .filter { (it["ConnectionTypeID"] as? Int) in type2Ids }
                    .sumOf { (it["Quantity"] as? Int) ?: 1 }

                val point = ChargingPoint(
                    id = pointJson.optInt("ID", 0),
                    title = addressInfo.optString("Title"),
                    latitude = addressInfo.optDouble("Latitude", 0.0),
                    longitude = addressInfo.optDouble("Longitude", 0.0),
                    operatorId = pointJson.optInt("OperatorID", 0).takeIf { it != 0 },
                    statusTypeId = pointJson.optInt("StatusTypeID", 0).takeIf { it != 0 },
                    connections = connections,
                    operator = getOperatorName(pointJson.optInt("OperatorID", 0).takeIf { it != 0 }),
                    status = getStatusType(pointJson.optInt("StatusTypeID", 0).takeIf { it != 0 }),
                    ccsPoints = ccsPoints,
                    type2Points = type2Points
                )
                
                chargingPoints.add(point)
            }

            AppLogger.i("fetchChargingPoints: Received ${chargingPoints.size} charging points")
            chargingPoints
        } catch (e: Exception) {
            AppLogger.e("Error fetching charging points", e)
            emptyList()
        }
    }

    // private fun setupMap() {
    //     binding.mapView.apply {
    //         setTileSource(TileSourceFactory.MAPNIK)
    //         // Disable all touch interactions - map is fixed to user location
    //         setMultiTouchControls(false)
    //         zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    //         // Disable scrolling and zooming
    //         setScrollableAreaLimitDouble(null)
    //         isFlingEnabled = false
    //         // Disable horizontal and vertical scroll (prevents drag/pan)
    //         isHorizontalMapRepetitionEnabled = false
    //         isVerticalMapRepetitionEnabled = false
    //         controller.setZoom(17.0)
    //         // Default to UK center initially
    //         controller.setCenter(GeoPoint(54.0, -2.0))
            
    //         // Override touch events to completely disable map interaction
    //         // but still allow touches to pass through to the bottom sheet
    //         overlays.add(0, object : org.osmdroid.views.overlay.Overlay() {
    //             override fun onDoubleTap(
    //                 e: android.view.MotionEvent?,
    //                 pMapView: org.osmdroid.views.MapView?
    //             ): Boolean {
    //                 // Consume double-tap to prevent zoom
    //                 return true
    //             }
                
    //             override fun onScroll(
    //                 pEvent1: android.view.MotionEvent?,
    //                 pEvent2: android.view.MotionEvent?,
    //                 pDistanceX: Float,
    //                 pDistanceY: Float,
    //                 pMapView: org.osmdroid.views.MapView?
    //             ): Boolean {
    //                 // Consume scroll to prevent pan
    //                 return true
    //             }
                
    //             override fun onFling(
    //                 pEvent1: android.view.MotionEvent?,
    //                 pEvent2: android.view.MotionEvent?,
    //                 pVelocityX: Float,
    //                 pVelocityY: Float,
    //                 pMapView: org.osmdroid.views.MapView?
    //             ): Boolean {
    //                 // Consume fling to prevent pan
    //                 return true
    //             }
    //         })
    //     }
        
    //     // Create location marker
    //     locationMarker = Marker(binding.mapView).apply {
    //         setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    //         icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.location_dot)
    //         title = "Current Location"
    //     }
    //     binding.mapView.overlays.add(locationMarker)
    // }
    
    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        
        // Get navigation bar height to add to peek height
        val navBarHeight = getNavigationBarHeight()
        val basePeekHeight = resources.getDimensionPixelSize(R.dimen.bottom_sheet_peek_height)
        
        bottomSheetBehavior.apply {
            peekHeight = basePeekHeight + navBarHeight
            isHideable = false
            isFitToContents = true
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        
        // Add padding for navigation bar (use fixed value, not cumulative)
        val basePadding = resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)
        binding.bottomSheet.setPadding(
            binding.bottomSheet.paddingLeft,
            binding.bottomSheet.paddingTop,
            binding.bottomSheet.paddingRight,
            basePadding + navBarHeight
        )
        
        // Initially hide expanded content
        binding.expandedContent.alpha = 0f
        
        // Toggle expanded content visibility based on state
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // Don't hide the view, just control alpha
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.expandedContent.alpha = 1f
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.expandedContent.alpha = 0f
                    }
                    else -> { /* Keep current alpha */ }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Fade in/out expanded content as sheet slides
                binding.expandedContent.alpha = slideOffset.coerceIn(0f, 1f)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun setupUI() {
        binding.routePlanningPanel.visibility = View.GONE
        binding.navigationStatusChip.visibility = View.GONE
        binding.routeToggleButton.text = "Plan route"

        binding.routeToggleButton.setOnClickListener {
            if (navigationActive) {
                stopNavigation()
            } else {
                toggleRoutePanel()
            }
        }

        // Bottom sheet buttons
        binding.viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.exportButton.setOnClickListener {
            exportDataFiles()
        }
        
        // Auto-start connection - check permissions first
        ensurePermissionsAndStart()
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        updateBleStatus(state)
                    }
                }
                launch {
                    viewModel.distanceMiles.collect { miles ->
                        currentDistanceMiles = miles
                        updateDistanceDisplay()
                    }
                }
                launch {
                    viewModel.avgSpeedMph.collect { mph ->
                        currentSpeedMph = mph
                        updateSpeedDisplay()
                    }
                }
                launch {
                    viewModel.gpsStatus.collect { status ->
                        binding.topGpsStatus.text = status
                    }
                }
                launch {
                    viewModel.serviceTimeoutSeconds.collect { seconds ->
                        if (seconds != null && seconds > 0) {
                            binding.topTimeoutCountdown.visibility = View.VISIBLE
                            val minutes = seconds / 60
                            val secs = seconds % 60
                            binding.topTimeoutCountdown.text = "Auto-stop in: ${minutes}m ${secs}s"
                            // Only show "Reconnecting" if we've previously connected
                            // Otherwise let the normal BLE state show through (Scanning, Connecting, etc.)
                            if (ServiceStatus.hasConnectedBefore.value) {
                                binding.topBleStatus.text = "OBD: Reconnecting..."
                                binding.topBleStatus.setTextColor("#FF9800".toColorInt())  // Orange for reconnecting
                            }
                        } else {
                            binding.topTimeoutCountdown.visibility = View.GONE
                        }
                    }
                }
                launch {
                    ServiceStatus.timeoutSecondsRemaining.collect { seconds ->
                        viewModel.setServiceTimeoutSeconds(seconds)
                    }
                }
                launch {
                    ServiceStatus.isServiceRunning.collect { isRunning ->
                        if (!isRunning) {
                            // Service has stopped - update UI
                            binding.topBleStatus.text = "Disconnected - please reopen app to start again"
                            binding.topBleStatus.setTextColor("#F44336".toColorInt())
                            binding.topTimeoutCountdown.visibility = View.GONE
                            binding.topGpsStatus.text = "GPS: Offline"
                            binding.topGpsStatus.setTextColor("#F44336".toColorInt())
                        }
                    }
                }
            }
        }
    }
    
    private fun updateBleStatus(state: BleObdManager.State) {
       
        // Update top panel BLE status
        val topStatusText = when (state) {
            BleObdManager.State.DISCONNECTED -> "OBD: Disconnected"
            BleObdManager.State.SCANNING -> "Scanning for OBD device..."
            BleObdManager.State.CONNECTING -> "Connecting to OBD..."
            BleObdManager.State.DISCOVERING -> "Discovering services..."
            BleObdManager.State.CONFIGURING -> "Configuring OBD adapter..."
            BleObdManager.State.READY -> "OBD: Connected ✓"
        }
        binding.topBleStatus.text = topStatusText
        
        // Color coding
        val color = when (state) {
            BleObdManager.State.READY -> "#4CAF50".toColorInt()
            BleObdManager.State.DISCONNECTED -> "#F44336".toColorInt()
            else -> "#2196F3".toColorInt()
        }
        binding.topBleStatus.setTextColor(color)

        if (state == BleObdManager.State.READY) {
            if (journeyStartTime == null) {
                journeyStartTime = System.currentTimeMillis()
                startDurationTimer()
            }
        }
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateSocDisplay(pct: Double) {
        currentSocPct = pct
        binding.socValue.text = String.format(Locale.getDefault(), "%.0f", pct)
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateTempDisplay(celsius: Double) {
        currentTempC = celsius
        binding.tempValue.text = String.format(Locale.getDefault(), "%.0f", celsius)
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateSpeedDisplay() {
        val speed = currentSpeedMph
        binding.speedValue.text = speed?.let { 
            String.format(Locale.getDefault(), "%.0f", it) 
        } ?: "--"
        
        // Also update avg speed in expanded section
        binding.avgSpeedValue.text = speed?.let { 
            String.format(Locale.getDefault(), "%.0f", it) 
        } ?: "--"
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateDistanceDisplay() {
        val dist = currentDistanceMiles
        binding.distanceValue.text = dist?.let { 
            String.format(Locale.getDefault(), "%.1f", it) 
        } ?: "0.0"
    }
    
    @SuppressLint("SetTextI18n")
    
    private fun updateDurationDisplay() {
        journeyStartTime?.let { start ->
            val durationMs = System.currentTimeMillis() - start
            val hours = (durationMs / 3600000).toInt()
            val minutes = ((durationMs % 3600000) / 60000).toInt()
            val seconds = ((durationMs % 60000) / 1000).toInt()
            binding.durationValue.text = String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } ?: run {
            binding.durationValue.text = "0:00:00"
        }
    }
    
    private fun startDurationTimer() {
        if (durationJob != null) return
        durationJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)  // Update every second
                updateDurationDisplay()
            }
        }
    }
    
    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationJob != null) return
        
        // Just subscribe to LocationTracker metrics (foreground service handles starting it)
        locationJob = lifecycleScope.launch {
            LocationTracker.metrics.collect { metrics ->
                metrics?.let {
                    viewModel.setLocationStats(it.totalTripDistanceMiles, it.averageSpeedMph)
                    // Update current location and default origin
                    currentLocation = LatLng(it.lat, it.lon)
                    if (originPlace == null) {
                        originLatLng = currentLocation
                        originPlacePicker?.setText("Current location")
                        if (destinationPlace != null) {
                            updateRoute()
                        }
                    }
                    // Update GPS status in top panel
                    val gpsStatus = "GPS: %.5f, %.5f".format(it.lat, it.lon)
                    viewModel.setGpsStatus(gpsStatus)
                }
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        needed += Manifest.permission.BLUETOOTH_SCAN
        needed += Manifest.permission.BLUETOOTH_CONNECT
        // Some devices still require location for BLE scan
        needed += Manifest.permission.ACCESS_FINE_LOCATION
        needed += Manifest.permission.ACCESS_COARSE_LOCATION

        // Check if we already have all permissions
        val hasAll = needed.all { 
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED 
        }

        if (hasAll) {
            AppLogger.i("All permissions already granted")
            maybeRequestBackgroundLocation()
            startLocationTracking()
            startBleManager()
        } else {
            AppLogger.i("Requesting permissions: $needed")
            viewModel.appendLog("Requesting permissions...")
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
    
    private fun maybeRequestBackgroundLocation() {
        // Check if we already have it
        val hasBackgroundLocation = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (hasBackgroundLocation) {
            AppLogger.i("Background location already granted")
            return
        }
        
        // Show explanation dialog before requesting
        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage("To record journey data when the screen is off or you're using Android Auto, " +
                "please allow location access 'All the time' on the next screen.")
            .setPositiveButton("Continue") { _, _ ->
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Skip") { _, _ ->
                AppLogger.i("User skipped background location permission")
                viewModel.appendLog("Background location skipped - recording may stop when screen is off")
            }
            .show()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startBleManager() {
        if (manager == null) {
            manager = BleConnectionManager.getOrCreateManager(
                context = this,
                listener = createBleListener()
            )
        }
        // Foreground service already started in onCreate - just start the BLE manager
        manager?.start()
    }

    private fun createBleListener(): BleObdManager.Listener {
        return CommonBleListener(
            tag = "MainActivity",
            callbacks = Callbacks(
                onStatus = { status ->
                    runOnUiThread { viewModel.setStatus(status) }
                },
                onReady = {
                    runOnUiThread { viewModel.setReady(true) }
                },
                onSoc = { raw, pct, _ ->
                    runOnUiThread {
                        viewModel.setSoc(
                            display = "SOC: ${String.format(Locale.getDefault(), "%.1f", pct)}% (raw: $raw)",
                            time = "Time: ${nowString()}"
                        )
                        updateSocDisplay(pct)
                    }
                },
                onTemp = { celsius, _ ->
                    runOnUiThread {
                        updateTempDisplay(celsius)
                    }
                },
                onError = { msg, ex ->
                    runOnUiThread {
                        viewModel.appendLog("ERROR: $msg${if (ex != null) " - ${ex.message}" else ""}")
                    }
                },
                onLog = { line ->
                    runOnUiThread {
                        viewModel.appendLog(line)
                    }
                },
                onStateChanged = { state ->
                    runOnUiThread {
                        viewModel.setState(state)
                        if (state != BleObdManager.State.READY) {
                            viewModel.setReady(false)
                        }
                    }
                }
            )
        )
    }

    override fun onResume() {
        super.onResume()
        // binding.mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        // binding.mapView.onPause()
    }

    override fun onDestroy() {
        AppLogger.i("MainActivity destroyed")
        stopNavigation()
        locationJob?.cancel()
        stopDurationTimer()
        LocationTracker.stop()
        super.onDestroy()
    }

    private fun nowString(): String = 
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    
    private fun getNavigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun startBleForegroundService() {
        val intent = Intent(this, BleForegroundService::class.java).apply {
            action = BleForegroundService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun exportDataFiles() {
        try {
            val logPath = AppLogger.getLogFilePath()
            val csvPath = DataCapture.getCsvFilePath()
            val files = mutableListOf<android.net.Uri>()

            if (logPath != null) {
                val logFile = java.io.File(logPath)
                if (logFile.exists() && logFile.length() > 0) {
                    files.add(
                        androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            logFile
                        )
                    )
                }
            }

            if (csvPath != null) {
                val csvFile = java.io.File(csvPath)
                if (csvFile.exists() && csvFile.length() > 0) {
                    files.add(
                        androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            csvFile
                        )
                    )
                }
            }

            if (files.isEmpty()) {
                viewModel.appendLog("No data files to export")
                return
            }

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
                putExtra(Intent.EXTRA_SUBJECT, "PleaseCharge Data Export")
                putExtra(Intent.EXTRA_TEXT, "Exported logs and vehicle data from PleaseCharge app.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Export data via"))
            AppLogger.i("Data files exported: ${files.size} files")
            viewModel.appendLog("Exported ${files.size} file(s)")
        } catch (e: Exception) {
            AppLogger.e("Failed to export data files", e)
            viewModel.appendLog("Export failed: ${e.message}")
        }
    }
}
