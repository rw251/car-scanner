package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rw251.pleasecharge.ble.BleObdManager
import com.rw251.pleasecharge.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-screen activity for BLE OBD connection.
 * - Connect button to initiate BLE scan/connect
 * - Status bar showing connection state
 * - Scrollable log panel
 * - SOC button (enabled when ready) + SOC value display
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var manager: BleObdManager? = null
    private var locationJob: Job? = null
    private var locationStarted = false

    @SuppressLint("MissingPermission")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            AppLogger.i("All permissions granted")
            viewModel.appendLog("Permissions granted")
            // Now request background location if needed (Android 10+)
            maybeRequestBackgroundLocation()
            startLocationTracking()
            startBleManager()
        } else {
            AppLogger.w("Permissions denied: $results")
            viewModel.appendLog("Permissions denied - cannot scan for BLE devices")
        }
    }
    
    @SuppressLint("MissingPermission")
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLogger.i("Background location permission granted")
            viewModel.appendLog("Background location enabled - will record when screen is off")
        } else {
            AppLogger.w("Background location permission denied")
            viewModel.appendLog("Background location denied - recording will stop when screen is off")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logging and data capture
        AppLogger.init(this)
        DataCapture.init(this)
        AppLogger.i("MainActivity created")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar for cleaner single-screen look
        supportActionBar?.hide()

        // Ensure content respects status bar / display cutout insets
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)

        setupUI()
        observeViewModel()
        maybeStartLocationTracking()

        // Apply window insets so UI content stays clear of status bar / notches
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sysInsets.top + v.paddingTop, v.paddingRight, sysInsets.bottom + v.paddingBottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.btnCancel.setOnClickListener @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) {
            manager?.cancel()
            stopBleForegroundService()
        }

        binding.btnSoc.setOnClickListener {
            manager?.requestSoc()
        }

        binding.btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        binding.btnExportData.setOnClickListener {
            exportDataFiles()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.status.collect { status ->
                        binding.tvStatus.text = status
                    }
                }
                launch {
                    viewModel.logs.collect { logs ->
                        binding.tvLogs.text = logs.joinToString("\n")
                        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                launch {
                    viewModel.socDisplay.collect { soc ->
                        binding.tvSoc.text = soc ?: "SOC: -- (raw: --)"
                    }
                }
                launch {
                    viewModel.socTime.collect { time ->
                        binding.tvSocTime.text = time ?: "Time: --:--:--"
                    }
                }
                launch {
                    viewModel.isReady.collect { ready ->
                        binding.btnSoc.isEnabled = ready
                    }
                }
                launch {
                    viewModel.state.collect { state ->
                        updateButtonStates(state)
                    }
                }
                launch {
                    viewModel.distanceMiles.collect { miles ->
                        binding.tvDistance.text = miles?.let {
                            "Distance (10s): ${String.format(Locale.getDefault(), "%.2f", it)} mi"
                        } ?: "Distance (10s): --"
                    }
                }
                launch {
                    viewModel.avgSpeedMph.collect { mph ->
                        binding.tvSpeed.text = mph?.let {
                            "Avg speed (10s): ${String.format(Locale.getDefault(), "%.1f", it)} mph"
                        } ?: "Avg speed (10s): --"
                    }
                }
            }
        }
    }

    private fun updateButtonStates(state: BleObdManager.State) {
        when (state) {
            BleObdManager.State.DISCONNECTED -> {
                binding.btnConnect.isEnabled = true
                binding.btnCancel.isEnabled = false
                binding.btnSoc.isEnabled = false
            }
            BleObdManager.State.SCANNING,
            BleObdManager.State.CONNECTING,
            BleObdManager.State.DISCOVERING,
            BleObdManager.State.CONFIGURING -> {
                binding.btnConnect.isEnabled = false
                binding.btnCancel.isEnabled = true
                binding.btnSoc.isEnabled = false
            }
            BleObdManager.State.READY -> {
                binding.btnConnect.isEnabled = false
                binding.btnCancel.isEnabled = true
                binding.btnSoc.isEnabled = true
            }
        }
    }

    private fun maybeStartLocationTracking() {
        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            startLocationTracking()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (locationStarted) return
        locationStarted = true
        AppLogger.i("Starting location tracking")
        LocationTracker.start(applicationContext) { msg -> 
            AppLogger.d(msg, "LocationTracker")
            viewModel.appendLog(msg)
        }
        if (locationJob == null) {
            locationJob = lifecycleScope.launch {
                LocationTracker.metrics.collect { metrics ->
                    metrics?.let { 
                        viewModel.setLocationStats(it.distanceMiles, it.averageSpeedMph)
                        DataCapture.logLocation(
                            lat = it.lat,
                            lon = it.lon,
                            speedMph = it.averageSpeedMph,
                            distanceMiles = it.distanceMiles,
                            timestamp = it.timestampMs
                        )
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
        startBleForegroundService()
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
                    DataCapture.logSoc(raw, pct, timestamp)
                    viewModel.setSoc(
                        display = "SOC: ${String.format(Locale.getDefault(), "%.1f", pct)}% (raw: $raw)",
                        time = "Time: ${nowString()}"
                    )
                }
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                // Temperature not shown in simplified UI per plan
                runOnUiThread {
                    AppLogger.i("Temperature received: $celsius°C")
                    DataCapture.logTemp(celsius, timestamp)
                    viewModel.appendLog("Temp: ${String.format(Locale.getDefault(), "%.1f", celsius)} °C")
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

    override fun onDestroy() {
        AppLogger.i("MainActivity destroyed")
        locationJob?.cancel()
        LocationTracker.stop()
        super.onDestroy()
    }

    private fun nowString(): String = 
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

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