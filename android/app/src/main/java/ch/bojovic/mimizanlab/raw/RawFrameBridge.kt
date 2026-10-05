package ch.bojovic.mimizanlab.raw

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.os.Build
import ch.bojovic.mimizanlab.engine.BayerPattern
import ch.bojovic.mimizanlab.engine.ExposureInfo
import ch.bojovic.mimizanlab.engine.RawInput
import ch.bojovic.mimizanlab.engine.RawRect
import ch.bojovic.mimizanlab.engine.bayerPatternFromCamera2
import org.json.JSONArray
import org.json.JSONObject

/**
 * A RAW_SENSOR frame with its metadata, detached from the camera: the pixel
 * buffer is copied with the row stride removed, so the [Image] can go back to
 * the reader immediately.
 */
class RawCapture(
    val width: Int,
    val height: Int,
    /** `width*height` native-endian (little-endian on arm64) 16-bit samples. */
    val pixels: ByteArray,
    val characteristics: CameraCharacteristics,
    val result: TotalCaptureResult,
    val cameraId: String,
    val lensLabel: String,
    val timestampNs: Long,
    /** Clockwise rotation that makes the frame upright. */
    val rotationDegrees: Int,
    val capturedAtEpochMs: Long,
) {
    val tiffOrientation: Int get() = Orientation.tiffFromRotation(rotationDegrees)

    /** Metadata-only view, enough to rebuild a [RawInput] from the cached pixels. */
    fun meta(): RawMeta = RawFrameBridge.meta(this)

    companion object {
        /** Copy the first plane of a RAW_SENSOR image into a tightly packed array. */
        fun copyPixels(image: Image): ByteArray {
            val plane = image.planes[0]
            val buf = plane.buffer
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val rowBytes = w * 2
            val out = ByteArray(rowBytes * h)
            if (rowStride == rowBytes) {
                buf.rewind()
                buf.get(out, 0, minOf(out.size, buf.remaining()))
            } else {
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    buf.get(out, y * rowBytes, rowBytes)
                }
            }
            return out
        }
    }
}

/** Everything about a frame except the pixels (serialisable to JSON). */
data class RawMeta(
    val make: String,
    val model: String,
    val width: Int,
    val height: Int,
    val cfa: Int,
    val blackTile: DoubleArray,
    val whiteLevel: Double,
    val crop: IntArray?,
    val tiffOrientation: Int,
    val wbGains: DoubleArray?,
    val xyzToCam: DoubleArray?,
    val iso: Int?,
    val exposureNs: Long?,
    val fNumber: Double?,
    val focalMm: Double?,
    val lens: String,
    val cameraId: String,
    val capturedAtEpochMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("make", make); put("model", model)
        put("width", width); put("height", height)
        put("cfa", cfa)
        put("black_tile", JSONArray(blackTile.toList()))
        put("white_level", whiteLevel)
        crop?.let { put("crop", JSONArray(it.toList())) }
        put("orientation", tiffOrientation)
        wbGains?.let { put("wb_gains", JSONArray(it.toList())) }
        xyzToCam?.let { put("xyz_to_cam", JSONArray(it.toList())) }
        iso?.let { put("iso", it) }
        exposureNs?.let { put("exposure_ns", it) }
        fNumber?.let { put("f_number", it) }
        focalMm?.let { put("focal_mm", it) }
        put("lens", lens); put("camera_id", cameraId)
        put("captured_at_ms", capturedAtEpochMs)
    }

    fun toRawInput(pixels: ByteArray): RawInput = RawInput(
        make = make,
        model = model,
        width = width.toUInt(),
        height = height.toUInt(),
        pattern = patternOf(cfa),
        blackTile = blackTile.toList(),
        whiteLevel = whiteLevel,
        crop = crop?.let { RawRect(it[0].toUInt(), it[1].toUInt(), it[2].toUInt(), it[3].toUInt()) },
        orientation = tiffOrientation.toUInt(),
        wbGains = wbGains?.toList(),
        xyzToCam = xyzToCam?.toList(),
        exposure = ExposureInfo(
            iso = iso?.toUInt(),
            exposureNs = exposureNs?.toULong(),
            fNumber = fNumber,
            focalMm = focalMm,
            lens = lens,
        ),
        data = pixels,
    )

    companion object {
        fun patternOf(cfa: Int): BayerPattern =
            runCatching { bayerPatternFromCamera2(cfa) }.getOrDefault(BayerPattern.RGGB)

        fun fromJson(o: JSONObject): RawMeta = RawMeta(
            make = o.optString("make"),
            model = o.optString("model"),
            width = o.getInt("width"),
            height = o.getInt("height"),
            cfa = o.getInt("cfa"),
            blackTile = o.getJSONArray("black_tile").toDoubles(),
            whiteLevel = o.getDouble("white_level"),
            crop = o.optJSONArray("crop")?.let { a -> IntArray(a.length()) { a.getInt(it) } },
            tiffOrientation = o.optInt("orientation", 1),
            wbGains = o.optJSONArray("wb_gains")?.toDoubles(),
            xyzToCam = o.optJSONArray("xyz_to_cam")?.toDoubles(),
            iso = if (o.has("iso")) o.getInt("iso") else null,
            exposureNs = if (o.has("exposure_ns")) o.getLong("exposure_ns") else null,
            fNumber = if (o.has("f_number")) o.getDouble("f_number") else null,
            focalMm = if (o.has("focal_mm")) o.getDouble("focal_mm") else null,
            lens = o.optString("lens"),
            cameraId = o.optString("camera_id"),
            capturedAtEpochMs = o.optLong("captured_at_ms"),
        )

        private fun JSONArray.toDoubles() = DoubleArray(length()) { getDouble(it) }
    }
}

