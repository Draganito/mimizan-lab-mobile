package ch.bojovic.mimizanlab.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
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
import ch.bojovic.mimizanlab.raw.Orientation
import ch.bojovic.mimizanlab.raw.RawCapture
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

/**
 * Camera2 session: a preview stream (SurfaceTexture) with the ISP pinned
 * to a known rendering ([IspControls]), a small YUV stream for the
 * histogram, and a RAW_SENSOR reader for the still. The viewfinder shader
 * turns the preview back into the negative with [ispColor] of each frame;
 * [tone] lets the histogram do the same arithmetic on the CPU.
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

    private val _state = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = _state.asStateFlow()

    /**
     * 64-bin histogram of the viewfinder picture (normalised to the max
     * bin): the negative's display values when [tone] is set, else ISP luma.
     */
    private val _histogram = MutableStateFlow(FloatArray(64))
    val histogram: StateFlow<FloatArray> = _histogram.asStateFlow()

    /** Colour step the ISP applied to the latest preview frame. */
    private val _ispColor = MutableStateFlow<IspColor?>(null)
    val ispColor: StateFlow<IspColor?> = _ispColor.asStateFlow()

    /** Set by the screen's owner; the histogram follows it. */
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
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val aspect = lens.rawSize.width.toFloat() / lens.rawSize.height
        return sizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width <= 1600 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { abs(it.width.toFloat() / it.height - aspect) }
            ?: Size(1280, 960)
    }

    private fun analysisSizeFor(lens: LensInfo): Size {
        val c = manager.getCameraCharacteristics(lens.sensorId)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val aspect = lens.rawSize.width.toFloat() / lens.rawSize.height
        return sizes
            .filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f && it.width >= 240 }
            .minByOrNull { it.width.toLong() * it.height }
            ?: Size(320, 240)
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
        characteristics = manager.getCameraCharacteristics(lens.sensorId)
        // Request keys are judged by the camera the requests go to.
        isp = IspControls(manager.getCameraCharacteristics(lens.cameraId)).also { Log.i(TAG, "isp: ${it.description}") }
        _ispColor.value = null

        rawReader = ImageReader.newInstance(lens.rawSize.width, lens.rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val an = analysisSizeFor(lens)
        yuvReader = ImageReader.newInstance(an.width, an.height, ImageFormat.YUV_420_888, 2).apply {
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
        orientationListener.enable()

        val dev = openDevice(lens.cameraId)
        device = dev
        val outputs = listOf(previewSurface!!, yuvReader!!.surface, rawReader!!.surface)
        session = createSession(dev, outputs, lens.physicalId)
        _state.update { it.copy(lens = lens, previewSize = previewSize, error = null, focusPoint = null, flash = flash) }
        afRegion = null
        afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        startRepeatingLocked()
    }

    suspend fun stop() = lock.withLock {
        orientationListener.disable()
        closeSessionLocked()
    }

    fun release() {
        thread.quitSafely()
        analysisThread.quitSafely()
    }

    private fun closeSessionLocked() {
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
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("session configuration failed"))
                    }
                },
            )
            dev.createCaptureSession(config)
        }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            noteColor(result)
            results.tryEmit(result)
            if (++frameCounter % 3 != 0) return
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
        if (exposure.manual && lens.hasManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso!!.coerceIn(lens.isoRange.lower, lens.isoRange.upper))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureNs!!.coerceIn(lens.exposureRangeNs.lower, lens.exposureRangeNs.upper))
            // Keep the preview watchable at long exposures: 1/15 s frame cap.
            if (!still) b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure.exposureNs!!, 33_333_333L))
            // With AE off the automatics cannot decide; On fires once for the still.
            val single = still && armFlash && flash == FlashMode.On && lens.hasFlash
            b.set(CaptureRequest.FLASH_MODE, if (single) CameraMetadata.FLASH_MODE_SINGLE else CameraMetadata.FLASH_MODE_OFF)
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
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }
    }

    private fun startRepeatingLocked() {
        val s = session ?: return
        val dev = device ?: return
        val preview = previewSurface ?: return
        val yuv = yuvReader?.surface ?: return
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(preview)
        b.addTarget(yuv)
        applyControls(b, still = false)
        s.setRepeatingRequest(b.build(), previewCallback, handler)
    }

    suspend fun setExposure(e: ExposureSettings) = lock.withLock {
        exposure = e
        if (session != null) startRepeatingLocked()
    }

    fun currentExposure(): ExposureSettings = exposure

    suspend fun setFlash(mode: FlashMode) = lock.withLock {
        flash = mode
        _state.update { it.copy(flash = mode) }
    }

    /**
     * Focus and meter at a point given in upright-preview coordinates
     * (0..1, x right, y down as seen on screen).
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
     * Take one RAW frame. Locks focus (if AF is active) and runs AE
     * precapture when the exposure is automatic, then fires a still request.
     */
    suspend fun capture(lensLabel: String): RawCapture = lock.withLock {
        val s = session ?: error("camera not running")
        val dev = device ?: error("camera not running")
        val lens = lens ?: error("camera not running")
        val reader = rawReader ?: error("camera not running")
        val c = characteristics ?: error("camera not running")
        _state.update { it.copy(capturing = true) }
        try {
            val auto = !exposure.manual
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

            val imageDeferred = CompletableDeferred<Image>()
            val resultDeferred = CompletableDeferred<TotalCaptureResult>()
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireNextImage()
                if (img != null && !imageDeferred.complete(img)) img.close()
            }, handler)

            val still = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            still.addTarget(reader.surface)
            still.addTarget(previewSurface!!)
            applyControls(still, still = true, armFlash = flash != FlashMode.Off && lens.hasFlash)
            if (auto) still.set(CaptureRequest.CONTROL_AE_LOCK, true)
            val rotation = Orientation.rotationDegrees(lens.sensorOrientation, deviceRotation)
            val capturedAt = System.currentTimeMillis()
            s.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    resultDeferred.complete(result)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                    resultDeferred.completeExceptionally(IllegalStateException("capture failed (${failure.reason})"))
                }
            }, handler)

            val logicalResult = withTimeoutOrNull(6000) { resultDeferred.await() } ?: error("no capture result")
            val image = withTimeoutOrNull(6000) { imageDeferred.await() } ?: error("no RAW frame")
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
            reader.setOnImageAvailableListener(null, null)
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
        private val SRGB_LINEAR = FloatArray(256) { IspControls.decode(it / 255f) }
    }
}
