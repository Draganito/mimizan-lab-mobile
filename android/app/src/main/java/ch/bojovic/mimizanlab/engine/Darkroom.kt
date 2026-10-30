package ch.bojovic.mimizanlab.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import ch.bojovic.mimizanlab.raw.RawCapture
import ch.bojovic.mimizanlab.raw.RawMeta
import ch.bojovic.mimizanlab.store.MediaStoreWriter
import ch.bojovic.mimizanlab.store.Prefs
import ch.bojovic.mimizanlab.store.RawCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The darkroom's two processes: the monochrome negative, or the
 * three-colour plate (Prokudin-Gorsky's principle: three mixes of the
 * same separation, printed through the same paper into one RGB file).
 * A re-mix, not a re-development: the separation is the same.
 */
enum class Process { MONO, PLATE }

/** How a frame is developed; chosen in the viewfinder, changeable in review. */
data class DevelopSettings(
    val separation: SeparationMode = SeparationMode.DUBOIS,
    /** 2 = CFA-preserving 2x binning before ingest (quick mode). */
    val binning: Int = 1,
    val filter: Filter = Filter.NONE,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AsShot,
    /** Save a finished JPEG (the paper at the settings exposures, full size) as soon as the frame is developed. */
    val autoJpeg: Boolean = true,
    val process: Process = Process.MONO,
    /** The plate's taking filters (only read in the plate process). */
    val plateFilters: PlateFilters = PlateFilters.CAMERA,
    /** The plate's primaries, the colour space of the file. */
    val plateSpace: PlateSpace = PlateSpace.SRGB,
) {
    val isPlate: Boolean get() = process == Process.PLATE
}

sealed interface ShotStatus {
    data object Saving : ShotStatus
    /** Frame in the cache (earlier run), negative not in memory. */
    data object Cached : ShotStatus
    data object Queued : ShotStatus
    data object Developing : ShotStatus
    data class Ready(val info: DevelopInfo) : ShotStatus
    data class Failed(val message: String) : ShotStatus
}

data class Shot(
    val name: String,
    val meta: RawMeta,
    val settings: DevelopSettings,
    val status: ShotStatus,
    val jpegUri: Uri? = null,
    val thumbnail: Bitmap? = null,
)

/**
 * Process-wide state of the roll: what was shot, what is developed and the
 * one [Developed] negative that is kept in memory (the rest can be developed
 * again from the [RawCache]). The raw frame lives only in the app cache; the
 * gallery receives finished pictures. The [DevelopService] drains [runNext].
 */
