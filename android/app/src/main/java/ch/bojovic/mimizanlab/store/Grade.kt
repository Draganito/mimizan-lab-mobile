package ch.bojovic.mimizanlab.store

import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.PeakMeasured
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.Process
import org.json.JSONObject

/** The paper's two exposures, in stops from the base print. */
data class Grade(val soft: Double, val hard: Double) {
    fun toJson(): JSONObject = JSONObject().put("soft", soft).put("hard", hard)

    companion object {
        val BASE = Grade(0.0, 0.0)

        /** A grade from its object; null when the fields are not there. */
        fun fromJson(o: JSONObject, limit: Double): Grade? = runCatching {
            Grade(GradeMath.snapStops(o.getDouble("soft"), limit), GradeMath.snapStops(o.getDouble("hard"), limit))
        }.getOrNull()
    }
}

/**
 * Peak on a print. Off applies nothing. Auto is the measurement of the
 * negative it is applied to. Custom is [radius] and [amount] by hand;
 * the two are carried for the other modes too, so a return to Custom
 * finds the pair again.
 */
data class Peak(val mode: PeakMode, val radius: Double, val amount: Double) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("radius", radius).put("amount", amount)

    /** Radius and amount the print uses. Auto without a measurement applies nothing. */
    fun applied(measured: PeakMeasured?): Pair<Double, Double> =
        when (mode) {
            PeakMode.OFF -> radius to 0.0
            PeakMode.AUTO -> (measured?.radius ?: radius) to (measured?.amount ?: 0.0)
            PeakMode.CUSTOM -> radius to amount
        }

    companion object {
        /** The standing Peak of [mode], with the pair a Custom print starts from when nothing was measured. */
        fun standing(mode: PeakMode, limits: PeakLimits) = Peak(mode, limits.radiusDefault, 0.0)

        /** Peak from its object; a missing or unknown field falls back to Auto, the default radius, no amount. */
        fun fromJson(o: JSONObject, limits: PeakLimits): Peak =
            Peak(
                GradeMath.enumOr(o.takeIf { it.has("mode") }?.optString("mode"), PeakMode.AUTO),
                GradeMath.clampPeakRadius(o.optDouble("radius", limits.radiusDefault), limits),
                GradeMath.clampPeakAmount(o.optDouble("amount", 0.0), limits),
            )
    }
}

/**
 * What one frame is printed with: the grade and Peak, the recipe beside
 * the RAW. A new frame takes the standing pair of the moment; the
 * developer moves this one alone. Written to `<name>.grade` by
 * [Recipe] together with the frame's process and filter.
 */
data class Print(val grade: Grade, val peak: Peak)

/**
 * `<name>.grade` on disk: the grade's two exposures, `peak`, and the
 * process, filter, plate filters and plate primaries the frame was last
 * developed or re-mixed in (enum names, as the iOS app writes them).
 * Every field past `soft` and `hard` is optional, so an older file
 * reads: one from 0.15.0 has the grade alone and takes the standing
 * Peak; one from 0.17.9 or earlier has no process and reads as Mono,
 * Pan; one without `plateSpace` reads as sRGB.
 */
object Recipe {
    fun toJson(print: Print, settings: DevelopSettings): JSONObject =
        print.grade.toJson()
            .put("peak", print.peak.toJson())
            .put("process", settings.process.name)
            .put("filter", settings.filter.name)
            .put("plateFilters", settings.plateFilters.name)
            .put("plateSpace", settings.plateSpace.name)

