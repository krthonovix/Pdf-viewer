package com.ultralight.pdfviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.ultralight.pdfviewer.engine.PdfDocumentEngine
import com.ultralight.pdfviewer.storage.FileDescriptorResolver
import com.ultralight.pdfviewer.storage.ReadingStateStore
import com.ultralight.pdfviewer.ui.PdfPageAdapter
import com.ultralight.pdfviewer.ui.ZoomableRecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ultra-lightweight main Activity extending platform [Activity] directly (no AppCompat bloat)
 * for instant cold-start (< 80ms) and minimal APK footprint.
 *
 * Includes full WindowInsets (Status Bar + Camera DisplayCutout + Navigation Bar) handling
 * for Android 8.0 through Android 15+ Edge-to-Edge displays.
 */
class MainActivity : Activity(), ComponentCallbacks2 {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: PdfDocumentEngine
    private lateinit var stateStore: ReadingStateStore
    private lateinit var adapter: PdfPageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private val pagerSnapHelper = PagerSnapHelper()

    private lateinit var rootContainer: FrameLayout
    private lateinit var recyclerView: ZoomableRecyclerView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var tvDocumentTitle: TextView
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNightMode: Button
    private lateinit var btnScrollMode: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button

    private var currentUri: Uri? = null
    private var currentPageIndex: Int = 0
    private var isHudVisible: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = PdfDocumentEngine()
        stateStore = ReadingStateStore(this)

