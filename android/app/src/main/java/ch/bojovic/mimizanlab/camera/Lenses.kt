package ch.bojovic.mimizanlab.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import ch.bojovic.mimizanlab.raw.RawFrameBridge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One back lens that can deliver `RAW_SENSOR` frames. Either a logical
 * camera ([physicalId] = null) or a physical camera behind a logical one
 * (Pixel: the ultrawide is physical id "3" of logical "0" and does not
 * appear in `cameraIdList`).
 *
 * A logical camera without RAW does not hide the physical cameras behind
 * it. Phones that list each lens on its own (most Samsung HALs) are opened
 * directly; [physicalId] is only used when the sensor is absent from
 * `cameraIdList`.
 */

/** Largest RAW frame the phone pipeline is sized for (Pixel 9 is 12.5 MP). */
internal const val RAW_PIXEL_CAP = 16_000_000L
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
    /** `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT` of the sensor (0 when unknown). */
    val cfa: Int = 0,
    /** XYZ → camera for D65, 9 values row-major, for the plate's camera filters in the viewfinder. */
    val xyzToCam: DoubleArray? = null,
) {
    /** Id whose characteristics describe the sensor (black level, CFA, arrays). */
    val sensorId: String get() = physicalId ?: cameraId

    val key: String get() = "$cameraId/${physicalId ?: "-"}"
}

object Lenses {
    private const val TAG = "Lenses"

    /** Back lenses with RAW capability: main first, then by equivalent focal length. */
    fun enumerate(manager: CameraManager): List<LensInfo> {
        val listed = manager.cameraIdList.toSet()
        val lenses = mutableListOf<LensInfo>()
        for (id in manager.cameraIdList) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val logical = describe(id, null, c, hasFlash)
            if (logical != null) lenses += logical
            // Physical cameras of a logical one with a different focal length
            // (same focal = the same lens in another binning mode). A sensor
            // that has its own id is opened directly further down this loop;
            // routing through the logical camera is the less reliable path.
            for (pid in c.physicalCameraIds) {
                if (pid in listed) continue
                val pc = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                val lens = describe(id, pid, pc, hasFlash) ?: continue
                if (logical != null && abs(lens.focalMm - logical.focalMm) < 0.05f) continue
                lenses += lens
            }
        }
        val chosen = lenses
            // Direct opens before a physical route, so a duplicate sensor keeps the direct one.
            .sortedWith(compareBy({ it.label != "Main" }, { it.physicalId != null }, { it.equivalentMm }, { it.cameraId }))
            .distinctBy { it.equivalentMm to it.rawSize }
        if (chosen.isEmpty()) Log.w(TAG, "no back camera offers RAW_SENSOR")
        for (lens in chosen) {
            Log.i(
                TAG,
                "lens ${lens.label} id=${lens.cameraId} physical=${lens.physicalId ?: "-"} " +
                    "raw=${lens.rawSize.width}x${lens.rawSize.height} equiv=${lens.equivalentMm}mm cfa=${lens.cfa}",
            )
        }
        return chosen
    }

    private fun describe(openId: String, physicalId: String?, c: CameraCharacteristics, hasFlash: Boolean): LensInfo? {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
        val choice = chooseRawSize(rawSizes.map { it.width to it.height }) ?: return null
        val raw = Size(choice.first, choice.second)
        if (rawSizes.size > 1) {
            Log.i(
                TAG,
                "camera $openId/${physicalId ?: "-"} raw sizes " +
                    rawSizes.joinToString { "${it.width}x${it.height}" } +
                    " -> ${raw.width}x${raw.height}",
            )
        }
        val pixels = raw.width.toLong() * raw.height
        if (pixels > RAW_PIXEL_CAP) {
            Log.w(TAG, "camera $openId RAW ${raw.width}x${raw.height} is above $RAW_PIXEL_CAP px; the frame may exhaust memory")
        }
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(0f)
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = if (phys != null) hypot(phys.width.toDouble(), phys.height.toDouble()).toFloat() else 0f
        val focal = focalForLens(focals, diag)
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
            cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0,
            xyzToCam = RawFrameBridge.colourMatrix(c),
        )
    }
}

/**
 * Largest advertised RAW size that stays within [cap]. A quad-bayer phone
 * often lists the binned ~12 MP mosaic and the full 50–200 MP sensor; the
 * full frame is the same picture at a size this pipeline cannot hold.
 * When every size is above the cap, the smallest one is the only choice.
 */
internal fun chooseRawSize(sizes: List<Pair<Int, Int>>, cap: Long = RAW_PIXEL_CAP): Pair<Int, Int>? {
    if (sizes.isEmpty()) return null
    fun pixels(s: Pair<Int, Int>) = s.first.toLong() * s.second
    val under = sizes.filter { pixels(it) in 1L..cap }
    return if (under.isNotEmpty()) under.maxBy(::pixels) else sizes.minBy(::pixels)
}

/**
 * One focal length for the label. A logical camera lists every lens behind
 * it; the one nearest a 24 mm equivalent is the "main" people expect to
 * open. A physical camera has a single focal and is left alone.
 */
internal fun focalForLens(focals: FloatArray, diagMm: Float): Float {
    if (focals.isEmpty()) return 0f
    if (focals.size == 1 || diagMm <= 0f) return focals[0]
    return focals.minBy { abs(it * 43.27f / diagMm - 24f) }
}
