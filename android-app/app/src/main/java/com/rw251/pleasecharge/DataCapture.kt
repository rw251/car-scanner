package com.rw251.pleasecharge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures vehicle metrics to CSV for later analysis.
 * Format: timestamp,soc_raw,soc_pct,temp_celsius,speed_mph,distance_miles,lat,lon
 * 
 * Includes deduplication to prevent logging duplicate values within short time windows.
 */
object DataCapture {
    private const val CSV_FILE_NAME = "vehicle_data.csv"
    private const val MAX_CSV_FILE_SIZE = 10 * 1024 * 1024  // 10 MB
    
    // Deduplication windows (milliseconds)
    private const val SOC_DEDUPE_WINDOW_MS = 500L      // Don't log same SOC within 500ms
    private const val TEMP_DEDUPE_WINDOW_MS = 500L     // Don't log same temp within 500ms
    private const val LOCATION_DEDUPE_WINDOW_MS = 100L // Don't log same location within 100ms
    
    private var csvFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueue = ConcurrentLinkedQueue<CsvRow>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var initialized = false
    
    // Last logged values for deduplication
    private var lastSocRaw: Int? = null
    private var lastSocTimestamp: Long = 0
    private var lastTempCelsius: Double? = null
    private var lastTempTimestamp: Long = 0
    private var lastLocationTimestamp: Long = 0
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    
    data class CsvRow(
        val timestamp: Long,
        val socRaw: Int? = null,
        val socPct: Double? = null,
        val tempCelsius: Double? = null,
        val speedMph: Double? = null,
        val distanceMiles: Double? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )
    
    fun init(context: Context) {
        if (initialized) return
        csvFile = File(context.filesDir, CSV_FILE_NAME)
        
        // Create CSV with headers if it doesn't exist
        if (csvFile?.exists() == false) {
            csvFile?.writeText("timestamp,soc_raw,soc_pct,temp_celsius,speed_mph,distance_miles,lat,lon\n")
        }
        
        initialized = true
        AppLogger.i("DataCapture initialized - CSV file: ${csvFile?.absolutePath}")
        
        // Start background writer
        scope.launch {
            processWriteQueue()
        }
    }
    
    fun logSoc(raw: Int, pct: Double?, timestamp: Long = System.currentTimeMillis()) {
        // Deduplicate: skip if same value within window
        if (raw == lastSocRaw && (timestamp - lastSocTimestamp) < SOC_DEDUPE_WINDOW_MS) {
          AppLogger.i("DataCapture: Skipping duplicate SOC raw value $raw within deduplication window")
            return
        }
        lastSocRaw = raw
        lastSocTimestamp = timestamp
        enqueue(CsvRow(timestamp = timestamp, socRaw = raw, socPct = pct))
    }
    
    fun logTemp(celsius: Double, timestamp: Long = System.currentTimeMillis()) {
        // Deduplicate: skip if same value within window
        if (celsius == lastTempCelsius && (timestamp - lastTempTimestamp) < TEMP_DEDUPE_WINDOW_MS) {
          AppLogger.i("DataCapture: Skipping duplicate temperature value $celsius within deduplication window")
            return
        }
        lastTempCelsius = celsius
        lastTempTimestamp = timestamp
        enqueue(CsvRow(timestamp = timestamp, tempCelsius = celsius))
    }
    
    fun logLocation(lat: Double, lon: Double, speedMph: Double, distanceMiles: Double, timestamp: Long = System.currentTimeMillis()) {
        // Deduplicate: skip if same location within window
        if (lat == lastLat && lon == lastLon && (timestamp - lastLocationTimestamp) < LOCATION_DEDUPE_WINDOW_MS) {
          AppLogger.i("DataCapture: Skipping duplicate location value lat=$lat, lon=$lon within deduplication window")
            return
        }
        lastLat = lat
        lastLon = lon
        lastLocationTimestamp = timestamp
        enqueue(
            CsvRow(
                timestamp = timestamp,
                speedMph = speedMph,
                distanceMiles = distanceMiles,
                lat = lat,
                lon = lon
            )
        )
    }
    
    private fun enqueue(row: CsvRow) {
        writeQueue.offer(row)
    }
    
    private suspend fun processWriteQueue() {
        while (true) {
            val row = writeQueue.poll()
            if (row != null) {
                writeToFile(row)
            } else {
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    private fun writeToFile(row: CsvRow) {
        try {
            val file = csvFile ?: return
            
            // Rotate CSV if too large
            if (file.exists() && file.length() > MAX_CSV_FILE_SIZE) {
                val backupFile = File(file.parent, "${CSV_FILE_NAME}.old")
                file.renameTo(backupFile)
                file.writeText("timestamp,soc_raw,soc_pct,temp_celsius,speed_mph,distance_miles,lat,lon\n")
                AppLogger.i("CSV file rotated to backup")
            }
            
            val line = buildCsvLine(row)
            file.appendText(line)
        } catch (e: Exception) {
            AppLogger.e("Failed to write to CSV file", e)
        }
    }
    
    private fun buildCsvLine(row: CsvRow): String {
        val timestamp = dateFormat.format(Date(row.timestamp))
        return "$timestamp,${row.socRaw ?: ""},${row.socPct ?: ""},${row.tempCelsius ?: ""},${row.speedMph ?: ""},${row.distanceMiles ?: ""},${row.lat ?: ""},${row.lon ?: ""}\n"
    }
    
    fun getCsvFilePath(): String? = csvFile?.absolutePath

}