        bindViews()
        setupWindowInsets()
        setupRecyclerView()
        restoreGlobalPreferences()

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIncomingIntent(intent)
        }
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        recyclerView = findViewById(R.id.pdfRecyclerView)
        topBar = findViewById(R.id.topControlBar)
        bottomBar = findViewById(R.id.bottomControlBar)
        emptyStateLayout = findViewById(R.id.emptyStateContainer)
        tvDocumentTitle = findViewById(R.id.tvDocumentTitle)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNightMode = findViewById(R.id.btnNightMode)
        btnScrollMode = findViewById(R.id.btnScrollMode)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)

        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            launchDocumentPicker()
        }
        findViewById<Button>(R.id.btnOpenEmptyState).setOnClickListener {
            launchDocumentPicker()
        }

        tvPageIndicator.setOnClickListener {
            if (engine.pageCount > 0) {
                showJumpToPageDialog()
            }
        }

        btnPrevPage.setOnClickListener {
            navigateToPage(currentPageIndex - 1)
        }

        btnNextPage.setOnClickListener {
            navigateToPage(currentPageIndex + 1)
        }

        btnNightMode.setOnClickListener {
            val nextMode = !adapter.nightMode
            adapter.nightMode = nextMode
            stateStore.isNightMode = nextMode
            updateToggleLabels()
        }

        btnScrollMode.setOnClickListener {
            val nextHorizontal = !stateStore.isHorizontalPaging
            stateStore.isHorizontalPaging = nextHorizontal
            applyScrollOrientation(nextHorizontal)
            updateToggleLabels()
        }
    }

    /**
     * Prevents the top and bottom floating bars from colliding with the front camera cutout
     * (punch-hole/notch), status bar, and bottom navigation gesture bar on Android Edge-to-Edge screens.
     */
    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val margin12Dp = (12f * density).toInt()
        val margin14Dp = (14f * density).toInt()
        val margin16Dp = (16f * density).toInt()
        val topHudAllowancePx = (78f * density).toInt()
        val bottomHudAllowancePx = (76f * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )

            // Offset floating top bar safely below camera cutout & status bar
            val topParams = topBar.layoutParams as ViewGroup.MarginLayoutParams
            topParams.topMargin = insets.top + margin12Dp
            topParams.leftMargin = insets.left + margin14Dp
            topParams.rightMargin = insets.right + margin14Dp
            topBar.layoutParams = topParams

            // Offset floating bottom page pill safely above gesture navigation bar
            val bottomParams = bottomBar.layoutParams as ViewGroup.MarginLayoutParams
            bottomParams.bottomMargin = insets.bottom + margin16Dp
            bottomBar.layoutParams = bottomParams

            // Pad RecyclerView (with clipToPadding=false) so first and last PDF pages aren't covered by bars
            recyclerView.setPadding(
                insets.left,
                insets.top + topHudAllowancePx,
                insets.right,
                insets.bottom + bottomHudAllowancePx
            )

            windowInsets
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false).apply {
            isItemPrefetchEnabled = true
            initialPrefetchItemCount = 3
        }
        adapter = PdfPageAdapter(engine, uiScope)

        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(2)

        recyclerView.viewportZoomListener = object : ZoomableRecyclerView.ViewportZoomListener {
            override fun onZoomViewportSettled(zoomScale: Float) {
                adapter.renderVisibleZoomPatches(recyclerView, zoomScale)
            }

            override fun onZoomReset() {
                adapter.clearAllZoomPatches(recyclerView)
            }

            override fun onSingleTap() {
                toggleHudVisibility()
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateVisiblePageIndicator()
                    if (recyclerView.zoomScale > ZoomableRecyclerView.ZOOM_PATCH_THRESHOLD) {
                        adapter.renderVisibleZoomPatches(recyclerView, recyclerView.zoomScale)
                    }
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dx != 0 || dy != 0) {
                    updateVisiblePageIndicator()
                }
            }
        })
    }

    private fun toggleHudVisibility() {
        isHudVisible = !isHudVisible
        val duration = 180L

        if (isHudVisible) {
            topBar.visibility = View.VISIBLE
            topBar.animate().alpha(1f).translationY(0f).setDuration(duration).start()
            if (engine.pageCount > 0) {
                bottomBar.visibility = View.VISIBLE
                bottomBar.animate().alpha(1f).translationY(0f).setDuration(duration).start()
            }
        } else {
            topBar.animate()
                .alpha(0f)
                .translationY(-topBar.height.toFloat() * 0.5f)
                .setDuration(duration)
                .withEndAction {
                    if (!isHudVisible) topBar.visibility = View.GONE
                }
                .start()

            if (bottomBar.visibility == View.VISIBLE) {
                bottomBar.animate()
                    .alpha(0f)
                    .translationY(bottomBar.height.toFloat() * 0.5f)
                    .setDuration(duration)
                    .withEndAction {
                        if (!isHudVisible) bottomBar.visibility = View.GONE
                    }
                    .start()
            }
        }
    }

    private fun restoreGlobalPreferences() {
        val night = stateStore.isNightMode
        val horizontal = stateStore.isHorizontalPaging
        adapter.nightMode = night
        applyScrollOrientation(horizontal)
        updateToggleLabels()
    }

    private fun applyScrollOrientation(horizontal: Boolean) {
        val savedPage = currentPageIndex
        layoutManager.orientation = if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
        pagerSnapHelper.attachToRecyclerView(if (horizontal) recyclerView else null)
        adapter.isHorizontalPaging = horizontal
        if (engine.pageCount > 0) {
            recyclerView.scrollToPosition(savedPage.coerceIn(0, engine.pageCount - 1))
        }
    }

    private fun updateToggleLabels() {
        btnNightMode.text = if (adapter.nightMode) getString(R.string.mode_day) else getString(R.string.mode_night)
        btnScrollMode.text = if (stateStore.isHorizontalPaging) {
            getString(R.string.scroll_horizontal)
        } else {
            getString(R.string.scroll_vertical)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val dataUri = intent?.data
        if (dataUri != null) {
            openPdfUri(dataUri)
            return
        }

        // Attempt to reopen last read document if permissions persist
        val lastUri = stateStore.getLastOpenedUri()
        if (lastUri != null) {
            openPdfUri(lastUri, silentOnError = true)
        }
    }

    private fun launchDocumentPicker() {
        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(pickIntent, REQUEST_CODE_OPEN_PDF)
    }

    @Deprecated("Using platform Activity startActivityForResult for zero-dependency footprint")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_PDF && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Provider may not support persistable permissions; transient grant still works
            }
            openPdfUri(uri)
        }
    }

    private fun openPdfUri(uri: Uri, silentOnError: Boolean = false) {
        uiScope.launch {
            try {
                recyclerView.resetZoom()
                val pfd = FileDescriptorResolver.openSeekableDescriptor(this@MainActivity, uri)
                val totalPages = engine.openDocument(pfd)

                currentUri = uri
                tvDocumentTitle.text = resolveDisplayName(uri)
                emptyStateLayout.visibility = if (totalPages > 0) View.GONE else View.VISIBLE
                bottomBar.visibility = if (totalPages > 0 && isHudVisible) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()

                val restoredPage = stateStore.getLastPage(uri).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                currentPageIndex = restoredPage
                recyclerView.scrollToPosition(restoredPage)
                updatePageLabel(restoredPage, totalPages)
            } catch (e: Throwable) {
                if (!silentOnError) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_opening_pdf, e.localizedMessage ?: "Error"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment ?: getString(R.string.app_name)
        } catch (_: Throwable) {
            uri.lastPathSegment ?: getString(R.string.app_name)
        }
    }

    private fun navigateToPage(targetIndex: Int) {
        val total = engine.pageCount
        if (total <= 0) return
        val clamped = targetIndex.coerceIn(0, total - 1)
        if (clamped == currentPageIndex) return

        recyclerView.resetZoom()
        recyclerView.scrollToPosition(clamped)
        currentPageIndex = clamped
        updatePageLabel(clamped, total)
        currentUri?.let { stateStore.saveLastPage(it, clamped) }
    }

    private fun updateVisiblePageIndicator() {
        val total = engine.pageCount
        if (total <= 0) return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPageIndex) {
            currentPageIndex = firstVisible
            updatePageLabel(firstVisible, total)
            currentUri?.let { stateStore.saveLastPage(it, firstVisible) }
        }
    }

    private fun updatePageLabel(pageIndex: Int, totalPages: Int) {
        tvPageIndicator.text = getString(R.string.page_indicator_format, pageIndex + 1, totalPages)
        val hasPrev = pageIndex > 0
        val hasNext = pageIndex < totalPages - 1
        btnPrevPage.alpha = if (hasPrev) 1.0f else 0.35f
        btnPrevPage.isEnabled = hasPrev
        btnNextPage.alpha = if (hasNext) 1.0f else 0.35f
        btnNextPage.isEnabled = hasNext
    }

    private fun showJumpToPageDialog() {
        val total = engine.pageCount
        if (total <= 0) return

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $total"
            setText((currentPageIndex + 1).toString())
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.jump_to_page_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targetPage = input.text.toString().toIntOrNull()
                if (targetPage != null) {
                    navigateToPage(targetPage - 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            adapter.clearAllZoomPatches(recyclerView)
            engine.onTrimMemory(level)
        }
    }

    override fun onStop() {
        super.onStop()
        currentUri?.let { stateStore.saveLastPage(it, currentPageIndex) }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        engine.close()
    }

    companion object {
        private const val REQUEST_CODE_OPEN_PDF = 1001
    }
}
