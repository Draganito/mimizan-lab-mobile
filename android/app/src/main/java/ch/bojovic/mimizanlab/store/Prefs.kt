package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import ch.bojovic.mimizanlab.engine.PeakLimits
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.lampLimit
import ch.bojovic.mimizanlab.engine.paperStopLimit
import ch.bojovic.mimizanlab.engine.peakLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _peak = MutableStateFlow(sp.getBoolean(KEY_PEAK, true))
    /**
     * Peak's high-pass on the automatic JPEG and the full-size exports: the
     * radius and the amount are measured on each negative (the desktop's
     * `peak` program); this is only the checkbox. On for a fresh install.
     */
    val peak: StateFlow<Boolean> = _peak.asStateFlow()

    fun setPeak(on: Boolean) {
        _peak.value = on
        sp.edit { putBoolean(KEY_PEAK, on) }
    }

    private val _compensation = MutableStateFlow(sp.getInt(KEY_COMPENSATION, 0).coerceIn(-COMP_LIMIT_THIRDS, COMP_LIMIT_THIRDS))
    /**
     * Standing exposure compensation on A, in thirds of a stop (±3 EV),
     * added to the phone's AE compensation. The EV slider on the viewfinder
     * comes on top of it.
     */
    val compensationThirds: StateFlow<Int> = _compensation.asStateFlow()

    fun setCompensationThirds(thirds: Int) {
        val v = thirds.coerceIn(-COMP_LIMIT_THIRDS, COMP_LIMIT_THIRDS)
        _compensation.value = v
        sp.edit { putInt(KEY_COMPENSATION, v) }
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

        /** Twelfths of a stop, inside ±[STOP_LIMIT]; a non-finite value snaps to zero. */
        fun snapStops(v: Double): Double =
            (kotlin.math.round(finiteOrZero(v) * 12.0) / 12.0).coerceIn(-STOP_LIMIT, STOP_LIMIT)

        /** A stored float that was never finite (a damaged preferences file) reads as zero. */
        private fun finiteOrZero(v: Float): Double = finiteOrZero(v.toDouble())
        private fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_PEAK = "peak_on"
        private const val KEY_COMPENSATION = "compensation_thirds"
        private const val KEY_PROCESS = "process"
        private const val KEY_PLATE_FILTERS = "plate_filters"
        private const val KEY_PLATE_SPACE = "plate_space"
        private const val KEY_LAMP_RED = "lamp_red"
        private const val KEY_LAMP_BLUE = "lamp_blue"
        /** ±3 EV in thirds. */
        const val COMP_LIMIT_THIRDS = 9
    }
}
