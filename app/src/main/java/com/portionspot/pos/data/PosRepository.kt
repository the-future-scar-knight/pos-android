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
    private val cashTxnDao = db.cashTxnDao()
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

    fun salesForCustomerFlow(customerId: String): Flow<List<SaleEntity>> =
        saleDao.observeSalesForCustomer(customerId)

    fun takingsSinceFlow(businessId: String, since: Long): Flow<Double> =
        saleDao.observeTakingsSince(businessId, since)

    fun saleCountSinceFlow(businessId: String, since: Long): Flow<Int> =
        saleDao.observeCountSince(businessId, since)

    // ---- reports ----------------------------------------------------------

    fun salesSummaryFlow(businessId: String, from: Long, to: Long): Flow<SalesSummary> =
        saleDao.observeSummary(businessId, from, to)

    fun methodBreakdownFlow(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>> =
        saleDao.observeMethodBreakdown(businessId, from, to)

    /** How many sales in the window are fully refunded — subtract from the sale count
     *  for a net "live sales" figure (prompt §5). */
    fun fullyRefundedCountFlow(businessId: String, from: Long, to: Long): Flow<Int> =
        saleDao.observeFullyRefundedCount(businessId, from, to)

    // ---- dashboard --------------------------------------------------------

    fun topProductsFlow(businessId: String, from: Long, to: Long, limit: Int = 5): Flow<List<TopProduct>> =
        saleDao.observeTopProducts(businessId, from, to, limit)

    fun grossProfitFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeGrossProfit(businessId, from, to)

    /** Refunded value per sale (for the Receipts "refunded" badge). Presentation only. */
    fun refundedBySaleFlow(businessId: String): Flow<List<SaleRefundSum>> =
        refundDao.observeRefundedBySale(businessId)

    /** Revenue of costed lines only — the honest denominator for the margin figure. */
    fun costedRevenueFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeCostedRevenue(businessId, from, to)

    /**
     * Danger zone: wipe this DEVICE's re-pullable data (sales, catalog, customers and
     * ledgers) so a fresh cloud pull can repopulate them from the shared dataset. Used
     * to clear local TEST data before enabling push. Keeps the business profile,
     * device settings (printer/theme) and the cloud connection.
     */
    /**
     * Merge duplicate catalogue items in place — WITHOUT touching sales/customers, so it
     * is safe to run on a live device (unlike [resetLocalData]). Two safe rules:
     *  1. items sharing a sku (case/space-folded) are the same product → keep the newest,
     *     hard-delete the rest.
     *  2. a sku-less item whose name uniquely matches ONE sku'd item is a hand-added
     *     duplicate of that cloud product → hard-delete it (the sku'd row is canonical).
     * Ambiguous name matches (0 or >1) are left alone. Returns how many rows were removed.
     */
    suspend fun dedupeItems(): Int {
        val bid = businessDao.getOnce()?.id ?: return 0
        var removed = 0
        db.withTransaction {
            val items = itemDao.allForBusinessOnce(bid).filter { !it.deleted }
            // 1) exact-sku duplicates
            val keptBySku = mutableListOf<Item>()
            items.filter { !it.sku.isNullOrBlank() }
                .groupBy { it.sku!!.trim().lowercase() }
                .forEach { (_, group) ->
                    val keep = group.maxByOrNull { it.updatedAt }!!
                    keptBySku += keep
                    group.filter { it.id != keep.id }.forEach { itemDao.hardDelete(it.id); removed++ }
                }
            // 2) sku-less item absorbed into a UNIQUE same-name sku'd item
            val skuedByName = keptBySku.groupBy { it.name.trim().lowercase() }
            items.filter { it.sku.isNullOrBlank() }.forEach { item ->
                val match = skuedByName[item.name.trim().lowercase()]
                if (match != null && match.size == 1) { itemDao.hardDelete(item.id); removed++ }
            }
        }
        return removed
    }

    suspend fun resetLocalData() {
        val bid = businessDao.getOnce()?.id ?: return
        db.withTransaction {
            saleDao.wipeSales(bid)
            saleDao.wipeSaleLines(bid)
            paymentDao.wipe(bid)
            movementDao.wipe(bid)
            creditDao.wipe(bid)
            refundDao.wipe(bid)
            refundDao.wipeLines(bid)
            refundDao.wipePayments(bid)
            mobileMoneyDao.wipe(bid)
            notificationDao.wipe(bid)
            itemDao.wipe(bid)
            customerDao.wipe(bid)
        }
    }

    fun stampsSinceFlow(businessId: String, from: Long): Flow<List<SaleStamp>> =
        saleDao.observeStampsSince(businessId, from)

    suspend fun linesForSale(saleId: String): List<SaleLine> =
        saleDao.linesForSale(saleId)

    suspend fun saveBusiness(business: Business) =
        businessDao.upsert(business.copy(updatedAt = now(), pendingSync = true))

    /**
     * Save a catalogue item, guaranteeing SKU uniqueness at the source so duplicates
     * can't be created in the first place (prompt §1). If another row already owns this
     * SKU (case/space-folded), we write onto THAT row's id instead of inserting a second
     * copy — whether this is a brand-new add or an edit that collides with an existing
     * code. SKU-less items are unaffected (they can legitimately repeat).
     */
    suspend fun saveItem(item: Item) {
        val sku = item.sku?.trim()?.ifBlank { null }
        val canonicalId = sku
            ?.let { itemDao.getBySku(item.businessId, it) }
            ?.takeIf { it.id != item.id }
            ?.id
        val target = if (canonicalId != null) item.copy(id = canonicalId) else item
        itemDao.upsert(target.copy(sku = sku, updatedAt = now(), pendingSync = true))
    }

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

    /** (saleId → receipt reference) map source for labelling ledger rows with the sale
     *  that created them. */
    fun saleRefsFlow(businessId: String): Flow<List<SaleRef>> =
        saleDao.observeSaleRefs(businessId)

    suspend fun saveCustomer(customer: Customer) =
        customerDao.upsert(customer.copy(updatedAt = now(), pendingSync = true))

    /** One customer by id (e.g. to resolve the owed-refund target from a sale). */
    suspend fun customerById(id: String): Customer? = customerDao.getById(id)

    /**
     * Record a repayment against a customer's account. Settles the debt with a
     * `credit_paid` row up to what's owed; any OVERPAYMENT is booked as a `change_owed`
     * row, so the excess flows into the shop-owes-customer balance (payable back later
     * via [recordChangePayment]) instead of driving the debt negative. Both rows commit
     * together.
     */
    suspend fun recordRepayment(
        businessId: String,
        customerId: String,
        amount: Double,
        note: String? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0) return
        val debt = creditDao.balanceOnce(customerId).coerceAtLeast(0.0)
        val paid = minOf(amount, debt)      // never past zero: debt won't go negative
        val excess = amount - paid          // overpayment → shop now owes the customer
        val stamp = now()
        db.withTransaction {
            if (paid > 0.0) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "credit_paid",
                        amount = paid,
                        note = note,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            if (excess > 0.0) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "change_owed",
                        amount = excess,
                        note = if (paid > 0.0) "Overpayment" else note ?: "Overpayment",
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
        }
    }

    /** Shop-wide money owed back to customers (change + unpaid refunds). */
    fun totalChangeOwedFlow(businessId: String): Flow<Double> =
        creditDao.observeTotalChangeOwed(businessId)

    // ---- expenses + cash ledger (accounting spine, B3; local-only, sync-ready) ----

    fun expensesFlow(businessId: String): Flow<List<Expense>> =
        expenseDao.observeForBusiness(businessId)

    fun pendingExpensesFlow(businessId: String): Flow<List<Expense>> =
        expenseDao.observePending(businessId)

    fun recurringTemplatesFlow(businessId: String): Flow<List<Expense>> =
        expenseDao.observeTemplates(businessId)

    fun postedExpensesBetweenFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        expenseDao.observePostedTotalBetween(businessId, from, to)

    fun payablesTotalFlow(businessId: String): Flow<Double> =
        expenseDao.observePayablesTotal(businessId)

    fun ownerContributionsFlow(businessId: String): Flow<Double> =
        expenseDao.observeOwnerContributions(businessId)

    fun cashTxnsFlow(businessId: String): Flow<List<CashTxn>> =
        cashTxnDao.observeForBusiness(businessId)

    /** Net of the cash-ledger movements (opening float is added on top in the VM). */
    fun cashMovementsSumFlow(businessId: String): Flow<Double> =
        cashTxnDao.observeMovementsSum(businessId)

    /** Opening cash float the admin set (device-local setting), 0 if unset. */
    suspend fun openingFloat(businessId: String): Double =
        settingDao.get(KEY_OPENING_FLOAT)?.toDoubleOrNull() ?: 0.0

    /** Cash-on-hand right now = opening float + net movements. Used for the shortfall
     *  check the moment an expense is being posted. */
    suspend fun cashOnHandOnce(businessId: String): Double =
        openingFloat(businessId) + cashTxnDao.movementsSumOnce(businessId)

    /**
     * SUBMIT an expense for admin approval (prompt §9.2). Anyone may submit; the row
     * lands as `status = 'pending'` and posts nothing until approved. A [recurring]
     * submission carries its [recurrencePeriod]; on approval it becomes a schedule.
     * Returns the new row so the caller can notify the admin.
     */
    suspend fun submitExpense(
        businessId: String,
        category: String,
        amount: Double,
        date: String,
        description: String?,
        recurring: Boolean = false,
        recurrencePeriod: String? = null,
        periodStart: String? = null,
        periodEnd: String? = null,
        submittedBy: String? = null,
        submittedByName: String? = null
    ): Expense? {
        if (amount <= 0.0) return null
        val e = Expense(
            businessId = businessId, category = category, amount = amount, date = date,
            description = description?.trim()?.ifBlank { null }, status = "pending",
            recurring = recurring,
            recurrencePeriod = if (recurring) (recurrencePeriod ?: "monthly") else null,
            periodStart = periodStart, periodEnd = periodEnd,
            submittedBy = submittedBy, submittedByName = submittedByName
        )
        expenseDao.upsert(e)
        return e
    }

    /** Edit a still-PENDING submission before it's approved (kept editable; once posted
     *  it's immutable). No-op if the row has already left the pending state. */
    suspend fun updatePendingExpense(
        id: String, category: String, amount: Double, date: String, description: String?,
        recurring: Boolean, recurrencePeriod: String?
    ) {
        val e = expenseDao.getById(id) ?: return
        if (e.status != "pending" || e.deleted) return
        if (amount <= 0.0) return
        expenseDao.upsert(
            e.copy(
                category = category, amount = amount, date = date,
                description = description?.trim()?.ifBlank { null },
                recurring = recurring,
                recurrencePeriod = if (recurring) (recurrencePeriod ?: "monthly") else null,
                updatedAt = now(), pendingSync = true
            )
        )
    }

    /** Reject a pending expense (nothing hits the books). Audited. */
    suspend fun rejectExpense(id: String, cashierId: String? = null, cashierName: String? = null) {
        val e = expenseDao.getById(id) ?: return
        if (e.status != "pending") return
        val stamp = now()
        db.withTransaction {
            expenseDao.upsert(e.copy(status = "rejected", updatedAt = stamp, pendingSync = true))
            clearPendingExpenseNotice(e.businessId, e.id)
            auditDao.insert(
                AuditEntry(
                    businessId = e.businessId, action = "expense_rejected", entityType = "expense",
                    entityId = e.id, summary = "Rejected ${e.category} expense ${fmtMoney(e.amount)}",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    /** Tombstone the "awaiting approval" feed row once an expense is approved/rejected. */
    private suspend fun clearPendingExpenseNotice(businessId: String, expenseId: String) {
        notificationDao.getByKey(businessId, "expensepending:$expenseId")?.let {
            notificationDao.tombstone(listOf(it.id))
        }
    }

    /**
     * APPROVE (post) a pending expense with a chosen funding [mode] — the double-entry-
     * lite split (prompt §9.4). Modes:
     *  - "cash"      → pay it all from cash-on-hand (cashPortion = amount, cash-out row).
     *  - "available" → pay what cash there is, remainder → accounts payable (cash → 0).
     *  - "capital"   → the owner covers it; cash untouched (capitalPortion = amount).
     * A recurring submission additionally becomes a SCHEDULE (a separate template row)
     * so future periods auto-post. Audited. All writes are atomic.
     */
    suspend fun approveExpense(
        id: String, mode: String, cashierId: String? = null, cashierName: String? = null
    ) {
        val e = expenseDao.getById(id) ?: return
        if (e.status != "pending" || e.deleted) return
        val stamp = now()
        db.withTransaction {
            val onHand = cashOnHandOnce(e.businessId).coerceAtLeast(0.0)
            val (cash, payable, capital) = splitFunding(e.amount, mode, onHand)
            val posted = e.copy(
                status = "approved", approvedBy = cashierId, approvedByName = cashierName,
                approvedAt = stamp, postedAt = stamp,
                cashPortion = cash, payablePortion = payable, capitalPortion = capital,
                updatedAt = stamp, pendingSync = true
            )
            expenseDao.upsert(posted)
            clearPendingExpenseNotice(e.businessId, e.id)
            if (cash > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = e.businessId, type = "expense", amount = -cash,
                        source = fundingLabel(mode), note = "${e.category} · ${e.description ?: "expense"}",
                        refType = "expense", refId = e.id,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // A recurring approval spins up the schedule template (not itself a cost).
            if (e.recurring && e.recurrencePeriod != null) {
                expenseDao.upsert(
                    Expense(
                        businessId = e.businessId, category = e.category, amount = e.amount,
                        date = e.date, description = e.description, status = "approved",
                        recurring = true, recurrencePeriod = e.recurrencePeriod,
                        recurrenceActive = true, isTemplate = true,
                        approvedBy = cashierId, approvedByName = cashierName, approvedAt = stamp,
                        nextRunAt = nextRun(stamp, e.recurrencePeriod), lastRunAt = stamp,
                        submittedBy = e.submittedBy, submittedByName = e.submittedByName
                    )
                )
            }
            auditDao.insert(
                AuditEntry(
                    businessId = e.businessId, action = "expense_approved", entityType = "expense",
                    entityId = e.id,
                    summary = "Approved ${e.category} ${fmtMoney(e.amount)} via ${fundingLabel(mode)}" +
                        (if (payable > CENT) " (${fmtMoney(payable)} owed)" else ""),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    /** Pause / resume a recurring schedule (admin). No new children post while paused. */
    suspend fun setRecurringActive(templateId: String, active: Boolean) {
        val t = expenseDao.getById(templateId) ?: return
        if (!t.isTemplate) return
        expenseDao.upsert(t.copy(recurrenceActive = active, updatedAt = now(), pendingSync = true))
    }

    /** Edit a recurring schedule's amount — affects FUTURE children only; already-posted
     *  charges stay immutable. */
    suspend fun editRecurringAmount(templateId: String, newAmount: Double) {
        val t = expenseDao.getById(templateId) ?: return
        if (!t.isTemplate || newAmount <= 0.0) return
        expenseDao.upsert(t.copy(amount = newAmount, updatedAt = now(), pendingSync = true))
    }

    /** Cancel a recurring schedule (tombstone the template; posted history is kept). */
    suspend fun cancelRecurring(templateId: String) = expenseDao.softDelete(templateId, now())

    /** Delete a still-pending or rejected submission (posted rows stay immutable). */
    suspend fun deleteExpense(id: String) {
        val e = expenseDao.getById(id) ?: return
        if (e.status == "approved" && !e.isTemplate) return   // posted costs are immutable
        expenseDao.softDelete(id, now())
    }

    /** Set the opening cash float (device-local). Cash-on-hand = this + Σ movements. */
    suspend fun setOpeningFloat(amount: Double) =
        putSetting(KEY_OPENING_FLOAT, amount.coerceAtLeast(0.0).toString())

    /** Record an ad-hoc cash payout / drawer adjustment (admin), audited. */
    suspend fun recordCashAdjustment(
        businessId: String, amount: Double, note: String,
        cashierId: String? = null, cashierName: String? = null
    ) {
        if (kotlin.math.abs(amount) < CENT) return
        val stamp = now()
        db.withTransaction {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = if (amount < 0) "payout" else "adjust",
                    amount = amount, source = "manual", note = note.ifBlank { "Cash adjustment" },
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            auditDao.insert(
                AuditEntry(
                    businessId = businessId, action = "cash_adjust", entityType = "cash",
                    summary = "Cash ${if (amount < 0) "payout" else "top-up"} ${fmtMoney(kotlin.math.abs(amount))} — ${note.ifBlank { "manual" }}",
                    meta = fmtMoney(amount), createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    /**
     * Post every recurring child that has come due (driven by the auto-post worker,
     * prompt §9.3). Each due template mints an approved child charge and drains cash for
     * it, auto-applying the "take what's available" rule when cash is short (so books
     * balance with no UI). Advances each template's next-run. Returns short summaries the
     * caller turns into admin notifications.
     */
    suspend fun postDueRecurringExpenses(businessId: String): List<Pair<Expense, String>> {
        val now = now()
        val due = expenseDao.dueTemplates(businessId, now)
        val posted = ArrayList<Pair<Expense, String>>()
        for (tpl in due) {
            db.withTransaction {
                val onHand = cashOnHandOnce(businessId).coerceAtLeast(0.0)
                // Default policy for unattended posting: pay from cash, remainder → payable.
                val mode = if (onHand + CENT >= tpl.amount) "cash" else "available"
                val (cash, payable, capital) = splitFunding(tpl.amount, mode, onHand)
                val child = Expense(
                    businessId = businessId, category = tpl.category, amount = tpl.amount,
                    date = ymd(now), description = tpl.description, status = "approved",
                    templateId = tpl.id, recurring = false, isTemplate = false,
                    approvedAt = now, postedAt = now,
                    cashPortion = cash, payablePortion = payable, capitalPortion = capital,
                    approvedByName = "Auto (recurring)"
                )
                expenseDao.upsert(child)
                if (cash > CENT) {
                    cashTxnDao.insert(
                        CashTxn(
                            businessId = businessId, type = "expense", amount = -cash,
                            source = "recurring", note = "${tpl.category} (recurring)",
                            refType = "expense", refId = child.id,
                            createdAt = now, updatedAt = now
                        )
                    )
                }
                expenseDao.upsert(
                    tpl.copy(
                        lastRunAt = now, nextRunAt = nextRun(now, tpl.recurrencePeriod ?: "monthly"),
                        updatedAt = now, pendingSync = true
                    )
                )
                auditDao.insert(
                    AuditEntry(
                        businessId = businessId, action = "expense_recurring_posted",
                        entityType = "expense", entityId = child.id,
                        summary = "Auto-posted ${tpl.category} ${fmtMoney(tpl.amount)}" +
                            (if (payable > CENT) " (${fmtMoney(payable)} owed)" else ""),
                        createdByName = "Auto (recurring)"
                    )
                )
                posted += child to buildString {
                    append("${tpl.category} ${fmtMoney(tpl.amount)} auto-posted")
                    if (payable > CENT) append(" — ${fmtMoney(payable)} on account (cash short)")
                }
            }
        }
        return posted
    }

    /**
     * Worker entry point (§9.3): post every due recurring charge and, for each, persist
     * an admin-feed notification and return it for a system push. No re-approval — the
     * admin is simply informed.
     */
    suspend fun runRecurringExpenseSweep(): List<AppNotification> {
        val biz = businessDao.getOnce() ?: return emptyList()
        val posted = postDueRecurringExpenses(biz.id)
        if (posted.isEmpty()) return emptyList()
        val stamp = now()
        val out = ArrayList<AppNotification>()
        for ((child, summary) in posted) {
            val n = AppNotification(
                businessId = biz.id, category = "expenses",
                severity = if (child.payablePortion > CENT) "warn" else "info",
                title = "Recurring expense posted", body = summary,
                dedupeKey = "recurring:${child.id}", refType = "expense", refId = child.id,
                eventAt = stamp, createdAt = stamp, pushedAt = stamp
            )
            notificationDao.upsert(n)
            out += n
        }
        return out
    }

    /**
     * Persist + return a "new expense awaiting approval" admin notification (§9.2), fired
     * the moment a cashier submits one. Keyed per-expense so each submission is its own row.
     */
    suspend fun notifyExpenseSubmitted(e: Expense): AppNotification {
        val stamp = now()
        val n = AppNotification(
            businessId = e.businessId, category = "expenses", severity = "warn",
            title = "Expense to approve",
            body = "${e.category} ${fmtMoney(e.amount)}" +
                (e.submittedByName?.let { " · by $it" } ?: "") +
                (if (e.recurring) " · recurring" else ""),
            dedupeKey = "expensepending:${e.id}", refType = "expense", refId = e.id,
            eventAt = e.createdAt, createdAt = stamp, pushedAt = stamp
        )
        notificationDao.upsert(n)
        return n
    }

    /** Split a posted expense [amount] across cash / payable / capital for the chosen
     *  funding [mode], honouring the [onHand] ceiling. Returns (cash, payable, capital). */
    private fun splitFunding(amount: Double, mode: String, onHand: Double): Triple<Double, Double, Double> = when (mode) {
        "capital" -> Triple(0.0, 0.0, amount)
        "available" -> {
            val cash = amount.coerceAtMost(onHand.coerceAtLeast(0.0))
            Triple(cash, (amount - cash).coerceAtLeast(0.0), 0.0)
        }
        else -> Triple(amount, 0.0, 0.0)   // "cash"
    }

    private fun fundingLabel(mode: String): String = when (mode) {
        "capital" -> "owner capital"
        "available" -> "cash + payable"
        else -> "cash"
    }

    /** Next auto-post instant for a period, from [from]. */
    private fun nextRun(from: Long, period: String): Long {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = from }
        when (period) {
            "daily" -> c.add(java.util.Calendar.DAY_OF_YEAR, 1)
            "weekly" -> c.add(java.util.Calendar.DAY_OF_YEAR, 7)
            else -> c.add(java.util.Calendar.MONTH, 1)   // monthly
        }
        return c.timeInMillis
    }

    private fun ymd(epoch: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epoch))

    // ---- suppliers (local-only; never synced) -----------------------------

    fun suppliersFlow(businessId: String): Flow<List<Supplier>> =
        supplierDao.observeForBusiness(businessId)

    suspend fun saveSupplier(supplier: Supplier) =
        supplierDao.upsert(supplier.copy(updatedAt = now()))

    suspend fun deleteSupplier(id: String) = supplierDao.softDelete(id, now())

    // ---- purchase orders (local-only; never synced) -----------------------

    fun purchaseOrdersFlow(businessId: String): Flow<List<PurchaseOrderWithLines>> =
        poDao.observeWithLines(businessId)

    /** Shop-wide accounts payable owed to suppliers (sum of unpaid PO balances). */
    fun supplierPayablesFlow(businessId: String): Flow<Double> =
        poDao.observeSupplierPayables(businessId)

    /**
     * Place a supplier order (B4) with its lines, PENDING stock, and the cash payment —
     * all in one atomic write. Returns the new PO id.
     *
     * PENDING STOCK (§10.4): every line flagged [PurchaseOrderLine.stockOnArrival] is
     * reflected as incoming stock that is NOT yet sellable. A line with no [itemId] mints
     * a brand-new catalog product marked `pendingNew` (blocked from sale) carrying the
     * incoming quantity; a line that links an existing item bumps that item's `pendingQty`
     * (a "+N pending" badge) while its current sellable stock is untouched.
     *
     * PAYMENT (§10.3) — buying stock is a CASH → INVENTORY asset purchase, NOT an expense:
     * [payNow] cash drains the drawer through a `cash_txns` "purchase" row (never an
     * `expenses` row, so it never reduces derived net profit — the goods only affect profit
     * later via cost-of-goods-sold when sold). [fundingMode] mirrors B3's shortfall choice:
     *  - "cash"      → pay [payNow] fully from cash.
     *  - "available" → pay what cash there is, remainder → supplier accounts payable.
     *  - "capital"   → owner covers [payNow] out of pocket (cash untouched).
     * Whatever of the order total isn't covered becomes `payableRemainder` (money owed to
     * the supplier).
     */
    suspend fun createPurchaseOrder(
        businessId: String,
        supplierId: String?,
        supplierName: String,
        notes: String?,
        eta: Long?,
        lines: List<PurchaseOrderLine>,
        payNow: Double,
        fundingMode: String,
        cashierId: String? = null,
        cashierName: String? = null
    ): String {
        val stamp = now()
        val total = lines.sumOf { it.qty * it.unitCost }
        val po = PurchaseOrder(
            businessId = businessId,
            ref = genRef("PO"),
            supplierId = supplierId,
            supplierName = supplierName,
            status = "placed",
            notes = notes,
            eta = eta,
            createdAt = stamp,
            sentAt = stamp,
            updatedAt = stamp
        )
        db.withTransaction {
            val onHand = cashOnHandOnce(businessId).coerceAtLeast(0.0)
            val want = payNow.coerceIn(0.0, total)
            val cash: Double
            val capital: Double
            when (fundingMode) {
                "capital" -> { cash = 0.0; capital = want }
                "available" -> { cash = want.coerceAtMost(onHand); capital = 0.0 }
                "none" -> { cash = 0.0; capital = 0.0 }
                else -> { cash = want; capital = 0.0 }   // "cash"
            }
            val payable = (total - cash - capital).coerceAtLeast(0.0)

            // Persist the header + its lines (stamping the new PO id), pre-creating /
            // bumping PENDING stock for every line flagged to stock on arrival.
            val savedLines = ArrayList<PurchaseOrderLine>(lines.size)
            for (raw in lines) {
                var line = raw.copy(poId = po.id)
                if (line.stockOnArrival) {
                    val existing = line.itemId?.let { itemDao.getById(it) }
                    if (existing != null) {
                        // Existing product: show the incoming qty as a pending addition;
                        // current sellable stock stays exactly as it is.
                        itemDao.upsert(
                            existing.copy(
                                pendingQty = existing.pendingQty + line.qty,
                                updatedAt = stamp, pendingSync = true
                            )
                        )
                    } else if (line.name.isNotBlank()) {
                        // Brand-new product: create it PENDING (not sellable) with the
                        // incoming qty; sell price / cost / type seed the catalog row.
                        val measured = line.productType == "measured"
                        val newItem = Item(
                            businessId = businessId,
                            name = line.name.trim(),
                            sku = line.sku?.trim()?.ifBlank { null },
                            productType = line.productType,
                            price = line.sellPrice ?: 0.0,
                            pricePerUnit = if (measured) (line.sellPrice ?: 0.0) else 0.0,
                            cost = line.unitCost,
                            trackStock = true,
                            stockQty = 0.0,
                            pendingQty = line.qty,
                            pendingNew = true,
                            updatedAt = stamp
                        )
                        itemDao.upsert(newItem)
                        line = line.copy(itemId = newItem.id)
                    }
                }
                savedLines += line
            }
            poDao.insert(
                po.copy(cashPaid = cash, capitalPaid = capital, payableRemainder = payable)
            )
            poDao.insertLines(savedLines)

            if (cash > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "purchase", amount = -cash,
                        source = fundingLabel(fundingMode),
                        note = "PO ${po.ref} · ${supplierName.ifBlank { "supplier" }}",
                        refType = "purchase_order", refId = po.id,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            auditDao.insert(
                AuditEntry(
                    businessId = businessId, action = "purchase_created",
                    entityType = "purchase_order", entityId = po.id,
                    summary = "Placed ${po.ref} ${fmtMoney(total)}" +
                        (if (cash > CENT) " · paid ${fmtMoney(cash)} cash" else "") +
                        (if (capital > CENT) " · ${fmtMoney(capital)} owner" else "") +
                        (if (payable > CENT) " · ${fmtMoney(payable)} owed" else ""),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        return po.id
    }

    /** Draft → placed. Stamps sentAt. (New orders are created already "placed".) */
    suspend fun markPoSent(poId: String) {
        val po = poDao.getById(poId) ?: return
        val stamp = now()
        poDao.upsert(po.copy(status = "placed", sentAt = stamp, updatedAt = stamp))
    }

    /**
     * Cancel an open PO: clears any PENDING stock it created (a brand-new pending product
     * is tombstoned; an existing item's pending addition is rolled back) and zeroes the
     * supplier payable. Cash already paid is deliberately left spent (historical). Atomic.
     */
    suspend fun cancelPo(poId: String) {
        val po = poDao.getById(poId) ?: return
        val stamp = now()
        db.withTransaction {
            for (line in poDao.linesForPo(poId)) {
                if (!line.stockOnArrival) continue
                val outstanding = (line.qty - (line.receivedQty ?: 0.0)).coerceAtLeast(0.0)
                if (outstanding <= 0.0) continue
                val item = line.itemId?.let { itemDao.getById(it) } ?: continue
                if (item.pendingNew && (line.receivedQty ?: 0.0) <= 0.0) {
                    // Never arrived and exists only for this PO → remove the pending product.
                    itemDao.softDelete(item.id, stamp)
                } else {
                    itemDao.upsert(
                        item.copy(
                            pendingQty = (item.pendingQty - outstanding).coerceAtLeast(0.0),
                            updatedAt = stamp, pendingSync = true
                        )
                    )
                }
            }
            poDao.upsert(po.copy(status = "cancelled", payableRemainder = 0.0, updatedAt = stamp))
        }
    }

    /**
     * Confirm ARRIVAL of a PO (§10.5), moving each line's still-pending quantity into real
     * sellable stock: increments the linked item's on-hand (measured or unit), draws its
     * `pendingQty` back down, and clears the `pendingNew` block so a new product becomes
     * sellable. [receivedByLine] optionally supplies a per-line arrived quantity (partial
     * arrival); a null/absent entry arrives the whole outstanding quantity. Recomputes the
     * PO status to `partial` or `received`. Atomic.
     */
    suspend fun confirmArrival(
        poId: String,
        receivedByLine: Map<String, Double>? = null,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        val po = poDao.getById(poId) ?: return
        if (po.status == "received" || po.status == "cancelled") return
        val stamp = now()
        db.withTransaction {
            val lines = poDao.linesForPo(poId)
            var allDone = true
            for (line in lines) {
                val already = line.receivedQty ?: 0.0
                val outstanding = (line.qty - already).coerceAtLeast(0.0)
                if (outstanding <= 0.0) continue
                // Default: arrive everything still outstanding on this line.
                val recv = (receivedByLine?.get(line.id) ?: outstanding)
                    .coerceIn(0.0, outstanding)
                if (recv <= 0.0) { allDone = false; continue }

                if (line.stockOnArrival && line.itemId != null) {
                    val item = itemDao.getById(line.itemId)
                    if (item != null) {
                        val measured = item.isMeasured
                        itemDao.upsert(
                            item.copy(
                                stockQty = if (measured) item.stockQty else item.stockQty + recv,
                                stockMeasured = if (measured) item.stockMeasured + recv else item.stockMeasured,
                                pendingQty = (item.pendingQty - recv).coerceAtLeast(0.0),
                                pendingNew = false,          // first arrival makes it sellable
                                updatedAt = stamp, pendingSync = true
                            )
                        )
                    }
                }
                val newReceived = already + recv
                poDao.upsertLine(line.copy(receivedQty = newReceived))
                if (newReceived + CENT < line.qty) allDone = false
            }
            poDao.upsert(
                po.copy(
                    status = if (allDone) "received" else "partial",
                    receivedAt = if (allDone) stamp else po.receivedAt,
                    updatedAt = stamp
                )
            )
            auditDao.insert(
                AuditEntry(
                    businessId = po.businessId, action = "purchase_received",
                    entityType = "purchase_order", entityId = po.id,
                    summary = (if (allDone) "Received ${po.ref}" else "Partly received ${po.ref}"),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    /**
     * Settle (part of) a supplier's accounts payable on a PO from cash-on-hand. [mode]:
     * "cash" pays the whole remaining balance; "available" pays only what cash there is
     * (the rest stays owed). Drains the drawer via a `cash_txns` "purchase" row (still an
     * asset purchase, never an expense). Atomic. No-op if nothing is owed / no cash.
     */
    suspend fun recordSupplierPayment(
        poId: String, mode: String, cashierId: String? = null, cashierName: String? = null
    ) {
        val po = poDao.getById(poId) ?: return
        val remainder = po.payableRemainder
        if (remainder <= CENT) return
        val stamp = now()
        db.withTransaction {
            val onHand = cashOnHandOnce(po.businessId).coerceAtLeast(0.0)
            val pay = if (mode == "available") remainder.coerceAtMost(onHand) else remainder
            if (pay <= CENT) return@withTransaction
            cashTxnDao.insert(
                CashTxn(
                    businessId = po.businessId, type = "purchase", amount = -pay,
                    source = "supplier payment", note = "PO ${po.ref} balance",
                    refType = "purchase_order", refId = po.id,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            poDao.upsert(
                po.copy(
                    cashPaid = po.cashPaid + pay,
                    payableRemainder = (remainder - pay).coerceAtLeast(0.0),
                    updatedAt = stamp
                )
            )
            auditDao.insert(
                AuditEntry(
                    businessId = po.businessId, action = "purchase_payment",
                    entityType = "purchase_order", entityId = po.id,
                    summary = "Paid ${fmtMoney(pay)} to ${po.supplierName.ifBlank { "supplier" }} on ${po.ref}",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
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
     *  - Overpayment is change. The cashier records how much was actually handed back
     *    ([changeGiven], stored in [SaleEntity.changeDue]). This is reconciled BOTH ways:
     *    an under-give (given < due) books the remainder as `change_owed` (shop owes the
     *    customer); an over-give (given > due) books the excess as `credit_owed` (the
     *    customer owes the shop). Both attach to [customer]. For a walk-in (no customer)
     *    with an imbalance, [tillDiscrepancy]=true instead records an un-attributed
     *    `till_short`/`till_over` entry in the audit log.
     */
    suspend fun checkout(
        businessId: String,
        cart: List<CartLine>,
        payments: List<Tender> = emptyList(),
        discount: Double = 0.0,
        note: String? = null,
        customer: Customer? = null,
        onCredit: Boolean = false,
        changeGiven: Double = 0.0,
        tillDiscrepancy: Boolean = false,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0,
        totalRounding: Double = 0.0,
        cashierId: String? = null,
        cashierName: String? = null
    ): SaleWithLines {
        // Money math (pure + unit-tested in SaleMathTest): discount clamped to the
        // goods value, VAT charged on the DISCOUNTED base, total is tax-inclusive.
        val subtotal = cart.sumOf { it.lineSubtotal }       // pre-tax goods value (gross)
        // Per-item discounts fold into the sale's discount total, so VAT is charged on
        // the fully discounted base and reports count them as discounts given.
        val perItemDiscount = cart.sumOf { it.lineDiscountApplied }
        // Per-item markups fold into the sale's markup total, so VAT is charged on the
        // marked-up base and the receipt can print the markup on its own line.
        val perItemMarkup = cart.sumOf { it.lineMarkupApplied }
        val totals = computeSaleTotals(subtotal, discount + perItemDiscount, vatEnabled, vatPercent, perItemMarkup)
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
        // Overpayment is change. The cashier records how much was ACTUALLY handed back
        // ([changeGiven]) — which is deliberately NOT clamped to the change due, because
        // both directions of error are real and must be reconciled:
        //  - under-given (given < due)  => the shop still owes the customer  → change_owed
        //  - over-given  (given > due)  => the customer now owes the shop    → credit_owed
        val change = (amountPaid - total).coerceAtLeast(0.0)
        val changeGivenActual = changeGiven.coerceAtLeast(0.0)
        val changeOwed = (change - changeGivenActual).coerceAtLeast(0.0)   // shop owes customer
        val overGiven = (changeGivenActual - change).coerceAtLeast(0.0)    // customer owes shop
        val ledgerChangeOwed = changeOwed > CENT && customer != null
        val ledgerOverGiven = overGiven > CENT && customer != null

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
            markupTotal = perItemMarkup,
            taxTotal = taxTotal,
            total = total,
            paymentMethod = method,
            // Tendered only models the cash-handed-over case (single cash tender).
            tendered = if (singleCash) tenders.first().amount else null,
            amountPaid = amountPaid,
            // Change actually handed over now, and the remainder the shop still owes.
            changeDue = changeGivenActual.takeIf { it > 0.0 },
            changeOwed = changeOwed.takeIf { it > 0.0 },
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
                lineDiscount = c.lineDiscountApplied,
                lineMarkup = c.lineMarkupApplied,
                // lineTotal stays the GROSS goods value; the discount/markup print on
                // their own lines and are already folded into the sale totals.
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
            // Cash-on-hand ledger (B3): only PHYSICAL cash moves the drawer. Net cash in
            // = cash tenders received − change actually handed back. Mobile-money/card
            // tenders don't touch cash-on-hand. Skip a zero net (e.g. a card-only sale).
            val cashTendered = tenders.filter { it.method == "cash" }.sumOf { it.amount }
            val netCashIn = cashTendered - changeGivenActual
            if (kotlin.math.abs(netCashIn) > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "sale", amount = netCashIn,
                        source = "cash", note = "Sale #$receiptNo",
                        refType = "sale", refId = saleId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // Draw down stock for any tracked items in the cart and log the movement.
            // Untracked items and ad-hoc lines (no matching item row) are left alone.
            // Clamped at zero so a mis-counted shelf never shows negative on hand.
            // Box lines consume qty * boxSize units.
            for (c in cart) {
                val item = itemDao.getById(c.itemId) ?: continue
                if (!item.trackStock) continue
                // Measured items draw down the DECIMAL stockMeasured by the sold quantity
                // and never touch the integer box/piece stockQty; everything else draws
                // whole units (qty * boxSize) off stockQty. Both clamp at zero.
                val measured = item.productType == "measured" || c.measured
                val drawn = if (measured) c.qty else c.stockUnits
                val remaining = ((if (measured) item.stockMeasured else item.stockQty) - drawn)
                    .coerceAtLeast(0.0)
                val updated = if (measured) item.copy(stockMeasured = remaining, updatedAt = stamp, pendingSync = true)
                else item.copy(stockQty = remaining, updatedAt = stamp, pendingSync = true)
                itemDao.upsert(updated)
                movementDao.insert(
                    StockMovement(
                        businessId = businessId,
                        itemId = item.id,
                        type = "sale",
                        delta = -drawn,
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
            // Change we couldn't hand back in full => owed to the customer (we-owe).
            if (ledgerChangeOwed) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer!!.id,
                        saleId = saleId,
                        type = "change_owed",
                        amount = changeOwed,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // Cashier handed back MORE change than was due => the customer owes the shop
            // the excess. Books as ordinary DEBT (credit_owed), noted so it's traceable.
            if (ledgerOverGiven) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer!!.id,
                        saleId = saleId,
                        type = "credit_owed",
                        amount = overGiven,
                        note = "Over-given change",
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // Walk-in (no customer) with an imbalance the cashier chose NOT to attach to
            // anyone => record it as an un-attributed TILL discrepancy in the audit log so
            // an admin sees it. Over-given change leaves the drawer SHORT; unclaimed change
            // the shop kept leaves the drawer OVER. Written in the same transaction.
            if (customer == null && tillDiscrepancy) {
                if (overGiven > CENT) {
                    auditDao.insert(
                        AuditEntry(
                            businessId = businessId,
                            action = "till_short",
                            entityType = "sale",
                            entityId = saleId,
                            summary = "Till shortage ${fmtMoney(overGiven)} — over-given change on walk-in sale #$receiptNo",
                            meta = fmtMoney(overGiven),
                            createdBy = cashierId,
                            createdByName = cashierName
                        )
                    )
                }
                if (changeOwed > CENT) {
                    auditDao.insert(
                        AuditEntry(
                            businessId = businessId,
                            action = "till_over",
                            entityType = "sale",
                            entityId = saleId,
                            summary = "Till overage ${fmtMoney(changeOwed)} — unclaimed change on walk-in sale #$receiptNo",
                            meta = fmtMoney(changeOwed),
                            createdBy = cashierId,
                            createdByName = cashierName
                        )
                    )
                }
            }
        }
        return SaleWithLines(savedSale, lines)
    }

    // ---- quotes (§1.2 parity) --------------------------------------------

    /**
     * Save a QUOTE: a `sales` row with status='quote' — no payment taken, no stock
     * drawn down, no ledger. It carries full priced totals (so the printed/PDF quote
     * shows the amounts) and a [validUntil] lapse date from the shop's quote-validity
     * setting. status='quote' keeps it out of every sales report and the sync push
     * (both filter status='completed'), so a quote is a purely local document the
     * cashier prints or shares. Returns it for the receipt/PDF like [checkout].
     */
    suspend fun saveQuote(
        businessId: String,
        cart: List<CartLine>,
        discount: Double = 0.0,
        note: String? = null,
        customer: Customer? = null,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0,
        validityDays: Int = 7,
        cashierId: String? = null,
        cashierName: String? = null
    ): SaleWithLines {
        val subtotal = cart.sumOf { it.lineSubtotal }
        val perItemDiscount = cart.sumOf { it.lineDiscountApplied }
        val perItemMarkup = cart.sumOf { it.lineMarkupApplied }
        val totals = computeSaleTotals(subtotal, discount + perItemDiscount, vatEnabled, vatPercent, perItemMarkup)
        val saleId = newId()
        val stamp = now()
        val validUntil = stamp + validityDays.coerceAtLeast(0) * 24L * 60 * 60 * 1000
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            receiptNo = genRef("QTN"),
            status = "quote",
            validUntil = validUntil,
            subtotal = subtotal,
            discountTotal = totals.discount,
            markupTotal = perItemMarkup,
            taxTotal = totals.taxTotal,
            total = totals.total,
            paymentMethod = "quote",
            amountPaid = 0.0,
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
                name = if (c.mode == "box") "${c.name} (Box of ${c.unitsPerLine})" else c.name,
                qty = c.qty,
                unitPrice = c.unitPrice,
                lineDiscount = c.lineDiscountApplied,
                lineMarkup = c.lineMarkupApplied,
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
        return SaleWithLines(sale, lines)
    }

    /** Saved quotes (newest first) for the Quotes list. */
    fun quotesFlow(businessId: String): Flow<List<SaleEntity>> =
        saleDao.observeQuotes(businessId)

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
        val perItemDiscount = cart.sumOf { it.lineDiscountApplied }
        val perItemMarkup = cart.sumOf { it.lineMarkupApplied }
        val parkedDiscount = (discount + perItemDiscount).coerceIn(0.0, subtotal)
        val sale = SaleEntity(
            id = saleId,
            businessId = businessId,
            status = "parked",
            subtotal = subtotal,
            discountTotal = parkedDiscount,
            markupTotal = perItemMarkup,
            total = subtotal - parkedDiscount + perItemMarkup,
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
                lineDiscount = c.lineDiscountApplied,
                lineMarkup = c.lineMarkupApplied,
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
        val cart = saleLinesToCart(saleId)
        db.withTransaction {
            saleDao.hardDeletePayments(saleId)
            saleDao.hardDeleteLines(saleId)
            saleDao.hardDeleteSale(saleId)
        }
        return cart
    }

    /**
     * Rebuild an in-memory cart from a saved sale/quote's lines WITHOUT deleting it —
     * used to turn an accepted quote into a live sale (the quote record is kept).
     * Ad-hoc lines with no item id are dropped (they can't be re-priced).
     */
    suspend fun saleLinesToCart(saleId: String): List<CartLine> =
        saleDao.linesForSale(saleId).mapNotNull { l ->
            val itemId = l.itemId ?: return@mapNotNull null
            CartLine(
                itemId = itemId,
                name = l.name,
                unitPrice = l.unitPrice,
                taxRate = 0.0,                      // VAT is business-level now
                qty = l.qty,
                mode = l.mode,
                unitsPerLine = l.unitsPerLine,
                lineDiscount = l.lineDiscount,
                lineMarkup = l.lineMarkup
            )
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

    /**
     * Undo a verification made in error (§6). The reverse of [verifyMobileMoney]:
     *  - `debt`  — soft-deletes the `credit_paid` ledger row it created (via the
     *              receipt's [MobileMoneyReceipt.appliedCreditTxnId]), which restores
     *              the customer's balance; the row is kept (deleted=1) for audit/sync.
     *  - `sale`  — nothing was applied to a ledger, so just clears the flag.
     * The receipt returns to the queue: `needs_verification` if it still has a matched
     * customer, otherwise `unmatched`. No-op unless the receipt is currently verified.
     */
    suspend fun unverifyMobileMoney(
        receiptId: String,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        val r = mobileMoneyDao.getById(receiptId) ?: return
        if (r.status != "verified") return
        val stamp = now()
        db.withTransaction {
            r.appliedCreditTxnId?.let { txnId ->
                creditDao.getById(txnId)?.let { txn ->
                    creditDao.upsert(txn.copy(deleted = true, updatedAt = stamp, pendingSync = true))
                }
            }
            mobileMoneyDao.upsert(
                r.copy(
                    status = if (r.matchedCustomerId != null) "needs_verification" else "unmatched",
                    purpose = null,
                    appliedCreditTxnId = null,
                    updatedAt = stamp,
                    pendingSync = true
                )
            )
        }
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
            agingRows = customerDao.allForBusiness(businessId).let { custs ->
                DebtAging.compute(
                    creditDao.allForBusinessOnce(businessId),
                    custs.associate { it.id to it.name },
                    nowMs,
                    custs.associate { it.id to it.creditLimit },
                )
            },
            openPurchaseOrders = poDao.openWithEtaOnce(businessId),
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
        // Opening cash float (B3): the starting cash-on-hand the admin sets.
        const val KEY_OPENING_FLOAT = "cash_opening_float"
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
    val unitsPerLine: Int = 1,
    /** True for a measured (unit-priced) line: [qty] is a decimal quantity of
     *  [unitLabel] and [unitPrice] is the price of one unit. Its shelf draw-down hits
     *  the item's decimal `stockMeasured`, not the integer `stockQty`. */
    val measured: Boolean = false,
    /** Unit label for a measured line (kg, L, m, …); blank for non-measured lines. */
    val unitLabel: String = "",
    /** Fixed currency amount knocked off this whole line (0 = none). Clamped to the
     *  line's gross value below, so it can never make a line go negative. */
    val lineDiscount: Double = 0.0,
    /** Fixed currency amount ADDED to this whole line (0 = none). The mirror of
     *  [lineDiscount] — there is no cap, so it just floors at 0. */
    val lineMarkup: Double = 0.0
) {
    /** Pre-discount goods value of the line. */
    val lineGross: Double get() = unitPrice * qty
    /** The per-item discount actually applied, never more than the goods are worth. */
    val lineDiscountApplied: Double get() = lineDiscount.coerceIn(0.0, lineGross)
    /** The per-item markup actually applied (never negative; no upper cap). */
    val lineMarkupApplied: Double get() = lineMarkup.coerceAtLeast(0.0)
    /** Gross goods value — feeds the sale subtotal and the discount-approval base
     *  (per-item discounts are folded into the sale's discount total at checkout). */
    val lineSubtotal: Double get() = lineGross
    /** Goods value after the per-item discount, plus any per-item markup. */
    val lineNet: Double get() = lineGross - lineDiscountApplied + lineMarkupApplied
    val lineTax: Double get() = lineNet * (taxRate / 100.0)
    /** What the customer pays for this line (net of the per-item discount, plus tax). */
    val lineTotal: Double get() = lineNet + lineTax
    /** Total stock units this line removes from the shelf. */
    val stockUnits: Double get() = qty * unitsPerLine
    /** Identity for cart merge/update: the same item at a different price mode is a separate line. */
    val lineKey: String get() = "$itemId#$mode"
}
