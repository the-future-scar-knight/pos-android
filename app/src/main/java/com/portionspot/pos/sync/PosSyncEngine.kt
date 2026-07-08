package com.portionspot.pos.sync

import com.portionspot.pos.data.BusinessDao
import com.portionspot.pos.data.CreditDao
import com.portionspot.pos.data.CustomerDao
import com.portionspot.pos.data.ItemDao
import com.portionspot.pos.data.MobileMoneyDao
import com.portionspot.pos.data.RefundDao
import com.portionspot.pos.data.SaleDao
import com.portionspot.pos.data.SalePaymentDao
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
    private val salePaymentDao: SalePaymentDao,
    private val customerDao: CustomerDao,
    private val creditDao: CreditDao,
    private val refundDao: RefundDao,
    private val mobileMoneyDao: MobileMoneyDao,
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
    /**
     * Push local data up — but ONLY when [SyncConfig.pushEnabled] is on (default OFF),
     * so the repoint ships pull-only and no local/test data can reach the shared prod
     * DB until the owner opts in. Scope is the APPEND-ONLY tables: completed sales and
     * refunds (as `type='return'` rows), both upserted with ignore-duplicates so a
     * re-push never rewrites an existing shared row. Products and customers/credit push
     * (overwrite / derived-balance risk) are separate later sub-stages.
     */
    private suspend fun push(api: SupabaseRest): Int {
        if (!config.pushEnabled()) return 0
        val bid = businessDao.getOnce()?.id ?: return 0
        // local itemId → sku, so pushed line items carry the cloud product reference.
        val skuById = itemDao.allForBusinessOnce(bid)
            .filter { !it.sku.isNullOrBlank() }
            .associate { it.id to it.sku!! }
        val skuOf: (String?) -> String? = { itemId -> itemId?.let { skuById[it] } }
        var n = 0

        // products — only locally-edited items that carry a sku (the cloud conflict key).
        val products = itemDao.pending().filter { !it.sku.isNullOrBlank() && !it.deleted }
        if (products.isNotEmpty()) {
            api.upsert("products", syncJson.encodeToString(products.map { it.toProductPush() }), "sku")
            itemDao.markSynced(products.map { it.id })
            n += products.size
        }

        // customers (upsert on local_id; balance/credit_limit deliberately not sent).
        val customers = customerDao.pending()
        if (customers.isNotEmpty()) {
            api.upsert("customers", syncJson.encodeToString(customers.map { it.toCustomerPush() }), "local_id")
            customerDao.markSynced(customers.map { it.id })
            n += customers.size
        }

        // credit — after customers, so every referenced customer has a cloud bigint id
        // to resolve customer_id to. Unresolved rows are left for a later pass.
        val credit = creditDao.pending()
        if (credit.isNotEmpty()) {
            val localToCloud = customerIdMap(api).entries.associate { (cloud, local) -> local to cloud }
            val pushable = credit.mapNotNull { c ->
                val cloudCid = localToCloud[c.customerId] ?: return@mapNotNull null
                c to c.toCreditPush(cloudCid, null)
            }
            if (pushable.isNotEmpty()) {
                api.upsert("credit_transactions", syncJson.encodeToString(pushable.map { it.second }), "local_id")
                creditDao.markSynced(pushable.map { it.first.id })
                n += pushable.size
            }
        }

        // mobile-money receipts (upsert on txn_code — idempotent).
        val mm = mobileMoneyDao.pending()
        if (mm.isNotEmpty()) {
            api.upsert("mobile_money_receipts", syncJson.encodeToString(mm.map { it.toPush() }), "txn_code")
            mobileMoneyDao.markSynced(mm.map { it.id })
            n += mm.size
        }

        val sales = saleDao.pendingSales().filter { it.status == "completed" }
        if (sales.isNotEmpty()) {
            val dtos = sales.map { s ->
                buildSalePush(s, saleDao.allLinesForSale(s.id), salePaymentDao.forSale(s.id), skuOf)
            }
            api.upsert("sales", syncJson.encodeToString(dtos), "id", ignoreDuplicates = true)
            sales.forEach { saleDao.markSaleSynced(it.id) }
            n += sales.size
        }

        val refunds = refundDao.pending()
        if (refunds.isNotEmpty()) {
            val dtos = refunds.map { r -> buildRefundPush(r, refundDao.linesFor(r.id), skuOf) }
            api.upsert("sales", syncJson.encodeToString(dtos), "id", ignoreDuplicates = true)
            refundDao.markSynced(refunds.map { it.id })
            n += refunds.size
        }

        return n
    }

    // ── pull ──────────────────────────────────────────────────────────────
    /** Pull the shared catalog/customers/sales onto this device. Read-only: nothing
     *  is ever written to the cloud here, so a mapping bug can't corrupt prod data. */
    private suspend fun pull(api: SupabaseRest): Int {
        val bid = businessDao.getOnce()?.id ?: return 0
        var n = 0
        n += pullProducts(api, bid)
        n += pullCustomers(api, bid)
        n += pullSales(api, bid)
        n += pullCredit(api, bid)
        n += pullMobileMoney(api, bid)
        return n
    }

    /** cloud customers.id (as text) → Android customer id (its local_id). The bridge
     *  for credit, whose customer_id column holds the customers BIGINT id, not local_id. */
    private suspend fun customerIdMap(api: SupabaseRest): Map<String, String> =
        syncJson.decodeFromString<List<CustomerIdRow>>(api.selectAll("customers", "id,local_id"))
            .associate { it.id.toString() to it.bridge() }

    /** credit_transactions → credit, bridging customer_id(bigint)→customers.local_id.
     *  Rows whose customer can't be resolved are skipped (kept for a later pass). */
    private suspend fun pullCredit(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<CreditDto>>(
            api.selectSince("credit_transactions", config.cursor("credit_transactions"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val cloudIdToLocal = customerIdMap(api)
        var applied = 0
        for (dto in rows) {
            val cid = dto.customerId ?: continue
            val androidCustomerId = cloudIdToLocal[cid] ?: continue
            val bridge = dto.localId?.ifBlank { null } ?: "ctx-${dto.id ?: ""}"
            val local = creditDao.getById(bridge)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                creditDao.upsert(dto.toCreditTxn(bid, androidCustomerId, local))
                applied++
            }
        }
        config.setCursor("credit_transactions", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /** mobile_money_receipts → local, deduped by txn_code (the idempotency key). */
    private suspend fun pullMobileMoney(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<MobileMoneyDto>>(
            api.selectSince("mobile_money_receipts", config.cursor("mobile_money_receipts"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = mobileMoneyDao.getByTxn(bid, dto.txnCode)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                mobileMoneyDao.upsert(dto.toReceipt(bid, local))
                applied++
            }
        }
        config.setCursor("mobile_money_receipts", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * products → items, bridged by sku (the cloud has no per-row local id here).
     *
     * Matching is deliberately forgiving to avoid DUPLICATES: a cloud product is joined
     * to an existing local item by trimmed/case-folded sku; failing that (an item added
     * on-device with no sku, or from an older build), by trimmed/case-folded NAME among
     * the sku-less local items — so it's absorbed and adopts the cloud sku instead of
     * inserting a second copy. Each sku-less local item can be claimed only once. Cloud
     * rows sharing a sku are also collapsed to the newest, so a data glitch upstream
     * can't fan out into two local rows. Only genuinely new products insert a fresh row.
     */
    private suspend fun pullProducts(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<ProductDto>>(
            api.selectSince("products", config.cursor("products"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val localAll = itemDao.allForBusinessOnce(bid)
        val bySku = localAll.filter { !it.sku.isNullOrBlank() }
            .associateBy { it.sku!!.trim().lowercase() }
        // Mutable so a sku-less local item is claimed by at most one cloud product.
        val byNameNoSku = localAll.filter { it.sku.isNullOrBlank() }
            .associateBy { it.name.trim().lowercase() }
            .toMutableMap()
        // Collapse duplicate-sku cloud rows to the newest (blank skus are NOT grouped —
        // that would wrongly merge every un-skued product into one).
        val (skued, blank) = rows.partition { it.sku.isNotBlank() }
        val toApply = skued.groupBy { it.sku.trim().lowercase() }
            .map { (_, g) -> g.maxByOrNull { IsoTime.toMillis(it.updatedAt) }!! } + blank

        var applied = 0
        for (dto in toApply) {
            val skuKey = dto.sku.trim().lowercase()
            val local = (if (skuKey.isNotEmpty()) bySku[skuKey] else null)
                ?: byNameNoSku.remove(dto.name.trim().lowercase())
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
