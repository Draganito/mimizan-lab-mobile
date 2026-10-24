package ch.bojovic.mimizanlab.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Who sets the exposure while the switch is on A. */
enum class MeteringMode {
    /** The phone's auto-exposure, with its EV compensation. */
    PHONE,
    /** The app, on RAW probes: the brightest raw channel is placed just under the clip (ETTR). */
    HIGHLIGHTS,
    /** The app, on RAW probes: the negative's grey (or the tapped spot) is placed at the grey card. */
    GREY,
}

/** What one RAW probe measured (`meter_raw`). Fractions of the range above black, 1 = clip. */
data class Reading(
    val highlight: Double,
    val grey: Double,
    val spotGrey: Double?,
    val clipped: Double,
)

/** ISO and time the loop asks for: the viewfinder's pair and the still's pair. */
data class ExposurePlan(
    val previewIso: Int,
    val previewNs: Long,
    val stillIso: Int,
    val stillNs: Long,
    /** The still cannot reach the exposure within its limits; Auto flash fires. */
    val flashRequired: Boolean,
)

/**
 * The app's own exposure control: a pure state machine fed by RAW probes,
 * no Camera2 inside.
 *
 * The loop keeps one number, the exposure `H = ISO · seconds` it wants.
 * The sensor is linear, so a probe taken at `H_p` whose statistic sits at
 * `s` needs `H = H_p · target / s` to sit at the target: one step, no
 * iteration. A dead band of a sixth of a stop keeps the readout still
 * against handshake; a probe that does not yet show the commanded exposure
 * (the pipeline is a few frames deep) is ignored.
 *
 * `H` is split into ISO and time twice: for the viewfinder the time stays
 * at or under 1/30 s so the stream keeps its rate, for the still it may go
 * to the handheld limit of the lens. Both take the lowest ISO that keeps
 * the time within the cap; the statistics do not depend on the split.
 */
