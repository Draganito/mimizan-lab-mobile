package ch.bojovic.mimizanlab.ui

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.SurfaceTexture
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.bojovic.mimizanlab.camera.CameraController
import ch.bojovic.mimizanlab.camera.ExposureSettings
import ch.bojovic.mimizanlab.camera.FlashMode
import ch.bojovic.mimizanlab.camera.LensInfo
import ch.bojovic.mimizanlab.engine.Darkroom
import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.engine.Filter
import ch.bojovic.mimizanlab.engine.FilterInfo
import ch.bojovic.mimizanlab.engine.SeparationMode
import ch.bojovic.mimizanlab.engine.filters
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val filterInfos: List<FilterInfo> = filters()

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

    private var surface: SurfaceTexture? = null
    private var startJob: Job? = null

    /** Weights the shader uses for the current filter (native sensor mix x transmission). */
    fun monoLook(contrast: Double = darkroom.prefs.contrast.value): MonoLook {
        val f = filterInfos.firstOrNull { it.filter == _develop.value.filter }
        val t = f?.transmission ?: listOf(1.0, 1.0, 1.0)
        val r = 0.25 * t[0]
        val g = 0.5 * t[1]
        val b = 0.25 * t[2]
        val s = r + g + b
        val vf = _viewfinder.value
        return MonoLook(
            r = (r / s).toFloat(), g = (g / s).toFloat(), b = (b / s).toFloat(),
            zebraThreshold = if (vf.zebra) 0.985f else 2f,
            grid = vf.grid,
            contrast = contrast.toFloat(),
        )
    }

    fun onSurface(st: SurfaceTexture?) {
        surface = st
        if (st != null) restart() else stop()
    }

    fun selectLens(l: LensInfo) {
        if (_lens.value?.key == l.key) return
        _lens.value = l
        _exposure.update { ExposureSettings(evSteps = it.evSteps) }
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
            try {
                controller.start(l, st)
                controller.setExposure(_exposure.value)
            } catch (e: SecurityException) {
                _message.value = "Camera permission missing"
            } catch (t: Throwable) {
                Log.e("CameraViewModel", "start", t)
                _message.value = t.message ?: "Camera failed"
            }
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
    fun setContrastDefault(c: Double) = darkroom.prefs.setContrast(c)
    fun setSharpen(amount: Double) = darkroom.prefs.setSharpen(amount)
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
                Log.e("CameraViewModel", "capture", t)
                _message.value = t.message ?: "Capture failed"
            }
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() {
        controller.release()
    }
}
