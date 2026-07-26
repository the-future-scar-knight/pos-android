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

    /** Items at or below their reorder level (and tracking stock). Low-stock card. */
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 AND isActive = 1 " +
            "AND trackStock = 1 AND stockQty <= reorderLevel " +
            "ORDER BY stockQty ASC"
    )
    fun observeLowStock(businessId: String): Flow<List<Item>>

    /** Tracked, active items — one-shot for the notification engine (low/out of stock). */
    @Query(
        "SELECT * FROM items WHERE businessId = :businessId AND deleted = 0 AND isActive = 1 AND trackStock = 1"
    )
    suspend fun trackedOnce(businessId: String): List<Item>

    /** Danger zone: zero out every item's on-hand for a business. */
    @Query("UPDATE items SET stockQty = 0, updatedAt = :at, pendingSync = 1 WHERE businessId = :businessId")
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

@Dao
interface SaleDao {
    @Insert
    suspend fun insertSale(sale: SaleEntity)

    @Insert
    suspend fun insertLines(lines: List<SaleLine>)

    @Query(
        "SELECT * FROM sales WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY soldAt DESC LIMIT :limit"
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

    @Query(
        "SELECT COALESCE(SUM(total), 0) FROM sales " +
            "WHERE businessId = :businessId AND deleted = 0 AND status = 'completed' " +
            "AND soldAt >= :since"
    )
    fun observeTakingsSince(businessId: String, since: Long): Flow<Double>

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

    /** Gross profit = SUM((soldPrice - currentCost) * qty), only for items that have a cost. */
    @Query(
        "SELECT COALESCE(SUM((li.unitPrice - i.cost) * li.qty), 0) " +
            "FROM sale_items li JOIN sales s ON li.saleId = s.id " +
            "JOIN items i ON li.itemId = i.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND li.deleted = 0 " +
            "AND s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to " +
            "AND i.cost IS NOT NULL"
    )
    fun observeGrossProfit(businessId: String, from: Long, to: Long): Flow<Double>

    /**
     * Revenue of only those sold lines whose item has a cost — the correct margin
     * denominator when the catalog is partially costed (profit is computed over the
     * same costed lines, so margin = profit / this stays honest).
     */
    @Query(
        "SELECT COALESCE(SUM(li.unitPrice * li.qty), 0) " +
            "FROM sale_items li JOIN sales s ON li.saleId = s.id " +
            "JOIN items i ON li.itemId = i.id " +
            "WHERE s.businessId = :businessId AND s.deleted = 0 AND li.deleted = 0 " +
            "AND s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to " +
            "AND i.cost IS NOT NULL"
    )
    fun observeCostedRevenue(businessId: String, from: Long, to: Long): Flow<Double>

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

    @Query("DELETE FROM sale_payments WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}

@Dao
interface StockMovementDao {
    @Insert
    suspend fun insert(movement: StockMovement)

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

    @Query("SELECT * FROM refunds WHERE saleId = :saleId AND deleted = 0")
    suspend fun forSaleOnce(saleId: String): List<Refund>

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

    /** Net of all cash movements — added to the opening float for cash-on-hand. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId AND deleted = 0")
    fun observeMovementsSum(businessId: String): Flow<Double>

    /** Same net, read once (for a synchronous shortfall check at approval time). */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM cash_txns WHERE businessId = :businessId AND deleted = 0")
    suspend fun movementsSumOnce(businessId: String): Double

    @Insert
    suspend fun insert(txn: CashTxn)

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
