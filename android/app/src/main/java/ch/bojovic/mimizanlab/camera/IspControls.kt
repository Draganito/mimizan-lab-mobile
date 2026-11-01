package ch.bojovic.mimizanlab.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import kotlin.math.pow

/**
 * Pins the ISP where the viewfinder can invert it. A listed sRGB preset
 * stays on while the meter runs: the HAL built its meter for that curve.
 * Where the preset is missing, the fixed sRGB curve is the same shape the
 * finder matched to the paper, but a meter that sees it (the Pixel 4a)
 * settles dark. That curve is applied only after autoexposure has locked
 * ([apply] with `pinCurve`). Lens shading stays off (the RAW has none),
 * and so do noise reduction, edge enhancement, distortion correction and
 * stabilisation. What was pinned is in [description].
 */
class IspControls(c: CameraCharacteristics) {
    /** The HAL's own sRGB. Safe to request while the meter is running. */
    val preset: Boolean
    /** A curve we draw. The meter must not see it until exposure is locked. */
    val curve: TonemapCurve?
    private val shadingOff: Boolean
    private val noiseOff: Boolean
    private val edgeOff: Boolean
    private val distortionOff: Boolean
    private val stabilizationOff: Boolean
    val description: String

    init {
        val tonemaps = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet() ?: emptySet()
        val maxPoints = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        val canDrawCurve = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemaps && maxPoints >= 8
        preset = CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tonemaps
        curve = if (!preset && canDrawCurve) srgbCurve(minOf(maxPoints, 64)) else null
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
                preset -> "preset sRGB"
                curve != null ->
                    "sRGB curve (${curve.getPointCount(TonemapCurve.CHANNEL_GREEN)} pts, after the meter settles)"
                else -> "NOT pinned (assuming sRGB)"
            },
            "shading " + if (shadingOff) "off" else "on (not pinned)",
            "nr " + if (noiseOff) "off" else "on",
            "edge " + if (edgeOff) "off" else "on",
            "distortion " + if (distortionOff) "off" else "default",
            "stab " + if (stabilizationOff) "off" else "default",
        ).joinToString(", ")
    }

    fun apply(b: CaptureRequest.Builder, pinCurve: Boolean) {
        when {
            preset -> {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            }
            pinCurve && curve != null -> {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, curve)
            }
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
        /** The HAL interpolates linearly between control points; squared spacing puts most of them where sRGB bends. */
        private fun srgbCurve(points: Int): TonemapCurve {
            val pts = FloatArray(points * 2)
            for (i in 0 until points) {
                val t = i.toFloat() / (points - 1)
                val lin = t * t
                pts[2 * i] = lin
                pts[2 * i + 1] = encode(lin)
            }
            return TonemapCurve(pts, pts, pts)
        }

        private fun encode(lin: Float): Float =
            if (lin <= 0.0031308f) 12.92f * lin else 1.055f * lin.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

        fun decode(code: Float): Float =
            if (code <= 0.04045f) code / 12.92f else ((code + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}
