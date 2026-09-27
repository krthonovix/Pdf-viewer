package com.ultralight.pdfviewer

import com.ultralight.pdfviewer.engine.ViewportMatrixCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMatrixCalculatorTest {

    @Test
    fun `packSize and unpack preserve exact width and height without heap allocations`() {
        val width = 612
        val height = 792
        val packed = ViewportMatrixCalculator.packSize(width, height)

        assertEquals(width, ViewportMatrixCalculator.unpackWidth(packed))
        assertEquals(height, ViewportMatrixCalculator.unpackHeight(packed))
    }

    @Test
    fun `computeTargetHeight preserves PDF page aspect ratio`() {
        // US Letter: 612 x 792 points -> on a 1080px screen width -> height should be 1397px
        val targetH = ViewportMatrixCalculator.computeTargetHeight(
            pdfPageWidth = 612,
            pdfPageHeight = 792,
            targetWidthPx = 1080
        )
        assertEquals(1397, targetH)
    }

    @Test
    fun `calculateViewportTransform scales and translates zoomed viewport accurately`() {
        val transform = ViewportMatrixCalculator.calculateViewportTransform(
            pdfPageWidth = 600,
            baseWidthPx = 1200, // 2x base density scale
            zoomScale = 3.0f,   // 3x pinch zoom -> total scale = 6.0x
            viewportLeftPx = 450f,
            viewportTopPx = 900f
        )

        assertEquals(6.0f, transform.scaleFactor, 0.0001f)
        assertEquals(-450f, transform.translateX, 0.0001f)
        assertEquals(-900f, transform.translateY, 0.0001f)
    }

    @Test
    fun `calculateViewportTransform clamps zoom within valid bounds`() {
        val transformLow = ViewportMatrixCalculator.calculateViewportTransform(
            pdfPageWidth = 500,
            baseWidthPx = 1000,
            zoomScale = 0.4f, // Below 1.0f minimum
            viewportLeftPx = -20f,
            viewportTopPx = -10f
        )
        assertEquals(2.0f, transformLow.scaleFactor, 0.0001f)
        assertEquals(0f, transformLow.translateX, 0.0001f)
        assertEquals(0f, transformLow.translateY, 0.0001f)
    }
}
