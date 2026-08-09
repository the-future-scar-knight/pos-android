package com.portionspot.pos.print

import android.graphics.Bitmap
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.baseToSecond
import com.portionspot.pos.data.secondCurrencyActive
import com.portionspot.pos.payments.PaymentMethod
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a raw ESC/POS byte stream for a completed sale.
 *
 * The layout mirrors the React PWA's printer.js (buildEscPos) so receipts look
 * the same across both apps, but this is a clean re-implementation from the
 * open ESC/POS standard — not a copy. (Notably the logo raster uses the correct
 * `GS v 0` opcode 0x30, where printer.js had a 0x00 typo.)
 *
 * Pure/standalone: no Android UI, just bytes. Text is encoded Latin-1 so common
 * accented characters survive on CP-style thermal heads; pure ASCII is identical.
 */
/**
 * Receipt look knobs that aren't on the synced [Business] record — mirrors the
 * device-local ShopPrefs. Defaults reproduce the previous fixed layout.
 */
data class ReceiptStyle(
    val largeText: Boolean = false,   // 2x text height (legacy "large receipt text")
    val feedLines: Int = 2,           // blank lines fed before the cut
    val boldName: Boolean = true,
    val showLogo: Boolean = true,
    val showTagline: Boolean = true,
    val showAddress: Boolean = true,
    val showVat: Boolean = true,
    val showCashier: Boolean = true,
    val showPayment: Boolean = true,
    val showChange: Boolean = true,
    val boldTotals: Boolean = true,
    val showFooter: Boolean = true,
    // Second currency (dual-currency shops): when a non-blank code + positive rate
    // are set, the receipt also prints the total (and cash change) converted to it.
    val secondCode: String = "",
    val secondRate: Double = 0.0,
)

