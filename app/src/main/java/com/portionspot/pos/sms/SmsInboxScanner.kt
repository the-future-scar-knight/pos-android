package com.portionspot.pos.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.portionspot.pos.data.PosRepository

/**
 * Backfills mobile-money receipts from the device SMS inbox (prompt §6).
 *
 * Why this exists: the passive [SmsReceiver] only catches a message while the app's
 * process is alive. On aggressive OEMs (Samsung especially) the app is killed in the
 * background, so a payment that lands while the POS is closed is never seen — exactly
 * the gap the shop owner hit on a real device. On open (and on demand) we re-scan the
 * recent inbox and re-parse every message.
 *
 * Safe to run repeatedly: [PosRepository.recordMobileMoneyReceipt] is idempotent on
 * the unique (businessId, txnCode) index, so an already-stored payment is skipped and
 * only genuinely-new ones are inserted. Returns how many NEW receipts were captured.
 */
object SmsInboxScanner {

    /** Scan at most this many of the most-recent inbox messages — plenty for reconciliation. */
    private const val MAX_SCAN = 500

    suspend fun backfill(
        context: Context,
        repo: PosRepository,
        cashierId: String?,
        cashierName: String?,
    ): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return 0

        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        var newCount = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                cols,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                var scanned = 0
                while (c.moveToNext() && scanned < MAX_SCAN) {
                    scanned++
                    val sender = if (iAddr >= 0) c.getString(iAddr) else null
                    val body = if (iBody >= 0) c.getString(iBody) else null
                    val date = if (iDate >= 0) c.getLong(iDate) else System.currentTimeMillis()
                    val parsed = MobileMoneyParser.parse(sender, body, date) ?: continue
                    val stored = repo.recordMobileMoneyReceipt(
                        parsed = parsed,
                        rawBody = body ?: "",
                        cashierId = cashierId,
                        cashierName = cashierName,
                    )
                    if (stored != null) newCount++
                }
            }
        } catch (_: Exception) {
            // A provider read can fail on some OEMs/permission states — never crash the
            // caller; the live receiver still works and the next scan can retry.
        }
        return newCount
    }
}
