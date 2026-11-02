package ch.bojovic.mimizanlab.store

import ch.bojovic.mimizanlab.engine.PeakMeasured

/** The paper's two exposures, in stops from the base print. */
data class Grade(val soft: Double, val hard: Double)

/**
 * The arithmetic of the grade, the zone strip and Peak, with every limit
 * passed in. [Prefs] fills the limits from the core; the JVM tests pass
 * their own, so nothing here needs the native library.
 */
object GradeMath {
    /** Twelfths of a stop, inside ±[limit]; a non-finite value snaps to zero. */
    fun snapStops(v: Double, limit: Double): Double =
        (kotlin.math.round(finiteOrZero(v) * 12.0) / 12.0).coerceIn(-limit, limit)

    /**
     * Add [delta] stops to both exposures, snapped to a twelfth, and stop
     * at [limit] so the gap stays. Returns the pair and the stops actually
     * added (the delta after the snap and the clamp).
     */
    fun shiftGrade(soft: Double, hard: Double, delta: Double, limit: Double): Placed {
        val lo = -limit - minOf(soft, hard)
        val hi = limit - maxOf(soft, hard)
        val d = (kotlin.math.round(finiteOrZero(delta) * 12.0) / 12.0).coerceIn(lo, hi)
        return Placed(Grade(soft + d, hard + d), d)
    }

    /**
     * The stored grade with the balance's stops on top: this negative's
     * shift and the compensation, as one delta through [shiftGrade].
     */
    fun place(stored: Grade, shift: Double, bias: Double, limit: Double): Placed =
        shiftGrade(stored.soft, stored.hard, shift + bias, limit)

    /**
     * The grade the user set on a placed print, taken back to the stored
     * grade: the stops the placement added come off both exposures, each
     * inside ±[limit]. A placement that pinned a slider at the limit cannot
     * be undone exactly; what the user sees stays, the stored pair moves.
     */
    fun unplace(shown: Grade, placedShift: Double, limit: Double): Grade =
        Grade(snapStops(shown.soft - placedShift, limit), snapStops(shown.hard - placedShift, limit))

    /** Radius and amount a print uses. Off applies nothing. Auto uses the measurement, or nothing when there is none. */
    fun peakApplied(
        mode: PeakMode,
        radius: Double,
        amount: Double,
        measured: PeakMeasured?,
        radiusDefault: Double,
    ): Pair<Double, Double> =
        when (mode) {
            PeakMode.OFF -> radius to 0.0
            PeakMode.AUTO -> (measured?.radius ?: radiusDefault) to (measured?.amount ?: 0.0)
            PeakMode.CUSTOM -> radius to amount
        }

    /**
     * The shot mode from the preferences as written by this or an earlier
     * version: the named mode, else the old stay-on-finder switch (on was
     * JPEG, off was Developer), else JPEG for a fresh install.
     */
    fun shotModeOf(named: String?, stayOnFinder: Boolean?): ShotMode {
        if (named != null) return enumOr(named, ShotMode.JPEG)
        if (stayOnFinder == null) return ShotMode.JPEG
        return if (stayOnFinder) ShotMode.JPEG else ShotMode.DEVELOPER
    }

    /**
     * Peak's mode the same way: the named mode, else the old checkbox (on
     * was the measurement, off was none), else Auto for a fresh install.
     */
    fun peakModeOf(named: String?, peakOn: Boolean?): PeakMode {
        if (named != null) return enumOr(named, PeakMode.AUTO)
        if (peakOn == null) return PeakMode.AUTO
        return if (peakOn) PeakMode.AUTO else PeakMode.OFF
    }

    /** A stored float that was never finite (a damaged preferences file) reads as zero. */
    fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

    inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

/** A grade with the balance on it, and the stops the balance actually added. */
data class Placed(val grade: Grade, val shift: Double)
