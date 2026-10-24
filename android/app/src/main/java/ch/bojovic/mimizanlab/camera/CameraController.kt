package ch.bojovic.mimizanlab.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.RequiresPermission
import ch.bojovic.mimizanlab.engine.MeterInput
import ch.bojovic.mimizanlab.engine.RawRect
import ch.bojovic.mimizanlab.engine.Weights
import ch.bojovic.mimizanlab.engine.meterRaw
import ch.bojovic.mimizanlab.raw.Orientation
import ch.bojovic.mimizanlab.raw.RawCapture
import ch.bojovic.mimizanlab.raw.RawFrameBridge
import ch.bojovic.mimizanlab.raw.RawMeta
import java.util.concurrent.Executor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Still-capture flash. The viewfinder never lights it. */
enum class FlashMode { Off, Auto, On }

/** Exposure the user asked for. `null` = automatic. */
data class ExposureSettings(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val evSteps: Int = 0,
) {
    val manual: Boolean get() = iso != null && exposureNs != null
}

/** What the viewfinder shows about the running stream. */
data class StreamState(
    val lens: LensInfo? = null,
    val previewSize: Size? = null,
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val afState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE,
    val aeState: Int = CaptureResult.CONTROL_AE_STATE_INACTIVE,
    val focusPoint: Pair<Float, Float>? = null,
    val deviceRotation: Int = 0,
    val flash: FlashMode = FlashMode.Off,
    val capturing: Boolean = false,
    val error: String? = null,
    /** Who is setting the exposure right now (PHONE when the app's meter is not running). */
    val metering: MeteringMode = MeteringMode.PHONE,
    /** The still's pair the app's meter has decided on; null under the phone's AE. */
    val plannedIso: Int? = null,
    val plannedNs: Long? = null,
    /** The app's meter cannot reach its exposure within the still's limits. */
    val flashRequired: Boolean = false,
    /** Why the app's meter stepped aside, once it has. */
    val meteringNote: String? = null,
)

/**
 * Camera2 session: a preview stream (SurfaceTexture) with the ISP pinned
 * to a known rendering ([IspControls]), a small YUV stream for the
 * histogram, and a RAW_SENSOR reader for the still and for the meter's
 * probes. The viewfinder shader turns the preview back into the negative
 * with [ispColor] of each frame; [tone] lets the histogram do the same
 * arithmetic on the CPU.
 *
 * With [MeteringMode.HIGHLIGHTS] or [MeteringMode.GREY] the phone's AE is
 * off and the app sets ISO and time itself, from `RAW_SENSOR` probes:
 * single requests with the RAW reader as an extra target, slipped between
 * the repeating preview frames a few times a second. Such a request has the
 * same frame duration as the preview and the RAW stream was configured
 * anyway, so the stream keeps its rate; a guard watches the frame
 * timestamps and hands the exposure back to the phone if a HAL stalls
 * nevertheless.
 *
 * One instance per screen; [start] / [stop] follow the lifecycle.
 */
