package com.rw251.pleasecharge

import com.google.android.gms.maps.model.LatLng
import kotlin.math.pow
import kotlin.math.roundToInt

// Polyline encoder. This should be possible using the PolyUtil class
// from the Google Maps Android API Utility Library, but you can't have
// the Maps SDK and Navigation SDK in the same app due to conflicting dependencies.

object PolylineEncoder {

    fun encode(points: List<LatLng>, precision: Int = 5): String {
        val factor = 10.0.pow(precision.toDouble())
        var lastLat = 0
        var lastLng = 0
        val result = StringBuilder()

        for (point in points) {
            val lat = (point.latitude * factor).roundToInt()
            val lng = (point.longitude * factor).roundToInt()

            encodeValue(lat - lastLat, result)
            encodeValue(lng - lastLng, result)

            lastLat = lat
            lastLng = lng
        }

        return result.toString()
    }

    private fun encodeValue(value: Int, result: StringBuilder) {
        var v = value shl 1
        if (value < 0) v = v.inv()

        while (v >= 0x20) {
            result.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        result.append((v + 63).toChar())
    }
}
