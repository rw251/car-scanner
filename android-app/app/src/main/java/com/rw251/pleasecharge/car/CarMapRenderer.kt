package com.rw251.pleasecharge.car

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.GoogleMap
import com.google.android.libraries.navigation.NavigationViewForAuto
import com.rw251.pleasecharge.AppLogger

/**
 * Renders the Google Navigation map onto the Android Auto surface.
 * Uses NavigationViewForAuto with VirtualDisplay and Presentation.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    private val lifecycle: Lifecycle
) : SurfaceCallback, DefaultLifecycleObserver {

    companion object {
        private const val TAG = "CarMapRenderer"
        private const val VIRTUAL_DISPLAY_NAME = "NavigationDisplay"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var navigationView: NavigationViewForAuto? = null
    private var googleMap: GoogleMap? = null
    private var surfaceContainer: SurfaceContainer? = null

    init {
        lifecycle.addObserver(this)
    }

    private fun isSurfaceReady(container: SurfaceContainer): Boolean {
        return container.surface != null
                && container.dpi != 0
                && container.height != 0
                && container.width != 0
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        AppLogger.i("$TAG: onSurfaceAvailable - width=${surfaceContainer.width}, height=${surfaceContainer.height}, dpi=${surfaceContainer.dpi}")
        
        if (!isSurfaceReady(surfaceContainer)) {
            AppLogger.w("$TAG: Surface not ready, skipping")
            return
        }

        this.surfaceContainer = surfaceContainer

        try {
            // Create a virtual display that renders to the Android Auto surface
            val displayManager = carContext.getSystemService(DisplayManager::class.java)
            virtualDisplay = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                surfaceContainer.width,
                surfaceContainer.height,
                surfaceContainer.dpi,
                surfaceContainer.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )

            if (virtualDisplay == null) {
                AppLogger.e("$TAG: Failed to create virtual display")
                return
            }

            // Create a Presentation to show on the virtual display
            presentation = Presentation(carContext, virtualDisplay!!.display)

            // Create the NavigationViewForAuto
            navigationView = NavigationViewForAuto(carContext)
            navigationView?.onCreate(null)
            navigationView?.onStart()
            navigationView?.onResume()

            // Set the navigation view as the content of the presentation
            // NavigationViewForAuto extends FrameLayout (View), so explicit cast is required
            presentation?.setContentView(navigationView as android.view.View)
            presentation?.show()

            // Get the GoogleMap instance for any customization
            navigationView?.getMapAsync { map ->
                googleMap = map
                AppLogger.i("$TAG: GoogleMap ready for Android Auto")
            }

            AppLogger.i("$TAG: Navigation view created and displayed successfully")

        } catch (e: Exception) {
            AppLogger.e("$TAG: Failed to create navigation view", e)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        AppLogger.i("$TAG: onSurfaceDestroyed")
        cleanupResources()
    }

    override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
        AppLogger.i("$TAG: onVisibleAreaChanged - $visibleArea")
    }

    override fun onStableAreaChanged(stableArea: android.graphics.Rect) {
        AppLogger.i("$TAG: onStableAreaChanged - $stableArea")
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // Handle scroll/pan gestures
        googleMap?.let { map ->
            val cameraUpdate = com.google.android.gms.maps.CameraUpdateFactory.scrollBy(distanceX, distanceY)
            map.moveCamera(cameraUpdate)
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // Handle pinch-to-zoom gestures
        googleMap?.let { map ->
            val currentZoom = map.cameraPosition.zoom
            val newZoom = currentZoom + (scaleFactor - 1f) * 2f // Adjust sensitivity
            val cameraUpdate = com.google.android.gms.maps.CameraUpdateFactory.zoomTo(newZoom)
            map.animateCamera(cameraUpdate)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cleanupResources()
    }

    private fun cleanupResources() {
        try {
            navigationView?.onPause()
            navigationView?.onStop()
            navigationView?.onDestroy()
            navigationView = null

            presentation?.dismiss()
            presentation = null

            virtualDisplay?.release()
            virtualDisplay = null

            googleMap = null
            surfaceContainer = null

            AppLogger.i("$TAG: Resources cleaned up")
        } catch (e: Exception) {
            AppLogger.e("$TAG: Error during cleanup", e)
        }
    }

    /**
     * Get the current GoogleMap instance for additional customization.
     */
    fun getGoogleMap(): GoogleMap? = googleMap
}