class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val thread = HandlerThread("camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // The histogram runs on its own thread: capture callbacks must never
    // wait behind picture analysis.
    private val analysisThread = HandlerThread("analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)
    private var lastAnalysisMs = 0L
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

    /** The meter's probe loop and its analysis. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state.asStateFlow()

    /**
     * 64-bin histogram of the viewfinder picture (normalised to the max
     * bin): the negative's display values when [tone] is set, else ISP luma.
     * From the YUV stream; from the meter's RAW probes when a HAL refused
     * that stream.
     */
    private val _histogram = MutableStateFlow(FloatArray(64))
    val histogram: StateFlow<FloatArray> = _histogram.asStateFlow()

    /** Colour step the ISP applied to the latest preview frame. */
    private val _ispColor = MutableStateFlow<IspColor?>(null)
    val ispColor: StateFlow<IspColor?> = _ispColor.asStateFlow()

    /** Set by the screen's owner; the histogram and the meter's grey follow it. */
    @Volatile var tone: FinderTone? = null

    private val results = MutableSharedFlow<TotalCaptureResult>(extraBufferCapacity = 4)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var lens: LensInfo? = null
    private var characteristics: CameraCharacteristics? = null
    private var exposure = ExposureSettings()
    private var flash = FlashMode.Off
    private var afRegion: MeteringRectangle? = null
    /** Continuous until a tap pins the focus (then AUTO, locked by a trigger). */
    private var afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
    private var deviceRotation = 0
    private val lock = Mutex()
    private var frameCounter = 0
    private var isp: IspControls? = null

    // --- the app's meter
    private var meteringMode = MeteringMode.PHONE
    private var meter: ExposureMeter? = null
    private var probeJob: Job? = null
    /** Active array inside the RAW buffer, for the probe sample; null = whole buffer. */
    private var activeCrop: Rect? = null
    private var rowScratch: ByteArray? = null
    private var probeWithPreview = true
    private var probeFailures = 0
    private var probesDisabled = false
    private var probeCount = 0
    private val probeHistogram = IntArray(64)
    // Frame-gap guard: preview intervals from the sensor timestamps.
    private var lastFrameTs = 0L
    private var frameIntervalNs = 0L
    private var gapCount = 0

    /**
     * Whoever waits for the next RAW frame, in the order the requests went
     * out: the HAL returns RAW buffers in request order, so a probe and a
     * still share one reader without mixing up their frames.
     */
    private val rawWaiters = ArrayDeque<CompletableDeferred<Image>>()

    private val orientationListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            val snapped = Orientation.snap(orientation)
            if (snapped >= 0 && snapped != deviceRotation) {
                deviceRotation = snapped
                _state.update { it.copy(deviceRotation = snapped) }
            }
        }
    }

    val lenses: List<LensInfo> by lazy { Lenses.enumerate(manager) }

    /** Preview size for a 4:3 TextureView: the largest <= 1600 px wide of the sensor aspect. */
    fun previewSizeFor(lens: LensInfo): Size {
        val c = manager.getCameraCharacteristics(lens.sensorId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val aspect = lens.rawSize.width.toFloat() / lens.rawSize.height
        return sizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1600 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { abs(it.width.toFloat() / it.height - aspect) }
            ?: Size(1280, 960)
    }

    private fun analysisSizeFor(lens: LensInfo): Size? {
        val c = manager.getCameraCharacteristics(lens.sensorId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        if (sizes.isEmpty()) return null
        val aspect = lens.rawSize.width.toFloat() / lens.rawSize.height
        return sizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width >= 240 }
            .minByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun start(lens: LensInfo, texture: SurfaceTexture) = lock.withLock {
        closeSessionLocked()
        this.lens = lens
        val previewSize = previewSizeFor(lens)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)
        // Sensor-level characteristics (CFA, black level, arrays): the
        // physical camera's when the streams are routed to one.
        val c = manager.getCameraCharacteristics(lens.sensorId)
        characteristics = c
        activeCrop = RawFrameBridge.cropOf(
            c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
            c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            lens.rawSize.width,
            lens.rawSize.height,
        )?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
            val stall = runCatching { map.getOutputStallDuration(ImageFormat.RAW_SENSOR, lens.rawSize) }.getOrNull()
            val minDur = runCatching { map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, lens.rawSize) }.getOrNull()
            Log.i(TAG, "raw stream ${lens.rawSize}: min frame ${minDur?.div(1_000_000)} ms, stall ${stall?.div(1_000_000)} ms")
        }
        // Request keys are judged by the camera the requests go to.
        isp = IspControls(manager.getCameraCharacteristics(lens.cameraId)).also { Log.i(TAG, "isp: ${it.description}") }
        _ispColor.value = null

        rawReader = ImageReader.newInstance(lens.rawSize.width, lens.rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val waiter = synchronized(rawWaiters) { rawWaiters.removeFirstOrNull() }
                if (waiter == null || !waiter.complete(img)) img.close()
            }, handler)
        }
        val an = analysisSizeFor(lens)
        yuvReader = an?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    img.use {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAnalysisMs >= ANALYSIS_PERIOD_MS) {
                            lastAnalysisMs = now
                            analyse(it)
                        }
                    }
                }, analysisHandler)
            }
        }
        orientationListener.enable()

        val dev = openDevice(lens.cameraId)
        device = dev
        val preview = previewSurface!!
        val rawSurface = rawReader!!.surface
        val withAnalysis = listOfNotNull(preview, yuvReader?.surface, rawSurface)
        session = try {
            try {
                createSession(dev, withAnalysis, lens.physicalId)
            } catch (e: IllegalStateException) {
                if (e.message != "session configuration failed" || yuvReader == null) throw e
                // Samsung HALs often reject RAW + preview + YUV on one session.
                // The histogram needs the YUV stream; the still does not.
                Log.w(TAG, "session with analysis stream failed, retrying RAW + preview", e)
                yuvReader?.close()
                yuvReader = null
                createSession(dev, listOf(preview, rawSurface), lens.physicalId)
            }
        } catch (e: Exception) {
            orientationListener.disable()
            closeSessionLocked()
            throw e
        }
        _state.update { it.copy(lens = lens, previewSize = previewSize, error = null, focusPoint = null, flash = flash) }
        afRegion = null
        afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        lastFrameTs = 0L
        frameIntervalNs = 0L
        gapCount = 0
        probeCount = 0
        probeFailures = 0
        probeWithPreview = true
        armMeteringLocked()
        startRepeatingLocked()
    }

    suspend fun stop() = lock.withLock {
        orientationListener.disable()
        closeSessionLocked()
    }

    fun release() {
        scope.cancel()
        thread.quitSafely()
        analysisThread.quitSafely()
    }

    private fun closeSessionLocked() {
        probeJob?.cancel(); probeJob = null
        meter = null
        synchronized(rawWaiters) {
            rawWaiters.forEach { it.cancel() }
            rawWaiters.clear()
        }
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        device?.close(); device = null
        rawReader?.close(); rawReader = null
        yuvReader?.close(); yuvReader = null
        previewSurface?.release(); previewSurface = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                else _state.update { it.copy(error = "camera disconnected") }
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val msg = "camera error $error"
                if (cont.isActive) cont.resumeWithException(IllegalStateException(msg))
                else _state.update { it.copy(error = msg) }
            }
        })
    }

    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>, physicalId: String?): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map { s -> OutputConfiguration(s).also { if (physicalId != null) it.setPhysicalCameraId(physicalId) } },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        s.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("session configuration failed"))
                    }
                },
            )
            dev.createCaptureSession(config)
        }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            noteColor(result)
            noteFrameInterval(result)
            results.tryEmit(result)
            if (++frameCounter % 3 != 0) return
            meter?.let { m ->
                m.flickerHz = when (result.get(CaptureResult.STATISTICS_SCENE_FLICKER)) {
                    CameraMetadata.STATISTICS_SCENE_FLICKER_50HZ -> 50
                    CameraMetadata.STATISTICS_SCENE_FLICKER_60HZ -> 60
                    else -> m.flickerHz
                }
            }
            _state.update {
                it.copy(
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: it.iso,
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: it.exposureNs,
                    afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: it.afState,
                    aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: it.aeState,
                )
            }
        }
    }

    /**
     * A frame interval well above the running mean is a gap. Counted while
     * the meter probes, so a HAL whose RAW requests stall the preview is
     * found out instead of felt.
     */
    private fun noteFrameInterval(result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (lastFrameTs > 0L) {
            val d = ts - lastFrameTs
            if (frameIntervalNs == 0L) {
                frameIntervalNs = d
            } else if (d > frameIntervalNs * 5 / 2 && d < 1_000_000_000L) {
                gapCount++
            } else if (d > 0) {
                frameIntervalNs = (frameIntervalNs * 7 + d) / 8
            }
        }
        lastFrameTs = ts
    }

    /**
     * [armFlash] is set on the precapture trigger and the still, never on the
     * repeating preview, so the viewfinder stays dark.
     */
    private fun applyControls(b: CaptureRequest.Builder, still: Boolean, armFlash: Boolean = false) {
        val lens = lens ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (lens.hasAutoFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
            afRegion?.let {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
            }
        }
        val plan = if (exposure.manual) null else meter?.plan
        if ((exposure.manual || plan != null) && lens.hasManualSensor) {
            val (iso, ns) = when {
                plan != null && still -> plan.stillIso to plan.stillNs
                plan != null -> plan.previewIso to plan.previewNs
                else -> exposure.iso!! to exposure.exposureNs!!
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(lens.isoRange.lower, lens.isoRange.upper))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns.coerceIn(lens.exposureRangeNs.lower, lens.exposureRangeNs.upper))
            // Manual: keep the preview watchable at long exposures, 1/30 s frame cap.
            // The meter keeps its preview time at or under 1/30 s and leaves
            // the frame duration to the template, so the stream keeps its rate.
            if (plan == null && !still && supports(CaptureRequest.SENSOR_FRAME_DURATION)) {
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(ns, 33_333_333L))
            }
            // The preview shows the sensor's exposure, not a boosted copy of it
            // (the boost touches YUV/JPEG only, never the RAW).
            if (supports(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST)) {
                b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
            }
            // With AE off the automatics cannot decide. On fires once for the
            // still; Auto fires when the meter could not reach its exposure.
            val fire = still && armFlash && lens.hasFlash &&
                (flash == FlashMode.On || (flash == FlashMode.Auto && plan?.flashRequired == true))
            b.set(CaptureRequest.FLASH_MODE, if (fire) CameraMetadata.FLASH_MODE_SINGLE else CameraMetadata.FLASH_MODE_OFF)
        } else {
            val ae = when {
                armFlash && lens.hasFlash && flash == FlashMode.On -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                armFlash && lens.hasFlash && flash == FlashMode.Auto -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, ae)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure.evSteps.coerceIn(lens.evRange.lower, lens.evRange.upper))
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        isp?.apply(b)
        if (still) {
            val shading = characteristics?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
            if (shading != null && CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON in shading) {
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }
            if (supports(CaptureRequest.CONTROL_ENABLE_ZSL)) b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
    }

    /** True when the open camera lists [key], or when it publishes no key list at all. */
    private fun supports(key: CaptureRequest.Key<*>): Boolean {
        val keys = characteristics?.availableCaptureRequestKeys ?: return true
        return keys.contains(key)
    }

    private fun startRepeatingLocked() {
        val s = session ?: return
        val dev = device ?: return
        val preview = previewSurface ?: return
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(preview)
        yuvReader?.surface?.let { b.addTarget(it) }
        applyControls(b, still = false)
        s.setRepeatingRequest(b.build(), previewCallback, handler)
    }

    suspend fun setExposure(e: ExposureSettings) = lock.withLock {
        exposure = e
        meter?.biasStops = biasStops(e)
        if (session != null) startRepeatingLocked()
    }

    /** The EV slider in stops: the HAL's step times the steps. */
    private fun biasStops(e: ExposureSettings): Double = e.evSteps * (lens?.evStep?.toDouble() ?: 0.0)

    suspend fun setFlash(mode: FlashMode) = lock.withLock {
        flash = mode
        _state.update { it.copy(flash = mode) }
    }

    /** Who meters. Takes effect at once on a running session. */
    suspend fun setMetering(mode: MeteringMode) = lock.withLock {
        if (mode == meteringMode && !probesDisabled) return@withLock
        meteringMode = mode
        // A fresh choice gets a fresh try, also after the guard stepped in.
        probesDisabled = false
        probeFailures = 0
        _state.update { it.copy(meteringNote = null) }
        if (session != null) {
            armMeteringLocked()
            startRepeatingLocked()
        }
    }

    /**
     * Create the meter for the current lens and mode and start probing, or
     * drop it. The meter starts from the exposure the stream last showed,
     * whichever automatics chose it, so the first probe is close.
     */
    private fun armMeteringLocked() {
        probeJob?.cancel(); probeJob = null
        val lens = lens
        if (lens == null || meteringMode == MeteringMode.PHONE || !lens.hasManualSensor || probesDisabled) {
            meter = null
            publishMetering()
            return
        }
        val m = ExposureMeter(
            isoMin = lens.isoRange.lower,
            isoMax = lens.isoRange.upper,
            nsMin = lens.exposureRangeNs.lower,
            nsMax = lens.exposureRangeNs.upper,
            equivalentMm = lens.equivalentMm,
            mode = meteringMode,
        )
        m.biasStops = biasStops(exposure)
        val s = _state.value
        m.seed(s.iso ?: 100, s.exposureNs ?: 16_666_667L)
        meter = m
        gapCount = 0
        probeCount = 0
        publishMetering()
        probeJob = scope.launch { probeLoop(m) }
    }

    private fun publishMetering() {
        val m = meter
        val plan = m?.plan
        _state.update {
            it.copy(
                metering = m?.mode ?: MeteringMode.PHONE,
                plannedIso = plan?.stillIso,
                plannedNs = plan?.stillNs,
                flashRequired = plan?.flashRequired ?: false,
            )
        }
    }

    private suspend fun probeLoop(m: ExposureMeter) {
        while (coroutineContext.isActive) {
            delay(PROBE_PERIOD_MS)
            if (meter !== m || probesDisabled) return
            if (exposure.manual || _state.value.capturing) continue
            try {
                probe(m)
                probeFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "probe", t)
                if (++probeFailures >= 5) {
                    standDown(m, "RAW probes fail on this camera; the phone meters")
                    return
                }
            }
            if (++probeCount % GUARD_WINDOW == 0) {
                if (gapCount >= GUARD_GAPS) {
                    Log.w(TAG, "guard: $gapCount gaps in $GUARD_WINDOW probes (interval ${frameIntervalNs / 1_000_000} ms)")
                    standDown(m, "RAW probes stall the preview on this phone; the phone meters")
                    return
                }
                gapCount = 0
            }
        }
    }

    /** The meter steps aside for this session; the phone's AE takes over. */
    private suspend fun standDown(m: ExposureMeter, note: String) {
        lock.withLock {
            if (meter !== m) return
            probesDisabled = true
            meter = null
            _state.update { it.copy(meteringNote = note) }
            publishMetering()
            runCatching { startRepeatingLocked() }
        }
    }

    /**
     * One probe: a preview-template request with the RAW reader as an
     * extra target, the same controls as the repeating request. The frame
     * is sampled, measured in Rust, and the meter decides; a changed plan
     * goes out as the new repeating request.
     */
    private suspend fun probe(m: ExposureMeter) {
        val resultDeferred = CompletableDeferred<TotalCaptureResult>()
        var imageDeferred: CompletableDeferred<Image>? = null
        val withPreview = probeWithPreview
        lock.withLock {
            val s = session ?: return
            val dev = device ?: return
            val reader = rawReader ?: return
            val preview = previewSurface ?: return
            if (meter !== m) return
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            if (withPreview) b.addTarget(preview)
            yuvReader?.surface?.let { b.addTarget(it) }
            b.addTarget(reader.surface)
            applyControls(b, still = false)
            val waiter = CompletableDeferred<Image>()
            synchronized(rawWaiters) { rawWaiters.addLast(waiter) }
            imageDeferred = waiter
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    previewCallback.onCaptureCompleted(s, req, result)
                    resultDeferred.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                    synchronized(rawWaiters) { rawWaiters.remove(waiter) }
                    waiter.cancel()
                    resultDeferred.completeExceptionally(IllegalStateException("probe failed (${failure.reason})"))
                }
            }, handler)
        }
        val waiter = imageDeferred ?: return
        val image = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { waiter.await() }
        } catch (e: CancellationException) {
            if (!coroutineContext.isActive) throw e
            null
        }
        if (image == null) {
            synchronized(rawWaiters) { rawWaiters.remove(waiter) }
            // A HAL that refuses RAW beside the preview in one request gets
            // the RAW alone next time; the viewfinder then skips that frame.
            if (withPreview && resultDeferred.isCompleted) probeWithPreview = false
            error(if (resultDeferred.isCompleted) "probe without frame" else "probe timed out")
        }
        val sample = try {
            val (sm, scratch) = RawSubsample.of(image, activeCrop, rowScratch)
            rowScratch = scratch
            sm
        } finally {
            image.close()
        }
        val logical = withTimeoutOrNull(PROBE_TIMEOUT_MS) { resultDeferred.await() } ?: error("probe without result")
        val result = lens?.physicalId?.let { logical.physicalCameraTotalResults[it] } ?: logical
        val c = characteristics ?: return
        val lens = lens ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val (black, white) = RawFrameBridge.levels(c, result)
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val w = tone?.weights ?: floatArrayOf(0.25f, 0.5f, 0.25f)
        val spot = afRegion?.let { r -> sample.rectOf(r.x, r.y, r.x + r.width, r.y + r.height) }
        val input = MeterInput(
            width = sample.width.toUInt(),
            height = sample.height.toUInt(),
            pattern = RawMeta.patternOf(lens.cfa),
            blackTile = black.toList(),
            whiteLevel = white,
            wbGains = gains?.let { listOf(it.red.toDouble(), it.greenEven.toDouble(), it.greenOdd.toDouble(), it.blue.toDouble()) },
            weights = Weights(w[0].toDouble(), w[1].toDouble(), w[2].toDouble()),
            spot = spot?.let { RawRect(it[0].toUInt(), it[1].toUInt(), it[2].toUInt(), it[3].toUInt()) },
            data = sample.data,
        )
        val stats = withContext(Dispatchers.Default) { meterRaw(input) }
        if (yuvReader == null) publishProbeHistogram(stats.lumaHist)
        val changed = m.onProbe(Reading(stats.highlight, stats.grey, stats.spotGrey, stats.clipped), iso, ns)
        if (changed) {
            lock.withLock { if (meter === m && !exposure.manual) runCatching { startRepeatingLocked() } }
        }
        publishMetering()
    }

    /** The probe's luma histogram through the paper, when there is no YUV stream to draw one from. */
    private fun publishProbeHistogram(lumaHist: List<UInt>) {
        val gray = tone?.grayTable
        val bins = probeHistogram
        bins.fill(0)
        val n = lumaHist.size
        for (i in 0 until n) {
            val l = i.toFloat() / (n - 1)
            val d = if (gray == null) l else gray[((l * (gray.size - 1)) + 0.5f).toInt().coerceIn(0, gray.size - 1)]
            bins[(d * 63.999f).toInt().coerceIn(0, 63)] += lumaHist[i].toInt()
        }
        val max = bins.max().coerceAtLeast(1).toFloat()
        _histogram.value = FloatArray(64) { bins[it] / max }
    }

    /**
     * Focus and meter at a point given in upright-preview coordinates
     * (0..1, x right, y down as seen on screen). The app's grey meter reads
     * the spot alone while it is set.
     */
    suspend fun focusAt(xNorm: Float, yNorm: Float) = lock.withLock {
        val lens = lens ?: return@withLock
        val c = characteristics ?: return@withLock
        val s = session ?: return@withLock
        val dev = device ?: return@withLock
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@withLock
        // Undo the sensor orientation: the preview is rotated by it.
        val (sx, sy) = when (lens.sensorOrientation) {
            90 -> yNorm to 1f - xNorm
            180 -> 1f - xNorm to 1f - yNorm
            270 -> 1f - yNorm to xNorm
            else -> xNorm to yNorm
        }
        val half = (minOf(active.width(), active.height()) * 0.06f).toInt()
        val cx = active.left + (sx * active.width()).toInt()
        val cy = active.top + (sy * active.height()).toInt()
        val left = (cx - half).coerceIn(active.left, active.right - 2 * half)
        val top = (cy - half).coerceIn(active.top, active.bottom - 2 * half)
        afRegion = MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        _state.update { it.copy(focusPoint = xNorm to yNorm) }

        if (lens.hasAutoFocus) {
            // Cancel any previous lock, switch to single AF on the region and
            // trigger once; the repeating request keeps AUTO so the lock holds.
            afMode = CameraMetadata.CONTROL_AF_MODE_AUTO
            val cancel = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            cancel.addTarget(previewSurface!!)
            applyControls(cancel, still = false)
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(cancel.build(), null, handler)
            startRepeatingLocked()
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(previewSurface!!)
            applyControls(b, still = false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
        } else {
            startRepeatingLocked()
        }
    }

    suspend fun clearFocusPoint() = lock.withLock {
        afRegion = null
        afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        _state.update { it.copy(focusPoint = null) }
        if (session != null) startRepeatingLocked()
    }

    /**
     * One still request. [includePreview] keeps the viewfinder attached;
     * HALs that reject that combination are retried without it.
     */
    private suspend fun fireStill(
        session: CameraCaptureSession,
        dev: CameraDevice,
        reader: ImageReader,
        includePreview: Boolean,
    ): Pair<TotalCaptureResult, Image> {
        val imageDeferred = CompletableDeferred<Image>()
        val resultDeferred = CompletableDeferred<TotalCaptureResult>()
        synchronized(rawWaiters) { rawWaiters.addLast(imageDeferred) }
        var image: Image? = null
        try {
            val still = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            still.addTarget(reader.surface)
            if (includePreview) previewSurface?.let { still.addTarget(it) }
            applyControls(still, still = true, armFlash = flash != FlashMode.Off && lens?.hasFlash == true)
            if (!exposure.manual && meter == null && supports(CaptureRequest.CONTROL_AE_LOCK)) {
                still.set(CaptureRequest.CONTROL_AE_LOCK, true)
            }
            session.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    resultDeferred.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                    synchronized(rawWaiters) { rawWaiters.remove(imageDeferred) }
                    resultDeferred.completeExceptionally(IllegalStateException("capture failed (${failure.reason})"))
                }
            }, handler)
            val logicalResult = withTimeoutOrNull(6000) { resultDeferred.await() } ?: error("no capture result")
            image = withTimeoutOrNull(6000) { imageDeferred.await() } ?: error("no RAW frame")
            return logicalResult to image
        } catch (e: Throwable) {
            synchronized(rawWaiters) { rawWaiters.remove(imageDeferred) }
            if (imageDeferred.isCompleted && !imageDeferred.isCancelled) runCatching { imageDeferred.getCompleted().close() }
            else image?.close()
            throw e
        }
    }

    /**
     * Take one RAW frame. Locks focus (if AF is active) and runs AE
     * precapture when the phone's exposure is automatic, then fires a still
     * request. Under the app's meter there is nothing to converge: the
     * still uses the pair the last probe decided on.
     */
    suspend fun capture(lensLabel: String): RawCapture = lock.withLock {
        val s = session ?: error("camera not running")
        val dev = device ?: error("camera not running")
        val lens = lens ?: error("camera not running")
        val reader = rawReader ?: error("camera not running")
        val c = characteristics ?: error("camera not running")
        _state.update { it.copy(capturing = true) }
        try {
            val auto = !exposure.manual && meter == null
            val flashArmed = auto && flash != FlashMode.Off && lens.hasFlash
            // A tap already pinned the focus in AUTO mode; only continuous AF needs a lock.
            val lockAf = lens.hasAutoFocus && afMode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            if (lockAf || auto) {
                val trig = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                trig.addTarget(previewSurface!!)
                applyControls(trig, still = false, armFlash = flashArmed)
                if (lockAf) trig.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                if (auto) trig.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(trig.build(), previewCallback, handler)
                var sawPrecapture = false
                withTimeoutOrNull(if (flashArmed) 3000 else 1500) {
                    results.first { r ->
                        val af = r.get(CaptureResult.CONTROL_AF_STATE)
                        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
                        val afOk = !lockAf || af == null ||
                            af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                        if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                        val aeOk = when {
                            !auto -> true
                            ae == null -> true
                            flashArmed -> sawPrecapture &&
                                ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE &&
                                ae != CaptureResult.CONTROL_AE_STATE_SEARCHING
                            else -> ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                                ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                        }
                        afOk && aeOk
                    }
                }
            }

            val rotation = Orientation.rotationDegrees(lens.sensorOrientation, deviceRotation)
            val capturedAt = System.currentTimeMillis()
            // Some HALs reject a still that also targets the preview. The RAW
            // reader alone is enough; the preview target is only a courtesy.
            val (logicalResult, image) = try {
                fireStill(s, dev, reader, includePreview = true)
            } catch (e: IllegalStateException) {
                if (e.message?.startsWith("capture failed") != true) throw e
                Log.w(TAG, "still with preview target failed, retrying RAW only", e)
                fireStill(s, dev, reader, includePreview = false)
            }
            // Metadata of the sensor that produced the frame: the physical
            // result when routed, else the logical one.
            val result = lens.physicalId?.let { logicalResult.physicalCameraTotalResults[it] } ?: logicalResult
            val capture = try {
                RawCapture(
                    width = image.width,
                    height = image.height,
                    pixels = RawCapture.copyPixels(image),
                    characteristics = c,
                    result = result,
                    cameraId = lens.sensorId,
                    lensLabel = lensLabel,
                    timestampNs = image.timestamp,
                    rotationDegrees = rotation,
                    capturedAtEpochMs = capturedAt,
                )
            } finally {
                image.close()
            }
            // Release the AF lock and go back to the continuous preview.
            if (lockAf) {
                val cancel = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                cancel.addTarget(previewSurface!!)
                applyControls(cancel, still = false)
                cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                runCatching { s.capture(cancel.build(), null, handler) }
            }
            runCatching { startRepeatingLocked() }
            capture
        } catch (e: CameraAccessException) {
            Log.e(TAG, "capture", e)
            throw IllegalStateException(e.message, e)
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    /** The ISP's colour step of this frame, for the shader; published only when it moved. */
    private fun noteColor(logical: TotalCaptureResult) {
        val result = lens?.physicalId?.let { logical.physicalCameraTotalResults[it] } ?: logical
        val m = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val g = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        if (m == null && g == null) return
        val transform = FloatArray(9) { i ->
            if (m == null) {
                if (i % 4 == 0) 1f else 0f
            } else {
                m.getElement(i % 3, i / 3).toFloat()
            }
        }
        val gains = if (g == null) floatArrayOf(1f, 1f, 1f, 1f) else floatArrayOf(g.red, g.greenEven, g.greenOdd, g.blue)
        val next = IspColor(transform, gains)
        if (!next.near(_ispColor.value)) _ispColor.value = next
    }

    private val bins = IntArray(64)

    /**
     * Histogram of the picture on screen, every frame the YUV reader
     * delivers: the preview decoded (JFIF full range), linearised from the
     * pinned sRGB curve and put through [tone]; plain luma until a tone
     * is set. Subsampled 2x in both directions. Planes are copied out in
     * bulk first: per-sample `ByteBuffer.get` is far too slow here.
     */
    private fun analyse(image: Image) {
        val yP = image.planes[0]
        val w = image.width
        val h = image.height
        val yRs = yP.rowStride
        val yPs = yP.pixelStride
        yBytes = copyPlane(yP.buffer, yBytes)
        val t = tone
        bins.fill(0)
        if (t == null) {
            var y = 0
            while (y < h) {
                var x = 0
                val row = y * yRs
                while (x < w) {
                    bins[(yBytes[row + x * yPs].toInt() and 0xFF) shr 2]++
                    x += 2
                }
                y += 2
            }
        } else {
            val uP = image.planes[1]
            val vP = image.planes[2]
            uBytes = copyPlane(uP.buffer, uBytes)
            vBytes = copyPlane(vP.buffer, vBytes)
            val cRs = uP.rowStride
            val cPs = uP.pixelStride
            val lin = SRGB_LINEAR
            val v0 = t.v[0]
            val v1 = t.v[1]
            val v2 = t.v[2]
            val gray = t.grayTable
            val scale = (gray.size - 1).toFloat()
            var y = 0
            while (y < h) {
                var x = 0
                val yRow = y * yRs
                val cRow = (y shr 1) * cRs
                while (x < w) {
                    val yy = (yBytes[yRow + x * yPs].toInt() and 0xFF).toFloat()
                    val ci = cRow + (x shr 1) * cPs
                    val cb = (uBytes[ci].toInt() and 0xFF) - 128f
                    val cr = (vBytes[ci].toInt() and 0xFF) - 128f
                    val r = (yy + 1.402f * cr).toInt().coerceIn(0, 255)
                    val g = (yy - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772f * cb).toInt().coerceIn(0, 255)
                    val l = (v0 * lin[r] + v1 * lin[g] + v2 * lin[b]).coerceIn(0f, 1f)
                    val d = gray[(l * scale + 0.5f).toInt()]
                    bins[(d * 63.999f).toInt().coerceIn(0, 63)]++
                    x += 2
                }
                y += 2
            }
        }
        val max = bins.max().coerceAtLeast(1).toFloat()
        _histogram.value = FloatArray(64) { bins[it] / max }
    }

    private fun copyPlane(buf: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        val src = buf.duplicate()
        src.rewind()
        val n = src.remaining()
        val out = if (into.size >= n) into else ByteArray(n)
        src.get(out, 0, n)
        return out
    }

    companion object {
        private const val TAG = "CameraController"
        private const val ANALYSIS_PERIOD_MS = 66L
        /** Five probes a second: a scene change settles within a second. */
        private const val PROBE_PERIOD_MS = 200L
        private const val PROBE_TIMEOUT_MS = 1500L
        /** The guard looks at this many probes at a time ... */
        private const val GUARD_WINDOW = 12
        /** ... and stands the meter down when this many of them saw a frame gap. */
        private const val GUARD_GAPS = 6
        private val SRGB_LINEAR = FloatArray(256) { IspControls.decode(it / 255f) }
    }
}
