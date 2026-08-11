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
        resetCursors()
        dao.delete(KEY_LAST_SYNC)
        dao.delete(KEY_LAST_UPLOAD)
        dao.delete(KEY_LAST_DOWNLOAD)
    }

    /**
     * Master switch for PUSHING local data up (Stage 2). Default OFF so the repoint
     * ships pull-only: nothing this device holds can reach the shared production DB
     * until the owner has verified the pull and cleared any local test data.
     */
    suspend fun pushEnabled(): Boolean = dao.get(KEY_PUSH)?.toBooleanStrictOrNull() ?: false

    suspend fun setPushEnabled(on: Boolean) = dao.put(Setting(KEY_PUSH, on.toString()))

    /**
     * The SHOP's business id, learned from the cloud `businesses` row on first connect.
     *
     * A device generates its own business uuid on first run, long before it has ever seen
     * a database. Push that id and the rows upload with a 2xx and are then invisible to
     * every other client, because the web filters by ITS business — no error, nothing to
     * find, just takings that quietly belong to nobody.
     *
     * Kept as a separate value rather than rewriting the local row's primary key: the
     * local id is referenced by every item, sale and customer on the device, and moving a
     * primary key to fix a wire field would be a mass repoint of the whole database to
     * solve a problem that only exists at the boundary.
     */
    suspend fun cloudBusinessId(): String? = dao.get(KEY_CLOUD_BID)?.trim()?.ifBlank { null }

    suspend fun setCloudBusinessId(id: String) = dao.put(Setting(KEY_CLOUD_BID, id.trim()))

    suspend fun cursor(table: String): String = dao.get(cursorKey(table)) ?: IsoTime.EPOCH

    suspend fun setCursor(table: String, value: String) =
        dao.put(Setting(cursorKey(table), value))

    /** Forget every pull cursor so the next sync re-pulls the whole shared dataset
     *  from scratch (used after a local-data reset). Connection is left intact.
     *  Sweeps the pre-repoint cursor names too, so an upgraded device doesn't keep a
     *  cursor for a table this build no longer knows about. */
    suspend fun resetCursors() =
        (TABLES + LEGACY_CURSOR_TABLES).distinct().forEach { dao.delete(cursorKey(it)) }

    suspend fun lastSyncAt(): Long? = dao.get(KEY_LAST_SYNC)?.toLongOrNull()

    suspend fun setLastSyncAt(ts: Long) = dao.put(Setting(KEY_LAST_SYNC, ts.toString()))

    /** When this device last pushed >0 rows UP (distinct from a pull-only pass). */
    suspend fun lastUploadAt(): Long? = dao.get(KEY_LAST_UPLOAD)?.toLongOrNull()

    suspend fun setLastUploadAt(ts: Long) = dao.put(Setting(KEY_LAST_UPLOAD, ts.toString()))

    /** When this device last pulled >0 rows DOWN. */
    suspend fun lastDownloadAt(): Long? = dao.get(KEY_LAST_DOWNLOAD)?.toLongOrNull()

    suspend fun setLastDownloadAt(ts: Long) = dao.put(Setting(KEY_LAST_DOWNLOAD, ts.toString()))

    companion object {
        const val KEY_URL = "supabase_url"
        const val KEY_KEY = "supabase_key"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_UPLOAD = "last_upload_at"
        const val KEY_LAST_DOWNLOAD = "last_download_at"
        const val KEY_PUSH = "sync_push_enabled"
        const val KEY_CLOUD_BID = "cloud_business_id"

        /**
         * Cloud tables Android syncs — the REAL web-POS schema, verified against the live
         * project rather than recalled.
         *
         * Every name here is the cloud's, and several differ from the Room table of the
         * same data: local `credit_transactions` is cloud `credit_txns`, local `cash_txns`
         * is cloud `cash_movements`, local `audit_log` is cloud `audit_entries`. The
         * mapping lives in Dtos.kt; this list exists so each table gets a pull cursor and
         * so `clearConnection`/`resetCursors` can forget every one of them.
         *
         * ★ There is no `products`. The catalogue is `items`, and it always was on the web
         * side. Pointing a till at a table the shared database does not have is how you get
         * two catalogues that both work and never see each other.
         */
        val TABLES = listOf(
            // ── Till core ──
            // items is PULL-ONLY: the web owns the catalogue. Stock is not written here
            // either — `stock_movements` is the authority and `items.stock_qty` is a
            // derived cache every device recomputes after a pull.
            "items",
            // The tags on each item — here, the cars a part fits. Pull-only for the same
            // reason as `items`, and on its own cursor so a catalogue that gains 600
            // fitments doesn't re-read the whole product list to find them.
            "item_attributes",
            "customers",
            // A sale is three rows, not one JSON blob: the header plus its line and tender
            // children. They carry their own cursors so a pull that dies part-way resumes
            // where it stopped instead of re-reading every sale the shop has ever made.
            "sales", "sale_items", "sale_payments",
            // Refunds are FIRST-CLASS on the web now — real tables, not negative-total
            // sales rows. That also restores the refund→sale link the old shared schema
            // dropped, so a returned line can finally be traced to what it came off.
            "refunds", "refund_items", "refund_payments",
            "credit_txns",
            "stock_movements",
            "mobile_money_receipts",
            // ── Cash: one drawer, one shift ──
            // cash_sessions is unique per BUSINESS while open, not per till (see the merge
            // rule in PosSyncEngine): the shop has one physical drawer, so it has exactly
            // one thing to count.
            "cash_sessions", "cash_movements",
            // ── Accounting spine + supplier orders ──
            "expenses", "suppliers", "purchase_orders", "purchase_order_items",
            // Append-only audit trail — receipt edits, till shortages/overages and voids
            // recorded on a cashier phone become visible on the owner's phone.
            "audit_entries",
            // Roster + per-staff permission revocations (staff.permissions jsonb).
            "staff"
        )

        /**
         * Local Room tables with NO cloud home, listed so it is a decision rather than an
         * oversight. Each is device-local until the web grows a table for it:
         *
         *  - `notifications`   the alert feed. Cross-device alerting needs a shared table;
         *                      until then an alert raised on a cashier phone stays there.
         *  - `staff_requests`  the admin⇄cashier approval channel (credit-limit asks).
         *  - `day_closes`      the till/safe day-close record. Its cloud analogue is
         *                      `cash_sessions`, but the two are not the same shape —
         *                      mapping them is its own piece of work.
         *  - `outside_funds`   owner money vs loan. No cloud column expresses the split.
         *  - `settings`        device settings (printer, theme). Deliberately never synced.
         */
        val LOCAL_ONLY_TABLES = listOf(
            "notifications", "staff_requests", "day_closes", "outside_funds", "settings"
        )

        /**
         * Cursor keys written by builds that predate the repoint. A device upgrading into
         * this build already has `cursor_products`, `cursor_credit_transactions` and the
         * rest sitting in its settings store; because those names are no longer in
         * [TABLES], nothing would ever clear them again. They are harmless but they are
         * also a lie about what this device has seen, and a stale cursor is exactly the
         * kind of thing that later looks like "the sync silently skipped older rows".
         *
         * Listed only for cleanup — never for reading.
         */
        private val LEGACY_CURSOR_TABLES = listOf(
            "products", "credit_transactions", "cash_txns", "audit_log", "notifications",
            "staff_requests"
        )

        private fun cursorKey(table: String) = "cursor_$table"
    }
}
