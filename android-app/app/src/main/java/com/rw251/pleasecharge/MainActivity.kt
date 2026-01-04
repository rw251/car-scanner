package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.rw251.pleasecharge.ble.BleObdManager
import com.rw251.pleasecharge.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var locationStarted = false
    private var locationMarker: Marker? = null
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    
    // Track current stats for display
    private var currentSocPct: Double? = null
    private var currentTempC: Double? = null
    private var currentSpeedMph: Double? = null
    private var currentDistanceMiles: Double? = null
    private var journeyStartTime: Long? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logging and data capture
        AppLogger.init(this)
        DataCapture.init(this)
        AppLogger.i("MainActivity created")
        
        // Reset service status for fresh start
        ServiceStatus.reset()
        
        // Initialize osmdroid configuration
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar for full-screen map
        supportActionBar?.hide()

        // Start foreground service EARLY - ensures location tracking starts
        // regardless of permission state. It will wait for permissions but won't lose time.
        startBleForegroundService()

        setupMap()
        setupBottomSheet()
        setupUI()
        observeViewModel()
        // ensurePermissionsAndStart() is called in setupUI(), so no need for duplicate maybeStartLocationTracking()
    }
    
    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Disable all touch interactions - map is fixed to user location
            setMultiTouchControls(false)
            setBuiltInZoomControls(false)
            // Disable scrolling and zooming
            setScrollableAreaLimitDouble(null)
            isFlingEnabled = false
            // Disable horizontal and vertical scroll (prevents drag/pan)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(17.0)
            // Default to UK center initially
            controller.setCenter(GeoPoint(54.0, -2.0))
            
            // Override touch events to completely disable map interaction
            // but still allow touches to pass through to the bottom sheet
            overlays.add(0, object : org.osmdroid.views.overlay.Overlay() {
                override fun onDoubleTap(
                    e: android.view.MotionEvent?,
                    pMapView: org.osmdroid.views.MapView?
                ): Boolean {
                    // Consume double-tap to prevent zoom
                    return true
                }
                
                override fun onScroll(
                    pEvent1: android.view.MotionEvent?,
                    pEvent2: android.view.MotionEvent?,
                    pDistanceX: Float,
                    pDistanceY: Float,
                    pMapView: org.osmdroid.views.MapView?
                ): Boolean {
                    // Consume scroll to prevent pan
                    return true
                }
                
                override fun onFling(
                    pEvent1: android.view.MotionEvent?,
                    pEvent2: android.view.MotionEvent?,
                    pVelocityX: Float,
                    pVelocityY: Float,
                    pMapView: org.osmdroid.views.MapView?
                ): Boolean {
                    // Consume fling to prevent pan
                    return true
                }
            })
        }
        
        // Create location marker
        locationMarker = Marker(binding.mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.location_dot)
            title = "Current Location"
        }
        binding.mapView.overlays.add(locationMarker)
    }
    
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
                                binding.topBleStatus.setTextColor(android.graphics.Color.parseColor("#FF9800"))  // Orange for reconnecting
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
                            binding.topBleStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                            binding.topTimeoutCountdown.visibility = View.GONE
                            binding.topGpsStatus.text = "GPS: Offline"
                            binding.topGpsStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
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
            BleObdManager.State.READY -> android.graphics.Color.parseColor("#4CAF50")
            BleObdManager.State.DISCONNECTED -> android.graphics.Color.parseColor("#F44336")
            else -> android.graphics.Color.parseColor("#2196F3")
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
            while (true) {
                kotlinx.coroutines.delay(1000)  // Update every second
                updateDurationDisplay()
            }
        }
    }
    
    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }
    
    private fun updateMapLocation(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        locationMarker?.position = point
        binding.mapView.controller.animateTo(point)
        binding.mapView.invalidate()
    }

    // private fun maybeStartLocationTracking() {
    //     val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    //     val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    //     if (hasFine || hasCoarse) {
    //         startLocationTracking()
    //     }
    // }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationStarted) return
        locationStarted = true
        
        // Just subscribe to LocationTracker metrics (foreground service handles starting it)
        if (locationJob == null) {
            locationJob = lifecycleScope.launch {
                LocationTracker.metrics.collect { metrics ->
                    metrics?.let { 
                        viewModel.setLocationStats(it.totalTripDistanceMiles, it.averageSpeedMph)
                        // Update map with current location
                        runOnUiThread {
                            updateMapLocation(it.lat, it.lon)
                        }
                        // Update GPS status in top panel
                        val gpsStatus = "GPS: %.5f, %.5f".format(it.lat, it.lon)
                        viewModel.setGpsStatus(gpsStatus)
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
        // Background location is only needed on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
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
        return object : BleObdManager.Listener {
            override fun onStatus(text: String) {
                runOnUiThread { viewModel.setStatus(text) }
            }

            override fun onReady() {
                runOnUiThread {
                    viewModel.setReady(true)
                }
            }

            override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
                runOnUiThread {
                    val pct = pct95 ?: (raw / 9.5)
                    AppLogger.i("SOC received: raw=$raw, pct=$pct")
                    // DataCapture logging is done by BleForegroundService only (avoid duplicates)
                    viewModel.setSoc(
                        display = "SOC: ${String.format(Locale.getDefault(), "%.1f", pct)}% (raw: $raw)",
                        time = "Time: ${nowString()}"
                    )
                    // Update the map UI SOC display
                    updateSocDisplay(pct)
                }
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                runOnUiThread {
                    AppLogger.i("Temperature received: $celsius°C")
                    // DataCapture logging is done by BleForegroundService only (avoid duplicates)
                    // Update the map UI temp display
                    updateTempDisplay(celsius)
                }
            }

            override fun onError(msg: String, ex: Throwable?) {
                runOnUiThread {
                    AppLogger.e("BLE Error: $msg", ex)
                    viewModel.appendLog("ERROR: $msg${if (ex != null) " - ${ex.message}" else ""}")
                }
            }

            override fun onLog(line: String) {
                runOnUiThread { 
                    AppLogger.d(line, "BleObdManager")
                    viewModel.appendLog(line) 
                }
            }

            override fun onStateChanged(state: BleObdManager.State) {
                runOnUiThread {
                    viewModel.setState(state)
                    if (state != BleObdManager.State.READY) {
                        viewModel.setReady(false)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        AppLogger.i("MainActivity destroyed")
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

    private fun stopBleForegroundService() {
        val intent = Intent(this, BleForegroundService::class.java).apply {
            action = BleForegroundService.ACTION_STOP
        }
        stopService(intent)
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