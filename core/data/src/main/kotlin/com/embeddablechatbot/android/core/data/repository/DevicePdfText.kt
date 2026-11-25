package com.embeddablechatbot.android.core.data.repository

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a PDF's text on the phone (Android 15+ `PdfRenderer` text API), for servers that have no
 * model able to read PDFs. The file is untrusted: pages and characters are capped, and the temporary
 * copy in app-private cache is deleted afterwards.
 */
@Singleton
class DevicePdfText @Inject constructor(@param:ApplicationContext private val context: Context) {
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    suspend fun text(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!available || bytes.size < 5 || String(bytes, 0, 5, Charsets.US_ASCII) != "%PDF-") return@withContext null
        val file = File.createTempFile("mail_pdf", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> read(fd) }
        } catch (_: Exception) {
            null // Corrupt, encrypted or unsupported: the caller reports the server's message.
        } finally {
            file.delete()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun read(fd: ParcelFileDescriptor): String? = PdfRenderer(fd).use { pdf ->
        val out = StringBuilder()
        for (index in 0 until minOf(pdf.pageCount, MAX_PAGES)) {
            pdf.openPage(index).use { page -> page.textContents.forEach { out.append(it.text).append('\n') } }
            out.append('\n')
            if (out.length >= MAX_CHARS) break
        }
        out.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_CHARS).ifBlank { null }
    }

    private companion object {
        const val MAX_PAGES = 60
        const val MAX_CHARS = 20_000
    }
}
