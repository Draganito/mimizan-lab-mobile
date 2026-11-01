package ch.bojovic.mimizanlab.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import kotlin.math.pow

/**
 * Pins the ISP where the device can do it without disturbing the meter.
 * A listed sRGB preset is requested, so the viewfinder shader can invert
 * that curve exactly. A hand-built contrast curve is not: on phones that
 * only offer that, the meter sees the curve and the preview settles dark.
 * Those phones keep the camera's own curve, and the shader still assumes
 * sRGB. Lens shading stays off (the RAW has none, so the negative keeps
 * the vignetting), and so do noise reduction, edge enhancement, distortion
 * correction and stabilisation. Only modes the device lists are requested;
 * what could not be pinned is in [description].
 */
class IspControls(c: CameraCharacteristics) {
    private val tonemapMode: Int?
    private val shadingOff: Boolean
    private val noiseOff: Boolean
    private val edgeOff: Boolean
    private val distortionOff: Boolean
    private val stabilizationOff: Boolean
    val description: String

    init {
        val tonemaps = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet() ?: emptySet()
        // PRESET_CURVE is the HAL's own sRGB, and autoexposure is built for it.
        // CONTRAST_CURVE is a curve we would install ourselves. Phones without
        // the preset (the Pixel 4a, and other older HALs) then meter the
        // altered preview and walk darker. Leaving the curve unset keeps the
        // meter. Phones that list the preset are unchanged.
        val declinedCurve = CameraMetadata.TONEMAP_MODE_PRESET_CURVE !in tonemaps &&
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemaps
        tonemapMode = if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tonemaps) {
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE
        } else {
            null
        }
        shadingOff = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
            ?.contains(CameraMetadata.SHADING_MODE_OFF) == true
        noiseOff = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true
        edgeOff = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.contains(CameraMetadata.EDGE_MODE_OFF) == true
        distortionOff = c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            ?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true
        stabilizationOff = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF) == true
        description = listOf(
            "tonemap " + when {
                tonemapMode == CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "preset sRGB"
                declinedCurve -> "not pinned (custom curve disturbs autoexposure)"
                else -> "NOT pinned (assuming sRGB)"
            },
            "shading " + if (shadingOff) "off" else "on (not pinned)",
            "nr " + if (noiseOff) "off" else "on",
            "edge " + if (edgeOff) "off" else "on",
            "distortion " + if (distortionOff) "off" else "default",
            "stab " + if (stabilizationOff) "off" else "default",
        ).joinToString(", ")
    }

    fun apply(b: CaptureRequest.Builder) {
        if (tonemapMode == CameraMetadata.TONEMAP_MODE_PRESET_CURVE) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
            b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
        }
        if (shadingOff) b.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
        if (noiseOff) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        if (edgeOff) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        if (distortionOff) b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        if (stabilizationOff) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
    }

    companion object {
        fun decode(code: Float): Float =
            if (code <= 0.04045f) code / 12.92f else ((code + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}
