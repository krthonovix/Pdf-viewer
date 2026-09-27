package com.ultralight.pdfviewer.ui

import android.graphics.RectF
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ultralight.pdfviewer.engine.PdfDocumentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ultra-lightweight [RecyclerView.Adapter] for rendering PDF pages.
 *
 * Key optimizations:
 * - Uses stable IDs (`pageIndex.toLong()`) to avoid unnecessary ViewHolder rebinds.
 * - Cancels in-flight coroutine jobs immediately inside [onViewRecycled] so rapid 500-page flings
 *   never build up a render backlog.
 * - Manages pinning/unpinning of bitmaps in [com.ultralight.pdfviewer.memory.PageBitmapCache]
 *   and recycles high-resolution zoom patch bitmaps directly back into [com.ultralight.pdfviewer.memory.BitmapPool].
 */
class PdfPageAdapter(
    private val engine: PdfDocumentEngine,
    private val uiScope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var nightMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount, PAYLOAD_NIGHT_MODE)
            }
        }

    var isHorizontalPaging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private var zoomPatchJob: Job? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemCount(): Int = engine.pageCount

    override fun getItemId(position: Int): Long = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val pageView = PdfPageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return PageViewHolder(pageView)
    }

    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_NIGHT_MODE)) {
            holder.pageView.nightModeEnabled = nightMode
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
    }

    /**
     * Renders razor-sharp viewport patches for only the currently visible ViewHolders
     * when the user is zoomed in (`zoomScale > 1.15f`).
     */
    fun renderVisibleZoomPatches(recyclerView: ZoomableRecyclerView, zoomScale: Float) {
        zoomPatchJob?.cancel()
        zoomPatchJob = uiScope.launch {
            val zoomedRect = RectF()
            val pageSpaceRect = RectF()

            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i) as? PdfPageView ?: continue
                val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
                val pageIndex = holder.boundPageIndex
                if (pageIndex < 0) continue

                if (recyclerView.computeVisibleChildRegion(child, zoomedRect, pageSpaceRect)) {
                    val targetZoomedRect = RectF(zoomedRect)
                    val targetPageRect = RectF(pageSpaceRect)
                    val patch = engine.renderZoomedViewportPatch(
                        pageIndex = pageIndex,
                        baseWidthPx = child.width,
                        zoomScale = zoomScale,
                        visibleZoomedRect = targetZoomedRect
                    )
                    if (patch != null && holder.boundPageIndex == pageIndex) {
                        val previousPatch = child.clearZoomPatch()
                        engine.bitmapPool.release(previousPatch)
                        child.setZoomPatch(patch, targetPageRect)
                    } else {
                        engine.bitmapPool.release(patch)
                    }
                } else {
                    val previousPatch = child.clearZoomPatch()
                    engine.bitmapPool.release(previousPatch)
                }
            }
        }
    }

    /**
     * Releases all active high-res zoom patches back to the [com.ultralight.pdfviewer.memory.BitmapPool]
     * when zoom resets to 1.0x.
     */
    fun clearAllZoomPatches(recyclerView: RecyclerView) {
        zoomPatchJob?.cancel()
        zoomPatchJob = null
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) as? PdfPageView ?: continue
            val oldPatch = child.clearZoomPatch()
            engine.bitmapPool.release(oldPatch)
        }
    }

    inner class PageViewHolder(val pageView: PdfPageView) : RecyclerView.ViewHolder(pageView) {
        var boundPageIndex: Int = -1
            private set
        private var renderJob: Job? = null

        fun bind(pageIndex: Int) {
            recycle()
            boundPageIndex = pageIndex
            pageView.nightModeEnabled = nightMode
            pageView.pageAspectRatio = engine.getPageAspectRatioFast(pageIndex)

            val lp = pageView.layoutParams
            lp.height = if (isHorizontalPaging) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            pageView.layoutParams = lp

            engine.bitmapCache.pin(pageIndex)

            // Fast synchronous hit from LruCache if already rendered
            val cached = engine.bitmapCache.get(pageIndex)
            if (cached != null) {
                pageView.setBaseBitmap(cached)
                return
            }

            pageView.setBaseBitmap(null)
            val targetWidth = pageView.resources.displayMetrics.widthPixels.coerceAtLeast(360)

            renderJob = uiScope.launch {
                val bmp = engine.renderPageBase(pageIndex, targetWidth)
                if (bmp != null && boundPageIndex == pageIndex) {
                    pageView.pageAspectRatio = bmp.height.toFloat() / bmp.width.coerceAtLeast(1).toFloat()
                    pageView.setBaseBitmap(bmp)
                }
            }
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null

            val oldPatch = pageView.clearZoomPatch()
            engine.bitmapPool.release(oldPatch)

            val prevPage = boundPageIndex
            val prevBase = pageView.getBaseBitmap()
            pageView.setBaseBitmap(null)
            boundPageIndex = -1

            if (prevPage >= 0) {
                engine.bitmapCache.unpin(prevPage, prevBase)
            }
        }
    }

    companion object {
        private const val PAYLOAD_NIGHT_MODE = "payload_night_mode"
    }
}