class Darkroom private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cache = RawCache(context)
    val media = MediaStoreWriter(context)
    val cameraFiles = CameraFiles(context)
    val prefs = Prefs(context)

    private val _shots = MutableStateFlow<List<Shot>>(emptyList())
    /** Newest first. */
    val shots: StateFlow<List<Shot>> = _shots.asStateFlow()

    private val _cacheBytes = MutableStateFlow(0L)
    /** Size of the raw cache on disk. */
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    private val _current = MutableStateFlow<Negative?>(null)
    /** The developed negative held in memory (at most one). */
    val current: StateFlow<Negative?> = _current.asStateFlow()

    private val queue = ArrayDeque<String>()
    private val queueLock = Mutex()
    private val developLock = Mutex()

    /**
     * A developed frame plus the identity it belongs to, and Peak's radius
     * and amount as measured on it (null when the measurement failed).
     */
    class Negative(
        val name: String,
        val developed: Developed,
        val info: DevelopInfo,
        val settings: DevelopSettings,
        val peak: PeakMeasured?,
    )

    init {
        // Frames from an earlier run are still in the cache: list them so
        // they can be developed again.
        scope.launch {
            val restored = cache.names().mapNotNull { name ->
                cache.loadMeta(name)?.let {
                    Shot(name, it, DevelopSettings(), ShotStatus.Cached, thumbnail = cache.loadThumb(name))
                }
            }
            if (restored.isNotEmpty()) _shots.update { restored + it }
            refreshCacheSize()
        }
    }

    /** Called from the camera: persists the frame, then queues the development. */
    fun submit(capture: RawCapture, settings: DevelopSettings) {
        val name = nameFor(capture.capturedAtEpochMs)
        val meta = capture.meta()
        _shots.update { listOf(Shot(name, meta, settings, ShotStatus.Saving)) + it }
        scope.launch(Dispatchers.IO) {
            try {
                cache.put(name, capture.pixels, meta)
            } catch (t: Throwable) {
                Log.e(TAG, "save $name", t)
                updateShot(name) { it.copy(status = ShotStatus.Failed(t.message ?: "could not save the frame")) }
                return@launch
            }
            // The cache may have dropped old frames to stay in budget.
            syncWithCache()
            enqueue(name, settings)
        }
    }

    /**
     * Forget a frame: raw + meta leave the cache, the negative leaves memory
     * if it is this one. Pictures already in the gallery stay.
     */
    fun delete(name: String) {
        scope.launch(Dispatchers.IO) {
            queueLock.withLock { queue.remove(name) }
            developLock.withLock {
                cache.remove(name)
                val prev = _current.getAndUpdate { if (it?.name == name) null else it }
                if (prev?.name == name) prev.developed.close()
                _shots.update { list -> list.filterNot { it.name == name } }
            }
            refreshCacheSize()
        }
    }

    /** Empty the roll: every cached frame goes, the gallery is untouched. */
    fun deleteAll() {
        scope.launch(Dispatchers.IO) {
            queueLock.withLock { queue.clear() }
            developLock.withLock {
                cache.removeAll()
                _current.getAndUpdate { null }?.developed?.close()
                _shots.value = emptyList()
            }
            refreshCacheSize()
        }
    }

    /** Drop list entries whose frame the cache has trimmed away. */
    private fun syncWithCache() {
        val names = cache.names().toSet()
        _shots.update { list -> list.filter { it.name in names || it.status == ShotStatus.Saving } }
        refreshCacheSize()
    }

    private fun refreshCacheSize() {
        _cacheBytes.value = cache.sizeBytes()
    }

    /** Fresh settings in the standing process (what a frame from an earlier run is developed in). */
    fun standingSettings(): DevelopSettings = withStandingProcess(DevelopSettings())

    /** `s` in the process the darkroom stands in now (a second development follows the switch made in review). */
    fun withStandingProcess(s: DevelopSettings): DevelopSettings = s.copy(
        process = prefs.process.value,
        plateFilters = prefs.plateFilters.value,
        plateSpace = prefs.plateSpace.value,
    )

    /** Develop (again) from the cache, e.g. with other settings. */
    fun redevelop(name: String, settings: DevelopSettings) {
        scope.launch { enqueue(name, settings) }
    }

    private suspend fun enqueue(name: String, settings: DevelopSettings) {
        queueLock.withLock { if (name !in queue) queue.addLast(name) }
        updateShot(name) { it.copy(settings = settings, status = ShotStatus.Queued) }
        DevelopService.start(context)
    }

    /** Develop the next queued frame; false when the queue is empty. */
    suspend fun runNext(onProgress: (String) -> Unit): Boolean {
        val name = queueLock.withLock { queue.removeFirstOrNull() } ?: return false
        val shot = _shots.value.firstOrNull { it.name == name } ?: return true
        onProgress(name)
        developLock.withLock {
            updateShot(name) { it.copy(status = ShotStatus.Developing) }
            try {
                val (meta, pixels) = withContext(Dispatchers.IO) { cache.load(name) } ?: error("frame not in cache")
                val input = meta.toRawInput(pixels)
                val params = paramsFor(meta, shot.settings)
                val t0 = System.nanoTime()
                val developed = develop(input, params)
                if (shot.settings.isPlate) {
                    // The plate is a re-mix of the separation just made; the
                    // lamps start from the settings, as the paper does.
                    val w = developed.setPlate(shot.settings.plateFilters, shot.settings.plateSpace)
                    developed.setLamps(0.0, 0.0)
                    Log.i(TAG, "plate ${w.filters}: R ${w.red} G ${w.green} B ${w.blue}")
                }
                val info = developed.info()
                Log.i(TAG, "developed $name in ${(System.nanoTime() - t0) / 1_000_000} ms: ${info.timing}")
                val thumb = thumbnail(developed)
                cache.saveThumb(name, thumb)
                // Peak's radius and amount for this negative, as the
                // desktop measures them on opening a picture. Measured even
                // with the checkbox off: review can turn it on without a
                // second pass.
                val peak = measurePeak(developed, name)
                // Replace the negative kept in memory.
                _current.getAndUpdate { Negative(name, developed, info, shot.settings, peak) }?.developed?.close()
                updateShot(name) { it.copy(status = ShotStatus.Ready(info), thumbnail = thumb) }
                // A second development of the same frame does not add a second JPEG.
                if (shot.settings.autoJpeg && shot.jpegUri == null) {
                    val uri = exportJpeg(
                        developed, name, suffixFor(shot.settings, info),
                        soft = prefs.soft.value, hard = prefs.hard.value,
                        peakRadius = peak?.radius ?: Prefs.PEAK.radiusDefault,
                        peakAmount = if (prefs.peak.value) peak?.amount ?: 0.0 else 0.0,
                    )
                    updateShot(name) { it.copy(jpegUri = uri) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "develop $name", t)
                updateShot(name) { it.copy(status = ShotStatus.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
        return true
    }

    /** The same, before any frame exists: camera file by device and sensor CFA. */
    fun developParamsFor(make: String, model: String, cfa: Int, s: DevelopSettings): DevelopParams {
        val base = defaultDevelopParams()
        return base.copy(
            separation = s.separation,
            binning = s.binning.toUInt(),
            filter = s.filter,
            whiteBalance = s.whiteBalance,
            cameraFileJson = cameraFiles.forModel(make, model, RawMeta.patternOf(cfa)),
        )
    }

    private fun paramsFor(meta: RawMeta, s: DevelopSettings): DevelopParams =
        developParamsFor(meta.make, meta.model, meta.cfa, s)

    /** 256 px box of the preview, grey or, in the plate process, colour. */
    private fun thumbnail(d: Developed): Bitmap {
        val soft = prefs.soft.value
        val hard = prefs.hard.value
        return if (d.info().plate != null) {
            val g = d.previewRgb(soft, hard, 0u)
            subsampled(g.width.toInt(), g.height.toInt()) { i ->
                val r = g.data[3 * i].toInt() and 0xFF
                val gg = g.data[3 * i + 1].toInt() and 0xFF
                val b = g.data[3 * i + 2].toInt() and 0xFF
                (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
            }
        } else {
            val g = d.preview(soft, hard, 0u)
            subsampled(g.width.toInt(), g.height.toInt()) { i ->
                val v = g.data[i].toInt() and 0xFF
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    /** Every `scale`-th pixel of a `sw × sh` picture so the longer edge fits 256 px; [argb] reads pixel index `i`. */
    private inline fun subsampled(sw: Int, sh: Int, argb: (Int) -> Int): Bitmap {
        // The preview planes are 2048 px; the thumbnail is a 256 px box.
        val scale = maxOf(1, (maxOf(sw, sh) + 255) / 256)
        val w = sw / scale
        val h = sh / scale
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * scale * sw
            for (x in 0 until w) px[y * w + x] = argb(row + x * scale)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Peak's radius and amount on `d` at the settings exposures; null (and
     * a log line) when the measurement throws, so a development never
     * fails on its sharpening.
     */
    private fun measurePeak(d: Developed, name: String): PeakMeasured? = try {
        val t0 = System.nanoTime()
        val m = d.measurePeak(prefs.soft.value, prefs.hard.value)
        Log.i(TAG, "peak $name: radius ${m.radius} px, amount ${m.amount}, found ${m.found}, ${(System.nanoTime() - t0) / 1_000_000} ms")
        m
    } catch (t: Throwable) {
        Log.w(TAG, "peak $name", t)
        null
    }

    /**
     * The full-size JPEG into the album, Peak's high-pass applied when
     * `peakAmount` > 0 (`peakRadius` in pixels of the negative). In the
     * plate process the file is RGB with the plate's profile. `suffix`
     * names the filter or the plate in the file name ([suffixFor]).
     */
    fun exportJpeg(
        d: Developed,
        name: String,
        suffix: String,
        soft: Double = 0.0,
        hard: Double = 0.0,
        peakRadius: Double = Prefs.PEAK.radiusDefault,
        peakAmount: Double = 0.0,
    ): Uri = exportPrint(name, suffix, "jpg", MediaStoreWriter.MIME_JPEG) { path ->
        d.exportJpeg(path, soft, hard, 0u, peakRadius, peakAmount)
    }

    /** The same picture as a 16-bit TIFF (RGB for a plate). */
    fun exportTiff(
        d: Developed,
        name: String,
        suffix: String,
        soft: Double = 0.0,
        hard: Double = 0.0,
        peakRadius: Double = Prefs.PEAK.radiusDefault,
        peakAmount: Double = 0.0,
    ): Uri = exportPrint(name, suffix, "tif", MediaStoreWriter.MIME_TIFF) { path ->
        d.exportTiff16(path, soft, hard, 0u, peakRadius, peakAmount)
    }

    /** Rust writes to a path: a scratch file in the app cache, then the copy into the album. */
    private fun exportPrint(name: String, suffix: String, ext: String, mime: String, write: (String) -> Unit): Uri {
        val fileName = "$name$suffix.$ext"
        val tmp = File(context.cacheDir, fileName)
        try {
            write(tmp.absolutePath)
            return media.importFile(tmp, fileName, mime)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Linear negative + saturation mask, the desktop interchange format. In
     * the plate process the stacked three-frame plate (`<name>.plate.tif`),
     * which `mimizan print` prints in colour.
     */
    fun exportNegative(d: Developed, name: String): List<Uri> {
        val stem = if (d.info().plate != null) "$name.plate" else "$name.negative"
        val tmp = File(context.cacheDir, "$stem.tif")
        val mask = File(context.cacheDir, "$stem.mask.tif")
        try {
            d.exportNegative(tmp.absolutePath)
            val uris = mutableListOf(media.importFile(tmp, "$stem.tif", MediaStoreWriter.MIME_TIFF))
            if (mask.exists()) uris += media.importFile(mask, "$stem.mask.tif", MediaStoreWriter.MIME_TIFF)
            return uris
        } finally {
            tmp.delete(); mask.delete()
        }
    }

    private fun updateShot(name: String, f: (Shot) -> Shot) {
        _shots.update { list -> list.map { if (it.name == name) f(it) else it } }
    }

    companion object {
        private const val TAG = "Darkroom"
        @Volatile private var instance: Darkroom? = null
        fun get(context: Context): Darkroom =
            instance ?: synchronized(this) { instance ?: Darkroom(context.applicationContext).also { instance = it } }

        private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fun nameFor(epochMs: Long) = "MLM_" + stamp.format(Date(epochMs))

        fun suffixFor(filter: Filter): String = when (filter) {
            Filter.NONE -> ""
            Filter.YELLOW8 -> "_Y8"
            Filter.YELLOW_GREEN11 -> "_YG11"
            Filter.ORANGE16 -> "_O16"
            Filter.RED25 -> "_R25"
            Filter.GREEN58 -> "_G58"
            Filter.BLUE47 -> "_B47"
        }

        /** The plate in the file name, by the set the frame resolved to. */
        fun suffixFor(plate: PlateState): String = when (plate.weights.filters) {
            PlateFilters.CAMERA -> "_plate"
            PlateFilters.WRATTEN -> "_plate_wratten"
            PlateFilters.CHANNELS -> "_plate_channels"
        }

        /** The file-name suffix of a development as it stands: the plate it is in, else its filter. */
        fun suffixFor(settings: DevelopSettings, info: DevelopInfo): String =
            info.plate?.let { suffixFor(it) } ?: suffixFor(settings.filter)
    }
}

/** Camera files shipped in `assets/cameras`, matched on make + model. */
class CameraFiles(private val context: Context) {
    private val files: List<Pair<JSONObject, String>> by lazy {
        val am = context.assets
        (am.list("cameras") ?: emptyArray()).filter { it.endsWith(".json") }.mapNotNull { f ->
            runCatching {
                val text = am.open("cameras/$f").bufferedReader().readText()
                JSONObject(text) to text
            }.getOrNull()
        }
    }

    /**
     * The file for this make/model whose `bayer_phase` matches the frame
     * (the core refuses a mismatch, so a wrong file is worse than none).
     */
    fun forModel(make: String, model: String, pattern: BayerPattern): String? {
        val m = make.trim().lowercase()
        val mo = model.trim().lowercase()
        return files.firstOrNull { (o, _) ->
            o.optString("make").trim().lowercase() == m &&
                mo.startsWith(o.optString("model").trim().lowercase()) &&
                o.optString("bayer_phase", pattern.name).equals(pattern.name, ignoreCase = true)
        }?.second
    }
}
