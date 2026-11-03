package ch.bojovic.mimizanlab.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import kotlin.math.pow

/**
 * Pins the ISP where the viewfinder can invert it, from the first frame.
 * A listed sRGB preset is requested. Where the preset is missing, the
 * fixed sRGB curve is installed, so backlight in the finder stays as dark
 * as the paper. A Qualcomm meter follows that curve and settles dark, so
 * on that chip the curve stays off and the shader assumes sRGB. An
 * Exynos meter (Galaxy S25 FE) does not follow the curve: the paper
 * curve stays on, as on Tensor. Lens shading stays off on a Pixel,
 * where the RAW has none. On Samsung the RAW is already shaded, so
 * the preview keeps the correction or the finder is brighter in the
 * middle than the print. Noise reduction, edge enhancement, distortion
 * correction and stabilisation stay off. What was pinned is in
 * [description].
 */
class IspControls(c: CameraCharacteristics) {
    private val tonemapMode: Int?
    private val curve: TonemapCurve?
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
        val qualcommMeter = qualcommMeter(c)
        val declinedCurve = CameraMetadata.TONEMAP_MODE_PRESET_CURVE !in tonemaps && canDrawCurve && qualcommMeter
        when {
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tonemaps -> {
                tonemapMode = CameraMetadata.TONEMAP_MODE_PRESET_CURVE
                curve = null
            }
            canDrawCurve && !qualcommMeter -> {
                tonemapMode = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
                curve = srgbCurve(minOf(maxPoints, 64))
            }
            else -> {
                tonemapMode = null
                curve = null
            }
        }
        shadingOff = pinShadingOff() &&
            c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
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
                tonemapMode == CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE ->
                    "sRGB curve (${curve!!.getPointCount(TonemapCurve.CHANNEL_GREEN)} pts)"
                declinedCurve -> "not pinned (qualcomm meter follows a custom curve)"
                else -> "NOT pinned (assuming sRGB)"
            },
            "shading " + when {
                shadingOff -> "off"
                !pinShadingOff() -> "on (samsung RAW is shaded)"
                else -> "on (not pinned)"
            },
            "nr " + if (noiseOff) "off" else "on",
            "edge " + if (edgeOff) "off" else "on",
            "distortion " + if (distortionOff) "off" else "default",
            "stab " + if (stabilizationOff) "off" else "default",
        ).joinToString(", ")
    }

    fun apply(b: CaptureRequest.Builder) {
        when (tonemapMode) {
            CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            }
            CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> {
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
        /**
         * The Qualcomm camera stack meters a curve we install and settles dark.
         * Vendor tags are not always in [CameraCharacteristics.getKeys], so the
         * chip is the backstop. Tensor and Exynos (Samsung) are not
         * this chip: they keep the paper curve.
         */
        private fun qualcommMeter(c: CameraCharacteristics): Boolean {
            if (vendorCurve(c.keys.map { it.name })) return true
            if (vendorCurve(c.availableCaptureRequestKeys.map { it.name })) return true
            if (vendorCurve(c.availableCaptureResultKeys.map { it.name })) return true
            // Null on Android 13 and 14 when the HAL lists no session keys.
            if (vendorCurve(c.availableSessionKeys.orEmpty().map { it.name })) return true
            if (Build.SOC_MANUFACTURER.equals("Qualcomm", ignoreCase = true)) return true
            return Build.HARDWARE.equals("qcom", ignoreCase = true)
        }

        /**
         * Samsung's HAL shades the RAW. Forcing [SHADING_MODE_OFF] on the
         * preview then leaves the finder brighter in the middle than the
         * print. A Pixel RAW has no shading, so the preview stays off there.
         */
        internal fun pinShadingOff(manufacturer: String = Build.MANUFACTURER): Boolean =
            !manufacturer.equals("samsung", ignoreCase = true)

        /** A Qualcomm camera stack: its vendor tags carry these prefixes. */
        internal fun vendorCurve(names: List<String>): Boolean =
            names.any { it.startsWith("org.codeaurora.") || it.startsWith("org.quic.camera.") }

        private fun srgbCurve(points: Int): TonemapCurve {
            val pts = srgbCurvePoints(points)
            return TonemapCurve(pts, pts, pts)
        }

        /**
         * The control points of the sRGB curve as `(in, out)` pairs. The
         * HAL interpolates linearly between them; squared spacing puts most
         * of them where sRGB bends. Starts at (0, 0), ends at (1, 1).
         */
        internal fun srgbCurvePoints(points: Int): FloatArray {
            val pts = FloatArray(points * 2)
            for (i in 0 until points) {
                val t = i.toFloat() / (points - 1)
                val lin = t * t
                pts[2 * i] = lin
                pts[2 * i + 1] = encode(lin)
            }
            return pts
        }

        internal fun encode(lin: Float): Float =
            if (lin <= 0.0031308f) 12.92f * lin else 1.055f * lin.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

        fun decode(code: Float): Float =
            if (code <= 0.04045f) code / 12.92f else ((code + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}
