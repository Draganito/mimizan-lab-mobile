package ch.bojovic.mimizanlab.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ch.bojovic.mimizanlab.raw.RawMeta
import java.io.File
import org.json.JSONObject

/**
 * App-private copy of every frame (`<name>.raw` = packed 16-bit samples,
 * `<name>.json` = [RawMeta]) so a shot can be developed again later without
 * a DNG decoder on the phone. Oldest frames go first when the cache exceeds
 * [budgetBytes].
 */
class RawCache(context: Context, private val budgetBytes: Long = 1_500L shl 20) {
    private val dir = File(context.cacheDir, "raw").apply { mkdirs() }

    fun rawFile(name: String) = File(dir, "$name.raw")
    fun metaFile(name: String) = File(dir, "$name.json")
    /** The developed preview, already reduced, kept so the roll can show it without developing again. */
    fun thumbFile(name: String) = File(dir, "$name.jpg")

    /**
     * Pixels first, metadata last and through a rename: a frame only counts
     * ([has], [names]) once both files are complete, so a crash mid-write
     * leaves at most an orphan `.raw` that the next [trim] sweeps away.
     */
    fun put(name: String, pixels: ByteArray, meta: RawMeta) {
        rawFile(name).writeBytes(pixels)
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(meta.toJson().toString())
        if (!tmp.renameTo(metaFile(name))) {
            tmp.delete()
            error("could not store the frame's metadata")
        }
        trim(keep = name)
    }

    fun has(name: String) = rawFile(name).exists() && metaFile(name).exists()

    /** The metadata alone; null when the frame is not cached or its file is damaged. */
    fun loadMeta(name: String): RawMeta? {
        if (!has(name)) return null
        return runCatching { RawMeta.fromJson(JSONObject(metaFile(name).readText())) }.getOrNull()
    }

    fun load(name: String): Pair<RawMeta, ByteArray>? {
        val meta = loadMeta(name) ?: return null
        return meta to rawFile(name).readBytes()
    }

    /** A JPEG of the thumbnail the development already made. Nothing is decoded from the raw. */
    fun saveThumb(name: String, bitmap: Bitmap) {
        thumbFile(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 55, it) }
    }

    fun loadThumb(name: String): Bitmap? {
        val f = thumbFile(name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    fun remove(name: String) {
        rawFile(name).delete()
        metaFile(name).delete()
        thumbFile(name).delete()
    }

    fun removeAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Bytes on disk for all cached frames. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Names of cached frames, newest first. */
    fun names(): List<String> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    private fun trim(keep: String) {
        val raws = dir.listFiles { f -> f.extension == "raw" }?.sortedBy { it.lastModified() } ?: return
        var total = raws.sumOf { it.length() }
        for (f in raws) {
            val name = f.nameWithoutExtension
            if (name == keep) continue
            // An orphan from an interrupted put() is never a frame; it goes regardless of the budget.
            if (total <= budgetBytes && metaFile(name).exists()) continue
            total -= f.length()
            remove(name)
        }
    }
}
