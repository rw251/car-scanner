package com.rw251.pleasecharge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.*

/**
 * BLE + OBD manager implementing a state machine for connection management.
 * 
 * States: IDLE → SCANNING → CONNECTING → DISCOVERING → CONFIGURING → READY
 * 
 * Features:
 * - Scans for device named "IOS-Vlink" or with the main service UUID
 * - Connects, discovers GATT, enables notifications, selects a writer
 * - Runs init AT queue (ATZ, ATD, ATE0, ATS0, ATH0, ATL0) once
 * - Exposes requestSoc() for one-shot SOC requests (no auto-polling)
 * - Optional single retry reconnect with backoff on disconnect
 */
class BleObdManager(
    private val context: Context,
    private val listener: Listener,
    private val scope: CoroutineScope
) {
    enum class State {
        IDLE,           // Not connected, not attempting
        SCANNING,       // Scanning for device
        CONNECTING,     // Connecting to device
        DISCOVERING,    // Discovering GATT services
        CONFIGURING,    // Running init AT queue
        READY           // Connected and ready for commands
    }

    interface Listener {
        fun onStatus(text: String)
        fun onReady()
        fun onSoc(raw: Int, pct93: Double?, pct95: Double?, pct97: Double?, timestamp: Long)
        fun onTemp(celsius: Double, timestamp: Long)
        fun onError(msg: String, ex: Throwable? = null)
        fun onLog(line: String)
        fun onStateChanged(state: State)
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    // UUIDs as per JS spec
    private val SERVICE_MAIN = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val TARGET_NAME = "IOS-Vlink"

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var notifier: BluetoothGattCharacteristic? = null

    private val atInitQueue = ArrayDeque(listOf("ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0"))
    private var initComplete = false
    private val inboundBuffer = StringBuilder()
    private var waitingForResponse = false
    private var pendingNotificationDescriptor: BluetoothGattDescriptor? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 1  // Single retry per plan

    private var currentState: State = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                listener.onLog("State: $value")
                listener.onStateChanged(value)
            }
        }

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAllPermissions(): Boolean {
        return hasPerm(Manifest.permission.BLUETOOTH_SCAN) && hasPerm(Manifest.permission.BLUETOOTH_CONNECT) && hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    fun start() {
        if (!hasAllPermissions()) {
            listener.onError("Missing BLE permissions")
            listener.onLog("Cannot start: missing permissions")
            return
        }
        
        stop() // clean slate
        reconnectAttempts = 0
        currentState = State.SCANNING
        listener.onStatus("SCANNING")
        listener.onLog("Start scan for $TARGET_NAME or service $SERVICE_MAIN")
        startScan()
        // startDiagnosticUnfilteredScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
        stopScanSafe()
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        resetInternalState()
        currentState = State.IDLE
        listener.onStatus("IDLE")
        listener.onLog("Stopped")
    }

    /**
     * Cancel current connection or scan without scheduling reconnect.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cancel() {
        stopScanSafe()
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        resetInternalState()
        currentState = State.IDLE
        listener.onStatus("CANCELLED")
        listener.onLog("Cancelled")
    }

    /**
     * Request SOC once. Only works when state == READY.
     * Sends 22B046 command and the response will be delivered via onSoc callback.
     */
    fun requestSoc() {
        if (currentState != State.READY) {
            listener.onError("Not ready - cannot request SOC")
            listener.onLog("requestSoc() called but state is $currentState")
            return
        }
        listener.onLog("Requesting SOC (22B046)")
        send("22B046")
    }

    /**
     * Request battery temperature once. Only works when state == READY.
     */
//    fun requestTemp() {
//        if (currentState != State.READY) {
//            listener.onError("Not ready - cannot request temp")
//            return
//        }
//        listener.onLog("Requesting temp (22B056)")
//        send("22B056")
//    }

    private fun resetInternalState() {
        writer = null
        notifier = null
        atInitQueue.clear()
        atInitQueue.addAll(listOf("ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0"))
        initComplete = false
        inboundBuffer.clear()
        waitingForResponse = false
        pendingNotificationDescriptor = null
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            listener.onLog("SecurityException closing GATT: ${e.message}")
        }
        gatt = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(TARGET_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_MAIN)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // If Bluetooth Adapter not enabled, log and skip
        if (bluetoothAdapter == null) {
            listener.onError("Bluetooth adapter not present")
            listener.onLog("Bluetooth adapter missing; cannot scan")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            listener.onError("Bluetooth adapter not enabled")
            listener.onLog("Bluetooth adapter is disabled; enable Bluetooth and retry")
            return
        }
        try {
            // Start with a filtered scan; if nothing found after a short timeout
            // we'll fall back to scanning for all advertising devices.
            scanner?.startScan(filters, settings, scanCallback)
            listener.onLog("Scanning (filtered)...")
        } catch (e: SecurityException) {
            listener.onError("Scan permission error", e)
            listener.onLog("SecurityException starting scan: ${e.message}")
        }
    }

    private fun stopScanSafe() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            listener.onLog("SecurityException stopping scan: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            listener.onLog("Scan result received")
            val device = result.device ?: return
            val record = result.scanRecord
            val name = record?.deviceName ?: device.name ?: "<unknown>"
            val address = device.address
            val uuids = record?.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: ""
            listener.onLog("Scan result: name=$name address=$address uuids=$uuids")

            val targetMatch = name.equals(TARGET_NAME, ignoreCase = true) ||
                    (record?.serviceUuids?.any { it.uuid == SERVICE_MAIN } == true)
            if (targetMatch) {
                stopScanSafe()
                listener.onLog("Found target device: name=$name, address=$address")
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onError("Scan failed: $errorCode")
            listener.onLog("Scan failed with error code: $errorCode")
            scheduleReconnect()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connect(device: BluetoothDevice) {
        currentState = State.CONNECTING
        listener.onStatus("CONNECTING")
        listener.onLog("Connecting to ${device.address}")
        try {
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            listener.onError("Connect permission error", e)
            listener.onLog("SecurityException connecting: ${e.message}")
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Connection error: $status")
                listener.onLog("Connection error: status=$status, newState=$newState")
                closeGatt()
                scheduleReconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    currentState = State.DISCOVERING
                    listener.onStatus("DISCOVERING SERVICES")
                    listener.onLog("Connected, discovering services...")
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        listener.onError("Discover services permission error", e)
                        scheduleReconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onStatus("DISCONNECTED")
                    listener.onLog("Disconnected")
                    closeGatt()
                    scheduleReconnect()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed: $status")
                listener.onLog("Service discovery failed with status: $status")
                scheduleReconnect()
                return
            }
            
            val services = gatt.services.orEmpty()
            listener.onLog("Services discovered: ${services.size} services")
            services.forEach { s ->
                listener.onLog("  Service: ${s.uuid}")
            }
            
            val allChars = services.flatMap { s -> s.characteristics.map { s to it } }
            
            // Writer: any characteristic that supports write or write_no_response, prefer in MAIN service
            writer = allChars
                .filter { (_, c) ->
                    (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }.maxByOrNull { (s, _) -> s.uuid == SERVICE_MAIN }?.second

            // Notifier: any NOTIFY char in MAIN service
            notifier = allChars
                .firstOrNull { (s, c) -> 
                    s.uuid == SERVICE_MAIN && 
                    (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 
                }?.second

            if (writer == null || notifier == null) {
                listener.onError("Writer or notifier characteristic not found")
                listener.onLog("Writer=${writer?.uuid}, Notifier=${notifier?.uuid}")
                scheduleReconnect()
                return
            }

            listener.onLog("Writer: ${writer?.uuid}")
            listener.onLog("Notifier: ${notifier?.uuid}")
            enableNotifications(gatt, notifier!!)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
//            val data = gatt.readCharacteristic(characteristic)
            val chunk = value.toString(Charset.forName("UTF-8")).replace("\u0000", "")
            if (chunk.isEmpty()) return
            handleNotificationChunk(chunk)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (descriptor != pendingNotificationDescriptor) return

            pendingNotificationDescriptor = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Notifications enabled for ${descriptor.characteristic.uuid}")
                runInitQueue()
            } else {
                listener.onError("Failed to enable notifications: $status")
                listener.onLog("CCCD write failed with status: $status")
                scheduleReconnect()
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        currentState = State.CONFIGURING
        listener.onStatus("ENABLING NOTIFICATIONS")
        
        val ok = gatt.setCharacteristicNotification(ch, true)
        if (!ok) {
            listener.onError("Failed to enable characteristic notifications")
            listener.onLog("setCharacteristicNotification returned false for ${ch.uuid}")
            scheduleReconnect()
            return
        }
        
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            try {
                val wrote = gatt.writeDescriptor(cccd, cccdValue)
                if (wrote < 0) {
                    pendingNotificationDescriptor = null
                    listener.onError("Failed to write CCCD descriptor")
                    listener.onLog("writeDescriptor returned false")
                    scheduleReconnect()
                } else {
                    pendingNotificationDescriptor = cccd
                }
            } catch (e: SecurityException) {
                pendingNotificationDescriptor = null
                listener.onError("CCCD write permission error", e)
                scheduleReconnect()
            }
        } else {
            listener.onLog("CCCD descriptor missing; proceeding without CCCD write")
            runInitQueue()
        }
    }

    private fun runInitQueue() {
        listener.onStatus("CONFIGURING")
        listener.onLog("Running AT init queue...")
        processNextInitCommand()
    }

    private fun processNextInitCommand() {
        if (atInitQueue.isEmpty()) {
            // Init complete - transition to READY
            initComplete = true
            currentState = State.READY
            listener.onStatus("READY")
            listener.onLog("Init complete - READY for commands")
            listener.onReady()
            reconnectAttempts = 0  // Reset on successful connection
            return
        }
        
        val cmd = atInitQueue.removeFirst()
        send(cmd)
    }

    private fun send(command: String) {
        val w = writer ?: run {
            listener.onError("No writer characteristic")
            return
        }
        val g = gatt ?: run {
            listener.onError("No GATT connection")
            return
        }
        
        val bytes = (command + "\r").toByteArray(Charset.forName("UTF-8"))

        try {
            val res = g.writeCharacteristic(w, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (res < 0) {
                listener.onError("Write failed: $command")
                listener.onLog("writeCharacteristic returned false for: $command")
            } else {
                waitingForResponse = true
                listener.onStatus("Sending: $command")
                listener.onLog("TX: $command")
            }
        } catch (e: SecurityException) {
            listener.onError("Write permission error", e)
            listener.onLog("SecurityException writing: ${e.message}")
        }
    }

    private fun handleNotificationChunk(chunk: String) {
        inboundBuffer.append(chunk)
        
        // Process complete messages (terminated by '>')
        while (true) {
            val promptIndex = inboundBuffer.indexOf(">")
            if (promptIndex == -1) break
            
            val message = inboundBuffer.substring(0, promptIndex)
            inboundBuffer.delete(0, promptIndex + 1)

            val lines = message
                .split("\r", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (lines.isNotEmpty()) {
                listener.onLog("RX: ${lines.joinToString(" | ")}")
            }

            processResponse(lines)
        }
    }

    private fun processResponse(lines: List<String>) {
        val shouldAdvanceInit = waitingForResponse && !initComplete
        
        scope.launch(Dispatchers.Main) {
            delay(100)  // Small delay before processing
            
            lines.forEach { line ->
                parseOBDResponse(line)
            }
            
            waitingForResponse = false
            
            if (shouldAdvanceInit) {
                processNextInitCommand()
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun parseOBDResponse(value: String) {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return

        val upper = cleaned.uppercase(Locale.US)
        
        // Skip AT responses, OK, info messages
        if (upper == "OK" || 
            upper.startsWith("AT") || 
            upper.contains("ELM") || 
            upper.contains("SEARCHING") ||
            upper.contains("STOPPED")) {
            return
        }

        // Expect 62xxxx + payload (response to mode 22 request)
        val hex = upper.replace("\\s".toRegex(), "")
        if (!hex.startsWith("62") || hex.length < 10) return

        val header = hex.substring(0, 6)
        val payload = hex.substring(6)
        if (payload.length < 4) return

        val bytes = payload.substring(0, 4)
        val a = bytes.substring(0, 2).toIntOrNull(16) ?: return
        val b = bytes.substring(2, 4).toIntOrNull(16) ?: return
        val raw = a * 256 + b
        val now = System.currentTimeMillis()

        when (header) {
            "62B046" -> { // SOC response
                val soc93 = raw / 9.3
                val soc95 = raw / 9.5
                val soc97 = raw / 9.7
                listener.onLog("SOC parsed: raw=$raw, ~${String.format("%.1f", soc95)}%")
                listener.onSoc(raw, soc93, soc95, soc97, now)
            }
            "62B061" -> { // SOH (State of Health)
                listener.onLog("SOH raw: $raw")
            }
            "62B042" -> { // Voltage
                val voltage = raw / 4.0
                listener.onLog("Voltage: ${String.format("%.1f", voltage)} V")
            }
            "62B043" -> { // Current
                val current = (raw - 40000) * 0.025
                listener.onLog("Current: ${String.format("%.2f", current)} A")
            }
            "62B056" -> { // Battery temp
                if (raw != 0) {
                    val temp = (a / 2.0) - 40.0
                    listener.onLog("Temp parsed: ${String.format("%.1f", temp)} °C")
                    listener.onTemp(temp, now)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            listener.onStatus("DISCONNECTED")
            listener.onLog("Max reconnect attempts reached")
            currentState = State.IDLE
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            val backoffMs = 300L * (reconnectAttempts + 1)
            reconnectAttempts++
            listener.onStatus("RECONNECTING in ${backoffMs/1000}s (attempt $reconnectAttempts)")
            listener.onLog("Scheduling reconnect in ${backoffMs}ms (attempt $reconnectAttempts)")
            delay(backoffMs)
            
            if (hasAllPermissions()) {
                resetInternalState()
                currentState = State.SCANNING
                listener.onStatus("SCANNING")
                startScan()
            } else {
                listener.onError("Missing permissions for reconnect")
                currentState = State.IDLE
            }
        }
    }
}
