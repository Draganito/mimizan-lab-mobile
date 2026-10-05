package ch.bojovic.mimizanlab.store

import android.content.Context
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

    fun put(name: String, pixels: ByteArray, meta: RawMeta) {
        rawFile(name).writeBytes(pixels)
        metaFile(name).writeText(meta.toJson().toString())
        trim(keep = name)
    }

    fun has(name: String) = rawFile(name).exists() && metaFile(name).exists()

    fun load(name: String): Pair<RawMeta, ByteArray>? {
        if (!has(name)) return null
        val meta = RawMeta.fromJson(JSONObject(metaFile(name).readText()))
        return meta to rawFile(name).readBytes()
    }

    fun remove(name: String) {
        rawFile(name).delete()
        metaFile(name).delete()
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
            if (total <= budgetBytes) break
            if (f.nameWithoutExtension == keep) continue
            total -= f.length()
            remove(f.nameWithoutExtension)
        }
    }
}
