package com.ultralight.pdfviewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Custom [RecyclerView] supporting smooth 60/120 FPS pinch-to-zoom (`1.0x` to `5.0x`),
 * double-tap smart zoom (`1.0x` <-> `2.5x`), 2D panning when zoomed, and debounced
 * high-resolution viewport patch notifications.
 *
 * By transforming the [Canvas] during active pinch/pan gestures and requesting a single
 * viewport-sized patch once the gesture settles (80ms debounce), zoom feels instantaneous
 * while consuming O(1) memory.
 */
class ZoomableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    interface ViewportZoomListener {
        /**
         * Called 80ms after a zoom or pan gesture settles when [zoomScale] > 1.15f.
         */
        fun onZoomViewportSettled(zoomScale: Float)

        /**
         * Called immediately when zoom returns to 1.0f so any high-res patch bitmaps can be returned to the pool.
         */
        fun onZoomReset()

        /**
         * Called on a single tap to toggle immersive toolbar visibility.
         */
        fun onSingleTap()
    }

    var viewportZoomListener: ViewportZoomListener? = null

    var zoomScale: Float = 1.0f
        private set

    // Pan offsets in zoomed screen pixels: within [-(width * (zoomScale - 1)), 0]
    private var panX: Float = 0f
    private var panY: Float = 0f

    private var isScaling = false

    private val settleRunnable = Runnable {
        if (zoomScale > ZOOM_PATCH_THRESHOLD) {
            viewportZoomListener?.onZoomViewportSettled(zoomScale)
        } else {
            viewportZoomListener?.onZoomReset()
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                removeCallbacks(settleRunnable)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = zoomScale
                val newScale = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val factor = newScale / prevScale

                if (abs(newScale - prevScale) > 0.001f) {
                    // Keep focal point stationary under fingers
                    val focusX = detector.focusX
                    val focusY = detector.focusY
                    panX = focusX - (focusX - panX) * factor
                    panY = focusY - (focusY - panY) * factor
                    zoomScale = newScale
                    clampPan()
                    invalidate()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                if (zoomScale < 1.04f) {
                    resetZoom()
                } else {
                    scheduleViewportSettle()
                }
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                viewportZoomListener?.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                removeCallbacks(settleRunnable)
                if (zoomScale > 1.15f) {
                    resetZoom()
                } else {
                    val targetScale = DOUBLE_TAP_ZOOM
                    val factor = targetScale / zoomScale
                    panX = e.x - (e.x - panX) * factor
                    panY = e.y - (e.y - panY) * factor
                    zoomScale = targetScale
                    clampPan()
                    invalidate()
                    scheduleViewportSettle()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (zoomScale > 1.01f && !isScaling) {
                    removeCallbacks(settleRunnable)
                    val prevPanX = panX
                    panX -= distanceX
                    clampPan()

                    // Pass remaining vertical (or horizontal) scroll delta to RecyclerView so pages scroll seamlessly while zoomed
                    val unconsumedX = (distanceX - (prevPanX - panX)).toInt()
                    val unconsumedY = distanceY.toInt()
                    if (unconsumedX != 0 || unconsumedY != 0) {
                        scrollBy(unconsumedX, unconsumedY)
                    }

                    invalidate()
                    scheduleViewportSettle()
                    return true
                }
                return false
            }
        }
    )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val gestureHandled = gestureDetector.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (zoomScale > ZOOM_PATCH_THRESHOLD) {
                scheduleViewportSettle()
            }
        }

        // When zoomed in, gestureDetector handles 2D pan and forwards vertical scroll via scrollBy()
        return if (zoomScale > 1.01f || isScaling) {
            true
        } else {
            gestureHandled || super.onTouchEvent(e)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (zoomScale > 1.001f) {
            val saveCount = canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(zoomScale, zoomScale)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    /**
     * Computes the visible region of a child [PdfPageView] in BOTH:
     * 1. `outZoomedRect`: zoomed pixel coordinates `[0 .. child.width * zoomScale, 0 .. child.height * zoomScale]`
     *    used by [com.ultralight.pdfviewer.engine.PdfDocumentEngine.renderZoomedViewportPatch].
     * 2. `outPageSpaceRect`: unzoomed child view coordinates `[0 .. child.width, 0 .. child.height]`
     *    where [PdfPageView] should draw the rendered high-res patch.
     *
     * Returns `true` if the child has a non-empty visible intersection with the screen.
     */
    fun computeVisibleChildRegion(
        child: PdfPageView,
        outZoomedRect: RectF,
        outPageSpaceRect: RectF
    ): Boolean {
        val cw = child.width.toFloat()
        val ch = child.height.toFloat()
        if (cw <= 0f || ch <= 0f) return false

        // Screen bounds mapped into unscaled RecyclerView coordinates:
        // screenX = panX + rvX * zoomScale  =>  rvX = (screenX - panX) / zoomScale
        val visibleRvLeft = (-panX) / zoomScale
        val visibleRvTop = (-panY) / zoomScale
        val visibleRvRight = (width - panX) / zoomScale
        val visibleRvBottom = (height - panY) / zoomScale

        // Intersect with child bounds inside RecyclerView
        val childLeft = child.left.toFloat()
        val childTop = child.top.toFloat()
        val interLeft = visibleRvLeft.coerceAtLeast(childLeft)
        val interTop = visibleRvTop.coerceAtLeast(childTop)
        val interRight = visibleRvRight.coerceAtMost(child.right.toFloat())
        val interBottom = visibleRvBottom.coerceAtMost(child.bottom.toFloat())

        if (interRight <= interLeft + 2f || interBottom <= interTop + 2f) {
            return false
        }

        // Convert to child-local unzoomed coordinates [0..cw, 0..ch]
        val localLeft = (interLeft - childLeft).coerceIn(0f, cw)
        val localTop = (interTop - childTop).coerceIn(0f, ch)
        val localRight = (interRight - childLeft).coerceIn(0f, cw)
        val localBottom = (interBottom - childTop).coerceIn(0f, ch)

        outPageSpaceRect.set(localLeft, localTop, localRight, localBottom)
        outZoomedRect.set(
            localLeft * zoomScale,
            localTop * zoomScale,
            localRight * zoomScale,
            localBottom * zoomScale
        )
        return true
    }

    fun resetZoom() {
        removeCallbacks(settleRunnable)
        zoomScale = 1.0f
        panX = 0f
        panY = 0f
        viewportZoomListener?.onZoomReset()
        invalidate()
    }

    private fun clampPan() {
        val minPanX = -(width * (zoomScale - 1f)).coerceAtLeast(0f)
        val minPanY = -(height * (zoomScale - 1f)).coerceAtLeast(0f)
        panX = panX.coerceIn(minPanX, 0f)
        panY = panY.coerceIn(minPanY, 0f)
    }

    private fun scheduleViewportSettle() {
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, ZOOM_SETTLE_DEBOUNCE_MS)
    }

    companion object {
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f
        const val DOUBLE_TAP_ZOOM = 2.5f
        const val ZOOM_PATCH_THRESHOLD = 1.15f
        private const val ZOOM_SETTLE_DEBOUNCE_MS = 80L
    }
}
