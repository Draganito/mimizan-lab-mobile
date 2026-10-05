package ch.bojovic.mimizanlab.store

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The few settings that survive a restart. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mimizan", Context.MODE_PRIVATE)

    private val _contrast = MutableStateFlow(sp.getFloat(KEY_CONTRAST, 0f).toDouble())
    /** Default contrast (-1..1) for new reviews and the automatic JPEG. */
    val contrast: StateFlow<Double> = _contrast.asStateFlow()

    fun setContrast(c: Double) {
        val v = c.coerceIn(-1.0, 1.0)
        _contrast.value = v
        sp.edit { putFloat(KEY_CONTRAST, v.toFloat()) }
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

    private val _referenceLook = MutableStateFlow(sp.getBoolean(KEY_REFERENCE, true))
    /** Reference print curve for the automatic JPEG and the start of review. Off is gamma 2.2. */
    val referenceLook: StateFlow<Boolean> = _referenceLook.asStateFlow()

    fun setReferenceLook(on: Boolean) {
        _referenceLook.value = on
        sp.edit { putBoolean(KEY_REFERENCE, on) }
    }

    private companion object {
        const val KEY_CONTRAST = "contrast_default"
        const val KEY_SHARPEN = "usm_amount"
        const val KEY_DECONV = "deconvolution"
        const val KEY_DECONV_N = "deconv_passes"
        const val KEY_REFERENCE = "reference_look"
    }
}
