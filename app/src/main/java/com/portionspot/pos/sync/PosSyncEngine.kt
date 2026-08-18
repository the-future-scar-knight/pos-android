package com.portionspot.pos.sync

import com.portionspot.pos.data.AuditDao
import com.portionspot.pos.data.BusinessDao
import com.portionspot.pos.data.CashTxnDao
import com.portionspot.pos.data.CreditDao
import com.portionspot.pos.data.CustomerDao
import com.portionspot.pos.data.ExpenseDao
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.ItemAttribute
import com.portionspot.pos.data.ItemAttributeDao
import com.portionspot.pos.data.attributeId
import com.portionspot.pos.data.ItemDao
import com.portionspot.pos.data.MobileMoneyDao
import com.portionspot.pos.data.NotificationDao
import com.portionspot.pos.data.PurchaseOrderDao
import com.portionspot.pos.data.RefundDao
import com.portionspot.pos.data.SaleDao
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SalePaymentDao
import com.portionspot.pos.data.StaffDao
import com.portionspot.pos.data.StaffRequestDao
import com.portionspot.pos.data.SupplierDao
import com.portionspot.pos.media.ProductImages
// The NEW wire contract (sync.wire) — the shapes the shared web schema actually has.
// Imported explicitly rather than star-imported so it is obvious at a glance which
// mappers are the repointed ones while the old sync-package DTOs are still being
// retired around them.
// Three names exist in BOTH packages while the old contract is being retired, so they
// are aliased rather than star-imported: an ambiguous `SaleDto` would silently resolve
// to the old JSONB shape and push a sale the shared schema cannot read. The aliases go
// away with Dtos.kt.
import com.portionspot.pos.sync.wire.CustomerDto as WireCustomerDto
import com.portionspot.pos.sync.wire.SaleDto as WireSaleDto
import com.portionspot.pos.sync.wire.CreditDto as WireCreditDto
import com.portionspot.pos.data.CashSessionDao
import com.portionspot.pos.data.StockMovementDao
import com.portionspot.pos.data.stockOnHandFromDelta
import com.portionspot.pos.data.cashMovementCountedElsewhere
import com.portionspot.pos.data.countToRescue
import com.portionspot.pos.data.planSessionMerge
import com.portionspot.pos.data.planDayRollover
import com.portionspot.pos.data.ShopPolicy
import com.portionspot.pos.data.ShopPolicyPlan
import com.portionspot.pos.data.planShopPolicySync
import com.portionspot.pos.sync.wire.BusinessPolicyDto
import com.portionspot.pos.sync.wire.toPatch
import com.portionspot.pos.data.indexSessionsByDay
import com.portionspot.pos.data.startOfDay
import com.portionspot.pos.sync.wire.RefundDto
import com.portionspot.pos.sync.wire.RefundItemDto
import com.portionspot.pos.sync.wire.RefundPaymentDto
import com.portionspot.pos.sync.wire.toRefund
import com.portionspot.pos.sync.wire.toRefundLine
import com.portionspot.pos.sync.wire.toRefundPayment
import com.portionspot.pos.sync.wire.StockMovementDto
import com.portionspot.pos.sync.wire.toStockMovement
import com.portionspot.pos.sync.wire.CashSessionDto
import com.portionspot.pos.sync.wire.toCashSession
import com.portionspot.pos.sync.wire.CashMovementDto
import com.portionspot.pos.sync.wire.counterpartCashTxn
import com.portionspot.pos.sync.wire.toCashTxn
import com.portionspot.pos.sync.wire.ItemAttributeDto
import com.portionspot.pos.sync.wire.toItemAttribute
import com.portionspot.pos.sync.wire.toMoney
import com.portionspot.pos.sync.wire.ItemDto
import com.portionspot.pos.sync.wire.baselineStamp
import com.portionspot.pos.sync.wire.SaleItemDto
import com.portionspot.pos.sync.wire.SalePaymentDto
import com.portionspot.pos.sync.wire.toItem
import com.portionspot.pos.sync.wire.toCustomer
import com.portionspot.pos.sync.wire.toSaleEntity
import com.portionspot.pos.sync.wire.mergeIntoSale
import com.portionspot.pos.sync.wire.toSaleLine
import com.portionspot.pos.sync.wire.toSalePayment
import com.portionspot.pos.sync.wire.toCreditTxn
import com.portionspot.pos.sync.wire.SupplierDto
import com.portionspot.pos.sync.wire.toSupplier
import com.portionspot.pos.sync.wire.ExpenseDto
import com.portionspot.pos.sync.wire.toExpense
import com.portionspot.pos.sync.wire.AuditEntryDto
import com.portionspot.pos.sync.wire.toAuditEntry
import com.portionspot.pos.sync.wire.PurchaseOrderDto
import com.portionspot.pos.sync.wire.toPurchaseOrder
import com.portionspot.pos.sync.wire.PurchaseOrderItemDto
import com.portionspot.pos.sync.wire.toPurchaseOrderLine
import com.portionspot.pos.sync.wire.StaffDto
import com.portionspot.pos.sync.wire.toStaffMember
import com.portionspot.pos.data.uuidOrNull
import com.portionspot.pos.sync.wire.RefundItemPushDto
import com.portionspot.pos.sync.wire.RefundPaymentPushDto
import com.portionspot.pos.sync.wire.SaleItemPushDto
import com.portionspot.pos.sync.wire.SalePaymentPushDto
import com.portionspot.pos.sync.wire.SalePushDto
import com.portionspot.pos.sync.wire.buildSalePush
import com.portionspot.pos.sync.wire.toPush
import com.portionspot.pos.sync.wire.toMovementPush
import com.portionspot.pos.sync.wire.toReceiptPush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException
import kotlin.math.abs

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
 * Postgres "new row violates row-level security policy". On a staff-gated database
 * (one where writes require a signed-in staff account) this is what EVERY table
 * returns when the device is pushing with only the anon key — i.e. connected to the
 * database but not signed in.
 */
private const val PG_RLS_VIOLATION = "42501"

/**
 * "This table does not exist here" — PostgREST answers 404/PGRST205 and Postgres 42P01.
 *
 * Only ever used to let an OPTIONAL table's pull degrade to nothing on a database that
 * predates it. Never widen this to the tables that carry money: a sale that silently did
 * not arrive is worse than a sync that says it failed.
 */
private fun IOException.isMissingTable(): Boolean {
    val text = message.orEmpty()
    return text.contains("HTTP 404") || text.contains("PGRST205") || text.contains("42P01")
}

/**
 * "This COLUMN does not exist here" — Postgres 42703, or PGRST204 from PostgREST's own
 * schema cache when a write names it.
 *
 * The schema moves by columns now rather than by whole tables, so this is the finer-
 * grained sibling of [isMissingTable] and carries the same warning: it is only ever for
 * letting an OPTIONAL field degrade to nothing. A database that has not grown a settings
 * column yet has simply not stated that setting. Never widen it to a money column — a sale
 * whose total the database would not accept must fail loudly, not quietly go up short.
 */
private fun IOException.isMissingColumn(): Boolean {
    val text = message.orEmpty()
    return text.contains("42703") || text.contains("PGRST204")
}

/** The only columns of the shared `businesses` row this app reads or writes. Named
 *  explicitly rather than `select=*` so the narrow scope is visible at the request, not
 *  just in a comment — see [PosSyncEngine.syncShopPolicy]. */
private const val POLICY_COLUMNS =
    "id,max_item_discount,discount_threshold,variance_note_threshold,updated_at"

