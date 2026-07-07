package com.portionspot.pos.sync

import com.portionspot.pos.data.Setting
import com.portionspot.pos.data.SettingDao

/** The bring-your-own database connection the user entered in Settings. */
data class Connection(val url: String, val anonKey: String)

/**
 * Reads/writes the sync configuration from the local [SettingDao] key/value
 * store: the user-supplied Supabase connection and the per-table pull cursors.
 * Nothing here ever leaves the device.
 *
 * Time cursors are fixed-format UTC ISO strings (see [IsoTime]); because the
 * format is fixed-width, a plain string `>` comparison is also chronological,
 * which is exactly what the `updated_at=gt.<cursor>` pull relies on.
 */
class SyncConfig(private val dao: SettingDao) {

    suspend fun connection(): Connection? {
        val url = dao.get(KEY_URL)?.trim()?.trimEnd('/')
        val key = dao.get(KEY_KEY)?.trim()
        return if (!url.isNullOrBlank() && !key.isNullOrBlank()) Connection(url, key) else null
    }

    suspend fun isConfigured(): Boolean = connection() != null

    suspend fun saveConnection(url: String, anonKey: String) {
        dao.put(Setting(KEY_URL, url.trim().trimEnd('/')))
        dao.put(Setting(KEY_KEY, anonKey.trim()))
    }

    suspend fun clearConnection() {
        dao.delete(KEY_URL)
        dao.delete(KEY_KEY)
        // Forget where we were so a future reconnect re-pulls from scratch.
        TABLES.forEach { dao.delete(cursorKey(it)) }
        dao.delete(KEY_LAST_SYNC)
    }

    /**
     * Master switch for PUSHING local data up (Stage 2). Default OFF so the repoint
     * ships pull-only: nothing this device holds can reach the shared production DB
     * until the owner has verified the pull and cleared any local test data.
     */
    suspend fun pushEnabled(): Boolean = dao.get(KEY_PUSH)?.toBooleanStrictOrNull() ?: false

    suspend fun setPushEnabled(on: Boolean) = dao.put(Setting(KEY_PUSH, on.toString()))

    suspend fun cursor(table: String): String = dao.get(cursorKey(table)) ?: IsoTime.EPOCH

    suspend fun setCursor(table: String, value: String) =
        dao.put(Setting(cursorKey(table), value))

    suspend fun lastSyncAt(): Long? = dao.get(KEY_LAST_SYNC)?.toLongOrNull()

    suspend fun setLastSyncAt(ts: Long) = dao.put(Setting(KEY_LAST_SYNC, ts.toString()))

    companion object {
        const val KEY_URL = "supabase_url"
        const val KEY_KEY = "supabase_key"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_PUSH = "sync_push_enabled"

        /** Cloud tables Android syncs (the shared web-POS schema). */
        val TABLES = listOf("products", "customers", "sales")

        private fun cursorKey(table: String) = "cursor_$table"
    }
}
