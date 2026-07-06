package com.portionspot.pos.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.portionspot.pos.MainActivity
import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.MobileMoneyReceipt
import java.util.Locale

/**
 * Local notifications for the POS. Phase 5 uses exactly one channel — incoming
 * mobile-money payments (prompt §6.2) — firing a "verify" prompt the moment a
 * payment SMS is parsed. Tapping deep-links into the Mobile Money screen.
 *
 * This is the on-device half only: the persisted admin notification feed and the
 * N-hour escalation of unverified payments (§8) are Phase 7 and live elsewhere.
 */
object Notifier {

    const val CHANNEL_MOBILE_MONEY = "mobile_money"
    const val CHANNEL_ADMIN = "admin_alerts"

    /** Intent extras MainActivity reads to deep-link to a screen. */
    const val EXTRA_OPEN = "open"
    const val OPEN_MOBILE_MONEY = "mobile_money"
    const val OPEN_ADMIN_ALERTS = "admin_alerts"
    const val EXTRA_RECEIPT_ID = "receiptId"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_MOBILE_MONEY) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MOBILE_MONEY, "Mobile money payments", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Incoming EcoCash / mobile-money payments awaiting verification" }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ADMIN) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ADMIN, "Admin alerts", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Low stock, large sales, aging debts, unverified payments and sync health" }
            )
        }
    }

    /** Post a "payment received — verify" notification for a freshly-parsed receipt. */
    fun notifyPayment(context: Context, receipt: MobileMoneyReceipt) {
        ensureChannel(context)
        // On 13+ posting is a no-op without the runtime grant; check rather than crash.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val who = receipt.matchedCustomerName
            ?: receipt.senderName
            ?: receipt.senderPhone
            ?: "someone"
        val provider = receipt.provider.replaceFirstChar { it.uppercase() }
        val title = "Received ${money(receipt.amount, receipt.currency)} via $provider"
        val text = "From $who — tap to verify"

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, OPEN_MOBILE_MONEY)
            putExtra(EXTRA_RECEIPT_ID, receipt.id)
        }
        val pi = PendingIntent.getActivity(
            context,
            receipt.id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MOBILE_MONEY)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nRef: ${receipt.txnCode}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        // Stable id per txn so a redelivered SMS updates rather than stacks.
        runCatching {
            NotificationManagerCompat.from(context).notify(receipt.txnCode.hashCode(), notification)
        }
    }

    /** Post a persisted admin notification (§8) and deep-link to the admin Alerts feed. */
    fun notifyAdmin(context: Context, n: AppNotification) {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, OPEN_ADMIN_ALERTS)
        }
        val pi = PendingIntent.getActivity(
            context, n.dedupeKey.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ADMIN)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(if (n.severity == "danger") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(n.dedupeKey.hashCode(), notification)
        }
    }

    private fun money(n: Double, currency: String): String {
        val amt = String.format(Locale.US, "%.2f", n)
        return if (currency.equals("USD", ignoreCase = true)) "\$$amt" else "${currency.uppercase()} $amt"
    }
}
