package com.portionspot.pos.data

import androidx.room.withTransaction
import com.portionspot.pos.device.phoneKey
import com.portionspot.pos.notify.NotifSnapshot
import com.portionspot.pos.notify.NotifThresholds
import com.portionspot.pos.notify.NotificationEngine
import com.portionspot.pos.sms.ParsedPayment
import kotlinx.coroutines.flow.Flow

/** Half-a-cent tolerance for money comparisons (guards Double rounding on totals). */
private const val CENT = 0.005

/**
 * The single gateway between the UI and Room. (Supabase sync will be added
 * here in a later milestone — push dirty rows where updatedAt > cursor.)
 */
class PosRepository(private val db: PosDatabase) {

    private val businessDao = db.businessDao()
    private val itemDao = db.itemDao()
    private val saleDao = db.saleDao()
    private val paymentDao = db.salePaymentDao()
    private val movementDao = db.stockMovementDao()
    private val customerDao = db.customerDao()
    private val creditDao = db.creditDao()
    private val settingDao = db.settingDao()
    private val expenseDao = db.expenseDao()
    private val supplierDao = db.supplierDao()
    private val poDao = db.purchaseOrderDao()
    private val refundDao = db.refundDao()
    private val mobileMoneyDao = db.mobileMoneyDao()
    private val notificationDao = db.notificationDao()
    private val auditDao = db.auditDao()

    // ---- Local key/value settings (theme, etc. — never synced) -------------

    suspend fun getSetting(key: String): String? = settingDao.get(key)

    suspend fun putSetting(key: String, value: String) =
        settingDao.put(Setting(key, value))

    val businessFlow: Flow<Business?> = businessDao.observe()

    fun itemsFlow(businessId: String): Flow<List<Item>> =
        itemDao.observeForBusiness(businessId)

    fun recentSalesFlow(businessId: String): Flow<List<SaleEntity>> =
        saleDao.observeRecent(businessId)

    fun takingsSinceFlow(businessId: String, since: Long): Flow<Double> =
        saleDao.observeTakingsSince(businessId, since)

    fun saleCountSinceFlow(businessId: String, since: Long): Flow<Int> =
        saleDao.observeCountSince(businessId, since)

    // ---- reports ----------------------------------------------------------

    fun salesSummaryFlow(businessId: String, from: Long, to: Long): Flow<SalesSummary> =
        saleDao.observeSummary(businessId, from, to)

    fun methodBreakdownFlow(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>> =
        saleDao.observeMethodBreakdown(businessId, from, to)

    // ---- dashboard --------------------------------------------------------

    fun topProductsFlow(businessId: String, from: Long, to: Long, limit: Int = 5): Flow<List<TopProduct>> =
        saleDao.observeTopProducts(businessId, from, to, limit)

    fun grossProfitFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeGrossProfit(businessId, from, to)

    fun stampsSinceFlow(businessId: String, from: Long): Flow<List<SaleStamp>> =
        saleDao.observeStampsSince(businessId, from)

    suspend fun linesForSale(saleId: String): List<SaleLine> =
        saleDao.linesForSale(saleId)

    suspend fun saveBusiness(business: Business) =
        businessDao.upsert(business.copy(updatedAt = now(), pendingSync = true))

    suspend fun saveItem(item: Item) =
        itemDao.upsert(item.copy(updatedAt = now(), pendingSync = true))

    suspend fun itemByBarcode(businessId: String, barcode: String): Item? =
        itemDao.getByBarcode(businessId, barcode.trim())

    // ---- customers & credit ----------------------------------------------

    fun customersFlow(businessId: String): Flow<List<Customer>> =
        customerDao.observeForBusiness(businessId)

    fun balancesFlow(businessId: String): Flow<List<BalanceRow>> =
        creditDao.observeBalances(businessId)

    fun balanceFlow(customerId: String): Flow<Double> =
        creditDao.observeBalance(customerId)

    fun creditHistoryFlow(customerId: String): Flow<List<CreditTxn>> =
        creditDao.observeForCustomer(customerId)

    /** Whole-shop credit ledger for the Change & Credit screen. */
    fun creditLedgerFlow(businessId: String): Flow<List<CreditTxn>> =
        creditDao.observeForBusiness(businessId)

    suspend fun saveCustomer(customer: Customer) =
        customerDao.upsert(customer.copy(updatedAt = now(), pendingSync = true))

    /** One customer by id (e.g. to resolve the owed-refund target from a sale). */
    suspend fun customerById(id: String): Customer? = customerDao.getById(id)

    /** Record a repayment against a customer's account (writes a credit_paid row). */
    suspend fun recordRepayment(
        businessId: String,
        customerId: String,
        amount: Double,
        note: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0) return
        creditDao.insert(
            CreditTxn(
                businessId = businessId,
                customerId = customerId,
                type = "credit_paid",
                amount = amount,
                note = note,
                createdBy = cashierId,
                createdByName = cashierName
            )
        )
    }

    // ---- expenses (local-only; never synced) ------------------------------

    fun expensesFlow(businessId: String): Flow<List<Expense>> =
        expenseDao.observeForBusiness(businessId)

    suspend fun saveExpense(expense: Expense) =
        expenseDao.upsert(expense.copy(updatedAt = now()))

    suspend fun deleteExpense(id: String) = expenseDao.softDelete(id, now())

    // ---- suppliers (local-only; never synced) -----------------------------

    fun suppliersFlow(businessId: String): Flow<List<Supplier>> =
        supplierDao.observeForBusiness(businessId)

    suspend fun saveSupplier(supplier: Supplier) =
        supplierDao.upsert(supplier.copy(updatedAt = now()))

    suspend fun deleteSupplier(id: String) = supplierDao.softDelete(id, now())

    // ---- purchase orders (local-only; never synced) -----------------------

    fun purchaseOrdersFlow(businessId: String): Flow<List<PurchaseOrderWithLines>> =
        poDao.observeWithLines(businessId)

