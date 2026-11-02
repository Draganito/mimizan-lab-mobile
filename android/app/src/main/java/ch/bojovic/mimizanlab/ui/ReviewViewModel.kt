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
import ch.bojovic.mimizanlab.store.Grade
import ch.bojovic.mimizanlab.store.GradeMath
import ch.bojovic.mimizanlab.store.PeakMode
import ch.bojovic.mimizanlab.store.Placed
import ch.bojovic.mimizanlab.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /** Soft-pass time in stops from the base exposure, as shown: the stored grade plus [placedShift]. */
    val soft: Double = 0.0,
    /** Hard-pass time in stops from the base exposure, as shown. */
    val hard: Double = 0.0,
    /**
     * Stops this negative's middle wants to sit on the grey card, measured
     * on the current mix (`zoneShift(0, 0)`); null until the development is
     * in memory and the render has measured it.
     */
    val zoneShift: Double? = null,
    /**
     * The stops the balance actually added to the stored grade in [soft]
     * and [hard] (shift plus compensation, snapped and clamped to the room).
     * Zero with the balance off. A grade move takes exactly this back off
     * before it stores, so the next picture adds its own shift once.
     */
    val placedShift: Double = 0.0,
    /** The plate process as the development stands; null is the monochrome negative. */
    val plate: PlateState? = null,
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
        // Peak lives in the settings; a zoomed-in view follows the mode and the custom pair.
        viewModelScope.launch {
            combine(darkroom.prefs.peakMode, darkroom.prefs.peakRadius, darkroom.prefs.peakAmount) { _, _, _ -> }
                .collect { detailRegion?.let { r -> requestDetail(r[0], r[1], r[2], r[3]) } }
        }
    }

    /** Bind to a shot; follows [Darkroom.current] so a (re)development shows up. */
    fun open(name: String) {
        if (_state.value.name == name && openJob?.isActive == true) {
            // The collector stays alive, so a second visit must still pick up
            // the settings exposures, plus the zone shift when balance is on
            // (the settings sheet may have changed both). The render places.
            render()
            return
        }
        openJob?.cancel()
        detailRegion = null
        val g = darkroom.prefs.grade.value
        _state.value = ReviewState(name = name, soft = g.soft, hard = g.hard)
        openJob = viewModelScope.launch {
            darkroom.current.collect { neg ->
                if (neg != null && neg.name == name) {
                    developed = neg.developed
                    // info.weights already include the filter of the first
                    // mix; the sliders start from the camera's base weights.
                    // The plate is the process the frame was developed in.
                    // Lamps stay at zero. The pair starts at the stored
                    // grade; the render measures this negative's shift and
                    // places it, and the shift is never stored.
                    val stored = darkroom.prefs.grade.value
                    _state.update {
                        it.copy(
                            info = neg.info,
                            weights = neg.info.defaultWeights,
                            effective = neg.info.weights,
                            filter = neg.info.filter,
                            plate = neg.info.plate,
                            peakMeasured = neg.peak,
                            needsDevelop = false,
                            detail = null,
                            soft = stored.soft,
                            hard = stored.hard,
                            zoneShift = null,
                            placedShift = 0.0,
                        )
                    }
                    render()
                } else {
                    developed = null
                    val stored = darkroom.prefs.grade.value
                    _state.update {
                        it.copy(
                            needsDevelop = true, preview = null, detail = null,
                            soft = stored.soft, hard = stored.hard, zoneShift = null, placedShift = 0.0,
                        )
                    }
                }
            }
        }
    }

    /** Develop (again) from the cache, in the process the darkroom stands in now. */
    fun develop(settings: DevelopSettings = shotSettings() ?: DevelopSettings()) {
        val shot = darkroom.shots.value.firstOrNull { it.name == _state.value.name } ?: return
        darkroom.redevelop(shot.name, darkroom.withStandingProcess(settings).copy(autoJpeg = false))
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

    /** Custom, started from this negative's measurement. The 1:1 view follows; the preview is never sharpened. */
    fun setPeakMode(mode: PeakMode) {
        if (mode == PeakMode.CUSTOM && darkroom.prefs.peakMode.value != PeakMode.CUSTOM) {
            _state.value.peakMeasured?.let { darkroom.prefs.setPeak(it.radius, it.amount) }
        }
        darkroom.prefs.setPeakMode(mode)
    }

    /** Peak's radius and amount by hand. Remembered for the next picture and the automatic JPEG. */
    fun setPeak(radius: Double, amount: Double) {
        darkroom.prefs.setPeak(radius, amount)
    }

    /** Radius and amount the exports and the 1:1 view apply. */
    private fun peakApplied(s: ReviewState): Pair<Double, Double> =
        Prefs.peakApplied(darkroom.prefs.peakMode.value, darkroom.prefs.peakRadius.value, darkroom.prefs.peakAmount.value, s.peakMeasured)

    /**
     * The stored grade with the balance on it, from a [shift] already
     * measured: no call into the development, so the slider path stays on
     * the main thread without waiting on the render's lock. With the
     * balance off (or nothing measured yet) the stored grade as it is.
     */
    private fun placed(shift: Double?): Placed {
        val stored = darkroom.prefs.grade.value
        if (!darkroom.prefs.zoneAuto.value || shift == null) return Placed(stored, 0.0)
        return Prefs.place(stored, shift, darkroom.prefs.zoneBias.value)
    }

    /**
     * The stops this negative's middle wants, measured on the mix as it
     * stands. Costs a mix of the preview planes: only from the render, on
     * its dispatcher. Zero when the development refuses (no preview planes).
     */
    private fun measureShift(d: Developed): Double = runCatching { d.zoneShift(0.0, 0.0) }.getOrDefault(0.0)

    fun setSoft(stops: Double) = setGrade(Prefs.snapStops(stops), _state.value.hard)

    fun setHard(stops: Double) = setGrade(_state.value.soft, Prefs.snapStops(stops))

    /**
     * Both exposures as they stand on this print. With the balance on, the
     * sliders include this negative's shift and the compensation; exactly
     * the stops that were added ([ReviewState.placedShift]) come back off
     * before the grade is stored, so the next picture adds its own once.
     * Then the pair is placed again from what was stored: what the user
     * set is what shows, unless the balance pins a slider at the limit.
     */
    fun setGrade(soft: Double, hard: Double) {
        val s = _state.value
        val stored = GradeMath.unplace(Grade(soft, hard), s.placedShift, Prefs.STOP_LIMIT)
        darkroom.prefs.setGrade(stored.soft, stored.hard)
        val p = placed(s.zoneShift)
        _state.update { it.copy(soft = p.grade.soft, hard = p.grade.hard, placedShift = p.shift) }
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
     * primaries). A plate is always shown with the lamps at zero. With the
     * balance on, the zone shift is measured here, once per negative and
     * again after a remix (the mix moves the middle), and the shown pair is
     * placed from the stored grade: the stored grade never carries the shift.
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
                    // A measurement made on an earlier mix is not kept, also
                    // with the balance off: turning it on must measure again.
                    val shift = when {
                        !darkroom.prefs.zoneAuto.value -> if (remix) null else s.zoneShift
                        remix || s.zoneShift == null -> measureShift(d)
                        else -> s.zoneShift
                    }
                    val p = placed(shift)
                    val soft = p.grade.soft
                    val hard = p.grade.hard
                    // The pair goes out before the picture, so a slider moved
                    // during this render is not put back afterwards.
                    _state.update { it.copy(soft = soft, hard = hard, zoneShift = shift, placedShift = p.shift) }
                    val info = d.info()
                    val h = d.histogram(soft, hard)
                    val hist = normalised(h)
                    val plate = info.plate
                    val bitmap: Bitmap
                    val histRgb: FloatArray
                    if (plate != null) {
                        bitmap = toBitmap(d.previewRgb(soft, hard, 0u))
                        histRgb = normalised(d.histogramRgb(soft, hard))
                    } else {
                        bitmap = toBitmap(d.preview(soft, hard, 0u))
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
                    val applied = peakApplied(s)
                    val bitmap = if (s.plate != null) {
                        toBitmap(d.detailRgb(x.toUInt(), y.toUInt(), w.toUInt(), h.toUInt(), s.soft, s.hard, 0u, applied.first, applied.second))
                    } else {
                        toBitmap(d.detail(x.toUInt(), y.toUInt(), w.toUInt(), h.toUInt(), s.soft, s.hard, 0u, applied.first, applied.second))
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
        val applied = peakApplied(s)
        darkroom.exportJpeg(d, name, suffix, s.soft, s.hard, applied.first, applied.second)
    }

    fun exportTiff() = export("TIFF 16-bit") { d, name, suffix ->
        val s = _state.value
        val applied = peakApplied(s)
        darkroom.exportTiff(d, name, suffix, s.soft, s.hard, applied.first, applied.second)
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
