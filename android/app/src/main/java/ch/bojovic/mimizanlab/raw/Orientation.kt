package ch.bojovic.mimizanlab.raw

import android.view.OrientationEventListener

/**
 * Orientation bookkeeping: the Camera2 JPEG rotation rule turned into the TIFF
 * orientation tag the Rust side (and the DNG) carry.
 */
object Orientation {
    /** Snap an [OrientationEventListener] angle to 0/90/180/270; -1 = unknown. */
    fun snap(deviceDegrees: Int): Int {
        if (deviceDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) return -1
        return ((deviceDegrees + 45) / 90 * 90) % 360
    }

    /**
     * Clockwise rotation (degrees) that makes a back-camera frame upright, for
     * the physical device rotation [deviceDegrees] (0 = natural portrait).
     * Same rule as `CaptureRequest.JPEG_ORIENTATION` for a back camera.
     */
    fun rotationDegrees(sensorOrientation: Int, deviceDegrees: Int): Int {
        val d = if (deviceDegrees < 0) 0 else deviceDegrees
        return (sensorOrientation + d + 360) % 360
    }

    /** TIFF/EXIF orientation value for a clockwise rotation. */
    fun tiffFromRotation(rotation: Int): Int = when (rotation) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    fun tiff(sensorOrientation: Int, deviceDegrees: Int): Int =
        tiffFromRotation(rotationDegrees(sensorOrientation, deviceDegrees))
}
