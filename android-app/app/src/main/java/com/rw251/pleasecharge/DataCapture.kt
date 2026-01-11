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
    private const val CSV_FILE_PREFIX = "vehicle_data_" // e.g., vehicle_data_2026-02.csv
    private const val CSV_FILE_SUFFIX = ".csv"
    private const val MAX_CSV_FILE_SIZE = 10 * 1024 * 1024  // 10 MB
    
    // Deduplication windows (milliseconds)
    private const val SOC_DEDUPE_WINDOW_MS = 500L      // Don't log same SOC within 500ms
    private const val TEMP_DEDUPE_WINDOW_MS = 500L     // Don't log same temp within 500ms
    private const val LOCATION_DEDUPE_WINDOW_MS = 100L // Don't log same location within 100ms
    
    private var csvFile: File? = null
    private var filesDir: File? = null
    private var currentWeekId: String = ""
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
        filesDir = context.filesDir
        currentWeekId = weekId(System.currentTimeMillis())
        csvFile = File(filesDir!!, fileNameForWeek(currentWeekId))
        
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
        // Enforce minimum logging interval
        if ((timestamp - lastSocLoggedTs) < SOC_MIN_INTERVAL_MS) {
            return
        }
        lastSocLoggedTs = timestamp
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
        // Enforce minimum logging interval
        if ((timestamp - lastTempLoggedTs) < TEMP_MIN_INTERVAL_MS) {
            return
        }
        lastTempLoggedTs = timestamp
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
            // Rotate weekly file based on row timestamp
            val thisWeek = weekId(row.timestamp)
            if (thisWeek != currentWeekId) {
                currentWeekId = thisWeek
                csvFile = File(filesDir!!, fileNameForWeek(currentWeekId))
                if (csvFile?.exists() == false) {
                    csvFile?.writeText("timestamp,soc_raw,soc_pct,temp_celsius,speed_mph,distance_miles,lat,lon\n")
                }
                pruneOldCsvFiles()
            }

            val file = csvFile ?: return
            
            // Rotate CSV if too large
            if (file.exists() && file.length() > MAX_CSV_FILE_SIZE) {
                val backupFile = File(file.parent, "${file.name}.old")
                file.renameTo(backupFile)
                file.writeText("timestamp,soc_raw,soc_pct,temp_celsius,speed_mph,distance_miles,lat,lon\n")
                AppLogger.i("CSV file rotated due to size: ${backupFile.name}")
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

    private fun weekId(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return "%04d-%02d".format(year, week)
    }

    private fun fileNameForWeek(weekId: String): String = CSV_FILE_PREFIX + weekId + CSV_FILE_SUFFIX

    private fun pruneOldCsvFiles() {
        val dir = filesDir ?: return
        val files = dir.listFiles { f -> f.name.startsWith(CSV_FILE_PREFIX) && f.name.endsWith(CSV_FILE_SUFFIX) }?.toList() ?: return
        // Sort by week id in filename
        val sorted = files.sortedBy { it.name.substring(CSV_FILE_PREFIX.length, it.name.length - CSV_FILE_SUFFIX.length) }
        // Keep last 4 weeks
        val toDelete = if (sorted.size > 4) sorted.subList(0, sorted.size - 4) else emptyList()
        toDelete.forEach {
            try {
                it.delete()
                AppLogger.i("DataCapture: Deleted old CSV ${it.name}")
            } catch (e: Exception) {
                AppLogger.w("DataCapture: Failed to delete ${it.name}: ${e.message}")
            }
        }
    }

    // Enforce min logging interval of 5 seconds for SOC and temp
    private const val SOC_MIN_INTERVAL_MS = 5000L
    private const val TEMP_MIN_INTERVAL_MS = 5000L
    private var lastSocLoggedTs: Long = 0L
    private var lastTempLoggedTs: Long = 0L

}
