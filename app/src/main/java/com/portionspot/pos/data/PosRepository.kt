package com.portionspot.pos.data

import androidx.room.withTransaction
import com.portionspot.pos.device.phoneKey
import com.portionspot.pos.notify.NotifSnapshot
import com.portionspot.pos.notify.NotifThresholds
import com.portionspot.pos.notify.NotificationEngine
import com.portionspot.pos.sms.ParsedPayment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Half-a-cent tolerance for money comparisons (guards Double rounding on totals). */
private const val CENT = 0.005

/** Categories [NotificationEngine] computes and therefore owns the lifecycle of. Rows
 *  in any other category (e.g. "expenses") are written directly and are never swept. */
private val ENGINE_CATEGORIES = setOf("inventory", "sales", "system", "payments", "refunds")

/**
 * The single gateway between the UI and Room. (Supabase sync will be added
 * here in a later milestone — push dirty rows where updatedAt > cursor.)
 */
class PosRepository(private val db: PosDatabase) {

    private val businessDao = db.businessDao()
    private val itemDao = db.itemDao()
    private val itemAttributeDao = db.itemAttributeDao()
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
    private val staffRequestDao = db.staffRequestDao()
    private val dayCloseDao = db.dayCloseDao()
    private val outsideFundDao = db.outsideFundDao()
    private val cashSessionDao = db.cashSessionDao()
    private val staffDao = db.staffDao()

    /**
     * Fire-and-forget hook the DI container points at [com.portionspot.pos.sync.SyncManager.requestSync],
     * so a freshly-created/updated admin notification reaches the OTHER phones in seconds
     * instead of on the ~15-minute worker cycle. Debounced inside the SyncManager, so
     * calling it per sweep is safe. Null in tests / before wiring — never required.
     */
    var onSyncWorthyChange: ((String) -> Unit)? = null

    private fun nudgeSync(reason: String) {
        runCatching { onSyncWorthyChange?.invoke(reason) }
    }

    /**
     * The ONLY way an audit entry is written. The row is born `pendingSync = true`
     * (entity default), so it queues for upload, and the write nudges a debounced sync
     * so the owner's admin phone sees a cashier's receipt edit / till discrepancy /
     * void in seconds instead of on the ~15-minute worker cycle. Append-only: entries
     * are never updated or deleted after this point.
     */
    private suspend fun logAudit(entry: AuditEntry) {
        auditDao.insert(entry)
        nudgeSync("audit")
    }

    // ---- Local key/value settings (theme, etc. — never synced) -------------

    suspend fun getSetting(key: String): String? = settingDao.get(key)

    suspend fun putSetting(key: String, value: String) =
        settingDao.put(Setting(key, value))

    // ---- Staff roster mirror (cloud-owned; see StaffMember) -----------------

    /**
     * Mirror a `staff` row the admin console has just written to the cloud.
     *
     * ★ [member].businessId must be the CLOUD business id. The PIN hash is salted with it
     * (see [StaffMember.pinShopId]), so a row filed under the device's own local uuid
     * derives a digest that can never match — a correct PIN refused forever, with nothing
     * anywhere to say why.
     *
     * `pendingSync = false`: the cloud already has this row, because the console wrote it
     * there FIRST and only mirrors here on success. There is nothing to push back.
     */
    suspend fun mirrorStaff(member: StaffMember) =
        staffDao.upsert(member.copy(pendingSync = false))

    /** The local mirror of one staff row, or null if this device hasn't pulled it yet. */
    suspend fun staffById(id: String): StaffMember? = staffDao.getById(id)

    val businessFlow: Flow<Business?> = businessDao.observe()

    fun itemsFlow(businessId: String): Flow<List<Item>> =
        itemDao.observeForBusiness(businessId)

    /** Every live item tag for the shop — the cars each part fits. Feeds catalogue search
     *  (see [tagIndex]), the fitment line on a product card, and the editor's typeahead
     *  (see [attrVocabulary]). */
    fun itemAttributesFlow(businessId: String): Flow<List<ItemAttribute>> =
        itemAttributeDao.observeForBusiness(businessId)

    /** One item's live tags, key then value, for the attribute editor. */
    fun itemAttributesForItemFlow(itemId: String): Flow<List<ItemAttribute>> =
        itemAttributeDao.observeForItem(itemId)

    // ---- Item attributes: add / edit / remove ------------------------------
    //
    // ★ Every write here goes through [attributeId]. The id is the tag's IDENTITY, derived
    // from (business, item, canonical key, canonical value), and it is what makes two
    // offline phones tagging the same part converge on one row instead of racing into a
    // duplicate the cloud's live unique index refuses — which fails the whole push batch,
    // not just the tag. Never write one of these rows with [newId].
    //
    // The id is derived from the LOCAL business id, which is not the shop's. That is
    // deliberate and handled at the boundary: `PosSyncEngine.rekeyPendingAttributes` re-mints
    // an unsent row under the shop's id just before it is uploaded. Reading the cloud id
    // here would put a sync concept into the repository for no gain — a till in local mode
    // has no cloud id to read anyway.

    /**
     * Add (or resurrect) one tag on an item.
     *
     * A removed tag is a TOMBSTONE, not a missing row, so adding "Vezel" back after
     * removing it derives the same id and this upsert simply flips `deleted` off with a
     * fresh stamp. That is the whole reason the id is derived: with a random one the
     * re-add would be a second live row for the same fitment, which the cloud rejects.
     *
     * Blank key or blank value is a no-op — a tag with no value is not a fitment, it is a
     * row that matches every search for the empty string.
     */
    suspend fun addItemAttribute(businessId: String, itemId: String, key: String, value: String) {
        val k = key.trim()
        val v = value.trim()
        if (k.isEmpty() || v.isEmpty()) return
        itemAttributeDao.upsert(
            ItemAttribute(
                id = attributeId(businessId, itemId, k, v),
                businessId = businessId,
                itemId = itemId,
                key = k,
                value = v,
                updatedAt = now(),
                deleted = false,
                pendingSync = true,
            )
        )
        nudgeSync("itemAttribute")
    }

    /**
     * Change a tag's key or value.
     *
     * ★ This is NOT an in-place edit, and it cannot be. The key and the value ARE the row's
     * identity — change either and the derived id changes with it — so an edit is a
     * tombstone of the old tag plus an upsert of the new one, committed together. Writing
     * the new text onto the old row would leave a row whose id no longer describes its
     * contents, and the next device to add that same tag would mint the correct id and
     * create a duplicate beside it.
     *
     * When only the casing or the spacing changed, the derived id is unchanged and this
     * collapses to a single upsert that stores the new spelling — no tombstone, because
     * there is nothing to tombstone.
     */
    suspend fun editItemAttribute(row: ItemAttribute, key: String, value: String) {
        val k = key.trim()
        val v = value.trim()
        if (k.isEmpty() || v.isEmpty()) return
        val newId = attributeId(row.businessId, row.itemId, k, v)
        val stamp = now()
        db.withTransaction {
            if (newId != row.id) {
                itemAttributeDao.upsert(
                    row.copy(deleted = true, updatedAt = stamp, pendingSync = true)
                )
            }
            itemAttributeDao.upsert(
                ItemAttribute(
                    id = newId,
                    businessId = row.businessId,
                    itemId = row.itemId,
                    key = k,
                    value = v,
                    updatedAt = stamp,
                    deleted = false,
                    pendingSync = true,
                )
            )
        }
        nudgeSync("itemAttribute")
    }

    /**
     * Remove one tag — as a tombstone, never as a delete.
     *
     * The row has to survive so the removal can travel: a fitment that simply vanished from
     * this phone is a fitment every other phone still holds, and the till keeps offering a
     * part for a car the shop has decided it does not fit.
     */
    suspend fun removeItemAttribute(id: String) {
        val row = itemAttributeDao.getById(id) ?: return
        if (row.deleted) return
        itemAttributeDao.upsert(row.copy(deleted = true, updatedAt = now(), pendingSync = true))
        nudgeSync("itemAttribute")
    }

    /** Real receipts only — completed and refunded. Quotes and parked carts live in
     *  `sales` too and are deliberately excluded; see [SaleDao.observeRecent]. */
    fun recentSalesFlow(businessId: String): Flow<List<SaleEntity>> =
        saleDao.observeRecent(businessId)

    fun salesForCustomerFlow(customerId: String): Flow<List<SaleEntity>> =
        saleDao.observeSalesForCustomer(customerId)

    /** Completed sales that carried a discount, newest first — admin "Discounts given". */
    fun discountedSalesFlow(businessId: String): Flow<List<SaleEntity>> =
        saleDao.observeDiscountedSales(businessId)

    // No `takingsSinceFlow`: "what came in today" is a CASH-BASIS question and belongs to
    // [CashBasis], fed by [cashBasisSalesFlow] + [creditLedgerFlow]. See the note in
    // [SaleDao] where the billed-total query used to live.

    fun saleCountSinceFlow(businessId: String, since: Long): Flow<Int> =
        saleDao.observeCountSince(businessId, since)

    // ---- reports ----------------------------------------------------------

    fun salesSummaryFlow(businessId: String, from: Long, to: Long): Flow<SalesSummary> =
        saleDao.observeSummary(businessId, from, to)

    /** The per-item markup handed back with returned goods in this window. Pair with
     *  [SalesSummary.markup] to report what the counter actually kept. */
    fun refundedMarkupFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeRefundedMarkup(businessId, from, to)

    fun methodBreakdownFlow(businessId: String, from: Long, to: Long): Flow<List<MethodBreakdown>> =
        saleDao.observeMethodBreakdown(businessId, from, to)

    /** How many sales in the window are fully refunded — subtract from the sale count
     *  for a net "live sales" figure (prompt §5). */
    fun fullyRefundedCountFlow(businessId: String, from: Long, to: Long): Flow<Int> =
        saleDao.observeFullyRefundedCount(businessId, from, to)

    // ---- dashboard --------------------------------------------------------

    fun topProductsFlow(businessId: String, from: Long, to: Long, limit: Int = 5): Flow<List<TopProduct>> =
        saleDao.observeTopProducts(businessId, from, to, limit)

    /**
     * Gross profit for the window: each sale's margin resolved by [computeSaleMargin],
     * then summed. Per sale rather than in one SQL aggregate because the whole-sale
     * discount has to be shared pro-rata with that sale's own costed lines — a shop-wide
     * SUM cannot express it, and the old query simply left the discount out.
     */
    fun grossProfitFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeSaleMargins(businessId, from, to)
            .map { rows -> rows.sumOf { it.margin().profit } }

    /** Refunded value per sale (for the Receipts "refunded" badge). Presentation only. */
    fun refundedBySaleFlow(businessId: String): Flow<List<SaleRefundSum>> =
        refundDao.observeRefundedBySale(businessId)

    /** Revenue of costed lines only — the honest denominator for the margin figure.
     *  Same rows and same allocation as [grossProfitFlow], so `profit / this` measures
     *  one consistent set of lines with one consistent discount treatment. */
    fun costedRevenueFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        saleDao.observeSaleMargins(businessId, from, to)
            .map { rows -> rows.sumOf { it.margin().costedRevenue } }

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

