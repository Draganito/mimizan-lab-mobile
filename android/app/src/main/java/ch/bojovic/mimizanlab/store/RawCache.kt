package ch.bojovic.mimizanlab.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ch.bojovic.mimizanlab.engine.DevelopSettings
import ch.bojovic.mimizanlab.raw.RawMeta
import java.io.File
import org.json.JSONObject

/**
 * App-private copy of every frame (`<name>.raw` = packed 16-bit samples,
 * `<name>.json` = [RawMeta], `<name>.grade` = the [Recipe] (the print,
 * the process and the filter), and once developed `<name>.jpg` = the
 * roll's thumbnail) so a shot can
 * be developed again later without a DNG decoder on the phone. Oldest
 * frames go first when the cache exceeds [budgetBytes].
 */
class RawCache(context: Context, private val budgetBytes: Long = 1_500L shl 20) {
    private val dir = File(context.cacheDir, "raw").apply { mkdirs() }

    fun rawFile(name: String) = File(dir, "$name.raw")
    fun metaFile(name: String) = File(dir, "$name.json")
    /** This frame's recipe, as the developer last left it: the print, the process and the filter. */
    fun printFile(name: String) = File(dir, "$name.grade")
    /** The developed preview, already reduced, kept so the roll can show it without developing again. */
    fun thumbFile(name: String) = File(dir, "$name.jpg")

    /**
     * Pixels first, metadata last and through a rename: a frame only counts
     * ([has], [names]) once both files are complete, so a crash mid-write
     * leaves at most an orphan `.raw` that the next [trim] sweeps away.
     * [protect] are frames the trim must not evict whatever the budget
     * says: those queued or under development right now.
     */
    fun put(name: String, pixels: ByteArray, meta: RawMeta, print: Print, settings: DevelopSettings, protect: Set<String> = emptySet()) {
        rawFile(name).writeBytes(pixels)
        saveRecipe(name, print, settings)
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(meta.toJson().toString())
        if (!tmp.renameTo(metaFile(name))) {
            tmp.delete()
            error("could not store the frame's metadata")
        }
        trim(keep = protect + name)
    }

    /** Overwritten in place: the file is a few numbers and names, and the old ones are no loss. */
    fun saveRecipe(name: String, print: Print, settings: DevelopSettings) {
        printFile(name).writeText(Recipe.toJson(print, settings).toString())
    }

    /**
     * The frame's recipe; null when none was written (a frame from before
     * 0.15) or the file is damaged. A file with the grade alone (0.15.0)
     * takes [standingPeak]; one without the process and filter reads as
     * Mono, Pan.
     */
    fun loadRecipe(name: String, standingPeak: Peak): Pair<Print, DevelopSettings>? {
        val f = printFile(name)
        if (!f.exists()) return null
        return Recipe.fromJson(f.readText(), Prefs.STOP_LIMIT, Prefs.PEAK, standingPeak)
    }

    fun has(name: String) = rawFile(name).exists() && metaFile(name).exists()

    /**
     * The metadata alone; null when the frame is not cached. A `.json` that
     * is there but does not decode (schema change, cut-off write) is moved
     * beside itself as `<name>.bad-<stamp>.json`, twin of iOS
     * `FormatDump.readOrSetAside`, so the damaged file is not lost and the
     * roll no longer lists a frame it cannot open.
     */
    fun loadMeta(name: String): RawMeta? {
        if (!has(name)) return null
        val f = metaFile(name)
        return try {
            RawMeta.fromJson(JSONObject(f.readText()))
        } catch (t: Throwable) {
            val aside = File(dir, "$name.bad-${System.currentTimeMillis() / 1000}.json")
            if (!f.renameTo(aside)) {
                Log.w(TAG, "could not set aside damaged ${f.name}", t)
            } else {
                Log.w(TAG, "${f.name} did not decode; set aside as ${aside.name}", t)
            }
            null
        }
    }

    fun load(name: String): Pair<RawMeta, ByteArray>? {
        val meta = loadMeta(name) ?: return null
        return meta to rawFile(name).readBytes()
    }

    /**
     * A JPEG of the thumbnail the development already made. Nothing is
     * decoded from the raw. Written beside and renamed into place, so a
     * crash mid-write leaves a `.tmp` the next [trim] sweeps away, never a
     * half picture.
     */
    fun saveThumb(name: String, bitmap: Bitmap) {
        val tmp = File(dir, "$name.jpg.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 55, it) }
        if (!tmp.renameTo(thumbFile(name))) {
            tmp.delete()
            error("could not store the frame's thumbnail")
        }
    }

    fun loadThumb(name: String): Bitmap? {
        val f = thumbFile(name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    fun remove(name: String) {
        rawFile(name).delete()
        metaFile(name).delete()
        printFile(name).delete()
        thumbFile(name).delete()
    }

    fun removeAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Bytes on disk for all cached frames. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Names of cached frames (both files present), newest first. */
    fun names(): List<String> =
        dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?.filter(::has)
            ?: emptyList()

    /**
     * Oldest frames first until the directory fits the budget. The budget
     * counts everything in the directory: pixels, metadata and thumbnails.
     * Leftovers of an interrupted write (`.tmp` older than a minute, so a
     * thumbnail being written right now is left alone) go first. A `.raw`
     * without its metadata is never a frame, but under a minute it may
     * still be the write in flight (twin of iOS `orphanAge`), so it stays
     * until the budget forces it out.
     */
    private fun trim(keep: Set<String>) {
        val all = dir.listFiles() ?: return
        var total = all.sumOf { it.length() }
        val stale = System.currentTimeMillis() - 60_000L
        for (f in all.filter { it.name.endsWith(".tmp") && it.lastModified() < stale }) {
            total -= f.length()
            f.delete()
        }
        val raws = all.filter { it.extension == "raw" }.sortedBy { it.lastModified() }
        for (f in raws) {
            val name = f.nameWithoutExtension
            if (name in keep) continue
            val hasMeta = metaFile(name).exists()
            if (total <= budgetBytes && hasMeta) continue
            if (total <= budgetBytes && !hasMeta && f.lastModified() >= stale) continue
            total -= f.length() + metaFile(name).length() + printFile(name).length() + thumbFile(name).length()
            remove(name)
        }
        // Sidecars whose frame is gone (a `.raw` swept earlier, a write
        // that landed after a `removeAll`) are never a frame either.
        val names = raws.mapTo(HashSet()) { it.nameWithoutExtension }
        for (f in all) {
            if (f.extension == "raw" || f.name.endsWith(".tmp")) continue
            val name = f.nameWithoutExtension
            if (name !in keep && name !in names && f.lastModified() < stale) f.delete()
        }
    }

    companion object {
        private const val TAG = "RawCache"
    }
}