    /** The print and the settings from the file's text; null when the text is not a recipe. */
    fun fromJson(text: String, limit: Double, limits: PeakLimits, standingPeak: Peak): Pair<Print, DevelopSettings>? = runCatching {
        val o = JSONObject(text)
        val grade = Grade.fromJson(o, limit) ?: return null
        val peak = o.optJSONObject("peak")?.let { Peak.fromJson(it, limits) } ?: standingPeak
        val settings = DevelopSettings(
            filter = GradeMath.enumOr(o.optString("filter"), Filter.NONE),
            process = GradeMath.enumOr(o.optString("process"), Process.MONO),
            plateFilters = GradeMath.enumOr(o.optString("plateFilters"), PlateFilters.CAMERA),
            plateSpace = GradeMath.enumOr(o.optString("plateSpace"), PlateSpace.SRGB),
        )
        Print(grade, peak) to settings
    }.getOrNull()
}

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
     * at [limit] so the gap stays.
     */
    fun shiftGrade(soft: Double, hard: Double, delta: Double, limit: Double): Grade {
        val lo = -limit - minOf(soft, hard)
        val hi = limit - maxOf(soft, hard)
        val d = (kotlin.math.round(finiteOrZero(delta) * 12.0) / 12.0).coerceIn(lo, hi)
        return Grade(soft + d, hard + d)
    }

    /**
     * Notches of grade a spread of two fingers by [scale] stands for, a
     * twelfth of a stop of hard light over soft each: spreading the ramp
     * is the softer paper, closing it the harder, one stop to the
     * doubling (half the earlier two-stop gain, so a full grade swing
     * may need two spreads). The step itself is the core's
     * ([Prefs.graded]). A scale that is no number is no spread.
     */
    fun spreadNotches(scale: Double): Int {
        val s = if (scale.isFinite()) scale.coerceIn(0.05, 20.0) else 1.0
        return Math.round(-12.0 * kotlin.math.log2(s)).toInt()
    }

    /** Linear value of the grey card: the core's `zone::GRAY_CARD`. */
    const val GREY_CARD: Double = 0.18

    /** Stops of the scene the paper strip shows under the grey card. */
    const val STRIP_BELOW: Double = 5.0

    /** Stops of the scene over the grey card: up to the clip, linear 1, where the negative ends. */
    val STRIP_ABOVE: Double = -kotlin.math.log2(GREY_CARD)

    /**
     * Where a grey [stops] from the grey card stands, as a part of the
     * strip's width. The card itself is at `stripFracOf(0.0)`, the mark.
     */
    fun stripFracOf(stops: Double): Double = (stops + STRIP_BELOW) / (STRIP_BELOW + STRIP_ABOVE)

    /**
     * Linear grey at [frac] of the strip's width: [STRIP_BELOW] stops
     * under the grey card at the left edge, the clip at the right.
     */
    fun stripLinearAt(frac: Double): Double =
        (GREY_CARD * Math.pow(2.0, frac * (STRIP_BELOW + STRIP_ABOVE) - STRIP_BELOW)).coerceIn(0.0, 1.0)

    /** The whole stops from the grey card that lie inside the strip, the edges left out: the ticks. */
    fun stripTicks(): List<Double> =
        generateSequence(1.0 - kotlin.math.ceil(STRIP_BELOW)) { it + 1.0 }.takeWhile { it < STRIP_ABOVE }.toList()

    /** A peak radius inside the core's range; a non-finite value is the default. */
    fun clampPeakRadius(v: Double, limits: PeakLimits): Double =
        (if (v.isFinite()) v else limits.radiusDefault).coerceIn(limits.radiusMin, limits.radiusMax)

    /** A peak amount inside the core's range; a non-finite value is zero. */
    fun clampPeakAmount(v: Double, limits: PeakLimits): Double = finiteOrZero(v).coerceIn(0.0, limits.amountMax)

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
     * The standing Peak mode the same way: the named mode, else the old
     * checkbox (on was the measurement, off was none), else Auto for a
     * fresh install. Custom is a frame's choice since 0.15.1, not a
     * standing one: a stored Custom reads as Auto.
     */
    fun peakModeOf(named: String?, peakOn: Boolean?): PeakMode {
        if (named != null) return enumOr(named, PeakMode.AUTO).takeIf { it != PeakMode.CUSTOM } ?: PeakMode.AUTO
        if (peakOn == null) return PeakMode.AUTO
        return if (peakOn) PeakMode.AUTO else PeakMode.OFF
    }

    /** A stored float that was never finite (a damaged preferences file) reads as zero. */
    fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

    inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