/** Just the `id` of a cloud `businesses` row — see [PosSyncEngine.adoptBusinessId]. */
@kotlinx.serialization.Serializable
private data class BusinessIdRow(val id: String = "")

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
    private val itemAttributeDao: ItemAttributeDao,
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
    private val notificationDao: NotificationDao,
    private val auditDao: AuditDao,
    private val staffRequestDao: StaffRequestDao,
    private val cashSessionDao: CashSessionDao,
    private val stockMovementDao: StockMovementDao,
    private val staffDao: StaffDao,
    private val config: SyncConfig,
    /** Current signed-in user's JWT for RLS; null falls back to anon (denied). */
    private val accessToken: () -> String? = { null },
    /** Hook to refresh a near-expiry token before a pass (see AuthManager). */
    private val ensureFreshToken: suspend () -> Unit = {},
    /**
     * Turn the sales and refunds this pass pulled into cash-ledger rows — see
     * [com.portionspot.pos.data.PosRepository.reconcilePulledCash].
     *
     * Injected rather than reimplemented here. The till's own arithmetic for "what cash did
     * this sale put in the drawer" lives in the repository, and a second copy of it in the
     * sync engine would drift from the first; once it drifted, every pass would find a
     * disagreement and write a correcting row for it, forever. A no-op default keeps the
     * engine constructible without the repository.
     */
    private val reconcileCash: suspend (String) -> Unit = {},
    /**
     * The shop's money RULES as this device currently enforces them, plus when a person
     * last changed them here (0 = never). Null means this engine was built without a
     * policy source and the step is skipped entirely.
     *
     * Injected rather than read from a DAO for the same reason [reconcileCash] is: the
     * settings keys, their defaults and the "was this a person's edit or an adopted
     * value" distinction all belong to the repository, and a second copy of them here
     * would be a second answer to what the shop's discount cap is.
     */
    private val readShopPolicy: suspend () -> Pair<ShopPolicy, Long>? = { null },
    /** Write an ADOPTED policy down. Must not stamp the local "a person changed this"
     *  clock — see [com.portionspot.pos.data.PosRepository.putShopPolicy]. */
    private val writeShopPolicy: suspend (ShopPolicy) -> Unit = {},
) {
    suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
        val conn = config.connection() ?: return@withContext SyncOutcome.NotConfigured
        ensureFreshToken()
        val api = SupabaseRest(conn.url, conn.anonKey, accessToken)
        try {
            // BEFORE the push, not inside the pull: the push stamps every row with the
            // shop's business id, and the pass order is push-then-pull. Left until the
            // pull, the very first sync on a new device would refuse to upload and report
            // an error, then adopt, and only succeed on the second pass — an avoidable
            // failure the owner would see and have no way to interpret. Cheap to call
            // every pass; it returns immediately once the id is known.
            adoptBusinessId(api)
            // Push each table independently (one table failing no longer aborts the
            // whole pass), then pull. Per-table push failures ride back on Success so
            // the UI can name the table and error instead of a blanket "Sync failed".
            val pushResult = push(api)
            // ★ NOTHING is pulled until this device knows which shop it belongs to.
            //
            // Every pull is filtered on it, and a pull that cannot be filtered returns
            // every business in the project — which the mappers then stamp as ours. The
            // till ends up holding another shop's catalogue and counting stock for
            // products it has never sold, with no error anywhere to say so. Refusing is
            // recoverable and visible; adopting a stranger's inventory is neither.
            val cloudBid = config.cloudBusinessId()
            // Between the push and the pull, and its own step rather than part of either:
            // it is the one thing here that reads and writes the SAME shared row, so it
            // belongs neither in [push] (which only ever sends rows this device owns) nor
            // in [pull] (which is read-only by design, and that invariant is worth more
            // than the tidiness of one more line in its list).
            val policyErrors = if (cloudBid == null) emptyList() else syncShopPolicy(api, cloudBid)
            val pulled = if (cloudBid == null) 0 else pull(api, cloudBid)
            // Said out loud rather than left as a quiet no-op. With push disabled — the
            // default on a freshly repointed device — an unidentified shop would
            // otherwise report a clean pass that moved nothing, every time, forever.
            val errors = if (cloudBid == null) {
                pushResult.errors + (
                    "Waiting to identify this shop in the database. It must hold exactly " +
                        "one business before this till can sync; check the Sync screen."
                    )
            } else pushResult.errors + policyErrors
            val at = System.currentTimeMillis()
            config.setLastSyncAt(at)
            // Split timestamps so the owner can SEE the two directions independently:
            // only stamp a direction that actually moved rows this pass.
            if (pushResult.pushed > 0) config.setLastUploadAt(at)
            if (pulled > 0) config.setLastDownloadAt(at)
            SyncOutcome.Success(pushResult.pushed, pulled, errors)
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
        // ★ Counts ONLY what [push] actually sends. It used to include every pending
        // table, which meant the badge promised uploads that could not happen — the
        // number never reached zero and stopped meaning anything. A queue indicator that
        // never empties is worse than none: the owner learns to ignore it, and then it
        // cannot tell them when something really is stuck.
        //
        // Items are absent because the catalogue is pull-only (the web owns it).
        customerDao.pending().size +
            saleDao.pendingSales().count { it.status == "completed" } +
            refundDao.pending().size +
            creditDao.pending().size +
            mobileMoneyDao.pending().size +
            stockMovementDao.pending().size +
            // Sale and refund drawer movements are never sent (the other side derives
            // them), so counting them here would promise an upload that cannot happen.
            cashTxnDao.pending().count { !cashMovementCountedElsewhere(it.refType) } +
            cashSessionDao.pending().size +
            // The accounting spine and supplier orders, each counted the way [push]
            // actually filters it: an expense TEMPLATE is a schedule and is never sent,
            // and a PO line whose parent id is not a uuid cannot be sent at all.
            supplierDao.pending().size +
            poDao.pending().size +
            poDao.pendingLines().count { uuidOrNull(it.poId) != null } +
            expenseDao.pending().count { !it.isTemplate } +
            auditDao.pending().size
    }

    // ── push ────────────────────────────────────────────────────────────────
    /**
     * Push local data up — but ONLY when [SyncConfig.pushEnabled] is on (default OFF),
     * so a freshly repointed device ships pull-only and nothing local can reach the
     * shared production database until the owner opts in.
     *
     * ── DUPLICATE HANDLING IS OPT-IN PER TABLE ────────────────────────────────
     *
     * Every table here upserts on `id`, and `id` is a uuid this device minted, so a
     * re-push overwrites THIS DEVICE'S OWN ROW and can never touch anyone else's. That
     * makes merge-duplicates the correct resolution nearly everywhere, and it is the
     * default below.
     *
     * ignore-duplicates is used for exactly ONE table — `mobile_money_receipts` — where
     * `(business_id, txn_code)` is a genuine idempotency key: the same SMS read twice is
     * the same receipt, and dropping the second copy is right. Anywhere else, "silently
     * skip the row that is already there" means a money row vanishes with no error, no
     * retry and nothing to find afterwards, which is the worst failure this engine can
     * have because it looks exactly like success.
     *
     * Order matters: customers before the sales, refunds and credit rows that reference
     * them, and each sale header before its own lines and tenders.
     */
    private suspend fun push(api: SupabaseRest): PushResult {
        if (!config.pushEnabled()) return PushResult(0, emptyList())
        val bid = businessDao.getOnce()?.id ?: return PushResult(0, emptyList())
        // ★ Every row goes up stamped with the SHOP's business id, not this device's.
        // Until that has been learned from the cloud (see [adoptBusinessId]) nothing is
        // pushed at all: uploading under a locally-invented id would succeed, return 2xx,
        // and produce rows no other client can see. Refusing to push is recoverable;
        // silently orphaning a day's takings is not.
        val cloudBid = config.cloudBusinessId()
            ?: return PushResult(
                0,
                listOf(
                    "Waiting to identify this shop's database before uploading — " +
                        "pull once while online, then try again."
                )
            )
        var n = 0
        val errors = mutableListOf<String>()
        // Tables the database REFUSED on row-level-security grounds. Collected apart from
        // real errors because they all share ONE cause and one fix; collapsed into a
        // single actionable line at the end rather than a wall of raw Postgres JSON.
        val rlsBlocked = mutableListOf<String>()

        // Run one table's push in isolation: a thrown failure is recorded against the
        // table name and swallowed so the remaining tables still get their turn. Only a
        // block that reaches its own markSynced marks its rows clean, so a failed table's
        // rows stay pending and retry next pass.
        suspend fun pushTable(table: String, block: suspend () -> Int) {
            try {
                n += block()
            } catch (e: Exception) {
                val raw = e.message ?: e.javaClass.simpleName
                if (raw.contains(PG_RLS_VIOLATION) || raw.contains("row-level security", true)) {
                    rlsBlocked += table
                } else {
                    errors.add("$table: $raw")
                }
            }
        }

        // items — the catalogue. Pushed FIRST, before anything that references a product:
        // a sale line or a stock movement naming an item the cloud has never heard of is
        // an orphan nothing will ever resolve, since there are no foreign keys to refuse
        // it. Parents before children, the same rule the rest of this pass follows.
        //
        // ★ `stock_qty` is deliberately NOT in [ItemPushDto]. Stock travels as movements
        // and only as movements — see that DTO for what writing a till's computed on-hand
        // into the shop's baseline would do to a second till.
        //
        // Last-write-wins on `client_updated_at`, which is what the pull already applies
        // in the other direction: a row older than the local edit does not overwrite it.
        pushTable("items") {
            val rows = itemDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("items", syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }), "id")
            itemDao.markSynced(rows.map { it.id })
            rows.size
        }

        // item_attributes — the fitments. Straight after the catalogue, since a tag is
        // about a product, and a product this till invented has to exist up there first.
        //
        // ★ The id is DERIVED from (business, item, key, value), and the business in that
        // derivation has to be the SHOP's, not this device's. A till in local mode has
        // never heard of the shop's id, so every tag typed before the first connect was
        // minted under the device's own business uuid — pushed as-is, each one would
        // arrive as a NEW row alongside the web's identical tag and be refused by
        // `uq_item_attributes_live` with a 23505, failing this entire batch.
        // [rekeyPendingAttributes] re-mints them first; it is a no-op once the ids already
        // agree, which is the normal case on every pass after the first.
        pushTable("item_attributes") {
            rekeyPendingAttributes(cloudBid)
            // Re-read AFTER the re-key: it rewrites primary keys, so anything read before
            // it is a stale copy of a row that no longer exists under that id.
            val rows = itemAttributeDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "item_attributes",
                syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            itemAttributeDao.markSynced(rows.map { it.id })
            rows.size
        }

        // customers — upsert on the Android uuid, which IS the cloud primary key now.
        // No `local_id` bridge and nothing to resolve: the row goes up under the id it
        // already has, so a sale referencing it can never arrive before its customer.
        pushTable("customers") {
            val rows = customerDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("customers", syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }), "id")
            customerDao.markSynced(rows.map { it.id })
            rows.size
        }

        // sales — the header, then its lines, then its tenders. A sale is three rows on
        // the shared schema, not one row with a JSON blob, which is what makes the
        // per-line cost and markup readable by the other client at all.
        pushTable("sales") {
            val sales = saleDao.pendingSales().filter { it.status == "completed" }
            if (sales.isEmpty()) return@pushTable 0

            val headers = mutableListOf<SalePushDto>()
            val lines = mutableListOf<SaleItemPushDto>()
            val tenders = mutableListOf<SalePaymentPushDto>()
            for (s in sales) {
                val all = saleDao.allLinesForSale(s.id)
                // The HEADER's money is derived from the LIVE lines only — a line
                // tombstoned by an in-place receipt edit is not part of what the receipt
                // says now, and counting it would put goods on the cloud row that the
                // printed receipt does not have.
                headers += buildSalePush(s, all.filter { !it.deleted }).copy(businessId = cloudBid)
                // The LINE rows go up in full, tombstones included, carrying their
                // `deleted` flag — that is how the removal itself reaches the other
                // devices. Dropping them here would leave the deleted line alive in the
                // cloud forever.
                lines += all.map { it.toPush().copy(businessId = cloudBid) }
                tenders += salePaymentDao.forSale(s.id).map { it.toPush().copy(businessId = cloudBid) }
            }

            api.upsert("sales", syncJson.encodeToString(headers), "id")
            if (lines.isNotEmpty()) {
                api.upsert("sale_items", syncJson.encodeToString(lines), "id")
            }
            if (tenders.isNotEmpty()) {
                api.upsert("sale_payments", syncJson.encodeToString(tenders), "id")
            }
            // Only mark clean once all three landed: the upserts above throw on failure,
            // so reaching this line means the whole sale is up, not just its header. A
            // header without its lines would read as a sale of nothing.
            sales.forEach { saleDao.markSaleSynced(it.id) }
            sales.size
        }

        // refunds — first-class rows now, not `type='return'` sales with negative totals.
        // `refunds.sale_id` and `refund_items.sale_line_id` carry the link back to the
        // original receipt, which the old shared schema had no column for and dropped.
        pushTable("refunds") {
            val refunds = refundDao.pending()
            if (refunds.isEmpty()) return@pushTable 0

            val headers = refunds.map { it.toPush().copy(businessId = cloudBid) }
            val lines = mutableListOf<RefundItemPushDto>()
            val payouts = mutableListOf<RefundPaymentPushDto>()
            for (r in refunds) {
                lines += refundDao.linesFor(r.id).map { it.toPush().copy(businessId = cloudBid) }
                payouts += refundDao.paymentsFor(r.id).map { it.toPush().copy(businessId = cloudBid) }
            }

            api.upsert("refunds", syncJson.encodeToString(headers), "id")
            if (lines.isNotEmpty()) {
                api.upsert("refund_items", syncJson.encodeToString(lines), "id")
            }
            if (payouts.isNotEmpty()) {
                api.upsert("refund_payments", syncJson.encodeToString(payouts), "id")
            }
            refundDao.markSynced(refunds.map { it.id })
            refunds.size
        }

        // credit_txns — note the table name. The cloud column `customer_id` is a plain
        // uuid FK, so the old bigint→local_id translation (and the whole class of "this
        // row is waiting on its customer to sync" warnings it needed) is gone.
        pushTable("credit_txns") {
            val rows = creditDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("credit_txns", syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }), "id")
            creditDao.markSynced(rows.map { it.id })
            rows.size
        }

        // mobile_money_receipts — THE ONE TABLE where ignore-duplicates is correct.
        // `(business_id, txn_code)` is a real idempotency key: the same provider SMS read
        // twice is the same receipt, so skipping the second copy loses nothing. This is
        // the only place in this engine where a dropped row is not a lost fact.
        pushTable("mobile_money_receipts") {
            val rows = mobileMoneyDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "mobile_money_receipts",
                syncJson.encodeToString(rows.map { it.toReceiptPush().copy(businessId = cloudBid) }),
                "business_id,txn_code",
                ignoreDuplicates = true,
            )
            mobileMoneyDao.markSynced(rows.map { it.id })
            rows.size
        }

        // stock_movements — the stock ledger, and the ONLY way a till's stock reaches
        // anyone else. The catalogue is pull-only, so a till never edits the product; it
        // appends a movement and every device recomputes on-hand from the ledger. Without
        // this block a sale drew down one till and no other till ever heard.
        pushTable("stock_movements") {
            val rows = stockMovementDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "stock_movements",
                syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            stockMovementDao.markSynced(rows.map { it.id })
            rows.size
        }

        // cash_movements — the till/safe ledger. Pushed so a float top-up or a drop made
        // on a PHONE reaches the shop's drawer count; without it the cash-up saw the
        // phone's sales but none of its cash handling.
        //
        // Stamped with the shift that is open on THIS device, or null when none is. Null
        // is safe by agreement: the web counts an unstamped movement whose timestamp
        // falls inside the shift window, the same way it counts an unstamped sale.
        //
        // ★ `type` is CHECK-constrained to seven values and a violation fails the WHOLE
        // batch, so it goes through [cashMovementTypeToWire], which is guaranteed to emit
        // a legal one. This app's vocabulary is much wider than those seven (sale,
        // expense, drawing, loan, transfer_in/out, safe_withdrawal, variance …), so
        // anything without an exact counterpart collapses to pay_in/pay_out by sign. The
        // DIRECTION of the money is always preserved; the finer category is not, and that
        // is a deliberate trade rather than a mapping still to be finished — inventing a
        // category for a movement whose meaning we are guessing at would put real money
        // under the wrong heading in the shop's own cash report.
        pushTable("cash_movements") {
            val pending = cashTxnDao.pending()
            if (pending.isEmpty()) return@pushTable 0
            // ★ A sale's and a refund's cash never go up — the other side already counts
            // them from the tenders and the change, and a second copy doubles the drawer.
            // See [cashMovementCountedElsewhere].
            val (counted, ours) = pending.partition { cashMovementCountedElsewhere(it.refType) }
            // Marked clean without being sent. They are deliberately local-only, and
            // leaving them pending would park them in the "N to upload" badge forever —
            // a queue indicator that never empties is worse than none, because the owner
            // learns to ignore it and then it cannot tell them when something IS stuck.
            if (counted.isNotEmpty()) cashTxnDao.markSynced(counted.map { it.id })
            if (ours.isEmpty()) return@pushTable 0
            // ★ Stamped by the movement's OWN day, not by "whichever shift is open now".
            // `cash_movements` has no local sessionId column, so this is the only moment the
            // link is made — and a batch that has been sitting on a phone since before
            // midnight would otherwise land yesterday's petty cash inside today's shift and
            // put the discrepancy in the wrong day's cash-up. A day with no session yet
            // stamps null, which is the same fallback as before: the other side counts an
            // unstamped movement whose timestamp falls inside the shift window.
            val sessionByDay = indexSessionsByDay(cashSessionDao.allLive(bid))
            api.upsert(
                "cash_movements",
                syncJson.encodeToString(
                    ours.map {
                        it.toMovementPush(sessionByDay[startOfDay(it.createdAt)])
                            .copy(businessId = cloudBid)
                    }
                ),
                "id",
            )
            cashTxnDao.markSynced(ours.map { it.id })
            ours.size
        }

        // cash_sessions — the shift itself. Pushed AFTER the sales and refunds that
        // reference it so the shop's own cash-up never sees a takings row pointing at a
        // shift the cloud has not been told about yet.
        //
        // `variance` is GENERATED on the cloud and is absent from the DTO. A merged-away
        // shift goes up here too, carrying its closed status and its note, which is how
        // the other tills learn the conflict was settled and stop trying to settle it.
        pushTable("cash_sessions") {
            val rows = cashSessionDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert("cash_sessions", syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }), "id")
            cashSessionDao.markSynced(rows.map { it.id })
            rows.size
        }

        // suppliers — the vendor list. BEFORE `purchase_orders`, which name a supplier by
        // uuid: parents before children, the same rule the rest of this pass follows.
        pushTable("suppliers") {
            val rows = supplierDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "suppliers",
                syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            supplierDao.markSynced(rows.map { it.id })
            rows.size
        }

        // purchase_orders — the restock orders. TWO things here fail the WHOLE batch
        // rather than the offending row, so both are handled at the boundary:
        //
        // ★ `status` is CHECK-constrained to draft/sent/received/cancelled and this app
        // writes `placed` and `partial`, NEITHER of which is legal. It goes through
        // [com.portionspot.pos.data.purchaseOrderStatusToWire], which can only emit one of
        // the four. The trip is lossy — both collapse to `sent` — and the pull is what
        // puts the finer state back; see [pullPurchaseOrders].
        //
        // ★ `supplier_id` is a `uuid` column while the local field is a free String, so it
        // is filtered through [uuidOrNull] and sent as NULL when it cannot be one. Losing
        // one link is recoverable; a rejected batch loses every order in it.
        pushTable("purchase_orders") {
            val rows = poDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "purchase_orders",
                syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            poDao.markSynced(rows.map { it.id })
            rows.size
        }

        // purchase_order_items — the lines, AFTER their headers. Pushed off their OWN
        // pending set rather than the headers': a line goes dirty on its own when a
        // received quantity is filled in, while its order has long since gone up clean.
        //
        // The cloud REQUIRES `business_id` on a line and [PurchaseOrderLine] has no such
        // field, so it is injected here like every other row. The line has no clock of its
        // own either — it only ever changes as part of a PO write — so `client_updated_at`
        // is taken from the parent order.
        pushTable("purchase_order_items") {
            val rows = poDao.pendingLines()
            if (rows.isEmpty()) return@pushTable 0
            // `po_id` is `uuid NOT NULL`. A line whose parent id is not one cannot be sent
            // at all, and sending it would take every other line down with it. Left
            // PENDING rather than marked clean: a row that stays in the queue is visible,
            // a row marked sent that never went is not.
            val sendable = rows.filter { uuidOrNull(it.poId) != null }
            if (sendable.isEmpty()) return@pushTable 0
            val stamps = HashMap<String, Long>()
            val payload = sendable.map { line ->
                val stamp = stamps.getOrPut(line.poId) { poDao.getById(line.poId)?.updatedAt ?: 0L }
                line.toPush(if (stamp > 0L) stamp else System.currentTimeMillis())
                    .copy(businessId = cloudBid)
            }
            api.upsert("purchase_order_items", syncJson.encodeToString(payload), "id")
            poDao.markLinesSynced(sendable.map { it.id })
            sendable.size
        }

        // expenses — the shop's running costs.
        //
        // ★ TEMPLATES ARE NOT SENT. An `isTemplate` row is a recurring SCHEDULE, not a
        // cost that was incurred, and the shared table has only nine columns — none of
        // them saying which is which. Pushed up it would read as a real expense and be
        // counted against profit ALONGSIDE every child it posts, so the same rent would
        // reduce the month's profit twice and keep doing so every month.
        //
        // The twenty-one local fields with no cloud column (the approval lifecycle, the
        // cash/payable/capital funding split, the recurrence engine) simply do not travel.
        // The pull merges onto the local row rather than replacing it, so they survive.
        pushTable("expenses") {
            val pending = expenseDao.pending()
            if (pending.isEmpty()) return@pushTable 0
            val (templates, real) = pending.partition { it.isTemplate }
            // Marked clean without being sent — deliberately local, and leaving them
            // pending would re-read them out of the database on every pass forever.
            if (templates.isNotEmpty()) expenseDao.markSynced(templates.map { it.id })
            if (real.isEmpty()) return@pushTable 0
            api.upsert(
                "expenses",
                syncJson.encodeToString(real.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            expenseDao.markSynced(real.map { it.id })
            real.size
        }

        // audit_entries — the append-only trail. Note the names: the CLOUD table is
        // `audit_entries` and the local Room table is `audit_log`.
        //
        // Merge-upsert on `id` like everything else. The old code insert-once'd here on
        // the belief that the cloud had no UPDATE policy; the live table has a single
        // tenant policy covering ALL commands, so there is nothing to work around and a
        // row re-sent after a failed pass overwrites only itself.
        //
        // ★ `meta` is jsonb up there and a JSON string down here — see [AuditEntryPushDto].
        pushTable("audit_entries") {
            val rows = auditDao.pending()
            if (rows.isEmpty()) return@pushTable 0
            api.upsert(
                "audit_entries",
                syncJson.encodeToString(rows.map { it.toPush().copy(businessId = cloudBid) }),
                "id",
            )
            auditDao.markSynced(rows.map { it.id })
            rows.size
        }

        // ── Deliberately NOT pushed, so this is a decision and not an oversight ──
        //
        // No cloud table exists for these at all, and inventing one from a till would
        // stand up a second schema beside the web's:
        //   notifications · staff_requests · day_closes · outside_funds
        //
        // `cash_txns` has no table of its own either: it is split across `cash_movements`
        // and `cash_sessions` above, and a sale's or refund's drawer movement is dropped
        // entirely because the other side already counts it.

        if (rlsBlocked.isNotEmpty()) {
            val held = rlsBlocked.joinToString(", ")
            errors.add(
                if (accessToken() == null) {
                    "This device is not signed in, so the database refused the upload from a " +
                        "staff account. Sign in under Settings, then tap Sync now ($held)."
                } else {
                    "Your staff account cannot upload to this database — it may have been " +
                        "deactivated, or it is not on the shop's staff list ($held)."
                }
            )
        }

        return PushResult(n, errors)
    }

    /**
     * Re-mint any UNSENT tag whose id was derived from the wrong business.
     *
     * An attribute's id is `uuidV5(namespace, business ‖ item ‖ key ‖ value)` and both
     * clients depend on computing the same one. The repository can only derive it from the
     * business id the row is filed under LOCALLY, which is this device's own uuid — the
     * shop's id is a sync concept and is not known at all until the first connect. So every
     * tag added in local mode carries an id the web would never produce. Sent as-is it
     * would land as a second row for a tag the shop already has, and the live partial
     * unique index would reject the batch.
     *
     * Only `pendingSync` rows are touched, and the re-key is a hard delete plus an insert
     * under the correct id: the row has never been uploaded, so there is nothing out there
     * holding the old id and nothing to tombstone. A pulled row is never re-keyed — its id
     * came from the web and is by definition the agreed one.
     *
     * Oldest first, so that when a removal and a re-add of the same tag both collapse onto
     * one id, the later write is the one left standing — and so the batch cannot contain
     * the same id twice, which Postgres refuses outright ("cannot affect row a second
     * time") and would again cost the whole push.
     */
    private suspend fun rekeyPendingAttributes(cloudBid: String) {
        for (row in itemAttributeDao.pending().sortedBy { it.updatedAt }) {
            val wanted = attributeId(cloudBid, row.itemId, row.key, row.value)
            if (wanted == row.id) continue
            itemAttributeDao.hardDelete(row.id)
            itemAttributeDao.upsert(row.copy(id = wanted))
        }
    }

    // ── pull ──────────────────────────────────────────────────────────────
    /**
     * Pull the shared catalogue, customers and sales onto this device. Read-only:
     * nothing is written to the cloud here, so a mapping bug cannot corrupt prod data.
     *
     * ★ Only tables that EXIST on the shared schema are pulled. The previous version
     * asked for `products`, `credit_transactions`, `cash_txns`, `audit_log`,
     * `notifications` and `staff_requests` — six tables the web database does not have.
     * Every pass therefore spent six round-trips collecting 404s and reporting them to
     * the owner as sync errors, which made the real errors impossible to pick out.
     *
     * Parents before children throughout: customers before the sales and credit rows
     * that reference them, and sale headers before their lines and tenders.
     */
    private suspend fun pull(api: SupabaseRest, cloudBid: String): Int {
        val bid = businessDao.getOnce()?.id ?: return 0
        var n = 0
        n += pullItems(api, bid, cloudBid)
        n += pullItemAttributes(api, bid, cloudBid)
        n += pullCustomersWire(api, bid, cloudBid)
        n += pullSalesWire(api, bid, cloudBid)
        n += pullSaleItems(api, bid, cloudBid)
        n += pullSalePayments(api, bid, cloudBid)
        // Runs every pass, not just when sales came down: devices that already
        // double-counted need the repair even once their cursor is past those rows.
        healDuplicateSales(bid)
        n += pullCreditWire(api, bid, cloudBid)
        n += pullMobileMoney(api, bid, cloudBid)
        n += pullRefunds(api, bid, cloudBid)
        n += pullRefundItems(api, bid, cloudBid)
        n += pullRefundPayments(api, bid, cloudBid)
        // AFTER both tender pulls: a pull writes another till's sale, its lines and its
        // tenders but NO cash-ledger row, so without this each phone's drawer counts only
        // its own takings — and the day close then measures the real, shared drawer against
        // half a figure and books the difference as a variance against profit. Placed here
        // for the same reason recomputeStockFromLedger sits after its ledger: a sale's cash
        // is its tenders less the change given, so reconciling before `sale_payments` and
        // `refund_payments` have landed would read a sale with no tenders and book zero.
        reconcileCash(bid)
        n += pullStockMovements(api, bid, cloudBid)
        // AFTER the ledger lands: items.stock_qty is a CACHE of these rows, so the
        // pulled cache is only as good as the movements behind it. Recomputing here is
        // what makes another till's sale show up as stock leaving this one.
        recomputeStockFromLedger(bid)
        n += pullCashSessions(api, bid, cloudBid)
        // AFTER the session pull, because that pull is the moment two tills that were
        // offline from each other finally see each other's shift. Resolving before it
        // would just re-decide a conflict this device cannot yet know exists.
        mergeOpenSessions(bid)
        // The other half of the drawer, and it sits HERE for two reasons. A movement names
        // the shift it happened in, so the shifts land first — nothing local enforces that
        // link, but a pass that showed a phone a day's cash before it had heard of the day
        // is a pass whose order nobody could reason about later. And it is AFTER
        // reconcileCash above, which is the pass that rebuilds another till's SALE cash:
        // these two write to the same ledger from opposite sources and must not be read as
        // one step, because only reconcileCash is allowed to touch a sale's or refund's
        // drawer row and only this is allowed to import anything else.
        n += pullCashMovements(api, bid, cloudBid)
        // ── Accounting spine + supplier orders ──
        // Suppliers before the orders that name them, and orders before their lines.
        n += pullSuppliers(api, bid, cloudBid)
        n += pullPurchaseOrders(api, bid, cloudBid)
        n += pullPurchaseOrderLines(api, cloudBid)
        n += pullExpenses(api, bid, cloudBid)
        n += pullAudit(api, bid, cloudBid)
        // The roster + the credential behind it. Last because nothing else waits on it,
        // and deliberately NOT stamped with `bid` — see [pullStaff].
        n += pullStaff(api, cloudBid)
        return n
    }

    /**
     * `staff` → the local roster the sign-in screen checks a PIN against.
     *
     * ★★ THE ONLY PULL IN THIS FILE THAT DOES NOT TAKE `bid` ★★
     *
     * Every other mapper here is handed the LOCAL business uuid — the id this device
     * invented on first run — because local rows are keyed by it and nothing about their
     * data depends on WHICH id it is. A staff row is the exception, and the difference is
     * invisible until a cashier is standing at the counter.
     *
     * `staff.pin_hash` is PBKDF2 over a salt derived as
     * `SHA-256("PortionSpot POS pin v1:" + business_id)`, copied from the web's `pin.js`
     * so one hash works on both clients. The web salts with the CLOUD id. Stamp these rows
     * with the local uuid instead and every hash this app derives disagrees with every hash
     * the web wrote — no exception, no log line, just a correct PIN reported as wrong,
     * forever, looking exactly like the cashier mistyping it.
     *
     * So the local `staff.businessId` IS the cloud business id, and the roster queries
     * ([com.portionspot.pos.data.StaffDao.all]) are keyed by that. This is not a shortcut
     * around the local/cloud split; it is the one table where the hash decides the key.
     *
     * Pull-only. Creating or editing a cashier is a direct PostgREST write from the admin
     * device ([com.portionspot.pos.auth.StaffAdminClient]), which mirrors the row locally
     * itself, so there is no pending-push set here to send back up.
     */
    private suspend fun pullStaff(api: SupabaseRest, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<StaffDto>>(
            api.selectSince("staff", cloudBid, config.cursor("staff"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // ★ UNCONDITIONAL OVERWRITE — no last-write-wins comparison, unlike every other
        // pull here. This table is the revocation channel: the cloud is the authority on
        // who may open the till and what they may do, and a local row is only ever a bridge
        // the admin console wrote so a change it just made is usable before the next pull.
        // A last-write-wins check would hand that bridge a veto — and on a phone whose clock
        // runs a few hours fast, a stale local row would shadow a real deactivation for
        // exactly as long as the drift. There is nothing on this side worth defending.
        for (dto in rows) {
            staffDao.upsert(dto.toStaffMember(cloudBid, staffDao.getById(dto.id)))
        }
        config.setCursor("staff", rows.maxOf { it.cursorStamp() })
        return rows.size
    }

    /**
     * Learn the shop's business id from the cloud and remember it.
     *
     * ★ ADOPT, NEVER INSERT. A device makes up its own business uuid on first run, before
     * it has ever seen a database. Pushing rows under that id uploads them successfully
     * and renders them invisible to every other client, because the web filters by ITS
     * business — a 2xx, no error, and takings that belong to nobody.
     *
     * Creating a `businesses` row instead would be worse still: a second business row
     * permanently disables the web's automatic adoption, by design, so the mistake could
     * not be undone from here afterwards.
     *
     * `id` is the column to take, NOT `business_id` — the table has both, and `id` is the
     * one every `items.business_id` actually references. Only adopted when the database
     * holds EXACTLY ONE business: two rows means this is not a single-shop database and
     * guessing which one the till belongs to is not a decision code should make silently.
     */
    private suspend fun adoptBusinessId(api: SupabaseRest) {
        if (config.cloudBusinessId() != null) return
        val rows = runCatching {
            syncJson.decodeFromString<List<BusinessIdRow>>(api.selectAll("businesses", "id"))
        }.getOrNull() ?: return
        val only = rows.singleOrNull() ?: return
        if (only.id.isNotBlank()) config.setCloudBusinessId(only.id)
    }

    /**
     * The shop's money RULES, two-way — the per-line discount cap, the PIN-gate
     * percentage and the cash-variance note threshold.
     *
     * ── WHY THIS EXISTS ───────────────────────────────────────────────────────
     *
     * Until now these three lived only in this phone's key/value settings, so "the shop's
     * discount cap" was really "whatever the phone in your hand was last told". The owner
     * raising the cap on his handset left the cashier's till enforcing the old one, and
     * lowering it left the cashier's till still allowing the larger discount on every line
     * of every sale. Neither phone errors, neither logs anything, and the only trace is a
     * discount report that does not match the rule anybody thinks is in force.
     *
     * `businesses.discount_threshold` has been on the shared row the whole time and
     * Android has never once read it: the only reference to this table in this file was
     * [adoptBusinessId], which asks for `id` and nothing else.
     *
     * ── THE HAZARD, STATED WHERE IT WOULD BE INTRODUCED ───────────────────────
     *
     * ★★ The push is a PATCH, never an upsert. ★★ PostgREST resolves an upsert of a
     * partial row by NULLING every column it was not handed, and this app is handed three
     * columns of a row that carries the shop's bank account, its EcoCash merchant code,
     * its VAT number, its receipt header and footer, its logo and seven payment-method
     * flags. Upserting here would wipe all of them, return 2xx, and report a successful
     * sync; the owner would find out on the next receipt he printed. See
     * [SupabaseRest.updateById] and [com.portionspot.pos.sync.wire.BusinessPolicyPatchDto].
     *
     * ★ Filtered on `id`, which is the CLOUD business id, never the local one. The device
     * mints its own `businesses.id` on first run and it is not this shop's — the same trap
     * [pullStaff] documents, and it fails the same silent way: a PATCH filtered on an id
     * the shared database has never heard of matches zero rows and returns 2xx.
     *
     * A database that predates the columns is treated as one that simply has not stated a
     * policy: the step becomes a no-op rather than an error the owner sees every pass and
     * cannot act on.
     */
    private suspend fun syncShopPolicy(api: SupabaseRest, cloudBid: String): List<String> {
        val (local, changedAt) = readShopPolicy() ?: return emptyList()
        val row = try {
            syncJson.decodeFromString<List<BusinessPolicyDto>>(
                api.selectRowById("businesses", cloudBid, POLICY_COLUMNS)
            ).firstOrNull() ?: return emptyList()
        } catch (e: IOException) {
            if (e.isMissingTable() || e.isMissingColumn()) return emptyList()
            return listOf("shop settings: ${e.message}")
        } catch (e: Exception) {
            return listOf("shop settings: ${e.message ?: e.javaClass.simpleName}")
        }

        val plan = planShopPolicySync(
            local = local,
            localChangedAt = changedAt,
            wire = row.toPolicyValues(),
            wireChangedAt = IsoTime.toMillis(row.cursorStamp()),
        )
        return when (plan) {
            ShopPolicyPlan.Settled -> emptyList()
            is ShopPolicyPlan.Adopt -> {
                writeShopPolicy(plan.policy)
                emptyList()
            }
            is ShopPolicyPlan.Push -> {
                // Held back exactly like every other push while the till is pull-only. The
                // local edit is NOT discarded and is NOT overwritten: the plan stays Push
                // for as long as this device's clock is the newer one, so the owner's
                // change goes up the moment he enables uploading rather than being lost.
                if (!config.pushEnabled()) return emptyList()
                try {
                    api.updateById(
                        "businesses",
                        cloudBid,
                        syncJson.encodeToString(plan.policy.toPatch(changedAt)),
                    )
                    emptyList()
                } catch (e: Exception) {
                    listOf("shop settings: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * `refunds` → local refund headers.
     *
     * A refund whose wire row names no `sale_id`, on a device that has no local copy to
     * take one from, is SKIPPED rather than imported. `Refund.saleId` is NOT NULL on this
     * side, so importing it would mean inventing a sale id — attaching real money to a
     * receipt it never came off, which is worse than not having the row at all. It stays
     * skipped rather than being counted as applied, so the cursor is the only thing that
     * moves and a later pass can pick it up if the sale arrives.
     */
    private suspend fun pullRefunds(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<RefundDto>>(
            api.selectSince("refunds", cloudBid, config.cursor("refunds"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = refundDao.getById(dto.id)
            if (local != null && IsoTime.toMillis(dto.cursorStamp()) <= local.updatedAt) continue
            val merged = dto.toRefund(bid, local) ?: continue
            refundDao.upsert(merged)
            applied++
        }
        config.setCursor("refunds", rows.maxOf { it.cursorStamp() })
        return applied
    }

    private suspend fun pullRefundItems(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<RefundItemDto>>(
            api.selectSince("refund_items", cloudBid, config.cursor("refund_items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val live = rows.filter { !it.deleted }
        if (live.isNotEmpty()) refundDao.upsertLines(live.map { it.toRefundLine(bid) })
        config.setCursor("refund_items", rows.maxOf { it.cursorStamp() })
        return live.size
    }

    private suspend fun pullRefundPayments(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<RefundPaymentDto>>(
            api.selectSince("refund_payments", cloudBid, config.cursor("refund_payments"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val live = rows.filter { !it.deleted }
        if (live.isNotEmpty()) refundDao.upsertPayments(live.map { it.toRefundPayment(bid) })
        config.setCursor("refund_payments", rows.maxOf { it.cursorStamp() })
        return live.size
    }

    /** `stock_movements` → the local ledger. Append-only in spirit, upserted by id so a
     *  re-pull after a cursor reset lands on the row it already wrote. */
    private suspend fun pullStockMovements(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<StockMovementDto>>(
            api.selectSince("stock_movements", cloudBid, config.cursor("stock_movements"), PAGE)
        )
        if (rows.isEmpty()) return 0
        val applied = rows.mapNotNull { it.toStockMovement(bid) }
        if (applied.isNotEmpty()) stockMovementDao.upsertAll(applied)
        config.setCursor("stock_movements", rows.maxOf { it.cursorStamp() })
        return applied.size
    }

    /**
     * Rebuild every item's on-hand from its movement ledger.
     *
     * `items.stock_qty` is a CACHE. The authority is the sum of that item's movements,
     * and this is the step that makes a sale rung up on another till show as stock leaving
     * this one — without it, a device would keep whatever figure the catalogue pull
     * happened to carry and never notice the other till's draw-down.
     *
     * ★ Items with NO movements are left ALONE rather than zeroed. A shop that has been
     * trading before the ledger existed has plenty of stock and no history for it, and
     * "no movements recorded" means unknown, not none. Zeroing them would empty the
     * shelves of a working shop on its first sync.
     *
     * ★ A `measure` product's on-hand lives in [Item.stockMeasured], not [Item.stockQty] —
     * the fractional quantity and the whole-unit count are different fields on this side,
     * even though the cloud keeps both in `stock_qty`.
     */
    private suspend fun recomputeStockFromLedger(bid: String) {
        val moved = stockMovementDao.deltaSinceBaselineByItem(bid).associate { it.itemId to it.onHand }
        val updated = itemDao.allForBusinessOnce(bid).mapNotNull { item ->
            // The shop's figure plus everything that has moved since — INCLUDING nothing
            // at all, which is why the delta cannot be `moved[item.id] ?: return`: an item
            // whose only movements predate its baseline must settle back to that figure
            // rather than keep a stale local number. Null = no baseline = leave it alone.
            val computed = stockOnHandFromDelta(item, moved[item.id] ?: 0.0)
                ?: return@mapNotNull null
            val measured = item.productType == "measured"
            val current = if (measured) item.stockMeasured else item.stockQty
            if (abs(current - computed) < 0.0005) return@mapNotNull null
            if (measured) item.copy(stockMeasured = computed) else item.copy(stockQty = computed)
        }
        if (updated.isNotEmpty()) itemDao.upsertAll(updated)
    }

    /** `cash_sessions` → local shifts, keyed by id. */
    private suspend fun pullCashSessions(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<CashSessionDto>>(
            api.selectSince("cash_sessions", cloudBid, config.cursor("cash_sessions"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = cashSessionDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.cursorStamp()) > local.updatedAt) {
                cashSessionDao.upsert(dto.toCashSession(bid, local))
                applied++
            }
        }
        config.setCursor("cash_sessions", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * Collapse a shop that has ended up with more than one open shift down to one.
     *
     * The unique index on the cloud REJECTS the second open session, and rejecting it is
     * not the same as resolving it: the losing till's sales still reference a session id
     * the cloud has never heard of, and a cash-up that cannot resolve a session leaves
     * those takings out of the count silently. So the resolution happens here, on every
     * device, using a rule that reaches the same answer everywhere without any device
     * asking another — see [planSessionMerge].
     *
     * Everything happens in ONE transaction: repoint the losers' sales and refunds onto
     * the survivor, then close the losers with a note saying what became of them. Split
     * across two transactions there would be a window where a sale points at a shift that
     * has already been closed, which is the very state this is fixing.
     *
     * Every touched row is marked dirty, so the correction goes UP on the same pass
     * rather than living only on the device that happened to notice.
     */
    private suspend fun mergeOpenSessions(bid: String) {
        // ★ ROLL THE DAY OVER FIRST. A shift is a trading DAY, and the merge rule keeps the
        // OLDEST open session — correct among sessions of the same day, and badly wrong
        // across days. With yesterday's session still open the merge would keep YESTERDAY
        // and repoint today's sales back into it, moving a day's takings into the wrong
        // period on every device at once. Closing the ended days first means the merge only
        // ever ranks candidates from a single day, which is the state its rule assumes.
        val stamp = System.currentTimeMillis()
        val rollover = planDayRollover(cashSessionDao.openSessions(bid), stamp)
        for (c in rollover.closes) cashSessionDao.closeForDay(c.id, c.note, c.closedAt, stamp)
        // Deliberately no OPENING here: only a real sale opens a day (see
        // [PosRepository.currentDaySessionId]). A sync pass on an idle phone that minted the
        // day's session would have every phone in the shop racing to create the same row.
        val plan = planSessionMerge(cashSessionDao.openSessions(bid)) ?: return
        val mergedAt = System.currentTimeMillis()
        cashSessionDao.applyMerge(
            winnerId = plan.winner.id,
            losers = plan.losers.map { it.id to plan.closingNote(it) },
            at = mergedAt,
        )
        // ★ CARRY THE COUNT ACROSS, or the merge buries it. applyMerge moves the losers'
        // sales and refunds and closes them; it says nothing about a drawer somebody
        // physically counted. When the shift that lost the merge was the counted one, the
        // survivor read as never counted, every other device went on offering to close the
        // day, and counting it again moves the takings to the safe a second time — out of a
        // drawer that no longer holds them. See [countToRescue].
        val rescued = countToRescue(plan.winner, plan.losers) ?: return
        val count = rescued.countedCash ?: return
        cashSessionDao.closeWithCount(
            id = plan.winner.id,
            closedAt = rescued.closedAt ?: mergedAt,
            closedBy = rescued.closedBy,
            closedByName = rescued.closedByName,
            countedCash = count,
            expectedCash = rescued.expectedCash ?: 0.0,
            movedToSafe = rescued.movedToSafe,
            floatTarget = rescued.floatTarget,
            note = rescued.note,
            at = mergedAt,
        )
    }

    /**
     * `cash_movements` → the local cash ledger. **The half that was missing.**
     *
     * This table was pushed and never pulled, and the owner found it with two phones over
     * one drawer: phone A closed the trading day — which writes a `variance` true-up and a
     * till→safe transfer — and recorded an owner drawing. All of it reached the database.
     * None of it ever reached phone B, so the two phones disagreed about what was in the
     * till and what was in the safe, and neither could say why.
     *
     * ── WHY THIS CANNOT DOUBLE-COUNT A SALE ───────────────────────────────────────
     *
     * A sale's and a refund's drawer movement are rebuilt locally from the pulled tenders
     * by [reconcileCash], NOT imported. If a wire row could also carry that same money, the
     * two mechanisms would each book it once and every synced sale would appear in the
     * drawer twice. The guard is STRUCTURAL and it lives on the push, not here: no client
     * ever sends one. This app excludes them by [cashMovementCountedElsewhere], and the web
     * never writes them at all — its cash-up derives takings from `sale_payments`, change
     * from `sales.change_due` and payouts from `refund_payments`, and its own screen says
     * so. So the invariant is "the table does not contain sale money", not "the pull
     * filters sale money out", and it holds because BOTH writers uphold it.
     *
     * ★ That invariant is not checkable from a wire row. `cash_movements` has no `ref_type`
     * and no `ref_id`, so a row that violated it would be indistinguishable from an honest
     * pay-in. The one check that IS possible is made below — an id this device already
     * holds as a sale's or refund's own row is never overwritten — and it is a backstop, not
     * the mechanism. Anything that starts writing sale-derived rows into this table breaks
     * the drawer on every device in the shop, and the place to stop it is there.
     *
     * Keyed by id, which is the push's own key: a device re-reading a row it wrote finds
     * its own local row and leaves the money alone (see [toCashTxn]).
     */
    private suspend fun pullCashMovements(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<CashMovementDto>>(
            api.selectSince("cash_movements", cloudBid, config.cursor("cash_movements"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = cashTxnDao.getById(dto.id)
            // The backstop described above. A local sale/refund row is the mirror's to own
            // and nothing on the wire may touch it — not even its tombstone, because
            // [planCashMirror] converges that money by BALANCE and would immediately write
            // a correction row for whatever this took away.
            if (local != null && cashMovementCountedElsewhere(local.refType)) continue
            if (local == null || IsoTime.toMillis(dto.cursorStamp()) > local.updatedAt) {
                cashTxnDao.upsert(dto.toCashTxn(bid, local))
                applied++
            }
            // ★ THE OTHER POCKET. The web writes a till→safe drop, a safe→till float
            // top-up and a bank deposit as ONE row each, because it derives the location
            // from the type; this app keeps one row per location. Import only the half the
            // type names and the money half-vanishes — a browser drop took cash out of the
            // phone's till and never put it in the phone's safe, so the two clients
            // disagreed about the shop's TOTAL cash on hand, not merely about where it was.
            //
            // Written under a derived, deterministic id so re-reading the source row on a
            // later pass updates this half instead of inserting another one. Safe to adopt
            // because this app never emits those words itself — see
            // [cashMovementTypeFromWire].
            val other = dto.counterpartCashTxn(bid) ?: continue
            val otherLocal = cashTxnDao.getById(other.id)
            if (otherLocal == null || IsoTime.toMillis(dto.cursorStamp()) > otherLocal.updatedAt) {
                cashTxnDao.upsert(other)
                applied++
            }
        }
        config.setCursor("cash_movements", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * `items` → the local catalogue, keyed by id.
     *
     * Keyed by ID, not bridged by sku or name. The old pull had to guess which local row
     * a cloud product meant — matching on folded sku, then on folded NAME — because the
     * shared table carried no id the device could recognise, and every near-miss inserted
     * a second copy of a product the shop already had. Both sides now agree on the uuid,
     * so there is nothing left to guess and no duplicate to heal afterwards.
     */
    private suspend fun pullItems(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<ItemDto>>(
            api.selectSince("items", cloudBid, config.cursor("items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = itemDao.getById(dto.id)
            val stamp = IsoTime.toMillis(dto.updatedAt)
            if (local == null || stamp > local.updatedAt) {
                itemDao.upsert(dto.toItem(bid, local))
                applied++
                continue
            }
            // The row is older than the local edit and must NOT overwrite it — but its
            // STOCK BASELINE still applies. Android never pushes `items.stock_qty`, so
            // the shop's figure is the only authority for what is on the shelf, and a
            // device that has rung up a sale (bumping its own updatedAt past the cloud's)
            // would otherwise never learn a baseline at all. Without one the ledger has
            // nothing to be measured from, which is precisely how a shelf of 2 read as
            // empty after selling 1.
            // Stamped on the CLIENT clock, the one the movements share — see
            // [ItemDto.baselineStamp]. `stamp` above is the server's and is only good for
            // ordering the pull.
            // ★ Only when the FIGURE moved. `client_updated_at` bumps for any edit at
            // all, so re-stamping the baseline on a rename or a reprice pushed it past
            // every movement made before that edit and dropped them — a product with 24
            // on the shelf and a sale of 3 against it reported 24 again the moment
            // someone fixed its spelling.
            val incoming = dto.stockQty.toMoney()
            val figureMoved = abs(local.stockBaseQty - incoming) >= 0.0005
            if (local.stockBaseAt <= 0L || figureMoved) {
                itemDao.upsert(
                    local.copy(stockBaseQty = incoming, stockBaseAt = dto.baselineStamp())
                )
            }
        }
        config.setCursor("items", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /**
     * `item_attributes` → the tags on each catalogue item (here: the cars a part fits).
     *
     * Runs straight after [pullItems] so a newly-arrived product and its fitments land in
     * the same pass — a tag whose item is not on the device yet is still stored, because
     * the search reads it by item id and simply finds nothing until the item shows up. That
     * is self-correcting; dropping the row would not be, since the cursor has moved past it.
     *
     * Tombstones apply like any other row: a fitment deleted on the web must stop matching
     * here, or the till keeps offering a part for a car the shop has decided it does not fit.
     *
     * Last-write-wins on the stamp, with no special case for a locally-pending row: the
     * push half of the pass has already run by the time this does (see [sync]), so a
     * tag typed on this phone is up there before anything comes back down, and the row that
     * returns is its own echo.
     */
    private suspend fun pullItemAttributes(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val body = try {
            api.selectSince("item_attributes", cloudBid, config.cursor("item_attributes"), PAGE)
        } catch (e: IOException) {
            // A database that predates this table must not lose its SALES over its tags.
            // Tags are enrichment — every other pull in this pass is money — so a missing
            // table degrades to "no fitments" rather than aborting the whole pull. Only a
            // missing table is swallowed; a network or permission failure still throws.
            if (e.isMissingTable()) return 0 else throw e
        }
        val rows = syncJson.decodeFromString<List<ItemAttributeDto>>(body)
        if (rows.isEmpty()) return 0
        val apply = ArrayList<ItemAttribute>(rows.size)
        for (dto in rows) {
            val local = itemAttributeDao.getById(dto.id)
            if (local != null && IsoTime.toMillis(dto.updatedAt) <= local.updatedAt) continue
            apply += dto.toItemAttribute(bid) ?: continue
        }
        if (apply.isNotEmpty()) itemAttributeDao.upsertAll(apply)
        config.setCursor("item_attributes", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return apply.size
    }

    private suspend fun pullCustomersWire(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<WireCustomerDto>>(
            api.selectSince("customers", cloudBid, config.cursor("customers"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = customerDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                customerDao.upsert(dto.toCustomer(bid, local))
                applied++
            }
        }
        config.setCursor("customers", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /** `sales` → local sale HEADERS. Lines and tenders arrive on their own cursors. */
    private suspend fun pullSalesWire(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<WireSaleDto>>(
            api.selectSince("sales", cloudBid, config.cursor("sales"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = saleDao.getSaleById(dto.id)
            if (local == null) {
                saleDao.upsertSale(dto.toSaleEntity(bid))
                applied++
            } else if (IsoTime.toMillis(dto.cursorStamp()) > local.updatedAt) {
                // Fold onto the row that already exists — its id is referenced by lines,
                // tenders, refunds, credit rows and the audit trail.
                saleDao.upsertSale(dto.mergeIntoSale(local))
                applied++
            }
        }
        config.setCursor("sales", rows.maxOf { it.cursorStamp() })
        return applied
    }

    private suspend fun pullSaleItems(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<SaleItemDto>>(
            api.selectSince("sale_items", cloudBid, config.cursor("sale_items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // The cloud row carries every field a SaleLine has, so it is applied whole rather
        // than merged onto a local copy — there is nothing local worth preserving on a
        // line, unlike a sale header (which owns edit markers and payment state).
        val toApply = rows.map { it.toSaleLine(bid, null) }
        saleDao.upsertLines(toApply)
        config.setCursor("sale_items", rows.maxOf { it.cursorStamp() })
        return toApply.size
    }

    private suspend fun pullSalePayments(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<SalePaymentDto>>(
            api.selectSince("sale_payments", cloudBid, config.cursor("sale_payments"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // A tender is append-only in practice; a deleted one is dropped rather than
        // applied, so a voided payment cannot reappear on the device that pulled it.
        val live = rows.filter { !it.deleted }
        salePaymentDao.upsertAll(live.map { it.toSalePayment(bid) })
        config.setCursor("sale_payments", rows.maxOf { it.cursorStamp() })
        return live.size
    }

    /** `credit_txns` → the local credit ledger. Note the table name: the old engine
     *  asked for `credit_transactions`, which has never existed on the web schema. */
    private suspend fun pullCreditWire(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<WireCreditDto>>(
            api.selectSince("credit_txns", cloudBid, config.cursor("credit_txns"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = creditDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.cursorStamp()) > local.updatedAt) {
                creditDao.upsert(dto.toCreditTxn(bid, local))
                applied++
            }
        }
        config.setCursor("credit_txns", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * `audit_entries` → the local trail (Room calls the table `audit_log`).
     *
     * APPEND-ONLY, so this is insert-the-missing and nothing else: an entry whose id
     * already exists locally is SKIPPED, never overwritten — that is the whole point of an
     * audit log, and it also makes a re-pull after a cursor reset a no-op instead of a
     * rewrite. Inserted rows land with `pendingSync = false` so they are not bounced
     * straight back up.
     *
     * Keyed by the uuid `id` both sides already agree on; there is no `local_id` bridge on
     * this table and never was one on the real schema.
     */
    private suspend fun pullAudit(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<AuditEntryDto>>(
            api.selectSince("audit_entries", cloudBid, config.cursor("audit_entries"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // A tombstoned entry is not imported: [AuditEntry] has no `deleted` field, so
        // there is nowhere to record the tombstone and inserting the row would resurrect
        // something the shop deliberately removed on the other side.
        val live = rows.filter { !it.deleted }
        var applied = 0
        if (live.isNotEmpty()) {
            val existing = auditDao.existingIds(live.map { it.id }).toSet()
            val fresh = live.filter { it.id !in existing }.map { it.toAuditEntry(bid) }
            if (fresh.isNotEmpty()) {
                auditDao.insertAll(fresh)
                applied = fresh.size
            }
        }
        config.setCursor("audit_entries", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * notifications → the local admin feed, matched by `(businessId, dedupeKey)` — NOT
     * by id, because every device mints its own row id for the same condition. A match
     * is updated in place (the local `id` and the device-local `pushedAt` survive); a
     * miss is inserted. Last-write-wins on `updated_at`, same as every other table.
     */
    private suspend fun pullNotifications(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<NotificationDto>>(
            api.selectSince("notifications", cloudBid, config.cursor("notifications"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            if (dto.dedupeKey.isBlank()) continue
            val local = notificationDao.getByDedupeKey(bid, dto.dedupeKey)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                notificationDao.upsert(dto.toNotification(bid, local))
                applied++
            }
        }
        config.setCursor("notifications", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * `expenses` → the local cost ledger, keyed by id.
     *
     * ★ A MERGE, never a replacement. The shared table has nine columns; [Expense] has
     * thirty-one. The approval lifecycle, the cash/payable/capital funding split and the
     * whole recurrence engine have no cloud column at all, so applying a wire row wholesale
     * would blank an approved expense's decision, its funding and its schedule — see
     * [com.portionspot.pos.sync.wire.toExpense], which also explains why a row authored on
     * the web is synthesised as already approved rather than left pending forever.
     */
    private suspend fun pullExpenses(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<ExpenseDto>>(
            api.selectSince("expenses", cloudBid, config.cursor("expenses"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = expenseDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                expenseDao.upsert(dto.toExpense(bid, local))
                applied++
            }
        }
        config.setCursor("expenses", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /** `suppliers` → the local vendor list, keyed by id. `createdAt` has no cloud column
     *  and is preserved from the local row. */
    private suspend fun pullSuppliers(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<SupplierDto>>(
            api.selectSince("suppliers", cloudBid, config.cursor("suppliers"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = supplierDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.updatedAt) > local.updatedAt) {
                supplierDao.upsert(dto.toSupplier(bid, local))
                applied++
            }
        }
        config.setCursor("suppliers", rows.maxOf { it.updatedAt ?: IsoTime.EPOCH })
        return applied
    }

    /**
     * `purchase_orders` → the local restock orders, keyed by id.
     *
     * ★ The status round trip is LOSSY and is repaired HERE. `placed` and `partial` both
     * go up as `sent` (the cloud CHECK allows nothing else), so coming back the mapper is
     * handed the LOCAL row and keeps a `partial` rather than resetting it to `placed` —
     * otherwise a half-received order would look un-received on the next pull and the same
     * goods could be booked into stock a second time.
     *
     * The PO's money (`cashPaid`, `capitalPaid`, `payableRemainder`) and its arrival prompt
     * (`eta`, `arrivalPromptedAt`) have no cloud column and survive the merge untouched.
     */
    private suspend fun pullPurchaseOrders(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<PurchaseOrderDto>>(
            api.selectSince("purchase_orders", cloudBid, config.cursor("purchase_orders"), PAGE)
        )
        if (rows.isEmpty()) return 0
        var applied = 0
        for (dto in rows) {
            val local = poDao.getById(dto.id)
            if (local == null || IsoTime.toMillis(dto.cursorStamp()) > local.updatedAt) {
                poDao.upsert(dto.toPurchaseOrder(bid, local))
                applied++
            }
        }
        config.setCursor("purchase_orders", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * `purchase_order_items` → the local PO lines, keyed by id.
     *
     * A line whose header has not arrived yet is still applied — `poId` is a plain value
     * with no foreign key on either side — so it can never be silently dropped for being
     * early. `sellPrice`, `stockOnArrival` and `productType` have no cloud column and are
     * preserved from the local row.
     */
    private suspend fun pullPurchaseOrderLines(api: SupabaseRest, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<PurchaseOrderItemDto>>(
            api.selectSince("purchase_order_items", cloudBid, config.cursor("purchase_order_items"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // [PurchaseOrderLine] has no `deleted` field, so a tombstone has nowhere to live
        // here; the row is dropped rather than applied, which is what keeps goods removed
        // from an order on the web from reappearing on it here.
        val live = rows.filter { !it.deleted }
        var applied = 0
        for (dto in live) {
            val local = poDao.getLineById(dto.id)
            // Lines carry no local clock, so the parent order's updatedAt is the tiebreak.
            val localStamp = local?.poId?.let { poDao.getById(it)?.updatedAt } ?: 0L
            if (local == null || IsoTime.toMillis(dto.cursorStamp()) > localStamp) {
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
    private suspend fun pullCredit(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<CreditDto>>(
            api.selectSince("credit_transactions", cloudBid, config.cursor("credit_transactions"), PAGE)
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
    private suspend fun pullMobileMoney(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<MobileMoneyDto>>(
            api.selectSince("mobile_money_receipts", cloudBid, config.cursor("mobile_money_receipts"), PAGE)
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
    private suspend fun pullProducts(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<ProductDto>>(
            api.selectSince("products", cloudBid, config.cursor("products"), PAGE)
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
    private suspend fun pullCustomers(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<CustomerDto>>(
            api.selectSince("customers", cloudBid, config.cursor("customers"), PAGE)
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
     * `items` column. `type='return'` refunds and quotes/holds are skipped in Stage 1 —
     * reconstructing them into the local refund tables is a later stage.
     *
     * MATCH FIRST, THEN DECIDE. A cloud sale is either brand new here (insert it) or it
     * is a row this device already holds (fold the cloud values onto it). It is NEVER a
     * second row: matching is by receipt REF first — which prefers the locally-created
     * UUID row, the one that owns the lines, tenders, refunds and audit trail — and by
     * cloud id second. That is the same identity test the old insert-once guards used,
     * so duplicates remain impossible; what changed is what happens on a match.
     *
     * The old code `continue`d on a match, which made the pull INSERT-ONLY: a receipt
     * edited on the till reached the cloud but never reached the other phones, so the
     * admin kept seeing the old total forever. An existing row is now REWRITTEN IN PLACE
     * (same primary key) whenever the cloud copy is genuinely newer, goods and all.
     *
     * LAST-WRITE-WINS, with unsynced local work protected: the cloud row must be
     * STRICTLY newer than the local row to be applied. A local row that is still dirty
     * (`synced = false`, i.e. its own edit has not gone up yet) therefore keeps winning
     * for as long as its stamp is at or ahead of the cloud's — including the very common
     * case of a device pulling back the row it just pushed, where the two stamps are
     * equal and nothing is touched.
     */
    private suspend fun pullSales(api: SupabaseRest, bid: String, cloudBid: String): Int {
        val rows = syncJson.decodeFromString<List<SaleDto>>(
            api.selectSince("sales", cloudBid, config.cursor("sales"), PAGE)
        )
        if (rows.isEmpty()) return 0
        // sku → local item id, so pulled sale lines join to the catalog for reports.
        val skuToId = itemDao.allForBusinessOnce(bid)
            .filter { !it.sku.isNullOrBlank() }
            .associate { it.sku!!.lowercase() to it.id }
        val resolveItemId: (String?) -> String? = { sku -> sku?.lowercase()?.let { skuToId[it] } }
        var applied = 0
        for (dto in rows) {
            if (dto.type != "sale") continue
            val ref = dto.ref ?: dto.id
            // IDENTITY: the push side keys cloud sales by REF (see buildSalePush), so a
            // sale THIS device made returns with id = "0012" while it lives locally under
            // a UUID. Matching on id alone missed that and inserted a second local row —
            // the dashboard then counted every till sale twice. Match on receiptNo OR id.
            // A sale that genuinely originated elsewhere (web POS, ref "PSM-260526-6315")
            // matches neither and still imports normally.
            val local = saleDao.getSaleByReceiptNo(bid, ref) ?: saleDao.getSaleById(dto.id)

            if (local == null) {
                saleDao.upsertSale(dto.toSaleEntity(bid))
                val lines = dto.toSaleLines(bid, resolveItemId = resolveItemId)
                if (lines.isNotEmpty()) saleDao.upsertLines(lines)
                applied++
                continue
            }

            // Only a genuinely newer cloud copy may overwrite what is on this device.
            if (IsoTime.toMillis(dto.cursorStamp()) <= local.updatedAt) continue

            // The goods must end up matching the pulled receipt exactly (an edit can add,
            // remove or re-quantify lines), so they are replaced wholesale rather than
            // merged line by line. They hang off the LOCAL id, never the cloud one.
            val newLines = dto.toSaleLines(bid, local.id, resolveItemId)
            val merged = dto.mergeIntoSale(local)
            // "Edited" is a local inference: the cloud `sales` table has no edited_at /
            // edit_count column and this fix does not change the cloud schema. A newer
            // cloud row whose money, state or goods actually differ IS the edit, so it
            // carries the badge; a row that merely came back with a fresher stamp does
            // not, and never inflates the count.
            val oldLines = saleDao.linesForSale(local.id)
            val changed = saleSignature(merged, newLines) != saleSignature(local, oldLines)
            val next = if (changed) {
                merged.copy(editedAt = merged.updatedAt, editCount = local.editCount + 1)
            } else merged
            saleDao.replaceSaleWithLines(next, newLines)
            applied++
        }
        config.setCursor("sales", rows.maxOf { it.cursorStamp() })
        return applied
    }

    /**
     * Collapse local sales that are two copies of ONE receipt — the locally-created row
     * (id = UUID, owns the lines/tenders) plus the twin pulled back down under its ref.
     * The UUID row survives; only a row whose id IS its own receiptNo (i.e. cloud-shaped)
     * is ever removed, and only when its total matches the survivor's to the cent, so two
     * unrelated rows can never be merged. The duplicate's orphaned lines and tenders go
     * with it. Hard-delete, local only — never tombstoned, never synced (same convention
     * as [healDuplicateItems]) since the cloud row is the legitimate single copy.
     * Idempotent: a no-op once each receipt has one row.
     */
    private suspend fun healDuplicateSales(bid: String) {
        val groups = saleDao.salesWithReceiptOnce(bid).groupBy { it.receiptNo!!.trim() }
        for ((ref, group) in groups) {
            if (group.size < 2) continue
            // Survivor = a locally-created row (id != ref); newest wins if several.
            val survivor = group.filter { it.id != ref }.maxByOrNull { it.updatedAt } ?: continue
            for (dup in group) {
                if (dup.id != ref) continue                       // never drop a local row
                if (abs(dup.total - survivor.total) > 0.005) continue  // same money only
                saleDao.hardDeleteLines(dup.id)
                saleDao.hardDeletePayments(dup.id)
                saleDao.hardDeleteSale(dup.id)
            }
        }
    }

    companion object {
        /** Rows per pull. A single till changes far fewer than this between syncs. */
        private const val PAGE = 1000

        /** Skip the post-push read-back above this batch size (URL length / cost). */
        private const val VERIFY_MAX = 200
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