    /**
     * Create a draft PO with its lines in one atomic write. The [lines] arrive
     * without a parent id; we stamp the freshly minted PO id onto each. Returns
     * the new PO id. [ref] is the human code `PO-YYMMDD-NNNN`.
     */
    suspend fun createPurchaseOrder(
        businessId: String,
        supplierId: String?,
        supplierName: String,
        notes: String?,
        lines: List<PurchaseOrderLine>
    ): String {
        val po = PurchaseOrder(
            businessId = businessId,
            ref = genRef("PO"),
            supplierId = supplierId,
            supplierName = supplierName,
            status = "draft",
            notes = notes
        )
        db.withTransaction {
            poDao.insert(po)
            poDao.insertLines(lines.map { it.copy(poId = po.id) })
        }
        return po.id
    }

    /** Draft → sent. Stamps sentAt. */
    suspend fun markPoSent(poId: String) {
        val po = poDao.getById(poId) ?: return
        val stamp = now()
        poDao.upsert(po.copy(status = "sent", sentAt = stamp, updatedAt = stamp))
    }

    /** Any open state → cancelled. */
    suspend fun cancelPo(poId: String) {
        val po = poDao.getById(poId) ?: return
        poDao.upsert(po.copy(status = "cancelled", updatedAt = now()))
    }

    /**
     * Receive a PO: for every line with a received quantity > 0, add the goods to
     * the linked catalog item's stock and record what was received, then close the
     * PO. [enteredByLine] maps a line id to the quantity entered in the receive
     * dialog; when [boxMode] is on, that quantity is read as BOXES and multiplied
     * by the item's pack size to get units. All of it commits atomically so a crash
     * can't restock without closing the PO (or vice-versa).
     */
    suspend fun receivePurchaseOrder(
        poId: String,
        enteredByLine: Map<String, Double>,
        boxMode: Boolean
    ) {
        val po = poDao.getById(poId) ?: return
        val stamp = now()
        db.withTransaction {
            for (line in poDao.linesForPo(poId)) {
                val entered = enteredByLine[line.id] ?: 0.0
                val item = line.itemId?.let { itemDao.getById(it) }
                // Boxes → units using the item's pack size (ad-hoc lines stay 1:1).
                val units =
                    if (boxMode && item != null && item.boxSize > 1) entered * item.boxSize
                    else entered
                if (units > 0.0 && item != null) {
                    itemDao.upsert(
                        item.copy(
                            stockQty = item.stockQty + units,
                            updatedAt = stamp,
                            pendingSync = true
                        )
                    )
                }
                poDao.upsertLine(line.copy(receivedQty = units))
            }
            poDao.upsert(po.copy(status = "received", receivedAt = stamp, updatedAt = stamp))
        }
    }

    /**
     * First-run bootstrap: make sure there's a business row. The catalog starts
     * EMPTY — the operator adds stock by hand or pulls it from the cloud. Only the
     * editable default business name is seeded. Returns the active businessId.
     */
    suspend fun ensureSeeded(): String {
        val existing = businessDao.getOnce()
        val business = existing ?: Business(
            name = "PortionSpot Motors",
            currency = "USD",
            receiptFooter = "Thank you for your business!"
        ).also { businessDao.upsert(it) }
        return business.id
    }

