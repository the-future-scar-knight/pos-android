package com.portionspot.pos.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates A4 PDFs (prompt §5 storage) using the platform [PdfDocument] — no third-
 * party dependency. Covers the sale receipt, the refund receipt and a customer
 * statement (debt / change / credit) built from the ledger. Files are written to the
 * app's external Documents/PortionSpot folder (browsable, FileProvider-shareable via
 * [PdfFiles]); the same layout works on every supported API level.
 *
 * Distinct from the ESC/POS thermal path (print/EscPos.kt) — that stays for on-paper
 * receipts; these PDFs are for saving, re-opening and sharing over WhatsApp/email.
 */
object PdfDocs {

    // A4 @ 72dpi.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f

    /** One statement line: a dated ledger entry with a signed [amount]. */
    data class StatementEntry(val date: Long, val label: String, val amount: Double)

    fun saleReceipt(context: Context, business: Business, sale: SaleEntity, lines: List<SaleLine>): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c = page.canvas
        val cur = business.currency
        val isQuote = sale.status == "quote"
        var y = header(c, business)

        y = title(c, if (isQuote) "QUOTATION" else "RECEIPT", y)
        val ref = sale.receiptNo ?: sale.id.takeLast(6).uppercase()
        y = kv(c, if (isQuote) "Quote no" else "Receipt no", ref, y)
        y = kv(c, "Date", dateTime(sale.soldAt), y)
        if (isQuote) sale.validUntil?.let { y = kv(c, "Valid until", dateOnly(it), y) }
        sale.createdByName?.takeIf { it.isNotBlank() }?.let { y = kv(c, "Cashier", it, y) }
        y = kv(c, "Customer", sale.customerName?.takeIf { it.isNotBlank() } ?: "Walk-in", y)
        y = rule(c, y)

        y = row(c, "Item", "Qty", "Amount", y, bold = true)
        for (ln in lines) {
            y = row(c, ln.name, trimQty(ln.qty), money(ln.lineTotal, cur), y)
        }
        y = rule(c, y)
        y = totalRow(c, "Subtotal", money(sale.subtotal, cur), y)
        if (sale.discountTotal > 0) y = totalRow(c, "Discount", "-${money(sale.discountTotal, cur)}", y)
        if (sale.taxTotal > 0) y = totalRow(c, "VAT", money(sale.taxTotal, cur), y)
        y = totalRow(c, "TOTAL", money(sale.total, cur), y, bold = true)

