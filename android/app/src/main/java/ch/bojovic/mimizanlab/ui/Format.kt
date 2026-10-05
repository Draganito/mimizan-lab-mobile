package ch.bojovic.mimizanlab.ui

import ch.bojovic.mimizanlab.engine.Filter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object Format {
    fun shutter(ns: Long?): String {
        if (ns == null || ns <= 0) return "—"
        val s = ns / 1e9
        return if (s >= 0.3) String.format(Locale.US, "%.1f s", s)
        else "1/" + (1.0 / s).roundToInt()
    }

    fun iso(iso: Int?): String = if (iso == null) "—" else "ISO $iso"

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
        n >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", n / (1024.0 * 1024))
        n >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", n / 1024.0)
        else -> "$n B"
    }

    fun ev(steps: Int, step: Double): String {
        val v = steps * step
        return if (v == 0.0) "±0" else String.format(Locale.US, "%+.1f", v)
    }

    fun filterShort(f: Filter): String = when (f) {
        Filter.NONE -> "Pan"
        Filter.YELLOW8 -> "Y8"
        Filter.YELLOW_GREEN11 -> "YG11"
        Filter.ORANGE16 -> "O16"
        Filter.RED25 -> "R25"
        Filter.GREEN58 -> "G58"
        Filter.BLUE47 -> "B47"
    }

    fun filterLong(f: Filter): String = when (f) {
        Filter.NONE -> "Panchromatic (no filter)"
        Filter.YELLOW8 -> "Yellow 8"
        Filter.YELLOW_GREEN11 -> "Yellow-green 11"
        Filter.ORANGE16 -> "Orange 16"
        Filter.RED25 -> "Red 25"
        Filter.GREEN58 -> "Green 58"
        Filter.BLUE47 -> "Blue 47"
    }

    /** Log-spaced shutter slider: 0..1 -> [lo, hi] ns. */
    fun sliderToNs(t: Float, lo: Long, hi: Long): Long {
        val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
        return Math.exp(a + (b - a) * t).toLong().coerceIn(lo, hi)
    }

    fun nsToSlider(ns: Long, lo: Long, hi: Long): Float {
        val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
        return ((ln(ns.toDouble()) - a) / (b - a)).toFloat().coerceIn(0f, 1f)
    }

    fun sliderToIso(t: Float, lo: Int, hi: Int): Int {
        val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
        val v = Math.exp(a + (b - a) * t)
        // Third-stop steps read better than raw numbers.
        val stops = (ln(v / lo) / ln(2.0) * 3).roundToInt() / 3.0
        return (lo * 2.0.pow(stops)).roundToInt().coerceIn(lo, hi)
    }

    fun isoToSlider(iso: Int, lo: Int, hi: Int): Float {
        val a = ln(lo.toDouble()); val b = ln(hi.toDouble())
        return ((ln(iso.toDouble()) - a) / (b - a)).toFloat().coerceIn(0f, 1f)
    }
}
