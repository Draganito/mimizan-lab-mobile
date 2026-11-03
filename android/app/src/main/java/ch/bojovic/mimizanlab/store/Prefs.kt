package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.PeakMeasured
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.paperStopLimit
import ch.bojovic.mimizanlab.engine.peakLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a shot does. JPEG writes the picture and stays. Developer opens it. Raw keeps the frame. */
enum class ShotMode {
    JPEG, DEVELOPER, RAW;

    val label: String
        get() = when (this) {
            JPEG -> "JPEG"
            DEVELOPER -> "Developer"
            RAW -> "Raw"
        }
}

/** Peak on a print: off, the measurement of this negative, or a hand pair. */
enum class PeakMode {
    OFF, AUTO, CUSTOM;

    val label: String
        get() = when (this) {
            OFF -> "Off"
            AUTO -> "Auto"
            CUSTOM -> "Custom"
        }
}

/** The few settings that survive a restart. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mimizan", Context.MODE_PRIVATE)

    private val _grade = MutableStateFlow(
        Grade(snapStops(finiteOrZero(sp.getFloat(KEY_SOFT, 0f))), snapStops(finiteOrZero(sp.getFloat(KEY_HARD, 0f)))),
    )
    /**
     * Both paper exposures as one value, so a move of the zone strip is
     * one emission for the viewfinder; [soft] and [hard] are the same
     * numbers one by one for the sliders.
     */
    val grade: StateFlow<Grade> = _grade.asStateFlow()

    private val _soft = MutableStateFlow(_grade.value.soft)
    /** Soft-pass time in stops from the base exposure, for the viewfinder, the automatic JPEG and the start of review. */
    val soft: StateFlow<Double> = _soft.asStateFlow()

    private val _hard = MutableStateFlow(_grade.value.hard)
    /** Hard-pass time in stops from the base exposure. */
    val hard: StateFlow<Double> = _hard.asStateFlow()

    fun setSoft(stops: Double) = setGrade(stops, _grade.value.hard)

    fun setHard(stops: Double) = setGrade(_grade.value.soft, stops)

    /** Both exposures at once: one write, one emission of [grade]. */
    fun setGrade(soft: Double, hard: Double) {
        val g = Grade(snapStops(soft), snapStops(hard))
        _grade.value = g
        _soft.value = g.soft
        _hard.value = g.hard
        sp.edit {
            putFloat(KEY_SOFT, g.soft.toFloat())
            putFloat(KEY_HARD, g.hard.toFloat())
        }
    }

    private val _peakMode = MutableStateFlow(
        GradeMath.peakModeOf(sp.getString(KEY_PEAK_MODE, null), if (sp.contains(KEY_PEAK)) sp.getBoolean(KEY_PEAK, true) else null),
    )
    /**
     * Peak's high-pass on the automatic JPEG and the full-size exports.
     * Auto measures each negative. Custom keeps a hand pair. Auto on a fresh install.
     */
    val peakMode: StateFlow<PeakMode> = _peakMode.asStateFlow()

    fun setPeakMode(mode: PeakMode) {
        _peakMode.value = mode
        sp.edit { putString(KEY_PEAK_MODE, mode.name) }
    }

    private val _peakRadius = MutableStateFlow(clampPeakRadius(sp.getFloat(KEY_PEAK_RADIUS, PEAK.radiusDefault.toFloat()).toDouble()))
    /** Custom Peak radius, pixels of the negative. */
    val peakRadius: StateFlow<Double> = _peakRadius.asStateFlow()

    private val _peakAmount = MutableStateFlow(clampPeakAmount(sp.getFloat(KEY_PEAK_AMOUNT, 0f).toDouble()))
    /** Custom Peak amount. */
    val peakAmount: StateFlow<Double> = _peakAmount.asStateFlow()

    fun setPeak(radius: Double, amount: Double) {
        val r = clampPeakRadius(radius)
        val a = clampPeakAmount(amount)
        _peakRadius.value = r
        _peakAmount.value = a
        sp.edit {
            putFloat(KEY_PEAK_RADIUS, r.toFloat())
            putFloat(KEY_PEAK_AMOUNT, a.toFloat())
        }
    }

    private val _process = MutableStateFlow(GradeMath.enumOr(sp.getString(KEY_PROCESS, null), Process.MONO))
    /** The darkroom's process: the monochrome negative, or the three-colour plate. The next shot opens in it. */
    val process: StateFlow<Process> = _process.asStateFlow()

    fun setProcess(p: Process) {
        _process.value = p
        sp.edit { putString(KEY_PROCESS, p.name) }
    }

    private val _plateFilters = MutableStateFlow(GradeMath.enumOr(sp.getString(KEY_PLATE_FILTERS, null), PlateFilters.CAMERA))
    /** The plate's taking filters. */
    val plateFilters: StateFlow<PlateFilters> = _plateFilters.asStateFlow()

    fun setPlateFilters(f: PlateFilters) {
        _plateFilters.value = f
        sp.edit { putString(KEY_PLATE_FILTERS, f.name) }
    }

    private val _plateSpace = MutableStateFlow(GradeMath.enumOr(sp.getString(KEY_PLATE_SPACE, null), PlateSpace.SRGB))
    /** The plate's projection primaries, the colour space of the file. sRGB on the phone: the gallery shows it as it is. */
    val plateSpace: StateFlow<PlateSpace> = _plateSpace.asStateFlow()

    fun setPlateSpace(s: PlateSpace) {
        _plateSpace.value = s
        sp.edit { putString(KEY_PLATE_SPACE, s.name) }
    }

    private val _shotMode = MutableStateFlow(
        GradeMath.shotModeOf(
            sp.getString(KEY_SHOT_MODE, null),
            if (sp.contains(KEY_STAY_ON_FINDER)) sp.getBoolean(KEY_STAY_ON_FINDER, true) else null,
        ),
    )
    /**
     * What the shutter does. JPEG on a fresh install: the viewfinder is
     * already the paper, so the picture is written and the finder stays.
     */
    val shotMode: StateFlow<ShotMode> = _shotMode.asStateFlow()

    fun setShotMode(mode: ShotMode) {
        _shotMode.value = mode
        sp.edit { putString(KEY_SHOT_MODE, mode.name) }
    }

    private val _zebra = MutableStateFlow(sp.getBoolean(KEY_ZEBRA, true))
    /**
     * Overexposure marks on the finder. On for a fresh install: the
     * finder is the paper, and the marks are the one sign of lost
     * highlights before the shot.
     */
    val zebra: StateFlow<Boolean> = _zebra.asStateFlow()

    fun setZebra(on: Boolean) {
        _zebra.value = on
        sp.edit { putBoolean(KEY_ZEBRA, on) }
    }

    private val _grid = MutableStateFlow(sp.getBoolean(KEY_GRID, false))
    /** The rule of thirds on the finder. Off for a fresh install. */
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    fun setGrid(on: Boolean) {
        _grid.value = on
        sp.edit { putBoolean(KEY_GRID, on) }
    }

    private val _histogram = MutableStateFlow(sp.getBoolean(KEY_HISTOGRAM, false))
    /** The finder's histogram. Off for a fresh install. */
    val histogram: StateFlow<Boolean> = _histogram.asStateFlow()

    fun setHistogram(on: Boolean) {
        _histogram.value = on
        sp.edit { putBoolean(KEY_HISTOGRAM, on) }
    }

    private val _zoneAuto = MutableStateFlow(sp.getBoolean(KEY_ZONE_AUTO, false))
    /**
     * On, a picture places its middle on the grey card. The stored grade
     * stays. Off for a fresh install.
     */
    val zoneAuto: StateFlow<Boolean> = _zoneAuto.asStateFlow()

    fun setZoneAuto(on: Boolean) {
        _zoneAuto.value = on
        sp.edit { putBoolean(KEY_ZONE_AUTO, on) }
    }

    private val _zoneBias = MutableStateFlow(snapZoneBias(finiteOrZero(sp.getFloat(KEY_ZONE_BIAS, 0f))))
    /**
     * Extra paper time, in stops, added to a balanced picture. Plus prints
     * darker. The grade stays. Zero for a fresh install.
     */
    val zoneBias: StateFlow<Double> = _zoneBias.asStateFlow()

    fun setZoneBias(stops: Double) {
        val v = snapZoneBias(stops)
        _zoneBias.value = v
        sp.edit { putFloat(KEY_ZONE_BIAS, v.toFloat()) }
    }

    /**
     * Whether the one-time note after the first JPEG shot (the frame stays
     * on the roll and can be printed again) has been shown. Not a flow:
     * read once, at the shot.
     */
    val rollHintShown: Boolean get() = sp.getBoolean(KEY_ROLL_HINT, false)

    fun markRollHintShown() = sp.edit { putBoolean(KEY_ROLL_HINT, true) }

    companion object {
        /** How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`, asked once. */
        val STOP_LIMIT: Double = paperStopLimit()

        /** Extra paper time a balanced picture may take, in stops. The grade stays inside [STOP_LIMIT]. */
        const val ZONE_BIAS_LIMIT: Double = 2.0

        /** The range of the peak fields: the core's `recipe::PEAK_*`, asked once. */
        val PEAK: PeakLimits = peakLimits()

        /** A peak radius inside the core's range; a non-finite value is the default. */
        fun clampPeakRadius(v: Double): Double =
            (if (v.isFinite()) v else PEAK.radiusDefault).coerceIn(PEAK.radiusMin, PEAK.radiusMax)

        /** A peak amount inside the core's range; a non-finite value is zero. */
        fun clampPeakAmount(v: Double): Double = GradeMath.finiteOrZero(v).coerceIn(0.0, PEAK.amountMax)

        /** Radius and amount a print uses. Off applies nothing. Auto uses the measurement. */
        fun peakApplied(mode: PeakMode, radius: Double, amount: Double, measured: PeakMeasured?): Pair<Double, Double> =
            GradeMath.peakApplied(mode, radius, amount, measured, PEAK.radiusDefault)

        /** Add [delta] stops to both exposures, snapped to a twelfth, and stop at the limit so the gap stays. */
        fun shiftGrade(soft: Double, hard: Double, delta: Double): Pair<Double, Double> =
            GradeMath.shiftGrade(soft, hard, delta, STOP_LIMIT).grade.let { it.soft to it.hard }

        /**
         * The stored grade with the balance on it: this negative's shift
         * (`Developed.zoneShift(0, 0)`, the stops before the room is
         * considered) plus the compensation, through [shiftGrade]. The same
         * call places the automatic JPEG, the thumbnail and the review.
         */
        fun place(stored: Grade, shift: Double, bias: Double): Placed = GradeMath.place(stored, shift, bias, STOP_LIMIT)

        /** Twelfths of a stop, inside ±[STOP_LIMIT]; a non-finite value snaps to zero. */
        fun snapStops(v: Double): Double = GradeMath.snapStops(v, STOP_LIMIT)

        /** Twelfths of a stop, inside ±[ZONE_BIAS_LIMIT]. */
        fun snapZoneBias(v: Double): Double = GradeMath.snapStops(v, ZONE_BIAS_LIMIT)

        private fun finiteOrZero(v: Float): Double = GradeMath.finiteOrZero(v.toDouble())

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_PEAK = "peak_on"
        private const val KEY_PEAK_MODE = "peak_mode"
        private const val KEY_PEAK_RADIUS = "peak_radius"
        private const val KEY_PEAK_AMOUNT = "peak_amount"
        private const val KEY_PROCESS = "process"
        private const val KEY_PLATE_FILTERS = "plate_filters"
        private const val KEY_PLATE_SPACE = "plate_space"
        private const val KEY_STAY_ON_FINDER = "stay_on_finder"
        private const val KEY_SHOT_MODE = "shot_mode"
        private const val KEY_ZEBRA = "finder_zebra"
        private const val KEY_GRID = "finder_grid"
        private const val KEY_HISTOGRAM = "finder_histogram"
        private const val KEY_ZONE_AUTO = "zone_auto"
        private const val KEY_ZONE_BIAS = "zone_bias"
        private const val KEY_ROLL_HINT = "hint_roll_shown"
    }
}
