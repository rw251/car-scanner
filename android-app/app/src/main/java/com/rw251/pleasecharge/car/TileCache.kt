package com.rw251.pleasecharge.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Manages downloading and caching of OpenStreetMap tiles.
 * Uses standard OSM tile URL format: https://tile.openstreetmap.org/{z}/{x}/{y}.png
 */
class TileCache {
    companion object {
        private const val TAG = "TileCache"
        private const val TILE_SIZE = 256
        private const val OSM_TILE_URL = "https://tile.openstreetmap.org"
        private const val USER_AGENT = "PleaseCharge/1.0 (Android Auto EV App)"
        
        // Cache up to 50 tiles in memory (~12MB at 256x256 ARGB_8888)
        private const val CACHE_SIZE = 50
    }
    
    private val memoryCache = LruCache<String, Bitmap>(CACHE_SIZE)
    private val pendingRequests = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class TileCoord(val x: Int, val y: Int, val zoom: Int)
    
    /**
     * Convert lat/lon to tile coordinates at given zoom level
     */
    fun latLonToTile(lat: Double, lon: Double, zoom: Int): TileCoord {
        val n = 1 shl zoom // 2^zoom
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return TileCoord(x, y, zoom)
    }
    
    /**
     * Convert tile coordinates to lat/lon (northwest corner of tile)
     */
    fun tileToLatLon(tile: TileCoord): Pair<Double, Double> {
        val n = 1 shl tile.zoom
        val lon = tile.x.toDouble() / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1 - 2 * tile.y.toDouble() / n)))
        val lat = Math.toDegrees(latRad)
        return Pair(lat, lon)
    }
    
    /**
     * Get pixel offset within tile for given lat/lon
     */
    fun getPixelOffset(lat: Double, lon: Double, zoom: Int): Pair<Float, Float> {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n * TILE_SIZE).toFloat()
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE_SIZE).toFloat()
        return Pair(x, y)
    }
    
    /**
     * Get a tile from cache, or start downloading it.
     * Returns null if tile is not yet cached (will be available on next render).
     */
    fun getTile(tile: TileCoord): Bitmap? {
        val key = "${tile.zoom}/${tile.x}/${tile.y}"
        
        // Check memory cache
        memoryCache.get(key)?.let { return it }
        
        // Start download if not already pending
        synchronized(pendingRequests) {
            if (!pendingRequests.contains(key)) {
                pendingRequests.add(key)
                downloadTile(tile, key)
            }
        }
        
        return null
    }
    
    private fun downloadTile(tile: TileCoord, key: String) {
        scope.launch {
            try {
                val url = URL("$OSM_TILE_URL/${tile.zoom}/${tile.x}/${tile.y}.png")
                Log.d(TAG, "Downloading tile: $url")
                
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    if (bitmap != null) {
                        memoryCache.put(key, bitmap)
                        Log.d(TAG, "Tile cached: $key")
                    }
                } else {
                    Log.w(TAG, "Failed to download tile $key: ${connection.responseCode}")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading tile $key", e)
            } finally {
                synchronized(pendingRequests) {
                    pendingRequests.remove(key)
                }
            }
        }
    }
    
    /**
     * Prefetch tiles around a location
     */
    fun prefetchAround(lat: Double, lon: Double, zoom: Int, radius: Int = 2) {
        val center = latLonToTile(lat, lon, zoom)
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                val tile = TileCoord(center.x + dx, center.y + dy, zoom)
                getTile(tile) // This will trigger download if not cached
            }
        }
    }
    
    fun clear() {
        memoryCache.evictAll()
        scope.cancel()
    }
}
