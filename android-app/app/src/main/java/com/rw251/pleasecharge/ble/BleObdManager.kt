package com.rw251.pleasecharge.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.Charset
import java.util.*

/**
 * BLE + OBD manager implementing the language-agnostic spec derived from the web app.
 * - Scans for device named "IOS-Vlink" or with the main service UUID
 * - Connects, discovers GATT, enables notifications, selects a writer
 * - Runs init AT queue, then polls SOC and Temp periodically
 * - Notifies client via callbacks; auto-reconnect on drop
 */
class BleObdManager(
    private val context: Context,
    private val listener: Listener,
    private val scope: CoroutineScope
) {
    enum class State {
        IDLE,           // Not connected, not attempting
        SEARCHING,      // Scanning for device
        CONNECTING,     // Connecting to device
        CONFIGURING,    // Running init AT queue
        READY           // Connected and polling
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

    // UUIDs as per JS
    private val SERVICE_MAIN = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
    private val SERVICE_DEVICE_INFO = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val SERVICE_GATT = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    private val SERVICE_GAP = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val TARGET_NAME = "IOS-Vlink"

    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var notifier: BluetoothGattCharacteristic? = null

    private val atInitQueue = ArrayDeque(listOf("ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0"))
    private val inboundBuffer = StringBuilder()
    private var waitingForResponse = false
    private var pendingNotificationDescriptor: BluetoothGattDescriptor? = null

    private var reconnectJob: Job? = null
    private var pollingJob: Job? = null

    private var currentState: State = State.IDLE
        set(value) {
            field = value
            listener.onStateChanged(value)
        }

    private fun hasPerm(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAllPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPerm(Manifest.permission.BLUETOOTH_SCAN) && hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            val locationGranted = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) || hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
            hasPerm(Manifest.permission.BLUETOOTH) &&
                hasPerm(Manifest.permission.BLUETOOTH_ADMIN) &&
                locationGranted
        }
    }

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ])
    fun start() {
        stop() // clean slate
        currentState = State.SEARCHING
        listener.onStatus("SEARCHING")
        listener.onLog("Start scan for $TARGET_NAME or $SERVICE_MAIN")
        startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
        stopScan()
        pollingJob?.cancel()
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        writer = null
        notifier = null
        atInitQueue.clear()
        atInitQueue.addAll(listOf("ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0"))
        inboundBuffer.clear()
        waitingForResponse = false
        pendingNotificationDescriptor = null
        currentState = State.IDLE
        listener.onStatus("IDLE")
        listener.onLog("Stopped")
    }

    /**
     * Cancel current connection or scan without scheduling reconnect.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cancel() {
        stopScan()
        pollingJob?.cancel()
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        writer = null
        notifier = null
        atInitQueue.clear()
        atInitQueue.addAll(listOf("ATZ", "ATD", "ATE0", "ATS0", "ATH0", "ATL0"))
        inboundBuffer.clear()
        waitingForResponse = false
        pendingNotificationDescriptor = null
        currentState = State.IDLE
        listener.onStatus("CANCELLED")
        listener.onLog("Cancelled connection/scan")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(TARGET_NAME).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_MAIN)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(filters, settings, scanCallback)
        listener.onLog("Scanning...")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name
            if (name == TARGET_NAME || result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_MAIN } == true) {
                stopScan()
                listener.onLog("Found device: name=$name, address=${device.address}")
                connect(device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            listener.onError("Scan failed: $errorCode")
            listener.onLog("Scan failed: $errorCode")
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
            listener.onLog("Connect permission error: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Connection error: $status")
                listener.onLog("Connection error: $status, state=$newState")
                gatt.close()
                scheduleReconnect()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("GETTING SERVICES")
                listener.onLog("Connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onStatus("DISCONNECTED")
                listener.onLog("Disconnected")
                gatt.close()
                scheduleReconnect()
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("Service discovery failed: $status")
                listener.onLog("Service discovery failed: $status")
                scheduleReconnect()
                return
            }
            val services = gatt.services.orEmpty()
            listener.onLog("Services discovered: ${services.map { it.uuid }}")
            val allChars = services.flatMap { s -> s.characteristics.map { s to it } }
            // Writer: any characteristic that supports write or write_no_response, prefer in MAIN service
            writer = allChars
                .filter { (s, c) -> (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }
                .sortedByDescending { (s, _) -> s.uuid == SERVICE_MAIN }
                .firstOrNull()?.second

            // Notifier: any NOTIFY char in MAIN service
            notifier = allChars
                .firstOrNull { (s, c) -> s.uuid == SERVICE_MAIN && (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 }
                ?.second

            if (writer == null || notifier == null) {
                listener.onError("Writer or notifier not found")
                listener.onLog("Writer or notifier not found")
                scheduleReconnect()
                return
            }

            listener.onLog("Writer=${writer?.uuid}, Notifier=${notifier?.uuid}")
            enableNotifications(gatt, notifier!!)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            val chunk = data.toString(Charset.forName("UTF-8")).replace("\u0000", "")
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
                listener.onLog("Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                scheduleReconnect()
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        listener.onStatus("GETTING CHARACTERISTICS")
        val ok = gatt.setCharacteristicNotification(ch, true)
        if (!ok) {
            listener.onError("Failed to enable characteristic notifications")
            listener.onLog("setCharacteristicNotification returned false for ${ch.uuid}")
            scheduleReconnect()
            return
        }
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            pendingNotificationDescriptor = cccd
            val wrote = gatt.writeDescriptor(cccd)
            if (!wrote) {
                pendingNotificationDescriptor = null
                listener.onError("Failed to write CCCD descriptor")
                listener.onLog("writeDescriptor returned false for ${ch.uuid}")
                scheduleReconnect()
            }
        } else {
            listener.onLog("CCCD descriptor missing for ${ch.uuid}; proceeding without write")
            runInitQueue()
        }
    }

    private fun runInitQueue() {
        currentState = State.CONFIGURING
        listener.onStatus("CONFIGURING")
        nextInQueueOrStartPolling()
    }

    private fun nextInQueueOrStartPolling() {
        val cmd = if (atInitQueue.isEmpty()) null else atInitQueue.removeFirst()
        if (cmd != null) {
            send(cmd)
            return
        }

        if (currentState != State.READY) {
            currentState = State.READY
            listener.onStatus("READY")
            listener.onLog("Init complete; starting polling")
            listener.onReady()
        }

        if (pollingJob?.isActive != true) {
            startPolling()
        }
    }

    private fun send(command: String) {
        val w = writer ?: return
        val g = gatt ?: return
        val bytes = (command + "\r").toByteArray(Charset.forName("UTF-8"))
        w.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        w.value = bytes
        try {
            val ok = g.writeCharacteristic(w)
            if (!ok) {
                listener.onError("Write failed to enqueue: $command")
            } else {
                waitingForResponse = true
                listener.onStatus("Sending: $command")
                listener.onLog("Sending: $command")
            }
        } catch (e: SecurityException) {
            listener.onError("Write permission error", e)
            listener.onLog("Write permission error: ${e.message}")
        }
    }

    private fun handleNotificationChunk(chunk: String) {
        inboundBuffer.append(chunk)
        while (true) {
            val promptIndex = inboundBuffer.indexOf(">")
            if (promptIndex == -1) {
                break
            }
            val message = inboundBuffer.substring(0, promptIndex)
            inboundBuffer.delete(0, promptIndex + 1)

            val lines = message
                .split("\r", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (lines.isNotEmpty()) {
                listener.onLog("Received: ${lines.joinToString(" | ")}")
            }

            notifyProcessingComplete(lines)
        }
    }

    private fun notifyProcessingComplete(lines: List<String>) {
        val shouldAdvance = waitingForResponse
        scope.launch(Dispatchers.Main) {
            delay(250)
            lines.forEach { processLine(it) }
            if (shouldAdvance) {
                waitingForResponse = false
                nextInQueueOrStartPolling()
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            // initial small delay similar to JS
            delay(500)
            send("22B046") // SOC
            while (isActive) {
                delay(30_000)
                send("22B046")
                delay(500)
                send("22B056") // battery temp
            }
        }
    }

    private fun processLine(value: String) {
        // Expect 62xxxx + AABB
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return

        val upper = cleaned.uppercase(Locale.US)
        if (upper == "OK" || upper.startsWith("AT") || upper.contains("ELM") || upper.contains("SEARCHING")) {
            return
        }

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
            "62B046" -> { // SOC
                val soc93 = raw / 9.3
                val soc95 = raw / 9.5
                val soc97 = raw / 9.7
                listener.onSoc(raw, soc93, soc95, soc97, now)
            }
            "62B061" -> {
                // SOH (not polled by default)
            }
            "62B042" -> {
                // Voltage = raw / 4.0
            }
            "62B043" -> {
                // Current = (raw - 40000) * 0.025
            }
            "62B056" -> { // Battery temp
                if (raw != 0) {
                    val temp = (a / 2.0) - 40.0
                    listener.onTemp(temp, now)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            listener.onStatus("RECONNECTING IN 3s")
            listener.onLog("Scheduling reconnect in 3s")
            delay(3000)
            if (hasAllPermissions()) {
                start()
            }
        }
    }
}
