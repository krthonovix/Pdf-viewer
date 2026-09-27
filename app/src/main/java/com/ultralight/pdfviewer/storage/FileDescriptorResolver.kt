package com.ultralight.pdfviewer.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Resolves a [Uri] (`content://` or `file://`) into a seekable [ParcelFileDescriptor]
 * required by [android.graphics.pdf.PdfRenderer].
 *
 * Uses a **Zero-Copy fast path**: if the underlying file descriptor supports `lseek`,
 * it is passed directly to `PdfRenderer` with 0 bytes written to disk and 0 ms copy overhead.
 * Only falls back to a streaming 64 KB buffered copy when opening non-seekable pipe streams.
 */
object FileDescriptorResolver {

    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val TEMP_CACHE_FILE_NAME = "active_stream_doc.pdf"

    suspend fun openSeekableDescriptor(
        context: Context,
        uri: Uri
    ): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val directPfd = resolver.openFileDescriptor(uri, "r")

        if (directPfd != null) {
            if (isSeekable(directPfd)) {
                return@withContext directPfd
            }
            // Non-seekable pipe/socket descriptor: close and stream into a single reusable temp file
            directPfd.close()
        }

        val tempFile = File(context.cacheDir, TEMP_CACHE_FILE_NAME)
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } ?: throw IllegalArgumentException("No se pudo abrir el stream para el URI: $uri")

        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun isSeekable(pfd: ParcelFileDescriptor): Boolean {
        return try {
            Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_CUR) >= 0L
        } catch (_: Throwable) {
            false
        }
    }
}
