package com.ultralight.pdfviewer.engine

import android.graphics.Matrix

/**
 * Pure, allocation-free math helper for calculating PDF page dimensions and
 * viewport transformation matrices during pinch-to-zoom.
 */
object ViewportMatrixCalculator {

    /**
     * Packs two 32-bit integers (width and height) into a single primitive 64-bit Long
     * so page dimensions for an entire 1,000-page PDF take only 8 KB with zero heap objects.
     */
    fun packSize(width: Int, height: Int): Long {
        return (width.toLong() shl 32) or (height.toLong() and 0xFFFFFFFFL)
    }

    fun unpackWidth(packed: Long): Int = (packed ushr 32).toInt()

    fun unpackHeight(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

    /**
     * Computes the target height in pixels for a page given a target width, preserving aspect ratio.
     */
    fun computeTargetHeight(pdfPageWidth: Int, pdfPageHeight: Int, targetWidthPx: Int): Int {
        if (pdfPageWidth <= 0 || pdfPageHeight <= 0 || targetWidthPx <= 0) return 1
        val ratio = pdfPageHeight.toFloat() / pdfPageWidth.toFloat()
        return (targetWidthPx * ratio).toInt().coerceAtLeast(1)
    }

    /**
     * Transformation parameters for rendering a zoomed sub-region of a PDF page into a fixed-size
     * viewport patch bitmap.
     */
    data class ViewportTransform(
        val scaleFactor: Float,
        val translateX: Float,
        val translateY: Float
    )

    /**
     * Calculates the exact scale and translation needed by [android.graphics.pdf.PdfRenderer.Page.render]
     * to render only the visible portion of a zoomed page into a fixed-size viewport bitmap.
     *
     * @param pdfPageWidth Intrinsic width of the PDF page in PostScript points (1/72 inch).
     * @param baseWidthPx Width of the page when rendered at 1.0x zoom on screen.
     * @param zoomScale Current pinch-to-zoom factor (e.g., 2.5f).
     * @param viewportLeftPx Left offset of the visible viewport within the zoomed page coordinate space.
     * @param viewportTopPx Top offset of the visible viewport within the zoomed page coordinate space.
     */
    fun calculateViewportTransform(
        pdfPageWidth: Int,
        baseWidthPx: Int,
        zoomScale: Float,
        viewportLeftPx: Float,
        viewportTopPx: Float
    ): ViewportTransform {
        val safePdfW = pdfPageWidth.coerceAtLeast(1).toFloat()
        val safeBaseW = baseWidthPx.coerceAtLeast(1).toFloat()
        val clampedZoom = zoomScale.coerceIn(1.0f, 6.0f)

        val scaleFactor = (safeBaseW / safePdfW) * clampedZoom
        return ViewportTransform(
            scaleFactor = scaleFactor,
            translateX = -viewportLeftPx.coerceAtLeast(0f),
            translateY = -viewportTopPx.coerceAtLeast(0f)
        )
    }

    /**
     * Populates a reusable [Matrix] instance in-place (zero allocation) for `PdfRenderer.Page.render()`.
     */
    fun applyToMatrix(
        outMatrix: Matrix,
        pdfPageWidth: Int,
        baseWidthPx: Int,
        zoomScale: Float,
        viewportLeftPx: Float,
        viewportTopPx: Float
    ) {
        val transform = calculateViewportTransform(
            pdfPageWidth = pdfPageWidth,
            baseWidthPx = baseWidthPx,
            zoomScale = zoomScale,
            viewportLeftPx = viewportLeftPx,
            viewportTopPx = viewportTopPx
        )
        outMatrix.reset()
        outMatrix.postScale(transform.scaleFactor, transform.scaleFactor)
        outMatrix.postTranslate(transform.translateX, transform.translateY)
    }
}
