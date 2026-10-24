package ch.bojovic.mimizanlab.ui

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.bojovic.mimizanlab.camera.CameraController
import ch.bojovic.mimizanlab.camera.ExposureSettings
import ch.bojovic.mimizanlab.camera.FinderTone
import ch.bojovic.mimizanlab.camera.FlashMode
import ch.bojovic.mimizanlab.camera.IspColor
import ch.bojovic.mimizanlab.camera.LensInfo
import ch.bojovic.mimizanlab.engine.Darkroom
import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.SeparationMode
import ch.bojovic.mimizanlab.engine.mixWeights
import ch.bojovic.mimizanlab.engine.paperLut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Viewfinder settings that are not camera state. */
data class ViewfinderSettings(
    val zebra: Boolean = true,
    val grid: Boolean = false,
    val showHistogram: Boolean = true,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    val controller = CameraController(app)
    val darkroom = Darkroom.get(app)
    val lenses: List<LensInfo> get() = controller.lenses
    val stream = controller.state
    val histogram = controller.histogram

    private val _lens = MutableStateFlow(lenses.firstOrNull())
    val lens: StateFlow<LensInfo?> = _lens.asStateFlow()

    private val _exposure = MutableStateFlow(ExposureSettings())
    val exposure: StateFlow<ExposureSettings> = _exposure.asStateFlow()

    private val _develop = MutableStateFlow(DevelopSettings())
    val develop: StateFlow<DevelopSettings> = _develop.asStateFlow()

    private val _viewfinder = MutableStateFlow(ViewfinderSettings())
    val viewfinder: StateFlow<ViewfinderSettings> = _viewfinder.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * What the viewfinder shader applies to the camera preview: the still's
     * mix weights for the current filter and this frame's balance, pulled
     * back through the ISP's colour matrix, and the paper the automatic
     * JPEG uses. Recomputed when the ISP colour moves (white balance), a
     * develop setting changes, or either exposure changes.
     */
    val tone: StateFlow<FinderTone> = combine(
        controller.ispColor,
        _develop,
        combine(darkroom.prefs.soft, darkroom.prefs.hard) { s, h -> s to h },
        _viewfinder,
        _lens,
    ) { color, dev, paper, vf, lens ->
        buildTone(color ?: IspColor.IDENTITY, dev, paper.first, paper.second, vf.zebra, lens)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        buildTone(
            IspColor.IDENTITY,
            DevelopSettings(),
            darkroom.prefs.soft.value,
            darkroom.prefs.hard.value,
            true,
            _lens.value,
        ),
    )

    init {
        viewModelScope.launch { tone.collect { controller.tone = it } }
        viewModelScope.launch {
            darkroom.prefs.compensationThirds.collect { thirds ->
                _exposure.update { it.copy(compensationStops = thirds / 3.0) }
                push()
            }
        }
    }

    private var surface: SurfaceTexture? = null
    private var startJob: Job? = null

    private fun buildTone(
        color: IspColor,
        dev: DevelopSettings,
        soft: Double,
        hard: Double,
        zebra: Boolean,
        lens: LensInfo?,
    ): FinderTone {
        val params = darkroom.developParamsFor(Build.MANUFACTURER, Build.MODEL, lens?.cfa ?: 0, dev)
        val gains = color.gains.map { it.toDouble() }
        val w = try {
            mixWeights(params, gains)
        } catch (t: Throwable) {
            Log.w(TAG, "mix weights", t)
            ch.bojovic.mimizanlab.engine.Weights(0.25, 0.5, 0.25)
        }
        val weights = floatArrayOf(w.r.toFloat(), w.g.toFloat(), w.b.toFloat())
        return FinderTone(
            v = FinderTone.pullBack(weights, color),
            lut = paperLut(soft, hard, 4096u).toFloatArray(),
            invGamma = 1f,
            zebra = if (zebra) 0.985f else 2f,
        )
    }

    fun onSurface(st: SurfaceTexture?) {
        surface = st
        if (st != null) restart() else stop()
    }

    fun selectLens(l: LensInfo) {
        if (_lens.value?.key == l.key) return
        _lens.value = l
        _exposure.update { ExposureSettings(evSteps = it.evSteps, compensationStops = it.compensationStops) }
        restart()
    }

    fun nextLens() {
        val list = lenses
        if (list.size < 2) return
        val i = list.indexOfFirst { it.key == _lens.value?.key }
        selectLens(list[(i + 1) % list.size])
    }

    @SuppressLint("MissingPermission") // the screen only hands over a surface once CAMERA is granted
    private fun restart() {
        val st = surface ?: return
        val l = _lens.value ?: run { _message.value = "No RAW-capable camera"; return }
        startJob?.cancel()
        startJob = viewModelScope.launch {
            // The first lens a HAL lists is often a logical camera that
            // advertises RAW and then rejects the session. Try the rest
            // before giving up, and keep the one that actually opened.
            val order = listOf(l) + lenses.filter { it.key != l.key }
            var last: Throwable? = null
            for (candidate in order) {
                try {
                    controller.start(candidate, st)
                    controller.setExposure(_exposure.value)
                    if (candidate.key != l.key) {
                        _lens.value = candidate
                        _message.value = "${l.label} has no RAW session, opened ${candidate.label}"
                    }
                    return@launch
                } catch (e: SecurityException) {
                    _message.value = "Camera permission missing"
                    return@launch
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "start ${candidate.key}", t)
                    last = t
                }
            }
            _message.value = last?.message ?: "Camera failed"
        }
    }

    fun resume() = restart()

    fun stop() {
        startJob?.cancel()
        viewModelScope.launch { controller.stop() }
    }

    fun setEv(steps: Int) {
        _exposure.update { it.copy(evSteps = steps) }
        push()
    }

    fun setManual(iso: Int, exposureNs: Long) {
        _exposure.update { it.copy(iso = iso, exposureNs = exposureNs) }
        push()
    }

    fun setAuto() {
        _exposure.update { it.copy(iso = null, exposureNs = null) }
        push()
    }

    /** Switch to manual seeded with the exposure the automatics chose. */
    fun enterManual() {
        val s = stream.value
        val l = _lens.value ?: return
        val iso = (s.iso ?: 100).coerceIn(l.isoRange.lower, l.isoRange.upper)
        val t = (s.exposureNs ?: 8_000_000L).coerceIn(l.exposureRangeNs.lower, l.exposureRangeNs.upper)
        setManual(iso, t)
    }

    private fun push() {
        val e = _exposure.value
        viewModelScope.launch { controller.setExposure(e) }
    }

    fun focusAt(x: Float, y: Float) {
        viewModelScope.launch { runCatching { controller.focusAt(x, y) } }
    }

    fun clearFocus() {
        viewModelScope.launch { runCatching { controller.clearFocusPoint() } }
    }

    /** Off, then auto, then always, then off again. */
    fun cycleFlash() {
        val next = when (stream.value.flash) {
            FlashMode.Off -> FlashMode.Auto
            FlashMode.Auto -> FlashMode.On
            FlashMode.On -> FlashMode.Off
        }
        viewModelScope.launch { controller.setFlash(next) }
    }

    fun setFilter(f: Filter) = _develop.update { it.copy(filter = f) }
    fun setSeparation(m: SeparationMode) = _develop.update { it.copy(separation = m) }
    fun setQuick(quick: Boolean) = _develop.update { it.copy(binning = if (quick) 2 else 1) }
    fun setAutoJpeg(on: Boolean) = _develop.update { it.copy(autoJpeg = on) }
    fun setSoft(stops: Double) = darkroom.prefs.setSoft(stops)
    fun setHard(stops: Double) = darkroom.prefs.setHard(stops)
    fun setSharpen(amount: Double) = darkroom.prefs.setSharpen(amount)
    fun setDeconvolution(on: Boolean) = darkroom.prefs.setDeconvolution(on)
    fun setDeconvPasses(n: Int) = darkroom.prefs.setDeconvPasses(n)
    fun setCompensationThirds(thirds: Int) = darkroom.prefs.setCompensationThirds(thirds)
    fun toggleZebra() = _viewfinder.update { it.copy(zebra = !it.zebra) }
    fun toggleGrid() = _viewfinder.update { it.copy(grid = !it.grid) }
    fun toggleHistogram() = _viewfinder.update { it.copy(showHistogram = !it.showHistogram) }

    fun capture() {
        val l = _lens.value ?: return
        if (stream.value.capturing) return
        viewModelScope.launch {
            try {
                val frame = controller.capture(l.label)
                darkroom.submit(frame, _develop.value)
            } catch (t: Throwable) {
                Log.e(TAG, "capture", t)
                _message.value = t.message ?: "Capture failed"
            }
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() {
        controller.release()
    }

    private companion object {
        const val TAG = "CameraViewModel"
    }
}
