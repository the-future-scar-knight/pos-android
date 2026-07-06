package com.portionspot.pos.pdf

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share / re-open the PDFs produced by [PdfDocs] (prompt §5). Files live in the app's
 * external Documents/PortionSpot folder and are exposed through the manifest
 * FileProvider (`${applicationId}.fileprovider`) so a `content://` URI can be granted
 * to WhatsApp, Gmail, Drive or a PDF viewer without any storage permission.
 */
object PdfFiles {

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Open the system share sheet (WhatsApp/email/etc.) for [file]. */
    fun share(context: Context, file: File, subject: String) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(context, "No app to share the PDF", Toast.LENGTH_SHORT).show() }
    }

    /** Open [file] in a PDF viewer. */
    fun view(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show() }
    }
}
