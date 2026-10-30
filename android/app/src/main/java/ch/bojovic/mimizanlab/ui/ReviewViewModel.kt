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
import ch.bojovic.mimizanlab.engine.PeakMeasured
import ch.bojovic.mimizanlab.engine.PlateFilters
import ch.bojovic.mimizanlab.engine.PlateSpace
import ch.bojovic.mimizanlab.engine.PlateState
import ch.bojovic.mimizanlab.engine.Process
import ch.bojovic.mimizanlab.engine.RgbImage
import ch.bojovic.mimizanlab.engine.WeightSpace
import ch.bojovic.mimizanlab.engine.Weights
import ch.bojovic.mimizanlab.engine.normaliseWeights
import ch.bojovic.mimizanlab.store.Prefs
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
    /** Soft-pass time in stops from the base exposure. */
    val soft: Double = 0.0,
    /** Hard-pass time in stops from the base exposure. */
    val hard: Double = 0.0,
    /** The plate process as the development stands; null is the monochrome negative. */
    val plate: PlateState? = null,
    /** Peak's radius (pixels of the negative) and amount the exports and the 1:1 view use. */
    val peakRadius: Double = Prefs.PEAK.radiusDefault,
    val peakAmount: Double = 0.0,
    /** What the algorithm measured on this negative; null until the development carries it. */
    val peakMeasured: PeakMeasured? = null,
    /** Grey, or the plate in colour. */
    val preview: Bitmap? = null,
    /** The luminance's histogram (the plate's too). */
    val histogram: FloatArray = FloatArray(0),
    /** The three positives, 3 x 256 bins normalised together; empty for a negative. */
    val histogramRgb: FloatArray = FloatArray(0),
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

    init {
        // The Peak checkbox lives in the settings; a zoomed-in view follows it.
        viewModelScope.launch {
            darkroom.prefs.peak.collect { detailRegion?.let { r -> requestDetail(r[0], r[1], r[2], r[3]) } }
        }
    }

    /** Bind to a shot; follows [Darkroom.current] so a (re)development shows up. */
    fun open(name: String) {
        if (_state.value.name == name && openJob?.isActive == true) {
            // The collector stays alive, so a second visit must still pick up
            // the settings exposures.
            _state.update {
                it.copy(
                    soft = darkroom.prefs.soft.value,
                    hard = darkroom.prefs.hard.value,
                )
            }
            render()
            return
        }
        openJob?.cancel()
        detailRegion = null
        _state.value = ReviewState(
            name = name,
            soft = darkroom.prefs.soft.value,
            hard = darkroom.prefs.hard.value,
        )
        openJob = viewModelScope.launch {
            darkroom.current.collect { neg ->
                if (neg != null && neg.name == name) {
                    developed = neg.developed
                    // info.weights already include the filter of the first
                    // mix; the sliders start from the camera's base weights.
                    // The plate is the process the frame was developed in.
                    // Lamps stay at zero.
                    _state.update {
                        it.copy(
                            info = neg.info,
                            weights = neg.info.defaultWeights,
                            effective = neg.info.weights,
                            filter = neg.info.filter,
                            plate = neg.info.plate,
                            // A (re)development has new pixels: the fields
                            // take the measured pair, as on the desktop.
                            peakMeasured = neg.peak,
                            peakRadius = neg.peak?.radius ?: Prefs.PEAK.radiusDefault,
                            peakAmount = neg.peak?.amount ?: 0.0,
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

    /** Develop (again) from the cache, in the process the darkroom stands in now. */
    fun develop(settings: DevelopSettings = shotSettings() ?: DevelopSettings()) {
        val shot = darkroom.shots.value.firstOrNull { it.name == _state.value.name } ?: return
        darkroom.redevelop(shot.name, darkroom.withStandingProcess(settings))
    }

    fun shotSettings(): DevelopSettings? = darkroom.shots.value.firstOrNull { it.name == _state.value.name }?.settings

    fun setFilter(f: Filter) {
        _state.update { it.copy(filter = f) }
        remixAndRender()
    }

    /**
     * Switch the process: the plate with the standing filters and
     * primaries, or back to the monochrome mix. A re-mix of the same
     * separation; the roll remembers the choice for the next shot.
     */
    fun setProcess(p: Process) {
        darkroom.prefs.setProcess(p)
        val cur = _state.value
        if ((p == Process.PLATE) == (cur.plate != null)) return
        remixAndRender()
    }

    fun setPlateFilters(f: PlateFilters) {
        darkroom.prefs.setPlateFilters(f)
        if (_state.value.plate != null) remixAndRender()
    }

    fun setPlateSpace(s: PlateSpace) {
        darkroom.prefs.setPlateSpace(s)
        if (_state.value.plate != null) remixAndRender()
    }

    /** Peak's radius and amount by hand; only the 1:1 view has to follow (the preview is never sharpened). */
    fun setPeak(radius: Double, amount: Double) {
        _state.update { it.copy(peakRadius = Prefs.clampPeakRadius(radius), peakAmount = Prefs.clampPeakAmount(amount)) }
        detailRegion?.let { r -> requestDetail(r[0], r[1], r[2], r[3]) }
    }

    /** Back to the pair the algorithm measured. */
    fun resetPeak() {
        val m = _state.value.peakMeasured ?: return
        setPeak(m.radius, m.amount)
    }

    /** The amount the exports and the 1:1 view apply: zero while the Peak checkbox is off. */
    private fun peakAmountApplied(s: ReviewState): Double = if (darkroom.prefs.peak.value) s.peakAmount else 0.0

    fun setSoft(stops: Double) = setPaper(soft = Prefs.snapStops(stops), hard = null)

    fun setHard(stops: Double) = setPaper(soft = null, hard = Prefs.snapStops(stops))

    private fun setPaper(soft: Double?, hard: Double?) {
        _state.update { it.copy(soft = soft ?: it.soft, hard = hard ?: it.hard) }
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

    /**
     * Draw the picture as it stands. `remix` applies the sliders and the
     * standing process first (the mix, or the plate's filters and
     * primaries). A plate is always shown with the lamps at zero.
     */
    private fun render(remix: Boolean = false) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val d = developed ?: return@launch
            renderLock.withLock {
                try {
                    _state.update { it.copy(busy = true) }
                    val s = _state.value
                    if (remix) {
                        if (darkroom.prefs.process.value == Process.PLATE) {
                            d.setPlate(darkroom.prefs.plateFilters.value, darkroom.prefs.plateSpace.value)
                            d.setLamps(0.0, 0.0)
                        } else {
                            val w = d.remix(normaliseWeights(s.weights), WeightSpace.BALANCED, s.filter)
                            _state.update { it.copy(effective = w) }
                        }
                    } else if (s.plate != null) {
                        d.setLamps(0.0, 0.0)
                    }
                    val info = d.info()
                    val h = d.histogram(s.soft, s.hard)
                    val hist = normalised(h)
                    val plate = info.plate
                    val bitmap: Bitmap
                    val histRgb: FloatArray
                    if (plate != null) {
                        bitmap = toBitmap(d.previewRgb(s.soft, s.hard, 0u))
                        histRgb = normalised(d.histogramRgb(s.soft, s.hard))
                    } else {
                        bitmap = toBitmap(d.preview(s.soft, s.hard, 0u))
                        histRgb = FloatArray(0)
                    }
                    _state.update {
                        it.copy(
                            preview = bitmap, histogram = hist, histogramRgb = histRgb, detail = null,
                            busy = false, info = info, plate = plate,
                        )
                    }
                    // A zoomed-in view has to follow the new tones.
                    detailRegion?.let { r -> requestDetail(r[0], r[1], r[2], r[3]) }
                } catch (t: Throwable) {
                    Log.e("Review", "render", t)
                    _state.update { it.copy(busy = false, message = t.message) }
                }
            }
        }
    }

    private fun normalised(h: List<ULong>): FloatArray {
        val max = (h.maxOrNull() ?: 1uL).toDouble().coerceAtLeast(1.0)
        return FloatArray(h.size) { (h[it].toDouble() / max).toFloat() }
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
                    val s = _state.value
                    val amount = peakAmountApplied(s)
                    val bitmap = if (s.plate != null) {
                        toBitmap(d.detailRgb(x.toUInt(), y.toUInt(), w.toUInt(), h.toUInt(), s.soft, s.hard, 0u, s.peakRadius, amount))
                    } else {
                        toBitmap(d.detail(x.toUInt(), y.toUInt(), w.toUInt(), h.toUInt(), s.soft, s.hard, 0u, s.peakRadius, amount))
                    }
                    _state.update { it.copy(detail = Detail(x, y, bitmap)) }
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

    fun exportJpeg() = export("JPEG") { d, name, suffix ->
        val s = _state.value
        darkroom.exportJpeg(d, name, suffix, s.soft, s.hard, s.peakRadius, peakAmountApplied(s))
    }

    fun exportTiff() = export("TIFF 16-bit") { d, name, suffix ->
        val s = _state.value
        darkroom.exportTiff(d, name, suffix, s.soft, s.hard, s.peakRadius, peakAmountApplied(s))
    }

    fun exportNegative() = export(if (_state.value.plate != null) "plate" else "negative") { d, name, _ ->
        darkroom.exportNegative(d, name).first()
    }

    private fun export(label: String, f: (Developed, String, String) -> Uri) {
        val d = developed ?: return
        val s = _state.value
        val suffix = s.plate?.let { Darkroom.suffixFor(it) } ?: Darkroom.suffixFor(s.filter)
        viewModelScope.launch(Dispatchers.IO) {
            renderLock.withLock {
                _state.update { it.copy(exporting = label) }
                try {
                    val uri = f(d, s.name, suffix)
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

        /**
         * RGB8 -> ARGB bitmap. The values are the paper's gamma 2.2 in the
         * plate's primaries; the display takes them as sRGB, as it takes
         * the grey preview (the same small difference, now in colour).
         */
        suspend fun toBitmap(g: RgbImage): Bitmap = withContext(Dispatchers.Default) {
            val w = g.width.toInt()
            val h = g.height.toInt()
            val px = IntArray(w * h)
            val src = g.data
            for (i in px.indices) {
                val r = src[3 * i].toInt() and 0xFF
                val gg = src[3 * i + 1].toInt() and 0xFF
                val b = src[3 * i + 2].toInt() and 0xFF
                px[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
