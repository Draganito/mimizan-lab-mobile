package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.Filter
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

    companion object {
        /** The modes a standing Peak can be: a hand pair belongs to one negative, so Custom is the developer's alone. */
        val STANDING = listOf(OFF, AUTO)
    }
}

/** The few settings that survive a restart. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mimizan", Context.MODE_PRIVATE)

    private val _grade = MutableStateFlow(
        Grade(snapStops(finiteOrZero(sp.getFloat(KEY_SOFT, 0f))), snapStops(finiteOrZero(sp.getFloat(KEY_HARD, 0f)))),
    )
    /**
     * The standing grade: what the finder shows, what the automatic JPEG
     * prints, and where a new frame starts. A frame's own grade, moved in
     * the developer, lives with the frame ([RawCache]), not here. One
     * value, so a move of the zone strip is one emission for the
     * viewfinder and the sliders alike.
     */
    val grade: StateFlow<Grade> = _grade.asStateFlow()

    /** Both exposures at once: one write, one emission of [grade]. */
    fun setGrade(soft: Double, hard: Double) {
        val g = snapGrade(soft, hard)
        _grade.value = g
        sp.edit {
            putFloat(KEY_SOFT, g.soft.toFloat())
            putFloat(KEY_HARD, g.hard.toFloat())
        }
    }

    private val _peakMode = MutableStateFlow(
        GradeMath.peakModeOf(sp.getString(KEY_PEAK_MODE, null), if (sp.contains(KEY_PEAK)) sp.getBoolean(KEY_PEAK, true) else null),
    )
    /**
     * The standing Peak, Off or Auto: what a new frame starts with and
     * the automatic JPEG applies. Auto measures each negative. A hand
     * pair is set in the developer and lives with the frame. Auto on a
     * fresh install.
     */
    val peakMode: StateFlow<PeakMode> = _peakMode.asStateFlow()

    fun setPeakMode(mode: PeakMode) {
        require(mode in PeakMode.STANDING) { "a standing Peak is Off or Auto" }
        _peakMode.value = mode
        sp.edit { putString(KEY_PEAK_MODE, mode.name) }
    }

    /** What a frame taken now is printed with: the standing grade and the standing Peak. */
    val standingPrint: Print get() = Print(_grade.value, standingPeak)

    /** The standing Peak as a print carries it. */
    val standingPeak: Peak get() = Peak.standing(_peakMode.value, PEAK)

    private val _process = MutableStateFlow(GradeMath.enumOr(sp.getString(KEY_PROCESS, null), Process.MONO))
    /** The darkroom's process: the monochrome negative, or the three-colour plate. The next shot opens in it. */
    val process: StateFlow<Process> = _process.asStateFlow()

    fun setProcess(p: Process) {
        _process.value = p
        sp.edit { putString(KEY_PROCESS, p.name) }
    }

    private val _filter = MutableStateFlow(GradeMath.enumOr(sp.getString(KEY_FILTER, null), Filter.NONE))
    /** The monochrome taking filter in front of the lens: the finder shows it, the next shot is mixed with it, the developer may change it. */
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    fun setFilter(f: Filter) {
        _filter.value = f
        sp.edit { putString(KEY_FILTER, f.name) }
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

        /** The range of the peak fields: the core's `recipe::PEAK_*`, asked once. */
        val PEAK: PeakLimits = peakLimits()

        /** A hand pair inside the core's ranges. */
        fun customPeak(radius: Double, amount: Double): Peak =
            Peak(PeakMode.CUSTOM, GradeMath.clampPeakRadius(radius, PEAK), GradeMath.clampPeakAmount(amount, PEAK))

        /** Add [delta] stops to both exposures, snapped to a twelfth, and stop at the limit so the gap stays. */
        fun shiftGrade(soft: Double, hard: Double, delta: Double): Grade = GradeMath.shiftGrade(soft, hard, delta, STOP_LIMIT)

        /** The pair with [contrast] stops between hard and soft, the exposure kept, inside ±[STOP_LIMIT]. */
        fun withContrast(soft: Double, hard: Double, contrast: Double): Grade = GradeMath.withContrast(soft, hard, contrast, STOP_LIMIT)

        /** Twelfths of a stop, inside ±[STOP_LIMIT]; a non-finite value snaps to zero. */
        fun snapStops(v: Double): Double = GradeMath.snapStops(v, STOP_LIMIT)

        /** A grade, each exposure snapped and inside ±[STOP_LIMIT]. */
        fun snapGrade(soft: Double, hard: Double): Grade = Grade(snapStops(soft), snapStops(hard))

        private fun finiteOrZero(v: Float): Double = GradeMath.finiteOrZero(v.toDouble())

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_PEAK = "peak_on"
        private const val KEY_PEAK_MODE = "peak_mode"
        private const val KEY_PROCESS = "process"
        private const val KEY_FILTER = "filter"
        private const val KEY_PLATE_FILTERS = "plate_filters"
        private const val KEY_PLATE_SPACE = "plate_space"
        private const val KEY_STAY_ON_FINDER = "stay_on_finder"
        private const val KEY_SHOT_MODE = "shot_mode"
        private const val KEY_ZEBRA = "finder_zebra"
        private const val KEY_GRID = "finder_grid"
        private const val KEY_HISTOGRAM = "finder_histogram"
        private const val KEY_ROLL_HINT = "hint_roll_shown"
    }
}
