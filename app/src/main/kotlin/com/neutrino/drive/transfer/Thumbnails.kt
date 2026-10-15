package com.neutrino.drive.transfer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.neutrino.drive.core.api.NeutrinoApi
import com.neutrino.drive.core.crypto.B64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The cover thumbnail that rides along with an encrypted upload.
 *
 * Made here because nobody else can: the server holds ciphertext. Matches the web's
 * `generateThumbnail` and iOS's `ThumbnailGenerator` on everything a consumer can observe — JPEG,
 * longest edge at most 512 px, never upscaled, quality 0.8, standard base64 with no `data:` prefix,
 * EXIF orientation applied. Videos get a poster frame one second in (the midpoint of anything
 * shorter than two seconds), so a clip and a photo produce the same kind of tile.
 *
 * Returning `null` is always acceptable: a missing thumbnail must never cost the user an upload.
 */
object ThumbnailGenerator {
    const val MAX_PIXELS = 512
    private const val QUALITY = 80

    fun forUri(resolver: ContentResolver, uri: Uri, mimeType: String): String? = runCatching {
        when {
            mimeType.startsWith("image/") -> image(resolver, uri)
            mimeType.startsWith("video/") -> video(resolver, uri)
            else -> null
        }
    }.getOrNull()

    private fun image(resolver: ContentResolver, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXELS) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        return encode(oriented(bitmap, orientation))
    }

    private fun video(resolver: ContentResolver, uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return null
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val atMs = if (durationMs in 1 until 2000) durationMs / 2 else 1000
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            encode(frame)
        } finally {
            retriever.release()
        }
    }

    private fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun encode(source: Bitmap): String? {
        val longest = maxOf(source.width, source.height)
        if (longest <= 0) return null
        val scaled = if (longest > MAX_PIXELS) {
            val f = MAX_PIXELS.toFloat() / longest
            Bitmap.createScaledBitmap(source, maxOf(1, (source.width * f).toInt()), maxOf(1, (source.height * f).toInt()), true)
        } else source
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        return B64.encodeStandard(out.toByteArray())
    }
}

/**
 * Cover thumbnails for list rows, fetched from `GET /files/{id}/thumbnail` (plaintext by design,
 * bearer-authenticated) and kept in memory. A file without one is remembered as such, so a folder
 * of documents costs one 404 each, once.
 */
class ThumbnailCache(private val api: NeutrinoApi) {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val locks = mutableMapOf<String, Mutex>()

    fun peek(path: String): Bitmap? = cache.get(path)

    suspend fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (path in missing) return null
        val lock = synchronized(locks) { locks.getOrPut(path) { Mutex() } }
        return lock.withLock {
            cache.get(path) ?: withContext(Dispatchers.IO) {
                val bytes = runCatching { api.thumbnail(path) }.getOrNull()
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bitmap == null) missing += path else cache.put(path, bitmap)
                bitmap
            }
        }
    }

    fun clear() {
        cache.evictAll()
        missing.clear()
    }
}
