package com.rw251.pleasecharge.car

import android.graphics.*
import android.util.Log
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.Lifecycle
import androidx.core.graphics.toColorInt
import java.util.Locale

/**
 * Surface renderer that draws OpenStreetMap tiles with the user's location.
 * Fetches tiles from OSM tile servers and caches them in memory.
 */
class SimpleMapRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle
) {
    
    companion object {
        private const val TAG = "SimpleMapRenderer"
        private const val DEFAULT_ZOOM = 16
        private const val TILE_SIZE = 256
    }
    
    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    
    private val tileCache = TileCache()
    
    private val backgroundPaint = Paint().apply {
        color = "#E8E8E8".toColorInt()
        isAntiAlias = true
    }
    
    private val tilePaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    
    private val locationDotPaint = Paint().apply {
        color = Color.BLUE
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val locationRingPaint = Paint().apply {
        color = Color.BLUE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 0  // Hidden - was showing as unwanted circle
    }
    
    private val locationOuterRingPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    
    private val textPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    
    private val loadingPaint = Paint().apply {
        color = "#CCCCCC".toColorInt()
        isAntiAlias = true
    }
    
    private val panelBackgroundPaint = Paint().apply {
        color = "#FFFFFF".toColorInt()  // Solid white background
        isAntiAlias = false  // No anti-aliasing for sharp edges
        style = Paint.Style.FILL
    }
    
    private val buttonBackgroundPaint = Paint().apply {
        color = "#2196F3".toColorInt()  // Blue button
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private val buttonTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    private val panelTextPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 18f
        isAntiAlias = true
    }
    
    private val panelLabelPaint = Paint().apply {
        color = Color.GRAY
        textSize = 14f
        isAntiAlias = true
    }
    
    private val panelValuePaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
        isFakeBoldText = true
    }
    
    // Current map state
    private var userLat: Double = 0.0
    private var userLon: Double = 0.0
    private var hasLocation = false
    private var surfaceReady = false
    
    private var zoomLevel = DEFAULT_ZOOM
    private var offsetX = 0f
    private var offsetY = 0f
    
    // Panel state
    var isPanelExpanded = false
        set(value) {
            field = value
            renderFrame()
        }
    
    var socPercent: String = "--"
    var batteryTempC: String = "--"
    var connectionStatus: String = "DISCONNECTED"
    var distanceMiles: Double? = null
    var avgSpeedMph: Double? = null
    var isConnected: Boolean = false
    
    val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            synchronized(this@SimpleMapRenderer) {
                Log.i(TAG, "Surface available! container=$surfaceContainer, surface=${surfaceContainer.surface}, width=${surfaceContainer.width}, height=${surfaceContainer.height}")
                surface?.release()
                surface = surfaceContainer.surface
                surfaceReady = true
                renderFrame()
            }
        }
        
        override fun onVisibleAreaChanged(newVisibleArea: Rect) {
            synchronized(this@SimpleMapRenderer) {
                Log.i(TAG, "Visible area changed: $newVisibleArea, surfaceReady=$surfaceReady, surface=$surface")
                visibleArea = newVisibleArea
                // Only render if surface is available
                if (surfaceReady) {
                    renderFrame()
                }
            }
        }
        
        override fun onStableAreaChanged(newStableArea: Rect) {
            synchronized(this@SimpleMapRenderer) {
                Log.i(TAG, "Stable area changed: $newStableArea, surfaceReady=$surfaceReady")
                stableArea = newStableArea
                // Only render if surface is available
                if (surfaceReady) {
                    renderFrame()
                }
            }
        }
        
        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            synchronized(this@SimpleMapRenderer) {
                Log.i(TAG, "Surface destroyed")
                surfaceReady = false
                surface?.release()
                surface = null
            }
        }
        
        override fun onScroll(distanceX: Float, distanceY: Float) {
            // Disabled - map should not be draggable
        }
        
        override fun onScale(focusX: Float, focusY: Float, newScaleFactor: Float) {
            // Disabled - map should not be zoomable
        }
    }
    
    init {
        Log.i(TAG, "SimpleMapRenderer init, lifecycle state: ${lifecycle.currentState}")
        // Note: surfaceCallback registration is done by the Session, not via lifecycle
    }
    
    // Removed onCreate - registration is now done directly by Session in onCreateScreen
    
    fun updateLocation(lat: Double, lon: Double) {
        synchronized(this) {
            Log.i(TAG, "Location updated: $lat, $lon")
            userLat = lat
            userLon = lon
            hasLocation = true
            // Prefetch tiles around new location
            tileCache.prefetchAround(lat, lon, zoomLevel)
            // Render if surface is ready
            if (surfaceReady && surface?.isValid == true) {
                renderFrame()
            }
        }
    }

    private fun renderFrame() {
        if (!surfaceReady) {
            return
        }
        
        val currentSurface = surface
        if (currentSurface == null || !currentSurface.isValid) {
            return
        }
        
        val currentVisibleArea = visibleArea ?: return
        
        // Validate the visible area has reasonable dimensions
        if (currentVisibleArea.isEmpty || currentVisibleArea.width() <= 0 || currentVisibleArea.height() <= 0) {
            return
        }
        
        var canvas: Canvas? = null
        try {
            // Use software canvas only - hardware canvas causes crashes in DHU
            // This can throw IllegalArgumentException if surface becomes invalid between check and lock
            canvas = currentSurface.lockCanvas(null)
            if (canvas != null) {
                renderMap(canvas, currentVisibleArea)
            }
        } catch (_: IllegalArgumentException) {
            // Surface became invalid between check and lock - this is expected, just skip
            return
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering frame", e)
        } finally {
            try {
                if (canvas != null) {
                    currentSurface.unlockCanvasAndPost(canvas)
                }
            } catch (_: Exception) {
                // Ignore unlock errors
            }
        }
    }
    
    private fun renderMap(canvas: Canvas, bounds: Rect) {
        // Validate bounds
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "Invalid bounds for rendering: $bounds")
            return
        }
        
        // Clear with background
        canvas.drawRect(bounds, backgroundPaint)
        
        if (hasLocation) {
            // Draw map tiles centered on user location
            drawTiles(canvas, bounds)
            
            // Draw user location marker - adjust center when panel is open
            val panelWidth = if (isPanelExpanded) bounds.width() / 2 else 0
            val availableWidth = bounds.width() - panelWidth
            val centerX = bounds.left + panelWidth + (availableWidth / 2f) + offsetX
            val centerY = bounds.centerY().toFloat() + offsetY
            drawLocationMarker(canvas, centerX, centerY)
        } else {
            // No location yet - draw message
            canvas.drawText("Waiting for GPS location...", 
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), textPaint)
        }
        
        // Draw collapsible panel overlay if expanded
        if (isPanelExpanded) {
            drawPanel(canvas, bounds)
        }
    }
    
    private fun drawTiles(canvas: Canvas, bounds: Rect) {
        // Get pixel position for user location
        val (userPixelX, userPixelY) = tileCache.getPixelOffset(userLat, userLon, zoomLevel)
        
        // Calculate screen center - adjust when panel is open
        val panelWidth = if (isPanelExpanded) bounds.width() / 2 else 0
        val availableWidth = bounds.width() - panelWidth
        val screenCenterX = bounds.left + panelWidth + (availableWidth / 2f) + offsetX
        val screenCenterY = bounds.centerY().toFloat() + offsetY
        
        // Calculate which tiles we need to cover the visible area
        val tilesNeededX = (bounds.width() / TILE_SIZE) + 3
        val tilesNeededY = (bounds.height() / TILE_SIZE) + 3
        
        // Get the center tile
        val centerTile = tileCache.latLonToTile(userLat, userLon, zoomLevel)
        
        // Calculate the offset within the center tile
        val tileOriginX = (centerTile.x * TILE_SIZE).toFloat()
        val tileOriginY = (centerTile.y * TILE_SIZE).toFloat()
        val offsetInTileX = userPixelX - tileOriginX
        val offsetInTileY = userPixelY - tileOriginY
        
        // Draw tiles around the center
        for (dx in -tilesNeededX/2..tilesNeededX/2) {
            for (dy in -tilesNeededY/2..tilesNeededY/2) {
                val tile = TileCache.TileCoord(
                    centerTile.x + dx,
                    centerTile.y + dy,
                    zoomLevel
                )
                
                // Calculate where this tile should be drawn on screen
                val tileScreenX = screenCenterX - offsetInTileX + (dx * TILE_SIZE)
                val tileScreenY = screenCenterY - offsetInTileY + (dy * TILE_SIZE)
                
                // Only draw if tile is visible
                if (tileScreenX + TILE_SIZE >= bounds.left && tileScreenX <= bounds.right &&
                    tileScreenY + TILE_SIZE >= bounds.top && tileScreenY <= bounds.bottom) {
                    
                    val tileBitmap = tileCache.getTile(tile)
                    if (tileBitmap != null) {
                        canvas.drawBitmap(tileBitmap, tileScreenX, tileScreenY, tilePaint)
                    } else {
                        // Draw placeholder for loading tile
                        canvas.drawRect(
                            tileScreenX, tileScreenY,
                            tileScreenX + TILE_SIZE, tileScreenY + TILE_SIZE,
                            loadingPaint
                        )
                    }
                }
            }
        }
    }
    
    private fun drawLocationMarker(canvas: Canvas, x: Float, y: Float) {
        // White outer ring for visibility
        canvas.drawCircle(x, y, 18f, locationOuterRingPaint)
        
        // Blue ring
        canvas.drawCircle(x, y, 16f, locationRingPaint)
        
        // Inner blue dot
        canvas.drawCircle(x, y, 10f, locationDotPaint)
    }
    
    private fun drawPanel(canvas: Canvas, bounds: Rect) {
        // Panel takes up left half of screen (full height)
        val panelWidth = bounds.width() / 2
        val panelRect = RectF(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.left.toFloat() + panelWidth,
            bounds.bottom.toFloat()
        )
        
        // Draw opaque background (no rounded corners)
        canvas.drawRect(panelRect, panelBackgroundPaint)
        
        // Draw stats with compact spacing, starting from top
        val leftMargin = panelRect.left + 20f
        var yPos = panelRect.top + 20f
        
        // Show Connect button when not connected, otherwise show status
        if (!isConnected) {
            // Draw "Tap to Connect" button area
            canvas.drawText("STATUS", leftMargin, yPos, panelLabelPaint)
            yPos += 20f
            canvas.drawText("Not Connected", leftMargin, yPos, panelValuePaint)
            yPos += 30f
            
            // Draw connect button
            val buttonRect = RectF(
                leftMargin,
                yPos,
                panelRect.right - 20f,
                yPos + 40f
            )
            canvas.drawRoundRect(buttonRect, 8f, 8f, buttonBackgroundPaint)
            canvas.drawText("CONNECT", buttonRect.centerX(), buttonRect.centerY() + 7f, buttonTextPaint)
            yPos += 60f
        } else {
            // Connection Status
            canvas.drawText("STATUS", leftMargin, yPos, panelLabelPaint)
            yPos += 20f
            canvas.drawText(connectionStatus, leftMargin, yPos, panelValuePaint)
            yPos += 40f
        }
        
        // Battery SOC
        canvas.drawText("BATTERY", leftMargin, yPos, panelLabelPaint)
        yPos += 20f
        canvas.drawText("$socPercent%", leftMargin, yPos, panelValuePaint)
        yPos += 40f
        
        // Battery Temperature
        canvas.drawText("TEMPERATURE", leftMargin, yPos, panelLabelPaint)
        yPos += 20f
        canvas.drawText("${batteryTempC}°C", leftMargin, yPos, panelValuePaint)
        yPos += 40f
        
        // Distance
        canvas.drawText("DISTANCE", leftMargin, yPos, panelLabelPaint)
        yPos += 20f
        val distText = distanceMiles?.let { String.format(Locale.getDefault(), "%.2f mi", it) } ?: "--"
        canvas.drawText(distText, leftMargin, yPos, panelValuePaint)
        yPos += 40f
        
        // Speed
        canvas.drawText("AVG SPEED", leftMargin, yPos, panelLabelPaint)
        yPos += 20f
        val speedText = avgSpeedMph?.let { String.format(Locale.getDefault(),"%.1f mph", it) } ?: "--"
        canvas.drawText(speedText, leftMargin, yPos, panelValuePaint)
        yPos += 40f
        
        // Location info
        if (hasLocation) {
            canvas.drawText("LOCATION", leftMargin, yPos, panelLabelPaint)
            yPos += 20f
            canvas.drawText(String.format(Locale.getDefault(),"%.5f°", userLat), leftMargin, yPos, panelTextPaint)
            yPos += 22f
            canvas.drawText(String.format(Locale.getDefault(),"%.5f°", userLon), leftMargin, yPos, panelTextPaint)
        }
    }
}

