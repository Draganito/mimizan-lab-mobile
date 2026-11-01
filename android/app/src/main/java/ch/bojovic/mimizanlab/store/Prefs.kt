package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.PeakMeasured
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.lampLimit
import ch.bojovic.mimizanlab.engine.paperStopLimit
import ch.bojovic.mimizanlab.engine.peakLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _soft = MutableStateFlow(snapStops(finiteOrZero(sp.getFloat(KEY_SOFT, 0f))))
    /** Soft-pass time in stops from the base exposure, for the viewfinder, the automatic JPEG and the start of review. */
    val soft: StateFlow<Double> = _soft.asStateFlow()

    fun setSoft(stops: Double) {
        val v = snapStops(stops)
        _soft.value = v
        sp.edit { putFloat(KEY_SOFT, v.toFloat()) }
    }

    private val _hard = MutableStateFlow(snapStops(finiteOrZero(sp.getFloat(KEY_HARD, 0f))))
    /** Hard-pass time in stops from the base exposure. */
    val hard: StateFlow<Double> = _hard.asStateFlow()

    fun setHard(stops: Double) {
        val v = snapStops(stops)
        _hard.value = v
        sp.edit { putFloat(KEY_HARD, v.toFloat()) }
    }

    private val _peakMode = MutableStateFlow(loadPeakMode())
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

    /** A bool from before the three states: on was the measurement, off was none. */
    private fun loadPeakMode(): PeakMode {
        val named = sp.getString(KEY_PEAK_MODE, null)
        if (named != null) return enumOr(named, PeakMode.AUTO)
        if (!sp.contains(KEY_PEAK)) return PeakMode.AUTO
        return if (sp.getBoolean(KEY_PEAK, true)) PeakMode.AUTO else PeakMode.OFF
    }

    private val _process = MutableStateFlow(enumOr(sp.getString(KEY_PROCESS, null), Process.MONO))
    /** The darkroom's process: the monochrome negative, or the three-colour plate. The next shot opens in it. */
    val process: StateFlow<Process> = _process.asStateFlow()

    fun setProcess(p: Process) {
        _process.value = p
        sp.edit { putString(KEY_PROCESS, p.name) }
    }

    private val _plateFilters = MutableStateFlow(enumOr(sp.getString(KEY_PLATE_FILTERS, null), PlateFilters.CAMERA))
    /** The plate's taking filters. */
    val plateFilters: StateFlow<PlateFilters> = _plateFilters.asStateFlow()

    fun setPlateFilters(f: PlateFilters) {
        _plateFilters.value = f
        sp.edit { putString(KEY_PLATE_FILTERS, f.name) }
    }

    private val _plateSpace = MutableStateFlow(enumOr(sp.getString(KEY_PLATE_SPACE, null), PlateSpace.SRGB))
    /** The plate's projection primaries, the colour space of the file. sRGB on the phone: the gallery shows it as it is. */
    val plateSpace: StateFlow<PlateSpace> = _plateSpace.asStateFlow()

    fun setPlateSpace(s: PlateSpace) {
        _plateSpace.value = s
        sp.edit { putString(KEY_PLATE_SPACE, s.name) }
    }

    private val _stayOnFinder = MutableStateFlow(sp.getBoolean(KEY_STAY_ON_FINDER, true))
    /**
     * On, a shot saves the JPEG at the standing grade and stays on the finder.
     * On for a fresh install: the viewfinder is already the paper.
     */
    val stayOnFinder: StateFlow<Boolean> = _stayOnFinder.asStateFlow()

    fun setStayOnFinder(on: Boolean) {
        _stayOnFinder.value = on
        sp.edit { putBoolean(KEY_STAY_ON_FINDER, on) }
    }

    private val _lampRed = MutableStateFlow(snapLamp(finiteOrZero(sp.getFloat(KEY_LAMP_RED, 0f))))
    /** The red lamp of the plate's projector, stops; the start value in review and the automatic JPEG's. */
    val lampRed: StateFlow<Double> = _lampRed.asStateFlow()

    fun setLampRed(stops: Double) {
        val v = snapLamp(stops)
        _lampRed.value = v
        sp.edit { putFloat(KEY_LAMP_RED, v.toFloat()) }
    }

    private val _lampBlue = MutableStateFlow(snapLamp(finiteOrZero(sp.getFloat(KEY_LAMP_BLUE, 0f))))
    /** The blue lamp. */
    val lampBlue: StateFlow<Double> = _lampBlue.asStateFlow()

    fun setLampBlue(stops: Double) {
        val v = snapLamp(stops)
        _lampBlue.value = v
        sp.edit { putFloat(KEY_LAMP_BLUE, v.toFloat()) }
    }

    companion object {
        /** How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`, asked once. */
        val STOP_LIMIT: Double = paperStopLimit()

        /** How far either lamp may move, in stops: the core's `print::LAMP_LIMIT`. */
        val LAMP_LIMIT: Double = lampLimit()

        /** Twelfths of a stop, inside ±[LAMP_LIMIT]. */
        fun snapLamp(v: Double): Double =
            (kotlin.math.round(finiteOrZero(v) * 12.0) / 12.0).coerceIn(-LAMP_LIMIT, LAMP_LIMIT)

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

        /** The range of the peak fields: the core's `recipe::PEAK_*`, asked once. */
        val PEAK: PeakLimits = peakLimits()

        /** A peak radius inside the core's range; a non-finite value is the default. */
        fun clampPeakRadius(v: Double): Double =
            (if (v.isFinite()) v else PEAK.radiusDefault).coerceIn(PEAK.radiusMin, PEAK.radiusMax)

        /** A peak amount inside the core's range; a non-finite value is zero. */
        fun clampPeakAmount(v: Double): Double = finiteOrZero(v).coerceIn(0.0, PEAK.amountMax)

        /** Radius and amount a print uses. Off applies nothing. Auto uses the measurement. */
        fun peakApplied(mode: PeakMode, radius: Double, amount: Double, measured: PeakMeasured?): Pair<Double, Double> =
            when (mode) {
                PeakMode.OFF -> radius to 0.0
                PeakMode.AUTO -> (measured?.radius ?: PEAK.radiusDefault) to (measured?.amount ?: 0.0)
                PeakMode.CUSTOM -> radius to amount
            }

        /** Twelfths of a stop, inside ±[STOP_LIMIT]; a non-finite value snaps to zero. */
        fun snapStops(v: Double): Double =
            (kotlin.math.round(finiteOrZero(v) * 12.0) / 12.0).coerceIn(-STOP_LIMIT, STOP_LIMIT)

        /** A stored float that was never finite (a damaged preferences file) reads as zero. */
        private fun finiteOrZero(v: Float): Double = finiteOrZero(v.toDouble())
        private fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_PEAK = "peak_on"
        private const val KEY_PEAK_MODE = "peak_mode"
        private const val KEY_PEAK_RADIUS = "peak_radius"
        private const val KEY_PEAK_AMOUNT = "peak_amount"
        private const val KEY_PROCESS = "process"
        private const val KEY_PLATE_FILTERS = "plate_filters"
        private const val KEY_PLATE_SPACE = "plate_space"
        private const val KEY_LAMP_RED = "lamp_red"
        private const val KEY_LAMP_BLUE = "lamp_blue"
        private const val KEY_STAY_ON_FINDER = "stay_on_finder"
    }
}
