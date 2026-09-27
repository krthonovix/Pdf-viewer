package com.ultralight.pdfviewer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom lightweight [View] that draws a PDF page directly onto its hardware-accelerated [Canvas]
 * with zero nested ViewGroups and zero object allocations inside [onDraw].
 *
 * Features:
 * 1. Base 1x page rendering with bilinear filtering (`FILTER_BITMAP_FLAG`).
 * 2. High-resolution viewport patch overlay when zoomed (`zoom > 1.15f`).
 * 3. Zero-RAM GPU Night Mode using a pre-allocated [ColorMatrixColorFilter].
 */
class PdfPageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val placeholderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint().apply {
        color = 0xFF2A2A2A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val destRect = Rect()
    private val patchDestRectF = RectF()

    private var baseBitmap: Bitmap? = null
    private var zoomPatchBitmap: Bitmap? = null
    private var zoomPatchPageRect = RectF()

    var pageAspectRatio: Float = 1.294f // Default US Letter (792 / 612)
        set(value) {
            if (kotlin.math.abs(field - value) > 0.002f) {
                field = value
                requestLayout()
            }
        }

    var nightModeEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                bitmapPaint.colorFilter = if (value) NIGHT_MODE_FILTER else null
                placeholderPaint.color = if (value) 0xFF121212.toInt() else Color.WHITE
                invalidate()
            }
        }

    fun setBaseBitmap(bitmap: Bitmap?) {
        baseBitmap = bitmap
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = baseBitmap

    /**
     * Attaches a high-resolution patch rendered specifically for the currently visible zoomed
     * area, mapped back to unzoomed [View] coordinates (`[0..width, 0..height]`).
     */
    fun setZoomPatch(patch: Bitmap?, pageSpaceRect: RectF) {
        zoomPatchBitmap = patch
        zoomPatchPageRect.set(pageSpaceRect)
        invalidate()
    }

    fun clearZoomPatch(): Bitmap? {
        val old = zoomPatchBitmap
        zoomPatchBitmap = null
        zoomPatchPageRect.setEmpty()
        if (old != null) {
            invalidate()
        }
        return old
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (width * pageAspectRatio).toInt().coerceAtLeast(1)
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        destRect.set(0, 0, w, h)

        val base = baseBitmap
        if (base != null && !base.isRecycled) {
            canvas.drawBitmap(base, null, destRect, bitmapPaint)
        } else {
            canvas.drawRect(destRect, placeholderPaint)
        }

        // Overlay razor-sharp zoomed viewport patch if present
        val patch = zoomPatchBitmap
        if (patch != null && !patch.isRecycled && !zoomPatchPageRect.isEmpty) {
            patchDestRectF.set(zoomPatchPageRect)
            canvas.drawBitmap(patch, null, patchDestRectF, bitmapPaint)
        }

        // Subtle 1px bottom border between pages
        canvas.drawLine(0f, h - 1f, w.toFloat(), h - 1f, dividerPaint)
    }

    companion object {
        /**
         * High-contrast dark-mode inversion matrix that maps white backgrounds (#FFFFFF)
         * to deep dark gray (#121212) and black text (#000000) to warm off-white (#EBEBEB)
         * to reduce eye strain without requiring any extra Bitmap memory.
         */
        private val NIGHT_MODE_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 235f,
                    0f, -0.85f, 0f, 0f, 235f,
                    0f, 0f, -0.85f, 0f, 235f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}
