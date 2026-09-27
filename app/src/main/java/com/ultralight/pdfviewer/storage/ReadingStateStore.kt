package com.ultralight.pdfviewer.storage

import android.content.Context
import android.net.Uri

/**
 * Ultra-lightweight persistence layer backed by [android.content.SharedPreferences].
 * Stores the last read page index, global night-mode toggle, and scroll orientation
 * with zero database dependencies.
 */
class ReadingStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastPage(uri: Uri): Int {
        return prefs.getInt(pageKey(uri), 0).coerceAtLeast(0)
    }

    fun saveLastPage(uri: Uri, pageIndex: Int) {
        prefs.edit()
            .putInt(pageKey(uri), pageIndex.coerceAtLeast(0))
            .putString(KEY_LAST_URI, uri.toString())
            .apply()
    }

    fun getLastOpenedUri(): Uri? {
        val raw = prefs.getString(KEY_LAST_URI, null) ?: return null
        return try {
            Uri.parse(raw)
        } catch (_: Throwable) {
            null
        }
    }

    var isNightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply()
        }

    var isHorizontalPaging: Boolean
        get() = prefs.getBoolean(KEY_HORIZONTAL_PAGING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HORIZONTAL_PAGING, value).apply()
        }

    private fun pageKey(uri: Uri): String {
        // Compact deterministic key based on URI string hash
        return "page_${uri.toString().hashCode()}"
    }

    companion object {
        private const val PREFS_NAME = "ultralight_pdf_prefs"
        private const val KEY_LAST_URI = "last_opened_uri"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val KEY_HORIZONTAL_PAGING = "horizontal_paging"
    }
}