    /**
     * Freeze the in-memory cart into an immutable completed sale + its lines.
     * Returns the saved sale together with its lines so the caller can show/print
     * a receipt without another DB read.
     *
     * VAT is computed at the business level (not per item): when [vatEnabled],
     * the tax is [vatPercent]% of the discounted subtotal (the taxable base), and
     * the printed total is tax-inclusive. When disabled, subtotal == total.
     *
     * [payments] are the tenders actually collected — one entry for a single
     * tender, several for a split (e.g. $50 cash + $30 EcoCash). Each carries its
     * own method, amount and optional reference. The parent sale stores the single
     * method, or "split" when more than one tender is present.
     *
     * Settlement:
     *  - amountPaid = sum of tenders. If it is short of the total and [onCredit]
     *    with a [customer], the shortfall becomes a `credit_owed` ledger row.
     *  - Overpayment is change. By default it is handed back ([SaleEntity.changeDue]);
     *    when [changeAsCredit] and a [customer] is set, it is instead booked as a
     *    `change_owed` row the shop settles later.
     */
    suspend fun checkout(
        businessId: String,
        cart: List<CartLine>,
        payments: List<Tender> = emptyList(),
        discount: Double = 0.0,
        note: String? = null,
        customer: Customer? = null,
        onCredit: Boolean = false,
        changeAsCredit: Boolean = false,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0,
        totalRounding: Double = 0.0,
        cashierId: String? = null,
        cashierName: String? = null
    ): SaleWithLines {
        // Money math (pure + unit-tested in SaleMathTest): discount clamped to the
        // goods value, VAT charged on the DISCOUNTED base, total is tax-inclusive.
        val subtotal = cart.sumOf { it.lineSubtotal }       // pre-tax goods value
        val totals = computeSaleTotals(subtotal, discount, vatEnabled, vatPercent)
        val saleDiscount = totals.discount
        val taxTotal = totals.taxTotal
        // Optional checkout rounding (e.g. nearest 5c for cash floats). Adjusts only
        // the grand total; subtotal/discount/VAT stay as computed.
        val total = if (totalRounding > 0.0)
            Math.round(totals.total / totalRounding) * totalRounding
        else totals.total
        val saleId = newId()
        val stamp = now()

        val tenders = payments.filter { it.amount != 0.0 }
        val amountPaid = tenders.sumOf { it.amount }
        // Credit must be tied to a customer; the unpaid shortfall goes on account.
        val credit = onCredit && customer != null
        val owed = if (credit) (total - amountPaid).coerceAtLeast(0.0) else 0.0
        // Overpayment is change — handed back unless booked to the customer's account.
        val change = (amountPaid - total).coerceAtLeast(0.0)
        val changeToCredit = changeAsCredit && customer != null && change > 0.0

        val method = when {
            tenders.size > 1 -> "split"
            tenders.size == 1 -> tenders.first().method
            else -> "credit"
        }
        val singleCash = tenders.size == 1 && tenders.first().method == "cash"

        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            status = "completed",
            subtotal = subtotal,
            discountTotal = saleDiscount,
            taxTotal = taxTotal,
            total = total,
            paymentMethod = method,
            // Tendered only models the cash-handed-over case (single cash tender).
            tendered = if (singleCash) tenders.first().amount else null,
            amountPaid = amountPaid,
            // Change handed back; zero when it was booked to the customer's account.
            changeDue = if (changeToCredit) 0.0 else change.takeIf { it > 0.0 },
            // A single non-cash tender keeps its reference on the sale for the receipt.
            paymentRef = tenders.singleOrNull()?.reference?.takeIf { it.isNotBlank() },
            paymentStatus = if (owed > 0.0) "unpaid" else "paid",
            note = note,
            customerId = customer?.id,
            customerName = customer?.name,
            soldAt = stamp,
            createdBy = cashierId,
            createdByName = cashierName,
            updatedAt = stamp,
            synced = false
        )
        val lines = cart.map { c ->
            SaleLine(
                saleId = saleId,
                businessId = businessId,
                itemId = c.itemId,
                // Box lines snapshot the pack size into the name so the receipt
                // reads "Engine Oil (Box of 4)" rather than a bare unit count.
                name = if (c.mode == "box") "${c.name} (Box of ${c.unitsPerLine})" else c.name,
                qty = c.qty,
                unitPrice = c.unitPrice,
                // Per-line tax is superseded by business-level VAT; keep lines tax-free.
                lineTax = 0.0,
                lineTotal = c.lineSubtotal,
                mode = c.mode,
                unitsPerLine = c.unitsPerLine,
                updatedAt = stamp
            )
        }
        val paymentRows = tenders.map { t ->
            SalePayment(
                saleId = saleId,
                businessId = businessId,
                method = t.method,
                amount = t.amount,
                reference = t.reference?.takeIf { it.isNotBlank() },
                // Record the second-currency tender if there was one (base `amount` stays authoritative).
                tenderCurrency = t.currency,
                tenderAmount = t.tenderAmount,
                rate = t.rate,
                createdAt = stamp
            )
        }
        // All writes for one sale commit together (or not at all): the receipt,
        // its lines, the tenders, the stock draw-down + movement ledger and any
        // credit/change ledger rows are atomic, so a crash mid-checkout can never
        // leave a half-recorded sale.
        var savedSale = sale
        db.withTransaction {
            val receiptNo = nextReceiptNo(businessId)
            savedSale = sale.copy(receiptNo = receiptNo)
            saleDao.insertSale(savedSale)
            saleDao.insertLines(lines)
            if (paymentRows.isNotEmpty()) paymentDao.insertAll(paymentRows)
            // Draw down stock for any tracked items in the cart and log the movement.
            // Untracked items and ad-hoc lines (no matching item row) are left alone.
            // Clamped at zero so a mis-counted shelf never shows negative on hand.
            // Box lines consume qty * boxSize units.
            for (c in cart) {
                val item = itemDao.getById(c.itemId) ?: continue
                if (!item.trackStock) continue
                val remaining = (item.stockQty - c.stockUnits).coerceAtLeast(0.0)
                itemDao.upsert(item.copy(stockQty = remaining, updatedAt = stamp, pendingSync = true))
                movementDao.insert(
                    StockMovement(
                        businessId = businessId,
                        itemId = item.id,
                        type = "sale",
                        delta = -c.stockUnits,
                        balanceAfter = remaining,
                        note = "Sale #$receiptNo",
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp
                    )
                )
            }
            // Sold on credit => add the unpaid shortfall to the customer's ledger.
            if (owed > 0.0) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer!!.id,
                        saleId = saleId,
                        type = "credit_owed",
                        amount = owed,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // Change we couldn't (or chose not to) hand back => owed to the customer.
            if (changeToCredit) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer!!.id,
                        saleId = saleId,
                        type = "change_owed",
                        amount = change,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
        }
        return SaleWithLines(savedSale, lines)
    }

    // ---- parked / held sales ---------------------------------------------

    fun parkedSalesFlow(businessId: String): Flow<List<SaleEntity>> =
        saleDao.observeParked(businessId)

    fun parkedCountFlow(businessId: String): Flow<Int> =
        saleDao.observeParkedCount(businessId)

    /**
     * Hold the current cart for later: writes a `parked` sale + its lines with NO
     * stock draw-down, tender or ledger entry. Line [mode]/[unitsPerLine] are
     * snapshotted so the cart rebuilds exactly on resume. Returns the parked id.
     */
    suspend fun parkSale(
        businessId: String,
        cart: List<CartLine>,
        discount: Double = 0.0,
        note: String? = null,
        customer: Customer? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ): String {
        val saleId = newId()
        val stamp = now()
        val subtotal = cart.sumOf { it.lineSubtotal }
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            status = "parked",
            subtotal = subtotal,
            discountTotal = discount.coerceIn(0.0, subtotal),
            total = subtotal,
            paymentStatus = "unpaid",
            note = note,
            customerId = customer?.id,
            customerName = customer?.name,
            soldAt = stamp,
            createdBy = cashierId,
            createdByName = cashierName,
            updatedAt = stamp,
            synced = false
        )
        val lines = cart.map { c ->
            SaleLine(
                saleId = saleId,
                businessId = businessId,
                itemId = c.itemId,
                name = c.name,                      // raw name; mode is stored separately
                qty = c.qty,
                unitPrice = c.unitPrice,
                lineTotal = c.lineSubtotal,
                mode = c.mode,
                unitsPerLine = c.unitsPerLine,
                updatedAt = stamp
            )
        }
        db.withTransaction {
            saleDao.insertSale(sale)
            saleDao.insertLines(lines)
        }
        return saleId
    }

    /**
     * Resume a parked sale: rebuild the in-memory cart from its lines, then hard-
     * delete the parked sale (header + lines + any tenders) so it can't be resumed
     * twice. Ad-hoc lines without an item id are dropped (they can't re-price).
     */
    suspend fun resumeParked(saleId: String): List<CartLine> {
        val lines = saleDao.linesForSale(saleId)
        val cart = lines.mapNotNull { l ->
            val itemId = l.itemId ?: return@mapNotNull null
            CartLine(
                itemId = itemId,
                name = l.name,
                unitPrice = l.unitPrice,
                taxRate = 0.0,                      // VAT is business-level now
                qty = l.qty,
                mode = l.mode,
                unitsPerLine = l.unitsPerLine
            )
        }
        db.withTransaction {
            saleDao.hardDeletePayments(saleId)
            saleDao.hardDeleteLines(saleId)
            saleDao.hardDeleteSale(saleId)
        }
        return cart
    }

    // ---- inventory: stock movements & adjustments -------------------------

    fun lowStockFlow(businessId: String): Flow<List<Item>> =
        itemDao.observeLowStock(businessId)

    fun stockMovementsFlow(businessId: String): Flow<List<StockMovement>> =
        movementDao.observeForBusiness(businessId)

    fun itemMovementsFlow(itemId: String): Flow<List<StockMovement>> =
        movementDao.observeForItem(itemId)

    /**
     * Set an item's on-hand to [newQty] and log the signed change. [type] is
     * "adjust" for a manual stock-take correction or "restock" for goods received
     * outside a PO. The delta is computed from the current on-hand.
     */
    suspend fun adjustStock(
        itemId: String,
        newQty: Double,
        type: String = "adjust",
        note: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        val item = itemDao.getById(itemId) ?: return
        val stamp = now()
        val delta = newQty - item.stockQty
        db.withTransaction {
            itemDao.upsert(item.copy(stockQty = newQty, updatedAt = stamp, pendingSync = true))
            movementDao.insert(
                StockMovement(
                    businessId = item.businessId,
                    itemId = itemId,
                    type = type,
                    delta = delta,
                    balanceAfter = newQty,
                    note = note,
                    createdBy = cashierId,
                    createdByName = cashierName,
                    createdAt = stamp
                )
            )
        }
    }

    // ---- danger zone ------------------------------------------------------

    /** Zero every item's on-hand for a business (keeps the catalog rows). */
    suspend fun resetAllStock(businessId: String) =
        itemDao.resetAllStock(businessId, now())

    /** Wipe all sales history: receipts, their lines, their tenders and refunds. */
    suspend fun wipeSalesData(businessId: String) {
        db.withTransaction {
            saleDao.wipeSaleLines(businessId)
            saleDao.wipeSales(businessId)
            paymentDao.wipe(businessId)
            refundDao.wipePayments(businessId)
            refundDao.wipeLines(businessId)
            refundDao.wipe(businessId)
        }
    }

    // ---- change owed to customers ----------------------------------------

    fun changeBalanceFlow(customerId: String): Flow<Double> =
        creditDao.observeChangeBalance(customerId)

    /** Shop hands over change it previously owed a customer (writes change_paid). */
    suspend fun recordChangePayment(
        businessId: String,
        customerId: String,
        amount: Double,
        note: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0) return
        creditDao.insert(
            CreditTxn(
                businessId = businessId,
                customerId = customerId,
                type = "change_paid",
                amount = amount,
                note = note,
                createdBy = cashierId,
                createdByName = cashierName
            )
        )
    }

    // ---- refunds & returns (prompt §11) ----------------------------------

    /** Whole-shop refund history (newest first), each with its returned lines. */
    fun refundsFlow(businessId: String): Flow<List<RefundWithLines>> =
        refundDao.observeWithLines(businessId)

    /** Refunds already made against a given sale (UI caps over-refunding). */
    fun refundsForSaleFlow(saleId: String): Flow<List<Refund>> =
        refundDao.observeForSale(saleId)

    /** Refund tender breakdown over a window (mirrors the sale tender breakdown). */
    fun refundBreakdownFlow(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>> =
        refundDao.observeRefundBreakdown(businessId, from, to)

    /** Money refunded over a window — subtract from gross for net takings. */
    fun refundedSinceFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        refundDao.observeRefundedSince(businessId, from, to)

    suspend fun refundPaymentsFor(refundId: String): List<RefundPayment> =
        refundDao.paymentsFor(refundId)

    /** Units of a sale line already returned across prior refunds (refundable cap). */
    suspend fun qtyReturnedForLine(saleLineId: String): Double =
        refundDao.qtyReturnedForLine(saleLineId)

    /**
     * Issue a refund against a completed sale (prompt §11). Cashier-performed and
     * offline-first. Everything commits in ONE transaction so a crash can't restock
     * without recording the refund (or book money owed without the goods movement):
     *
     *  - Writes the immutable [Refund] header + its returned [RefundLine]s. The
     *    original sale is never edited — this is a reversal linked back to it.
     *  - [refundTotal] is computed proportionally from the sale (carries discount +
     *    VAT — see [computeRefundTotal]); the multiplier is applied exactly once.
     *  - Restocks each returned line that is [RefundLineInput.restock] and tracked,
     *    with a `return` [StockMovement] (damaged goods are refunded but not restocked).
     *  - [payouts] is the money handed back NOW (may be empty, partial, or split);
     *    each becomes a [RefundPayment] row carrying its method/currency/time.
     *  - Any shortfall (refundTotal − paid-now) is booked as a `refund_owed` credit
     *    row when a [customer] is set, so it ages in Change & Credit like change owed.
     *    A walk-in (no customer) can't carry a balance — pay such refunds in full.
     *
     * Returns the saved [Refund]. [cashierId]/[cashierName] stamp attribution.
     */
    suspend fun createRefund(
        businessId: String,
        sale: SaleEntity,
        lines: List<RefundLineInput>,
        payouts: List<Tender> = emptyList(),
        reason: String? = null,
        customer: Customer? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ): Refund {
        val stamp = now()
        val refundId = newId()

        // Returned goods' pre-adjustment value; ratio folds in the sale's discount+VAT.
        val returnedSubtotal = lines.sumOf { it.saleLine.unitPrice * it.qtyReturned }
        val refundTotal = computeRefundTotal(returnedSubtotal, sale.subtotal, sale.total).refundTotal

        val paidNow = payouts.filter { it.amount != 0.0 }.sumOf { it.amount }
        val outstanding = (refundTotal - paidNow).coerceAtLeast(0.0)
        // A balance can only be tracked/aged against a known customer (like change_owed).
        val owedToCustomer = outstanding > CENT && customer != null
        val status = if (outstanding <= CENT) "settled" else "owed"

        val refund = Refund(
            id = refundId,
            businessId = businessId,
            saleId = sale.id,
            saleReceiptNo = sale.receiptNo,
            customerId = customer?.id,
            customerName = customer?.name,
            reason = reason,
            refundTotal = refundTotal,
            status = status,
            createdBy = cashierId,
            createdByName = cashierName,
            createdAt = stamp,
            updatedAt = stamp
        )
        val refundLines = lines.map { inp ->
            RefundLine(
                refundId = refundId,
                businessId = businessId,
                saleLineId = inp.saleLine.id,
                itemId = inp.saleLine.itemId,
                name = inp.saleLine.name,
                qty = inp.qtyReturned,
                unitPrice = inp.saleLine.unitPrice,
                lineTotal = inp.saleLine.unitPrice * inp.qtyReturned,
                mode = inp.saleLine.mode,
                unitsPerLine = inp.saleLine.unitsPerLine,
                restock = inp.restock,
                createdAt = stamp
            )
        }
        val payoutRows = payouts.filter { it.amount != 0.0 }.map { t ->
            RefundPayment(
                refundId = refundId,
                businessId = businessId,
                method = t.method,
                amount = t.amount,
                reference = t.reference?.takeIf { it.isNotBlank() },
                tenderCurrency = t.currency,
                tenderAmount = t.tenderAmount,
                rate = t.rate,
                createdBy = cashierId,
                createdByName = cashierName,
                createdAt = stamp
            )
        }
        db.withTransaction {
            refundDao.insert(refund)
            if (refundLines.isNotEmpty()) refundDao.insertLines(refundLines)
            payoutRows.forEach { refundDao.insertPayment(it) }
            // Put returned goods back on the shelf (unless damaged). Box lines return
            // qty * unitsPerLine stock units — the multiplier applied once, same as
            // the checkout draw-down, only with the opposite sign.
            for (inp in lines) {
                if (!inp.restock) continue
                val item = inp.saleLine.itemId?.let { itemDao.getById(it) } ?: continue
                if (!item.trackStock) continue
                val units = inp.qtyReturned * inp.saleLine.unitsPerLine
                if (units <= 0.0) continue
                val newQty = item.stockQty + units
                itemDao.upsert(item.copy(stockQty = newQty, updatedAt = stamp, pendingSync = true))
                movementDao.insert(
                    StockMovement(
                        businessId = businessId,
                        itemId = item.id,
                        type = "return",
                        delta = units,
                        balanceAfter = newQty,
                        note = "Refund on #${sale.receiptNo ?: sale.id.take(8)}",
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp
                    )
                )
            }
            // Money still owed to the customer after the immediate payout → ageable row.
            if (owedToCustomer) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer!!.id,
                        saleId = sale.id,
                        type = "refund_owed",
                        amount = outstanding,
                        note = "Refund on #${sale.receiptNo ?: ""}".trim(),
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
        }
        return refund
    }

    /**
     * Pay off part or all of a refund the shop still owes a customer (prompt §11 —
     * "money over time, like change"). Writes a [RefundPayment] (records the method)
     * plus a `refund_paid` credit row that reduces the aged "we owe you" balance, and
     * flips the refund to `settled` once fully paid. Mirrors [recordChangePayment].
     */
    suspend fun recordRefundPayout(
        refundId: String,
        tender: Tender,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (tender.amount <= 0.0) return
        val refund = refundDao.getById(refundId) ?: return
        val stamp = now()
        db.withTransaction {
            refundDao.insertPayment(
                RefundPayment(
                    refundId = refundId,
                    businessId = refund.businessId,
                    method = tender.method,
                    amount = tender.amount,
                    reference = tender.reference?.takeIf { it.isNotBlank() },
                    tenderCurrency = tender.currency,
                    tenderAmount = tender.tenderAmount,
                    rate = tender.rate,
                    createdBy = cashierId,
                    createdByName = cashierName,
                    createdAt = stamp
                )
            )
            if (refund.customerId != null) {
                creditDao.insert(
                    CreditTxn(
                        businessId = refund.businessId,
                        customerId = refund.customerId,
                        saleId = refund.saleId,
                        type = "refund_paid",
                        amount = tender.amount,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // paidSoFar already includes the row just inserted (same transaction).
            val paid = refundDao.paidSoFar(refundId)
            val newStatus = if (paid + CENT >= refund.refundTotal) "settled" else "owed"
            if (newStatus != refund.status) {
                refundDao.upsert(refund.copy(status = newStatus, updatedAt = stamp))
            }
        }
    }

    // ---- mobile-money SMS reconciliation (prompt §6) ---------------------

    /** One status bucket (needs_verification / unmatched / verified), newest first. */
    fun mobileMoneyFlow(businessId: String, status: String): Flow<List<MobileMoneyReceipt>> =
        mobileMoneyDao.observeByStatus(businessId, status)

    /** Count still awaiting the cashier — drives the "More" badge. */
    fun mobileMoneyPendingCountFlow(businessId: String): Flow<Int> =
        mobileMoneyDao.observePendingCount(businessId)

    suspend fun mobileMoneyById(id: String): MobileMoneyReceipt? = mobileMoneyDao.getById(id)

    /**
     * Persist a parsed payment SMS (§6). Called by the passive SMS receiver, so it is
     * fully offline and idempotent:
     *  - Resolves the active business; no business yet ⇒ nothing to attach to, skip.
     *  - The unique (businessId, txnCode) index + insert-ignore means the SAME SMS can
     *    never be stored twice (§6.6). Returns null when it was a duplicate (so the
     *    receiver doesn't re-notify), otherwise the freshly stored receipt.
     *  - Matches the payer's number to a saved customer by the last-9-digits [phoneKey]
     *    (the same matcher the call-log/contacts features use) → `needs_verification`;
     *    no match ⇒ `unmatched` for later manual assignment (§6.1/§6.5).
     *  - [cashierId]/[cashierName] are the last-unlocked cashier (attribution on a
     *    passive record; may be null before first login).
     */
    suspend fun recordMobileMoneyReceipt(
        parsed: ParsedPayment,
        rawBody: String,
        cashierId: String? = null,
        cashierName: String? = null
    ): MobileMoneyReceipt? {
        val businessId = businessDao.getOnce()?.id ?: return null
        // Cheap pre-check; the unique index is the real guard against a race.
        if (mobileMoneyDao.getByTxn(businessId, parsed.txnCode) != null) return null

        val key = phoneKey(parsed.senderPhone)
        val match = if (key != null)
            customerDao.allForBusiness(businessId).firstOrNull { phoneKey(it.phone) == key }
        else null

        val receipt = MobileMoneyReceipt(
            businessId = businessId,
            provider = parsed.provider,
            rawBody = rawBody,
            sender = parsed.sender,
            senderName = parsed.senderName,
            senderPhone = parsed.senderPhone,
            amount = parsed.amount,
            currency = parsed.currency,
            txnCode = parsed.txnCode,
            receivedAt = parsed.receivedAt,
            status = if (match != null) "needs_verification" else "unmatched",
            matchedCustomerId = match?.id,
            matchedCustomerName = match?.name,
            createdBy = cashierId,
            createdByName = cashierName,
            updatedAt = now()
        )
        val row = mobileMoneyDao.insertIgnore(receipt)
        return if (row == -1L) null else receipt
    }

    /** Manually assign an unmatched payment to a customer, moving it to needs_verification. */
    suspend fun assignMobileMoneyCustomer(receiptId: String, customer: Customer) {
        val r = mobileMoneyDao.getById(receiptId) ?: return
        mobileMoneyDao.upsert(
            r.copy(
                matchedCustomerId = customer.id,
                matchedCustomerName = customer.name,
                status = "needs_verification",
                updatedAt = now(),
                pendingSync = true
            )
        )
    }

    /**
     * Verify a payment (§6.3–6.4). The cashier says what the money was for:
     *  - `debt`  — apply it to the customer's account as a `credit_paid` row (reuses
     *              the exact credit-ledger mechanism as [recordRepayment]; the receipt
     *              records the ledger row it produced via [MobileMoneyReceipt.appliedCreditTxnId]).
     *              Over-payment lands as a negative balance the Change & Credit screen
     *              surfaces, same as any repayment.
     *  - `sale`  — acknowledge it against a walk-in sale already rung up in the POS; no
     *              ledger row (the sale itself carries the money). Just marks it verified.
     * Idempotent-ish: a re-verify simply rewrites the receipt; guard in the UI stops
     * double-applying to a debt.
     */
    suspend fun verifyMobileMoney(
        receiptId: String,
        customer: Customer?,
        purpose: String,
        note: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        val r = mobileMoneyDao.getById(receiptId) ?: return
        if (r.status == "verified") return
        val stamp = now()
        db.withTransaction {
            var appliedCreditId: String? = null
            if (purpose == "debt" && customer != null) {
                val txn = CreditTxn(
                    businessId = r.businessId,
                    customerId = customer.id,
                    type = "credit_paid",
                    amount = r.amount,
                    note = note ?: "${r.provider} ${r.txnCode}",
                    createdBy = cashierId,
                    createdByName = cashierName,
                    createdAt = stamp,
                    updatedAt = stamp
                )
                creditDao.insert(txn)
                appliedCreditId = txn.id
            }
            mobileMoneyDao.upsert(
                r.copy(
                    status = "verified",
                    purpose = purpose,
                    matchedCustomerId = customer?.id ?: r.matchedCustomerId,
                    matchedCustomerName = customer?.name ?: r.matchedCustomerName,
                    appliedCreditTxnId = appliedCreditId,
                    note = note ?: r.note,
                    updatedAt = stamp,
                    pendingSync = true
                )
            )
        }
    }

    /** Dismiss a receipt (not a real payment / handled elsewhere). Never deletes it. */
    suspend fun ignoreMobileMoney(receiptId: String) {
        val r = mobileMoneyDao.getById(receiptId) ?: return
        mobileMoneyDao.upsert(r.copy(status = "ignored", updatedAt = now(), pendingSync = true))
    }

    // ---- admin: notifications backend (Phase 7, §8) ----------------------

    fun notificationsFlow(businessId: String): Flow<List<AppNotification>> =
        notificationDao.observeForBusiness(businessId)

    fun unreadNotificationCountFlow(businessId: String): Flow<Int> =
        notificationDao.observeUnreadCount(businessId)

    suspend fun markNotificationRead(id: String) = notificationDao.markRead(id, now())

    suspend fun markAllNotificationsRead(businessId: String) =
        notificationDao.markAllRead(businessId, now())

    /**
     * Recompute the whole alert state and reconcile it into the `notifications` table
     * (§8). One row per condition (keyed by dedupeKey): new conditions are inserted,
     * existing ones updated in place (preserving read-state), and conditions that have
     * cleared are tombstoned. Returns the rows that newly warrant a system notification
     * (pushed once), so the caller — which owns a Context — can post them.
     */
    suspend fun runNotificationSweep(
        thresholds: NotifThresholds,
        pendingSyncCount: Int,
        lastSyncAt: Long?
    ): List<AppNotification> {
        val biz = businessDao.getOnce() ?: return emptyList()
        val businessId = biz.id
        val nowMs = now()
        val salesWindow = nowMs - 30L * 24 * 60 * 60 * 1000   // large sales in the last 30 days
        val snapshot = NotifSnapshot(
            nowMs = nowMs,
            currency = biz.currency,
            trackedItems = itemDao.trackedOnce(businessId),
            owedRefunds = refundDao.owedOnce(businessId),
            pendingPayments = mobileMoneyDao.pendingOnce(businessId),
            largeSales = saleDao.largeSalesOnce(businessId, salesWindow, thresholds.largeSaleMin),
            agingRows = DebtAging.compute(
                creditDao.allForBusinessOnce(businessId),
                customerDao.allForBusiness(businessId).associate { it.id to it.name },
                nowMs
            ),
            pendingSyncCount = pendingSyncCount,
            lastSyncAt = lastSyncAt,
            thresholds = thresholds
        )
        val candidates = NotificationEngine.compute(snapshot)
        val existing = notificationDao.allActive(businessId).associateBy { it.dedupeKey }
        val seen = HashSet<String>()
        val toPush = ArrayList<AppNotification>()
        val stamp = now()
        for (c in candidates) {
            seen += c.dedupeKey
            val prev = existing[c.dedupeKey]
            if (prev == null) {
                var n = AppNotification(
                    businessId = businessId, category = c.category, severity = c.severity,
                    title = c.title, body = c.body, dedupeKey = c.dedupeKey,
                    refType = c.refType, refId = c.refId, eventAt = c.eventAt, createdAt = stamp
                )
                if (c.pushWorthy) { n = n.copy(pushedAt = stamp); toPush += n }
                notificationDao.upsert(n)
            } else {
                var n = prev.copy(
                    category = c.category, severity = c.severity, title = c.title,
                    body = c.body, eventAt = c.eventAt, refType = c.refType, refId = c.refId
                )
                if (c.pushWorthy && prev.pushedAt == null) { n = n.copy(pushedAt = stamp); toPush += n }
                notificationDao.upsert(n)
            }
        }
        // Tombstone rows whose condition has cleared (resolved low stock, settled refund).
        val cleared = existing.values.filter { it.dedupeKey !in seen }.map { it.id }
        if (cleared.isNotEmpty()) notificationDao.tombstone(cleared)
        return toPush
    }

    // ---- admin: audit log (Phase 7, §8) ----------------------------------

    fun auditFlow(businessId: String): Flow<List<AuditEntry>> = auditDao.observeForBusiness(businessId)

    suspend fun logAudit(
        businessId: String,
        action: String,
        summary: String,
        entityType: String? = null,
        entityId: String? = null,
        meta: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        auditDao.insert(
            AuditEntry(
                businessId = businessId, action = action, entityType = entityType,
                entityId = entityId, summary = summary, meta = meta,
                createdBy = cashierId, createdByName = cashierName
            )
        )
    }

    // ---- admin: end-of-day / shift summary (Phase 7, §8) -----------------

    fun cashierDayFlow(businessId: String, from: Long, to: Long): Flow<List<CashierDay>> =
        saleDao.observeCashierDay(businessId, from, to)

    /** Change handed back in the window — cash-in-drawer = cash tenders − this. */
    fun changeGivenFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeChangeGiven(businessId, from, to)

    // ---- admin: void a wrongful refund (Phase 7, §8) ---------------------

    /**
     * Void a refund an admin judges wrongful. The refund ledger stays immutable in
     * spirit — we tombstone the refund and post COMPENSATING entries: re-draw any
     * restocked goods back down (a `adjust` movement) and settle any still-owed balance
     * with a `refund_paid` row so the customer's "we owe you" balance returns to zero.
     * Audit-logged.
     */
    suspend fun voidRefund(refundId: String, cashierId: String? = null, cashierName: String? = null) {
        val r = refundDao.getById(refundId) ?: return
        if (r.deleted) return
        val stamp = now()
        db.withTransaction {
            for (ln in refundDao.linesFor(refundId)) {
                if (!ln.restock) continue
                val item = ln.itemId?.let { itemDao.getById(it) } ?: continue
                if (!item.trackStock) continue
                val units = ln.qty * ln.unitsPerLine
                if (units <= 0.0) continue
                val newQty = (item.stockQty - units).coerceAtLeast(0.0)
                itemDao.upsert(item.copy(stockQty = newQty, updatedAt = stamp, pendingSync = true))
                movementDao.insert(
                    StockMovement(
                        businessId = r.businessId, itemId = item.id, type = "adjust",
                        delta = -units, balanceAfter = newQty,
                        note = "Void refund #${r.saleReceiptNo ?: ""}".trim(),
                        createdBy = cashierId, createdByName = cashierName, createdAt = stamp
                    )
                )
            }
            val outstanding = r.refundTotal - refundDao.paidSoFar(refundId)
            if (r.customerId != null && outstanding > CENT) {
                creditDao.insert(
                    CreditTxn(
                        businessId = r.businessId, customerId = r.customerId, saleId = r.saleId,
                        type = "refund_paid", amount = outstanding, note = "Void refund",
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            refundDao.softDelete(refundId, stamp)
            auditDao.insert(
                AuditEntry(
                    businessId = r.businessId, action = "void_refund", entityType = "refund",
                    entityId = refundId,
                    summary = "Voided refund of ${fmtMoney(r.refundTotal)} on #${r.saleReceiptNo ?: ""}".trim(),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    // ---- admin: write off a customer's debt (Phase 7, §8) ----------------

    /** Admin-only debt write-off: a `credit_paid` row that clears the balance, audited. */
    suspend fun writeOffDebt(
        businessId: String,
        customerId: String,
        amount: Double,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0.0) return
        db.withTransaction {
            creditDao.insert(
                CreditTxn(
                    businessId = businessId, customerId = customerId, type = "credit_paid",
                    amount = amount, note = "Debt write-off",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
            auditDao.insert(
                AuditEntry(
                    businessId = businessId, action = "debt_writeoff", entityType = "customer",
                    entityId = customerId, summary = "Wrote off ${fmtMoney(amount)}",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    private fun fmtMoney(n: Double): String = String.format(java.util.Locale.US, "%.2f", n)

    /** Count of local rows not yet pushed to the cloud — feeds the "not synced" alert. */
    suspend fun pendingSyncCount(): Int =
        businessDao.pending().size + itemDao.pending().size + saleDao.pendingSales().size +
            customerDao.pending().size + creditDao.pending().size + refundDao.pending().size +
            mobileMoneyDao.pending().size

    /** Admin notification thresholds (§8), read from device-local settings with defaults. */
    suspend fun loadNotifThresholds(): NotifThresholds = NotifThresholds(
        largeSaleMin = settingDao.get(KEY_ADMIN_LARGE_SALE)?.toDoubleOrNull() ?: 500.0,
        escalateHours = settingDao.get(KEY_ESC_HOURS)?.toIntOrNull() ?: 4,
        unsyncedHours = settingDao.get(KEY_UNSYNCED_HOURS)?.toIntOrNull() ?: 6
    )

    companion object {
        // Shared setting keys for admin notification thresholds (used by the VM to
        // persist and by the background worker to read — same source of truth).
        const val KEY_ADMIN_LARGE_SALE = "admin_large_sale"
        const val KEY_ESC_HOURS = "admin_escalate_hours"
        const val KEY_UNSYNCED_HOURS = "admin_unsynced_hours"
    }

    // ---- reports: tender breakdown from actual split amounts --------------

    fun paymentBreakdownFlow(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>> =
        paymentDao.observeMethodBreakdown(businessId, from, to)

    /** Quick customer create from a typed name at checkout (returns the new row). */
    suspend fun createCustomer(
        businessId: String,
        name: String,
        phone: String? = null,
        wholesale: Boolean = false
    ): Customer {
        val customer = Customer(
            businessId = businessId,
            name = name.trim(),
            phone = phone?.takeIf { it.isNotBlank() },
            wholesale = wholesale,
            updatedAt = now(),
            pendingSync = true
        )
        customerDao.upsert(customer)
        return customer
    }

    /**
     * Per-business sequential receipt number. Stored as a counter in the local
     * settings table; called inside the checkout transaction so it never skips or
     * collides. Zero-padded to four digits ("0001", "0002", …).
     */
    private suspend fun nextReceiptNo(businessId: String): String {
        val key = "receiptSeq:$businessId"
        val next = (settingDao.get(key)?.toIntOrNull() ?: 0) + 1
        settingDao.put(Setting(key, next.toString()))
        return next.toString().padStart(4, '0')
    }

    /**
     * Human-readable document code, mirroring the web's `genRef`:
     * `PREFIX-YYMMDD-NNNN` (NNNN a random 1000–9999). Good enough to eyeball and
     * search; collisions are harmless because the real key is the UUID id.
     */
    private fun genRef(prefix: String): String {
        val stamp = java.text.SimpleDateFormat("yyMMdd", java.util.Locale.US).format(java.util.Date())
        val n = (1000..9999).random()
        return "$prefix-$stamp-$n"
    }
}

/**
 * A single live line in the on-screen cart. Pure in-memory model — never
 * persisted directly; it is snapshotted into [SaleLine] only at checkout.
 *
 * Wholesale model (mirrors the web cart):
 *  - [mode] is how the line was priced — "box", "wholesale" or "retail".
 *  - [unitPrice] is the price of ONE line-unit (a whole box for box mode,
 *    otherwise a single piece).
 *  - [qty] counts those line-units (e.g. 2 boxes).
 *  - [unitsPerLine] is how many STOCK units one line-unit consumes — the box
 *    size for box mode, otherwise 1. Stock is drawn down by qty * unitsPerLine.
 */
/**
 * One tender collected at checkout. A normal sale has a single [Tender]; a split
 * sale has several (e.g. cash + EcoCash). [method] is a payment code (cash, card,
 * bank, paynow, ecocash, innbucks, onemoney, omari), [reference] an optional
 * mobile-money / bank / Paynow reference. Snapshotted into [SalePayment] rows.
 */
data class Tender(
    val method: String,
    val amount: Double,                 // value in the BASE currency — what the books/reports use
    val reference: String? = null,
    // ── Dual-currency: set only when the cashier tendered in the SECOND currency ──
    val currency: String? = null,       // second-currency code, e.g. "ZWG" (null => paid in base)
    val tenderAmount: Double? = null,   // amount actually handed over, in [currency]
    val rate: Double? = null            // second-per-base rate used to convert to [amount]
)

/**
 * One line the cashier chose to return, for [PosRepository.createRefund]. [saleLine]
 * is the ORIGINAL line off the sale (its unitPrice/mode/unitsPerLine are snapshots);
 * [qtyReturned] is how many line-units come back (≤ the line's qty minus anything
 * already returned); [restock] is false for damaged goods that shouldn't go back on
 * the shelf.
 */
data class RefundLineInput(
    val saleLine: SaleLine,
    val qtyReturned: Double,
    val restock: Boolean = true
)

data class CartLine(
    val itemId: String,
    val name: String,
    val unitPrice: Double,
    val taxRate: Double,
    val qty: Double,
    val mode: String = "retail",
    val unitsPerLine: Int = 1
) {
    val lineSubtotal: Double get() = unitPrice * qty
    val lineTax: Double get() = lineSubtotal * (taxRate / 100.0)
    val lineTotal: Double get() = lineSubtotal + lineTax
    /** Total stock units this line removes from the shelf. */
    val stockUnits: Double get() = qty * unitsPerLine
    /** Identity for cart merge/update: the same item at a different price mode is a separate line. */
    val lineKey: String get() = "$itemId#$mode"
}
