package com.portionspot.pos.sync

import com.portionspot.pos.data.BusinessDao
import com.portionspot.pos.data.CreditDao
import com.portionspot.pos.data.CustomerDao
import com.portionspot.pos.data.ItemDao
import com.portionspot.pos.data.SaleDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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
            val pushed = push(api)
            val pulled = pull(api)
            config.setLastSyncAt(System.currentTimeMillis())
            SyncOutcome.Success(pushed, pulled)
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "Sync failed")
        }
    }

    // ── push ──────────────────────────────────────────────────────────────
    private suspend fun push(api: SupabaseRest): Int {
        var n = 0

        businessDao.pending().let { rows ->
            if (rows.isNotEmpty()) {
                api.upsert("businesses", syncJson.encodeToString(rows.map { it.toDto() }), "id")
                rows.forEach { businessDao.markSynced(it.id) }
                n += rows.size
            }
        }

        customerDao.pending().let { rows ->
            if (rows.isNotEmpty()) {
                api.upsert("customers", syncJson.encodeToString(rows.map { it.toDto() }), "id")
                customerDao.markSynced(rows.map { it.id })
                n += rows.size
            }
        }

        itemDao.pending().let { rows ->
            if (rows.isNotEmpty()) {
                api.upsert("items", syncJson.encodeToString(rows.map { it.toDto() }), "id")
                itemDao.markSynced(rows.map { it.id })
                n += rows.size
            }
        }

        saleDao.pendingSales().let { sales ->
            if (sales.isNotEmpty()) {
                api.upsert("sales", syncJson.encodeToString(sales.map { it.toDto() }), "id")
                val lines = sales.flatMap { saleDao.allLinesForSale(it.id) }
                if (lines.isNotEmpty())
                    api.upsert("sale_items", syncJson.encodeToString(lines.map { it.toDto() }), "id")
                sales.forEach { saleDao.markSaleSynced(it.id) }
                n += sales.size
            }
        }

        creditDao.pending().let { rows ->
            if (rows.isNotEmpty()) {
                api.upsert("credit_transactions", syncJson.encodeToString(rows.map { it.toDto() }), "id")
                creditDao.markSynced(rows.map { it.id })
                n += rows.size
            }
        }

        return n
    }

    // ── pull ──────────────────────────────────────────────────────────────
    private suspend fun pull(api: SupabaseRest): Int =
        pullBusinesses(api) + pullCustomers(api) + pullItems(api) +
            pullSales(api) + pullSaleLines(api) + pullCredit(api)

    private suspend fun pullBusinesses(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<BusinessDto>>(
            api.selectSince("businesses", config.cursor("businesses"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = businessDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                businessDao.upsert(dto.toEntity(local))
                applied++
            }
        }
        config.setCursor("businesses", rows.maxOf { it.updatedAt })
        return applied
    }

    private suspend fun pullCustomers(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<CustomerDto>>(
            api.selectSince("customers", config.cursor("customers"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = customerDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                // `wholesale` is a local-only flag the cloud doesn't carry — keep
                // whatever this device already has so a pull never resets it.
                customerDao.upsert(dto.toEntity().copy(wholesale = local?.wholesale ?: false))
                applied++
            }
        }
        config.setCursor("customers", rows.maxOf { it.updatedAt })
        return applied
    }

    private suspend fun pullCredit(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<CreditTxnDto>>(
            api.selectSince("credit_transactions", config.cursor("credit_transactions"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = creditDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                creditDao.upsert(dto.toEntity())
                applied++
            }
        }
        config.setCursor("credit_transactions", rows.maxOf { it.updatedAt })
        return applied
    }

    private suspend fun pullItems(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<ItemDto>>(
            api.selectSince("items", config.cursor("items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = itemDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                itemDao.upsert(dto.toEntity())
                applied++
            }
        }
        config.setCursor("items", rows.maxOf { it.updatedAt })
        return applied
    }

    private suspend fun pullSales(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<SaleDto>>(
            api.selectSince("sales", config.cursor("sales"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = saleDao.getSaleById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                saleDao.upsertSale(dto.toEntity())
                applied++
            }
        }
        config.setCursor("sales", rows.maxOf { it.updatedAt })
        return applied
    }

    private suspend fun pullSaleLines(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<SaleLineDto>>(
            api.selectSince("sale_items", config.cursor("sale_items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // Sale lines are immutable snapshots, never edited locally — just mirror them.
        saleDao.upsertLines(rows.map { it.toEntity() })
        config.setCursor("sale_items", rows.maxOf { it.updatedAt })
        return rows.size
    }

    companion object {
        /** Rows per pull. A single till changes far fewer than this between syncs. */
        private const val PAGE = 1000
    }
}
