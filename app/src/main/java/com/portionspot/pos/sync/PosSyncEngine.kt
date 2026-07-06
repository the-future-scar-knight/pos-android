package com.portionspot.pos.sync

import com.portionspot.pos.data.BusinessDao
import com.portionspot.pos.data.CreditDao
import com.portionspot.pos.data.CustomerDao
import com.portionspot.pos.data.ItemDao
import com.portionspot.pos.data.SaleDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

/** Outcome of one sync pass, surfaced to the UI. */
sealed class SyncOutcome {
    data class Success(val pushed: Int, val pulled: Int) : SyncOutcome()
    object NotConfigured : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/**
 * Two-way sync against the user's own Supabase. Local Room is always the
 * source of truth; this just mirrors it to/from the cloud:
 *
 *   1. PUSH every locally-dirty row (parents first), then mark it clean.
 *   2. PULL rows changed since our per-table cursor, applying last-write-wins
 *      by `updated_at`.
 *
 * Offline-first falls out naturally: when there's no network the push/pull
 * simply throw, we report Failed, and the dirty rows wait for the next pass.
 */
class PosSyncEngine(
    private val businessDao: BusinessDao,
    private val itemDao: ItemDao,
    private val saleDao: SaleDao,
    private val customerDao: CustomerDao,
    private val creditDao: CreditDao,
    private val config: SyncConfig,
    /** Current signed-in user's JWT for RLS; null falls back to anon (denied). */
    private val accessToken: () -> String? = { null },
    /** Hook to refresh a near-expiry token before a pass (see AuthManager). */
    private val ensureFreshToken: suspend () -> Unit = {},
) {
    suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
        val conn = config.connection() ?: return@withContext SyncOutcome.NotConfigured
        ensureFreshToken()
        val api = SupabaseRest(conn.url, conn.anonKey, accessToken)
        try {
            // STAGE 1: PULL-ONLY. Push is intentionally disabled until the repoint is
            // proven on-device, so local test data can never reach the shared prod DB.
            val pushed = push(api)
            val pulled = pull(api)
            config.setLastSyncAt(System.currentTimeMillis())
            SyncOutcome.Success(pushed, pulled)
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "Sync failed")
        }
    }

    // ── push (Stage 2) ──────────────────────────────────────────────────────
    // Deliberately a no-op for Stage 1. The web-contract push (products on sku,
    // sales on id/ref with JSONB items, refunds as type='return' rows, customers on
    // local_id) lands once pull is verified against the live shared database.
    @Suppress("UNUSED_PARAMETER")
    private suspend fun push(api: SupabaseRest): Int = 0

    // ── pull ──────────────────────────────────────────────────────────────
    /** Pull the shared catalog/customers/sales onto this device. Read-only: nothing
     *  is ever written to the cloud here, so a mapping bug can't corrupt prod data. */
    private suspend fun pull(api: SupabaseRest): Int {
        val bid = businessDao.getOnce()?.id ?: return 0
        var n = 0
        n += pullProducts(api, bid)
        n += pullCustomers(api, bid)
        n += pullSales(api, bid)
        return n
    }

    /** products → items, bridged by sku (the cloud has no per-row local id here). */
    private suspend fun pullProducts(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<ProductDto>>(
            api.selectSince("products", config.cursor("products"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val bySku = itemDao.allForBusinessOnce(bid)
            .filter { !it.sku.isNullOrBlank() }
            .associateBy { it.sku!!.lowercase() }
        var applied = 0
        for (dto in rows) {
            val local = bySku[dto.sku.lowercase()]
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                itemDao.upsert(dto.toItem(bid, local))
                applied++
            }
        }
        config.setCursor("products", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /** customers → customers, bridged by local_id (= the Android UUID). */
    private suspend fun pullCustomers(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<CustomerDto>>(
            api.selectSince("customers", config.cursor("customers"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = customerDao.getById(dto.bridgeId())
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                customerDao.upsert(dto.toCustomer(bid, local))
                applied++
            }
        }
        config.setCursor("customers", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /**
     * sales → sales + sale_items. id is the ref, line items come from the JSONB
     * `items` column. Insert-once (matches the web's ignoreDuplicates) so re-pulling
     * never double-writes lines. `type='return'` refunds and quotes/holds are skipped
     * in Stage 1 — reconstructing them into the local refund tables is a later stage.
     */
    private suspend fun pullSales(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<SaleDto>>(
            api.selectSince("sales", config.cursor("sales"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // sku → local item id, so pulled sale lines join to the catalog for reports.
        val skuToId = itemDao.allForBusinessOnce(bid)
            .filter { !it.sku.isNullOrBlank() }
            .associate { it.sku!!.lowercase() to it.id }
        var applied = 0
        for (dto in rows) {
            if (dto.type != "sale") continue
            if (saleDao.getSaleById(dto.id) != null) continue
            saleDao.upsertSale(dto.toSaleEntity(bid))
            val lines = dto.toSaleLines(bid) { sku -> sku?.lowercase()?.let { skuToId[it] } }
            if (lines.isNotEmpty()) saleDao.upsertLines(lines)
            applied++
        }
        config.setCursor("sales", rows.maxOf { it.cursorStamp() })
        return applied
    }

    companion object {
        /** Rows per pull. A single till changes far fewer than this between syncs. */
        private const val PAGE = 1000
    }
}
