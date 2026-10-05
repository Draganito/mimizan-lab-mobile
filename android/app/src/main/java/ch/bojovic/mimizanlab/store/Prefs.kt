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

    private companion object {
        const val KEY_CONTRAST = "contrast_default"
        const val KEY_SHARPEN = "usm_amount"
    }
}
