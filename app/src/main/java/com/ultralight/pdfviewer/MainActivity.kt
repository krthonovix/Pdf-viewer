package com.ultralight.pdfviewer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
 */
class MainActivity : Activity(), ComponentCallbacks2 {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: PdfDocumentEngine
    private lateinit var stateStore: ReadingStateStore
    private lateinit var adapter: PdfPageAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private val pagerSnapHelper = PagerSnapHelper()

    private lateinit var recyclerView: ZoomableRecyclerView
    private lateinit var topBar: LinearLayout
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNightMode: Button
    private lateinit var btnScrollMode: Button

    private var currentUri: Uri? = null
    private var currentPageIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = PdfDocumentEngine()
        stateStore = ReadingStateStore(this)

        bindViews()
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
        recyclerView = findViewById(R.id.pdfRecyclerView)
        topBar = findViewById(R.id.topControlBar)
        emptyStateLayout = findViewById(R.id.emptyStateContainer)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNightMode = findViewById(R.id.btnNightMode)
        btnScrollMode = findViewById(R.id.btnScrollMode)

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
                topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                emptyStateLayout.visibility = if (totalPages > 0) View.GONE else View.VISIBLE
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

    private fun updateVisiblePageIndicator() {
        val total = engine.pageCount
        if (total <= 0) return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPageIndex) {
            currentPageIndex = firstVisible
            updatePageLabel(firstVisible, total)
            currentUri?.let { stateStore.saveLastPage(it, firstVisible) }
        } else if (tvPageIndicator.text.isEmpty()) {
            updatePageLabel(currentPageIndex, total)
        }
    }

    private fun updatePageLabel(pageIndex: Int, totalPages: Int) {
        tvPageIndicator.text = "${pageIndex + 1} / $totalPages"
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
                    val zeroBased = (targetPage - 1).coerceIn(0, total - 1)
                    recyclerView.resetZoom()
                    recyclerView.scrollToPosition(zeroBased)
                    currentPageIndex = zeroBased
                    updatePageLabel(zeroBased, total)
                    currentUri?.let { stateStore.saveLastPage(it, zeroBased) }
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
