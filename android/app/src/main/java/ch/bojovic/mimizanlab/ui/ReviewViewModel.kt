package ch.bojovic.mimizanlab.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.bojovic.mimizanlab.engine.Darkroom
import ch.bojovic.mimizanlab.engine.DevelopInfo
import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.engine.Developed
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.GrayImage
import ch.bojovic.mimizanlab.engine.Look
import ch.bojovic.mimizanlab.engine.WeightSpace
import ch.bojovic.mimizanlab.engine.Weights
import ch.bojovic.mimizanlab.engine.lookNeutral
import ch.bojovic.mimizanlab.engine.lookReference
import ch.bojovic.mimizanlab.engine.lookWithContrast
import ch.bojovic.mimizanlab.engine.normaliseWeights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A 1:1 crop of the negative and where it sits in the upright picture. */
data class Detail(val x: Int, val y: Int, val bitmap: Bitmap)

data class ReviewState(
    val name: String = "",
    val info: DevelopInfo? = null,
    /** Balanced-space weights the sliders show (before the filter). */
    val weights: Weights = Weights(0.25, 0.5, 0.25),
    /** What the mix actually uses: [weights] with the filter applied. */
    val effective: Weights = Weights(0.25, 0.5, 0.25),
    val filter: Filter = Filter.NONE,
    val reference: Boolean = true,
    /** S-curve on top of the look, -1..1 (0 = the look as it is). */
    val contrast: Double = 0.0,
    val preview: Bitmap? = null,
    val histogram: FloatArray = FloatArray(0),
    val detail: Detail? = null,
    val busy: Boolean = false,
    val exporting: String? = null,
    val lastExport: Uri? = null,
    val message: String? = null,
    /** The negative is not in memory: offer to develop it (again). */
    val needsDevelop: Boolean = false,
)

class ReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val darkroom = Darkroom.get(app)
    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state.asStateFlow()

    private var developed: Developed? = null
    private val renderLock = Mutex()
    private var renderJob: Job? = null
    private var detailJob: Job? = null
    private var openJob: Job? = null
    /** Last 1:1 window asked for, so a slider change can refresh it. */
    private var detailRegion: IntArray? = null

    /** Base look plus the contrast of this review. */
    private val look: Look
        get() {
            val s = _state.value
            val base = if (s.reference) lookReference() else lookNeutral()
            return if (s.contrast == 0.0) base else lookWithContrast(base, s.contrast)
        }

    /** Bind to a shot; follows [Darkroom.current] so a (re)development shows up. */
    fun open(name: String) {
        if (_state.value.name == name && openJob?.isActive == true) return
        openJob?.cancel()
        detailRegion = null
        _state.value = ReviewState(name = name, contrast = darkroom.prefs.contrast.value)
        openJob = viewModelScope.launch {
            darkroom.current.collect { neg ->
                if (neg != null && neg.name == name) {
                    developed = neg.developed
                    // info.weights already include the filter of the first
                    // mix; the sliders start from the camera's base weights.
                    _state.update {
                        it.copy(
                            info = neg.info,
                            weights = neg.info.defaultWeights,
                            effective = neg.info.weights,
                            filter = neg.info.filter,
                            needsDevelop = false,
                            detail = null,
                        )
                    }
                    render()
                } else {
                    developed = null
                    _state.update { it.copy(needsDevelop = true, preview = null, detail = null) }
                }
            }
        }
    }

    fun develop(settings: DevelopSettings = DevelopSettings()) {
        val shot = darkroom.shots.value.firstOrNull { it.name == _state.value.name } ?: return
        darkroom.redevelop(shot.name, settings)
    }

    fun shotSettings(): DevelopSettings? = darkroom.shots.value.firstOrNull { it.name == _state.value.name }?.settings

    fun setFilter(f: Filter) {
        _state.update { it.copy(filter = f) }
        remixAndRender()
    }

    /** Sliders give R and B; G takes the rest so the sum stays 1. */
    fun setWeightsRB(r: Double, b: Double) {
        val rr = r.coerceIn(0.0, 1.0)
        val bb = b.coerceIn(0.0, 1.0 - rr)
        _state.update { it.copy(weights = Weights(rr, 1.0 - rr - bb, bb)) }
        remixAndRender()
    }

    fun resetWeights() {
        val d = _state.value.info?.defaultWeights ?: return
        _state.update { it.copy(weights = d, filter = Filter.NONE) }
        remixAndRender()
    }

    fun setReference(on: Boolean) {
        _state.update { it.copy(reference = on) }
        render()
    }

    fun setContrast(c: Double) {
        _state.update { it.copy(contrast = c.coerceIn(-1.0, 1.0)) }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(60)
            render()
        }
    }

    private fun remixAndRender() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(60) // coalesce slider drags
            render(remix = true)
        }
    }

    private fun render(remix: Boolean = false) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val d = developed ?: return@launch
            renderLock.withLock {
                try {
                    _state.update { it.copy(busy = true) }
                    val s = _state.value
                    if (remix) {
                        val w = d.remix(normaliseWeights(s.weights), WeightSpace.BALANCED, s.filter)
                        _state.update { it.copy(effective = w) }
                    }
                    val g = d.preview(look, 0u)
                    val h = d.histogram(look)
                    val max = (h.maxOrNull() ?: 1uL).toDouble().coerceAtLeast(1.0)
                    val hist = FloatArray(h.size) { (h[it].toDouble() / max).toFloat() }
                    _state.update { it.copy(preview = toBitmap(g), histogram = hist, detail = null, busy = false, info = d.info()) }
                    // A zoomed-in view has to follow the new tones.
                    detailRegion?.let { r -> requestDetail(r[0], r[1], r[2], r[3]) }
                } catch (t: Throwable) {
                    Log.e("Review", "render", t)
                    _state.update { it.copy(busy = false, message = t.message) }
                }
            }
        }
    }

    /**
     * Ask for a 1:1 window (upright full-resolution coordinates). Debounced;
     * the UI calls this whenever the visible region changes at high zoom.
     */
    fun requestDetail(x: Int, y: Int, w: Int, h: Int) {
        detailRegion = intArrayOf(x, y, w, h)
        detailJob?.cancel()
        detailJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            val d = developed ?: return@launch
            renderLock.withLock {
                try {
                    val g = d.detail(x.toUInt(), y.toUInt(), w.toUInt(), h.toUInt(), look, 0u, 0.0)
                    _state.update { it.copy(detail = Detail(x, y, toBitmap(g))) }
                } catch (t: Throwable) {
                    Log.w("Review", "detail", t)
                }
            }
        }
    }

    fun clearDetail() {
        detailRegion = null
        detailJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    fun exportJpeg(fullSize: Boolean) = export(if (fullSize) "JPEG" else "JPEG 2048") { d, name, f ->
        darkroom.exportJpeg(d, name, f, fullSize, look, darkroom.prefs.sharpen.value)
    }

    fun exportTiff() = export("TIFF 16-bit") { d, name, f ->
        darkroom.exportTiff(d, name, f, look, darkroom.prefs.sharpen.value)
    }

    fun exportNegative() = export("negative") { d, name, _ -> darkroom.exportNegative(d, name).first() }

    private fun export(label: String, f: (Developed, String, Filter) -> Uri) {
        val d = developed ?: return
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            renderLock.withLock {
                _state.update { it.copy(exporting = label) }
                try {
                    val uri = f(d, s.name, s.filter)
                    _state.update { it.copy(exporting = null, lastExport = uri, message = "Saved $label to Pictures/Mimizan Lab") }
                } catch (t: Throwable) {
                    Log.e("Review", "export", t)
                    _state.update { it.copy(exporting = null, message = t.message ?: "Export failed") }
                }
            }
        }
    }

    fun shareIntent(): Intent? {
        val uri = _state.value.lastExport
            ?: darkroom.shots.value.firstOrNull { it.name == _state.value.name }?.jpegUri
            ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = getApplication<Application>().contentResolver.getType(uri) ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** Gray8 -> ARGB bitmap (Compose cannot draw ALPHA_8 as luminance). */
        suspend fun toBitmap(g: GrayImage): Bitmap = withContext(Dispatchers.Default) {
            val w = g.width.toInt()
            val h = g.height.toInt()
            val px = IntArray(w * h)
            val src = g.data
            for (i in px.indices) {
                val v = src[i].toInt() and 0xFF
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
