package com.portionspot.pos.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.portionspot.pos.PosApp
import com.portionspot.pos.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Passive listener for mobile-money confirmation SMS (prompt §6). Registered in the
 * manifest for `SMS_RECEIVED`, so it runs even when the app is backgrounded or PIN-
 * locked. Flow: reassemble the (possibly multipart) message → [MobileMoneyParser] →
 * on a payment, persist it idempotently and fire a "verify" notification.
 *
 * SMS arrival is the one inherently-online event in the app (the rest is offline-
 * first): everything after parsing — storing, matching, notifying — is local.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val first = messages[0]
        val sender = first.originatingAddress ?: first.displayOriginatingAddress
        // Long payment SMS arrive as several parts; concatenate to one body.
        val body = buildString { messages.forEach { append(it.messageBody ?: "") } }
        val receivedAt = first.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()

        val parsed = MobileMoneyParser.parse(sender, body, receivedAt) ?: return
        val app = context.applicationContext as? PosApp ?: return

        // Hop off the main thread for the DB write; keep the process alive until done.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val user = app.container.authManager.cachedUserOrNull()
                val stored = app.container.repository.recordMobileMoneyReceipt(
                    parsed = parsed,
                    rawBody = body,
                    cashierId = user?.id,
                    cashierName = user?.displayName
                )
                // null ⇒ duplicate txn code already stored; don't re-notify.
                if (stored != null) Notifier.notifyPayment(app, stored)
            } catch (_: Exception) {
                // Never crash the receiver on a malformed message or transient DB error.
            } finally {
                pending.finish()
            }
        }
    }
}
