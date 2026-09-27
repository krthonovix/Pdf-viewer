package com.ultralight.pdfviewer.memory

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Memory-bounded LruCache for base (1x) rendered PDF pages.
 *
 * When a page is evicted from the cache, its underlying [Bitmap] is automatically returned
 * to the [BitmapPool] for immediate reuse by newly scrolled pages, as long as it is not
 * currently pinned to a visible ViewHolder.
 */
class PageBitmapCache(
    private val bitmapPool: BitmapPool,
    maxBytes: Int = computeDefaultCacheBytes()
) {
    private val pinnedPages = HashSet<Int>()
    private val lock = Any()

    private val lruCache = object : LruCache<Int, Bitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: Int,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (oldValue !== newValue) {
                val isPinned = synchronized(lock) { pinnedPages.contains(key) }
                if (!isPinned) {
                    bitmapPool.release(oldValue)
                }
            }
        }
    }

    fun get(pageIndex: Int): Bitmap? = synchronized(lock) {
        val bmp = lruCache.get(pageIndex)
        if (bmp != null && bmp.isRecycled) {
            lruCache.remove(pageIndex)
            null
        } else {
            bmp
        }
    }

    fun put(pageIndex: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lock) {
            lruCache.put(pageIndex, bitmap)
        }
    }

    /**
     * Marks a page as actively displayed on screen so its bitmap won't be recycled into the pool
     * while a View is still drawing it.
     */
    fun pin(pageIndex: Int) {
        synchronized(lock) {
            pinnedPages.add(pageIndex)
        }
    }

    /**
     * Unpins a page when its ViewHolder is recycled or detached.
     * If the bitmap was already evicted from [lruCache] while pinned, returns it to [bitmapPool].
     */
    fun unpin(pageIndex: Int, detachedBitmap: Bitmap?) {
        val shouldRelease = synchronized(lock) {
            pinnedPages.remove(pageIndex)
            val cached = lruCache.get(pageIndex)
            detachedBitmap != null && cached !== detachedBitmap
        }
        if (shouldRelease) {
            bitmapPool.release(detachedBitmap)
        }
    }

    fun trimToHalf() {
        synchronized(lock) {
            lruCache.trimToSize(lruCache.maxSize() / 2)
        }
    }

    fun evictAll() {
        synchronized(lock) {
            pinnedPages.clear()
            lruCache.evictAll()
        }
    }

    companion object {
        fun computeDefaultCacheBytes(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory().toLong()
            // Use 1/8th of available VM heap, bounded between 12 MB and 48 MB
            val eighth = (maxMemory / 8L).toInt()
            return eighth.coerceIn(12 * 1024 * 1024, 48 * 1024 * 1024)
        }
    }
}
