package com.portionspot.pos.sync

import com.portionspot.pos.data.BusinessDao
import com.portionspot.pos.data.CashTxnDao
import com.portionspot.pos.data.CreditDao
import com.portionspot.pos.data.CustomerDao
import com.portionspot.pos.data.ExpenseDao
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.ItemDao
import com.portionspot.pos.data.MobileMoneyDao
import com.portionspot.pos.data.PurchaseOrderDao
import com.portionspot.pos.data.RefundDao
import com.portionspot.pos.data.SaleDao
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SalePaymentDao
import com.portionspot.pos.data.SupplierDao
import com.portionspot.pos.media.ProductImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Outcome of one sync pass, surfaced to the UI. */
sealed class SyncOutcome {
    /**
     * The pass completed. [pushErrors] holds a per-table message for any table whose
     * push failed while others still went up (e.g. "customers: column local_id missing").
     * Empty = a clean pass; non-empty = partial success the UI should surface.
     */
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val pushErrors: List<String> = emptyList()
    ) : SyncOutcome()
    object NotConfigured : SyncOutcome()
    data class Failed(val message: String) : SyncOutcome()
}

/** Internal result of the push half: how many rows went up, and per-table failures. */
private data class PushResult(val pushed: Int, val errors: List<String>)

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
    private val expenseDao: ExpenseDao,
    private val cashTxnDao: CashTxnDao,
    private val supplierDao: SupplierDao,
    private val poDao: PurchaseOrderDao,
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
            // Push each table independently (one table failing no longer aborts the
            // whole pass), then pull. Per-table push failures ride back on Success so
            // the UI can name the table and error instead of a blanket "Sync failed".
            val pushResult = push(api)
            val pulled = pull(api)
            val at = System.currentTimeMillis()
            config.setLastSyncAt(at)
            // Split timestamps so the owner can SEE the two directions independently:
            // only stamp a direction that actually moved rows this pass.
            if (pushResult.pushed > 0) config.setLastUploadAt(at)
            if (pulled > 0) config.setLastDownloadAt(at)
            SyncOutcome.Success(pushResult.pushed, pulled, pushResult.errors)
        } catch (e: Exception) {
            SyncOutcome.Failed(e.message ?: "Sync failed")
        }
    }

    /**
     * How many local rows are waiting to go UP — the sum of every pushable table's
     * pending set (products, customers, credit, mobile-money, sales, refunds, plus the
     * accounting spine and supplier orders). Drives the "N to upload" badge in the UI so
     * the owner can SEE work is queued. Read-only.
     */
    suspend fun pendingUploadCount(): Int = withContext(Dispatchers.IO) {
        itemDao.pending().count { !it.sku.isNullOrBlank() && !it.deleted } +
            customerDao.pending().size +
            creditDao.pending().size +
            mobileMoneyDao.pending().size +
            saleDao.pendingSales().count { it.status == "completed" } +
            refundDao.pending().size +
            expenseDao.pending().size +
            cashTxnDao.pending().size +
            supplierDao.pending().size +
            poDao.pending().size +
            poDao.pendingLines().size
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
    private suspend fun push(api: SupabaseRest): PushResult {
        if (!config.pushEnabled()) return PushResult(0, emptyList())
        val bid = businessDao.getOnce()?.id ?: return PushResult(0, emptyList())
        // local itemId → sku, so pushed line items carry the cloud product reference.
        val skuById = itemDao.allForBusinessOnce(bid)
            .filter { !it.sku.isNullOrBlank() }
            .associate { it.id to it.sku!! }
        val skuOf: (String?) -> String? = { itemId -> itemId?.let { skuById[it] } }
        var n = 0
        val errors = mutableListOf<String>()

        // Run one table's push in isolation: a thrown failure is recorded against the
        // table name and swallowed so the remaining tables still get their turn. Only
        // the block that reaches its own markSynced marks its rows clean, so a failed
        // table's rows stay pending and retry next pass.
        suspend fun pushTable(table: String, block: suspend () -> Int) {
            try {
                n += block()
            } catch (e: Exception) {
                errors.add("$table: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // products — only locally-edited items that carry a sku (the cloud conflict key).
        // First resolve any pending product image: upload the local copy to Storage and
        // swap in the resulting public URL. An item whose upload fails is dropped from
        // THIS push (its row stays pending) so it retries next pass instead of shipping
        // a device-local path as image_url.
        pushTable("products") {
            val pendingProducts = itemDao.pending().filter { !it.sku.isNullOrBlank() && !it.deleted }
            val products = pendingProducts.mapNotNull { item ->
                if (!item.imagePending) return@mapNotNull item
                val bytes = ProductImages.readBytes(item.imageLocalPath)
                if (bytes == null) {
                    // Removed image, or the local file vanished: clear the flag and push the
                    // (possibly null) image_url as-is so the cleared state reaches the cloud.
                    val cleared = item.copy(imagePending = false)
                    itemDao.upsert(cleared)
                    cleared
                } else {
                    try {
                        val path = ProductImages.objectPath(bid, item.id)
                        api.uploadObject("product-images", path, bytes, ProductImages.CONTENT_TYPE)
                        val uploaded = item.copy(
                            imageUrl = api.publicUrl("product-images", path),
                            imagePending = false,
                        )
                        itemDao.upsert(uploaded)
                        uploaded
                    } catch (_: Exception) {
                        null   // leave pending; skip this cycle and retry on the next sync
                    }
                }
            }
            if (products.isEmpty()) return@pushTable 0
            api.upsert("products", syncJson.encodeToString(products.map { it.toProductPush() }), "sku")
            itemDao.markSynced(products.map { it.id })
            products.size
        }

        // customers (upsert on local_id; balance/credit_limit deliberately not sent).
        pushTable("customers") {
            val customers = customerDao.pending()
            if (customers.isEmpty()) return@pushTable 0
            api.upsert("customers", syncJson.encodeToString(customers.map { it.toCustomerPush() }), "local_id")
            customerDao.markSynced(customers.map { it.id })
            customers.size
        }

        // credit — after customers, so every referenced customer has a cloud bigint id
        // to resolve customer_id to.
        //
        // NEVER silently drop a money row (the old mapNotNull did, forever):
        //   • blank/no customer (walk-in change) → push with customer_id = null (nullable
        //     cloud column), so it reaches the DB instead of rotting as pending.
        //   • a real customer that hasn't synced up THIS pass → leave the row pending
        //     (don't mark it synced) AND raise a visible warning; it retries next pass.
        pushTable("credit_transactions") {
            val credit = creditDao.pending()
            if (credit.isEmpty()) return@pushTable 0
            val localToCloud = customerIdMap(api).entries.associate { (cloud, local) -> local to cloud }
            val pushable = mutableListOf<Pair<com.portionspot.pos.data.CreditTxn, CreditPushDto>>()
            var unresolved = 0
            for (c in credit) {
                val hasCustomer = !c.customerId.isBlank()
                if (!hasCustomer) {
                    // Walk-in change/refund: no customer to resolve — push with null.
                    pushable.add(c to c.toCreditPush(null, null))
                    continue
                }
                val cloudCid = localToCloud[c.customerId]
                if (cloudCid == null) {
                    // Referenced customer isn't up yet: keep this row pending, retry later.
                    unresolved++
                    continue
                }
                pushable.add(c to c.toCreditPush(cloudCid, null))
            }
            if (unresolved > 0) {
                errors.add(
                    "credit_transactions: $unresolved row(s) waiting on their customer to sync — will retry"
                )
            }
            if (pushable.isEmpty()) return@pushTable 0
            api.upsert("credit_transactions", syncJson.encodeToString(pushable.map { it.second }), "local_id")
            creditDao.markSynced(pushable.map { it.first.id })
            pushable.size
        }

        // mobile-money receipts (upsert on txn_code — idempotent).
        pushTable("mobile_money_receipts") {
            val mm = mobileMoneyDao.pending()
            if (mm.isEmpty()) return@pushTable 0
            api.upsert("mobile_money_receipts", syncJson.encodeToString(mm.map { it.toPush() }), "txn_code")
            mobileMoneyDao.markSynced(mm.map { it.id })
            mm.size
        }

        pushTable("sales") {
            val sales = saleDao.pendingSales().filter { it.status == "completed" }
            if (sales.isEmpty()) return@pushTable 0
            // Tombstoned lines (removed by a B5 in-place edit) must not reach the cloud
            // item JSON — only what the receipt says NOW.
            suspend fun dtoFor(s: SaleEntity) = buildSalePush(
                s,
                saleDao.allLinesForSale(s.id).filter { !it.deleted },
                salePaymentDao.forSale(s.id),
                skuOf
            )
            // A never-edited sale is append-only: ignore-duplicates so a re-push can
            // never rewrite a shared row. An EDITED receipt is the deliberate exception —
            // it must overwrite its own cloud row (same id) or the correction is lost, so
            // it goes up with merge-duplicates. Split into two calls, same conflict key.
            val (edited, fresh) = sales.partition { it.editedAt != null }
            if (fresh.isNotEmpty()) {
                api.upsert(
                    "sales", syncJson.encodeToString(fresh.map { dtoFor(it) }),
                    "id", ignoreDuplicates = true
                )
            }
            if (edited.isNotEmpty()) {
                api.upsert(
                    "sales", syncJson.encodeToString(edited.map { dtoFor(it) }),
                    "id", ignoreDuplicates = false
                )
            }
            sales.forEach { saleDao.markSaleSynced(it.id) }
            sales.size
        }

        pushTable("refunds") {
            val refunds = refundDao.pending()
            if (refunds.isEmpty()) return@pushTable 0
            val dtos = refunds.map { r -> buildRefundPush(r, refundDao.linesFor(r.id), skuOf) }
            api.upsert("sales", syncJson.encodeToString(dtos), "id", ignoreDuplicates = true)
            refundDao.markSynced(refunds.map { it.id })
            refunds.size
        }

        // ── accounting spine + supplier orders (owner-approved for the cloud) ──
        // All five upsert on `local_id`, so a re-push overwrites this device's own row
        // and never anyone else's. Each block is isolated: one failing table records a
        // warning and leaves its rows pending, the rest still go up.

        // expenses — approvals, funding splits and recurring schedules.
        pushTable("expenses") {
            val rows = expenseDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("expenses", syncJson.encodeToString(rows.map { it.toExpensePush() }), "local_id")
            expenseDao.markSynced(rows.map { it.id })
            rows.size
        }

        // cash_txns — the append-only cash-on-hand ledger.
        pushTable("cash_txns") {
            val rows = cashTxnDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("cash_txns", syncJson.encodeToString(rows.map { it.toCashTxnPush() }), "local_id")
            cashTxnDao.markSynced(rows.map { it.id })
            rows.size
        }

        // ORDER MATTERS from here: suppliers → purchase_orders → purchase_order_items,
        // so a PO's supplier (and a line's PO) is already up when the child arrives.
        pushTable("suppliers") {
            val rows = supplierDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("suppliers", syncJson.encodeToString(rows.map { it.toSupplierPush() }), "local_id")
            supplierDao.markSynced(rows.map { it.id })
            rows.size
        }

        pushTable("purchase_orders") {
            val rows = poDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "purchase_orders",
                syncJson.encodeToString(rows.map { it.toPurchaseOrderPush() }),
                "local_id"
            )
            poDao.markSynced(rows.map { it.id })
            rows.size
        }

        // PO lines last. A line has no clock of its own, so its cloud timestamps come
        // from its parent PO's updatedAt (falling back to now for an orphan line, which
        // still keeps the pull cursor moving forward).
        pushTable("purchase_order_items") {
            val rows = poDao.pendingLines()
            if (rows.isEmpty()) return@pushTable 0
            val stampFor = HashMap<String, Long>()
            val dtos = rows.map { line ->
                val stamp = stampFor.getOrPut(line.poId) {
                    poDao.getById(line.poId)?.updatedAt ?: System.currentTimeMillis()
                }
                line.toPurchaseOrderLinePush(stamp)
            }
            api.upsert("purchase_order_items", syncJson.encodeToString(dtos), "local_id")
            poDao.markLinesSynced(rows.map { it.id })
            rows.size
        }

        return PushResult(n, errors)
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
        // Accounting spine + supplier orders. Same last-write-wins-by-updated_at rule,
        // same per-table cursor; parents (suppliers, POs) before children (PO lines).
        n += pullExpenses(api, bid)
        n += pullCashTxns(api, bid)
        n += pullSuppliers(api, bid)
        n += pullPurchaseOrders(api, bid)
        n += pullPurchaseOrderLines(api)
        return n
    }

    /** expenses → local expenses, bridged by local_id. */
    private suspend fun pullExpenses(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<ExpenseDto>>(
            api.selectSince("expenses", config.cursor("expenses"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = expenseDao.getById(dto.bridgeId())
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                expenseDao.upsert(dto.toExpense(bid, local))
                applied++
            }
        }
        config.setCursor("expenses", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /** cash_txns → local cash ledger, bridged by local_id. */
    private suspend fun pullCashTxns(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<CashTxnDto>>(
            api.selectSince("cash_txns", config.cursor("cash_txns"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = cashTxnDao.getById(dto.bridgeId())
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                cashTxnDao.upsert(dto.toCashTxn(bid, local))
                applied++
            }
        }
        config.setCursor("cash_txns", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /** suppliers → local suppliers, bridged by local_id. */
    private suspend fun pullSuppliers(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<SupplierDto>>(
            api.selectSince("suppliers", config.cursor("suppliers"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = supplierDao.getById(dto.bridgeId())
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                supplierDao.upsert(dto.toSupplier(bid, local))
                applied++
            }
        }
        config.setCursor("suppliers", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /** purchase_orders → local POs, bridged by local_id. */
    private suspend fun pullPurchaseOrders(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<PurchaseOrderDto>>(
            api.selectSince("purchase_orders", config.cursor("purchase_orders"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = poDao.getById(dto.bridgeId())
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                poDao.upsert(dto.toPurchaseOrder(bid, local))
                applied++
            }
        }
        config.setCursor("purchase_orders", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * purchase_order_items → local PO lines, bridged by local_id and linked to their
     * header by `po_local_id`. A line whose header hasn't arrived yet is still applied
     * (the link is a plain value, no FK), so it can never be silently dropped.
     */
    private suspend fun pullPurchaseOrderLines(api: SupabaseRest): Int {
        val rows = syncJson.decodeFromString<List<PurchaseOrderLineDto>>(
            api.selectSince("purchase_order_items", config.cursor("purchase_order_items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = poDao.getLineById(dto.bridgeId())
            // Lines carry no local clock, so the header's updated_at is the tiebreak.
            val localStamp = local?.poId?.let { poDao.getById(it)?.updatedAt } ?: 0L
            if (local == null || IsoTime.toMillis(dto.updatedAt) > localStamp) {
                poDao.upsertLine(dto.toPurchaseOrderLine(local))
                applied++
            }
        }
        config.setCursor("purchase_order_items", rows.maxOf { it.cursorStamp() })
        return applied
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
     * to an existing local item by trimmed/case-folded sku; failing that, by trimmed/
     * case-folded NAME against ANY local item (not just sku-less ones) — so a catalogue
     * the shop typed in first (whose items may carry a different/auto sku, or none) is
     * absorbed and adopts the cloud sku instead of the pull inserting a second copy. Each
     * local item can be claimed only once. Cloud rows sharing a sku are collapsed to the
     * newest. Only genuinely new products insert a fresh row. See [bridgeProducts].
     *
     * After applying, [healDuplicateItems] collapses any local rows that were ALREADY
     * duplicated (from earlier connects, before this broader bridge) so the shop doesn't
     * keep seeing two of everything.
     */
    private suspend fun pullProducts(api: SupabaseRest, bid: String): Int {
        val rows = syncJson.decodeFromString<List<ProductDto>>(
            api.selectSince("products", config.cursor("products"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val localAll = itemDao.allForBusinessOnce(bid)

        var applied = 0
        for ((dto, local) in bridgeProducts(localAll, rows)) {
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                itemDao.upsert(dto.toItem(bid, local))
                applied++
            }
        }
        healDuplicateItems(bid)
        config.setCursor("products", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /**
     * Collapse local catalogue rows that are duplicates of the SAME product — same
     * case/space-folded NAME, with skus that are equal or one-side-blank (two genuinely
     * different products that merely share a name, each with its own sku, are left
     * alone). The survivor is the sku-bearing, already-synced, newest row; each other
     * copy has its sale history repointed onto the survivor and is then hard-deleted
     * (local only — never synced). Idempotent: a no-op once there are no duplicates.
     */
    private suspend fun healDuplicateItems(bid: String) {
        val groups = itemDao.allForBusinessOnce(bid).groupBy { it.name.trim().lowercase() }
        for ((_, group) in groups) {
            if (group.size < 2) continue
            val survivor = group.maxWithOrNull(
                compareBy({ !it.sku.isNullOrBlank() }, { !it.pendingSync }, { it.updatedAt })
            ) ?: continue
            for (dup in group) {
                if (dup.id == survivor.id) continue
                val a = dup.sku?.trim()?.lowercase().orEmpty()
                val b = survivor.sku?.trim()?.lowercase().orEmpty()
                // Same product only: skus match, or at least one is blank.
                if (a.isNotEmpty() && b.isNotEmpty() && a != b) continue
                saleDao.repointLineItem(dup.id, survivor.id)
                itemDao.hardDelete(dup.id)
            }
        }
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

/**
 * Pure product-merge bridge (extracted from [PosSyncEngine.pullProducts] so it can be
 * unit-tested). Matches each cloud [ProductDto] to at most one local [Item] — by
 * case/space-folded sku first, then by folded name against ANY local item — claim-once,
 * so a locally-typed catalogue merges onto its cloud twin instead of duplicating. Cloud
 * rows sharing a sku are collapsed to the newest before matching. Returns each cloud row
 * paired with its matched local item (or null = a genuinely new product).
 */
internal fun bridgeProducts(local: List<Item>, cloud: List<ProductDto>): List<Pair<ProductDto, Item?>> {
    val bySku = HashMap<String, Item>()
    val byName = HashMap<String, Item>()
    for (it in local) {
        val s = it.sku?.trim()?.lowercase()
        if (!s.isNullOrEmpty()) bySku.putIfAbsent(s, it)
        byName.putIfAbsent(it.name.trim().lowercase(), it)
    }
    // Collapse duplicate-sku cloud rows to the newest; blank-sku rows stay individual
    // (grouping them would wrongly fuse every un-skued product into one).
    val (skued, blank) = cloud.partition { it.sku.isNotBlank() }
    val ordered = skued.groupBy { it.sku.trim().lowercase() }
        .map { (_, g) -> g.maxByOrNull { IsoTime.toMillis(it.updatedAt) }!! } + blank

    val claimed = HashSet<String>()   // local ids already taken (claim-once)
    return ordered.map { dto ->
        val skuKey = dto.sku.trim().lowercase()
        var match = if (skuKey.isNotEmpty()) bySku[skuKey] else null
        if (match != null && match.id in claimed) match = null
        if (match == null) {
            val cand = byName[dto.name.trim().lowercase()]
            if (cand != null && cand.id !in claimed) match = cand
        }
        if (match != null) claimed.add(match.id)
        dto to match
    }
}
