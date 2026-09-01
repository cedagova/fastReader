package com.cedagova.fastreader.library.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cedagova.fastreader.library.store.CoverStore
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Supplies the library's cover thumbnails. Kept behind an interface so renders can inject one. */
fun interface CoverLoader {

    /** The book's cover, or null when it has none or the cached image is unusable. */
    suspend fun load(bookId: String): ImageBitmap?

    companion object {
        /** No covers at all — every row falls back to its placeholder. */
        val None = CoverLoader { null }
    }
}

/**
 * Decodes covers from the on-disk cache LEAF101 fills.
 *
 * Cover art inside an EPUB is full page size, so it is downsampled to roughly
 * thumbnail resolution before it is kept: a library of a few hundred books must
 * not hold a few hundred full-size bitmaps in memory.
 */
class CoverStoreLoader(
    private val covers: CoverStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxCachedCovers: Int = DEFAULT_CACHE_ENTRIES,
) : CoverLoader {

    private val cache = LruCache<String, ImageBitmap>(maxCachedCovers)

    override suspend fun load(bookId: String): ImageBitmap? {
        cache.get(bookId)?.let { return it }
        val decoded = withContext(dispatcher) { covers.read(bookId)?.let(::decode) } ?: return null
        cache.put(bookId, decoded)
        return decoded
    }

    private fun decode(file: File): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) {
            null
        } else {
            var sampleSize = 1
            while (longestEdge / (sampleSize * 2) >= TARGET_EDGE_PX) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }
    } catch (_: OutOfMemoryError) {
        // A pathological cover must cost a placeholder, not the library screen.
        null
    }

    private companion object {
        const val DEFAULT_CACHE_ENTRIES = 64

        /** Rows show a 48x64dp thumbnail; ~256px covers that at the highest densities. */
        const val TARGET_EDGE_PX = 256
    }
}
