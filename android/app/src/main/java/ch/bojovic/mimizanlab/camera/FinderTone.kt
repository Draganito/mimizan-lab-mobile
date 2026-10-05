package ch.bojovic.mimizanlab.camera

import android.graphics.Bitmap
import kotlin.math.pow

/**
 * The ISP's colour step of one frame, from its capture result: the 3x3
 * matrix (row-major) it applied after the white-balance [gains]
 * (R, G_even, G_odd, B) to get linear sRGB.
 */
class IspColor(val transform: FloatArray, val gains: FloatArray) {
    fun near(o: IspColor?, eps: Float = 1e-3f): Boolean {
        if (o == null) return false
        for (i in 0 until 9) if (kotlin.math.abs(transform[i] - o.transform[i]) > eps) return false
        for (i in 0 until 4) if (kotlin.math.abs(gains[i] - o.gains[i]) > eps) return false
        return true
    }

    companion object {
        val IDENTITY = IspColor(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(1f, 1f, 1f, 1f))
    }
}

/**
 * How the camera's pinned-sRGB preview becomes the negative's display
 * value, for the GPU shader and the histogram alike:
 *
 *   gray  = v · linearise(rgb)        — [v] is the mix weights pulled back
 *                                       through the inverse ISP matrix and
 *                                       its green gain, so `gray` is the
 *                                       balanced mix the development computes
 *   value = lut[gray ^ invGamma]      — the look over its encoded axis
 *
 * [zebra] marks display values at or above it; > 1 means off.
 */
class FinderTone(
    val v: FloatArray,
    val lut: FloatArray,
    val invGamma: Float,
    val zebra: Float,
) {
    /** 256 x 1 grey ramp of [lut] for the shader (`lut.eval(x)`). */
    val lutBitmap: Bitmap by lazy {
        val n = lut.size
        val px = IntArray(n) { i ->
            val c = (lut[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        Bitmap.createBitmap(px, n, 1, Bitmap.Config.ARGB_8888)
    }

    /** Display value of the linear gray `i / (size - 1)`, for the histogram's inner loop. */
    val grayTable: FloatArray by lazy { FloatArray(1024) { displayOfGray(it / 1023f) } }

    /** Display value 0..1 of one linear-sRGB pixel. */
    fun display(r: Float, g: Float, b: Float): Float =
        displayOfGray((v[0] * r + v[1] * g + v[2] * b).coerceIn(0f, 1f))

    private fun displayOfGray(gray: Float): Float {
        val e = if (invGamma == 1f) gray else gray.toDouble().pow(invGamma.toDouble()).toFloat()
        val x = e * (lut.size - 1)
        val i = x.toInt().coerceIn(0, lut.size - 2)
        val f = x - i
        return lut[i] + (lut[i + 1] - lut[i]) * f
    }

    companion object {
        /**
         * [weights] are the balanced mix weights (`mix_weights`), [color]
         * the ISP step of the current frame. Falls back to the weights alone
         * when the matrix is singular.
         */
        fun pullBack(weights: FloatArray, color: IspColor): FloatArray {
            val inv = invert3(color.transform) ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            val gMean = 0.5f * (color.gains[1] + color.gains[2])
            val scale = if (gMean > 1e-6f) 1f / gMean else 1f
            // v = inv^T · w, i.e. v_j = Σ_i w_i · inv[i][j]
            return FloatArray(3) { j ->
                (weights[0] * inv[0 * 3 + j] + weights[1] * inv[1 * 3 + j] + weights[2] * inv[2 * 3 + j]) * scale
            }
        }

        private fun invert3(m: FloatArray): FloatArray? {
            val a = m[0]; val b = m[1]; val c = m[2]
            val d = m[3]; val e = m[4]; val f = m[5]
            val g = m[6]; val h = m[7]; val i = m[8]
            val co00 = e * i - f * h
            val co01 = -(d * i - f * g)
            val co02 = d * h - e * g
            val det = a * co00 + b * co01 + c * co02
            if (kotlin.math.abs(det) < 1e-9f) return null
            val r = 1f / det
            return floatArrayOf(
                co00 * r, -(b * i - c * h) * r, (b * f - c * e) * r,
                co01 * r, (a * i - c * g) * r, -(a * f - c * d) * r,
                co02 * r, -(a * h - b * g) * r, (a * e - b * d) * r,
            )
        }
    }
}
