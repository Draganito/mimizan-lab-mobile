package ch.bojovic.mimizanlab.engine

import android.content.Context
import android.content.Intent
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

/** How a frame is developed; chosen in the viewfinder, changeable in review. */
data class DevelopSettings(
    val separation: SeparationMode = SeparationMode.DUBOIS,
    /** 2 = CFA-preserving 2x binning before ingest (quick mode). */
    val binning: Int = 1,
    val filter: Filter = Filter.NONE,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AsShot,
    /** Save a finished JPEG (reference look, full size) as soon as the frame is developed. */
    val autoJpeg: Boolean = true,
)

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

    /** A developed frame plus the identity it belongs to. */
    class Negative(val name: String, val developed: Developed, val info: DevelopInfo, val settings: DevelopSettings)

    init {
        // Frames from an earlier run are still in the cache: list them so
        // they can be developed again.
        scope.launch {
            val restored = cache.names().mapNotNull { name ->
                runCatching {
                    val meta = RawMeta.fromJson(org.json.JSONObject(cache.metaFile(name).readText()))
                    Shot(name, meta, DevelopSettings(), ShotStatus.Cached)
                }.getOrNull()
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

    /** Develop (again) from the cache, e.g. with other settings. */
    fun redevelop(name: String, settings: DevelopSettings) {
        scope.launch { enqueue(name, settings) }
    }

    private suspend fun enqueue(name: String, settings: DevelopSettings) {
        queueLock.withLock { if (name !in queue) queue.addLast(name) }
        updateShot(name) { it.copy(settings = settings, status = ShotStatus.Queued) }
        DevelopService.start(context)
    }

    suspend fun pendingCount(): Int = queueLock.withLock { queue.size }

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
                val info = developed.info()
                Log.i(TAG, "developed $name in ${(System.nanoTime() - t0) / 1_000_000} ms: ${info.timing}")
                val thumb = thumbnail(developed)
                // Replace the negative kept in memory.
                _current.getAndUpdate { Negative(name, developed, info, shot.settings) }?.developed?.close()
                updateShot(name) { it.copy(status = ShotStatus.Ready(info), thumbnail = thumb) }
                // A second development of the same frame does not add a second JPEG.
                if (shot.settings.autoJpeg && shot.jpegUri == null) {
                    val look = lookWithContrast(baseLook(), prefs.contrast.value)
                    val uri = exportJpeg(
                        developed, name, shot.settings.filter, fullSize = true, look = look,
                        usm = prefs.sharpen.value,
                        deconvolution = prefs.deconvolution.value,
                        deconvPasses = prefs.deconvPasses.value,
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

    /** Same parameters the still uses, so the viewfinder develops the same negative. */
    fun developParams(meta: RawMeta, s: DevelopSettings): DevelopParams = paramsFor(meta, s)

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

    /** Settings look, before the contrast curve: reference, or gamma 2.2. */
    private fun baseLook() = if (prefs.referenceLook.value) lookReference() else lookNeutral()

    private fun thumbnail(d: Developed): Bitmap {
        val g = d.preview(lookWithContrast(baseLook(), prefs.contrast.value), 0u)
        // The preview planes are 2048 px; the thumbnail is a 256 px box.
        val scale = maxOf(1, (maxOf(g.width.toInt(), g.height.toInt()) + 255) / 256)
        val w = g.width.toInt() / scale
        val h = g.height.toInt() / scale
        val px = IntArray(w * h)
        val src = g.data
        val sw = g.width.toInt()
        for (y in 0 until h) {
            val row = y * scale * sw
            for (x in 0 until w) {
                val v = src[row + x * scale].toInt() and 0xFF
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Export through a scratch file (Rust writes paths) into the album.
     * `usm` (0..1.5) sharpens the full-size JPEG; the 2048 px version uses
     * screen compensation instead. `deconvolution` replaces USM on the
     * full-size file and is ignored for the 2048 px JPEG.
     */
    fun exportJpeg(
        d: Developed,
        name: String,
        filter: Filter,
        fullSize: Boolean,
        look: Look = lookReference(),
        usm: Double = 0.0,
        deconvolution: Boolean = false,
        deconvPasses: Int = 2,
    ): Uri {
        val suffix = suffixFor(filter) + if (fullSize) "" else "_2048"
        val tmp = File(context.cacheDir, "$name$suffix.jpg")
        val passes = if (fullSize && deconvolution) deconvPasses.coerceIn(1, 10).toUInt() else 0u
        try {
            d.exportJpeg(tmp.absolutePath, look, if (fullSize) null else 2048u, !fullSize, 0u, if (fullSize) usm else 0.0, fullSize && deconvolution, passes)
            return media.importFile(tmp, "$name$suffix.jpg", MediaStoreWriter.MIME_JPEG)
        } finally {
            tmp.delete()
        }
    }

    fun exportTiff(
        d: Developed,
        name: String,
        filter: Filter,
        look: Look = lookReference(),
        usm: Double = 0.0,
        deconvolution: Boolean = false,
        deconvPasses: Int = 2,
    ): Uri {
        val tmp = File(context.cacheDir, "$name${suffixFor(filter)}.tif")
        val passes = if (deconvolution) deconvPasses.coerceIn(1, 10).toUInt() else 0u
        try {
            d.exportTiff16(tmp.absolutePath, look, 0u, usm, deconvolution, passes)
            return media.importFile(tmp, "$name${suffixFor(filter)}.tif", MediaStoreWriter.MIME_TIFF)
        } finally {
            tmp.delete()
        }
    }

    /** Linear negative + saturation mask, the desktop interchange format. */
    fun exportNegative(d: Developed, name: String): List<Uri> {
        val tmp = File(context.cacheDir, "$name.negative.tif")
        val mask = File(context.cacheDir, "$name.negative.mask.tif")
        try {
            d.exportNegative(tmp.absolutePath)
            val uris = mutableListOf(media.importFile(tmp, "$name.negative.tif", MediaStoreWriter.MIME_TIFF))
            if (mask.exists()) uris += media.importFile(mask, "$name.negative.mask.tif", MediaStoreWriter.MIME_TIFF)
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
    }
}

/** Camera files shipped in `assets/cameras`, matched on make + model. */
class CameraFiles(private val context: Context) {
    private val files: List<Pair<org.json.JSONObject, String>> by lazy {
        val am = context.assets
        (am.list("cameras") ?: emptyArray()).filter { it.endsWith(".json") }.mapNotNull { f ->
            runCatching {
                val text = am.open("cameras/$f").bufferedReader().readText()
                org.json.JSONObject(text) to text
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
