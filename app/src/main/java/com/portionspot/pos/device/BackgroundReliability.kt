package com.portionspot.pos.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps the POS delivering payment SMS and admin alerts while it is backgrounded or
 * closed (prompt §6/§8). The app's background work — the manifest [SmsReceiver], the
 * 30-min admin-alert worker, and the on-open inbox backfill — all fail for the same
 * reason on aggressive OEMs (Samsung especially): Doze / battery optimization freezes
 * the process. The fix is to ask the owner to exempt the app from battery optimization.
 *
 * This is the standard, lightweight approach (a persistent foreground service is the
 * heavier alternative). We ask ONCE so we never nag; the owner can always grant it
 * later from system settings.
 */
object BackgroundReliability {

    private const val PREFS = "bg_reliability"
    private const val KEY_ASKED = "battery_opt_asked"

    /** True when the app is already exempt from battery optimization (Doze). */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun alreadyAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    private fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * Show the system "allow background activity" (ignore battery optimization) dialog
     * the first time only, and only if we're not already exempt. No-ops afterwards so
     * the owner is never nagged. Call from an Activity during launch.
     */
    @SuppressLint("BatteryLife") // sideloaded POS: uninterrupted payment/alert delivery is the point
    fun requestExemptionOnce(context: Context) {
        if (isExempt(context) || alreadyAsked(context)) return
        markAsked(context)
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
