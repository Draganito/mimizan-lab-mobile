package ch.bojovic.mimizanlab.store

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Writes finished files into `Pictures/Mimizan Lab` through MediaStore. */
class MediaStoreWriter(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** Stream [write] into a new MediaStore image item; returns its Uri. */
    fun create(displayName: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** Copy a finished file (Rust writes to paths) into the album. */
    fun importFile(file: File, displayName: String, mime: String): Uri =
        create(displayName, mime) { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }

    companion object {
        const val ALBUM = "Mimizan Lab"
        const val MIME_DNG = "image/x-adobe-dng"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_TIFF = "image/tiff"
    }
}
