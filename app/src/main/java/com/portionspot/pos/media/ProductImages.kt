package com.portionspot.pos.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * On-device store + processing for product images.
 *
 * A picked image is decoded, downscaled to a modest max edge and re-compressed to
 * JPEG before it is written into app-internal storage. This keeps both the local
 * file AND the eventual Supabase Storage upload small — important on Zimbabwean
 * mobile data, and plenty for a POS product card. The returned absolute path is
 * stored on the [com.portionspot.pos.data.Item] as `imageLocalPath`; the same file
 * is the source of bytes uploaded to Storage on sync push.
 */
object ProductImages {

    private const val DIR = "product_images"
    private const val MAX_EDGE = 800          // px, longest side after downscale
    private const val JPEG_QUALITY = 80

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** The Supabase Storage object key for an item: `<businessId>/<itemId>.jpg`. */
    fun objectPath(businessId: String, itemId: String): String = "$businessId/$itemId.jpg"

    /** The MIME type every product image is stored/uploaded as. */
    const val CONTENT_TYPE = "image/jpeg"

    /**
     * Copy [sourceUri] into internal storage, downscaled + JPEG-compressed. Returns
     * the absolute file path, or null if the image could not be read/decoded.
     */
    fun saveLocalCopy(context: Context, sourceUri: Uri): String? {
        val bitmap = decodeDownscaled(context, sourceUri) ?: return null
        return try {
            val out = File(dir(context), "${UUID.randomUUID()}.jpg")
            FileOutputStream(out).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            out.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Read the bytes of a locally-cached image for upload. Null if missing/unreadable. */
    fun readBytes(path: String?): ByteArray? {
        val f = path?.let { File(it) } ?: return null
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    /** Best-effort delete of a local cached copy (on remove/replace). */
    fun deleteLocal(path: String?) {
        path?.let { runCatching { File(it).delete() } }
    }

    /** Decode [uri] with an inSampleSize chosen so the longest side is ~[MAX_EDGE]. */
    private fun decodeDownscaled(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        // Pass 1: bounds only, to compute the sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        var longest = maxOf(w, h)
        while (longest / sample > MAX_EDGE) sample *= 2

        // Pass 2: decode at the reduced resolution.
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
}
