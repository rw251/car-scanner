package com.rw251.pleasecharge

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rw251.pleasecharge.ble.BleObdManager
import com.rw251.pleasecharge.databinding.ActivityMainBinding
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

    @SuppressLint("MissingPermission")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.appendLog("Permissions granted")
            startBleManager()
        } else {
            viewModel.appendLog("Permissions denied - cannot scan for BLE devices")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar for cleaner single-screen look
        supportActionBar?.hide()

        // Ensure content respects status bar / display cutout insets
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)

        setupUI()
        observeViewModel()

        // Apply window insets so UI content stays clear of status bar / notches
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sysInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sysInsets.top + v.paddingTop, v.paddingRight, sysInsets.bottom + v.paddingBottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStop() {
        super.onStop()
        // Stop scan/close GATT when app goes to background
        manager?.stop()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        manager?.stop()
        manager = null
    }

    @SuppressLint("MissingPermission")
    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.btnCancel.setOnClickListener @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) {
            manager?.cancel()
        }

        binding.btnSoc.setOnClickListener {
            manager?.requestSoc()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.status.collect { status ->
                        binding.tvStatus.text = "$status"
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

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        needed += Manifest.permission.BLUETOOTH_SCAN
        needed += Manifest.permission.BLUETOOTH_CONNECT
        // Some devices still require location for BLE scan
        needed += Manifest.permission.ACCESS_FINE_LOCATION

        // Check if we already have all permissions
        val hasAll = needed.all { 
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED 
        }

        if (hasAll) {
            startBleManager()
        } else {
            viewModel.appendLog("Requesting permissions...")
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startBleManager() {
        if (manager == null) {
            manager = BleObdManager(
                context = this,
                listener = createBleListener(),
                scope = lifecycleScope
            )
        }
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
                    viewModel.setSoc(
                        display = "SOC: ${String.format(Locale.getDefault(), "%.1f", pct)}% (raw: $raw)",
                        time = "Time: ${nowString()}"
                    )
                }
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                // Temperature not shown in simplified UI per plan
                runOnUiThread {
                    viewModel.appendLog("Temp: ${String.format(Locale.getDefault(), "%.1f", celsius)} °C")
                }
            }

            override fun onError(msg: String, ex: Throwable?) {
                runOnUiThread {
                    viewModel.appendLog("ERROR: $msg${if (ex != null) " - ${ex.message}" else ""}")
                }
            }

            override fun onLog(line: String) {
                runOnUiThread { viewModel.appendLog(line) }
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

    private fun nowString(): String = 
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}