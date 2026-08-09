package com.portionspot.pos.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.portionspot.pos.MainActivity
import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.MobileMoneyReceipt
import java.util.Locale

/**
 * Local notifications for the POS: incoming mobile-money payments (§6.2) and the
 * persisted admin alert feed (§8). Two jobs — make the phone actually ring, and land
 * the tap on the RECORD the alert is about, not on a screen the owner then has to
 * search through.
 *
 * Sound (the owner's "they make no sound"): a channel's importance, sound and vibration
 * are FROZEN at creation. Editing the constants below can never raise a channel that
 * already exists on his phone, so the ids carry a "_v2" generation and the pre-v2 ones
 * are deleted on first run. Note also that [NotificationCompat.Builder.setPriority] and
 * setSound/setVibrate are IGNORED from API 26 up — only the channel counts there. They
 * are still set because minSdk is 23 and the Sunmi till runs Android 6, where the
 * builder IS the whole story; do not "fix" silence by touching priority again.
 */
object Notifier {

    // Channel ids. The "_v2" generation exists purely because importance/sound cannot be
    // changed after creation — see the class comment. Admin alerts are split by severity
    // so a danger (out of stock, unverified payment gone stale, over credit limit) gets a
    // heads-up banner while a routine warn stays a quiet-but-audible tray entry.
    const val CHANNEL_MOBILE_MONEY = "mobile_money_v2"
    const val CHANNEL_ADMIN_URGENT = "admin_alerts_urgent_v2"
    const val CHANNEL_ADMIN_INFO = "admin_alerts_info_v2"

    /** Pre-v2 ids, stuck at their original importance. Deleted so they stop confusing
     *  the per-channel switches in system settings. Never recreate one: Android restores
     *  a deleted channel's OLD settings if an id is reused. */
    private val LEGACY_CHANNELS = listOf("mobile_money", "admin_alerts")

    /** Intent extras MainActivity reads to deep-link to a screen. */
    const val EXTRA_OPEN = "open"
    const val OPEN_MOBILE_MONEY = "mobile_money"
    const val OPEN_ADMIN_ALERTS = "admin_alerts"
    /** "Open the record named by [EXTRA_REF_TYPE]/[EXTRA_REF_ID]" — MainActivity picks
     *  the screen, because only it knows whether the signed-in person can reach it. */
    const val OPEN_RECORD = "record"
    const val EXTRA_RECEIPT_ID = "receiptId"
    const val EXTRA_REF_TYPE = "refType"
    const val EXTRA_REF_ID = "refId"

