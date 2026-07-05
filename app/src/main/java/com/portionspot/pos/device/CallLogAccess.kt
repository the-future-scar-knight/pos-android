package com.portionspot.pos.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat

/** One recent caller from the device call log. */
data class RecentCall(
    val number: String,
    /** Name cached in the call log by the dialer (present without READ_CONTACTS). */
    val cachedName: String?,
    val timeMillis: Long,
    /** One of [CallLog.Calls.INCOMING_TYPE], OUTGOING_TYPE, MISSED_TYPE, … */
    val type: Int
)

/**
 * Reads recent callers so a customer who just phoned in an order can be added or
 * opened in one tap (prompt §5). Requires [Manifest.permission.READ_CALL_LOG]; every
 * read degrades gracefully — a missing permission or a [SecurityException] yields an
 * empty list rather than throwing.
 */
object CallLogAccess {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Most-recent distinct callers, newest first. De-dupes by [phoneKey] so a caller
     * who rang several times appears once (keeping their latest call). Runs a
     * ContentResolver query — call it off the main thread.
     */
    fun recentCallers(context: Context, limit: Int = 15): List<RecentCall> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )
        val seen = LinkedHashMap<String, RecentCall>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                while (c.moveToNext() && seen.size < limit) {
                    val number = c.getString(numIdx)?.trim().orEmpty()
                    if (number.isBlank()) continue
                    val key = phoneKey(number) ?: number
                    if (seen.containsKey(key)) continue
                    seen[key] = RecentCall(
                        number = number,
                        cachedName = c.getString(nameIdx)?.trim()?.ifBlank { null },
                        timeMillis = c.getLong(dateIdx),
                        type = c.getInt(typeIdx)
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return seen.values.toList()
    }
}
