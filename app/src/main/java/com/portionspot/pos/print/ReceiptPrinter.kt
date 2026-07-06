package com.portionspot.pos.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine

/**
 * Ties the pieces together: decode the saved logo, build the ESC/POS stream for
 * a sale, and send it to the configured paired printer.
 */
object ReceiptPrinter {

    suspend fun print(
        context: Context,
        business: Business,
        sale: SaleEntity,
        lines: List<SaleLine>,
        style: ReceiptStyle = ReceiptStyle(),
        target: String = "bluetooth"
    ): PrintResult {
        val data = EscPos.receipt(
            business = business,
            sale = sale,
            lines = lines,
            paperWidth = business.paperWidth,
            style = style,
            logo = loadLogo(context, business.logoUri)
        )
        return dispatch(context, business, data, target)
    }

    suspend fun printRefund(
        context: Context,
        business: Business,
        refund: Refund,
        lines: List<RefundLine>,
        payments: List<RefundPayment>,
        style: ReceiptStyle = ReceiptStyle(),
        target: String = "bluetooth"
    ): PrintResult {
        val data = EscPos.refund(
            business = business,
            refund = refund,
            lines = lines,
            payments = payments,
            paperWidth = business.paperWidth,
            style = style,
            logo = loadLogo(context, business.logoUri)
        )
        return dispatch(context, business, data, target)
    }

    suspend fun testPrint(
        context: Context,
        business: Business,
        target: String = "bluetooth"
    ): PrintResult {
        val data = EscPos.testTicket(business)
        return dispatch(context, business, data, target)
    }

    /**
     * Route the built ESC/POS stream to the chosen printer (prompt §10):
     * "sunmi" internal printer · "rawbt" RawBT service · "bluetooth" paired ESC/POS.
     */
    private suspend fun dispatch(
        context: Context,
        business: Business,
        data: ByteArray,
        target: String
    ): PrintResult = when (target) {
        "sunmi" -> SunmiPrinter.send(context, data)
        "rawbt" -> RawBtPrinter.send(context, data)
        else -> {
            val mac = business.btPrinterMac
                ?: return PrintResult.Error("No printer selected. Go to Settings → Printer.")
            BluetoothPrinter.send(context, mac, data)
        }
    }

    private fun loadLogo(context: Context, uriString: String?): Bitmap? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString)).use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }
}