    /**
     * Discard this device's copy of the shop and re-adopt it from the database.
     *
     * ★ THE CASH TABLES WERE MISSING FROM THIS LIST, AND THAT IS HOW A TEST DATABASE GOT
     * INTO THE REAL ONE.
     *
     * On 2026-08-20 the app was repointed from the throwaway project to production and
     * reset. Everything below was wiped and re-pulled correctly. `cash_txns`,
     * `cash_sessions`, `day_closes` and `outside_funds` were not in the list, so they
     * survived the reset with `pendingSync = 1` still set — and the next push uploaded a
     * week of the throwaway's shifts and drawer movements into the shop's real books: 19
     * cash movements and 7 closed days, including a counted drawer of $318 attributed to
     * a cashier who never worked that day. Nothing errored. The reset reported success.
     *
     * The omission was not survivable in principle either: the whole premise of this
     * function is that local rows are discarded and the shared ones come back from the
     * cloud. A table left behind by a reset does not "keep its data" — it keeps data
     * belonging to a DIFFERENT database and then pushes it into this one.
     *
     * ★ `day_closes` and `outside_funds` are LOCAL-ONLY (see SyncConfig) and do not come
     * back from any pull. Wiping them destroys them, and that is the intended behaviour
     * here rather than an accepted cost: after a repoint they describe a shop this device
     * is no longer pointed at, and keeping them is what leaves a day close dated 1 January
     * 1970 sitting in the Cash screen — a `closedAt` of 0 that no cloud row will ever
     * correct because no cloud row owns it.
     *
     * `staff` is deliberately NOT wiped. It was never part of the contamination (the
     * roster is pull-only), and clearing it while offline would leave nobody able to sign
     * in to the till that just reset itself.
     */
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
            staffRequestDao.wipe(bid)
            itemDao.wipe(bid)
            // With the items, not after them: a reset that dropped the catalogue and kept
            // its tags would leave every fitment pointing at an item id that no longer
            // exists — invisible, un-searchable, and re-inserted alongside the fresh pull.
            itemAttributeDao.wipe(bid)
            customerDao.wipe(bid)
            // ── The drawer. See the note above: these four are why this exists. ──
            // Order is immaterial (no foreign keys), but they go together: a device left
            // holding sessions without their movements would report a shift whose cash it
            // cannot account for, which is worse than holding neither.
            cashTxnDao.wipe(bid)
            cashSessionDao.wipe(bid)
            dayCloseDao.wipe(bid)
            outsideFundDao.wipe(bid)
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
     *
     * ══ Changing the stock figure here LOGS A MOVEMENT, and has to ══
     * `items.stockQty` is a CACHE of the movement ledger — [PosSyncEngine] rebuilds it
     * after every pull as `stockBaseQty + Σ deltas since the baseline`. So writing a new
     * figure onto the row and stopping there does not restock anything: the number shows
     * until the next sync and is then computed away, because no movement ever said the
     * stock arrived. Restocking on the till simply did not work, and it failed by
     * reverting quietly rather than by refusing.
     *
     * It also never left the device. The catalogue is PULL-ONLY — the web owns products
     * and this app has no `items` push — so the ledger is the ONLY channel a till has for
     * telling the shop that stock moved. A movement both survives the recompute and
     * reaches the other tills; the cached figure does neither.
     *
     * The delta is measured against what this device currently holds, which is what the
     * recompute last settled on, so applying it lands exactly on the figure that was
     * typed. A brand-new product logs its opening count instead: that item has no
     * baseline yet ([Item.stockBaseAt] is 0), so the recompute leaves it alone and the
     * entry is there to answer where the stock came from — and to be correctly ignored
     * later, as pre-baseline, if the web ever gives the product a figure of its own.
     */
    suspend fun saveItem(item: Item, cashierId: String? = null, cashierName: String? = null) {
        val sku = item.sku?.trim()?.ifBlank { null }
        val canonicalId = sku
            ?.let { itemDao.getBySku(item.businessId, it) }
            ?.takeIf { it.id != item.id }
            ?.id
        val target = if (canonicalId != null) item.copy(id = canonicalId) else item
        val stamp = now()
        db.withTransaction {
            // Read BEFORE the upsert — afterwards the old figure is gone and the delta
            // would always compute as zero.
            val prior = itemDao.getById(target.id)
            itemDao.upsert(target.copy(sku = sku, updatedAt = stamp, pendingSync = true))

            // Which entry this edit owes, if any — see [stockEditFor] for the rules.
            stockEditFor(prior, target)?.let { edit ->
                movementDao.insert(
                    StockMovement(
                        businessId = target.businessId,
                        itemId = target.id,
                        type = edit.type,
                        delta = edit.delta,
                        balanceAfter = edit.balanceAfter,
                        note = edit.note,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        // ★ STRICTLY AFTER the row, and the millisecond matters.
                        //
                        // On-hand is the shop's figure plus movements stamped strictly
                        // after the instant that figure was true, and the item row's
                        // `client_updated_at` IS that instant. Stamping both at `stamp`
                        // put the movement exactly ON the baseline, where it is treated
                        // as the change that PRODUCED the figure and is not replayed.
                        //
                        // On the device that made the edit nothing went wrong — the pull
                        // sees an unchanged cloud figure and keeps its existing baseline,
                        // so the movement still counts. But any OTHER device adopts the
                        // pushed row as a fresh baseline and drops the movement sitting on
                        // it. Measured on the shop's own data: `Hamburger` was created on
                        // the till with 3, went up with stock_qty 0 (the catalogue push
                        // never sends stock), and its `restock +3` was stamped to the
                        // exact millisecond of the row — so every other phone would have
                        // read it as 0 in stock. The opening count of a till-created
                        // product was invisible to the whole shop.
                        createdAt = stamp + 1
                    )
                )
            }
        }
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
     * via [recordChangePayment]) instead of driving the debt negative.
     *
     * ══ CASH-ON-HAND: the drawer moves, and it used not to ══
     * This whole chain wrote credit-ledger rows and NOTHING else. So a customer settling a
     * debt in cash put money in the till and the app's cash-on-hand did not move — on any
     * phone. At day close the drawer counted OVER by every repayment ever collected, and
     * by the owner's rule [closeDay] writes that difference permanently as a `variance`,
     * which is a hit to profit. A day that balanced perfectly was recorded as a windfall.
     *
     * So the repayment now has to say HOW it was paid, and only [method] `cash` writes a
     * [CashTxn] — a positive one, into [CashLocation.TILL], because the money arrives at
     * the counter exactly like a sale's does. An EcoCash or card repayment settles the same
     * debt and moves no drawer, the same rule [checkout] applies to its tenders and
     * [createRefund] applies to its payouts. The amount booked is what was HANDED OVER,
     * including any overpayment: that cash is physically in the till, and the `change_owed`
     * row is what records that it has to go back out again.
     *
     * ══ PUSHED, deliberately ══
     * The row carries `refType = "customer"`, which [cashMovementCountedElsewhere] does NOT
     * exclude, so it goes up to `cash_movements` as a `pay_in`. That is right and it is the
     * convention [recordChangePayment] already set for the mirror-image payout: the shared
     * cash-up derives a SALE's cash from the tenders and a REFUND's from the payouts, but it
     * has no other representation of a debt repayment at all, so without this row a shop
     * counting its drawer on the web is short by every debt collected on a phone.
     *
     * All rows for one repayment commit together.
     */
    suspend fun recordRepayment(
        businessId: String,
        customerId: String,
        amount: Double,
        note: String? = null,
        method: String = "cash",
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0) return
        val debt = creditDao.balanceOnce(customerId)
        val plan = planRepayment(amount, debt, method)
        val stamp = now()
        db.withTransaction {
            if (plan.paid > 0.0) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "credit_paid",
                        amount = plan.paid,
                        note = note,
                        method = method,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            if (plan.excess > 0.0) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "change_owed",
                        amount = plan.excess,
                        note = if (plan.paid > 0.0) "Overpayment" else note ?: "Overpayment",
                        method = method,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // Cash only, and inside the same transaction as the ledger rows: a crash that
            // recorded the debt as settled without the money arriving (or the reverse)
            // would be a discrepancy nobody could later reconstruct.
            if (plan.cashIn > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "credit_payment", amount = plan.cashIn,
                        location = CashLocation.TILL,
                        source = "cash", note = note ?: "Debt repayment",
                        refType = "customer", refId = customerId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
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
     *  check the moment an expense is being posted.
     *
     *  ★ MEANING UNCHANGED by the till/safe split: this is still ALL the cash the shop
     *  holds, because it sums both locations and a transfer between them nets to zero.
     *  It equals [tillBalanceOnce] + [safeBalanceOnce], always. */
    suspend fun cashOnHandOnce(businessId: String): Double =
        openingFloat(businessId) + cashTxnDao.movementsSumOnce(businessId)

    // ══════════════════════════════════════════════════════════════════════════
    //  TILL AND SAFE — two on-site cash locations, one ledger
    // ══════════════════════════════════════════════════════════════════════════
    //
    //  The shop has NO BANK. Its money sits in exactly two places:
    //    • the TILL — a working float, only enough to make change;
    //    • the SAFE — the day's takings.
    //
    //  Both balances come off the SAME append-only `cash_txns` ledger via
    //  [CashTxn.location], so they can never drift from each other or from the
    //  combined cash-on-hand figure every existing caller reads. The opening float
    //  belongs to the TILL (the float IS the drawer's starting money); the safe starts
    //  empty and is filled by transfers.
    //
    //  Routing of the movements that already existed:
    //    sale cash, change payouts, refund payouts → TILL (they happen at the counter)
    //    expenses / purchases                      → whichever location paid (§4)
    //
    //  NOTHING here runs on a timer. Closing the day and topping up the float are
    //  BUTTONS, pressed when the person decides (§1).

    /** Live TILL balance = opening float + net of the movements booked to the till. */
    fun tillBalanceFlow(businessId: String): Flow<Double> =
        cashTxnDao.observeLocationSum(businessId, CashLocation.TILL)

    /** Live SAFE balance = net of the movements booked to the safe (starts empty). */
    fun safeBalanceFlow(businessId: String): Flow<Double> =
        cashTxnDao.observeLocationSum(businessId, CashLocation.SAFE)

    /** Movements in one location, newest first — the till or safe statement. */
    fun cashTxnsForLocationFlow(businessId: String, location: String): Flow<List<CashTxn>> =
        cashTxnDao.observeForLocation(businessId, CashLocation.of(location))

    /** Till balance right now (the float top-up and funding decisions need it). */
    suspend fun tillBalanceOnce(businessId: String): Double =
        openingFloat(businessId) + cashTxnDao.locationSumOnce(businessId, CashLocation.TILL)

    /** Safe balance right now. */
    suspend fun safeBalanceOnce(businessId: String): Double =
        cashTxnDao.locationSumOnce(businessId, CashLocation.SAFE)

    /** The owner's float target — how much change money to keep in the drawer. */
    suspend fun floatTarget(): Double =
        settingDao.get(KEY_FLOAT_TARGET)?.toDoubleOrNull() ?: DEFAULT_FLOAT_TARGET

    /** Set the float target. Settable INLINE in the close / top-up flows, by design. */
    suspend fun setFloatTarget(amount: Double) =
        putSetting(KEY_FLOAT_TARGET, amount.coerceAtLeast(0.0).toString())

    /** How far a close may miss before a note is required. */
    suspend fun varianceNoteThreshold(): Double =
        settingDao.get(KEY_VARIANCE_NOTE_THRESHOLD)?.toDoubleOrNull()
            ?: DEFAULT_VARIANCE_NOTE_THRESHOLD

    /** Set it here and it becomes the SHOP's threshold, not this phone's — see
     *  [ShopPolicy]. Routed through [putShopPolicy] so the change is stamped and travels;
     *  writing the key directly would leave the other tills on the old number for ever. */
    suspend fun setVarianceNoteThreshold(amount: Double) =
        putShopPolicy(
            shopPolicy().copy(varianceNoteThreshold = amount.coerceAtLeast(0.0)),
            localEdit = true,
        )

    // ── Shop policy: the money rules that belong to the business ──────────────
    // Stored in the same local key/value settings as everything else, because that is
    // where the till reads them from and nothing about syncing them changes that. What
    // changes is that they are now a MIRROR of `businesses.max_item_discount` /
    // `.discount_threshold` / `.variance_note_threshold` rather than the only copy.

    /** The three shared rules as this device currently enforces them. */
    suspend fun shopPolicy(): ShopPolicy = ShopPolicy(
        maxItemDiscount = settingDao.get(KEY_MAX_ITEM_DISCOUNT)?.toDoubleOrNull()
            ?: DEFAULT_MAX_ITEM_DISCOUNT,
        discountThresholdPct = settingDao.get(KEY_DISCOUNT_THRESHOLD)?.toDoubleOrNull()
            ?: DEFAULT_DISCOUNT_THRESHOLD_PCT,
        varianceNoteThreshold = settingDao.get(KEY_VARIANCE_NOTE_THRESHOLD)?.toDoubleOrNull()
            ?: DEFAULT_VARIANCE_NOTE_THRESHOLD,
    )

    /**
     * When a PERSON last changed one of the three on THIS device. 0 for never.
     *
     * Not "when the settings were last written" — an adopted value from the cloud must not
     * bump it, or the device would immediately push back what it has just been told and
     * the two clients would trade the same policy for ever, each pass looking like a real
     * change to the other.
     */
    suspend fun shopPolicyChangedAt(): Long =
        settingDao.get(KEY_SHOP_POLICY_CHANGED_AT)?.toLongOrNull() ?: 0L

    /**
     * Persist the three rules.
     *
     * [localEdit] is the whole distinction: true when a person moved the figure on this
     * phone (stamp the clock, so the next sync sends it up), false when the sync engine is
     * writing down what the shared row already says (leave the clock alone). The clock is
     * stamped only if a value actually MOVED, so re-saving the settings screen without
     * touching a discount does not hand this device a win over an edit the owner made in
     * the browser five minutes ago.
     */
    suspend fun putShopPolicy(next: ShopPolicy, localEdit: Boolean) {
        val changed = next != shopPolicy()
        putSetting(KEY_MAX_ITEM_DISCOUNT, next.maxItemDiscount.toString())
        putSetting(KEY_DISCOUNT_THRESHOLD, next.discountThresholdPct.toString())
        putSetting(KEY_VARIANCE_NOTE_THRESHOLD, next.varianceNoteThreshold.toString())
        if (localEdit && changed) putSetting(KEY_SHOP_POLICY_CHANGED_AT, now().toString())
    }

    /**
     * Move cash between the shop's own two locations. Written as a matching PAIR of rows
     * — `transfer_out` (negative, [from]) and `transfer_in` (positive, [to]) — for one
     * reason: moving your own money between your own pockets is NEITHER INCOME NOR
     * EXPENSE, so the pair must sum to exactly zero and leave cash-on-hand untouched.
     * Both rows are inserted in the caller's transaction; a half-written transfer that
     * created or destroyed money is therefore impossible.
     */
    private suspend fun writeTransfer(
        businessId: String,
        from: String,
        to: String,
        amount: Double,
        note: String,
        refType: String? = null,
        refId: String? = null,
        cashierId: String? = null,
        cashierName: String? = null,
        stamp: Long = now()
    ) {
        if (amount <= CENT || from == to) return
        cashTxnDao.insertAll(
            listOf(
                CashTxn(
                    businessId = businessId, type = "transfer_out", amount = -amount,
                    location = from, source = CashLocation.label(from), note = note,
                    refType = refType, refId = refId,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                ),
                CashTxn(
                    businessId = businessId, type = "transfer_in", amount = amount,
                    location = to, source = CashLocation.label(from), note = note,
                    refType = refType, refId = refId,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
        )
    }

    // ---- §2 CLOSE THE DAY (on command, never on a timer) ------------------

    /** What the close-day sheet needs before the owner types anything. */
    data class DayCloseProposal(
        val expectedTill: Double,
        val floatTarget: Double,
        val safeBalance: Double,
        val noteThreshold: Double
    )

    /** Read the figures the close-of-day flow opens with. Pure read — closes nothing. */
    suspend fun dayCloseProposal(businessId: String): DayCloseProposal = DayCloseProposal(
        expectedTill = tillBalanceOnce(businessId),
        floatTarget = floatTarget(),
        safeBalance = safeBalanceOnce(businessId),
        noteThreshold = varianceNoteThreshold()
    )

    /**
     * CLOSE THE DAY (§2). Pressed by a person who has just counted the drawer and
     * physically moved the excess — never fired on a schedule.
     *
     * Everything commits in ONE transaction, so the ledger can never end up trued-up but
     * not transferred (or vice versa):
     *
     *  1. TRUE UP. `variance = counted − expected`, written as a signed `variance`
     *     [CashTxn] against the TILL. **The counted figure wins** — that is the owner's
     *     explicit choice, so after this row the ledger says exactly what was in the
     *     drawer. A shortage is a NEGATIVE row (money genuinely gone), an overage
     *     positive.
     *  2. MOVE THE EXCESS. [moveToSafe] leaves the till and lands in the safe as a
     *     matching transfer pair — both balances move, the combined total does not.
     *  3. RECORD IT PERMANENTLY. A [DayClose] row keeps expected / counted / variance /
     *     moved / target plus WHO closed, so the owner can see a repeat offender.
     *  4. AUDIT. A shortage or overage also lands in the append-only audit trail.
     *
     * The variance is a LOSS (or gain) against profit and is reported as its own "cash
     * short/over" line — see [cashVarianceFlow]. It is deliberately NOT folded into gross
     * profit: a shortage is a shortage, not a margin problem.
     *
     * A close is also the CLOSING HALF OF THE DAY'S SHIFT. Since a shift here is a trading
     * day, the count, the float left in the till and the amount moved to the safe are
     * written onto that day's [CashSession] in the same transaction — which is how a shop
     * reading the shared `cash_sessions` table sees what was counted without `day_closes`
     * ever needing to become a cloud table.
     *
     * IDEMPOTENT. Closing the same trading day twice returns the close already recorded and
     * changes nothing: the second run must not move the takings into the safe a second time.
     *
     * [note] is required by the caller when `|variance|` exceeds [varianceNoteThreshold];
     * below that the owner is not made to type. Returns the recorded close (including the
     * one already on file for a day closed before), or null when there is nothing to record.
     */
    suspend fun closeDay(
        businessId: String,
        countedCash: Double,
        moveToSafe: Double,
        floatTarget: Double,
        note: String?,
        dayStart: Long = startOfDay(now()),
        cashierId: String? = null,
        cashierName: String? = null
    ): DayClose? {
        // ★★ ONE COUNT PER TRADING DAY. THIS IS THE OWNER'S DECISION, NOT A LIMITATION.
        //
        // He was asked directly and ruled that a day is counted once, and that he will tell
        // the cashiers so. A second-count / spot-count path was considered and deliberately
        // NOT built. Do not "fix" this into allowing a re-close.
        //
        // The reason it cannot simply be relaxed: closing is not only a record. It writes a
        // variance row AND physically moves the excess takings from the till to the safe. A
        // second close moves cash that has already left the drawer — the till then reads
        // negative against a safe holding money twice — and the variance it invents lands on
        // profit. A retap, or the owner's phone and a cashier's both closing the same
        // shop-day, is enough to cause it.
        //
        // Keyed on the trading day, so it holds even for a day that was never traded through
        // the till and therefore has no shift to check. The UI reads the same fact ahead of
        // time (see [dayCloseForFlow]) so nobody counts a drawer for a button that will
        // silently do nothing.
        dayCloseDao.forDayOnce(businessId, dayStart)?.let { return it }
        // The shift for the day BEING CLOSED — resolved from [dayStart], never from now.
        // Closing yesterday late at night must close YESTERDAY's shift; resolving by the
        // clock would write last night's count onto this morning's takings.
        // [pickDaySession], not [pickSurvivingSession]: if ANY session of this day carries a
        // count the day is settled, and this must be handed that row so the guard below sees
        // it. An unmergeable leftover session ranking ahead of the counted one is how the
        // takings get moved into the safe twice on a day that was already counted.
        val daySession =
            pickDaySession(cashSessionDao.forDayOnce(businessId, dayStart, startOfNextDay(dayStart)))
        // Already counted: the DayClose row above is normally what catches a repeat, but a
        // shift counted from another device and pulled down gets here first. Same rule.
        if (daySession?.countedCash != null) return null
        val stamp = now()
        val expected = tillBalanceOnce(businessId)
        val counted = countedCash.coerceAtLeast(0.0)
        val variance = counted - expected
        // Never move more than was actually counted, and never a negative amount.
        val moved = moveToSafe.coerceIn(0.0, counted)
        val target = floatTarget.coerceAtLeast(0.0)
        val close = DayClose(
            businessId = businessId, dayStart = dayStart,
            expectedCash = expected, countedCash = counted, variance = variance,
            movedToSafe = moved, floatTarget = target,
            note = note?.trim()?.ifBlank { null },
            closedBy = cashierId, closedByName = cashierName,
            closedAt = stamp, updatedAt = stamp
        )
        db.withTransaction {
            // 1. True the till up to what was physically counted.
            if (kotlin.math.abs(variance) > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "variance", amount = variance,
                        location = CashLocation.TILL, source = "day close",
                        note = (if (variance < 0) "Cash short at close" else "Cash over at close") +
                            (close.note?.let { " — $it" } ?: ""),
                        refType = "day_close", refId = close.id,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // 2. Move the excess takings out of the drawer and into the safe.
            writeTransfer(
                businessId = businessId,
                from = CashLocation.TILL, to = CashLocation.SAFE, amount = moved,
                note = "Day close — takings to safe",
                refType = "day_close", refId = close.id,
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
            // 3. The permanent, per-cashier record.
            dayCloseDao.insert(close)
            // 3b. THE SAME COUNT, ONTO THE DAY'S SHIFT — in the same transaction as the
            // record above, because a recorded close whose shift stayed open is exactly the
            // state the one-open-per-business index rejects on the next push. `day_closes`
            // stays local-only and detailed; `cash_sessions` is the summary the shop and
            // the web POS share, and these four columns are the half of it a close fills in.
            // `variance` is never written: it is GENERATED on the cloud and derived here.
            // ★ A DAY WITH NO SHIFT STILL HAS TO BE COUNTABLE EXACTLY ONCE. [planDayClose]
            // returns null when the day has no session, and the close then wrote its
            // `day_closes` row, moved the cash to the safe, and told no other device
            // anything at all — because `day_closes` is local-only and the shared summary
            // rides on the shift row. The second phone saw an uncounted day and offered to
            // count it again, which is the exact failure the one-count-per-day rule exists
            // to prevent, and it is silent on both devices.
            //
            // So the day's shift is MINTED here rather than skipped. Born CLOSED and dated
            // to the day it belongs to, it cannot trip the one-open-session-per-business
            // index, and it carries the count up on the next push like any other.
            val sessionForClose = daySession ?: CashSession(
                businessId = businessId,
                status = SessionStatus.CLOSED,
                openedAt = dayStart,
                openedBy = cashierId,
                openedByName = cashierName,
                note = DAY_BACKFILL_NOTE,
                updatedAt = stamp,
            ).also { cashSessionDao.upsert(it) }
            planDayClose(
                session = sessionForClose,
                closedAt = stamp,
                countedCash = counted,
                expectedCash = expected,
                movedToSafe = moved,
                floatTarget = target,
                closedBy = cashierId,
                closedByName = cashierName,
                note = close.note,
            )?.let { c ->
                cashSessionDao.closeWithCount(
                    id = c.id,
                    closedAt = c.closedAt,
                    closedBy = c.closedBy,
                    closedByName = c.closedByName,
                    countedCash = c.countedCash,
                    expectedCash = c.expectedCash,
                    movedToSafe = c.movedToSafe,
                    floatTarget = c.floatTarget,
                    note = c.note,
                    at = stamp,
                )
            }
            // 4. Audit trail (a discrepancy is a sensitive event).
            if (kotlin.math.abs(variance) > CENT) {
                logAudit(
                    AuditEntry(
                        businessId = businessId,
                        action = if (variance < 0) "till_short" else "till_over",
                        entityType = "day_close", entityId = close.id,
                        summary = (if (variance < 0) "Till short " else "Till over ") +
                            fmtMoney(kotlin.math.abs(variance)) +
                            " at close (counted ${fmtMoney(counted)} vs ${fmtMoney(expected)})" +
                            (close.note?.let { " — $it" } ?: ""),
                        meta = fmtMoney(variance),
                        createdBy = cashierId, createdByName = cashierName
                    )
                )
            }
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "day_closed",
                    entityType = "day_close", entityId = close.id,
                    summary = "Day closed · counted ${fmtMoney(counted)} · " +
                        "${fmtMoney(moved)} to safe · ${fmtMoney(target)} left in till",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        setFloatTarget(target)
        notifyCashEvent(
            businessId = businessId,
            dedupeKey = "dayclose:${close.id}",
            title = "Day closed",
            body = "Counted ${fmtMoney(counted)}" +
                (if (kotlin.math.abs(variance) > CENT)
                    " · ${if (variance < 0) "short" else "over"} ${fmtMoney(kotlin.math.abs(variance))}"
                else " · balanced") +
                " · ${fmtMoney(moved)} moved to the safe" +
                (cashierName?.let { " · by $it" } ?: ""),
            severity = if (kotlin.math.abs(variance) > CENT) "warn" else "info",
            refType = "day_close", refId = close.id, at = stamp
        )
        return close
    }

    /** The close history, newest first — per-cashier, so a repeat offender shows up. */
    fun dayClosesFlow(businessId: String): Flow<List<DayClose>> =
        dayCloseDao.observeForBusiness(businessId)

    /** The most recent close, for the "last closed …" line on the cash card. */
    fun latestDayCloseFlow(businessId: String): Flow<DayClose?> =
        dayCloseDao.observeLatest(businessId)

    /**
     * The close recorded for one trading day, observed — non-null means that day has been
     * counted and cannot be counted again (see [closeDay]). What the cash screen reads so
     * it can say so BEFORE anyone counts the drawer.
     *
     * ★ FALLS BACK TO THE SHIFT, because `day_closes` IS A LOCAL TABLE AND NEVER SYNCS.
     * Asking it alone means each phone only knows about closes IT performed. On two phones
     * (device test §G, 17 Aug): the cashier counted the drawer and moved the takings to the
     * safe; the owner's phone synced, saw no local close row, and cheerfully offered to
     * count the same day again. [closeDay] itself was never in danger — it re-checks the
     * pulled shift and returns null — but a money button that silently does nothing is the
     * exact failure the dialog's own comment says is worse than an error.
     *
     * The day's [CashSession] IS shared: `counted_cash`, `expected_cash`, `moved_to_safe`
     * and `float_target` all go up at the close and come back down on every device's pull.
     * That is a complete [DayClose] minus its id, so one is SYNTHESISED for display. It is
     * never inserted and never pushed — it is this device reporting what another device
     * did, and the `day_closes` row stays where the count was actually taken.
     */
    fun dayCloseForFlow(businessId: String, dayStart: Long): Flow<DayClose?> =
        combine(
            dayCloseDao.observeForDay(businessId, dayStart),
            cashSessionDao.observeForDay(businessId, dayStart, startOfNextDay(dayStart)),
        ) { local, sessions -> local ?: countedElsewhere(businessId, dayStart, sessions) }

    /** Same, read once — the authoritative check the close dialog makes when it opens. */
    suspend fun dayCloseFor(businessId: String, dayStart: Long): DayClose? =
        dayCloseDao.forDayOnce(businessId, dayStart)
            ?: countedElsewhere(
                businessId, dayStart,
                cashSessionDao.forDayOnce(businessId, dayStart, startOfNextDay(dayStart)),
            )

    /**
     * A day counted on ANOTHER device, seen through this one, as the [DayClose] the screens
     * already know how to render. Null when nobody has counted the day yet.
     *
     * Resolved with [pickDaySession] rather than "the first row", so two phones that both
     * opened a shift before seeing each other read the count off the SAME session. It asks
     * for the COUNTED session and not merely the surviving one on purpose: a day holding two
     * sessions stops being mergeable the moment a close closes one of them, and the leftover
     * uncounted session — opened first, so ranked first — would otherwise hide a count this
     * device is holding and offer the day up to be counted again. See [pickDaySession].
     *
     * `variance` is recomputed from counted − expected rather than carried: it is GENERATED
     * on the cloud and derived everywhere else, and a third copy is how a shop ends up with
     * two answers to "how short were we".
     */
    private fun countedElsewhere(
        businessId: String,
        dayStart: Long,
        sessions: List<CashSession>,
    ): DayClose? {
        val s = pickDaySession(sessions) ?: return null
        val counted = s.countedCash ?: return null
        val expected = s.expectedCash ?: 0.0
        return DayClose(
            // Keyed off the shift so repeated reads produce a stable identity, and so it can
            // never be mistaken for a locally-recorded close.
            id = s.id,
            businessId = businessId,
            dayStart = dayStart,
            expectedCash = expected,
            countedCash = counted,
            variance = counted - expected,
            movedToSafe = s.movedToSafe,
            floatTarget = s.floatTarget,
            note = s.note,
            closedBy = s.closedBy,
            closedByName = s.closedByName,
            closedAt = s.closedAt ?: s.updatedAt,
            updatedAt = s.updatedAt,
            // Never pushed: this row does not exist in `day_closes` and must not be made to.
            pendingSync = false,
        )
    }

    /** Signed CASH SHORT/OVER for a window — the profit line day-close variances feed. */
    fun cashVarianceFlow(businessId: String, from: Long, to: Long): Flow<Double> =
        cashTxnDao.observeVarianceSum(businessId, from, to)

    /**
     * THE Z-REPORT CASH-UP for `[from, to)` — see `ExpectedDrawer.kt` for the three
     * defects this exists to close and why the drawer is shop-wide rather than per till.
     *
     * A ONE-SHOT SNAPSHOT, not a flow, and that is the honest shape: a cash-up is a
     * measurement taken at an instant and compared against notes someone is holding. A
     * live figure that moved while the drawer was being counted would be worse than
     * useless — the count would be measured against a total that has since changed.
     *
     * Five reads, four of them uncapped and windowed only by time:
     *  - OPENING is the till ledger cut off at [from] ([CashTxnDao.locationSumBefore]) plus
     *    the device's opening float, i.e. exactly [tillBalanceOnce] as of the window's edge.
     *    It is read, never typed.
     *  - RECEIPTS and TENDERS come from `sales` + `sale_payments`, filtered on
     *    [RECEIPT_STATUSES] — so a split tender contributes its cash part and a
     *    part-refunded receipt still counts.
     *  - PAYOUTS come from `refund_payments`, dated by the payout.
     *  - MOVEMENTS are the till's own ledger with the sale/refund rows held back, because
     *    those are rebuilt from the tenders above.
     *
     * This deliberately does NOT feed [closeDay]. The close writes a permanent variance and
     * must measure against the ledger balance the rest of the money model is built on;
     * this is the independent cross-check that says whether the ledger has caught up.
     */
    suspend fun expectedDrawerFor(businessId: String, from: Long, to: Long): ExpectedDrawer =
        expectedDrawer(
            opening = openingFloat(businessId) +
                cashTxnDao.locationSumBefore(businessId, CashLocation.TILL, from),
            receipts = saleDao.drawerReceipts(businessId, from, to, RECEIPT_STATUSES),
            tenders = paymentDao.drawerTenders(businessId, from, to, RECEIPT_STATUSES),
            payouts = refundDao.drawerPayouts(businessId, from, to),
            movements = cashTxnDao.drawerMovements(businessId, CashLocation.TILL, from, to),
        )

    // ---- §3 TOP UP THE FLOAT (on command) --------------------------------

    /** What the top-up sheet opens with: where the till is against its target. */
    data class FloatTopUp(
        val till: Double,
        val target: Double,
        val safe: Double
    ) {
        /** How far the till is below target — 0 when it needs nothing. */
        val shortfall: Double get() = (target - till).coerceAtLeast(0.0)
        /** What the safe can actually supply toward that shortfall. */
        val available: Double get() = shortfall.coerceAtMost(safe.coerceAtLeast(0.0))
    }

    suspend fun floatTopUpProposal(businessId: String): FloatTopUp = FloatTopUp(
        till = tillBalanceOnce(businessId),
        target = floatTarget(),
        safe = safeBalanceOnce(businessId)
    )

    /**
     * TOP UP THE FLOAT (§3) — move [amount] from the safe into the till, on command.
     * A pure transfer: safe down, till up, combined cash unchanged. Audited and notified
     * so the owner learns their safe was opened even if someone else pressed it.
     * Returns the amount actually moved (clamped to what the safe holds).
     */
    suspend fun topUpFloat(
        businessId: String,
        amount: Double,
        cashierId: String? = null,
        cashierName: String? = null
    ): Double {
        val stamp = now()
        val safe = safeBalanceOnce(businessId).coerceAtLeast(0.0)
        val moved = amount.coerceIn(0.0, safe)
        if (moved <= CENT) return 0.0
        db.withTransaction {
            writeTransfer(
                businessId = businessId,
                from = CashLocation.SAFE, to = CashLocation.TILL, amount = moved,
                note = "Float top-up",
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "float_topup", entityType = "cash",
                    summary = "Moved ${fmtMoney(moved)} from the safe into the till",
                    meta = fmtMoney(moved),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        notifyCashEvent(
            businessId = businessId,
            dedupeKey = "floattopup:$stamp",
            title = "Float topped up",
            body = "${fmtMoney(moved)} moved from the safe into the till" +
                (cashierName?.let { " · by $it" } ?: ""),
            severity = "info", refType = "cash", refId = null, at = stamp
        )
        return moved
    }

    /**
     * Persist + push an admin-facing alert for a completed cash event (§1: "when a close
     * or a float top-up COMPLETES, notify the admin"). `pushedAt` is stamped so the phone
     * that PERFORMED the action does not buzz itself; the owner's admin phone fires it
     * once after the pull, exactly like the staff-request channel.
     */
    private suspend fun notifyCashEvent(
        businessId: String,
        dedupeKey: String,
        title: String,
        body: String,
        severity: String,
        refType: String?,
        refId: String?,
        at: Long
    ) {
        upsertNotificationByKey(
            AppNotification(
                businessId = businessId, category = "cash", severity = severity,
                title = title, body = body, dedupeKey = dedupeKey, audience = "admin",
                refType = refType, refId = refId,
                eventAt = at, createdAt = at, pushedAt = at,
                updatedAt = at, pendingSync = true
            )
        )
        nudgeSync("cash-event")
    }

    // Start-of-day now lives in DaySession.kt as a top-level `startOfDay(at)`, because the
    // shift, the dashboard and the end-of-day screen all have to measure a day from the
    // same edge. A private copy here was the second of three; a third would have made a
    // day's takings land in one bucket on one screen and another on the next.

    // ─────────────────────── THE TRADING DAY IS THE SHIFT ───────────────────────

    /**
     * The id of the shift every sale, refund and cash movement made right now belongs to —
     * creating today's if the shop has not traded yet.
     *
     * Safe to call on EVERY sale, and meant to be: there is no button that opens a shift,
     * so the first sale of the day is what opens it. Rolling the day over is part of the
     * same call for the same reason — the moment a sale arrives is the moment the app can
     * be sure which day it is trading in.
     *
     * ★ THE ROLLOVER CLOSE COMES FIRST AND IS NOT OPTIONAL. The shared database allows one
     * open session per BUSINESS, so yesterday's forgotten session physically blocks today's
     * from ever reaching the cloud. Closing it is not tidiness; it is the precondition for
     * today existing at all.
     *
     * ★ CREATION IS LAZY ON PURPOSE. A shop that opens the app and sells nothing gets no
     * session. Manufacturing one on launch would have every idle phone in the shop racing
     * to create the day's row every morning, and every one of those races is a rejected
     * push. Only a real sale opens a day.
     *
     * Two phones offline from each other can still both create one; that is what
     * [planSessionMerge] settles, and it settles it the same way on both because both
     * stamp `openedAt` at the day's midnight (see [DaySessionOpen.openedAt]).
     */
    suspend fun currentDaySessionId(businessId: String, at: Long = now()): String {
        var opened = false
        val id = db.withTransaction {
            val roll = planDayRollover(cashSessionDao.openSessions(businessId), at)
            val stamp = now()
            for (c in roll.closes) cashSessionDao.closeForDay(c.id, c.note, c.closedAt, stamp)
            roll.current?.id ?: run {
                // `roll.open` is non-null whenever `current` is; the fallback is belt and
                // braces so a day can never fail to open over a nullability technicality.
                val openAt = roll.open?.openedAt ?: startOfDay(at)
                val session = CashSession(
                    businessId = businessId,
                    status = SessionStatus.OPEN,
                    openedAt = openAt,
                    // No tillCode: the calendar opened this shift, not a device. Recording
                    // whichever phone happened to ring the first sale would read as "this
                    // till's shift" for a period that belongs to the whole shop.
                    updatedAt = stamp,
                )
                cashSessionDao.upsert(session)
                opened = true
                session.id
            }
        }
        // Outside the transaction: a sync nudge is fire-and-forget and has no business
        // holding a write lock open while it schedules work.
        if (opened) nudgeSync("day-session")
        return id
    }

    /** Close out any day that has ended, without opening today's. What the sync engine and
     *  app start need: the blocking session gone, but no session minted for a shop that has
     *  not sold anything yet. */
    suspend fun rolloverDaySessions(businessId: String, at: Long = now()) {
        val roll = planDayRollover(cashSessionDao.openSessions(businessId), at)
        if (roll.closes.isEmpty()) return
        val stamp = now()
        db.withTransaction {
            for (c in roll.closes) cashSessionDao.closeForDay(c.id, c.note, c.closedAt, stamp)
        }
        nudgeSync("day-rollover")
    }

    /** The shift standing for one trading day — what the end-of-day screen reads so that
     *  picking a day shows that day's shift, not "the shift that happens to be open". */
    fun daySessionFlow(businessId: String, dayStart: Long): Flow<CashSession?> =
        cashSessionDao.observeForDay(businessId, dayStart, startOfNextDay(dayStart))
            .map { rows -> pickSurvivingSession(rows) }

    /**
     * Attach sales and refunds written before shifts existed to the day they happened on.
     *
     * A one-time repair that is safe to re-run, and re-running it is the design rather than
     * a concession: it only ever selects rows whose `sessionId IS NULL`, so a second pass
     * over a repaired shop selects nothing. It is also capped per pass — attaching a session
     * marks the row dirty, and a long history repaired in one go would queue every sale the
     * shop has ever made for re-upload at once.
     *
     * ★ It creates only CLOSED sessions, and only for days strictly before today. That is
     * what makes it safe to run against the live database at all: it is structurally unable
     * to add a second OPEN session and trip the one-open-per-business index. Rows dated
     * today wait for a real sale to open today's session and are picked up next pass.
     *
     * A row whose date cannot be read is left alone. Filing it under "probably today" would
     * put someone else's takings into today's count, and a cash-up that is quietly wrong is
     * worse than one that is visibly incomplete.
     *
     * Returns how many rows were attached.
     */
    suspend fun backfillDaySessions(businessId: String, at: Long = now(), limit: Int = 500): Int {
        rolloverDaySessions(businessId, at)
        val today = startOfDay(at)
        val attached = db.withTransaction {
            val sales = cashSessionDao.salesWithoutSession(businessId, limit)
            val refunds = cashSessionDao.refundsWithoutSession(businessId, limit)
            if (sales.isEmpty() && refunds.isEmpty()) return@withTransaction 0
            // Which set an id came from decides which table the UPDATE lands on. Ids are
            // uuids, so a sale and a refund can never collide in this set.
            val saleIds = sales.mapTo(HashSet<String>()) { it.id }
            val byDay = indexSessionsByDay(cashSessionDao.allLive(businessId))
            val plan = planDayBackfill(sales + refunds, byDay, today)
            val stamp = now()
            val resolved = byDay.toMutableMap()
            for (day in plan.daysToCreate) {
                val session = CashSession(
                    businessId = businessId,
                    status = SessionStatus.CLOSED,
                    openedAt = day,
                    closedAt = endOfDay(day),
                    note = DAY_BACKFILL_NOTE,
                    updatedAt = stamp,
                )
                cashSessionDao.upsert(session)
                resolved[day] = session.id
            }
            var n = 0
            for ((day, ids) in plan.assignments) {
                val sessionId = resolved[day] ?: continue
                val forSales = ids.filter { it in saleIds }
                val forRefunds = ids.filter { it !in saleIds }
                if (forSales.isNotEmpty()) {
                    cashSessionDao.attachSalesToSession(sessionId, forSales)
                    n += forSales.size
                }
                if (forRefunds.isNotEmpty()) {
                    cashSessionDao.attachRefundsToSession(sessionId, forRefunds)
                    n += forRefunds.size
                }
            }
            n
        }
        if (attached > 0) nudgeSync("day-session-backfill")
        return attached
    }

    // ─────────────── ANOTHER TILL'S CASH IS STILL IN THIS DRAWER ───────────────

    /**
     * Bring the cash ledger into line with every sale and refund the device knows about,
     * whichever till originated them.
     *
     * A pull writes another phone's sale, its lines and its tenders, and no [CashTxn] —
     * every cash-ledger insert in this class is a local user action. With one shared drawer
     * and two phones that leaves each phone's cash-on-hand counting only its own takings,
     * and the day close then measures the REAL drawer against a figure covering half of it.
     * The difference is written permanently as a `variance`, which is a hit to profit. See
     * [planCashMirror] for the full reasoning.
     *
     * Runs on every sync pass and is meant to: it reconciles BALANCES rather than reacting
     * to events, so a second run finds nothing to do, and a device that pulled a refund and
     * later pulls the void of it converges on the same ledger as the device that voided it.
     * It is also the backfill — rows already sitting on a device from earlier pulls are
     * simply the first pass's work.
     *
     * ★ MUST RUN AFTER THE TENDERS ARE DOWN. A sale's cash is `sale_payments` less the
     * change given, so reconciling before [PosSyncEngine.pullSalePayments] would read a
     * sale with no tenders yet, book zero, and then have to correct itself on the next pass
     * — briefly showing a drawer short by a real sale.
     *
     * Returns the number of correcting rows written (0 on a device already in agreement).
     */
    suspend fun reconcilePulledCash(businessId: String): Int {
        val stamp = now()
        val plans = db.withTransaction {
            // Sales: expected-set keys only. "Absent from the expected set" covers a parked
            // sale, a quote and a sale mid-edit as well as a deleted one, and reversing real
            // takings on the strength of an absence is a way to lose money nobody would
            // trace back to here. A sale that needs reversing is reversed where it is voided.
            val sales = planCashMirror(
                refType = "sale",
                expected = saleDao.expectedCashBySale(businessId),
                existing = cashTxnDao.sumsByRef(businessId, "sale"),
                reverseOrphans = false,
            )
            // Refunds: orphans ARE reversed. A void soft-deletes the refund and the voiding
            // phone writes its own reversing row, so a phone that had mirrored the payout
            // must write the same one or that cash stays out of its drawer forever.
            val refunds = planCashMirror(
                refType = "refund",
                expected = refundDao.expectedCashByRefund(businessId),
                existing = cashTxnDao.sumsByRef(businessId, "refund"),
                reverseOrphans = true,
            )
            sales + refunds
        }
        if (plans.isEmpty()) return 0
        db.withTransaction {
            for (p in plans) {
                val isSale = p.refType == "sale"
                // The row reads like the local one it stands in for: same type vocabulary,
                // same TILL location, same `refType`/`refId` — which is also what keeps it
                // out of the push (see [cashMovementCountedElsewhere]). A correction running
                // AGAINST the natural direction of its kind is an `adjust`, matching what
                // [voidRefund] already writes for the same situation.
                val naturalDirection = if (isSale) p.amount > 0 else p.amount < 0
                val label = p.label ?: p.refId.take(8)
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId,
                        type = if (naturalDirection) p.refType else "adjust",
                        amount = p.amount,
                        // A sale and a refund both happen at the counter (§4).
                        location = CashLocation.TILL,
                        source = "cash",
                        // Says outright that this is a correction and where it came from.
                        // A drawer figure the owner cannot explain is one they stop trusting.
                        note = (if (isSale) "Sale #$label" else "Refund on #$label") +
                            " (reconciled from another till)",
                        refType = p.refType, refId = p.refId,
                        // Attribution follows the sale, not the phone that happened to sync.
                        createdBy = p.createdBy, createdByName = p.createdByName,
                        // ★ The SOURCE row's own instant, so the movement lands in the
                        // trading day it belongs to. Stamped with the sync time instead, a
                        // phone coming back online after midnight would file yesterday's
                        // takings in today's cash-up. A reversal has no source row left to
                        // read, so it honestly carries now.
                        createdAt = if (p.at > 0L) p.at else stamp,
                        updatedAt = stamp,
                    )
                )
            }
        }
        return plans.size
    }

    // ---- §4 FUNDING WATERFALL: TILL → SAFE → OUTSIDE FUNDS → abort -------

    /**
     * How one payment is going to be funded. The four parts always sum to the amount
     * being paid, so a plan can be checked by addition and never silently loses money.
     *
     *  - [fromTill] / [fromSafe] — shop cash, by location. Taking from the SAFE needs the
     *    owner's approval every time (see [requestSafeWithdrawal]); the VM enforces that
     *    before a plan with [fromSafe] is ever executed by a non-admin.
     *  - [outside] — money from outside the shop, of [outsideKind]:
     *      "capital" = the OWNER's own money (raises what the shop owes the owner),
     *      "loan"    = BORROWED (a liability to repay).
     *    Neither is sales and neither is profit. Outside funds pay the payee directly, so
     *    they move no shop cash and write no [CashTxn] — only an [OutsideFund] row.
     *  - [payable] — nothing left to pay with; the shop now owes the payee.
     */
    data class FundingPlan(
        val fromTill: Double = 0.0,
        val fromSafe: Double = 0.0,
        val outside: Double = 0.0,
        val outsideKind: String? = null,
        val payable: Double = 0.0
    ) {
        /** Shop cash leaving the premises under this plan. */
        val cash: Double get() = fromTill + fromSafe
        /** Does this plan open the safe? (⇒ needs admin approval when a cashier asks.) */
        val needsSafe: Boolean get() = fromSafe > 0.005
        val total: Double get() = fromTill + fromSafe + outside + payable
    }

    /** Build a plan against the CURRENT live balances. */
    private suspend fun planFundingNow(businessId: String, amount: Double, mode: String): FundingPlan =
        planFunding(amount, mode, tillBalanceOnce(businessId), safeBalanceOnce(businessId))

    /**
     * Write the cash side of a [FundingPlan]: one negative [CashTxn] per location that
     * actually paid, plus an [OutsideFund] row when outside money covered part of it.
     * Called INSIDE the caller's transaction so a payment and its funding land together.
     */
    private suspend fun postFunding(
        businessId: String,
        plan: FundingPlan,
        type: String,
        note: String,
        refType: String?,
        refId: String?,
        outsideSource: String?,
        cashierId: String?,
        cashierName: String?,
        stamp: Long
    ) {
        if (plan.fromTill > CENT) {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = type, amount = -plan.fromTill,
                    location = CashLocation.TILL, source = "till", note = note,
                    refType = refType, refId = refId,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
        }
        if (plan.fromSafe > CENT) {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = type, amount = -plan.fromSafe,
                    location = CashLocation.SAFE, source = "safe", note = note,
                    refType = refType, refId = refId,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
        }
        if (plan.outside > CENT) {
            // Outside money pays the payee directly — it never enters the drawer, so
            // there is no cash row, only the equity/liability record.
            outsideFundDao.insert(
                OutsideFund(
                    businessId = businessId,
                    kind = plan.outsideKind ?: "capital", direction = "in",
                    amount = plan.outside,
                    source = outsideSource ?: if (plan.outsideKind == "loan") "Loan" else "Owner",
                    note = note, refType = refType, refId = refId,
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
        }
    }

    // ---- §4 SAFE WITHDRAWAL: reuse the admin approval channel ------------

    /**
     * A cashier asks the owner to open the safe. Raised through the SAME `staff_requests`
     * channel as the credit-limit gate (type `"safe_withdrawal"`), so it lands in the
     * admin's Alerts feed on the other phone, syncs like every other request, and carries
     * the amount + reason.
     *
     * Deliberately NOT "pay the bill from the safe on approval". Approving MOVES the money
     * from the safe into the till — which is what physically happens when the owner opens
     * the safe — and the payment then proceeds from a till that has the cash. That keeps
     * the approved action tiny, self-contained and safely repeatable.
     */
    suspend fun requestSafeWithdrawal(
        businessId: String,
        amount: Double,
        reason: String?,
        targetType: String? = null,
        targetId: String? = null,
        targetName: String? = null,
        byId: String? = null,
        byName: String? = null
    ): StaffRequest? {
        if (amount <= CENT) return null
        return submitStaffRequest(
            type = "safe_withdrawal",
            targetType = targetType ?: "cash",
            targetId = targetId,
            targetName = targetName ?: "Safe withdrawal",
            amount = amount,
            note = reason,
            byId = byId,
            byName = byName
        )
    }

    /**
     * Take [amount] out of the safe and put it in the till. The one place a safe
     * withdrawal is executed, used by BOTH paths:
     *  - an ADMIN acting on their own device, approving inline;
     *  - an admin approving a cashier's `safe_withdrawal` request from the Alerts feed
     *    (see [decideStaffRequest], which calls this and then sets `applied` so a re-pull
     *    of the decided row can never move the money twice).
     *
     * Records a transfer pair (combined cash unchanged), audits WHO opened the safe and
     * notifies the admin feed. Returns what actually moved, clamped to the safe balance.
     */
    suspend fun withdrawFromSafe(
        businessId: String,
        amount: Double,
        reason: String?,
        cashierId: String? = null,
        cashierName: String? = null
    ): Double {
        val stamp = now()
        val safe = safeBalanceOnce(businessId).coerceAtLeast(0.0)
        val moved = amount.coerceIn(0.0, safe)
        if (moved <= CENT) return 0.0
        db.withTransaction {
            writeTransfer(
                businessId = businessId,
                from = CashLocation.SAFE, to = CashLocation.TILL, amount = moved,
                note = "Safe withdrawal" + (reason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "safe_withdrawal", entityType = "cash",
                    summary = "Took ${fmtMoney(moved)} out of the safe" +
                        (reason?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    meta = fmtMoney(moved),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        notifyCashEvent(
            businessId = businessId,
            dedupeKey = "safewithdrawal:$stamp",
            title = "Safe opened",
            body = "${fmtMoney(moved)} taken out of the safe" +
                (reason?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                (cashierName?.let { " · by $it" } ?: ""),
            severity = "warn", refType = "cash", refId = null, at = stamp
        )
        return moved
    }

    // ---- §4 / §6 OUTSIDE FUNDS + OWNER DRAWINGS --------------------------

    /** The outside-money ledger (owner injections, loans, drawings), newest first. */
    fun outsideFundsFlow(businessId: String): Flow<List<OutsideFund>> =
        outsideFundDao.observeForBusiness(businessId)

    /** Running "put in / taken out / borrowed / repaid" totals for the owner. */
    fun outsideFundTotalsFlow(businessId: String): Flow<OutsideFundTotals> =
        outsideFundDao.observeTotals(businessId)

    /**
     * Money coming INTO the shop from outside as CASH (§4): the owner topping the business
     * up out of their own pocket ([kind] = "capital") or a borrowing ([kind] = "loan").
     * Neither is sales and neither is profit — the cash row exists so the drawer/safe is
     * right, and the [OutsideFund] row is what the totals read, so it can never be counted
     * as takings. Lands in [location] (default: the safe, where takings live).
     */
    suspend fun recordOutsideCashIn(
        businessId: String,
        amount: Double,
        kind: String,
        source: String?,
        note: String?,
        location: String = CashLocation.SAFE,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= CENT) return
        val stamp = now()
        val loan = kind == "loan"
        val where = CashLocation.of(location)
        db.withTransaction {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = if (loan) "loan" else "capital",
                    amount = amount, location = where,
                    source = source ?: if (loan) "Loan" else "Owner",
                    note = note ?: if (loan) "Borrowed funds in" else "Owner money in",
                    refType = "outside_fund",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            outsideFundDao.insert(
                OutsideFund(
                    businessId = businessId, kind = if (loan) "loan" else "capital",
                    direction = "in", amount = amount,
                    source = source ?: if (loan) "Loan" else "Owner", note = note,
                    refType = "cash",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = if (loan) "loan_in" else "capital_in",
                    entityType = "cash",
                    summary = (if (loan) "Borrowed " else "Owner put in ") + fmtMoney(amount) +
                        " into the ${CashLocation.label(where).lowercase()}",
                    meta = fmtMoney(amount),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    /**
     * TAKE MONEY OUT (§6) — the owner drawing cash from the business. The exact mirror of
     * a capital injection:
     *  - cash goes DOWN (a negative `drawing` [CashTxn] in the chosen location);
     *  - an [OutsideFund] row `kind="capital", direction="out"` reduces what the shop owes
     *    the owner.
     *
     * ★ A DRAWING IS NOT AN EXPENSE and MUST NOT REDUCE PROFIT. It is not written to
     * `expenses`, so it never reaches the posted-expense total that net profit subtracts.
     * It shows up only where it belongs: less cash held, and a smaller "net put in".
     * Returns what was actually taken, clamped to the balance in that location.
     */
    suspend fun recordOwnerDrawing(
        businessId: String,
        amount: Double,
        location: String = CashLocation.SAFE,
        note: String?,
        cashierId: String? = null,
        cashierName: String? = null
    ): Double {
        if (amount <= CENT) return 0.0
        val where = CashLocation.of(location)
        val available = (if (where == CashLocation.SAFE) safeBalanceOnce(businessId)
        else tillBalanceOnce(businessId)).coerceAtLeast(0.0)
        val taken = amount.coerceAtMost(available)
        if (taken <= CENT) return 0.0
        val stamp = now()
        db.withTransaction {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = "drawing", amount = -taken,
                    location = where, source = "Owner",
                    note = note ?: "Owner drawing", refType = "outside_fund",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            outsideFundDao.insert(
                OutsideFund(
                    businessId = businessId, kind = "capital", direction = "out",
                    amount = taken, source = "Owner", note = note, refType = "cash",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "owner_drawing", entityType = "cash",
                    summary = "Owner took out ${fmtMoney(taken)} from the " +
                        CashLocation.label(where).lowercase() +
                        (note?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    meta = fmtMoney(taken),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        return taken
    }

    /** Repay part of a borrowing from shop cash (a liability going down, not an expense). */
    suspend fun recordLoanRepayment(
        businessId: String,
        amount: Double,
        location: String = CashLocation.SAFE,
        source: String?,
        note: String?,
        cashierId: String? = null,
        cashierName: String? = null
    ): Double {
        if (amount <= CENT) return 0.0
        val where = CashLocation.of(location)
        val available = (if (where == CashLocation.SAFE) safeBalanceOnce(businessId)
        else tillBalanceOnce(businessId)).coerceAtLeast(0.0)
        val paid = amount.coerceAtMost(available)
        if (paid <= CENT) return 0.0
        val stamp = now()
        db.withTransaction {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = "loan", amount = -paid,
                    location = where, source = source ?: "Loan",
                    note = note ?: "Loan repayment", refType = "outside_fund",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            outsideFundDao.insert(
                OutsideFund(
                    businessId = businessId, kind = "loan", direction = "out",
                    amount = paid, source = source ?: "Loan", note = note, refType = "cash",
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "loan_repaid", entityType = "cash",
                    summary = "Repaid ${fmtMoney(paid)} of borrowed money",
                    meta = fmtMoney(paid),
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
        return paid
    }

    // ---- §5 CASH-BASIS revenue / profit inputs ---------------------------

    /**
     * Raw per-sale input to [CashBasis] — see that object for what "revenue" now means.
     * Unwindowed on purpose: a repayment today can settle a sale from last year.
     *
     * [RECEIPT_STATUSES] is the SAME constant [expectedDrawer] passes, and passing it
     * from one place is the point: recognition and the drawer have to agree about which
     * rows are real sales, or a day balances on cash the sales figures never mention.
     */
    fun cashBasisSalesFlow(businessId: String): Flow<List<CashBasisSaleRow>> =
        saleDao.observeAllSaleMargins(businessId, RECEIPT_STATUSES)
            .map { rows -> rows.map { it.toCashBasisRow() } }

    /**
     * The reversal side of the same question: every live refund, so [CashBasis] can take
     * back the revenue and profit of a sale whose goods came back — on the REFUND's day,
     * without the immutable sale row being touched. Unwindowed for the same reason
     * [cashBasisSalesFlow] is: a refund written today reverses a sale from last month.
     */
    fun cashBasisRefundsFlow(businessId: String): Flow<List<CashBasisRefundRow>> =
        refundDao.observeCashBasisRefunds(businessId)

    // ---- §6 THE SPLIT: how much of this money is actually mine -----------

    /**
     * The cash held (till + safe) split into the four pots the owner thinks in (§6).
     *
     *  - [ownerFunds]     — the float plus outside money still sitting in the cash: the
     *                       opening float, owner injections and borrowings, less anything
     *                       the owner has drawn back out or repaid. **Never profit.**
     *                       (Named `floatCapital` until the drawings fix below.)
     *  - [stockMoney]     — the cost of the goods already SOLD AND COLLECTED that has not
     *                       yet been ploughed back into the shelves. It must buy the next
     *                       lot. (Collected cost of goods, less cash already spent on
     *                       purchases; floored at zero once the restocking has caught up.)
     *  - [owedToCustomers]— change owed plus unpaid refunds. **Explicitly NOT the owner's
     *                       money** — it is sitting in the drawer waiting to be handed
     *                       back, and must never inflate his figures.
     *  - [cashProfit]     — what is genuinely his to take, IN CASH, RIGHT NOW. Deliberately
     *                       the RESIDUAL, so the four parts add up to the cash actually
     *                       held, to the cent.
     *
     * ══ ★ THE WORD "PROFIT" WAS DOING TWO JOBS, AND THEY DISAGREE ══
     *
     * This field used to be called `profit`, and the owner's rule — "a drawing must never
     * reduce profit" — appeared to be broken here. It was not one rule failing; it was two
     * different figures sharing one name:
     *
     *  • EARNED PROFIT is the P&L figure: [CashBasis.Period.grossProfit], less posted
     *    expenses, less till short/over. A drawing is not a cost, never reaches `expenses`,
     *    and does not appear in it. See [recordOwnerDrawing]. That rule already held and
     *    still holds — nothing in this class can change it.
     *
     *  • [cashProfit] is a CASH-POSITION residual: of the notes physically in the till and
     *    the safe, how many are the owner's rather than the customers' or the next lot's.
     *    When the owner takes $100 out, that $100 is no longer in the drawer, so this
     *    figure MUST fall. Anything else would tell him to take money that is not there.
     *
     * They are now separately named, and the UI labels them separately, for the same reason
     * `Period.revenue` was renamed rather than redefined: a figure whose basis is ambiguous
     * gets read as whichever basis the reader had in mind.
     *
     * ══ ★ WHY THE ZERO CLAMP ON [ownerFunds] STAYS ══
     *
     * The obvious fix looked like "delete the `coerceAtLeast(0.0)` and let owner funds go
     * negative". It was considered and REJECTED, because the clamp is what guarantees the
     * one property that matters here:
     *
     *     ownerFunds >= 0, stockMoney >= 0, owedToCustomers >= 0   ⇒   cashProfit <= held
     *
     * Unclamped, an owner who had drawn $60 more than he ever put in would get
     * `ownerFunds = -60` and therefore `cashProfit = held + 60` — a screen reading "yours
     * to take $100" over a drawer holding $40. A negative term in a subtraction silently
     * inverts into an overstatement, which is the exact failure mode a residual split is
     * supposed to be immune to.
     *
     * What the clamp got WRONG was being SILENT. Once it bit, every further dollar drawn
     * came out of [cashProfit] with nothing anywhere saying why, so a drawing read as a
     * business loss. [ownerOverdrawn] is that missing explanation: the amount by which
     * drawings and repayments have exceeded the float plus everything put in, as a positive
     * figure, sitting OUTSIDE the four-way sum. It does not change any total; it names the
     * reason [cashProfit] fell, so the screen can say "you have already taken this" instead
     * of leaving it to look like money lost.
     *
     * ══ The invariant, stated so a test can assert it ══
     *
     *     ownerFunds + stockMoney + owedToCustomers + cashProfit  ==  held    (always)
     *     ownerFunds + cashProfit                                              (the owner's
     *         total claim on the cash) falls by EXACTLY the amount of a drawing — never by
     *         more. That is what "a drawing is not a loss" means arithmetically.
     *
     * SCOPE, stated honestly: this splits CASH THAT IS HERE. It says nothing about the
     * value of unsold stock (the owner explicitly does not want projected profit on goods
     * that have not sold), and money still owed on credit is profit EARNED but not yet
     * cash HELD — it shows in [creditOutstanding], not in [cashProfit].
     */
    data class CashSplit(
        val till: Double = 0.0,
        val safe: Double = 0.0,
        val ownerFunds: Double = 0.0,
        /** How far drawings have gone PAST the float + capital + loans, positive. Not one
         *  of the four pots and never in the sum — it explains a smaller [cashProfit]. */
        val ownerOverdrawn: Double = 0.0,
        val stockMoney: Double = 0.0,
        val owedToCustomers: Double = 0.0,
        val creditOutstanding: Double = 0.0
    ) {
        val held: Double get() = till + safe

        /** The residual — what is left once the other three pots are set aside. Can be
         *  negative for real reasons (a till shortage, a restock bigger than the takings);
         *  a drawing is never one of them, because a drawing lowers [held] and this term
         *  by the same amount it lowers what the owner can actually pick up. */
        val cashProfit: Double get() = held - ownerFunds - stockMoney - owedToCustomers

        /** Everything in the cash that is the owner's rather than a customer's or the next
         *  lot's. Falls by exactly what a drawing takes — the "not a loss" figure. */
        val ownerClaim: Double get() = ownerFunds + cashProfit

        companion object {
            /**
             * Build the split from the raw ledger figures. PURE — no Room, no clock — so
             * the drawings rules above are provable rather than merely asserted.
             *
             * [equityCash] is the signed net of the `capital` / `loan` / `drawing` cash
             * rows (see [CashTxnDao.observeEquityCashSum]): money in is positive, a drawing
             * or a loan repayment negative. [collectedCogs] is the cost of goods behind
             * money actually COLLECTED — [CashBasis.Period.cogs], already net of refunds,
             * because goods that came back are on the shelf again and their cost is no
             * longer money that has to buy the next lot.
             */
            fun of(
                openingFloat: Double,
                tillMovements: Double,
                safe: Double,
                equityCash: Double,
                collectedCogs: Double,
                purchaseCash: Double,
                owedToCustomers: Double,
                creditOutstanding: Double,
            ): CashSplit {
                // The owner's own money still in the cash. Negative means he has taken out
                // more than he ever put in — real, and surfaced rather than clamped away.
                val ownerNet = openingFloat + equityCash
                return CashSplit(
                    till = openingFloat + tillMovements,
                    safe = safe,
                    ownerFunds = ownerNet.coerceAtLeast(0.0),
                    ownerOverdrawn = (-ownerNet).coerceAtLeast(0.0),
                    stockMoney = (collectedCogs - purchaseCash).coerceAtLeast(0.0),
                    owedToCustomers = owedToCustomers.coerceAtLeast(0.0),
                    creditOutstanding = creditOutstanding.coerceAtLeast(0.0),
                )
            }
        }
    }

    // ---- misc cash reads --------------------------------------------------

    /** Equity/outside cash sitting in the drawer + safe (capital + loans − drawings). */
    fun equityCashFlow(businessId: String): Flow<Double> =
        cashTxnDao.observeEquityCashSum(businessId)

    /** Cash already spent buying stock, all time, as a positive figure. */
    fun purchaseCashFlow(businessId: String): Flow<Double> =
        cashTxnDao.observePurchaseCashSum(businessId)

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
            logAudit(
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
            notificationDao.tombstone(listOf(it.id), now())
        }
    }

    /**
     * APPROVE (post) a pending expense with a chosen funding [mode] — the double-entry-
     * lite split (prompt §9.4), now aware of the two cash locations (§4).
     *
     * The payer picks the SOURCE, and the order money is reached for is
     * **TILL → SAFE → OUTSIDE FUNDS → abort**. [mode] is passed straight to
     * [planFunding], which documents every value; the plan's cash side is posted per
     * location, its outside side becomes an [OutsideFund] row (owner capital or a loan),
     * and anything left over is accounts payable.
     *
     * The stored [Expense.capitalPortion] now means "funded from OUTSIDE the shop's cash";
     * WHICH outside source (the owner's own money vs borrowed) is recorded on the
     * [OutsideFund] row, so the two running totals stay separable.
     *
     * ★ Opening the SAFE needs the owner's approval every time. This function does not
     * know who is asking — the caller (the ViewModel, which holds the session role) either
     * is an admin, or must route through [requestSafeWithdrawal] first.
     *
     * A recurring submission additionally becomes a SCHEDULE (a separate template row)
     * so future periods auto-post. Audited. All writes are atomic.
     */
    suspend fun approveExpense(
        id: String, mode: String, cashierId: String? = null, cashierName: String? = null,
        outsideSource: String? = null
    ) {
        val e = expenseDao.getById(id) ?: return
        if (e.status != "pending" || e.deleted) return
        val stamp = now()
        val plan = planFundingNow(e.businessId, e.amount, mode)
        db.withTransaction {
            val posted = e.copy(
                status = "approved", approvedBy = cashierId, approvedByName = cashierName,
                approvedAt = stamp, postedAt = stamp,
                cashPortion = plan.cash, payablePortion = plan.payable,
                capitalPortion = plan.outside,
                updatedAt = stamp, pendingSync = true
            )
            expenseDao.upsert(posted)
            clearPendingExpenseNotice(e.businessId, e.id)
            postFunding(
                businessId = e.businessId, plan = plan, type = "expense",
                note = "${e.category} · ${e.description ?: "expense"}",
                refType = "expense", refId = e.id, outsideSource = outsideSource,
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
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
            logAudit(
                AuditEntry(
                    businessId = e.businessId, action = "expense_approved", entityType = "expense",
                    entityId = e.id,
                    summary = "Approved ${e.category} ${fmtMoney(e.amount)} via ${fundingLabel(plan)}" +
                        (if (plan.payable > CENT) " (${fmtMoney(plan.payable)} owed)" else ""),
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

    /** Record an ad-hoc cash payout / drawer adjustment (admin), audited. [location]
     *  says WHICH pot moved; it defaults to the till, which is where an ad-hoc
     *  over-the-counter correction happens. */
    suspend fun recordCashAdjustment(
        businessId: String, amount: Double, note: String,
        cashierId: String? = null, cashierName: String? = null,
        location: String = CashLocation.TILL
    ) {
        if (kotlin.math.abs(amount) < CENT) return
        val stamp = now()
        val where = CashLocation.of(location)
        db.withTransaction {
            cashTxnDao.insert(
                CashTxn(
                    businessId = businessId, type = if (amount < 0) "payout" else "adjust",
                    amount = amount, location = where,
                    source = "manual", note = note.ifBlank { "Cash adjustment" },
                    createdBy = cashierId, createdByName = cashierName,
                    createdAt = stamp, updatedAt = stamp
                )
            )
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "cash_adjust", entityType = "cash",
                    summary = "${CashLocation.label(where)} ${if (amount < 0) "payout" else "top-up"} " +
                        "${fmtMoney(kotlin.math.abs(amount))} — ${note.ifBlank { "manual" }}",
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
            // Unattended posting policy: run the ordinary TILL → SAFE → payable waterfall.
            // The owner set this schedule up themselves and every draw is audited and
            // notified, so it is their standing instruction rather than an unapproved
            // hand in the safe; if neither location covers it, the balance is simply owed.
            val plan = planFundingNow(businessId, tpl.amount, "waterfall")
            db.withTransaction {
                val child = Expense(
                    businessId = businessId, category = tpl.category, amount = tpl.amount,
                    date = ymd(now), description = tpl.description, status = "approved",
                    templateId = tpl.id, recurring = false, isTemplate = false,
                    approvedAt = now, postedAt = now,
                    cashPortion = plan.cash, payablePortion = plan.payable,
                    capitalPortion = plan.outside,
                    approvedByName = "Auto (recurring)"
                )
                expenseDao.upsert(child)
                postFunding(
                    businessId = businessId, plan = plan, type = "expense",
                    note = "${tpl.category} (recurring)",
                    refType = "expense", refId = child.id, outsideSource = null,
                    cashierId = null, cashierName = "Auto (recurring)", stamp = now
                )
                val payable = plan.payable
                expenseDao.upsert(
                    tpl.copy(
                        lastRunAt = now, nextRunAt = nextRun(now, tpl.recurrencePeriod ?: "monthly"),
                        updatedAt = now, pendingSync = true
                    )
                )
                logAudit(
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
                eventAt = stamp, createdAt = stamp, pushedAt = stamp,
                updatedAt = stamp, pendingSync = true
            )
            out += upsertNotificationByKey(n)
        }
        nudgeSync("notifications")
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
            eventAt = e.createdAt, createdAt = stamp, pushedAt = stamp,
            updatedAt = stamp, pendingSync = true
        )
        val row = upsertNotificationByKey(n)
        nudgeSync("notifications")
        return row
    }

    /** Human summary of where a payment's money actually came from, for the audit trail. */
    private fun fundingLabel(plan: FundingPlan): String {
        val parts = ArrayList<String>(4)
        if (plan.fromTill > CENT) parts += "till"
        if (plan.fromSafe > CENT) parts += "safe"
        if (plan.outside > CENT) parts += if (plan.outsideKind == "loan") "a loan" else "owner money"
        if (plan.payable > CENT) parts += "credit"
        return if (parts.isEmpty()) "nothing" else parts.joinToString(" + ")
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

    // ---- suppliers (synced: cloud `suppliers`, upsert on local_id) --------

    fun suppliersFlow(businessId: String): Flow<List<Supplier>> =
        supplierDao.observeForBusiness(businessId)

    suspend fun saveSupplier(supplier: Supplier) =
        supplierDao.upsert(supplier.copy(updatedAt = now(), pendingSync = true))

    suspend fun deleteSupplier(id: String) = supplierDao.softDelete(id, now())

    // ---- purchase orders (synced: `purchase_orders` + `purchase_order_items`) ----

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
     * the cash paid drains the shop's cash through `cash_txns` "purchase" rows (never an
     * `expenses` row, so it never reduces derived net profit — the goods only affect profit
     * later via cost-of-goods-sold when sold).
     *
     * FUNDING (§4) follows the same **TILL → SAFE → OUTSIDE FUNDS → abort** waterfall as
     * every other payment: [fundingMode] is passed to [planFunding] (see it for the full
     * vocabulary — "till", "safe", "waterfall", "capital", "loan", "none", legacy "cash").
     * The cash side posts per location, the outside side becomes an [OutsideFund] row, and
     * whatever of the order total isn't covered becomes `payableRemainder` (money owed to
     * the supplier). [PurchaseOrder.capitalPaid] now means "paid from outside funds"; which
     * kind of outside money it was lives on the [OutsideFund] row.
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
        cashierName: String? = null,
        outsideSource: String? = null
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
        val want = payNow.coerceIn(0.0, total)
        val plan = planFundingNow(businessId, want, fundingMode)
        db.withTransaction {
            val cash = plan.cash
            val capital = plan.outside
            // Anything the up-front payment didn't cover is owed to the supplier — both
            // the deliberate shortfall (payNow < total) and any waterfall remainder.
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

            postFunding(
                businessId = businessId, plan = plan, type = "purchase",
                note = "PO ${po.ref} · ${supplierName.ifBlank { "supplier" }}",
                refType = "purchase_order", refId = po.id, outsideSource = outsideSource,
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
            logAudit(
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
        poDao.upsert(po.copy(status = "placed", sentAt = stamp, updatedAt = stamp, pendingSync = true))
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
            poDao.upsert(
                po.copy(
                    status = "cancelled", payableRemainder = 0.0,
                    updatedAt = stamp, pendingSync = true
                )
            )
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
                poDao.upsertLine(line.copy(receivedQty = newReceived, pendingSync = true))
                if (newReceived + CENT < line.qty) allDone = false
            }
            poDao.upsert(
                po.copy(
                    status = if (allDone) "received" else "partial",
                    receivedAt = if (allDone) stamp else po.receivedAt,
                    updatedAt = stamp, pendingSync = true
                )
            )
            logAudit(
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
     * Settle (part of) a supplier's accounts payable on a PO. [mode] is the funding choice
     * from [planFunding] — the same TILL → SAFE → OUTSIDE waterfall as any other payment;
     * "waterfall" pays only what the two locations actually hold and leaves the rest owed.
     * Drains cash via `cash_txns` "purchase" rows (still an asset purchase, never an
     * expense). Atomic. No-op if nothing is owed / nothing can be paid.
     */
    suspend fun recordSupplierPayment(
        poId: String, mode: String, cashierId: String? = null, cashierName: String? = null,
        outsideSource: String? = null
    ) {
        val po = poDao.getById(poId) ?: return
        val remainder = po.payableRemainder
        if (remainder <= CENT) return
        val stamp = now()
        val plan = planFundingNow(po.businessId, remainder, mode)
        val pay = plan.cash + plan.outside
        if (pay <= CENT) return
        db.withTransaction {
            postFunding(
                businessId = po.businessId, plan = plan, type = "purchase",
                note = "PO ${po.ref} balance",
                refType = "purchase_order", refId = po.id, outsideSource = outsideSource,
                cashierId = cashierId, cashierName = cashierName, stamp = stamp
            )
            poDao.upsert(
                po.copy(
                    cashPaid = po.cashPaid + plan.cash,
                    capitalPaid = po.capitalPaid + plan.outside,
                    payableRemainder = (remainder - pay).coerceAtLeast(0.0),
                    updatedAt = stamp, pendingSync = true
                )
            )
            logAudit(
                AuditEntry(
                    businessId = po.businessId, action = "purchase_payment",
                    entityType = "purchase_order", entityId = po.id,
                    summary = "Paid ${fmtMoney(pay)} to ${po.supplierName.ifBlank { "supplier" }} " +
                        "on ${po.ref} from ${fundingLabel(plan)}",
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
        // ★ MONEY SHORT AND NOBODY OWES IT IS NOT A SALE. Without this the shortfall on a
        // non-credit sale simply evaporated: `owed` is only computed for a credit sale, so
        // $50 tendered against a $100 total wrote a sale of $100 marked `paid` with $50
        // recorded — the drawer $50 light at closing with nothing anywhere saying why.
        // The pay dialog already refuses to complete unless the sale is fully paid or a
        // valid credit sale ([PosUi] `valid = fullyPaid || creditValid`), so no cashier can
        // reach this today. It throws anyway, because a screen is a place a person is
        // stopped and this is the place the LEDGER is: a future caller — a repeat-sale
        // shortcut, a pulled order, a test — must not be able to write the shop poorer
        // than its books. `credit && customer == null` is caught by the same test: the
        // shortfall has no account to sit on, so it is not on credit, it is missing.
        //
        // The tolerance carries the rounding step, and that is not slack — it is a real
        // gap between the two screens. The pay dialog quotes the UNROUNDED total, while
        // `total` above is rounded to [totalRounding]; rounding UP can therefore leave a
        // sale legitimately short by up to half a step against a cashier who tendered
        // exactly what the screen asked for. Half a step is the most that gap can ever be,
        // so anything past it is a genuine shortfall rather than the rounding setting.
        val shortfall = total - amountPaid - owed
        require(shortfall <= CENT + totalRounding / 2.0) {
            "Underpaid sale: total $total, tendered $amountPaid, on account $owed. " +
                "A shortfall must go on a customer's account (onCredit with a customer) " +
                "or be tendered — it cannot be recorded as paid."
        }
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

        // Every sale belongs to a trading day, and the day IS the shift. Resolved here
        // rather than by the caller so no checkout path can forget: an unstamped sale
        // pushes with `session_id = NULL`, and a cash-up that cannot resolve a sale's
        // session leaves its takings out of the drawer count without saying so.
        val daySessionId = currentDaySessionId(businessId, stamp)

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
            sessionId = daySessionId,
            createdBy = cashierId,
            createdByName = cashierName,
            updatedAt = stamp,
            synced = false
        )
        // Cost of goods FROZEN at sale time: profit must be computed against what the
        // product cost WHEN IT SOLD, not against whatever the catalog says today. Read
        // once per distinct item; null for an item that carries no cost.
        val costByItem: Map<String, Double?> =
            cart.map { it.itemId }.distinct().associateWith { itemDao.getById(it)?.cost }
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
                unitCost = costByItem[c.itemId],
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
            //
            // LOCATION (§4): a sale happens at the counter, so its cash lands in the TILL.
            // It only reaches the safe when the owner closes the day and moves it there.
            val cashTendered = tenders.filter { it.method == "cash" }.sumOf { it.amount }
            val netCashIn = cashTendered - changeGivenActual
            if (kotlin.math.abs(netCashIn) > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "sale", amount = netCashIn,
                        location = CashLocation.TILL,
                        source = "cash", note = "Sale #$receiptNo",
                        refType = "sale", refId = saleId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // Draw down stock for any tracked items in the cart and log the movement.
            // Untracked items and ad-hoc lines (no matching item row) are left alone.
            // Box lines consume qty * boxSize units.
            //
            // ★ NOT clamped at zero, and the clamp that used to be here is the whole bug:
            // it floored the CACHED figure at 0 while writing the full movement to the
            // ledger, so the till showed a tidy 0 and the next sync recomputed
            // `baseline + Σ deltas` — no clamp there — and handed every till a -2. The
            // shortfall was real from the moment it was rung up; the clamp only decided
            // which screen found out about it, and made it look like a sync fault instead
            // of an oversell. The cache now says exactly what the recompute will say, and
            // a negative on-hand reads as "count this shelf" in the UI (see StockBadge).
            for (c in cart) {
                val item = itemDao.getById(c.itemId) ?: continue
                if (!item.trackStock) continue
                // Measured items draw down the DECIMAL stockMeasured by the sold quantity
                // and never touch the integer box/piece stockQty; everything else draws
                // whole units (qty * boxSize) off stockQty.
                val measured = item.productType == "measured" || c.measured
                val drawn = if (measured) c.qty else c.stockUnits
                val remaining = (if (measured) item.stockMeasured else item.stockQty) - drawn
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
                    logAudit(
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
                    logAudit(
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

    // ---- edit a receipt in place, within the admin window (B5) ------------

    /**
     * Rewrite an existing completed sale IN PLACE from [cart] — the caller's full,
     * final set of lines. The sale keeps its **id, receiptNo, soldAt, tenders and
     * cashier attribution**; only the goods, the derived money and the settlement
     * position move. There is never a second receipt: one sale, one row, edited.
     *
     * Everything derived is recomputed through the SAME path checkout uses
     * ([computeSaleTotals] over the cart's own line getters, plus the identical
     * [totalRounding] step), so an edited receipt and a freshly rung one can never
     * disagree on the math.
     *
     * Stock moves by the DELTA only, per item: extra quantity draws the shelf down
     * (`sale`), removed quantity puts it back (`return`). Measured items (B1) move
     * their decimal `stockMeasured`; everything else moves whole units
     * (qty x unitsPerLine) off `stockQty` — mirroring [checkout] exactly.
     *
     * The payment delta is never swallowed. [amountPaid] and any change already handed
     * over are FIXED (an edit doesn't re-open the till), so the customer's position
     * shifts by exactly `oldTotal - newTotal`, and that difference is booked through
     * B2's existing ledger: the customer now owing more => `credit_owed`; the shop now
     * owing them => `change_owed`. On a walk-in there is nobody to attach it to, so it
     * is recorded as an un-attributed till discrepancy in the audit log instead.
     *
     * Every edit appends [AuditEntry] rows (per-line diffs + a totals summary). The
     * sale row itself is mutable-in-place; the audit log is the append-only history.
     *
     * Returns the rewritten sale + lines, or null when the sale is gone, is not an
     * editable completed sale, is outside [windowMinutes], or has been refunded
     * (a refunded receipt must not have its goods moved under the refund).
     */
    suspend fun editSale(
        saleId: String,
        cart: List<CartLine>,
        windowMinutes: Int,
        vatEnabled: Boolean = false,
        vatPercent: Double = 0.0,
        discount: Double = 0.0,
        totalRounding: Double = 0.0,
        cashierId: String? = null,
        cashierName: String? = null
    ): SaleWithLines? {
        val sale = saleDao.getSaleById(saleId) ?: return null
        val stamp = now()
        // Window + status gate, evaluated at the SOURCE so a stale screen can't slip an
        // edit through after the receipt has locked.
        if (!sale.isEditable(windowMinutes, stamp)) return null
        // A receipt with money already returned against it is off limits: editing the
        // goods underneath a refund would make both records lie.
        if (refundDao.refundedTotalForSaleOnce(saleId) > CENT) return null
        if (cart.isEmpty()) return null

        val businessId = sale.businessId
        val oldLines = saleDao.linesForSale(saleId)
        val oldTotal = sale.total

        // ── money: identical computation to checkout ──
        val subtotal = cart.sumOf { it.lineSubtotal }
        val perItemDiscount = cart.sumOf { it.lineDiscountApplied }
        val perItemMarkup = cart.sumOf { it.lineMarkupApplied }
        val totals = computeSaleTotals(subtotal, discount + perItemDiscount, vatEnabled, vatPercent, perItemMarkup)
        val newTotal = if (totalRounding > 0.0)
            Math.round(totals.total / totalRounding) * totalRounding
        else totals.total

        // ── settlement: tenders and change already handed over are FIXED ──
        val amountPaid = sale.amountPaid
        val changeGivenActual = (sale.changeDue ?: 0.0).coerceAtLeast(0.0)
        // The customer's position after the edit. Positive => the shop is holding money
        // that belongs to them (we owe change); negative => they still owe the shop.
        val newPosition = amountPaid - changeGivenActual - newTotal
        val newChangeOwed = newPosition.coerceAtLeast(0.0)
        val newOwing = (-newPosition).coerceAtLeast(0.0)
        // What the EDIT itself moved. amountPaid/changeGiven cancel out, so the shift is
        // purely the change in the total — book only that, never the whole balance again.
        val delta = newTotal - oldTotal
        val customerId = sale.customerId

        // Same cost freeze as checkout: an edited receipt's lines capture the cost too,
        // so a re-written line never falls back to the live catalog cost.
        val costByItem: Map<String, Double?> =
            cart.map { it.itemId }.distinct().associateWith { itemDao.getById(it)?.cost }
        val newLines = cart.map { c ->
            SaleLine(
                saleId = saleId,
                businessId = businessId,
                itemId = c.itemId,
                name = if (c.mode == "box") "${c.name} (Box of ${c.unitsPerLine})" else c.name,
                qty = c.qty,
                unitPrice = c.unitPrice,
                unitCost = costByItem[c.itemId],
                lineTax = 0.0,
                lineDiscount = c.lineDiscountApplied,
                lineMarkup = c.lineMarkupApplied,
                lineTotal = c.lineSubtotal,
                mode = c.mode,
                unitsPerLine = c.unitsPerLine,
                updatedAt = stamp
            )
        }

        // ── stock delta, aggregated per item so a re-moded line nets out correctly ──
        // Units are counted the way checkout draws them: measured items in their decimal
        // quantity, everything else in whole units (qty x unitsPerLine).
        suspend fun unitsByItem(
            rows: List<Pair<String?, Pair<Double, Int>>>
        ): Map<String, Double> {
            val out = mutableMapOf<String, Double>()
            for ((itemId, qtyUnits) in rows) {
                val id = itemId ?: continue
                val item = itemDao.getById(id) ?: continue
                val (qty, unitsPerLine) = qtyUnits
                val units = if (item.isMeasured) qty else qty * unitsPerLine
                out[id] = (out[id] ?: 0.0) + units
            }
            return out
        }
        val oldUnits = unitsByItem(oldLines.map { it.itemId to (it.qty to it.unitsPerLine) })
        val newUnits = unitsByItem(cart.map { it.itemId to (it.qty to it.unitsPerLine) })

        // ── human-readable diff for the audit trail ──
        val oldQtyByName = oldLines.groupBy { it.name }.mapValues { (_, ls) -> ls.sumOf { it.qty } }
        val newQtyByName = newLines.groupBy { it.name }.mapValues { (_, ls) -> ls.sumOf { it.qty } }
        val diffs = mutableListOf<String>()
        for (name in (oldQtyByName.keys + newQtyByName.keys)) {
            val before = oldQtyByName[name] ?: 0.0
            val after = newQtyByName[name] ?: 0.0
            if (kotlin.math.abs(after - before) < 0.0001) continue
            diffs += when {
                before <= 0.0 -> "Added ${trimQty(after)} x $name"
                after <= 0.0 -> "Removed ${trimQty(before)} x $name"
                else -> "Qty $name ${trimQty(before)} -> ${trimQty(after)}"
            }
        }
        val receiptLabel = "#${sale.receiptNo ?: saleId.takeLast(6).uppercase()}"

        val updated = sale.copy(
            subtotal = subtotal,
            discountTotal = totals.discount,
            markupTotal = perItemMarkup,
            taxTotal = totals.taxTotal,
            total = newTotal,
            changeOwed = newChangeOwed.takeIf { it > CENT },
            paymentStatus = if (newOwing > CENT) "unpaid" else "paid",
            editedAt = stamp,
            editCount = sale.editCount + 1,
            updatedAt = stamp,
            // Re-queue for the cloud: the push upserts on id, so the SAME cloud row is
            // rewritten with the corrected goods and totals (no duplicate receipt).
            synced = false
        )

        db.withTransaction {
            // Replace the goods: the removed lines are tombstoned rather than erased, so
            // the local row history stays append-only-ish and the sync push can still see
            // what used to be there. Live reads (linesForSale) filter deleted = 0.
            saleDao.upsertLines(oldLines.map { it.copy(deleted = true, updatedAt = stamp) })
            saleDao.insertLines(newLines)
            saleDao.upsertSale(updated)

            // Stock: move only the difference, in the direction it went.
            for (itemId in (oldUnits.keys + newUnits.keys)) {
                val before = oldUnits[itemId] ?: 0.0
                val after = newUnits[itemId] ?: 0.0
                val change = after - before
                if (kotlin.math.abs(change) < 0.0001) continue
                val item = itemDao.getById(itemId) ?: continue
                if (!item.trackStock) continue
                val measured = item.isMeasured
                val onHand = if (measured) item.stockMeasured else item.stockQty
                // Unclamped, for the same reason as the checkout draw-down above: editing a
                // sale UP past the on-hand is an oversell arriving by a different door, and
                // a floored cache next to a full movement is the disagreement that produced
                // the -2 on the owner's phone.
                val remaining = onHand - change
                val next = if (measured) item.copy(stockMeasured = remaining, updatedAt = stamp, pendingSync = true)
                else item.copy(stockQty = remaining, updatedAt = stamp, pendingSync = true)
                itemDao.upsert(next)
                movementDao.insert(
                    StockMovement(
                        businessId = businessId,
                        itemId = itemId,
                        // More sold => a further draw-down; fewer sold => goods back on the shelf.
                        type = if (change > 0) "sale" else "return",
                        delta = -change,
                        balanceAfter = remaining,
                        note = "Edited sale $receiptLabel",
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp
                    )
                )
            }

            // The payment delta goes somewhere — always.
            if (kotlin.math.abs(delta) > CENT) {
                if (customerId != null) {
                    creditDao.insert(
                        CreditTxn(
                            businessId = businessId,
                            customerId = customerId,
                            saleId = saleId,
                            // Total went UP => they owe the difference. Went DOWN => we do.
                            type = if (delta > 0) "credit_owed" else "change_owed",
                            amount = kotlin.math.abs(delta),
                            note = "Receipt $receiptLabel edited",
                            createdBy = cashierId,
                            createdByName = cashierName,
                            createdAt = stamp,
                            updatedAt = stamp
                        )
                    )
                } else {
                    // Walk-in: no account to carry it. Record the till imbalance so an
                    // admin sees the money that didn't reconcile.
                    logAudit(
                        AuditEntry(
                            businessId = businessId,
                            action = if (delta > 0) "till_short" else "till_over",
                            entityType = "sale",
                            entityId = saleId,
                            summary = if (delta > 0)
                                "Till shortage ${fmtMoney(delta)} — walk-in receipt $receiptLabel edited upward, difference not collected"
                            else
                                "Till overage ${fmtMoney(-delta)} — walk-in receipt $receiptLabel edited downward, difference not refunded",
                            meta = fmtMoney(kotlin.math.abs(delta)),
                            createdBy = cashierId,
                            createdByName = cashierName,
                            createdAt = stamp
                        )
                    )
                }
            }

            // Append-only history: one row per line change, then the totals summary.
            for (d in diffs) {
                logAudit(
                    AuditEntry(
                        businessId = businessId,
                        action = "sale_edit_line",
                        entityType = "sale",
                        entityId = saleId,
                        summary = "$receiptLabel: $d",
                        meta = d,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp
                    )
                )
            }
            logAudit(
                AuditEntry(
                    businessId = businessId,
                    action = "sale_edit",
                    entityType = "sale",
                    entityId = saleId,
                    summary = "Receipt $receiptLabel edited — total ${fmtMoney(oldTotal)} -> ${fmtMoney(newTotal)}",
                    meta = diffs.joinToString("; ").ifBlank { "No line changes" },
                    createdBy = cashierId,
                    createdByName = cashierName,
                    createdAt = stamp
                )
            )
        }
        return SaleWithLines(updated, newLines)
    }

    /** Whole numbers read without a trailing ".0" in audit summaries. */
    private fun trimQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else String.format(java.util.Locale.US, "%.2f", q)

    /** Tenders recorded against a sale — the payment block of the receipt detail view. */
    suspend fun paymentsForSale(saleId: String): List<SalePayment> = paymentDao.forSale(saleId)

    /** Append-only edit/audit history for ONE sale, newest first (receipt detail view). */
    fun auditForSaleFlow(saleId: String): Flow<List<AuditEntry>> = auditDao.observeForEntity(saleId)

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

    /**
     * Zero every item's on-hand for a business (keeps the catalog rows).
     *
     * ══ Why this writes a movement per item ══
     * The single blanket UPDATE this used to be looked like it worked and did not.
     * `items.stockQty` is a CACHE the sync pass rebuilds as `stockBaseQty + Σ deltas`, so
     * a zeroed row with nothing in the ledger behind it came back at the next pull with
     * every figure restored. The owner reaches for this button exactly when the till is
     * holding stock numbers they want gone; a reset that silently undoes itself one sync
     * later is the worst available answer, because by then the shop has moved on
     * believing the shelves are clear.
     *
     * It also never left the device. The catalogue is pull-only, so the ledger is a
     * till's only way of telling the rest of the shop that stock moved — without these
     * rows the other tills kept their own figures and never heard about the reset.
     *
     * The row UPDATE stays: it is one statement for the whole catalogue, and it clears
     * BOTH on-hand columns, so a `measure` product is emptied rather than left holding a
     * fractional quantity the recompute would then treat as authoritative.
     */
    suspend fun resetAllStock(
        businessId: String,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        val stamp = now()
        db.withTransaction {
            // Read the on-hands BEFORE the UPDATE — afterwards every delta is zero and
            // the ledger would record a reset of nothing.
            val moves = itemDao.allForBusinessOnce(businessId).mapNotNull { item ->
                stockResetFor(item)?.let { reset ->
                    StockMovement(
                        businessId = businessId,
                        itemId = item.id,
                        type = reset.type,
                        delta = reset.delta,
                        balanceAfter = reset.balanceAfter,
                        note = reset.note,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp
                    )
                }
            }
            if (moves.isNotEmpty()) movementDao.insertAll(moves)
            itemDao.resetAllStock(businessId, stamp)
        }
    }

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

    /**
     * Shop hands over change it previously owed a customer (writes `change_paid`).
     *
     * If the cashier pays out MORE than is owed, the excess is NOT discarded (that
     * silently lost money): the owed part settles as `change_paid` and the remainder
     * becomes customer DEBT — a `credit_owed` row noted "Over-paid change" — exactly
     * mirroring the over-given-change branch of [checkout]. Both rows commit together.
     *
     * CASH-ON-HAND: only a CASH payout empties the drawer, and then it loses the FULL
     * [amount] — a single NEGATIVE `change_payout` [CashTxn]. Both halves of an over-pay
     * physically leave the till, so the cash row is the whole amount even though the
     * ledger splits it into `change_paid` + `credit_owed`.
     *
     * ★ [method] EXISTS BECAUSE THE SHOP DOES NOT ONLY PAY IN NOTES. This used to assume
     * cash and always move the till, so settling a customer's change by EcoCash took money
     * out of a drawer it had never been in — the till then read short by every non-cash
     * payout ever made, and the day close books that difference permanently as a variance
     * against profit. Same rule as everywhere else in this file: a card or mobile-money
     * movement settles the ledger and leaves the drawer alone. The method is recorded on
     * the credit rows too (`credit_txns.method` is a shared column), so the customer's
     * statement says how they were paid rather than implying notes across the counter.
     *
     * NO DOUBLE-COUNT: change handed back AT THE TILL is already netted out by [checkout]
     * (`cashTendered − changeGivenActual`). This function only ever pays out change that
     * was OWED — money [checkout] deliberately never subtracted because it never left.
     */
    suspend fun recordChangePayment(
        businessId: String,
        customerId: String,
        amount: Double,
        note: String? = null,
        method: String = "cash",
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (amount <= 0) return
        val weOwe = creditDao.changeBalanceOnce(customerId).coerceAtLeast(0.0)
        val settled = minOf(amount, weOwe)      // never past zero: we-owe won't go negative
        val over = (amount - settled).coerceAtLeast(0.0)   // customer now owes this back
        val stamp = now()
        db.withTransaction {
            if (settled > CENT) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "change_paid",
                        amount = settled,
                        note = note,
                        method = method,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            if (over > CENT) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customerId,
                        type = "credit_owed",
                        amount = over,
                        note = if (settled > CENT) "Over-paid change" else note ?: "Over-paid change",
                        method = method,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // The whole handed-over amount left the drawer — settled part AND any excess —
            // but ONLY when it was handed over in notes. An EcoCash payout settles the same
            // ledger and never opens the till; moving the drawer for it is how a physical
            // count comes out short against books that were right.
            if (method == "cash") {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "change_payout", amount = -amount,
                        location = CashLocation.TILL,
                        source = method, note = note ?: "Change paid out",
                        refType = "customer", refId = customerId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
        }
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
     * How a refund of [refundTotal] against [sale] would settle: what it cancels off the
     * customer's debt, and the MOST that may be handed back in money.
     *
     * Public because the refund dialog has to quote the same figure the till will honour.
     * [createRefund] clamps to this regardless, but a dialog offering $100 back on a sale
     * that will only pay out $40 is a cashier promising a customer money at the counter —
     * so both sides ask the one function.
     *
     * [customer] is passed in rather than re-read so the caller's already-resolved row is
     * used; null means a walk-in, who has no account to carry a debt and therefore gets
     * back whatever they paid.
     */
    suspend fun refundSettlement(
        sale: SaleEntity,
        refundTotal: Double,
        customer: Customer?,
    ): RefundSettlement {
        val collectedOnSale = customer?.let { c ->
            CashBasis.rawCollectedBySale(
                // Only id/soldAt/total/amountPaid are read; the costed economics belong to
                // recognition, which this question is not about.
                sales = listOf(
                    CashBasisSaleRow(
                        id = sale.id, soldAt = sale.soldAt, total = sale.total,
                        taxTotal = 0.0, amountPaid = sale.amountPaid, customerId = c.id,
                        costedRevenue = 0.0, lineProfit = 0.0
                    )
                ),
                ledger = creditDao.forCustomerOnce(c.id),
            )[sale.id] ?: 0.0
        } ?: sale.amountPaid.coerceIn(0.0, sale.total.coerceAtLeast(0.0))
        return planRefundSettlement(
            refundTotal = refundTotal,
            saleTotal = sale.total,
            collectedOnSale = collectedOnSale,
            alreadyRefunded = refundDao.refundedTotalForSaleOnce(sale.id),
        )
    }

    /**
     * Issue a refund against a completed sale (prompt §11). Cashier-performed and
     * offline-first. Everything commits in ONE transaction so a crash can't restock
     * without recording the refund (or book money owed without the goods movement):
     *
     *  - Writes the immutable [Refund] header + its returned [RefundLine]s. The
     *    original sale is never edited — this is a reversal linked back to it.
     *  - [refundTotal] is computed proportionally from the sale (carries the whole-sale
     *    discount + VAT — see [computeRefundTotal]); the multiplier is applied exactly
     *    once. Each returned line is valued by [returnedLineValue], so the line's OWN
     *    discount or cashier markup goes back with it instead of being smeared across
     *    every other line of the sale.
     *  - Restocks each returned line that is [RefundLineInput.restock] and tracked,
     *    with a `return` [StockMovement] (damaged goods are refunded but not restocked).
     *  - [payouts] is the money handed back NOW (may be empty, partial, or split);
     *    each becomes a [RefundPayment] row carrying its method/currency/time.
     *  - CASH-ON-HAND: the CASH portion of [payouts] physically leaves the drawer, so it
     *    posts a NEGATIVE `refund` [CashTxn] in the same transaction. Card/mobile-money
     *    reversals never touched the drawer and post nothing — the mirror of checkout,
     *    which only counts cash tenders.
     *  - ★ IT SETTLES DEBT BEFORE IT PAYS MONEY. The shop can only hand back what it was
     *    given, so the unpaid part of the sale is CANCELLED off the customer's account
     *    (a `credit_paid` row noted [CashBasis.DEBT_CANCELLED_NOTE]) and only
     *    [RefundSettlement.payable] may cross the counter. Refunding a $100 sale that had
     *    collected $40 used to hand over $100 and leave a real drawer at −$60, with the
     *    $60 still showing as a debt against goods back on the shelf.
     *  - Any shortfall (payable − paid-now) is booked as a `refund_owed` credit
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
        // Money leaving the drawer is counted against the same trading day the takings are,
        // so a payout gets its shift the same way a sale does.
        val daySessionId = currentDaySessionId(businessId, stamp)

        // ★ NOTHING COMES BACK TWICE. Each requested quantity is clamped here to what that
        // sale line still has outstanding — units sold, less every unit already returned on
        // a live refund — and lines with nothing left are dropped.
        //
        // The MONEY was already safe: [refundSettlement] nets off `alreadyRefunded`, so a
        // sale refunded twice paid out nothing the second time. The GOODS were not. The
        // restock loop below trusted its input, so refunding the same two brake pads twice
        // put four on the shelf: `items.stockQty` +2 too high with a matching `return`
        // movement to make it look deliberate, and stock is the one figure here that no
        // later cash count can catch — the drawer still balances while the shelf lies.
        //
        // The dialog caps the stepper the same way ([PosUi] `maxReturn = qty - already`),
        // so a cashier cannot reach this. It is enforced here anyway for the reason the
        // payout clamp above exists: the screen is where a person is stopped, and this is
        // where the ledger is written.
        val clamped = lines.mapNotNull { inp ->
            val outstanding = (inp.saleLine.qty - refundDao.qtyReturnedForLine(inp.saleLine.id))
                .coerceAtLeast(0.0)
            val qty = inp.qtyReturned.coerceIn(0.0, outstanding)
            if (qty <= 0.0) null else inp.copy(qtyReturned = qty)
        }

        // Returned goods valued net of each line's OWN discount/markup, measured against
        // the whole sale on the SAME basis; the ratio then folds in the whole-sale
        // discount + VAT. Both sides must use returnedLineValue — sale.subtotal is the
        // GROSS goods value (per-item adjustments live in the sale's discount/markup
        // totals), so pairing it with a net numerator would break a full return.
        val returnedSubtotal = clamped.sumOf { returnedLineValue(it.saleLine, it.qtyReturned) }
        val goodsValue = saleGoodsValue(saleDao.linesForSale(sale.id))
        val refundTotal = computeRefundTotal(returnedSubtotal, goodsValue, sale.total).refundTotal

        // ★ WHAT MAY ACTUALLY BE HANDED BACK — see [refundSettlement].
        val settlement = refundSettlement(sale, refundTotal, customer)
        // Clamped, not merely validated: the dialog offers at most `payable`, but a stale
        // screen or a caller that skipped it must not be able to empty the drawer.
        val paidNow = payouts.filter { it.amount != 0.0 }.sumOf { it.amount }
            .coerceIn(0.0, settlement.payable)
        val outstanding = (settlement.payable - paidNow).coerceAtLeast(0.0)
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
            payableTotal = settlement.payable,
            status = status,
            createdBy = cashierId,
            createdByName = cashierName,
            createdAt = stamp,
            sessionId = daySessionId,
            updatedAt = stamp
        )
        val refundLines = clamped.map { inp ->
            RefundLine(
                refundId = refundId,
                businessId = businessId,
                saleLineId = inp.saleLine.id,
                itemId = inp.saleLine.itemId,
                name = inp.saleLine.name,
                qty = inp.qtyReturned,
                unitPrice = inp.saleLine.unitPrice,
                // What this line was actually worth back, net of its own discount/markup
                // — the same figure that fed refundTotal, so the printed refund slip and
                // the money handed over cannot disagree.
                lineTotal = returnedLineValue(inp.saleLine, inp.qtyReturned),
                mode = inp.saleLine.mode,
                unitsPerLine = inp.saleLine.unitsPerLine,
                restock = inp.restock,
                createdAt = stamp
            )
        }
        // Allocate the payout across the tenders in the order they were entered, stopping
        // at `payable`. Trimming the LAST tender rather than scaling all of them keeps
        // every earlier row equal to the money that physically changed hands under that
        // method — a split refund of $40 cash + $30 EcoCash capped at $50 is $40 of cash
        // and $10 of EcoCash, not 71% of each, which is not a thing a drawer can hold.
        var payoutLeft = settlement.payable
        val payoutRows = payouts.filter { it.amount > 0.0 }.mapNotNull { t ->
            if (payoutLeft <= CENT) return@mapNotNull null
            val amount = minOf(t.amount, payoutLeft)
            payoutLeft -= amount
            RefundPayment(
                refundId = refundId,
                businessId = businessId,
                method = t.method,
                amount = amount,
                reference = t.reference?.takeIf { it.isNotBlank() },
                tenderCurrency = t.currency,
                tenderAmount = t.tenderAmount,
                rate = t.rate,
                createdBy = cashierId,
                createdByName = cashierName,
                createdAt = stamp
            )
        }
        // Only PHYSICAL cash moves cash-on-hand. A card/EcoCash reversal goes back the way
        // it came and never opens the drawer.
        val cashPaidNow = payoutRows.filter { it.method == "cash" }.sumOf { it.amount }
        val receiptLabel = "#${sale.receiptNo ?: sale.id.take(8)}"
        db.withTransaction {
            refundDao.insert(refund)
            if (refundLines.isNotEmpty()) refundDao.insertLines(refundLines)
            payoutRows.forEach { refundDao.insertPayment(it) }
            // Money handed back in cash LEAVES the drawer. Without this row the app's
            // cash-on-hand stayed at its pre-refund figure forever (overstating cash).
            if (cashPaidNow > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = businessId, type = "refund", amount = -cashPaidNow,
                        // Handed back over the counter, so it comes out of the TILL (§4).
                        location = CashLocation.TILL,
                        source = "cash", note = "Refund on $receiptLabel",
                        refType = "refund", refId = refundId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // Put returned goods back on the shelf (unless damaged). Box lines return
            // qty * unitsPerLine stock units — the multiplier applied once, same as
            // the checkout draw-down, only with the opposite sign.
            for (inp in clamped) {
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
            // ★ THE GOODS CAME BACK, SO THE DEBT GOES AWAY. The unpaid part of a refunded
            // sale is cancelled here — without this the customer kept owing for goods
            // sitting back on the shelf, AND was shown a refund owed to them for the same
            // money, two live balances netting to nothing (device test §B, 16 Aug).
            //
            // It is a `credit_paid` row because that is the only arithmetic both clients
            // share, and it carries [CashBasis.DEBT_CANCELLED_NOTE] so recognition knows
            // no money arrived — see the constant for why each half matters.
            if (settlement.debtRelieved > CENT && customer != null) {
                creditDao.insert(
                    CreditTxn(
                        businessId = businessId,
                        customerId = customer.id,
                        saleId = sale.id,
                        type = "credit_paid",
                        amount = settlement.debtRelieved,
                        note = "${CashBasis.DEBT_CANCELLED_NOTE} $receiptLabel".trim(),
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
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
     * What may STILL be handed back on a refund — [Refund.payableTotal] less every payout
     * already made against it.
     *
     * The ceiling [recordRefundPayout] enforces, exposed so the screen can enforce the SAME
     * one while the figure is being typed. A clamp that only exists in the repository is a
     * cashier reading $100 off the phone, telling the customer $100, and the till handing
     * over $40 — the app quietly doing the right thing with the money and the wrong thing to
     * the person at the counter.
     */
    suspend fun refundStillOwed(refundId: String): Double {
        val refund = refundDao.getById(refundId) ?: return 0.0
        return (refund.payableTotal - refundDao.paidSoFar(refundId)).coerceAtLeast(0.0)
    }

    /**
     * Pay off part or all of a refund the shop still owes a customer (prompt §11 —
     * "money over time, like change"). Writes a [RefundPayment] (records the method)
     * plus a `refund_paid` credit row that reduces the aged "we owe you" balance, and
     * flips the refund to `settled` once fully paid. Mirrors [recordChangePayment].
     *
     * CASH-ON-HAND: a CASH payout physically empties the drawer, so it also posts a
     * NEGATIVE `refund` [CashTxn]. A card/mobile-money reversal posts none. This is the
     * LATER half of the refund money — the at-refund-time half is booked in [createRefund],
     * and each payout is booked exactly once, so the two can never double-count.
     */
    suspend fun recordRefundPayout(
        refundId: String,
        tender: Tender,
        cashierId: String? = null,
        cashierName: String? = null
    ) {
        if (tender.amount <= 0.0) return
        val refund = refundDao.getById(refundId) ?: return
        // ★ NEVER PAST WHAT IS OWED. Instalments accumulate, so without this the same
        // refund could be paid out twice — and on a refund whose unpaid part was cancelled
        // off the account, a single payout of the GOODS value would hand over money the
        // sale never collected. Same ceiling the refund was created under, and the same one
        // [refundStillOwed] hands the screen so the cashier is stopped at the keypad rather
        // than silently corrected here.
        val amount = minOf(tender.amount, refundStillOwed(refundId))
        if (amount <= CENT) return
        val stamp = now()
        db.withTransaction {
            refundDao.insertPayment(
                RefundPayment(
                    refundId = refundId,
                    businessId = refund.businessId,
                    method = tender.method,
                    amount = amount,
                    reference = tender.reference?.takeIf { it.isNotBlank() },
                    tenderCurrency = tender.currency,
                    tenderAmount = tender.tenderAmount,
                    rate = tender.rate,
                    createdBy = cashierId,
                    createdByName = cashierName,
                    createdAt = stamp
                )
            )
            // Cash actually handed over now leaves the drawer.
            if (tender.method == "cash" && amount > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = refund.businessId, type = "refund", amount = -amount,
                        location = CashLocation.TILL,   // over the counter, out of the drawer
                        source = "cash",
                        note = "Refund payout on #${refund.saleReceiptNo ?: refund.saleId.take(8)}",
                        refType = "refund", refId = refundId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            if (refund.customerId != null) {
                creditDao.insert(
                    CreditTxn(
                        businessId = refund.businessId,
                        customerId = refund.customerId,
                        saleId = refund.saleId,
                        type = "refund_paid",
                        amount = amount,
                        method = tender.method,
                        createdBy = cashierId,
                        createdByName = cashierName,
                        createdAt = stamp,
                        updatedAt = stamp
                    )
                )
            }
            // paidSoFar already includes the row just inserted (same transaction).
            val paid = refundDao.paidSoFar(refundId)
            // Measured against what may be PAID, not against the goods' value: on a refund
            // whose unpaid part was cancelled off the account, the two differ and comparing
            // with the total would leave it `owed` forever, chasing a balance nobody owes.
            val newStatus = if (paid + CENT >= refund.payableTotal) "settled" else "owed"
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

    /**
     * Insert-or-update keyed by `(businessId, dedupeKey)` — the same natural key the
     * cloud upserts on, and the local UNIQUE index. Never mint a second row for a key
     * that already exists (a tombstoned alert that recurs, or a row that arrived from
     * another phone via a pull): reuse its `id` and its device-local `pushedAt`.
     */
    private suspend fun upsertNotificationByKey(n: AppNotification): AppNotification {
        val prev = notificationDao.getByDedupeKey(n.businessId, n.dedupeKey)
        val row = if (prev == null) n else n.copy(
            id = prev.id,
            createdAt = prev.createdAt,
            pushedAt = n.pushedAt ?: prev.pushedAt
        )
        notificationDao.upsert(row)
        return row
    }

    fun notificationsFlow(businessId: String): Flow<List<AppNotification>> =
        notificationDao.observeForBusiness(businessId)

    fun unreadNotificationCountFlow(businessId: String): Flow<Int> =
        notificationDao.observeUnreadCount(businessId)

    /** Read-state is SHARED: marking read here re-queues the row so every other phone
     *  sees it read too. */
    suspend fun markNotificationRead(id: String) {
        notificationDao.markRead(id, now())
        nudgeSync("notification-read")
    }

    suspend fun markAllNotificationsRead(businessId: String) {
        notificationDao.markAllRead(businessId, now())
        nudgeSync("notification-read")
    }

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
        var changed = false
        for (c in candidates) {
            seen += c.dedupeKey
            val prev = existing[c.dedupeKey]
            if (prev == null) {
                var n = AppNotification(
                    businessId = businessId, category = c.category, severity = c.severity,
                    title = c.title, body = c.body, dedupeKey = c.dedupeKey,
                    refType = c.refType, refId = c.refId, eventAt = c.eventAt,
                    createdAt = stamp, updatedAt = stamp, pendingSync = true
                )
                // pushedAt is DEVICE-LOCAL: this phone raising its own heads-up must not
                // dirty the shared row, so it is set without touching pendingSync/updatedAt.
                if (c.pushWorthy) { n = n.copy(pushedAt = stamp) }
                // Reuse any tombstoned / pulled-in row that already owns this dedupeKey
                // rather than colliding with the (businessId, dedupeKey) unique index.
                val row = upsertNotificationByKey(n)
                if (c.pushWorthy && row.pushedAt == stamp) toPush += row
                changed = true
            } else {
                // Only re-queue for upload when the CONTENT actually moved — a sweep that
                // recomputes an unchanged alert must not churn the cloud row every cycle.
                val contentChanged = prev.category != c.category || prev.severity != c.severity ||
                    prev.title != c.title || prev.body != c.body || prev.eventAt != c.eventAt ||
                    prev.refType != c.refType || prev.refId != c.refId || prev.deleted
                var n = prev.copy(
                    category = c.category, severity = c.severity, title = c.title,
                    body = c.body, eventAt = c.eventAt, refType = c.refType, refId = c.refId,
                    deleted = false
                )
                if (contentChanged) {
                    n = n.copy(updatedAt = stamp, pendingSync = true)
                    changed = true
                }
                // Each device fires its own heads-up for a newly-arrived alert; the flag
                // is local, so it never re-uploads the row.
                if (c.pushWorthy && prev.pushedAt == null) { n = n.copy(pushedAt = stamp); toPush += n }
                notificationDao.upsert(n)
            }
        }
        // Tombstone rows whose condition has cleared (resolved low stock, settled refund).
        // Only tombstone rows the ENGINE owns. Alerts written directly (expense
        // submissions, recurring postings — category "expenses") are not candidates, so a
        // blanket sweep would clear them; now that tombstones sync, that would wipe them
        // off every phone too.
        val cleared = existing.values
            .filter { it.category in ENGINE_CATEGORIES && it.dedupeKey !in seen }
            .map { it.id }
        if (cleared.isNotEmpty()) { notificationDao.tombstone(cleared, stamp); changed = true }
        // Only touch the network when the feed actually moved.
        if (changed) nudgeSync("notifications")
        return toPush
    }

    /**
     * Cross-device heads-up delivery (BUG A fix). A notification row that ARRIVED on this
     * phone via a pull — most importantly an event alert like a cashier's expense
     * submission, which the sweep deliberately never re-derives — otherwise sits silently
     * in the feed and never buzzes. This fires a system heads-up for every live, unread
     * row this device has not yet pushed AND whose [AppNotification.audience] matches the
     * device's current role, then stamps [AppNotification.pushedAt] so it fires once.
     *
     * [isAdmin] is the signed-in session's role ("all" reaches everyone, "cashier" only
     * non-admins, "admin"/unknown only admins — see [NotificationEngine.audienceMatches]).
     * The pushedAt stamp is DEVICE-LOCAL: written via [NotificationDao.markPushed] without
     * touching updatedAt/pendingSync, exactly like the sweep, so it never dirties the
     * shared row for upload. Returns the rows fired so the Context-owning caller posts them.
     */
    suspend fun fireUnpushedHeadsUps(isAdmin: Boolean): List<AppNotification> {
        val stamp = now()
        val fired = ArrayList<AppNotification>()
        for (n in notificationDao.unpushed()) {
            if (!NotificationEngine.audienceMatches(n.audience, isAdmin)) continue
            notificationDao.markPushed(n.id, stamp)
            fired += n.copy(pushedAt = stamp)
        }
        return fired
    }

    // ---- admin⇄cashier approval channel (Phase 3, staff_requests) --------

    /** Admin-side queue: pending requests awaiting a decision (oldest first). */
    fun pendingStaffRequestsFlow(businessId: String): Flow<List<StaffRequest>> =
        staffRequestDao.observePending(businessId)

    /** Admin Alerts tab badge input: how many requests are still pending. */
    fun pendingStaffRequestCountFlow(businessId: String): Flow<Int> =
        staffRequestDao.observePendingCount(businessId)

    /** Cashier-side status surface: this cashier's own recent requests, newest first. */
    fun myStaffRequestsFlow(businessId: String, requestedBy: String): Flow<List<StaffRequest>> =
        staffRequestDao.observeMine(businessId, requestedBy)

    /**
     * A cashier RAISES an approval request (§3). Inserts a pending row (pendingSync=true so
     * it goes UP insert-once) and mints an admin-facing feed notification so the owner's
     * phone buzzes. The notification is stamped `pushedAt` here so the ORIGINATING (cashier)
     * phone does not buzz itself for its own request; the audience="admin" match means the
     * admin phone fires it once via [fireUnpushedHeadsUps] after the pull brings it down.
     * Returns the created request so the caller can track its status.
     */
    suspend fun submitStaffRequest(
        type: String,
        targetType: String?,
        targetId: String?,
        targetName: String?,
        amount: Double?,
        note: String?,
        byId: String?,
        byName: String?,
    ): StaffRequest {
        val biz = businessDao.getOnce() ?: throw IllegalStateException("No business")
        val stamp = now()
        val req = StaffRequest(
            businessId = biz.id, type = type, targetType = targetType, targetId = targetId,
            targetName = targetName, amount = amount, note = note?.takeIf { it.isNotBlank() },
            requestedBy = byId, requestedByName = byName, status = "pending",
            createdAt = stamp, updatedAt = stamp, pendingSync = true
        )
        staffRequestDao.upsert(req)
        val amountLabel = amount?.let { " ${fmtMoney(it)}" } ?: ""
        val n = AppNotification(
            businessId = biz.id, category = "requests", severity = "warn",
            title = "Approval requested",
            body = "${byName ?: "A cashier"} needs approval for a $type$amountLabel" +
                (targetName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            dedupeKey = "reqpending:${req.id}", audience = "admin",
            refType = "staff_request", refId = req.id,
            eventAt = stamp, createdAt = stamp, pushedAt = stamp,   // don't buzz the requester's own phone
            updatedAt = stamp, pendingSync = true
        )
        upsertNotificationByKey(n)
        nudgeSync("staff-request-submitted")
        return req
    }

    /**
     * An admin DECIDES a request (§3). Sets status/decidedBy/decidedAt (pendingSync=true so
     * it goes UP merge-upsert — only admin devices write decided rows and they pass the
     * cloud UPDATE policy). It then:
     *   • TOMBSTONES the original "reqpending:<id>" admin notification — the action is now
     *     resolved, so it must clear off EVERY admin phone (tombstones sync), not linger as
     *     a stale "needs approval" item. A denied request is likewise done with.
     *   • Mints a decision notification aimed at the CASHIER (audience="cashier") so the
     *     requesting phone buzzes with the outcome; `pushedAt` is stamped so THIS admin
     *     phone doesn't buzz itself for a cashier-facing alert.
     *
     * [approvedAmount] lets the admin OVERRIDE the requested figure before confirming (they
     * set/confirm the final number). Null ⇒ approve the amount as requested.
     *
     * ★ EXECUTE-THE-ANSWER: for an APPROVED `credit_limit` request this admin device applies
     * the approved amount to the customer via the normal [saveCustomer] path — so the change
     * is marked pendingSync and rides the ordinary customer sync out to every device — then
     * records [StaffRequest.applied]=true. This is IDEMPOTENT: the pending-status guard above
     * plus the `applied` flag stop any double-application if the decided row is re-pulled.
     *
     * ★ The same machinery now carries `safe_withdrawal` (§4): approving one moves the
     * approved amount OUT OF THE SAFE AND INTO THE TILL via [withdrawFromSafe] — the
     * physical act of the owner opening the safe — so the cashier can then pay from a
     * drawer that has the money. Because MONEY MOVES here, idempotency is not optional:
     * the `status != "pending"` guard at the top and the `applied` flag mean a re-pulled
     * decided row can never open the safe twice.
     */
    suspend fun decideStaffRequest(
        id: String, approve: Boolean, byId: String?, byName: String?, approvedAmount: Double? = null
    ): StaffRequest? {
        val req = staffRequestDao.getById(id) ?: return null
        if (req.status != "pending") return req   // already decided — idempotent no-op
        val stamp = now()
        val effAmount = if (approve) (approvedAmount ?: req.amount) else req.amount
        // Apply the approved answer on THIS device before flipping status.
        var applied = false
        if (approve && !req.applied && req.type == "credit_limit" &&
            req.targetType == "customer" && req.targetId != null
        ) {
            customerById(req.targetId)?.let { cust ->
                saveCustomer(cust.copy(creditLimit = effAmount))
                applied = true
            }
        }
        if (approve && !req.applied && req.type == "safe_withdrawal" && (effAmount ?: 0.0) > CENT) {
            // Open the safe for exactly the approved amount and put it in the till, so
            // the requester can pay from the drawer. Clamped to what the safe holds.
            val moved = withdrawFromSafe(
                businessId = req.businessId,
                amount = effAmount ?: 0.0,
                reason = req.note ?: req.targetName,
                cashierId = byId, cashierName = byName
            )
            applied = moved > CENT
        }
        val decided = req.copy(
            status = if (approve) "approved" else "denied",
            amount = effAmount,
            decidedBy = byId, decidedByName = byName, decidedAt = stamp,
            applied = applied,
            updatedAt = stamp, pendingSync = true
        )
        staffRequestDao.upsert(decided)
        // Resolve the pending-approval alert on every admin phone (tombstones sync).
        notificationDao.getByKey(req.businessId, "reqpending:$id")?.let {
            notificationDao.tombstone(listOf(it.id), stamp)
        }
        val amountLabel = req.amount?.let { " ${fmtMoney(it)}" } ?: ""
        val n = AppNotification(
            businessId = req.businessId, category = "requests",
            severity = if (approve) "info" else "warn",
            title = if (approve) "Request approved" else "Request denied",
            body = "Your ${req.type}$amountLabel request was " +
                (if (approve) "approved" else "denied") +
                (byName?.let { " by $it" } ?: ""),
            dedupeKey = "reqdecided:$id", audience = "cashier",
            refType = "staff_request", refId = id,
            eventAt = stamp, createdAt = stamp, pushedAt = stamp,   // don't buzz the deciding admin's own phone
            updatedAt = stamp, pendingSync = true
        )
        upsertNotificationByKey(n)
        nudgeSync("staff-request-decided")
        return decided
    }

    /**
     * The cashier CONSUMED an approved request (applied the discount to the still-open
     * sale). This is DEVICE-LOCAL state on the cashier side: RLS blocks the cashier
     * updating a decided cloud row, so we set `applied` WITHOUT pendingSync — never letting
     * a cashier device dirty a decided row for push (it would fail RLS every cycle). The
     * cloud `applied` mirror stays false unless an admin device writes it. See
     * [StaffRequest.applied] and [StaffRequestDao.markAppliedLocal].
     */
    suspend fun markRequestApplied(id: String) {
        staffRequestDao.markAppliedLocal(id)
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
        logAudit(
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
     *
     * CASH-ON-HAND: a void reverses a payout, so any money that actually left the drawer
     * comes back — a POSITIVE `adjust` [CashTxn] for the CASH already paid out on this
     * refund (the entity's own rule: a correction is a new `adjust` row, never an edit).
     * A refund still fully owed, or one reversed by card/mobile money, never touched cash,
     * so nothing is written and the drawer is not inflated.
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
            // Put back only what actually left the drawer in cash. Nothing paid out (or
            // paid out by card/EcoCash) => no cash row at all.
            val cashPaidOut = refundDao.cashPaidSoFar(refundId)
            if (cashPaidOut > CENT) {
                cashTxnDao.insert(
                    CashTxn(
                        businessId = r.businessId, type = "adjust", amount = cashPaidOut,
                        // The payout left the drawer, so the reversal goes back into it.
                        location = CashLocation.TILL,
                        source = "cash",
                        note = "Void refund on #${r.saleReceiptNo ?: r.saleId.take(8)}",
                        refType = "refund", refId = refundId,
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // ★ BOTH LEDGERS THE REFUND TOUCHED, measured against the right figures — see
            // [planRefundVoid]. This used to credit the GOODS value against a liability
            // that was only ever the PAYABLE one, and never restored the debt the refund
            // had cancelled.
            val undo = planRefundVoid(
                refundTotal = r.refundTotal,
                payableTotal = r.payableTotal,
                paidOut = refundDao.paidSoFar(refundId),
            )
            if (r.customerId != null && undo.refundPaid > CENT) {
                creditDao.insert(
                    CreditTxn(
                        businessId = r.businessId, customerId = r.customerId, saleId = r.saleId,
                        type = "refund_paid", amount = undo.refundPaid, note = "Void refund",
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            // The goods are back with the customer, so they owe for them again. A NEW lot,
            // never a deletion of the cancellation: that really happened, and a delete on
            // one device races a pull on the other.
            if (r.customerId != null && undo.debtRestored > CENT) {
                creditDao.insert(
                    CreditTxn(
                        businessId = r.businessId, customerId = r.customerId, saleId = r.saleId,
                        type = "credit_owed", amount = undo.debtRestored,
                        note = "${CashBasis.DEBT_RESTORED_NOTE} #${r.saleReceiptNo ?: ""}".trim(),
                        createdBy = cashierId, createdByName = cashierName,
                        createdAt = stamp, updatedAt = stamp
                    )
                )
            }
            refundDao.softDelete(refundId, stamp)
            logAudit(
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
            logAudit(
                AuditEntry(
                    businessId = businessId, action = "debt_writeoff", entityType = "customer",
                    entityId = customerId, summary = "Wrote off ${fmtMoney(amount)}",
                    createdBy = cashierId, createdByName = cashierName
                )
            )
        }
    }

    private fun fmtMoney(n: Double): String = String.format(java.util.Locale.US, "%.2f", n)

    /** Count of local rows not yet pushed to the cloud — feeds the "not synced" alert.
     *  B3/B4 excluded expenses, cash, suppliers and purchase orders while they were
     *  local-only; they sync now, so leaving them out would UNDER-report and make the
     *  indicator lie. All five are counted.
     *
     *  `day_closes` and `outside_funds` are deliberately EXCLUDED: they are sync-READY but
     *  no push is wired (the cloud schema has no such tables), so their rows stay
     *  `pendingSync = 1` forever. Counting them would pin the "not synced" alert on
     *  permanently and teach the owner to ignore it. */
    suspend fun pendingSyncCount(): Int =
        businessDao.pending().size + itemDao.pending().size + saleDao.pendingSales().size +
            customerDao.pending().size + creditDao.pending().size + refundDao.pending().size +
            mobileMoneyDao.pending().size + expenseDao.pending().size +
            cashTxnDao.pending().size + supplierDao.pending().size +
            poDao.pending().size + poDao.pendingLines().size

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

        /**
         * The TILL FLOAT TARGET (§2/§3): how much change money the owner wants left in
         * the drawer after a close, and the level "Top up float" brings it back to. Set
         * inline in the close/top-up flows — deliberately NOT buried in Settings, because
         * it is a decision the owner makes while looking at the actual cash.
         */
        const val KEY_FLOAT_TARGET = "cash_float_target"
        /** Default float target when the owner has never set one. */
        const val DEFAULT_FLOAT_TARGET = 100.0

        /**
         * How far a close-of-day count may miss before a NOTE is required (§2). Small
         * rounding noise should not force typing; a real discrepancy should. Admin-set.
         */
        const val KEY_VARIANCE_NOTE_THRESHOLD = "cash_variance_note_threshold"
        /** Default: anything over a dollar has to be explained. */
        const val DEFAULT_VARIANCE_NOTE_THRESHOLD = 1.0

        /**
         * The other two thirds of the shop's policy ([ShopPolicy]). These keys were
         * private to the view model until the rules started syncing; they live here now
         * because the settings screen, the sync engine and the cash-up all have to read
         * the SAME key, and three copies of a string literal is how one of them quietly
         * ends up reading a setting nobody writes.
         */
        const val KEY_MAX_ITEM_DISCOUNT = "max_item_discount"
        const val KEY_DISCOUNT_THRESHOLD = "discount_threshold_pct"

        /** No per-line ceiling until somebody sets one. */
        const val DEFAULT_MAX_ITEM_DISCOUNT = 0.0
        /** Matches `businesses.discount_threshold default 5` on the shared schema, so a
         *  shop that has never touched either side gets the same answer from both. */
        const val DEFAULT_DISCOUNT_THRESHOLD_PCT = 5.0

        /**
         * When a PERSON last changed the policy on this device (epoch ms, 0 = never).
         * Compared against `businesses.updated_at` to settle which side is newer — see
         * [planShopPolicySync]. A value adopted FROM the cloud must never stamp it.
         */
        const val KEY_SHOP_POLICY_CHANGED_AT = "shop_policy_changed_at"

        /** Permanent per-device receipt prefix (see [deviceCode]). Write-once. */
        const val KEY_DEVICE_CODE = "device_receipt_code"
        /** Confusable-free alphabet — no I, O, 0 or 1, so a code is safe to read aloud. */
        private const val DEVICE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val DEVICE_CODE_LEN = 3

        /**
         * Build the funding plan for [amount] under [mode], given the two live balances.
         *
         * ON THE COMPANION, not the instance, ON PURPOSE: this function decides where
         * every dollar of a payment comes from, so it must be checkable WITHOUT a
         * database. [com.portionspot.pos.data.CashFundingTest] calls it directly and
         * asserts that every plan accounts for exactly the amount being paid — a plan
         * that did not add up would silently create or destroy money in the ledger.
         *
         * Modes, mirroring the order the owner actually reaches for money
         * (**TILL → SAFE → OUTSIDE FUNDS → abort**):
         *  - "till"      — take it all from the drawer (the caller offers this only when
         *                  the drawer covers it).
         *  - "safe"      — take it all from the safe (admin-approved, every time).
         *  - "waterfall" — TILL first, then SAFE, then whatever is left becomes PAYABLE.
         *                  This is the "take what's available" path, now aware of BOTH
         *                  locations.
         *  - "capital"   — the owner covers it out of pocket (shop cash untouched).
         *  - "loan"      — borrowed money covers it (a liability; shop cash untouched).
         *  - "none"      — pay nothing now; the whole amount is owed.
         *  - "cash"      — LEGACY, kept working: pay the FULL amount from shop cash,
         *                  drawer first and the remainder from the safe. It never creates
         *                  a payable, which is exactly what it did before the split.
         *
         * A negative balance (an over-drawn drawer) supplies nothing — it is clamped to
         * zero rather than treated as spendable.
         */
        fun planFunding(amount: Double, mode: String, till: Double, safe: Double): FundingPlan {
            val want = amount.coerceAtLeast(0.0)
            if (want <= CENT) return FundingPlan()
            val t = till.coerceAtLeast(0.0)
            val s = safe.coerceAtLeast(0.0)
            return when (mode) {
                "capital" -> FundingPlan(outside = want, outsideKind = "capital")
                "loan" -> FundingPlan(outside = want, outsideKind = "loan")
                "none" -> FundingPlan(payable = want)
                "safe" -> FundingPlan(fromSafe = want)
                "till" -> FundingPlan(fromTill = want)
                "available", "waterfall" -> {
                    val fromTill = want.coerceAtMost(t)
                    val fromSafe = (want - fromTill).coerceAtMost(s)
                    FundingPlan(
                        fromTill = fromTill,
                        fromSafe = fromSafe,
                        payable = (want - fromTill - fromSafe).coerceAtLeast(0.0)
                    )
                }
                // "cash" (legacy): the whole amount comes out of shop cash, drawer first.
                else -> {
                    val fromTill = want.coerceAtMost(t)
                    FundingPlan(fromTill = fromTill, fromSafe = want - fromTill)
                }
            }
        }

        /**
         * Split one debt repayment into the three things it does, given what the customer
         * owed and HOW they paid.
         *
         * ON THE COMPANION for the same reason [planFunding] is: this decides whether real
         * money enters the drawer, and it must be checkable without Room. `RepaymentTest`
         * asserts the two halves that matter — that the credit ledger does the identical
         * thing whatever the tender, and that only cash moves the till.
         *
         *  - [RepaymentPlan.paid]   settles debt (`credit_paid`), never past zero.
         *  - [RepaymentPlan.excess] is an OVERPAYMENT and becomes `change_owed`: money the
         *    shop now owes back, rather than a negative debt.
         *  - [RepaymentPlan.cashIn] is what physically arrives at the counter. It is the
         *    WHOLE [amount], not just the settling part — an overpayment is handed across
         *    the counter too, and the drawer holds it while the ledger records that it is
         *    owed back. Zero for any non-cash tender: EcoCash and a card swipe settle the
         *    debt without a note ever reaching the till.
         *
         * [method] is a checkout tender code ([com.portionspot.pos.payments.PaymentMethod]);
         * only `cash` opens a drawer, the same rule [checkout] and [createRefund] apply.
         */
        fun planRepayment(amount: Double, debt: Double, method: String): RepaymentPlan {
            val given = amount.coerceAtLeast(0.0)
            if (given <= CENT) return RepaymentPlan()
            val owed = debt.coerceAtLeast(0.0)
            val paid = minOf(given, owed)
            return RepaymentPlan(
                paid = paid,
                excess = given - paid,
                cashIn = if (method.trim().equals("cash", ignoreCase = true)) given else 0.0
            )
        }
    }

    /**
     * What one debt repayment does: how much debt it settles, how much of it was an
     * overpayment the shop now owes back, and how much cash actually arrived in the till.
     * Built by [planRepayment]; written by [recordRepayment].
     */
    data class RepaymentPlan(
        val paid: Double = 0.0,
        val excess: Double = 0.0,
        val cashIn: Double = 0.0
    )

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
     * This device's permanent short code — the thing that makes receipt refs unique
     * ACROSS phones. Three characters from a confusable-free alphabet (no I/O/0/1),
     * drawn once from a secure random source and then written to the local settings
     * table forever. Never regenerated: it survives restarts, sign-outs and account
     * switches, because renaming a device mid-life would let an old ref repeat.
     *
     * Not derived from ANDROID_ID on purpose — that needs a Context down here and is
     * per-app-signing-key anyway; 32^3 random codes are ample for the handful of tills
     * one shop runs.
     */
    private suspend fun deviceCode(): String {
        settingDao.get(KEY_DEVICE_CODE)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val rnd = java.security.SecureRandom()
        val code = (1..DEVICE_CODE_LEN)
            .map { DEVICE_CODE_ALPHABET[rnd.nextInt(DEVICE_CODE_ALPHABET.length)] }
            .joinToString("")
        settingDao.put(Setting(KEY_DEVICE_CODE, code))
        return code
    }

    /**
     * Receipt reference for a new sale: `<deviceCode>-NNNN`, e.g. `K7Q-0013`.
     *
     * The NNNN half is the old per-business counter in the local settings table —
     * kept because cashiers read it aloud and it stays small and sequential per till.
     * The device-code half is what fixes the multi-device data loss: the cloud keys
     * `sales` rows BY REF (see [buildSalePush]) and pushes fresh sales with
     * ignore-duplicates, so before this, two phones both minting "0013" meant the
     * second phone's sale was silently dropped on push and skipped on pull. Different
     * codes mean the refs can no longer collide.
     *
     * Existing sales keep whatever ref they already have — nothing renumbers history,
     * and nothing anywhere parses a ref as a number (the web POS already ships refs
     * like `PSM-260526-6315`, so a mixed-format ref column is normal).
     */
    private suspend fun nextReceiptNo(businessId: String): String {
        val key = "receiptSeq:$businessId"
        val next = (settingDao.get(key)?.toIntOrNull() ?: 0) + 1
        settingDao.put(Setting(key, next.toString()))
        return "${deviceCode()}-${next.toString().padStart(4, '0')}"
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

// ── Overselling: is this cart asking for more than the shelf holds? ─────────────
//
// Pure, so the decision can be tested on its own and cannot drift from the draw-down it
// describes. It answers ONLY "would this be an oversell"; whether the cashier may then go
// ahead is [com.portionspot.pos.auth.Capability.SELL_BELOW_STOCK], decided in the ViewModel.
//
// ★ Nothing here clamps anything. A shortfall is a fact about the shop, and the sale's
// movement is written in full (-3 when 3 were sold) precisely so the fact survives into the
// ledger where a stock take can find it. Clamping the movement would balance the number and
// lose the shortfall, which is how a shelf silently disagrees with its record.

/** A hundredth of a unit — the same tolerance the money and quantity comparisons use. */
private const val UNIT_EPS = 0.005

/**
 * Units of [item] one cart line takes off the shelf.
 *
 * Mirrors the checkout draw-down exactly, INCLUDING which column it comes out of: a
 * measured product's quantity is its own decimal (2.35 kg is 2.35 off `stockMeasured`),
 * everything else is whole units, so a box line of 2 with a pack size of 4 is 8. Reading
 * the wrong basis here would warn about the wrong number, which is worse than not warning.
 *
 * The line's own [CartLine.measured] flag counts too, matching checkout: the item row is
 * the authority on which column holds stock, but a line the cashier rang up by measure
 * draws a measure.
 */
fun stockUnitsDrawn(item: Item, line: CartLine): Double =
    if (item.isMeasured || line.measured) line.qty else line.stockUnits

/** Everything [cart] asks for of [item], across however many lines/price modes it sits on. */
fun cartStockUnits(item: Item, cart: List<CartLine>): Double =
    cart.filter { it.itemId == item.id }.sumOf { stockUnitsDrawn(item, it) }

/**
 * Is this item's recorded on-hand actually BELOW zero — the state that needs a stock take?
 *
 * The single definition, shared by the product badge, the inventory list, the dashboard
 * count and the admin notification, so those four can never disagree about the same shelf
 * (they have before, over the low-stock rule, and the owner got the false alarms).
 *
 * ★ Not a bare `< 0`. A measured item's on-hand is a running sum of decimals — 5 kg less
 * 2.35 less 2.65 does not land on 0.0 in binary — and a residue of -4e-16 is an empty
 * shelf, not a deficit. Testing it strictly would have every measured product in the shop
 * eventually announce a stock take it does not need, which is how an alert stops being
 * read. Counted items are whole numbers and are unaffected either way.
 */
fun Item.stockIsShort(): Boolean = onHand < -UNIT_EPS

/**
 * A cart that asks for more of an item than the shop's figure says is there.
 *
 * [onHand] is the figure as the till holds it — which can itself be NEGATIVE, when an
 * earlier oversell has already been rung up. That is a different situation from a busy
 * shelf and reads differently ([alreadyShort]): the shop is not merely out, its record is
 * wrong and only a count can fix it.
 */
data class Oversell(
    val itemName: String,
    /** What the till says is on the shelf right now. May be negative. */
    val onHand: Double,
    /** What the cart would take, in the same basis as [onHand]. */
    val requested: Double,
    /** True for a measured product, whose figures read in [unitLabel] rather than as a count. */
    val measured: Boolean,
    /** kg / L / m … for a measured product; blank for anything counted. */
    val unitLabel: String,
) {
    /** How far past the recorded stock this cart goes. Always > 0 for a real [Oversell]. */
    val shortfall: Double get() = requested - onHand
    /** The record was ALREADY below zero before this sale — a data problem, not a busy day.
     *  Same rule and same tolerance as [Item.stockIsShort], applied to the figure already
     *  captured here. */
    val alreadyShort: Boolean get() = onHand < -UNIT_EPS
}

/**
 * Would moving the cart from [current] to [next] sell [item] past its on-hand?
 *
 * Null means "just do it", and three things earn that answer:
 *
 *  - the item does not track stock at all, so it has no on-hand to exceed and there is
 *    nothing to warn about, ever;
 *  - the cart lands exactly ON the on-hand — selling the last two of two is the ordinary
 *    end of a shelf, not an incident, so the comparison is strictly-greater by [UNIT_EPS]
 *    and not >=;
 *  - the cart is not asking for MORE than it already was. A cashier reducing a line that is
 *    over (or removing it) is moving toward the truth; interrupting them to warn about a
 *    state they are in the middle of fixing would train them to dismiss the warning.
 */
fun oversellFor(item: Item, current: List<CartLine>, next: List<CartLine>): Oversell? {
    if (!item.trackStock) return null
    val requested = cartStockUnits(item, next)
    val onHand = item.onHand
    if (requested <= onHand + UNIT_EPS) return null
    if (requested <= cartStockUnits(item, current) + UNIT_EPS) return null
    return Oversell(
        itemName = item.name,
        onHand = onHand,
        requested = requested,
        measured = item.isMeasured,
        unitLabel = if (item.isMeasured) item.unit.trim().ifBlank { "unit" } else "",
    )
}