    /** The refTypes [com.portionspot.pos.notify.NotificationEngine] stamps that we can
     *  actually land on. Anything else falls back to the alert feed / a plain launch. */
    private val ROUTABLE_REFS =
        setOf("item", "sale", "refund", "customer", "purchase_order", "mm_receipt", "device")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        LEGACY_CHANNELS.forEach { runCatching { mgr.deleteNotificationChannel(it) } }
        if (mgr.getNotificationChannel(CHANNEL_MOBILE_MONEY) == null) {
            mgr.createNotificationChannel(
                loudChannel(
                    CHANNEL_MOBILE_MONEY, "Mobile money payments",
                    NotificationManager.IMPORTANCE_HIGH,
                    "Incoming EcoCash / mobile-money payments awaiting verification"
                )
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ADMIN_URGENT) == null) {
            mgr.createNotificationChannel(
                loudChannel(
                    CHANNEL_ADMIN_URGENT, "Urgent alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                    "Out of stock, payments left unverified, debts over the limit, sync failures"
                )
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ADMIN_INFO) == null) {
            mgr.createNotificationChannel(
                loudChannel(
                    CHANNEL_ADMIN_INFO, "Shop alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                    "Low stock, large sales, aging debts, orders arriving"
                )
            )
        }
    }

    /**
     * A channel that is audible on purpose: an explicit default-notification sound with
     * notification audio attributes (an implicitly-sounded channel has come up silent on
     * some OEM builds) plus vibration, which is OFF by default on a fresh channel — the
     * single likeliest reason a till in a noisy hardware shop is missed.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun loudChannel(id: String, name: String, importance: Int, desc: String) =
        NotificationChannel(id, name, importance).apply {
            description = desc
            enableVibration(true)
            vibrationPattern = VIBRATE_PATTERN
            enableLights(true)
            setShowBadge(true)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

    private val VIBRATE_PATTERN = longArrayOf(0L, 250L, 200L, 250L)

    /** Post a "payment received — verify" notification for a freshly-parsed receipt. */
    fun notifyPayment(context: Context, receipt: MobileMoneyReceipt) {
        ensureChannel(context)
        // On 13+ posting is a no-op without the runtime grant; check rather than crash.
        // (The grant is asked for in the app shell — see NotificationAccessBar in PosUi.)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val who = receipt.matchedCustomerName
            ?: receipt.senderName
            ?: receipt.senderPhone
            ?: "someone"
        val provider = receipt.provider.replaceFirstChar { it.uppercase() }
        val title = "Received ${money(receipt.amount, receipt.currency)} via $provider"
        val text = "From $who — tap to verify"

        val open = deepLinkIntent(
            context, OPEN_MOBILE_MONEY, "mm_receipt", receipt.id, tag = "mm/${receipt.id}"
        ).apply { putExtra(EXTRA_RECEIPT_ID, receipt.id) }
        val pi = PendingIntent.getActivity(
            context,
            requestCode("mm", receipt.id),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = base(context, CHANNEL_MOBILE_MONEY)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\nRef: ${receipt.txnCode}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pi)
            .build()

        // Stable id per txn so a redelivered SMS updates rather than stacks.
        runCatching {
            NotificationManagerCompat.from(context).notify(receipt.txnCode.hashCode(), notification)
        }
    }

    /**
     * Post a persisted notification (§8) as a system heads-up, deep-linked to the thing
     * it is about. [AppNotification.refType]/[AppNotification.refId] already name that
     * record, so they ride along in the intent and MainActivity resolves them against the
     * signed-in person: a cashier must never be dropped into an admin-only screen (it
     * routes isAdmin → AdminRoot), so record links land in the POS shell that everyone
     * can reach, and only a record-less admin alert opens the admin Alerts feed. Anything
     * unroutable just opens the app.
     *
     * The caller is responsible for the audience/role match ([NotificationEngine.audienceMatches]);
     * this only chooses the target once it has decided to fire.
     */
    fun notifyAlert(context: Context, n: AppNotification) {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val routable = n.refType != null && n.refType in ROUTABLE_REFS
        val target = when {
            routable -> OPEN_RECORD
            n.audience == "admin" -> OPEN_ADMIN_ALERTS
            else -> null
        }
        val open = deepLinkIntent(context, target, n.refType, n.refId, tag = "alert/${n.dedupeKey}")
        val pi = PendingIntent.getActivity(
            context, requestCode("alert", n.dedupeKey), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val danger = n.severity == "danger"
        val notification = base(context, if (danger) CHANNEL_ADMIN_URGENT else CHANNEL_ADMIN_INFO)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(if (danger) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(n.dedupeKey.hashCode(), notification)
        }
    }

    /**
     * Builder defaults shared by both kinds. setSound/setVibrate/setDefaults are dead
     * weight from API 26 up (the channel wins) but they are the ONLY thing that makes a
     * sound on the Android 6 Sunmi till, where there are no channels at all.
     */
    private fun base(context: Context, channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setAutoCancel(true)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
            .setVibrate(VIBRATE_PATTERN)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)

    /**
     * The intent a tap delivers to MainActivity (singleTop + CLEAR_TOP: the running
     * instance gets it through onNewIntent instead of being rebuilt, which is what the
     * openTarget state there expects).
     *
     * [tag] must be unique per record. PendingIntent equality ignores EXTRAS and compares
     * component/action/data — without a distinct data Uri every POS notification's
     * PendingIntent is "equal", and FLAG_UPDATE_CURRENT would silently rewrite an earlier
     * notification's extras, sending its tap to the wrong record. The request code is
     * namespaced for the same reason.
     */
    private fun deepLinkIntent(
        context: Context,
        open: String?,
        refType: String?,
        refId: String?,
        tag: String
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        data = Uri.parse("spotpos://open/$tag")
        if (open != null) putExtra(EXTRA_OPEN, open)
        if (refType != null) putExtra(EXTRA_REF_TYPE, refType)
        if (refId != null) putExtra(EXTRA_REF_ID, refId)
    }

    private fun requestCode(kind: String, key: String): Int = "$kind:$key".hashCode()

    private fun money(n: Double, currency: String): String {
        val amt = String.format(Locale.US, "%.2f", n)
        return if (currency.equals("USD", ignoreCase = true)) "\$$amt" else "${currency.uppercase()} $amt"
    }
}