class ExposureMeter(
    private val isoMin: Int,
    private val isoMax: Int,
    private val nsMin: Long,
    nsMax: Long,
    equivalentMm: Int,
    val mode: MeteringMode,
) {
    /** Stops added to the target (the EV slider). */
    @Volatile var biasStops: Double = 0.0

    /** Mains frequency the HAL reports (`STATISTICS_SCENE_FLICKER`); 0 = none seen. */
    @Volatile var flickerHz: Int = 0

    /** Longest still time: 1/8 s, or shorter for a longer lens (2/f for the equivalent focal length). */
    private val stillCapNs: Long = run {
        val byFocal = if (equivalentMm > 0) (2e9 / equivalentMm).toLong() else STILL_CAP_NS
        min(STILL_CAP_NS, byFocal).coerceIn(nsMin, nsMax)
    }
    private val previewCapNs: Long = min(PREVIEW_CAP_NS, stillCapNs).coerceAtLeast(nsMin)
    private val hMin = isoMin * nsMin / 1e9
    private val hStillMax = isoMax * stillCapNs / 1e9

    private var wanted = 100 * 1.0 / 60
    private var skipped = 0

    @Volatile var plan: ExposurePlan = planFor(wanted)
        private set

    /** Start from a known pair, e.g. what the phone's AE was using. */
    fun seed(iso: Int, ns: Long) {
        wanted = (iso * ns / 1e9).coerceIn(hMin, hStillMax * BEYOND)
        skipped = 0
        plan = planFor(wanted)
    }

    /**
     * One probe taken at [iso] and [ns]. Returns true when the plan changed.
     * A probe whose exposure is not the commanded one is skipped, three
     * times at most, so a change is not applied twice while it is in flight.
     */
    fun onProbe(r: Reading, iso: Int, ns: Long): Boolean {
        val hp = iso * ns / 1e9
        if (hp <= 0.0) return false
        val commanded = plan.previewIso * plan.previewNs / 1e9
        if (abs(log2(hp / commanded)) > IN_FLIGHT_TOLERANCE && skipped < 3) {
            skipped++
            return false
        }
        skipped = 0
        val stops = correction(r)
        if (abs(stops) < DEAD_BAND) return false
        wanted = (hp * 2.0.pow(stops)).coerceIn(hMin, hStillMax * BEYOND)
        val next = planFor(wanted)
        if (next == plan) return false
        plan = next
        return true
    }

    /** Stops to add to the probe's exposure so the statistic meets the target. */
    internal fun correction(r: Reading): Double {
        val bias = 2.0.pow(biasStops)
        val stops = when (mode) {
            MeteringMode.HIGHLIGHTS -> {
                var s = when {
                    // Clipped: how far over is unknown; a stop down per probe until it shows.
                    r.highlight >= CLIP -> -1.0
                    r.highlight <= 1e-6 -> MAX_UP
                    else -> log2(HIGHLIGHT_TARGET * bias / r.highlight)
                }
                // Expose for the shadows: when the scene's range exceeds the
                // paper's, the step down stops where the scene's grey reaches
                // Zone III and the lights above the budget go white.
                if (s < 0 && r.grey > 0) s = max(s, min(0.0, log2(GREY_FLOOR * bias / r.grey)))
                s
            }
            MeteringMode.GREY -> {
                val g = r.spotGrey ?: r.grey
                if (g <= 1e-6) MAX_UP else log2(GREY_TARGET * bias / g)
            }
            MeteringMode.PHONE -> 0.0
        }
        return stops.coerceIn(MAX_DOWN, MAX_UP)
    }

    private fun planFor(h: Double): ExposurePlan {
        val (pi, pt) = split(h, previewCapNs)
        val (si, st) = split(h, stillCapNs)
        return ExposurePlan(pi, pt, si, st, flashRequired = h > hStillMax * FLASH_SHORTFALL)
    }

    /**
     * Lowest ISO whose time stays within [capNs]. At or above the mains
     * half-period the time is a whole number of those periods, so a sensor
     * line sees the same light under a flickering lamp; the ISO makes up
     * the difference (under a factor of two).
     */
    internal fun split(h: Double, capNs: Long): Pair<Int, Long> {
        var t = (h / isoMin * 1e9).toLong().coerceIn(nsMin, capNs)
        val period = when (flickerHz) {
            50 -> 10_000_000L
            60 -> 8_333_333L
            else -> 0L
        }
        if (period > 0 && t >= period) t = (t / period) * period
        val iso = (h / (t / 1e9)).roundToInt().coerceIn(isoMin, isoMax)
        return iso to t
    }

    companion object {
        /** Where the 99th percentile of the raw channels goes: half a stop under the clip. */
        const val HIGHLIGHT_TARGET = 0.7071
        /** The grey card (`zone::GRAY_CARD`), Zone V at the paper's base exposure. */
        const val GREY_TARGET = 0.18
        /**
         * Highlights mode never pushes the scene's grey below Zone III, two
         * stops under the card: the lowest placement that still prints
         * shadow texture. Lights that would need more go white instead.
         */
        const val GREY_FLOOR = 0.18 / 4
        /** A percentile this high is at the clip for a 10-bit sensor. */
        const val CLIP = 0.98
        const val DEAD_BAND = 1.0 / 6
        const val IN_FLIGHT_TOLERANCE = 0.1
        const val MAX_UP = 3.0
        const val MAX_DOWN = -4.0
        /** Stops the wanted exposure may exceed the still's limits, so the flash decision can see the shortfall. */
        private const val BEYOND = 8.0
        /** Half a stop short of the target at the still's limits asks for the flash. */
        private const val FLASH_SHORTFALL = 1.414
        const val PREVIEW_CAP_NS = 33_333_333L
        const val STILL_CAP_NS = 125_000_000L

        fun log2(x: Double): Double = ln(x) / ln(2.0)
    }
}
