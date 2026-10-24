package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import ch.bojovic.mimizanlab.engine.paperStopLimit
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

    private val _sharpen = MutableStateFlow(finiteOrZero(sp.getFloat(KEY_SHARPEN, 0f)).coerceIn(0.0, SHARPEN_LIMIT))
    /** Unsharp-mask amount (0..1.5) for the automatic JPEG and full-size exports. */
    val sharpen: StateFlow<Double> = _sharpen.asStateFlow()

    fun setSharpen(amount: Double) {
        val v = finiteOrZero(amount).coerceIn(0.0, SHARPEN_LIMIT)
        _sharpen.value = v
        sp.edit { putFloat(KEY_SHARPEN, v.toFloat()) }
    }

    private val _deconvolution = MutableStateFlow(sp.getBoolean(KEY_DECONV, false))
    /** Richardson–Lucy on the whole picture instead of USM. */
    val deconvolution: StateFlow<Boolean> = _deconvolution.asStateFlow()

    fun setDeconvolution(on: Boolean) {
        _deconvolution.value = on
        sp.edit { putBoolean(KEY_DECONV, on) }
    }

    private val _deconvPasses = MutableStateFlow(sp.getInt(KEY_DECONV_N, 2).coerceIn(1, DECONV_PASSES_MAX))
    /** Richardson–Lucy passes (1..[DECONV_PASSES_MAX]). A fresh install starts at 2. */
    val deconvPasses: StateFlow<Int> = _deconvPasses.asStateFlow()

    fun setDeconvPasses(n: Int) {
        val v = n.coerceIn(1, DECONV_PASSES_MAX)
        _deconvPasses.value = v
        sp.edit { putInt(KEY_DECONV_N, v) }
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

    companion object {
        /** How far either paper exposure may move, in stops: the core's `paper::STOP_LIMIT`, asked once. */
        val STOP_LIMIT: Double = paperStopLimit()

        /** Unsharp-mask ceiling, the same the core clamps to. */
        const val SHARPEN_LIMIT = 1.5

        /** Richardson–Lucy ceiling, the same the core clamps to. */
        const val DECONV_PASSES_MAX = 10

        /** Twelfths of a stop, inside ±[STOP_LIMIT]; a non-finite value snaps to zero. */
        fun snapStops(v: Double): Double =
            (kotlin.math.round(finiteOrZero(v) * 12.0) / 12.0).coerceIn(-STOP_LIMIT, STOP_LIMIT)

        /** A stored float that was never finite (a damaged preferences file) reads as zero. */
        private fun finiteOrZero(v: Float): Double = finiteOrZero(v.toDouble())
        private fun finiteOrZero(v: Double): Double = if (v.isFinite()) v else 0.0

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_SHARPEN = "usm_amount"
        private const val KEY_DECONV = "deconvolution"
        private const val KEY_DECONV_N = "deconv_passes"
        private const val KEY_COMPENSATION = "compensation_thirds"
        /** ±3 EV in thirds. */
        const val COMP_LIMIT_THIRDS = 9
    }
}
