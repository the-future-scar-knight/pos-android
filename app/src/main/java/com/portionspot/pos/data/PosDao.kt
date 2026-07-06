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

    @Query("SELECT * FROM items WHERE businessId = :businessId AND barcode = :barcode AND deleted = 0 LIMIT 1")
    suspend fun getByBarcode(businessId: String, barcode: String): Item?

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

    // ---- sync ----
    @Query("SELECT * FROM items WHERE pendingSync = 1")
    suspend fun pending(): List<Item>

    @Query("UPDATE items SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
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

    /** Hard-delete a sale + its lines + tenders (used when resuming a parked sale). */
    @Query("DELETE FROM sales WHERE id = :saleId")
    suspend fun hardDeleteSale(saleId: String)

    @Query("DELETE FROM sale_items WHERE saleId = :saleId")
    suspend fun hardDeleteLines(saleId: String)

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

    @Query("UPDATE sales SET synced = 1 WHERE id = :id")
    suspend fun markSaleSynced(id: String)

    @Upsert
    suspend fun upsertSale(sale: SaleEntity)

    @Upsert
    suspend fun upsertLines(lines: List<SaleLine>)

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

    @Query("SELECT * FROM refunds WHERE saleId = :saleId AND deleted = 0")
    suspend fun forSaleOnce(saleId: String): List<Refund>

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

    @Upsert
    suspend fun upsert(notification: AppNotification)

    @Query("UPDATE notifications SET readAt = :at WHERE id = :id")
    suspend fun markRead(id: String, at: Long)

    @Query("UPDATE notifications SET readAt = :at WHERE businessId = :businessId AND readAt IS NULL AND deleted = 0")
    suspend fun markAllRead(businessId: String, at: Long)

    /** Tombstone rows whose condition has cleared (resolved low stock, settled refund). */
    @Query("UPDATE notifications SET deleted = 1 WHERE id IN (:ids)")
    suspend fun tombstone(ids: List<String>)

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

    @Query("DELETE FROM audit_log WHERE businessId = :businessId")
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
    @Query(
        "SELECT * FROM expenses WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY date DESC, createdAt DESC"
    )
    fun observeForBusiness(businessId: String): Flow<List<Expense>>

    @Upsert
    suspend fun upsert(expense: Expense)

    /** Soft-delete (tombstone) so the row is hidden but recoverable. */
    @Query("UPDATE expenses SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
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

    /** Soft-delete (tombstone) so the row is hidden but recoverable. */
    @Query("UPDATE suppliers SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
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

    /** Soft-delete the header (lines are left in place, hidden with the parent). */
    @Query("UPDATE purchase_orders SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}
