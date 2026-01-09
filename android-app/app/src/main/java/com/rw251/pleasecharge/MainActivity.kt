package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
    private var chargerMinDistanceMiles: Int = 0 // Default 0 miles from start
    private var chargerMaxDistanceMiles: Int = 300 // Default 300 miles from start
    private var includeCCSConnectors: Boolean = true // Default CCS on
    private var includeType2Connectors: Boolean = false // Default Type 2 off
    
    // Track what has been fetched from API
    private var lastFetchedMaxDistanceMiles: Int = 0
    private var fetchedWithCCS: Boolean = false
    private var fetchedWithType2: Boolean = false
    private var allFetchedChargers: List<ChargingPoint> = emptyList() // Raw unfiltered results

    // Route polyline tracking
    private var decodedRoutePath: List<LatLng> = emptyList()
    private var routeCumulativeDistances: List<Double> = emptyList() // in meters
    
    // Store origin/destination for deviation calculations
    private var routeOrigin: LatLng? = null
    private var routeDestination: LatLng? = null
    private var directRouteDurationSeconds: Long = 0
    
    // Charger list tracking
    private var allChargingPoints: List<ChargingPoint> = emptyList()
    private var currentLocationMeters: Double = 0.0 // Current distance along route in meters

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
        val type2Points: Int = 0,
        var distanceAlongRoute: Double = 0.0, // meters from route start
        var deviationSeconds: Long? = null, // additional time vs direct route
        var routeToChargerSeconds: Long? = null,
        var routeFromChargerSeconds: Long? = null
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

    private fun setupChargerConfigControls() {
        // Min distance input - update immediately as user types
        binding.chargerMinDistanceInput.setText(chargerMinDistanceMiles.toString())
        binding.chargerMinDistanceInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().toIntOrNull() ?: 0
                val newMin = value.coerceAtLeast(0)
                if (newMin != chargerMinDistanceMiles) {
                    chargerMinDistanceMiles = newMin
                    // Requirement 2: Update charger list immediately
                    applyFiltersAndUpdateChargerList()
                }
            }
        })

        // Max distance input - update immediately as user types
        binding.chargerMaxDistanceInput.setText(chargerMaxDistanceMiles.toString())
        binding.chargerMaxDistanceInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().toIntOrNull() ?: 300
                val newMax = value.coerceAtLeast(0)
                if (newMax != chargerMaxDistanceMiles) {
                    val oldMax = chargerMaxDistanceMiles
                    chargerMaxDistanceMiles = newMax
                    
                    // Requirement 3: Reducing max has no effect (just filter existing)
                    if (newMax <= oldMax) {
                        applyFiltersAndUpdateChargerList()
                    }
                    // Requirement 4: Increasing beyond last fetch shows prompt
                    else if (newMax > lastFetchedMaxDistanceMiles) {
                        showRefetchPrompt()
                    } else {
                        applyFiltersAndUpdateChargerList()
                    }
                }
            }
        })

        // Requirement 5: CCS and Type 2 toggle buttons
        binding.connectorCcsToggle.isChecked = includeCCSConnectors
        binding.connectorType2Toggle.isChecked = includeType2Connectors
        
        binding.connectorCcsToggle.setOnCheckedChangeListener { _, isChecked ->
            includeCCSConnectors = isChecked
            AppLogger.i("CCS filter: $isChecked")
            checkIfRefetchNeeded()
        }
        
        binding.connectorType2Toggle.setOnCheckedChangeListener { _, isChecked ->
            includeType2Connectors = isChecked
            AppLogger.i("Type 2 filter: $isChecked")
            checkIfRefetchNeeded()
        }
        
        // Hide refetch button initially
        binding.refetchChargersButton.visibility = View.GONE
        binding.refetchChargersButton.setOnClickListener {
            refetchChargers()
        }
    }
    
    private fun checkIfRefetchNeeded() {
        // Requirement 6: Only show prompt if we need data we don't have
        val needsCCS = includeCCSConnectors && !fetchedWithCCS
        val needsType2 = includeType2Connectors && !fetchedWithType2
        
        if (needsCCS || needsType2) {
            showRefetchPrompt()
        } else {
            // We have all the data we need, just refilter
            applyFiltersAndUpdateChargerList()
            binding.refetchChargersButton.visibility = View.GONE
        }
    }
    
    private fun showRefetchPrompt() {
        if (currentRoutePolyline != null) {
            binding.refetchChargersButton.visibility = View.VISIBLE
        }
    }
    
    private fun refetchChargers() {
        val origin = routeOrigin
        val destination = routeDestination
        if (origin != null && destination != null) {
            lifecycleScope.launch {
                showRouteStatus("Fetching chargers...")
                allFetchedChargers = fetchChargingPoints(
                    currentRoutePolyline, 
                    includeCCS = includeCCSConnectors, 
                    includeType2 = includeType2Connectors
                )
                lastFetchedMaxDistanceMiles = chargerMaxDistanceMiles
                if (includeCCSConnectors) fetchedWithCCS = true
                if (includeType2Connectors) fetchedWithType2 = true
                
                applyFiltersAndUpdateChargerList()
                binding.refetchChargersButton.visibility = View.GONE
                hideRouteStatus()
            }
        }
    }
    
    private fun applyFiltersAndUpdateChargerList() {
        if (allFetchedChargers.isEmpty()) return
        
        // Filter by connector type
        val connectorFiltered = allFetchedChargers.filter { point ->
            (includeCCSConnectors && point.ccsPoints > 0) || 
            (includeType2Connectors && point.type2Points > 0)
        }
        
        // Filter by distance range
        val minMeters = chargerMinDistanceMiles * 1609.344
        val maxMeters = chargerMaxDistanceMiles * 1609.344
        val distanceFiltered = connectorFiltered.filter { point ->
            point.distanceAlongRoute >= minMeters && point.distanceAlongRoute <= maxMeters
        }
        
        // Update the active list and UI
        allChargingPoints = distanceFiltered.sortedBy { it.distanceAlongRoute }
        updateChargerListDisplay()
        
        AppLogger.i("Applied filters: ${allChargingPoints.size} chargers (from ${allFetchedChargers.size} total)")
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

        // Setup charger configuration controls
        setupChargerConfigControls()

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

    private fun showRouteStatus(message: String) {
        runOnUiThread {
            binding.routeStatusText.text = message
            binding.routeStatusOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideRouteStatus() {
        runOnUiThread {
            binding.routeStatusOverlay.visibility = View.GONE
        }
    }

    private fun calculateAndDisplayRoute(origin: LatLng, destination: LatLng) {
        AppLogger.i("calculateAndDisplayRoute started")
        // Store origin and destination for deviation calculations
        routeOrigin = origin
        routeDestination = destination
        
        lifecycleScope.launch {
            try {
                showRouteStatus("Getting route...")
                val routeResult = fetchRouteTokenAndPolyline(origin, destination)
                if (routeResult == null) {
                    AppLogger.w("Routes API returned no routes")
                    binding.routeInfoPanel.visibility = View.GONE
                    hideRouteStatus()
                    return@launch
                }

                currentRouteToken = routeResult.first
                currentRoutePolyline = routeResult.second
                directRouteDurationSeconds = routeResult.third ?: 0L
                AppLogger.i("Route received: token=${currentRouteToken?.take(20)}... polyline=${currentRoutePolyline?.take(20)}... duration=${directRouteDurationSeconds}s")
                
                // Decode polyline for distance calculations
                currentRoutePolyline?.let { polyline ->
                    decodePolylineAndBuildDistances(polyline)
                }
                
                // Now fetch charging points after directRouteDurationSeconds is set
                showRouteStatus("Finding chargers...")
                allFetchedChargers = fetchChargingPoints(currentRoutePolyline, includeCCS = includeCCSConnectors, includeType2 = includeType2Connectors)
                lastFetchedMaxDistanceMiles = chargerMaxDistanceMiles
                fetchedWithCCS = includeCCSConnectors
                fetchedWithType2 = includeType2Connectors
                
                // Apply filters to get the active list
                applyFiltersAndUpdateChargerList()
                AppLogger.i("Chargers fetched: ${allFetchedChargers.size} total, ${allChargingPoints.size} after filters")
                
                showRouteStatus("Ready")
                displayRouteInfo(origin, destination)
                // Show charger list overlay
                binding.chargerListOverlay.visibility = View.VISIBLE
                // Hide status after a short delay
                kotlinx.coroutines.delay(1500)
                hideRouteStatus()
            } catch (e: Exception) {
                AppLogger.e("Error calculating route", e)
                hideRouteStatus()
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
        // Clear chargers and hide list
        allChargingPoints = emptyList()
        allFetchedChargers = emptyList()
        lastFetchedMaxDistanceMiles = 0
        fetchedWithCCS = false
        fetchedWithType2 = false
        binding.chargerListOverlay.visibility = View.GONE
        binding.refetchChargersButton.visibility = View.GONE
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
                
                // Update current position along route and charger list
                if (navigationActive && allChargingPoints.isNotEmpty() && decodedRoutePath.isNotEmpty()) {
                    currentLocationMeters = calculateDistanceAlongRoute(location.latitude, location.longitude)
                    updateChargerListDisplay()
                }
            }
        }
        mRoadSnappedLocationProvider.addLocationListener(mLocationListener)
    }

    private suspend fun fetchRouteTokenAndPolyline(
        origin: LatLng,
        destination: LatLng
    ): Triple<String?, String?, Long?>? = withContext(Dispatchers.IO) {
        AppLogger.i("fetchRouteTokenAndPolyline: origin=${origin.latitude},${origin.longitude} dest=${destination.latitude},${destination.longitude}")
        val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", BuildConfig.NAVIGATOR_API_KEY)
            setRequestProperty(
                "X-Goog-FieldMask",
                "routes.polyline.encodedPolyline,routes.routeToken,routes.duration"
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
        
        // Extract duration in seconds using proper parsing
        val durationString = route.optString("duration", "0s")
        val durationSeconds = parseDurationToSeconds(durationString)
        
        AppLogger.i("fetchRouteTokenAndPolyline: Success - token present=${!routeToken.isNullOrEmpty()}, polyline length=${polyline?.length ?: 0}, duration=${durationSeconds}s")

        Triple(routeToken, polyline, durationSeconds)
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
     * Decode polyline and build cumulative distance array
     */
    private fun decodePolylineAndBuildDistances(encodedPolyline: String) {
        try {
            val decoded = decodePolyline(encodedPolyline)
            decodedRoutePath = decoded
            
            // Build cumulative distances
            val cumulative = mutableListOf<Double>()
            var totalDistance = 0.0
            cumulative.add(0.0)
            
            for (i in 1 until decoded.size) {
                val prev = decoded[i - 1]
                val curr = decoded[i]
                val segmentDist = haversineDistance(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude
                ) * 1000.0 // convert km to meters
                totalDistance += segmentDist
                cumulative.add(totalDistance)
            }
            
            routeCumulativeDistances = cumulative
            AppLogger.i("Decoded polyline: ${decoded.size} points, total distance: ${totalDistance / 1000.0} km")
        } catch (e: Exception) {
            AppLogger.e("Failed to decode polyline", e)
            decodedRoutePath = emptyList()
            routeCumulativeDistances = emptyList()
        }
    }

    /**
     * Truncate polyline to a maximum character length
     * Safely truncates the encoded string to prevent HTTP 414 errors
     */
    private fun truncatePolyline(encodedPolyline: String, maxChars: Int = 6000): String {
        if (encodedPolyline.length <= maxChars) {
            return encodedPolyline
        }
        
        // Simply truncate the encoded string at maxChars
        // The polyline decoder will handle partial/truncated polylines gracefully
        val truncated = encodedPolyline.substring(0, maxChars)
        AppLogger.i("Truncated polyline from ${encodedPolyline.length} to ${truncated.length} chars to avoid HTTP 414")
        return truncated
    }

    /**
     * Decode Google polyline format
     */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    /**
     * Calculate distance along route to a given point (finds nearest route point)
     */
    private fun calculateDistanceAlongRoute(targetLat: Double, targetLng: Double): Double {
        if (decodedRoutePath.isEmpty() || routeCumulativeDistances.isEmpty()) {
            return 0.0
        }

        // Find the nearest route point to the target location
        val nearestIdx = findNearestRoutePointIndex(targetLat, targetLng)
        
        // Return the cumulative distance at that point
        return if (nearestIdx < routeCumulativeDistances.size) {
            val distance = routeCumulativeDistances[nearestIdx]
            AppLogger.d("calculateDistanceAlongRoute(${targetLat}, ${targetLng}): nearestIdx=$nearestIdx/${decodedRoutePath.size}, distance=${distance}m")
            distance
        } else {
            0.0
        }
    }

    /**
     * Find the index of the nearest route point to the target location.
     * This matches the JavaScript implementation's findNearestRouteIndex function.
     */
    private fun findNearestRoutePointIndex(targetLat: Double, targetLng: Double): Int {
        if (decodedRoutePath.isEmpty()) {
            return 0
        }
        
        var nearestIdx = 0
        var minDistMeters = Double.MAX_VALUE

        for (i in decodedRoutePath.indices) {
            val routePoint = decodedRoutePath[i]
            // Use haversine distance in meters
            val distMeters = haversineDistance(
                targetLat, targetLng,
                routePoint.latitude, routePoint.longitude
            ) * 1000.0 // Convert km to meters
            
            if (distMeters < minDistMeters) {
                minDistMeters = distMeters
                nearestIdx = i
            }
        }

        return nearestIdx
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

            // Truncate polyline to avoid HTTP 414 (Request URI Too Long) errors
            val truncatedPolyline = polylineString?.let { truncatePolyline(it) } ?: polylineString

            val url = URL("https://api.openchargemap.io/v3/poi")
            val params = mutableMapOf(
                "key" to apiKey,
                "polyline" to truncatedPolyline,
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
            // log entire response body
            AppLogger.d("OpenChargeMap reference data response: $responseBody")

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
            
            // Calculate distance along route for all points (don't filter yet)
            withContext(Dispatchers.Main) {
                showRouteStatus("Calculating distances...")
            }
            
            val maxDistanceMeters = chargerMaxDistanceMiles * 1609.344 // miles to meters
            val pointsWithDistance = chargingPoints.filter { point ->
                val distanceAlongRoute = calculateDistanceAlongRoute(point.latitude, point.longitude)
                point.distanceAlongRoute = distanceAlongRoute
                // Only filter by max distance for API efficiency
                distanceAlongRoute <= maxDistanceMeters
            }
            
            AppLogger.i("${pointsWithDistance.size} chargers within $chargerMaxDistanceMiles miles of route start")
            
            // Calculate deviations
            withContext(Dispatchers.Main) {
                showRouteStatus("Calculating deviations (${pointsWithDistance.size} chargers)...")
            }
            
            val pointsWithDeviations = calculateDeviationsForChargers(pointsWithDistance)
            
            // Log final results
            AppLogger.i("=== CHARGER DEVIATION RESULTS ===")
            AppLogger.i("Total chargers found: ${chargingPoints.size}")
            AppLogger.i("Up to $chargerMaxDistanceMiles miles: ${pointsWithDistance.size}")
            AppLogger.i("With calculated deviations: ${pointsWithDeviations.size}")
            pointsWithDeviations.forEach { point ->
                val devMinutes = (point.deviationSeconds ?: 0) / 60.0
                AppLogger.i("  ${point.title}: ${point.distanceAlongRoute / 1609.344} mi along route, +${devMinutes.roundToInt()} min deviation")
            }
            
            pointsWithDeviations
        } catch (e: Exception) {
            AppLogger.e("Error fetching charging points", e)
            emptyList()
        }
    }
    
    /**
     * Calculate route deviations for charging points using Google Routes API
     */
    private suspend fun calculateDeviationsForChargers(chargers: List<ChargingPoint>): List<ChargingPoint> = withContext(Dispatchers.IO) {
        val origin = routeOrigin
        val destination = routeDestination
        
        if (origin == null || destination == null) {
            AppLogger.w("Cannot calculate deviations: origin or destination not set")
            return@withContext chargers
        }
        
        val batchSize = 3 // Process 3 at a time to avoid rate limits
        val results = mutableListOf<ChargingPoint>()
        
        for (i in chargers.indices step batchSize) {
            val batch = chargers.subList(i, kotlin.math.min(i + batchSize, chargers.size))
            
            val batchResults = batch.map { point ->
                async(Dispatchers.IO) {
                    calculateChargerDeviation(origin, destination, point)
                }
            }.awaitAll()
            
            results.addAll(batchResults)
            
            // Update status
            withContext(Dispatchers.Main) {
                showRouteStatus("Calculating deviations (${results.size}/${chargers.size})...")
            }
            
            // Small delay to avoid overwhelming the API
            if (i + batchSize < chargers.size) {
                delay(50)
            }
        }
        
        results
    }
    
    /**
     * Parse duration string in format "1h25m30s" to seconds
     */
    private fun parseDurationToSeconds(durationString: String): Long {
        var total = 0L
        val hoursMatch = Regex("""(\d+)h""").find(durationString)
        val minutesMatch = Regex("""(\d+)m""").find(durationString)
        val secondsMatch = Regex("""(\d+)s""").find(durationString)
        
        hoursMatch?.let { total += it.groupValues[1].toLongOrNull() ?: 0L * 3600 }
        minutesMatch?.let { total += it.groupValues[1].toLongOrNull() ?: 0L * 60 }
        secondsMatch?.let { total += it.groupValues[1].toLongOrNull() ?: 0L }
        
        return total
    }

    /**
     * Calculate deviation for a single charger
     */
    private suspend fun calculateChargerDeviation(
        origin: LatLng,
        destination: LatLng,
        charger: ChargingPoint
    ): ChargingPoint = withContext(Dispatchers.IO) {
        try {
            val chargerLatLng = LatLng(charger.latitude, charger.longitude)
            
            // Make Routes API call with waypoint
            val url = URL("https://routes.googleapis.com/directions/v2:computeRoutes")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", BuildConfig.NAVIGATOR_API_KEY)
                setRequestProperty("X-Goog-FieldMask", "routes.duration,routes.legs.duration")
                setRequestProperty("X-Android-Package", "com.rw251.pleasecharge")
                setRequestProperty("X-Android-Cert", BuildConfig.RELEASE_SHA1.replace(":", ""))
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
            }
            
            val requestBody = JSONObject()
                .put("origin", JSONObject().put("location", JSONObject().put("latLng", 
                    JSONObject().put("latitude", origin.latitude).put("longitude", origin.longitude))))
                .put("destination", JSONObject().put("location", JSONObject().put("latLng",
                    JSONObject().put("latitude", destination.latitude).put("longitude", destination.longitude))))
                .put("intermediates", org.json.JSONArray().put(
                    JSONObject().put("location", JSONObject().put("latLng",
                        JSONObject().put("latitude", charger.latitude).put("longitude", charger.longitude)))))
                .put("travelMode", "DRIVE")
                .put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
                .toString()
            
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }
            
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                AppLogger.w("Routes API error for charger ${charger.id}: $responseCode")
                return@withContext charger
            }
            
            val responseBody = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = JSONObject(responseBody)
            val routes = json.optJSONArray("routes")
            
            if (routes != null && routes.length() > 0) {
                val route = routes.getJSONObject(0)
                val legs = route.optJSONArray("legs")
                
                if (legs != null && legs.length() >= 2) {
                    // Leg 0: origin to charger, Leg 1: charger to destination
                    val leg0DurationString = legs.getJSONObject(0).optString("duration", "0s")
                    val leg1DurationString = legs.getJSONObject(1).optString("duration", "0s")
                    
                    val leg0Duration = parseDurationToSeconds(leg0DurationString)
                    val leg1Duration = parseDurationToSeconds(leg1DurationString)
                    
                    val totalWithCharger = leg0Duration + leg1Duration
                    val deviation = totalWithCharger - directRouteDurationSeconds
                    
                    charger.routeToChargerSeconds = leg0Duration
                    charger.routeFromChargerSeconds = leg1Duration
                    charger.deviationSeconds = deviation
                    
                    AppLogger.d("Charger ${charger.id}: leg0=${leg0Duration}s, leg1=${leg1Duration}s, total=${totalWithCharger}s, direct=${directRouteDurationSeconds}s, deviation=${deviation}s")
                }
            }
            
            charger
        } catch (e: Exception) {
            AppLogger.e("Error calculating deviation for charger ${charger.id}", e)
            charger
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
    private fun updateChargerListDisplay() {
        // Find the next 3 chargers after current position
        val nextChargers = allChargingPoints
            .filter { it.distanceAlongRoute >= currentLocationMeters }
            .take(3)
        
        // Debug logging
        if (nextChargers.isNotEmpty()) {
            AppLogger.d("updateChargerList: currentPos=${currentLocationMeters}m, showing ${nextChargers.size} of ${allChargingPoints.size} chargers")
            nextChargers.forEachIndexed { idx, c ->
                AppLogger.d("  [$idx] ${c.title}: ${c.distanceAlongRoute}m along route")
            }
        }
        
        // Update UI for each charger slot
        updateChargerSlot(0, nextChargers, binding.charger1Container, binding.charger1Name, binding.charger1CCS, binding.charger1Deviation, binding.charger1Distance)
        updateChargerSlot(1, nextChargers, binding.charger2Container, binding.charger2Name, binding.charger2CCS, binding.charger2Deviation, binding.charger2Distance)
        updateChargerSlot(2, nextChargers, binding.charger3Container, binding.charger3Name, binding.charger3CCS, binding.charger3Deviation, binding.charger3Distance)
    }
    
    @SuppressLint("SetTextI18n")
    private fun updateChargerSlot(
        index: Int,
        chargers: List<ChargingPoint>,
        container: LinearLayout,
        nameText: TextView,
        ccsText: TextView,
        deviationText: TextView,
        distanceText: TextView
    ) {
        if (index < chargers.size) {
            val charger = chargers[index]
            container.visibility = View.VISIBLE
            
            nameText.text = charger.title ?: "Unknown"
            ccsText.text = charger.ccsPoints.toString()
            
            val deviationMinutes = (charger.deviationSeconds ?: 0) / 60
            deviationText.text = if (deviationMinutes > 0) "+${deviationMinutes}m" else "${deviationMinutes}m"
            
            // Distance remaining to this charger
            val remainingMeters = charger.distanceAlongRoute - currentLocationMeters
            val remainingMiles = remainingMeters / 1609.344
            val displayDistance = remainingMiles.coerceAtLeast(0.0)
            distanceText.text = String.format(Locale.getDefault(), "%.1f mi", displayDistance)
            
            // Debug logging
            AppLogger.d("Charger[$index] ${charger.title}: distAlongRoute=${charger.distanceAlongRoute}m, currentPos=${currentLocationMeters}m, remaining=${remainingMiles.toInt()}mi")
        } else {
            container.visibility = View.GONE
        }
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
                // Update charger distances every second for real-time feel
                if (navigationActive && allChargingPoints.isNotEmpty()) {
                    updateChargerListDisplay()
                }
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
                    
                    // Update current position along route and charger list
                    if (navigationActive && allChargingPoints.isNotEmpty() && decodedRoutePath.isNotEmpty()) {
                        currentLocationMeters = calculateDistanceAlongRoute(it.lat, it.lon)
                        updateChargerListDisplay()
                    }
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
