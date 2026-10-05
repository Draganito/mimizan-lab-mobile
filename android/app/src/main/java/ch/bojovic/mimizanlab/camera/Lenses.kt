package ch.bojovic.mimizanlab.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Rational
import android.util.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One back lens that can deliver `RAW_SENSOR` frames. Either a logical
 * camera ([physicalId] = null) or a physical camera behind a logical one
 * (Pixel: the ultrawide is physical id "3" of logical "0" and does not
 * appear in `cameraIdList`).
 */
data class LensInfo(
    /** The id to open. */
    val cameraId: String,
    /** Physical camera to route the streams to, or null for the logical camera itself. */
    val physicalId: String?,
    val label: String,
    val focalMm: Float,
    /** 35 mm equivalent focal length, from the sensor diagonal. */
    val equivalentMm: Int,
    val rawSize: Size,
    val sensorOrientation: Int,
    val isoRange: Range<Int>,
    val exposureRangeNs: Range<Long>,
    val evRange: Range<Int>,
    val evStep: Rational,
    val hasManualSensor: Boolean,
    val hasAutoFocus: Boolean,
    /** LED of the camera that is opened (the logical one, also for a physical lens). */
    val hasFlash: Boolean,
    val physicalWidthMm: Float,
    val physicalHeightMm: Float,
) {
    val isUltraWide: Boolean get() = equivalentMm in 1..20

    /** Id whose characteristics describe the sensor (black level, CFA, arrays). */
    val sensorId: String get() = physicalId ?: cameraId

    val key: String get() = "$cameraId/${physicalId ?: "-"}"
}

object Lenses {
    /** Back lenses with RAW capability: main first, then by equivalent focal length. */
    fun enumerate(manager: CameraManager): List<LensInfo> {
        val lenses = mutableListOf<LensInfo>()
        for (id in manager.cameraIdList) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val logical = describe(id, null, c, hasFlash) ?: continue
            lenses += logical
            // Physical cameras of a logical one with a different focal length
            // (same focal = the same lens in another binning mode).
            for (pid in c.physicalCameraIds) {
                val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val lens = describe(id, pid, pc, hasFlash) ?: continue
                if (abs(lens.focalMm - logical.focalMm) < 0.05f) continue
                lenses += lens
            }
        }
        return lenses
            .sortedWith(compareBy({ it.label != "Main" }, { it.equivalentMm }, { it.cameraId }))
            .distinctBy { it.equivalentMm to it.rawSize }
    }

    private fun describe(openId: String, physicalId: String?, c: CameraCharacteristics, hasFlash: Boolean): LensInfo? {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        val raw = rawSizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
        val focal = focals.firstOrNull() ?: 0f
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (phys != null) hypot(phys.width, phys.height) else 0f
        val equiv = if (diag > 0f) (focal * 43.27f / diag).roundToInt() else 0
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val label = when {
            equiv in 1..20 -> "Ultrawide"
            equiv > 40 -> "Tele"
            else -> "Main"
        }
        return LensInfo(
            cameraId = openId,
            physicalId = physicalId,
            label = label,
            focalMm = focal,
            equivalentMm = equiv,
            rawSize = raw,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(100_000L, 1_000_000_000L),
            evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1),
            hasManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            hasAutoFocus = minFocus > 0f,
            hasFlash = hasFlash,
            physicalWidthMm = phys?.width ?: 0f,
            physicalHeightMm = phys?.height ?: 0f,
        )
    }
}
