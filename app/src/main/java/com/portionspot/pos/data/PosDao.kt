package com.portionspot.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessDao {
    @Query("SELECT * FROM businesses WHERE deleted = 0 LIMIT 1")
    fun observe(): Flow<Business?>

    @Query("SELECT * FROM businesses WHERE deleted = 0 LIMIT 1")
    suspend fun getOnce(): Business?

    @Query("SELECT * FROM businesses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Business?

    @Upsert
    suspend fun upsert(business: Business)

    // ---- sync ----
    @Query("SELECT * FROM businesses WHERE pendingSync = 1")
    suspend fun pending(): List<Business>

    @Query("UPDATE businesses SET pendingSync = 0 WHERE id = :id")
    suspend fun markSynced(id: String)
}

@Dao
interface ItemDao {
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 AND isActive = 1 " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeForBusiness(businessId: String): Flow<List<Item>>

    @Query("SELECT COUNT(*) FROM items WHERE businessId = :businessId AND deleted = 0")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Item?

    /** All non-deleted items for a business — one-shot, for the sku↔id sync bridge. */
    @Query("SELECT * FROM items WHERE businessId = :businessId AND deleted = 0")
    suspend fun allForBusinessOnce(businessId: String): List<Item>

    @Query("SELECT * FROM items WHERE businessId = :businessId AND barcode = :barcode AND deleted = 0 LIMIT 1")
    suspend fun getByBarcode(businessId: String, barcode: String): Item?

    /** Existing catalogue row that already owns this SKU (case/space-folded), if any.
     *  Used to write onto the canonical row instead of inserting a duplicate. */
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 " +
            "AND sku IS NOT NULL AND TRIM(LOWER(sku)) = TRIM(LOWER(:sku)) LIMIT 1"
    )
    suspend fun getBySku(businessId: String, sku: String): Item?

    @Upsert
    suspend fun upsert(item: Item)

    @Upsert
    suspend fun upsertAll(items: List<Item>)

    /**
     * Items at or below their reorder level (and tracking stock). Low-stock card.
     *
     * ★ The on-hand column depends on the product type, and reading the wrong one is the
     * same fault that had [NotificationEngine] announcing every full drum of oil as out of
     * stock: a measured product (kg/L/m) keeps its real quantity in `stockMeasured` and
     * deliberately leaves `stockQty` at 0, so a bare `stockQty <= reorderLevel` matched
     * every measured item unconditionally. This mirrors the Kotlin [onHand] accessor,
     * which a query cannot call.
     *
     * `reorderLevel > 0` matters too: without it an item that has simply RUN OUT (0 on
     * hand, no level ever set) matched `0 <= 0` and read as "low", which is a different
     * condition with a different fix. No reorder level set means the owner has not said
     * what low means for this product, so it is not low — it is only ever out.
     */
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 AND isActive = 1 " +
            "AND trackStock = 1 AND reorderLevel > 0 " +
            "AND (CASE WHEN productType = 'measured' THEN stockMeasured ELSE stockQty END) " +
            "    <= reorderLevel " +
            "ORDER BY (CASE WHEN productType = 'measured' THEN stockMeasured ELSE stockQty END) ASC"
    )
    fun observeLowStock(businessId: String): Flow<List<Item>>

    /** Tracked, active items — one-shot for the notification engine (low/out of stock). */
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 AND isActive = 1 AND trackStock = 1"
    )
    suspend fun trackedOnce(businessId: String): List<Item>

    /** Danger zone: zero out every item's on-hand for a business.
     *  BOTH columns — a `measure` product keeps its quantity in `stockMeasured`, and
     *  leaving that behind meant "reset all stock" emptied everything except the very
     *  products sold by weight. [PosRepository.resetAllStock] logs the ledger entries
     *  that make this survive a sync; on its own this statement is undone by the next
     *  recompute. */
    @Query(
        "UPDATE items SET stockQty = 0, stockMeasured = 0, updatedAt = :at, pendingSync = 1 " +
            "WHERE businessId = :businessId"
    )
    suspend fun resetAllStock(businessId: String, at: Long)

    /** Hard-delete one item (used to merge a duplicate catalogue row; never synced). */
    @Query("DELETE FROM items WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** Soft-delete (tombstone) one item — used to drop a never-arrived pending product
     *  when its purchase order is cancelled. */
    @Query("UPDATE items SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync ----
    @Query("SELECT * FROM items WHERE pendingSync = 1")
    suspend fun pending(): List<Item>

    @Query("UPDATE items SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Danger zone: delete every item for a business (device reset before a fresh pull). */
    @Query("DELETE FROM items WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

/**
 * Item key/value tags — in this shop, the cars each part fits.
 *
 * Two-way now: the owner adds and removes fitments on the till, so this DAO carries the
 * `pending`/`markSynced` pair every other syncing DAO has. A removal is a TOMBSTONE
 * ([softDelete]) and never a row disappearing — a hard delete is invisible to the other
 * phones, which would keep offering a part for a car the shop has decided it does not fit.
 */
@Dao
interface ItemAttributeDao {
    /** Every live tag for the business. Small enough to hold in memory (the live shop has
     *  671 rows across 105 items) and the search needs all of them at once. */
    @Query("SELECT * FROM item_attributes WHERE businessId = :businessId AND deleted = 0")
    fun observeForBusiness(businessId: String): Flow<List<ItemAttribute>>

    @Query(
        "SELECT * FROM item_attributes WHERE itemId = :itemId AND deleted = 0 " +
            "ORDER BY key COLLATE NOCASE ASC, value COLLATE NOCASE ASC"
    )
    fun observeForItem(itemId: String): Flow<List<ItemAttribute>>

    @Query("SELECT * FROM item_attributes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ItemAttribute?

    @Upsert
    suspend fun upsert(row: ItemAttribute)

    @Upsert
    suspend fun upsertAll(rows: List<ItemAttribute>)

    // ---- sync ----
    @Query("SELECT * FROM item_attributes WHERE pendingSync = 1")
    suspend fun pending(): List<ItemAttribute>

    @Query("UPDATE item_attributes SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * Hard-delete one tag, and ONLY as part of re-keying a row that has never been uploaded
     * (see `PosSyncEngine.rekeyPendingAttributes`). A user-facing removal must tombstone
     * instead, or the other phones never hear about it.
     */
    @Query("DELETE FROM item_attributes WHERE id = :id")
    suspend fun hardDelete(id: String)

    /** Danger zone: drop every tag for a business (device reset before a fresh pull). */
    @Query("DELETE FROM item_attributes WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface SaleDao {
    @Insert
    suspend fun insertSale(sale: SaleEntity)

    @Insert
    suspend fun insertLines(lines: List<SaleLine>)

    /**
     * Real RECEIPTS, newest first — the Receipts list and the dashboard's "Recent sales".
     *
     * ★ THE STATUS FILTER IS A SAFETY GATE, NOT A TIDY-UP. This query used to return every
     * live row in `sales`, and `sales` is not a table of sales: a QUOTE and a PARKED cart
     * are stored there too, under their own [SaleEntity.status]. So a quote — a piece of
     * paper priced for a customer who never paid and never took the goods — rendered in
     * the Receipts list beside real takings, with reprint, share, and for anyone holding
     * PROCESS_REFUNDS a Refund button. Refunding it would put stock back on a shelf it
     * never left and book a real `refund_owed` debt against a document that was never a
     * sale. Quotes have their own tab ([observeQuotes]) and parked carts their own count
     * ([observeParkedCount]); neither belongs here.
     *
     * WHAT STAYS: `completed` and `refunded`. A refunded sale IS a receipt — the money
     * moved, the goods moved, and the return is recorded against it in `refunds` — so it
     * must stay visible and reprintable, with its refunded badge.
     *
     * WHAT GOES, DELIBERATELY: `void`. A voided sale has been reversed on purpose; its
     * goods and money have already been put back by whatever voided it. Showing it as a
     * receipt would offer a second reversal of the same transaction, which is the exact
     * hole this filter closes. It is not lost — the audit log holds the void.
     *
     * Callers that legitimately want a narrower or wider set must ask their own question:
     * the Z-Report re-filters this flow to `completed` for its cash-up, which still holds.
     */
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status IN ('completed', 'refunded') ORDER BY soldAt DESC LIMIT :limit"
    )
    fun observeRecent(businessId: String, limit: Int = 100): Flow<List<SaleEntity>>

    /** Completed sales for one customer, newest first — powers the Purchases tab of
     *  the customer detail dialog. */
    @Query(
        "SELECT * FROM sales WHERE customerId = :customerId AND deleted = 0 " +
            "AND status = 'completed' ORDER BY soldAt DESC"
    )
    fun observeSalesForCustomer(customerId: String): Flow<List<SaleEntity>>

    /** Completed sales that carried a whole-sale discount, newest first — the admin's
     *  "Discounts given" review list (§Job 2). Capped so a busy till doesn't stream its
     *  whole history. Line-level discounts fold into [SaleEntity.discountTotal] on the
     *  same row, so this is the single source for "what did we knock off, and who by". */
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'completed' AND discountTotal > 0 ORDER BY soldAt DESC LIMIT :limit"
    )
    fun observeDiscountedSales(businessId: String, limit: Int = 200): Flow<List<SaleEntity>>

    /** (id, receiptNo) for every live sale — cheap lookup to label ledger rows with the
     *  originating transaction's reference. */
    @Query("SELECT id, receiptNo FROM sales WHERE businessId = :businessId AND deleted = 0")
    fun observeSaleRefs(businessId: String): Flow<List<SaleRef>>

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId AND deleted = 0")
    suspend fun linesForSale(saleId: String): List<SaleLine>

    /**
     * Net cash every completed sale should have put in the drawer — cash tenders less the
     * change actually handed back — whichever till rang it up.
     *
     * The arithmetic is [PosRepository.checkout]'s, in SQL: `changeDue` is the amount
     * ACTUALLY handed over (not the amount owed), and only `cash` tenders open a drawer.
     * The LEFT JOIN matters — a sale settled entirely on credit has no tender rows at all,
     * and an INNER JOIN would drop it rather than reporting the zero.
     *
     * Whole history, unfiltered: this is the backfill as well as the ongoing reconciliation,
     * and rows that already agree are discarded by [planCashMirror] rather than by the query.
     */
    @Query(
        "SELECT s.id AS refId, " +
            "COALESCE(SUM(CASE WHEN p.method = 'cash' THEN p.amount ELSE 0 END), 0) - " +
            "COALESCE(s.changeDue, 0) AS total, " +
            "s.soldAt AS at, s.receiptNo AS label, " +
            "s.createdBy AS createdBy, s.createdByName AS createdByName " +
            "FROM sales s LEFT JOIN sale_payments p ON p.saleId = s.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND s.status = 'completed' " +
            "GROUP BY s.id"
    )
    suspend fun expectedCashBySale(businessId: String): List<ExpectedCashRow>

    /**
     * The window's RECEIPTS, for the Z-report cash-up (see `ExpectedDrawer.kt`).
     *
     * ★ NO `LIMIT`. The figure this feeds is an expected cash drawer, and the bug it
     * replaces was exactly a cap — the dashboard's `observeRecent(100)` — silently
     * truncating a busy day and turning the 101st receipt onward into an unexplained
     * shortage. Bounded by TIME and by nothing else.
     *
     * ★ [statuses] is passed in rather than written here so the caller can hand over
     * [RECEIPT_STATUSES] itself. `status = 'completed'` — the filter this replaces —
     * DROPS a sale that has since been partly refunded, whose cash is still in the drawer.
     *
     * `changeDue` is the change ACTUALLY handed over (see [expectedCashBySale] and
     * [netCashForSale]); COALESCE because it is null on every sale that gave none.
     */
    @Query(
        "SELECT s.id AS saleId, s.status AS status, s.total AS total, " +
            "COALESCE(s.changeDue, 0) AS changeGiven FROM sales s " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND s.status IN (:statuses) " +
            "AND s.soldAt >= :from AND s.soldAt < :to"
    )
    suspend fun drawerReceipts(
        businessId: String,
        from: Long,
        to: Long,
        statuses: Collection<String>,
    ): List<DrawerReceipt>

    // ---- parked / held sales (status = 'parked') ----
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'parked' ORDER BY soldAt DESC"
    )
    fun observeParked(businessId: String): Flow<List<SaleEntity>>

    @Query("SELECT COUNT(*) FROM sales WHERE businessId = :businessId AND deleted = 0 AND status = 'parked'")
    fun observeParkedCount(businessId: String): Flow<Int>

    // ---- quotes (status = 'quote') ----
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'quote' ORDER BY soldAt DESC"
    )
    fun observeQuotes(businessId: String): Flow<List<SaleEntity>>

    /** Hard-delete a sale + its lines + tenders (used when resuming a parked sale). */
    @Query("DELETE FROM sales WHERE id = :saleId")
    suspend fun hardDeleteSale(saleId: String)

    @Query("DELETE FROM sale_items WHERE saleId = :saleId")
    suspend fun hardDeleteLines(saleId: String)

    /** Repoint sale lines from a merged-away duplicate item onto the survivor, so
     *  historical sales still join to a live catalogue row after a de-dup. */
    @Query("UPDATE sale_items SET itemId = :survivor WHERE itemId = :dup")
    suspend fun repointLineItem(dup: String, survivor: String)

    @Query("DELETE FROM sale_payments WHERE saleId = :saleId")
    suspend fun hardDeletePayments(saleId: String)

    // ★ THERE IS DELIBERATELY NO "takings since" QUERY HERE.
    //
    // `SUM(total)` over completed sales is the BILLED value — it ignores `amountPaid`, so
    // a sale handed over entirely on account counts in full the day it is rung up. By the
    // owner's rule unpaid credit is not revenue; it counts on the day the money is
    // COLLECTED. That query fed the Receipts hero and had it reporting $100 for a sale the
    // drawer never saw, while the Dashboard correctly reported $0.
    //
    // Money collected is [CashBasis]'s question, not SQL's: it has to match repayments
    // FIFO back to the credit lots they settle, which no single aggregate can express.
    // Ask [PosRepository.cashBasisSalesFlow] + [PosRepository.creditLedgerFlow] instead —
    // one source, so no two screens can answer "what came in today" differently.

    @Query(
        "SELECT COUNT(*) FROM sales " +
            "WHERE businessId = :businessId AND deleted = 0 AND status = 'completed' " +
            "AND soldAt >= :since"
    )
    fun observeCountSince(businessId: String, since: Long): Flow<Int>

    // ---- reports (aggregates over a date range) ----
    @Query(
        "SELECT COUNT(*) AS count, " +
            "COALESCE(SUM(total), 0) AS gross, " +
            "COALESCE(SUM(taxTotal), 0) AS vat, " +
            "COALESCE(SUM(discountTotal), 0) AS discount, " +
            "COALESCE(SUM(total - taxTotal), 0) AS net " +
            "FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'completed' AND soldAt >= :from AND soldAt < :to"
    )
    fun observeSummary(businessId: String, from: Long, to: Long): Flow<SalesSummary>

    @Query(
        "SELECT paymentMethod AS method, COUNT(*) AS count, " +
            "COALESCE(SUM(total), 0) AS total " +
            "FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'completed' AND soldAt >= :from AND soldAt < :to " +
            "GROUP BY paymentMethod ORDER BY total DESC"
    )
    fun observeMethodBreakdown(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>>

    /**
     * Count of completed sales in the window whose goods have been FULLY returned —
     * the booked refund value (refundTotal, VAT+discount inclusive) reaches the sale
     * total. The report subtracts this from the sale count so a fully-refunded sale
     * stops counting as a live sale (prompt §5). Refunds are windowed on the same
     * period so the count and the "refunds paid" money line tell one consistent story.
     */
    @Query(
        "SELECT COUNT(*) FROM sales s " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 " +
            "AND s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to " +
            "AND s.total > 0 AND (" +
            "SELECT COALESCE(SUM(r.refundTotal), 0) FROM refunds r " +
            "WHERE r.saleId = s.id AND r.deleted = 0 " +
            "AND r.createdAt >= :from AND r.createdAt < :to" +
            ") >= s.total - 0.01"
    )
    fun observeFullyRefundedCount(businessId: String, from: Long, to: Long): Flow<Int>

    // ---- dashboard ----
    /** Best-selling lines in a window (grouped by snapshot name, so ad-hoc lines count too). */
    @Query(
        "SELECT li.name AS name, " +
            "COALESCE(SUM(li.qty), 0) AS qty, " +
            "COALESCE(SUM(li.lineTotal), 0) AS revenue " +
            "FROM sale_items li JOIN sales s ON li.saleId = s.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND li.deleted = 0 " +
            "AND s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to " +
            "GROUP BY li.name ORDER BY revenue DESC LIMIT :limit"
    )
    fun observeTopProducts(businessId: String, from: Long, to: Long, limit: Int = 5): Flow<List<TopProduct>>

    /**
     * Every live COMPLETED sale in the window reduced to the RAW INGREDIENTS of its
     * margin. It decides nothing on its own — [computeSaleMargin] does, and it is the
     * only place that does, so the dashboard, the margin denominator and the cash-basis
     * figures cannot drift apart into three different profits.
     *
     * WHY THE ALLOCATION IS NOT IN SQL. A sale's whole-sale discount has to be shared
     * pro-rata with the costed lines, which needs the sale's own totals alongside a
     * per-line aggregate. Expressing that inline made the query unreadable and, worse,
     * untestable — nothing here can be exercised without a device. Aggregating in SQL
     * (cheap) and allocating in a pure function (unit-tested) keeps both properties.
     *
     * THE THREE AGGREGATES
     *
     *  - `allLinesNet` — Σ `unitPrice*qty − lineDiscount + lineMarkup` over EVERY live
     *    line. No join to `items`: an ad-hoc line with no catalogue row is still goods
     *    the customer was billed for, and leaving it out would hand its share of the
     *    whole-sale discount to the costed lines. Line discounts and markups belong in
     *    here because the take is what was CHARGED — the old SQL summed a bare
     *    `unitPrice*qty`, so a cashier's discount cost the shop nothing in the figures
     *    and a cashier's markup earned it nothing.
     *
     *  - `costedLinesNet` — the same measure over lines that HAVE a cost to work from:
     *    the FROZEN `li.unitCost` captured at checkout, falling back to the live
     *    `i.cost` only for lines that never captured one (rows written before the
     *    column existed, or pulled from the cloud, which carries no cost). Before the
     *    freeze, the query read `i.cost` directly, so editing a product's cost price
     *    silently rewrote the profit of every past sale.
     *
     *  - `costedLinesCost` — Σ `cost × unitsPerLine × qty` over those same lines.
     *    `unitPrice` is the price of ONE LINE-UNIT (a whole box on a box line) while
     *    the cost is per STOCK UNIT, so a box of 4 was once charged one unit of cost
     *    instead of four, overstating profit by `cost * (unitsPerLine - 1) * qty`.
     *    `NULLIF(...,0)` guards a stray 0 box size, which would otherwise zero the cost
     *    and overstate profit all over again. Piece and measured lines carry 1.
     */
    @Query(
        "SELECT s.id AS id, s.soldAt AS soldAt, s.total AS total, s.taxTotal AS taxTotal, " +
            "s.amountPaid AS amountPaid, s.customerId AS customerId, " +
            "COALESCE((SELECT SUM(li.unitPrice * li.qty - li.lineDiscount + li.lineMarkup) " +
            "  FROM sale_items li " +
            "  WHERE li.saleId = s.id AND li.deleted = 0), 0) AS allLinesNet, " +
            "COALESCE((SELECT SUM(li.unitPrice * li.qty - li.lineDiscount + li.lineMarkup) " +
            "  FROM sale_items li JOIN items i ON li.itemId = i.id " +
            "  WHERE li.saleId = s.id AND li.deleted = 0 " +
            "  AND COALESCE(li.unitCost, i.cost) IS NOT NULL), 0) AS costedLinesNet, " +
            "COALESCE((SELECT SUM(COALESCE(li.unitCost, i.cost) " +
            "    * COALESCE(NULLIF(li.unitsPerLine, 0), 1) * li.qty) " +
            "  FROM sale_items li JOIN items i ON li.itemId = i.id " +
            "  WHERE li.saleId = s.id AND li.deleted = 0 " +
            "  AND COALESCE(li.unitCost, i.cost) IS NOT NULL), 0) AS costedLinesCost " +
            "FROM sales s WHERE s.businessId = :businessId AND s.deleted = 0 " +
            "AND s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to"
    )
    fun observeSaleMargins(businessId: String, from: Long, to: Long): Flow<List<SaleMarginRow>>

    /**
     * [observeSaleMargins] with no date window — raw input to [CashBasis] (§5).
     *
     * ★ Unwindowed on purpose: a repayment made TODAY can settle a sale from last year,
     * so the collected revenue of any window depends on sales outside it. The FIFO
     * attribution that repayments need cannot be expressed in SQL, so the aggregation
     * stops here and [CashBasis] decides what counts as revenue and when.
     *
     * ★ Filtered on [RECEIPT_STATUSES], NOT on `status = 'completed'`, and that has to
     * stay in step with [SaleDao.drawerReceipts]. The two filters answer the same
     * question about the same row — "was this a real sale" — and when they disagreed,
     * a sale carrying `refunded` had its CASH counted toward the expected drawer while
     * its REVENUE AND PROFIT vanished from the books entirely. The drawer would balance
     * and the day would show takings that were nowhere in the sales figures.
     *
     * Nothing writes `refunded` onto a sale today — [PosRepository.createRefund] is a
     * linked reversal and never edits the sale — so this is a guard against legacy rows
     * and against anything that syncs down from the shared cloud carrying it. The web
     * client's `cashBasisSales` input is filtered the same way, deliberately.
     */
    @Query(
        "SELECT s.id AS id, s.soldAt AS soldAt, s.total AS total, s.taxTotal AS taxTotal, " +
            "s.amountPaid AS amountPaid, s.customerId AS customerId, " +
            "COALESCE((SELECT SUM(li.unitPrice * li.qty - li.lineDiscount + li.lineMarkup) " +
            "  FROM sale_items li " +
            "  WHERE li.saleId = s.id AND li.deleted = 0), 0) AS allLinesNet, " +
            "COALESCE((SELECT SUM(li.unitPrice * li.qty - li.lineDiscount + li.lineMarkup) " +
            "  FROM sale_items li JOIN items i ON li.itemId = i.id " +
            "  WHERE li.saleId = s.id AND li.deleted = 0 " +
            "  AND COALESCE(li.unitCost, i.cost) IS NOT NULL), 0) AS costedLinesNet, " +
            "COALESCE((SELECT SUM(COALESCE(li.unitCost, i.cost) " +
            "    * COALESCE(NULLIF(li.unitsPerLine, 0), 1) * li.qty) " +
            "  FROM sale_items li JOIN items i ON li.itemId = i.id " +
            "  WHERE li.saleId = s.id AND li.deleted = 0 " +
            "  AND COALESCE(li.unitCost, i.cost) IS NOT NULL), 0) AS costedLinesCost " +
            "FROM sales s WHERE s.businessId = :businessId AND s.deleted = 0 " +
            "AND s.status IN (:statuses)"
    )
    fun observeAllSaleMargins(businessId: String, statuses: Set<String>): Flow<List<SaleMarginRow>>

    /** Bare (timestamp,total) rows since [from], bucketed in-app into the 7-day chart. */
    @Query(
        "SELECT soldAt AS soldAt, total AS total FROM sales " +
            "WHERE businessId = :businessId AND deleted = 0 AND status = 'completed' " +
            "AND soldAt >= :from"
    )
    fun observeStampsSince(businessId: String, from: Long): Flow<List<SaleStamp>>

    // ---- admin: end-of-day / shift summary + large-sale feed (Phase 7) ----
    @Query(
        "SELECT createdBy AS cashierId, createdByName AS cashierName, COUNT(*) AS count, " +
            "COALESCE(SUM(total), 0) AS total FROM sales " +
            "WHERE businessId = :businessId AND deleted = 0 AND status = 'completed' " +
            "AND soldAt >= :from AND soldAt < :to GROUP BY createdBy ORDER BY total DESC"
    )
    fun observeCashierDay(businessId: String, from: Long, to: Long): Flow<List<CashierDay>>

    /** Change actually handed back in a window — for the cash-in-drawer estimate. */
    @Query(
        "SELECT COALESCE(SUM(changeDue), 0) FROM sales WHERE businessId = :businessId " +
            "AND deleted = 0 AND status = 'completed' AND soldAt >= :from AND soldAt < :to"
    )
    fun observeChangeGiven(businessId: String, from: Long, to: Long): Flow<Double>

    /** Large sales since [since] at/above [min] — one-shot for the notification engine. */
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 AND status = 'completed' " +
            "AND soldAt >= :since AND total >= :min ORDER BY soldAt DESC LIMIT 50"
    )
    suspend fun largeSalesOnce(businessId: String, since: Long, min: Double): List<SaleEntity>

    // ---- sync ----
    @Query("SELECT * FROM sales WHERE synced = 0")
    suspend fun pendingSales(): List<SaleEntity>

    /** All lines (incl. any tombstoned) for pushing a sale up. */
    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    suspend fun allLinesForSale(saleId: String): List<SaleLine>

    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1")
    suspend fun getSaleById(id: String): SaleEntity?

    /**
     * Find a sale by its receipt reference. The cloud keys `sales` rows by REF (not by
     * our local UUID), so a sale this device made comes back down under an id we've
     * never seen — only the receiptNo identifies it. Prefers the locally-created row
     * (id != receiptNo, i.e. the UUID one that owns the lines/payments) if a pulled
     * twin is also present.
     */
    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND receiptNo = :receiptNo " +
            "ORDER BY (id <> :receiptNo) DESC LIMIT 1"
    )
    suspend fun getSaleByReceiptNo(businessId: String, receiptNo: String): SaleEntity?

    /** Every sale carrying a receipt ref (incl. tombstoned) — input to the duplicate heal. */
    @Query("SELECT * FROM sales WHERE businessId = :businessId AND receiptNo IS NOT NULL")
    suspend fun salesWithReceiptOnce(businessId: String): List<SaleEntity>

    @Query("UPDATE sales SET synced = 1 WHERE id = :id")
    suspend fun markSaleSynced(id: String)

    @Upsert
    suspend fun upsertSale(sale: SaleEntity)

    @Upsert
    suspend fun upsertLines(lines: List<SaleLine>)

    /**
     * Apply a pulled EDIT to a sale that already exists locally: rewrite the row (same
     * primary key — the local UUID that the lines, tenders, refunds, credit rows and the
     * audit trail all point at) and swap its goods for [lines], which must already carry
     * `saleId = sale.id`.
     *
     * ONE transaction on purpose. An edit can add, remove or re-quantify lines, so the
     * replacement is delete-then-insert; doing that outside a transaction could leave a
     * receipt showing money with none of its goods — or half of them — if the write is
     * interrupted. Either the whole corrected receipt lands or nothing does.
     */
    @Transaction
    suspend fun replaceSaleWithLines(sale: SaleEntity, lines: List<SaleLine>) {
        hardDeleteLines(sale.id)
        upsertSale(sale)
        if (lines.isNotEmpty()) insertLines(lines)
    }

    // ---- danger zone: wipe all sales data for a business ----
    @Query("DELETE FROM sales WHERE businessId = :businessId")
    suspend fun wipeSales(businessId: String)

    @Query("DELETE FROM sale_items WHERE businessId = :businessId")
    suspend fun wipeSaleLines(businessId: String)
}

@Dao
interface SalePaymentDao {
    @Insert
    suspend fun insertAll(payments: List<SalePayment>)

    /** Apply pulled tenders. REPLACE, not a plain insert: a tender that comes back down
     *  a second time — a cursor reset, a re-pull after a wipe — must land on the row it
     *  already wrote rather than abort the whole batch on a primary-key clash. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(payments: List<SalePayment>)

    @Query("SELECT * FROM sale_payments WHERE saleId = :saleId ORDER BY createdAt ASC")
    suspend fun forSale(saleId: String): List<SalePayment>

    @Query("SELECT * FROM sale_payments WHERE saleId = :saleId ORDER BY createdAt ASC")
    fun observeForSale(saleId: String): Flow<List<SalePayment>>

    /** Reports: money taken per tender over a window, summing actual split amounts. */
    @Query(
        "SELECT p.method AS method, COUNT(*) AS count, COALESCE(SUM(p.amount), 0) AS total " +
            "FROM sale_payments p JOIN sales s ON p.saleId = s.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND s.status = 'completed' " +
            "AND s.soldAt >= :from AND s.soldAt < :to " +
            "GROUP BY p.method ORDER BY total DESC"
    )
    fun observeMethodBreakdown(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>>

    /**
     * Every TENDER behind the window's receipts — the Z-report's cash drawer (see
     * `ExpectedDrawer.kt`).
     *
     * ★ THIS IS THE FIX FOR SPLIT PAYMENTS. [SaleEntity.paymentMethod] is a single code,
     * or the literal `"split"` when a sale has more than one tender, so reading the header
     * contributed **zero cash** for a $50-cash + $30-EcoCash sale. One row per tender is
     * the only shape that can answer "how much of this receipt was cash".
     *
     * The dual-currency trio travels with the row for the cash-up's currency split; the
     * base-currency `amount` is what every sum reads — see [DrawerTender].
     *
     * Uncapped and windowed on the SALE's `soldAt`, so a tender belongs to the trading day
     * the receipt does, and [statuses] is [RECEIPT_STATUSES] from the caller.
     */
    @Query(
        "SELECT p.saleId AS saleId, p.method AS method, p.amount AS amount, " +
            "p.tenderCurrency AS tenderCurrency, p.tenderAmount AS tenderAmount " +
            "FROM sale_payments p JOIN sales s ON p.saleId = s.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND s.status IN (:statuses) " +
            "AND s.soldAt >= :from AND s.soldAt < :to"
    )
    suspend fun drawerTenders(
        businessId: String,
        from: Long,
        to: Long,
        statuses: Collection<String>,
    ): List<DrawerTender>

    @Query("DELETE FROM sale_payments WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface StockMovementDao {
    @Insert
    suspend fun insert(movement: StockMovement)

    // ── sync (the ledger is the authority for stock, so it travels) ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(movements: List<StockMovement>)

    @Query("SELECT * FROM stock_movements WHERE pendingSync = 1 AND deleted = 0")
    suspend fun pending(): List<StockMovement>

    @Query("UPDATE stock_movements SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * On-hand per item, summed from the whole ledger.
     *
     * SUM(delta), not the newest row's `balanceAfter`: two tills selling the same product
     * while offline each write a snapshot computed from the stock THEY could see, so the
     * later-arriving snapshot silently discards the other till's sale. The deltas are
     * independent facts and add up correctly whatever order they arrive in.
     */
    @Query(
        "SELECT itemId AS itemId, SUM(delta) AS onHand FROM stock_movements " +
            "WHERE businessId = :businessId AND deleted = 0 GROUP BY itemId"
    )
    suspend fun onHandByItem(businessId: String): List<ItemOnHand>

    /**
     * Net movement PER ITEM since that item's own stock baseline.
     *
     * The join is what makes this correct: each item is measured from its OWN
     * `stockBaseAt`, because the tills learn the shop's figure for different products at
     * different times. `onHand` here is a DELTA to add to [Item.stockBaseQty], not an
     * on-hand — summing the whole ledger instead is what emptied a shelf of 2 after one
     * sale of 1, since nothing ever writes the opening entry the sum assumes.
     *
     * The tie clause is the web POS's rule, adopted so both clients read the same shelf:
     * at the baseline instant, exclude only the movement that PRODUCED the figure —
     * identified by WHAT IT IS (an absolute type carrying the counted figure as its
     * `balanceAfter`), never by when it happened. A sale rung by another till in that same
     * millisecond is independent work and counts. This is the SQL twin of
     * [countsTowardBaseline]; the two are asserted against the same cases in
     * `StockBaselineTest` and must be changed together.
     */
    @Query(
        "SELECT sm.itemId AS itemId, SUM(sm.delta) AS onHand FROM stock_movements sm " +
            "JOIN items i ON i.id = sm.itemId " +
            "WHERE sm.businessId = :businessId AND sm.deleted = 0 " +
            "AND i.stockBaseAt > 0 AND sm.createdAt >= i.stockBaseAt " +
            "AND NOT (sm.createdAt = i.stockBaseAt " +
            "AND sm.type IN ('adjust', 'restock', 'reset') " +
            "AND abs(sm.balanceAfter - i.stockBaseQty) < 0.005) " +
            "GROUP BY sm.itemId"
    )
    suspend fun deltaSinceBaselineByItem(businessId: String): List<ItemOnHand>

    @Insert
    suspend fun insertAll(movements: List<StockMovement>)

    @Query(
        "SELECT * FROM stock_movements WHERE itemId = :itemId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeForItem(itemId: String, limit: Int = 100): Flow<List<StockMovement>>

    @Query(
        "SELECT * FROM stock_movements WHERE businessId = :businessId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeForBusiness(businessId: String, limit: Int = 200): Flow<List<StockMovement>>

    @Query("DELETE FROM stock_movements WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface CustomerDao {
    @Query(
        "SELECT * FROM customers WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeForBusiness(businessId: String): Flow<List<Customer>>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Customer?

    /** One-shot snapshot (e.g. to match an incoming payment SMS to a saved number). */
    @Query("SELECT * FROM customers WHERE businessId = :businessId AND deleted = 0")
    suspend fun allForBusiness(businessId: String): List<Customer>

    @Upsert
    suspend fun upsert(customer: Customer)

    // ---- sync ----
    @Query("SELECT * FROM customers WHERE pendingSync = 1")
    suspend fun pending(): List<Customer>

    @Query("UPDATE customers SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Danger zone: delete every customer for a business (device reset before a fresh pull). */
    @Query("DELETE FROM customers WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface CreditDao {
    @Insert
    suspend fun insert(txn: CreditTxn)

    /** Customer credit (debt to the shop): credit_owed minus credit_paid. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'credit_owed' THEN amount " +
            "WHEN type = 'credit_paid' THEN -amount ELSE 0 END), 0) " +
            "FROM credit_transactions WHERE customerId = :customerId AND deleted = 0"
    )
    fun observeBalance(customerId: String): Flow<Double>

    /** Credit balance per customer for the whole shop, for the Customers list. */
    @Query(
        "SELECT customerId AS customerId, " +
            "COALESCE(SUM(CASE WHEN type = 'credit_owed' THEN amount " +
            "WHEN type = 'credit_paid' THEN -amount ELSE 0 END), 0) AS balance " +
            "FROM credit_transactions WHERE businessId = :businessId AND deleted = 0 " +
            "GROUP BY customerId"
    )
    fun observeBalances(businessId: String): Flow<List<BalanceRow>>

    /**
     * Money the shop still owes one customer: change AND unpaid refunds. A refund
     * that wasn't fully paid out at once ages here exactly like change owed
     * (prompt §11), so `change_owed`/`refund_owed` add and `change_paid`/`refund_paid`
     * subtract into one "we owe you" balance.
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type IN ('change_owed', 'refund_owed') THEN amount " +
            "WHEN type IN ('change_paid', 'refund_paid') THEN -amount ELSE 0 END), 0) " +
            "FROM credit_transactions WHERE customerId = :customerId AND deleted = 0"
    )
    fun observeChangeBalance(customerId: String): Flow<Double>

    /** One-shot DEBT balance (credit_owed − credit_paid). Used to split an overpayment:
     *  a repayment bigger than the debt settles it and books the rest as change owed. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type = 'credit_owed' THEN amount " +
            "WHEN type = 'credit_paid' THEN -amount ELSE 0 END), 0) " +
            "FROM credit_transactions WHERE customerId = :customerId AND deleted = 0"
    )
    suspend fun balanceOnce(customerId: String): Double

    /** One-shot WE-OWE balance (change/refund owed − paid). Used to split an over-payout:
     *  paying out more than we owe settles it and books the excess as customer debt. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type IN ('change_owed', 'refund_owed') THEN amount " +
            "WHEN type IN ('change_paid', 'refund_paid') THEN -amount ELSE 0 END), 0) " +
            "FROM credit_transactions WHERE customerId = :customerId AND deleted = 0"
    )
    suspend fun changeBalanceOnce(customerId: String): Double

    /** Shop-wide money owed BACK to customers (change + unpaid refunds), net of payouts.
     *  Powers the "You owe customers" summary on the Change & Credit screen. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN type IN ('change_owed', 'refund_owed') THEN amount " +
            "WHEN type IN ('change_paid', 'refund_paid') THEN -amount ELSE 0 END), 0) " +
            "FROM credit_transactions WHERE businessId = :businessId AND deleted = 0"
    )
    fun observeTotalChangeOwed(businessId: String): Flow<Double>

    @Query("DELETE FROM credit_transactions WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)

    @Query(
        "SELECT * FROM credit_transactions WHERE customerId = :customerId AND deleted = 0 " +
            "ORDER BY createdAt DESC"
    )
    fun observeForCustomer(customerId: String): Flow<List<CreditTxn>>

    /** Whole-shop credit ledger (newest first) — powers the Change & Credit screen. */
    @Query(
        "SELECT * FROM credit_transactions WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt DESC"
    )
    fun observeForBusiness(businessId: String): Flow<List<CreditTxn>>

    /** Chronological one-shot of the whole ledger — FIFO debt-aging computation. */
    @Query(
        "SELECT * FROM credit_transactions WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    suspend fun allForBusinessOnce(businessId: String): List<CreditTxn>

    // ---- sync ----
    @Query("SELECT * FROM credit_transactions WHERE pendingSync = 1")
    suspend fun pending(): List<CreditTxn>

    @Query("UPDATE credit_transactions SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM credit_transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CreditTxn?

    @Upsert
    suspend fun upsert(txn: CreditTxn)
}

@Dao
interface RefundDao {
    @Insert
    suspend fun insert(refund: Refund)

    @Insert
    suspend fun insertLines(lines: List<RefundLine>)

    @Insert
    suspend fun insertPayment(payment: RefundPayment)

    // ── pull (REPLACE, so a re-pull lands on the row it already wrote instead of
    //    aborting the batch on a primary-key clash) ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(lines: List<RefundLine>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPayments(payments: List<RefundPayment>)

    @Upsert
    suspend fun upsert(refund: Refund)

    /** Whole-shop refund history (newest first), each with its returned lines. */
    @Transaction
    @Query(
        "SELECT * FROM refunds WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt DESC"
    )
    fun observeWithLines(businessId: String): Flow<List<RefundWithLines>>

    /** Refunds already made against a given sale (to cap over-refunding in the UI). */
    @Query("SELECT * FROM refunds WHERE saleId = :saleId AND deleted = 0 ORDER BY createdAt DESC")
    fun observeForSale(saleId: String): Flow<List<Refund>>

    /**
     * Refunded value grouped by original sale — drives the Receipts "refunded" badge
     * (full vs partial) without touching the sale row, so no money aggregate shifts.
     */
    @Query(
        "SELECT saleId AS saleId, COALESCE(SUM(refundTotal), 0) AS refunded " +
            "FROM refunds WHERE businessId = :businessId AND deleted = 0 GROUP BY saleId"
    )
    fun observeRefundedBySale(businessId: String): Flow<List<SaleRefundSum>>

    /**
     * Every LIVE refund reduced to the three things cash-basis recognition needs (§5) —
     * which sale it reverses, when, and how much of it. Raw input to [CashBasis], which
     * turns `refundTotal / sale.total` into the share of the sale to un-recognise.
     *
     * ★ UNWINDOWED, exactly like [SaleDao.observeAllSaleMargins] and for the same reason:
     * a refund written today reverses a sale from last month, so the figures of ANY window
     * depend on refunds outside it. Windowing here would hide the reversal from every
     * period except the one the refund happens to fall in.
     *
     * `deleted = 0` is what makes an admin void converge: [PosRepository.voidRefund]
     * tombstones the refund, the id drops out of this result, and the reversal stops
     * existing rather than having to be reversed a second time.
     */
    @Query(
        "SELECT id AS id, saleId AS saleId, createdAt AS at, refundTotal AS refundTotal " +
            "FROM refunds WHERE businessId = :businessId AND deleted = 0"
    )
    fun observeCashBasisRefunds(businessId: String): Flow<List<CashBasisRefundRow>>

    @Query("SELECT * FROM refunds WHERE saleId = :saleId AND deleted = 0")
    suspend fun forSaleOnce(saleId: String): List<Refund>

    /**
     * Cash every live refund should have taken OUT of the drawer, as a NEGATIVE figure —
     * the sign the local payout rows are written with, so the two are directly comparable.
     *
     * Voided refunds are excluded by `deleted = 0` rather than reported as zero, and that
     * is what makes the void converge: the id drops out of this result, [planCashMirror]
     * sees cash held against an id it no longer recognises, and reverses it — which is
     * exactly what the phone that did the voiding wrote for itself.
     */
    @Query(
        "SELECT r.id AS refId, " +
            "-COALESCE(SUM(CASE WHEN p.method = 'cash' THEN p.amount ELSE 0 END), 0) AS total, " +
            "r.createdAt AS at, r.saleReceiptNo AS label, " +
            "r.createdBy AS createdBy, r.createdByName AS createdByName " +
            "FROM refunds r LEFT JOIN refund_payments p ON p.refundId = r.id " +
            "WHERE r.businessId = :businessId AND r.deleted = 0 " +
            "GROUP BY r.id"
    )
    suspend fun expectedCashByRefund(businessId: String): List<ExpectedCashRow>

    /**
     * Refund PAYOUTS made in the window — money handed back over the counter, which the
     * old Z-report never subtracted at all (see `ExpectedDrawer.kt`).
     *
     * ★ WINDOWED ON THE PAYOUT'S OWN `createdAt`, NOT THE REFUND'S. A refund can be owed
     * and settled over days ([PosRepository.recordRefundPayout]); the drawer empties on the
     * day the notes leave it, and dating an instalment by its parent refund would take
     * today's cash off yesterday's count.
     *
     * The method comes down with the row instead of being filtered in SQL so a non-cash
     * reversal — which never opened the drawer — can be shown and proven not to move the
     * expected figure. Voided refunds drop out through `r.deleted = 0`, the same way they
     * do for the cash mirror.
     */
    @Query(
        "SELECT p.method AS method, p.amount AS amount, " +
            "p.tenderCurrency AS tenderCurrency, p.tenderAmount AS tenderAmount " +
            "FROM refund_payments p JOIN refunds r ON p.refundId = r.id " +
            "WHERE r.businessId = :businessId AND r.deleted = 0 " +
            "AND p.createdAt >= :from AND p.createdAt < :to"
    )
    suspend fun drawerPayouts(businessId: String, from: Long, to: Long): List<DrawerPayout>

    /** Money already refunded against one sale — the gate on editing it in place (B5). */
    @Query("SELECT COALESCE(SUM(refundTotal), 0) FROM refunds WHERE saleId = :saleId AND deleted = 0")
    suspend fun refundedTotalForSaleOnce(saleId: String): Double

    /** Refunds still owing money — one-shot for the notification engine (§8). */
    @Query("SELECT * FROM refunds WHERE businessId = :businessId AND deleted = 0 AND status = 'owed'")
    suspend fun owedOnce(businessId: String): List<Refund>

    /** Tombstone a refund (admin void of a wrongful refund, §8). */
    @Query("UPDATE refunds SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("SELECT * FROM refunds WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Refund?

    @Query("SELECT * FROM refund_items WHERE refundId = :refundId")
    suspend fun linesFor(refundId: String): List<RefundLine>

    @Query("SELECT * FROM refund_payments WHERE refundId = :refundId ORDER BY createdAt ASC")
    suspend fun paymentsFor(refundId: String): List<RefundPayment>

    @Query("SELECT * FROM refund_payments WHERE refundId = :refundId ORDER BY createdAt ASC")
    fun observePaymentsFor(refundId: String): Flow<List<RefundPayment>>

    /** Money actually paid back so far on a refund. Outstanding = refundTotal − this. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM refund_payments WHERE refundId = :refundId")
    suspend fun paidSoFar(refundId: String): Double

    /**
     * Of that, the part handed back in PHYSICAL CASH — the only part that ever left the
     * drawer, so the only part a void has to put back (card/mobile-money reversals never
     * touched cash-on-hand).
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM refund_payments WHERE refundId = :refundId AND method = 'cash'")
    suspend fun cashPaidSoFar(refundId: String): Double

    /**
     * Units of a given sale line already returned across every prior refund — lets
     * the UI stop a cashier refunding more than were sold. Sums restocked and
     * non-restocked returns alike (both reduce what's still refundable).
     */
    @Query(
        "SELECT COALESCE(SUM(ri.qty), 0) FROM refund_items ri " +
            "JOIN refunds r ON ri.refundId = r.id " +
            "WHERE ri.saleLineId = :saleLineId AND r.deleted = 0"
    )
    suspend fun qtyReturnedForLine(saleLineId: String): Double

    /** Reports: total refunded per method over a window (mirrors the sale tender breakdown). */
    @Query(
        "SELECT p.method AS method, COUNT(*) AS count, COALESCE(SUM(p.amount), 0) AS total " +
            "FROM refund_payments p JOIN refunds r ON p.refundId = r.id " +
            "WHERE r.businessId = :businessId AND r.deleted = 0 " +
            "AND p.createdAt >= :from AND p.createdAt < :to " +
            "GROUP BY p.method ORDER BY total DESC"
    )
    fun observeRefundBreakdown(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>>

    /** Total refunded (money paid back) over a window — for net-of-refunds reporting. */
    @Query(
        "SELECT COALESCE(SUM(p.amount), 0) FROM refund_payments p " +
            "JOIN refunds r ON p.refundId = r.id " +
            "WHERE r.businessId = :businessId AND r.deleted = 0 " +
            "AND p.createdAt >= :from AND p.createdAt < :to"
    )
    fun observeRefundedSince(businessId: String, from: Long, to: Long): Flow<Double>

    // ---- sync ----
    @Query("SELECT * FROM refunds WHERE pendingSync = 1")
    suspend fun pending(): List<Refund>

    @Query("UPDATE refunds SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    // ---- danger zone: wipe all refund data for a business ----
    @Query("DELETE FROM refunds WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)

    @Query("DELETE FROM refund_items WHERE businessId = :businessId")
    suspend fun wipeLines(businessId: String)

    @Query("DELETE FROM refund_payments WHERE businessId = :businessId")
    suspend fun wipePayments(businessId: String)
}

@Dao
interface MobileMoneyDao {
    /**
     * Insert a parsed receipt, IGNORING a collision on the (businessId, txnCode)
     * unique index. This is the idempotency guarantee (§6.6): the same SMS parsed
     * twice can never create a second row. Returns the inserted rowId, or -1 when
     * the txn code was already present (so the receiver can skip notifying again).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(receipt: MobileMoneyReceipt): Long

    @Query("SELECT * FROM mobile_money_receipts WHERE businessId = :businessId AND txnCode = :txnCode LIMIT 1")
    suspend fun getByTxn(businessId: String, txnCode: String): MobileMoneyReceipt?

    /** Still awaiting the cashier — one-shot for the notification engine (§8). */
    @Query(
        "SELECT * FROM mobile_money_receipts WHERE businessId = :businessId AND deleted = 0 " +
            "AND status IN ('needs_verification', 'unmatched')"
    )
    suspend fun pendingOnce(businessId: String): List<MobileMoneyReceipt>

    @Query("SELECT * FROM mobile_money_receipts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MobileMoneyReceipt?

    /** One status bucket (needs_verification / unmatched / verified), newest first. */
    @Query(
        "SELECT * FROM mobile_money_receipts WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = :status ORDER BY receivedAt DESC"
    )
    fun observeByStatus(businessId: String, status: String): Flow<List<MobileMoneyReceipt>>

    /** Count still awaiting the cashier (matched-but-unverified + unmatched) → the "More" badge. */
    @Query(
        "SELECT COUNT(*) FROM mobile_money_receipts WHERE businessId = :businessId AND deleted = 0 " +
            "AND status IN ('needs_verification', 'unmatched')"
    )
    fun observePendingCount(businessId: String): Flow<Int>

    @Upsert
    suspend fun upsert(receipt: MobileMoneyReceipt)

    // ---- sync (deferred to the parity phase, but kept ready) ----
    @Query("SELECT * FROM mobile_money_receipts WHERE pendingSync = 1")
    suspend fun pending(): List<MobileMoneyReceipt>

    @Query("UPDATE mobile_money_receipts SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM mobile_money_receipts WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface NotificationDao {
    /** Whole-shop feed (newest event first) for the admin Alerts screen. */
    @Query(
        "SELECT * FROM notifications WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY eventAt DESC"
    )
    fun observeForBusiness(businessId: String): Flow<List<AppNotification>>

    /** Unread count → the admin Alerts tab badge. */
    @Query("SELECT COUNT(*) FROM notifications WHERE businessId = :businessId AND deleted = 0 AND readAt IS NULL")
    fun observeUnreadCount(businessId: String): Flow<Int>

    /** All live rows — the engine reconciles the current state against these. */
    @Query("SELECT * FROM notifications WHERE businessId = :businessId AND deleted = 0")
    suspend fun allActive(businessId: String): List<AppNotification>

    @Query("SELECT * FROM notifications WHERE businessId = :businessId AND dedupeKey = :key LIMIT 1")
    suspend fun getByKey(businessId: String, key: String): AppNotification?

    /**
     * Pull-merge lookup: the cloud row's identity is `(business_id, dedupe_key)`, NOT the
     * id (ids are minted per device), so an incoming row is matched here and updated in
     * place — keeping this device's local `id` and its device-local `pushedAt`.
     * Includes tombstoned rows so a cleared-then-recurring condition reuses one row.
     */
    @Query("SELECT * FROM notifications WHERE businessId = :businessId AND dedupeKey = :dedupeKey LIMIT 1")
    suspend fun getByDedupeKey(businessId: String, dedupeKey: String): AppNotification?

    /** Rows with unsynced local edits — the upload queue. */
    @Query("SELECT * FROM notifications WHERE pendingSync = 1")
    suspend fun pending(): List<AppNotification>

    @Query("UPDATE notifications SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Upsert
    suspend fun upsert(notification: AppNotification)

    /** Read-state IS synced, so marking read re-queues the row for upload. */
    @Query("UPDATE notifications SET readAt = :at, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun markRead(id: String, at: Long)

    @Query(
        "UPDATE notifications SET readAt = :at, updatedAt = :at, pendingSync = 1 " +
            "WHERE businessId = :businessId AND readAt IS NULL AND deleted = 0"
    )
    suspend fun markAllRead(businessId: String, at: Long)

    /** Tombstone rows whose condition has cleared (resolved low stock, settled refund).
     *  Tombstones sync too, so the alert clears on every phone, not just this one. */
    @Query("UPDATE notifications SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id IN (:ids)")
    suspend fun tombstone(ids: List<String>, at: Long)

    /**
     * Cross-device delivery queue (BUG A fix): live, unread rows THIS phone has not yet
     * raised a heads-up for. A row lands here after a PULL brought it down (or the sweep
     * created it without pushing), and the caller filters by audience against the device
     * role before firing. Not scoped to a business id — a device holds exactly one shop's
     * feed — so the background pass can query it without first resolving the id.
     */
    @Query(
        "SELECT * FROM notifications WHERE deleted = 0 AND readAt IS NULL AND pushedAt IS NULL"
    )
    suspend fun unpushed(): List<AppNotification>

    /** Stamp the device-local heads-up marker WITHOUT touching updatedAt/pendingSync:
     *  which phone has buzzed is device-local state and must never dirty the shared row. */
    @Query("UPDATE notifications SET pushedAt = :at WHERE id = :id")
    suspend fun markPushed(id: String, at: Long)

    @Query("DELETE FROM notifications WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entry: AuditEntry)

    @Query(
        "SELECT * FROM audit_log WHERE businessId = :businessId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeForBusiness(businessId: String, limit: Int = 300): Flow<List<AuditEntry>>

    /** The append-only trail for ONE entity (a sale's edit history), newest first. */
    @Query("SELECT * FROM audit_log WHERE entityId = :entityId ORDER BY createdAt DESC")
    fun observeForEntity(entityId: String): Flow<List<AuditEntry>>

    /** Rows still waiting to go up — the upload queue. */
    @Query("SELECT * FROM audit_log WHERE pendingSync = 1")
    suspend fun pending(): List<AuditEntry>

    @Query("UPDATE audit_log SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * Pull guard: the cloud row's `local_id` IS the local `id`, so a hit here means we
     * already hold that entry and the incoming copy must be SKIPPED — an audit row is
     * never overwritten after the fact.
     */
    @Query("SELECT id FROM audit_log WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** Pull insert. IGNORE, not REPLACE — a race can never clobber a local row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<AuditEntry>)

    @Query("DELETE FROM audit_log WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface StaffRequestDao {
    /** Admin queue: pending requests awaiting a decision, oldest first (act on the
     *  longest-waiting cashier first). Drives the "Requests" section of the Alerts screen. */
    @Query(
        "SELECT * FROM staff_requests WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'pending' ORDER BY createdAt ASC"
    )
    fun observePending(businessId: String): Flow<List<StaffRequest>>

    /** Count of pending requests → the Alerts tab badge on the admin side. */
    @Query(
        "SELECT COUNT(*) FROM staff_requests WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'pending'"
    )
    fun observePendingCount(businessId: String): Flow<Int>

    /** A cashier's own recent requests (any status) → the checkout status surface. Capped
     *  so a busy till doesn't stream its whole history into the banner flow. */
    @Query(
        "SELECT * FROM staff_requests WHERE businessId = :businessId AND deleted = 0 " +
            "AND requestedBy = :requestedBy ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeMine(businessId: String, requestedBy: String, limit: Int = 20): Flow<List<StaffRequest>>

    @Query("SELECT * FROM staff_requests WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StaffRequest?

    @Upsert
    suspend fun upsert(request: StaffRequest)

    /**
     * Mark applied WITHOUT re-queuing for sync — this is DEVICE-LOCAL cashier state. The
     * cashier can't UPDATE the cloud row (RLS: admin-only), so dirtying it would fail RLS
     * every cycle as a per-table error; the cloud `applied` mirror is written only by an
     * admin device. See [StaffRequest.applied].
     */
    @Query("UPDATE staff_requests SET applied = 1 WHERE id = :id")
    suspend fun markAppliedLocal(id: String)

    // ---- sync ----
    /** Rows with unsynced local edits — the upload queue (both pending-new and decided). */
    @Query("SELECT * FROM staff_requests WHERE pendingSync = 1")
    suspend fun pending(): List<StaffRequest>

    @Query("UPDATE staff_requests SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM staff_requests WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: Setting)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface ExpenseDao {
    /** History feed: every real (non-template) expense, newest first. */
    @Query(
        "SELECT * FROM expenses WHERE businessId = :businessId AND deleted = 0 " +
            "AND isTemplate = 0 ORDER BY date DESC, createdAt DESC"
    )
    fun observeForBusiness(businessId: String): Flow<List<Expense>>

    /** Awaiting an admin decision (submitted, not yet posted). */
    @Query(
        "SELECT * FROM expenses WHERE businessId = :businessId AND deleted = 0 " +
            "AND status = 'pending' ORDER BY createdAt ASC"
    )
    fun observePending(businessId: String): Flow<List<Expense>>

    /** Active recurring schedules (templates) for the admin to manage. */
    @Query(
        "SELECT * FROM expenses WHERE businessId = :businessId AND deleted = 0 " +
            "AND isTemplate = 1 ORDER BY createdAt DESC"
    )
    fun observeTemplates(businessId: String): Flow<List<Expense>>

    /** Sum of posted (approved, non-template) expense amounts in an epoch window —
     *  the figure that reduces derived net profit on the dashboard. */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE businessId = :businessId " +
            "AND deleted = 0 AND status = 'approved' AND isTemplate = 0 " +
            "AND createdAt BETWEEN :from AND :to"
    )
    fun observePostedTotalBetween(businessId: String, from: Long, to: Long): Flow<Double>

    /** Shop-wide accounts payable: what the shop still owes payees from short-funded,
     *  posted expenses. (No partial-repayment mechanism yet — sum of the portions.) */
    @Query(
        "SELECT COALESCE(SUM(payablePortion), 0) FROM expenses WHERE businessId = :businessId " +
            "AND deleted = 0 AND status = 'approved' AND isTemplate = 0"
    )
    fun observePayablesTotal(businessId: String): Flow<Double>

    /** Shop-wide owner contributions: expenses the owner covered out of pocket. */
    @Query(
        "SELECT COALESCE(SUM(capitalPortion), 0) FROM expenses WHERE businessId = :businessId " +
            "AND deleted = 0 AND status = 'approved' AND isTemplate = 0"
    )
    fun observeOwnerContributions(businessId: String): Flow<Double>

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Expense?

    /** Templates whose next charge is due (drives the auto-post worker). */
    @Query(
        "SELECT * FROM expenses WHERE businessId = :businessId AND deleted = 0 " +
            "AND isTemplate = 1 AND recurrenceActive = 1 AND status = 'approved' " +
            "AND nextRunAt IS NOT NULL AND nextRunAt <= :now"
    )
    suspend fun dueTemplates(businessId: String, now: Long): List<Expense>

    @Upsert
    suspend fun upsert(expense: Expense)

    /** Soft-delete (tombstone) so the row is hidden but recoverable. Re-queues for
     *  sync so the tombstone itself reaches the cloud. */
    @Query("UPDATE expenses SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync ----
    @Query("SELECT * FROM expenses WHERE pendingSync = 1")
    suspend fun pending(): List<Expense>

    @Query("UPDATE expenses SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Dao
interface CashTxnDao {
    /** Running cash-on-hand movements (newest first). */
    @Query(
        "SELECT * FROM cash_txns WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt DESC"
    )
    fun observeForBusiness(businessId: String): Flow<List<CashTxn>>

    /** Movements in ONE location only (newest first) — the till or safe statement. */
    @Query(
        "SELECT * FROM cash_txns WHERE businessId = :businessId AND deleted = 0 " +
            "AND location = :location ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeForLocation(businessId: String, location: String, limit: Int = 100): Flow<List<CashTxn>>

    /**
     * Net of ALL cash movements, both locations — added to the opening float for
     * cash-on-hand. Unchanged by the till/safe split ON PURPOSE: a transfer is written as
     * a matching `transfer_out`/`transfer_in` pair that sums to zero, so this figure (and
     * every existing caller of it) still reads total cash held on the premises.
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId AND deleted = 0")
    fun observeMovementsSum(businessId: String): Flow<Double>

    /** Same net, read once (for a synchronous shortfall check at approval time). */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId AND deleted = 0")
    suspend fun movementsSumOnce(businessId: String): Double

    /**
     * Net movements in ONE location. The TILL balance additionally carries the opening
     * float (the float IS the drawer's starting money); the SAFE starts empty and is
     * filled only by transfers in. Both are computed in the repository so the two
     * balances can never disagree with the combined total.
     */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_txns " +
            "WHERE businessId = :businessId AND deleted = 0 AND location = :location"
    )
    fun observeLocationSum(businessId: String, location: String): Flow<Double>

    /** Same per-location net, read once (funding decisions need it synchronously). */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_txns " +
            "WHERE businessId = :businessId AND deleted = 0 AND location = :location"
    )
    suspend fun locationSumOnce(businessId: String, location: String): Double

    /**
     * Net movements in one location STRICTLY BEFORE [before] — what the drawer held when
     * the window opened.
     *
     * This is what replaced the Z-report's "Opening float" TEXT BOX. Asking a cashier to
     * type the opening float makes the expected drawer a function of what someone
     * remembers, and the number they type is the number the count is measured against —
     * so a typo becomes a permanent shortage with a name attached to it. The ledger
     * already knows: it is the same running balance [PosRepository.tillBalanceOnce] reads,
     * cut off at the window's edge.
     */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_txns " +
            "WHERE businessId = :businessId AND deleted = 0 AND location = :location " +
            "AND createdAt < :before"
    )
    suspend fun locationSumBefore(businessId: String, location: String, before: Long): Double

    /**
     * Everything OTHER than sales and refunds that moved one location in a window, netted
     * per [CashTxn.type] — the third thing the old Z-report ignored (see `ExpectedDrawer.kt`).
     *
     * ★ THE `refType NOT IN ('sale', 'refund')` EXCLUSION IS LOAD-BEARING. Both the till
     * that rang a sale up ([PosRepository.checkout]) and a till that merely pulled it
     * ([planCashMirror]) write a cash row stamped with those refTypes, and the cash-up
     * rebuilds a receipt's contribution from its TENDER rows instead. Counting both would
     * put every sale in the expected drawer twice. Any new `refType` used for a sale- or
     * refund-derived movement has to be added here in the same change — the same coupling
     * `cashMovementCountedElsewhere` already carries.
     *
     * Everything else genuinely moved this drawer and belongs in the count: an expense or
     * purchase paid out of the till, a till↔safe transfer either way, change paid out to a
     * customer later (`refType = 'customer'`), owner money in, an owner drawing, and a
     * day-close true-up. Grouped by type so the cash-up can say WHICH, because "expected
     * is lower than the day's takings" is only a fair thing to tell a cashier if the
     * screen also says where the difference went.
     */
    @Query(
        "SELECT type AS type, COALESCE(SUM(amount), 0) AS amount FROM cash_txns " +
            "WHERE businessId = :businessId AND deleted = 0 AND location = :location " +
            "AND createdAt >= :from AND createdAt < :to " +
            "AND (refType IS NULL OR refType NOT IN ('sale', 'refund')) " +
            "GROUP BY type"
    )
    suspend fun drawerMovements(
        businessId: String,
        location: String,
        from: Long,
        to: Long,
    ): List<DrawerMovement>

    /**
     * Cash already booked against each source row of one kind — the "what the ledger
     * already holds" half of [planCashMirror].
     *
     * SUMMED, not counted. A refund accumulates one payout row per instalment and a void
     * writes a reversing row against the same id, so asking whether a row EXISTS answers
     * the wrong question; only the running total says how much of the event the drawer has
     * actually seen.
     */
    @Query(
        "SELECT refId AS refId, COALESCE(SUM(amount), 0) AS total FROM cash_txns " +
            "WHERE businessId = :businessId AND deleted = 0 AND refType = :refType " +
            "AND refId IS NOT NULL GROUP BY refId"
    )
    suspend fun sumsByRef(businessId: String, refType: String): List<CashRefSum>

    /**
     * CASH SHORT / OVER for a window: the signed sum of close-of-day `variance` rows.
     * Negative = the drawer came up short (a real loss); positive = over (a gain). Kept
     * OUT of gross profit deliberately — it is a separate line so a shortage is visible
     * as a shortage rather than quietly eating margin.
     */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId " +
            "AND deleted = 0 AND type = 'variance' AND createdAt >= :from AND createdAt < :to"
    )
    fun observeVarianceSum(businessId: String, from: Long, to: Long): Flow<Double>

    /**
     * Net of the movements that are EQUITY / OUTSIDE money rather than trading: the owner
     * putting cash in ("capital"), borrowing ("loan") and drawing money out ("drawing").
     * Feeds the "float & capital" slice of the four-part split — money in the drawer that
     * was never profit.
     */
    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId " +
            "AND deleted = 0 AND type IN ('capital', 'loan', 'drawing')"
    )
    fun observeEquityCashSum(businessId: String): Flow<Double>

    /**
     * Cash spent buying stock, all time, as a POSITIVE figure ("purchase" rows are
     * negative). The "stock money" slice of the split is the collected cost of goods sold
     * LESS this — money already ploughed back into the shelves is no longer set aside.
     */
    @Query(
        "SELECT -COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId " +
            "AND deleted = 0 AND type = 'purchase'"
    )
    fun observePurchaseCashSum(businessId: String): Flow<Double>

    @Insert
    suspend fun insert(txn: CashTxn)

    @Insert
    suspend fun insertAll(txns: List<CashTxn>)

    @Query("SELECT * FROM cash_txns WHERE businessId = :businessId AND deleted = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(businessId: String, limit: Int = 100): List<CashTxn>

    // ---- sync ----
    @Query("SELECT * FROM cash_txns WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CashTxn?

    @Upsert
    suspend fun upsert(txn: CashTxn)

    @Query("SELECT * FROM cash_txns WHERE pendingSync = 1")
    suspend fun pending(): List<CashTxn>

    @Query("UPDATE cash_txns SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Dao
interface DayCloseDao {
    @Insert
    suspend fun insert(close: DayClose)

    /**
     * The owner-visible close history, newest first. Deliberately NOT grouped: every
     * close carries [DayClose.closedByName], so a cashier who is short again and again
     * is visible by reading down the list.
     */
    @Query(
        "SELECT * FROM day_closes WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY closedAt DESC LIMIT :limit"
    )
    fun observeForBusiness(businessId: String, limit: Int = 90): Flow<List<DayClose>>

    /** The most recent close — "last closed <when>" on the cash card. */
    @Query(
        "SELECT * FROM day_closes WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY closedAt DESC LIMIT 1"
    )
    fun observeLatest(businessId: String): Flow<DayClose?>

    /**
     * The close already recorded for one trading day, if any.
     *
     * This is the outer interlock on closing a day twice, and it exists because closing is
     * not just a record: it moves the excess takings out of the till and into the safe. A
     * second close moves cash that has already left the drawer. Keyed on [DayClose.dayStart]
     * — the same trading-day key the shift is keyed on — so the guard holds even for a day
     * that was never traded through the till and therefore has no session to check.
     */
    @Query(
        "SELECT * FROM day_closes WHERE businessId = :businessId AND dayStart = :dayStart " +
            "AND deleted = 0 ORDER BY closedAt DESC LIMIT 1"
    )
    suspend fun forDayOnce(businessId: String, dayStart: Long): DayClose?

    /**
     * The same close, observed — so the UI can say a day is already counted BEFORE anyone
     * counts the drawer, rather than after they press a button that silently does nothing.
     *
     * Its own query rather than a filter over [observeForBusiness]: that one is capped at 90
     * rows for the history list, and a day older than the cap would come back as "not
     * closed" and offer to close it a second time.
     */
    @Query(
        "SELECT * FROM day_closes WHERE businessId = :businessId AND dayStart = :dayStart " +
            "AND deleted = 0 ORDER BY closedAt DESC LIMIT 1"
    )
    fun observeForDay(businessId: String, dayStart: Long): Flow<DayClose?>

    // ---- sync-ready (no push/pull wired: the cloud has no `day_closes` table) ----
    @Query("SELECT * FROM day_closes WHERE pendingSync = 1")
    suspend fun pending(): List<DayClose>

    @Query("UPDATE day_closes SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM day_closes WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface OutsideFundDao {
    @Insert
    suspend fun insert(fund: OutsideFund)

    /** The outside-money ledger, newest first (owner injections, loans, drawings). */
    @Query(
        "SELECT * FROM outside_funds WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    fun observeForBusiness(businessId: String, limit: Int = 100): Flow<List<OutsideFund>>

    /**
     * Running totals per kind and direction — the owner's "put in / taken out / net" and
     * the shop's outstanding borrowings. One pass over the ledger, so the four figures
     * can never disagree with each other.
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN kind = 'capital' AND direction = 'in' THEN amount ELSE 0 END), 0) AS capitalIn, " +
            "COALESCE(SUM(CASE WHEN kind = 'capital' AND direction = 'out' THEN amount ELSE 0 END), 0) AS capitalOut, " +
            "COALESCE(SUM(CASE WHEN kind = 'loan' AND direction = 'in' THEN amount ELSE 0 END), 0) AS loanIn, " +
            "COALESCE(SUM(CASE WHEN kind = 'loan' AND direction = 'out' THEN amount ELSE 0 END), 0) AS loanOut " +
            "FROM outside_funds WHERE businessId = :businessId AND deleted = 0"
    )
    fun observeTotals(businessId: String): Flow<OutsideFundTotals>

    // ---- sync-ready (no push/pull wired: the cloud has no `outside_funds` table) ----
    @Query("SELECT * FROM outside_funds WHERE pendingSync = 1")
    suspend fun pending(): List<OutsideFund>

    @Query("UPDATE outside_funds SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM outside_funds WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface SupplierDao {
    @Query(
        "SELECT * FROM suppliers WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeForBusiness(businessId: String): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Supplier?

    @Upsert
    suspend fun upsert(supplier: Supplier)

    /** Soft-delete (tombstone) so the row is hidden but recoverable. Re-queues for sync. */
    @Query("UPDATE suppliers SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync ----
    @Query("SELECT * FROM suppliers WHERE pendingSync = 1")
    suspend fun pending(): List<Supplier>

    @Query("UPDATE suppliers SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Dao
interface PurchaseOrderDao {
    /** Newest-first list of POs with their lines stitched in one read. */
    @Transaction
    @Query(
        "SELECT * FROM purchase_orders WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY createdAt DESC"
    )
    fun observeWithLines(businessId: String): Flow<List<PurchaseOrderWithLines>>

    @Query("SELECT * FROM purchase_orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PurchaseOrder?

    /** Open (placed/partial) POs that carry an ETA — drives the "has it arrived?" sweep. */
    @Query(
        "SELECT * FROM purchase_orders WHERE businessId = :businessId AND deleted = 0 " +
            "AND status IN ('placed', 'partial') AND eta IS NOT NULL"
    )
    suspend fun openWithEtaOnce(businessId: String): List<PurchaseOrder>

    /** Shop-wide accounts payable owed to suppliers (unpaid PO balances). */
    @Query(
        "SELECT COALESCE(SUM(payableRemainder), 0) FROM purchase_orders " +
            "WHERE businessId = :businessId AND deleted = 0 AND status != 'cancelled'"
    )
    fun observeSupplierPayables(businessId: String): Flow<Double>

    @Query("SELECT * FROM purchase_order_items WHERE poId = :poId")
    suspend fun linesForPo(poId: String): List<PurchaseOrderLine>

    @Insert
    suspend fun insert(po: PurchaseOrder)

    @Insert
    suspend fun insertLines(lines: List<PurchaseOrderLine>)

    @Upsert
    suspend fun upsert(po: PurchaseOrder)

    @Upsert
    suspend fun upsertLine(line: PurchaseOrderLine)

    /** Soft-delete the header (lines are left in place, hidden with the parent).
     *  Re-queues for sync so the tombstone reaches the cloud. */
    @Query("UPDATE purchase_orders SET deleted = 1, updatedAt = :at, pendingSync = 1 WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // ---- sync ----
    @Query("SELECT * FROM purchase_order_items WHERE id = :id LIMIT 1")
    suspend fun getLineById(id: String): PurchaseOrderLine?

    @Query("SELECT * FROM purchase_orders WHERE pendingSync = 1")
    suspend fun pending(): List<PurchaseOrder>

    @Query("UPDATE purchase_orders SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM purchase_order_items WHERE pendingSync = 1")
    suspend fun pendingLines(): List<PurchaseOrderLine>

    @Query("UPDATE purchase_order_items SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markLinesSynced(ids: List<String>)
}