object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D

    /** Build the full receipt for a completed [sale]. */
    fun receipt(
        business: Business,
        sale: SaleEntity,
        lines: List<SaleLine>,
        paperWidth: String = "58mm",
        style: ReceiptStyle = ReceiptStyle(),
        logo: Bitmap? = null
    ): ByteArray {
        val isQuote = sale.status == "quote"
        val charW = 1
        val charH = if (style.largeText) 2 else 1
        // GS ! n: bits 4-6 = width-1, bits 0-2 = height-1
        val sizeN = ((charW - 1) shl 4) or (charH - 1)
        val w = (if (paperWidth == "80mm") 48 else 32) / charW

        val out = ByteArrayOutputStream()
        fun cmd(vararg b: Int) { for (x in b) out.write(x) }
        fun line(s: String = "") { out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0x0A) }

        // Init: reset, clear emphasis, magnification 1x1, Font A, then user size.
        cmd(ESC, 0x40)            // ESC @  — initialise
        cmd(ESC, 0x21, 0x00)      // ESC !  — clear emphasis/double size
        cmd(GS, 0x21, 0x00)       // GS  !  — magnification 1x1
        cmd(ESC, 0x4D, 0x00)      // ESC M  — Font A
        if (sizeN != 0) cmd(GS, 0x21, sizeN)

        // Logo (centred), optional.
        if (style.showLogo && logo != null) {
            val raster = logoToRaster(logo, if (paperWidth == "80mm") 320 else 160)
            if (raster != null) {
                cmd(ESC, 0x61, 0x01)
                out.write(raster)
                line()
            }
        }

        // Header — shop name centred, bold per style.
        cmd(ESC, 0x61, 0x01)
        if (style.boldName) cmd(ESC, 0x45, 0x01)
        line(business.name.ifBlank { "PORTIONSPOT MOTORS" })
        cmd(ESC, 0x45, 0x00)
        if (style.showTagline) business.tagline?.takeIf { it.isNotBlank() }?.let { line(it) }
        if (style.showAddress) {
            business.address?.takeIf { it.isNotBlank() }?.let { line(it) }
            business.phone?.takeIf { it.isNotBlank() }?.let { line(it) }
            business.email?.takeIf { it.isNotBlank() }?.let { line(it) }
            business.website?.takeIf { it.isNotBlank() }?.let { line(it) }
        }
        // VAT / ZIMRA registration line (only when the shop is VAT-registered).
        if (style.showVat && business.vatEnabled) {
            business.vatNumber?.takeIf { it.isNotBlank() }?.let { line("VAT Reg: $it") }
        }

        cmd(ESC, 0x61, 0x00)
        line(dashes(w))

        // *** RECEIPT ***  /  *** QUOTATION ***
        cmd(ESC, 0x61, 0x01); cmd(ESC, 0x45, 0x01)
        line(if (isQuote) "*** QUOTATION ***" else "*** RECEIPT ***")
        cmd(ESC, 0x45, 0x00); cmd(ESC, 0x61, 0x00)

        val ref = sale.receiptNo ?: sale.id.takeLast(6).uppercase()
        val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.UK).format(Date(sale.soldAt))
        line(twoCol("Ref: $ref", dateStr, w))
        line("Customer: ${sale.customerName?.takeIf { it.isNotBlank() } ?: "Walk-in"}")
        if (style.showCashier) sale.createdByName?.takeIf { it.isNotBlank() }?.let { line("Served by: $it") }
        if (isQuote) {
            val vu = sale.validUntil?.let { SimpleDateFormat("dd/MM/yy", Locale.UK).format(Date(it)) } ?: "-"
            line("Valid until: $vu")
        }
        line(dashes(w))

        // Items
        val cur = business.currency
        lines.forEach { ln ->
            cmd(ESC, 0x45, 0x01)
            line(ln.name.take(w))
            cmd(ESC, 0x45, 0x00)
            // Per-line markup is folded into the shown price/amount so it reads as the
            // ordinary price. Markup is a cashier-only concept and is NEVER itemised on
            // the customer's receipt.
            val shownLine = ln.lineTotal + ln.lineMarkup
            val shownUnit = if (ln.qty != 0.0) shownLine / ln.qty else ln.unitPrice
            line(twoCol("  x${trimQty(ln.qty)} @ ${fmt(shownUnit, cur)}", fmt(shownLine, cur), w))
            if (ln.lineDiscount > 0) line(twoCol("  Discount", "-${fmt(ln.lineDiscount, cur)}", w))
        }
        line(dashes(w))

        // Totals — the subtotal carries the folded-in markup so the arithmetic
        // reconciles (Subtotal - Discount + VAT = TOTAL) without ever naming markup.
        line(twoCol("Subtotal", fmt(sale.subtotal + sale.markupTotal, cur), w))
        if (sale.discountTotal > 0) line(twoCol("Discount", "-${fmt(sale.discountTotal, cur)}", w))
        if (sale.taxTotal > 0) {
            val vatLabel = if (business.vatEnabled) "VAT ${trimQty(business.vatPercent)}%" else "VAT"
            line(twoCol(vatLabel, fmt(sale.taxTotal, cur), w))
        }
        if (style.boldTotals) cmd(ESC, 0x45, 0x01)
        line(twoCol("TOTAL", fmt(sale.total, cur), w))
        if (style.boldTotals) cmd(ESC, 0x45, 0x00)

        // Dual-currency: show the grand total converted to the second currency.
        val cur2On = secondCurrencyActive(style.secondCode, style.secondRate)
        if (cur2On) {
            line(twoCol("  @ ${trimQty(style.secondRate)}", fmt(baseToSecond(sale.total, style.secondRate), style.secondCode), w))
        }

        // Payment — credit, cash + change, or another tender with its reference.
        // A quote takes no payment, and the payment block can be hidden per settings.
        if (!isQuote && style.showPayment) when (sale.paymentMethod) {
            "credit" -> {
                cmd(ESC, 0x45, 0x01)
                line(twoCol("ON CREDIT", fmt(sale.total, cur), w))
                cmd(ESC, 0x45, 0x00)
            }
            "cash" -> {
                line(twoCol("Cash", fmt(sale.tendered ?: sale.total, cur), w))
            }
            else -> {
                val label = PaymentMethod.fromCode(sale.paymentMethod)?.label ?: "Paid"
                line(twoCol(label, fmt(sale.total, cur), w))
                sale.paymentRef?.takeIf { it.isNotBlank() }?.let { line("Ref: $it") }
            }
        }

        // Change we couldn't hand over in full => still owed (any tender, not just cash).
        // Never prints the change actually given, only the outstanding remainder.
        if (!isQuote && style.showPayment && style.showChange) {
            sale.changeOwed?.takeIf { it > 0 }?.let { owed ->
                line(twoCol("Change owed", fmt(owed, cur), w))
                if (cur2On) {
                    line(twoCol("  @ ${trimQty(style.secondRate)}", fmt(baseToSecond(owed, style.secondRate), style.secondCode), w))
                }
            }
        }

        // Footer (centred)
        if (style.showFooter) {
            business.receiptFooter?.takeIf { it.isNotBlank() }?.let { footer ->
                line(dashes(w))
                cmd(ESC, 0x61, 0x01)
                footer.split("\n").forEach { line(it) }
                cmd(ESC, 0x61, 0x00)
            }
        }

        cmd(ESC, 0x64, style.feedLines.coerceIn(0, 8))  // feed N lines (cut clearance)
        cmd(GS, 0x56, 0x41, 0x00)   // GS V A 0 — cut
        return out.toByteArray()
    }

    /** Build a refund ticket for [refund] and its returned [lines] + [payments]. */
    fun refund(
        business: Business,
        refund: Refund,
        lines: List<RefundLine>,
        payments: List<RefundPayment>,
        paperWidth: String = "58mm",
        style: ReceiptStyle = ReceiptStyle(),
        logo: Bitmap? = null
    ): ByteArray {
        val charH = if (style.largeText) 2 else 1
        val sizeN = (charH - 1)
        val w = if (paperWidth == "80mm") 48 else 32

        val out = ByteArrayOutputStream()
        fun cmd(vararg b: Int) { for (x in b) out.write(x) }
        fun line(s: String = "") { out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0x0A) }

        cmd(ESC, 0x40)
        cmd(ESC, 0x21, 0x00)
        cmd(GS, 0x21, 0x00)
        cmd(ESC, 0x4D, 0x00)
        if (sizeN != 0) cmd(GS, 0x21, sizeN)

        if (style.showLogo && logo != null) {
            val raster = logoToRaster(logo, if (paperWidth == "80mm") 320 else 160)
            if (raster != null) { cmd(ESC, 0x61, 0x01); out.write(raster); line() }
        }

        // Header
        cmd(ESC, 0x61, 0x01)
        if (style.boldName) cmd(ESC, 0x45, 0x01)
        line(business.name.ifBlank { "PORTIONSPOT MOTORS" })
        cmd(ESC, 0x45, 0x00)
        if (style.showAddress) {
            business.phone?.takeIf { it.isNotBlank() }?.let { line(it) }
        }
        cmd(ESC, 0x61, 0x00)
        line(dashes(w))

        // *** REFUND ***
        cmd(ESC, 0x61, 0x01); cmd(ESC, 0x45, 0x01)
        line("*** REFUND ***")
        cmd(ESC, 0x45, 0x00); cmd(ESC, 0x61, 0x00)

        val ref = refund.saleReceiptNo ?: refund.saleId.takeLast(6).uppercase()
        val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.UK).format(Date(refund.createdAt))
        line(twoCol("Refund of #$ref", dateStr, w))
        line("Customer: ${refund.customerName?.takeIf { it.isNotBlank() } ?: "Walk-in"}")
        refund.createdByName?.takeIf { it.isNotBlank() }?.let { line("Cashier: $it") }
        refund.reason?.takeIf { it.isNotBlank() }?.let { line("Reason: ${it.take(w - 8)}") }
        line(dashes(w))

        val cur = business.currency
        line("Items returned:")
        lines.forEach { ln ->
            cmd(ESC, 0x45, 0x01); line(ln.name.take(w)); cmd(ESC, 0x45, 0x00)
            // lineTotal is what actually went back: net of the line's own discount and
            // inclusive of any cashier markup. So it is NOT qty x unitPrice, and printing
            // the raw unitPrice beside it would read as bad arithmetic at the counter.
            // Derive the rate from the amount instead — the same convention the sale
            // receipt uses, which also keeps markup un-itemised.
            val shownUnit = if (ln.qty != 0.0) ln.lineTotal / ln.qty else ln.unitPrice
            line(twoCol("  x${trimQty(ln.qty)} @ ${fmt(shownUnit, cur)}", fmt(ln.lineTotal, cur), w))
            if (!ln.restock) line("  (not restocked)")
        }
        line(dashes(w))

        cmd(ESC, 0x45, 0x01)
        line(twoCol("REFUND TOTAL", fmt(refund.refundTotal, cur), w))
        cmd(ESC, 0x45, 0x00)

        if (payments.isNotEmpty()) {
            line("Paid back:")
            payments.forEach { p ->
                val label = PaymentMethod.fromCode(p.method)?.label
                    ?: p.method.replaceFirstChar { it.uppercase() }
                line(twoCol("  $label", fmt(p.amount, cur), w))
            }
        }
        val owed = refund.refundTotal - payments.sumOf { it.amount }
        if (owed > 0.005) {
            cmd(ESC, 0x45, 0x01)
            line(twoCol("STILL OWED", fmt(owed, cur), w))
            cmd(ESC, 0x45, 0x00)
        }

        if (style.showFooter) {
            business.receiptFooter?.takeIf { it.isNotBlank() }?.let { footer ->
                line(dashes(w))
                cmd(ESC, 0x61, 0x01)
                footer.split("\n").forEach { line(it) }
                cmd(ESC, 0x61, 0x00)
            }
        }

        cmd(ESC, 0x64, style.feedLines.coerceIn(0, 8))
        cmd(GS, 0x56, 0x41, 0x00)
        return out.toByteArray()
    }

    /** A tiny ticket used by the "Test print" button in Settings. */
    fun testTicket(business: Business): ByteArray {
        val out = ByteArrayOutputStream()
        fun cmd(vararg b: Int) { for (x in b) out.write(x) }
        fun line(s: String = "") { out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0x0A) }
        cmd(ESC, 0x40); cmd(ESC, 0x4D, 0x00)
        cmd(ESC, 0x61, 0x01); cmd(ESC, 0x45, 0x01)
        line(business.name.ifBlank { "ON-SPOT POS" })
        cmd(ESC, 0x45, 0x00)
        line("Printer test OK")
        line(SimpleDateFormat("dd/MM/yy HH:mm", Locale.UK).format(Date()))
        cmd(ESC, 0x64, 0x03)
        cmd(GS, 0x56, 0x41, 0x00)
        return out.toByteArray()
    }

    // ---- helpers ---------------------------------------------------------

    private fun fmt(n: Double, currency: String): String {
        val amt = String.format(Locale.US, "%.2f", n)
        return if (currency.equals("USD", ignoreCase = true)) "$$amt"
        else "${currency.uppercase()} $amt"
    }

    private fun dashes(w: Int) = "-".repeat(w)

    private fun twoCol(left: String, right: String, w: Int): String {
        if (left.length + right.length + 1 > w) {
            val keep = (w - right.length - 1).coerceAtLeast(0)
            return left.take(keep) + " " + right
        }
        return left + " ".repeat(w - left.length - right.length) + right
    }

    private fun trimQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

    /**
     * Convert a logo bitmap into a `GS v 0` raster bit-image command, scaled to
     * fit [maxWidthDots] and thresholded to 1-bit black/white. Returns null if
     * the image is empty.
     */
    private fun logoToRaster(src: Bitmap, maxWidthDots: Int): ByteArray? {
        val scale = min(1.0, maxWidthDots.toDouble() / max(src.width, 1))
        val dotW = min(maxWidthDots, (src.width * scale).roundToInt())
        val dotH = (src.height * scale).roundToInt()
        if (dotW <= 0 || dotH <= 0) return null

        val scaled = Bitmap.createScaledBitmap(src, dotW, dotH, true)
        val bpr = ceil(dotW / 8.0).toInt()         // bytes per row
        val buf = ByteArray(8 + bpr * dotH)

        // GS v 0  m  xL xH  yL yH  [data...]   (m = 0 normal)
        buf[0] = GS.toByte(); buf[1] = 0x76; buf[2] = 0x30; buf[3] = 0x00
        buf[4] = (bpr and 0xFF).toByte(); buf[5] = ((bpr shr 8) and 0xFF).toByte()
        buf[6] = (dotH and 0xFF).toByte(); buf[7] = ((dotH shr 8) and 0xFF).toByte()

        for (row in 0 until dotH) {
            for (col in 0 until dotW) {
                val px = scaled.getPixel(col, row)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val alpha = (px ushr 24) and 0xFF
                // Treat transparent pixels as white so PNG logos don't print solid.
                val gray = if (alpha < 128) 255 else (r * 299 + g * 587 + b * 114) / 1000
                if (gray < 128) {
                    val idx = 8 + row * bpr + (col shr 3)
                    buf[idx] = (buf[idx].toInt() or (0x80 shr (col and 7))).toByte()
                }
            }
        }
        return buf
    }
}