/** Camera2 characteristics + capture result -> the Rust `RawInput` contract. */
object RawFrameBridge {
    fun meta(c: RawCapture): RawMeta {
        val ch = c.characteristics
        val r = c.result
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        // Dynamic black level of this very frame when the HAL reports it,
        // else the static pattern. Both are row-major over the 2x2 CFA tile.
        val dyn = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = DoubleArray(4)
        if (dyn != null && dyn.size == 4) {
            for (i in 0 until 4) black[i] = dyn[i].toDouble()
        } else {
            val p = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (p != null) {
                val tmp = IntArray(4)
                p.copyTo(tmp, 0)
                for (i in 0 until 4) black[i] = tmp[i].toDouble()
            }
        }
        val white = (r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
            ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()

        val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val crop = cropOf(pre, active, c.width, c.height)

        val gains = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = gains?.let { doubleArrayOf(it.red.toDouble(), it.greenEven.toDouble(), it.greenOdd.toDouble(), it.blue.toDouble()) }

        val xf = ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val xyz = xf?.let { t ->
            DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() }
        }

        return RawMeta(
            make = Build.MANUFACTURER,
            model = Build.MODEL,
            width = c.width,
            height = c.height,
            cfa = cfa,
            blackTile = black,
            whiteLevel = white,
            crop = crop,
            tiffOrientation = c.tiffOrientation,
            wbGains = wb,
            xyzToCam = xyz,
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            fNumber = r.get(CaptureResult.LENS_APERTURE)?.toDouble(),
            focalMm = r.get(CaptureResult.LENS_FOCAL_LENGTH)?.toDouble(),
            lens = c.lensLabel,
            cameraId = c.cameraId,
            capturedAtEpochMs = c.capturedAtEpochMs,
        )
    }

    /** Active array relative to the buffer; null when it is the whole buffer. */
    fun cropOf(pre: Rect?, active: Rect?, bufW: Int, bufH: Int): IntArray? {
        if (pre == null || active == null) return null
        // The RAW buffer is the pre-correction array; if the HAL hands out a
        // buffer of another size the offsets do not apply.
        if (pre.width() != bufW || pre.height() != bufH) return null
        val x = (active.left - pre.left).coerceIn(0, bufW)
        val y = (active.top - pre.top).coerceIn(0, bufH)
        val w = active.width().coerceIn(1, bufW - x)
        val h = active.height().coerceIn(1, bufH - y)
        if (x == 0 && y == 0 && w == bufW && h == bufH) return null
        return intArrayOf(x, y, w, h)
    }

    fun toRawInput(c: RawCapture): RawInput = meta(c).toRawInput(c.pixels)
}
