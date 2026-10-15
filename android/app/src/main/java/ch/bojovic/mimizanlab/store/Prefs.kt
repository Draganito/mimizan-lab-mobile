package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The few settings that survive a restart. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mimizan", Context.MODE_PRIVATE)

    private val _soft = MutableStateFlow(snapStops(sp.getFloat(KEY_SOFT, 0f).toDouble()))
    /** Soft-pass time in stops from the base exposure, for the viewfinder, the automatic JPEG and the start of review. */
    val soft: StateFlow<Double> = _soft.asStateFlow()

    fun setSoft(stops: Double) {
        val v = snapStops(stops)
        _soft.value = v
        sp.edit { putFloat(KEY_SOFT, v.toFloat()) }
    }

    private val _hard = MutableStateFlow(snapStops(sp.getFloat(KEY_HARD, 0f).toDouble()))
    /** Hard-pass time in stops from the base exposure. */
    val hard: StateFlow<Double> = _hard.asStateFlow()

    fun setHard(stops: Double) {
        val v = snapStops(stops)
        _hard.value = v
        sp.edit { putFloat(KEY_HARD, v.toFloat()) }
    }

    private val _sharpen = MutableStateFlow(sp.getFloat(KEY_SHARPEN, 0f).toDouble())
    /** Unsharp-mask amount (0..1.5) for the automatic JPEG and full-size exports. */
    val sharpen: StateFlow<Double> = _sharpen.asStateFlow()

    fun setSharpen(amount: Double) {
        val v = amount.coerceIn(0.0, 1.5)
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

    private val _deconvPasses = MutableStateFlow(sp.getInt(KEY_DECONV_N, 2).coerceIn(1, 10))
    /** Richardson–Lucy passes (1..10). A fresh install starts at 2. */
    val deconvPasses: StateFlow<Int> = _deconvPasses.asStateFlow()

    fun setDeconvPasses(n: Int) {
        val v = n.coerceIn(1, 10)
        _deconvPasses.value = v
        sp.edit { putInt(KEY_DECONV_N, v) }
    }

    companion object {
        /** Same limit as the desktop enlarger (`paper::STOP_LIMIT`). */
        const val STOP_LIMIT = 3.0

        /** Twelfths of a stop, inside ±[STOP_LIMIT]. */
        fun snapStops(v: Double): Double =
            (kotlin.math.round(v * 12.0) / 12.0).coerceIn(-STOP_LIMIT, STOP_LIMIT)

        private const val KEY_SOFT = "paper_soft"
        private const val KEY_HARD = "paper_hard"
        private const val KEY_SHARPEN = "usm_amount"
        private const val KEY_DECONV = "deconvolution"
        private const val KEY_DECONV_N = "deconv_passes"
    }
}