        footer(c, business)
        doc.finishPage(page)
        return write(context, doc, "${if (isQuote) "Quote" else "Receipt"}-$ref")
    }

    fun refundReceipt(
        context: Context,
        business: Business,
        refund: Refund,
        lines: List<RefundLine>,
        payments: List<RefundPayment>
    ): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c = page.canvas
        val cur = business.currency
        var y = header(c, business)

        y = title(c, "REFUND", y)
        val ref = refund.saleReceiptNo ?: refund.saleId.takeLast(6).uppercase()
        y = kv(c, "Refund of", "#$ref", y)
        y = kv(c, "Date", dateTime(refund.createdAt), y)
        refund.createdByName?.takeIf { it.isNotBlank() }?.let { y = kv(c, "Cashier", it, y) }
        y = kv(c, "Customer", refund.customerName?.takeIf { it.isNotBlank() } ?: "Walk-in", y)
        refund.reason?.takeIf { it.isNotBlank() }?.let { y = kv(c, "Reason", it, y) }
        y = rule(c, y)

        y = row(c, "Item returned", "Qty", "Value", y, bold = true)
        for (ln in lines) {
            val name = if (!ln.restock) "${ln.name} (not restocked)" else ln.name
            y = row(c, name, trimQty(ln.qty), money(ln.lineTotal, cur), y)
        }
        y = rule(c, y)
        y = totalRow(c, "REFUND TOTAL", money(refund.refundTotal, cur), y, bold = true)
        if (payments.isNotEmpty()) {
            y += 6f
            y = totalRow(c, "Paid back", "", y)
            for (p in payments) {
                y = totalRow(c, "  ${p.method.replaceFirstChar { it.uppercase() }}", money(p.amount, cur), y)
            }
        }
        val owed = refund.refundTotal - payments.sumOf { it.amount }
        if (owed > 0.005) y = totalRow(c, "STILL OWED", money(owed, cur), y, bold = true)

        footer(c, business)
        doc.finishPage(page)
        return write(context, doc, "Refund-$ref")
    }

    /**
     * A customer statement (debt / change-owed / credit). [entries] are the dated
     * ledger rows with signed amounts; the running balance is accumulated as drawn.
     * Paginates when the rows overflow a page.
     */
    fun customerStatement(
        context: Context,
        business: Business,
        heading: String,
        customerName: String,
        entries: List<StatementEntry>
    ): File {
        val doc = PdfDocument()
        val cur = business.currency
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var c = page.canvas
        var y = header(c, business)
        y = title(c, heading.uppercase(), y)
        y = kv(c, "Customer", customerName, y)
        y = kv(c, "Generated", dateTime(System.currentTimeMillis()), y)
        y = rule(c, y)
        y = row(c, "Date", "Detail", "Balance", y, bold = true)

        var running = 0.0
        for (e in entries) {
            if (y > PAGE_H - MARGIN - 60f) {
                footer(c, business, "Page $pageNo")
                doc.finishPage(page)
                pageNo += 1
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                c = page.canvas
                y = MARGIN + 10f
                y = row(c, "Date", "Detail", "Balance", y, bold = true)
            }
            running += e.amount
            val detail = "${e.label}  ${if (e.amount >= 0) "+" else "-"}${money(kotlin.math.abs(e.amount), cur)}"
            y = row(c, dateOnly(e.date), detail, money(running, cur), y)
        }
        y = rule(c, y)
        y = totalRow(c, "CLOSING BALANCE", money(running, cur), y, bold = true)

        footer(c, business, "Page $pageNo")
        doc.finishPage(page)
        return write(context, doc, "Statement-${customerName.replace(Regex("[^A-Za-z0-9]"), "_").take(20)}")
    }

    // ---- drawing helpers -------------------------------------------------

    private val titlePaint = Paint().apply { color = 0xFF111827.toInt(); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
    private val h2Paint = Paint().apply { color = 0xFF111827.toInt(); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
    private val bodyPaint = Paint().apply { color = 0xFF1F2937.toInt(); textSize = 11f; isAntiAlias = true }
    private val boldPaint = Paint().apply { color = 0xFF111827.toInt(); textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
    private val mutedPaint = Paint().apply { color = 0xFF6B7280.toInt(); textSize = 10f; isAntiAlias = true }
    private val linePaint = Paint().apply { color = 0xFFD1D5DB.toInt(); strokeWidth = 0.8f; isAntiAlias = true }

    private val colQty = 360f
    private val colAmt = PAGE_W - MARGIN

    private fun header(c: Canvas, b: Business): Float {
        var y = MARGIN + 18f
        c.drawText(b.name.ifBlank { "PortionSpot Motors" }, MARGIN, y, titlePaint)
        y += 16f
        b.tagline?.takeIf { it.isNotBlank() }?.let { c.drawText(it, MARGIN, y, mutedPaint); y += 13f }
        b.address?.takeIf { it.isNotBlank() }?.let { c.drawText(it, MARGIN, y, mutedPaint); y += 13f }
        listOfNotNull(b.phone?.takeIf { it.isNotBlank() }, b.email?.takeIf { it.isNotBlank() })
            .takeIf { it.isNotEmpty() }?.let { c.drawText(it.joinToString("  ·  "), MARGIN, y, mutedPaint); y += 13f }
        if (b.vatEnabled) b.vatNumber?.takeIf { it.isNotBlank() }?.let { c.drawText("VAT Reg: $it", MARGIN, y, mutedPaint); y += 13f }
        return y + 6f
    }

    private fun title(c: Canvas, text: String, y: Float): Float {
        c.drawText(text, MARGIN, y + 6f, h2Paint)
        return y + 22f
    }

    private fun kv(c: Canvas, k: String, v: String, y: Float): Float {
        c.drawText(k, MARGIN, y, mutedPaint)
        c.drawText(v, MARGIN + 110f, y, bodyPaint)
        return y + 15f
    }

    private fun row(c: Canvas, left: String, mid: String, right: String, y: Float, bold: Boolean = false): Float {
        val p = if (bold) boldPaint else bodyPaint
        c.drawText(clip(left, 300), MARGIN, y, p)
        c.drawText(mid, colQty, y, p)
        c.drawText(right, colAmt - p.measureText(right), y, p)
        return y + 15f
    }

    private fun totalRow(c: Canvas, label: String, value: String, y: Float, bold: Boolean = false): Float {
        val p = if (bold) boldPaint else bodyPaint
        c.drawText(label, colQty - 120f, y, p)
        if (value.isNotEmpty()) c.drawText(value, colAmt - p.measureText(value), y, p)
        return y + 15f
    }

    private fun rule(c: Canvas, y: Float): Float {
        c.drawLine(MARGIN, y - 4f, PAGE_W - MARGIN, y - 4f, linePaint)
        return y + 8f
    }

    private fun footer(c: Canvas, b: Business, extra: String? = null) {
        val msg = b.receiptFooter?.takeIf { it.isNotBlank() } ?: "Thank you for your business!"
        c.drawText(msg, MARGIN, PAGE_H - MARGIN, mutedPaint)
        extra?.let { c.drawText(it, PAGE_W - MARGIN - mutedPaint.measureText(it), PAGE_H - MARGIN, mutedPaint) }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max / 6) s else s.take(max / 6) + "…"

    private fun money(n: Double, currency: String): String {
        val amt = String.format(Locale.US, "%.2f", n)
        return if (currency.equals("USD", ignoreCase = true)) "\$$amt" else "${currency.uppercase()} $amt"
    }

    private fun trimQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

    private fun dateTime(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.UK).format(Date(ms))
    private fun dateOnly(ms: Long) = SimpleDateFormat("dd/MM/yy", Locale.UK).format(Date(ms))

    /** Write the doc to app-external Documents/PortionSpot and return the file. */
    private fun write(context: Context, doc: PdfDocument, baseName: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PortionSpot").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "$baseName-$stamp.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
