package com.rw251.pleasecharge.ui.home

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.rw251.pleasecharge.databinding.FragmentHomeBinding
import com.rw251.pleasecharge.ble.BleObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: BleObdManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // No-op; button click will retry start
    }

    private fun nowString(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        manager = BleObdManager(requireContext(), object : BleObdManager.Listener {
            override fun onStatus(text: String) {
                binding.tvStatus.text = "Status: $text"
            }

            override fun onReady() {
                binding.tvStatus.text = "Status: READY"
            }

            override fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long) {
                binding.tvSoc.text = "SOC: ${String.format(Locale.getDefault(), "%.1f", (pct95 ?: raw/9.5))}% (raw: $raw)"
                binding.tvSocTime.text = "Time: ${nowString()}"
            }

            override fun onTemp(celsius: Double, timestamp: Long) {
                binding.tvTemp.text = "Battery temp: ${String.format(Locale.getDefault(), "%.1f", celsius)} °C"
                binding.tvTempTime.text = "Time: ${nowString()}"
            }

            override fun onError(msg: String, ex: Throwable?) {
                binding.tvStatus.text = "Status: $msg"
            }

            override fun onLog(line: String) {
                val tv = binding.tvLogs
                val current = tv.text.toString()
                val newText = if (current.length > 20_000) current.takeLast(10_000) + "\n" + line else current + "\n" + line
                tv.text = newText
                binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
            }

            override fun onStateChanged(state: BleObdManager.State) {
                when (state) {
                    BleObdManager.State.IDLE -> {
                        binding.btnConnect.isEnabled = true
                        binding.btnCancel.isEnabled = false
                        binding.btnReset.isEnabled = false
                    }
                    BleObdManager.State.SEARCHING,
                    BleObdManager.State.CONNECTING,
                    BleObdManager.State.CONFIGURING -> {
                        binding.btnConnect.isEnabled = false
                        binding.btnCancel.isEnabled = true
                        binding.btnReset.isEnabled = true
                    }
                    BleObdManager.State.READY -> {
                        binding.btnConnect.isEnabled = false
                        binding.btnCancel.isEnabled = true
                        binding.btnReset.isEnabled = true
                    }
                }
            }
        }, scope)

        binding.btnConnect.setOnClickListener {
            ensurePermissionsAndStart()
        }

        binding.btnCancel.setOnClickListener {
            manager?.cancel()
        }

        binding.btnReset.setOnClickListener {
            manager?.stop()
            ensurePermissionsAndStart()
        }

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        manager?.stop()
        _binding = null
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            needed += Manifest.permission.BLUETOOTH
            needed += Manifest.permission.BLUETOOTH_ADMIN
            // Some devices still require location for BLE scan
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(needed.toTypedArray())
        // Attempt start; if permissions missing, it will fail silently due to SecurityException catches
        manager?.start()
    }
}