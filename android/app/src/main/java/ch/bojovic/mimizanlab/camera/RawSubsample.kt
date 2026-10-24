package ch.bojovic.mimizanlab.camera

import android.graphics.Rect
import android.media.Image

/**
 * A sparse sample of a `RAW_SENSOR` frame for the meter: one whole 2x2 tile
 * every [step] pixels of the active area, packed as a small Bayer image.
 * Its (0,0) is an even position of the frame, so the sample has the phase
 * of the frame's (0,0) and the frame's black tile applies unchanged.
 * [data] is `width*height` little-endian 16-bit samples.
 */
class RawSample(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    /** Frame position of the sample's (0,0). */
    val originX: Int,
    val originY: Int,
    val step: Int,
) {
    /**
     * Sample-pixel rectangle `[x, y, w, h]` covered by a frame rectangle,
     * or null when no whole tile lies inside it.
     */
    fun rectOf(left: Int, top: Int, right: Int, bottom: Int): IntArray? {
        val i0 = ceilDiv(left - originX, step).coerceAtLeast(0)
        val i1 = (floorDiv(right - originX - 2, step) + 1).coerceAtMost(width / 2)
        val j0 = ceilDiv(top - originY, step).coerceAtLeast(0)
        val j1 = (floorDiv(bottom - originY - 2, step) + 1).coerceAtMost(height / 2)
        if (i1 <= i0 || j1 <= j0) return null
        return intArrayOf(2 * i0, 2 * j0, 2 * (i1 - i0), 2 * (j1 - j0))
    }

    private fun ceilDiv(a: Int, b: Int) = Math.floorDiv(a + b - 1, b)
    private fun floorDiv(a: Int, b: Int) = Math.floorDiv(a, b)
}

object RawSubsample {
    /** Pixels between sampled tiles: 4080x3072 gives 255x192 tiles, 392 kB. */
    const val STEP = 16

    /**
     * Sample [image] inside [crop] (buffer coordinates; null = the whole
     * buffer). Rows are copied out in bulk, two per tile row, then the
     * tiles are picked from the copy: per-sample `ByteBuffer.get` is far
     * too slow here. [scratch] is reused between calls when large enough.
     */
    fun of(image: Image, crop: Rect?, scratch: ByteArray?, step: Int = STEP): Pair<RawSample, ByteArray> {
        val plane = image.planes[0]
        val buf = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bufW = image.width
        val bufH = image.height
        val area = crop ?: Rect(0, 0, bufW, bufH)
        // Even origin: the sample keeps the frame's phase.
        val x0 = (area.left + 1) and 1.inv()
        val y0 = (area.top + 1) and 1.inv()
        val aw = area.right.coerceAtMost(bufW) - x0
        val ah = area.bottom.coerceAtMost(bufH) - y0
        require(aw >= 2 && ah >= 2) { "active area ${aw}x$ah too small to sample" }
        val tilesX = (aw - 2) / step + 1
        val tilesY = (ah - 2) / step + 1
        val outW = 2 * tilesX
        val outH = 2 * tilesY
        val out = ByteArray(outW * outH * 2)
        val spanPx = (tilesX - 1) * step + 2
        val spanBytes = spanPx * pixelStride
        val row = if (scratch != null && scratch.size >= spanBytes) scratch else ByteArray(spanBytes)
        for (j in 0 until tilesY) {
            val y = y0 + j * step
            for (r in 0..1) {
                buf.position((y + r) * rowStride + x0 * pixelStride)
                buf.get(row, 0, spanBytes)
                val outRow = ((2 * j + r) * outW) * 2
                if (pixelStride == 2) {
                    for (i in 0 until tilesX) {
                        System.arraycopy(row, i * step * 2, out, outRow + i * 4, 4)
                    }
                } else {
                    for (i in 0 until tilesX) {
                        val s0 = i * step * pixelStride
                        val s1 = s0 + pixelStride
                        val o = outRow + i * 4
                        out[o] = row[s0]; out[o + 1] = row[s0 + 1]
                        out[o + 2] = row[s1]; out[o + 3] = row[s1 + 1]
                    }
                }
            }
        }
        return RawSample(outW, outH, out, x0, y0, step) to row
    }
}
