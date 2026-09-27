package com.ultralight.pdfviewer.memory

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque

/**
 * Thread-safe, zero-allocation-oriented Bitmap pool for [Bitmap.Config.ARGB_8888] buffers.
 *
 * `android.graphics.pdf.PdfRenderer` strictly requires [Bitmap.Config.ARGB_8888].
 * By pooling and erasing existing bitmaps with [Color.WHITE] instead of allocating new ones
 * during fast scrolling, we eliminate GC pauses and OutOfMemoryError spikes.
 */
class BitmapPool(
    private val maxPoolBytes: Int = DEFAULT_MAX_POOL_BYTES
) {
    private val lock = Any()
    private val pool = ArrayDeque<Bitmap>(MAX_POOLED_ITEMS)
    private var currentPoolBytes: Int = 0

    /**
     * Acquires a mutable [Bitmap.Config.ARGB_8888] cleared to [Color.WHITE].
     * Reuses a pooled bitmap with matching dimensions (or reconfigures a pooled bitmap with sufficient allocation)
     * when available; allocates a new bitmap only on cache miss.
     */
    fun acquire(width: Int, height: Int): Bitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val requiredBytes = safeW * safeH * BYTES_PER_PIXEL_ARGB8888

        synchronized(lock) {
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.isRecycled) {
                    iterator.remove()
                    continue
                }
                if (candidate.width == safeW && candidate.height == safeH) {
                    iterator.remove()
                    currentPoolBytes = (currentPoolBytes - candidate.allocationByteCount).coerceAtLeast(0)
                    candidate.eraseColor(Color.WHITE)
                    return candidate
                }
                // API 19+ allows reconfiguring a mutable bitmap if its allocationByteCount >= requiredBytes
                if (candidate.allocationByteCount >= requiredBytes &&
                    candidate.allocationByteCount <= requiredBytes * 2
                ) {
                    iterator.remove()
                    currentPoolBytes = (currentPoolBytes - candidate.allocationByteCount).coerceAtLeast(0)
                    return try {
                        candidate.reconfigure(safeW, safeH, Bitmap.Config.ARGB_8888)
                        candidate.eraseColor(Color.WHITE)
                        candidate
                    } catch (_: Throwable) {
                        candidate.recycle()
                        Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.WHITE)
                        }
                    }
                }
            }
        }

        return Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    /**
     * Returns a [Bitmap] back to the pool for immediate reuse by upcoming pages.
     * If the pool exceeds [maxPoolBytes], the oldest bitmaps are recycled immediately.
     */
    fun release(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || !bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
            return
        }

        val byteCount = bitmap.allocationByteCount
        if (byteCount <= 0 || byteCount > maxPoolBytes) {
            bitmap.recycle()
            return
        }

        synchronized(lock) {
            // Avoid duplicate references in pool
            for (existing in pool) {
                if (existing === bitmap) return
            }

            while (
                (currentPoolBytes + byteCount > maxPoolBytes || pool.size >= MAX_POOLED_ITEMS) &&
                pool.isNotEmpty()
            ) {
                val evicted = pool.removeFirst()
                currentPoolBytes = (currentPoolBytes - evicted.allocationByteCount).coerceAtLeast(0)
                if (!evicted.isRecycled) {
                    evicted.recycle()
                }
            }

            pool.addLast(bitmap)
            currentPoolBytes += byteCount
        }
    }

    /**
     * Immediately frees all native pixel memory held by the pool.
     * Invoked on [android.content.ComponentCallbacks2.onTrimMemory] or when closing a document.
     */
    fun clear() {
        synchronized(lock) {
            while (pool.isNotEmpty()) {
                val bmp = pool.removeFirst()
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
            currentPoolBytes = 0
        }
    }

    fun currentBytes(): Int = synchronized(lock) { currentPoolBytes }

    companion object {
        private const val BYTES_PER_PIXEL_ARGB8888 = 4
        private const val MAX_POOLED_ITEMS = 8
        // Default 24 MB cap for pooled reusable buffers
        const val DEFAULT_MAX_POOL_BYTES = 24 * 1024 * 1024
    }
}
