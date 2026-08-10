package com.portionspot.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.CartLine
import com.portionspot.pos.data.CashBasis
import com.portionspot.pos.data.CashBasisSaleRow
import com.portionspot.pos.data.CashLocation
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.DayClose
import com.portionspot.pos.data.OutsideFund
import com.portionspot.pos.data.OutsideFundTotals
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.CustomerWithBalance
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.Supplier
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.AuditEntry
import com.portionspot.pos.data.CashierDay
import com.portionspot.pos.data.DebtAging
import com.portionspot.pos.data.DebtAgingRow
import com.portionspot.pos.data.MethodBreakdown
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.PosRepository
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.PurchaseOrderWithLines
import com.portionspot.pos.data.RefundLineInput
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.RefundWithLines
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalePayment
import com.portionspot.pos.data.isEditable
import com.portionspot.pos.data.SalesSummary
import com.portionspot.pos.data.SaleStamp
import com.portionspot.pos.data.StaffRequest
import com.portionspot.pos.data.StockMovement
import com.portionspot.pos.data.Tender
import com.portionspot.pos.data.TopProduct
import com.portionspot.pos.auth.Capability
import com.portionspot.pos.auth.Permissions
import com.portionspot.pos.auth.isCapabilityAllowed
import com.portionspot.pos.auth.lockedCapabilities
import com.portionspot.pos.payments.PaynowClient
import com.portionspot.pos.payments.PaynowInit
import com.portionspot.pos.payments.PaynowPoll
import com.portionspot.pos.sync.Connection
import com.portionspot.pos.sync.ConnectionTest
import com.portionspot.pos.sync.SyncManager
import com.portionspot.pos.sync.SyncOutcome
import com.portionspot.pos.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/** One-shot info shown after a successful cash sale. */
data class LastReceipt(
    val sale: SaleEntity,
    val lines: List<SaleLine>
) {
    val itemCount: Int get() = lines.sumOf { it.qty }.toInt()
}

/** Date windows the Reports screen can summarise over. */
enum class ReportRange(val label: String) {
    TODAY("Today"),
    WEEK("Last 7 days"),
    MONTH("This month"),
    ALL("All time")
}

/** One labelled bar in the Dashboard's 7-day revenue chart (read model). */
data class DayBar(val label: String, val total: Double)

/** One credit-ledger entry joined with its customer's display name (read model). */
data class CreditLedgerRow(
    val txn: CreditTxn,
    val customerName: String,
    /** Receipt ref of the sale that created this row (for "which transaction"); null if none. */
    val saleRef: String? = null
)

private const val DAY_MS = 24L * 60 * 60 * 1000

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * How the till is being run. [Local] (default) is a phone-only shop: full app
 * features + admin panel, no cloud account, an optional device PIN. [Cloud] is the
 * opt-in team mode: Supabase login, per-cashier PINs, staff management, attribution
 * and sync — the original behaviour, now behind a choice.
 */
enum class AppMode { Local, Cloud }

class PosViewModel(
    private val repo: PosRepository,
    private val sync: SyncManager,
    private val authManager: com.portionspot.pos.auth.AuthManager,
) : ViewModel() {

    private val businessId = MutableStateFlow<String?>(null)

    // ── App mode / first-run onboarding / local device PIN ────────────────
    // Default Local so a fresh install is usable immediately (no login wall).
    private val _appMode = MutableStateFlow(AppMode.Local)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    /** False until the first-run "Set a PIN / Keep it open" choice has been made. */
    private val _onboarded = MutableStateFlow(false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** Whether a local device PIN is set (drives the lock screen + Settings toggle). */
    private val _hasLocalPin = MutableStateFlow(false)
    val hasLocalPin: StateFlow<Boolean> = _hasLocalPin.asStateFlow()

    /** False until app-mode/onboarding/PIN flags have been read from storage. The gate
     *  shows nothing until this is true, so a returning user never sees the welcome
     *  screen flash before the persisted choice loads. */
    private val _bootLoaded = MutableStateFlow(false)
    val bootLoaded: StateFlow<Boolean> = _bootLoaded.asStateFlow()

    // ── Device-local cash settings ──
    // Declared UP HERE, ahead of every flow that reads them, on purpose: a property
    // initializer that reads a field declared further down sees null, and several of the
    // cash flows below combine these DIRECTLY (not inside a deferred lambda). Keep them
    // first. They must also stay above init{} — viewModelScope is Main.immediate, so the
    // coroutines init{} launches run during construction (see the _shopPrefs note below).

    /** Opening cash float (B3): the starting cash-on-hand the admin sets. It belongs to
     *  the TILL — the float IS the drawer's starting money. */
    private val _openingFloat = MutableStateFlow(0.0)
    val openingFloat: StateFlow<Double> = _openingFloat.asStateFlow()

    /** How much change money the owner wants left in the drawer after a close (§2/§3).
     *  Settable INLINE in the close / top-up flows, never buried in Settings. */
    private val _floatTarget = MutableStateFlow(PosRepository.DEFAULT_FLOAT_TARGET)
    val floatTarget: StateFlow<Double> = _floatTarget.asStateFlow()

    /** How far a close-of-day count may miss before a NOTE is required (§2). */
    private val _varianceNoteThreshold =
        MutableStateFlow(PosRepository.DEFAULT_VARIANCE_NOTE_THRESHOLD)
    val varianceNoteThreshold: StateFlow<Double> = _varianceNoteThreshold.asStateFlow()

    // The signed-in cashier, pushed in from AuthGate (see MainActivity). Stamped onto
    // refunds now, and onto the other financial writes as Phase-2 wiring continues.
    private var currentCashierId: String? = null
    private var currentCashierName: String? = null
    private var currentIsAdmin: Boolean = false

    // Per-person permissions for the signed-in user (see auth/Permissions.kt). Admins are
    // never gated. Exposed as flows so the UI can hide/disable controls reactively, and
    // read synchronously via [can] for the handler-side (defense-in-depth) block.
    private val _currentIsAdmin = MutableStateFlow(false)
    private val _currentPermissions = MutableStateFlow(Permissions.EMPTY)

    /** Mirror of the signed-in cashier id as a flow, so the cashier's own-requests feed
     *  (Phase 3) re-subscribes when the active session changes on a shared device. */
    private val _cashierId = MutableStateFlow<String?>(null)

    /**
     * Capabilities the SHOP has locked for everyone but the admin, read off the business
     * profile's `lock_*` switches. Empty until the business loads, which is the right
     * default: an unknown lock state must not invent restrictions the owner never set.
     */
    private val _shopLocks: StateFlow<Set<Capability>> =
        repo.businessFlow
            .map { b -> b?.lockedCapabilities() ?: emptySet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The set of capabilities the current user is allowed to exercise right now (admin =
     *  all). Collect this in Compose to gate visible controls. */
    val allowedCaps: StateFlow<Set<Capability>> =
        combine(_currentIsAdmin, _currentPermissions, _shopLocks) { admin, perms, locks ->
            Capability.entries.filter { isCapabilityAllowed(admin, perms, it, locks) }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Synchronous capability check for action handlers (the second, non-UI gate).
     *  Goes through the SAME [isCapabilityAllowed] the UI gate uses — two gates, one rule,
     *  so a control that is visible can never be one the handler then refuses (or worse). */
    fun can(cap: Capability): Boolean =
        isCapabilityAllowed(_currentIsAdmin.value, _currentPermissions.value, cap, _shopLocks.value)

    /** Is the signed-in session an ADMIN? Drives the §4 safe gate in the UI: an admin
     *  opens the safe inline, anyone else has to ask the owner. The real enforcement is
     *  in the action handlers, which read [currentIsAdmin] directly. */
    val isAdmin: StateFlow<Boolean> = _currentIsAdmin.asStateFlow()

    fun setCurrentCashier(
        id: String?,
        name: String?,
        isAdmin: Boolean = false,
        permissions: Permissions = Permissions.EMPTY,
    ) {
        currentCashierId = id
        currentCashierName = name
        currentIsAdmin = isAdmin
        _currentIsAdmin.value = isAdmin
        _currentPermissions.value = permissions
        _cashierId.value = id
    }

    /** Pull the signed-in user's own grants from pos_staff (admin edits reach the device).
     *  Wired to app foreground (PosApp) and the manual "Sync now". No-op offline/local. */
    fun refreshMyPermissions() {
        viewModelScope.launch { authManager.refreshCurrentPermissions() }
    }

    // Discounts are RECORDED, not approved (§Job 2): a cashier who holds the give_discounts
    // capability applies a discount directly and the admin reviews it after the fact in the
    // console's "Discounts given" list — there is no live per-discount PIN interruption.

    val business: StateFlow<Business?> =
        repo.businessFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<Item>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.itemsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentSales: StateFlow<List<SaleEntity>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.recentSalesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayTakings: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { repo.takingsSinceFlow(it, startOfToday()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    val todayCount: StateFlow<Int> =
        businessId.filterNotNull()
            .flatMapLatest { repo.saleCountSinceFlow(it, startOfToday()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val customers: StateFlow<List<CustomerWithBalance>> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                combine(repo.customersFlow(bid), repo.balancesFlow(bid)) { custs, balances ->
                    val byId = balances.associate { it.customerId to it.balance }
                    custs.map { CustomerWithBalance(it, byId[it.id] ?: 0.0) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whole-shop credit ledger (newest first) with customer names resolved. */
    val creditLedger: StateFlow<List<CreditLedgerRow>> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                combine(
                    repo.creditLedgerFlow(bid),
                    repo.customersFlow(bid),
                    repo.saleRefsFlow(bid)
                ) { txns, custs, refs ->
                    val nameById = custs.associate { it.id to it.name }
                    val refById = refs.associate { it.id to (it.receiptNo ?: it.id.takeLast(6).uppercase()) }
                    txns.map { txn ->
                        CreditLedgerRow(
                            txn = txn,
                            customerName = nameById[txn.customerId] ?: "Unknown",
                            saleRef = txn.saleId?.let { refById[it] ?: it.takeLast(6).uppercase() }
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All posted/history expenses for the shop (non-template), newest date first. */
    val expenses: StateFlow<List<Expense>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.expensesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Submitted expenses awaiting an admin decision (B3, §9.2). */
    val pendingExpenses: StateFlow<List<Expense>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.pendingExpensesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active recurring expense schedules (templates) for the admin to manage. */
    val recurringTemplates: StateFlow<List<Expense>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.recurringTemplatesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The cash-on-hand running balance = opening float + Σ cash-ledger movements (B3). */
    val cashOnHand: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                combine(repo.cashMovementsSumFlow(bid), _openingFloat) { moved, float -> float + moved }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Shop-wide accounts payable (short-funded expenses the shop still owes). */
    val payablesTotal: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { repo.payablesTotalFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Shop-wide owner contributions (expenses the owner covered out of pocket). */
    val ownerContributions: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { repo.ownerContributionsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** The cash ledger movements (newest first) for the admin cash view. */
    val cashTxns: StateFlow<List<CashTxn>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.cashTxnsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ══════════════════════════════════════════════════════════════════════
    //  TILL AND SAFE (§1–§6) — two on-site cash locations, no bank
    // ══════════════════════════════════════════════════════════════════════

    /** Live TILL balance = opening float + the movements booked to the drawer. */
    val tillBalance: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                combine(repo.tillBalanceFlow(bid), _openingFloat) { moved, float -> float + moved }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Live SAFE balance — the day's takings, filled by closing the day. */
    val safeBalance: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { repo.safeBalanceFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** The close-of-day history, newest first — per cashier, so patterns are visible. */
    val dayCloses: StateFlow<List<DayClose>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.dayClosesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The most recent close, for the "last closed …" line. */
    val latestDayClose: StateFlow<DayClose?> =
        businessId.filterNotNull()
            .flatMapLatest { repo.latestDayCloseFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Owner / lender money ledger, newest first. */
    val outsideFunds: StateFlow<List<OutsideFund>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.outsideFundsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Running "put in / taken out / borrowed / repaid" totals. */
    val outsideFundTotals: StateFlow<OutsideFundTotals> =
        businessId.filterNotNull()
            .flatMapLatest { repo.outsideFundTotalsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OutsideFundTotals())

    /**
     * Raw cash-basis inputs (§5): every live completed sale with its costed economics,
     * paired with the whole credit ledger. Unwindowed — a repayment today can settle a
     * sale from last year, so [CashBasis] needs the full picture to attribute it.
     */
    private val cashBasisInputs: Flow<Pair<List<CashBasisSaleRow>, List<CreditTxn>>> =
        businessId.filterNotNull().flatMapLatest { bid ->
            combine(repo.cashBasisSalesFlow(bid), repo.creditLedgerFlow(bid)) { sales, ledger ->
                sales to ledger
            }
        }

    /**
     * ALL-TIME cash-basis figures. Its [CashBasis.Period.cogs] is the cost of every good
     * that has been sold AND collected — the "stock money" input to the four-part split.
     */
    private val cashBasisAllTime: StateFlow<CashBasis.Period> =
        cashBasisInputs
            .map { (sales, ledger) -> CashBasis.compute(sales, ledger, 0L, Long.MAX_VALUE) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashBasis.Period())

    /** Everything the split needs off the cash ledger, gathered in one combine. */
    private data class SplitInputs(
        val tillMoves: Double = 0.0,
        val safe: Double = 0.0,
        val equityCash: Double = 0.0,
        val purchaseCash: Double = 0.0,
        val owedToCustomers: Double = 0.0
    )

    private val splitInputs: Flow<SplitInputs> =
        businessId.filterNotNull().flatMapLatest { bid ->
            combine(
                repo.tillBalanceFlow(bid),
                repo.safeBalanceFlow(bid),
                repo.equityCashFlow(bid),
                repo.purchaseCashFlow(bid),
                repo.totalChangeOwedFlow(bid)
            ) { till, safe, equity, purchases, owed -> SplitInputs(till, safe, equity, purchases, owed) }
        }

    /**
     * §6 — "how much of this money is actually mine". The cash physically held (till +
     * safe) split into four pots that ADD UP to it exactly:
     *
     *   float & capital + stock money + owed to customers + profit  =  till + safe
     *
     *  - float & capital = the opening float, plus owner injections and borrowings still
     *    sitting in the cash, less anything drawn back out. Never profit.
     *  - stock money     = the collected cost of goods sold that has NOT yet been spent
     *    restocking. It must buy the next lot.
     *  - owed to customers = change owed + unpaid refunds. NOT his money.
     *  - profit          = the residual. Making it the residual is what guarantees the
     *    four parts reconcile to the cent instead of nearly adding up.
     *
     * SCOPE, honestly: this splits cash that is HERE. It says nothing about unsold stock
     * (no projected profit — the owner explicitly does not want it), and profit earned on
     * an unpaid credit sale is real but is not yet cash, so it shows in
     * [PosRepository.CashSplit.creditOutstanding], not in profit.
     */
    val cashSplit: StateFlow<PosRepository.CashSplit> =
        combine(
            splitInputs,
            _openingFloat,
            cashBasisAllTime,
            customers
        ) { inputs, float, allTime, custs ->
            val till = float + inputs.tillMoves
            // Float + outside money still in the cash. Clamped at zero: if the owner has
            // drawn out more than they ever put in, the shop is not holding their money.
            val floatCapital = (float + inputs.equityCash).coerceAtLeast(0.0)
            // Cost of what has sold and been paid for, less what restocking already spent.
            val stockMoney = (allTime.cogs - inputs.purchaseCash).coerceAtLeast(0.0)
            PosRepository.CashSplit(
                till = till,
                safe = inputs.safe,
                floatCapital = floatCapital,
                stockMoney = stockMoney,
                owedToCustomers = inputs.owedToCustomers.coerceAtLeast(0.0),
                creditOutstanding = custs.sumOf { it.balance.coerceAtLeast(0.0) }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PosRepository.CashSplit())

    /** All (non-deleted) suppliers for the shop, A→Z by name. */
    val suppliers: StateFlow<List<Supplier>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.suppliersFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All (non-deleted) purchase orders for the shop, newest first, with lines. */
    val purchaseOrders: StateFlow<List<PurchaseOrderWithLines>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.purchaseOrdersFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Shop-wide accounts payable owed to suppliers (unpaid PO balances). */
    val supplierPayables: StateFlow<Double> =
        businessId.filterNotNull()
            .flatMapLatest { repo.supplierPayablesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    // ---- Reports (live aggregates over the chosen date window) ----
    private val _reportRange = MutableStateFlow(ReportRange.TODAY)
    val reportRange: StateFlow<ReportRange> = _reportRange.asStateFlow()

    val reportSummary: StateFlow<SalesSummary> =
        combine(businessId.filterNotNull(), _reportRange) { bid, range -> bid to range }
            .flatMapLatest { (bid, range) ->
                val (from, to) = rangeBounds(range)
                repo.salesSummaryFlow(bid, from, to)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SalesSummary())

    val reportBreakdown: StateFlow<List<MethodBreakdown>> =
        combine(businessId.filterNotNull(), _reportRange) { bid, range -> bid to range }
            .flatMapLatest { (bid, range) ->
                val (from, to) = rangeBounds(range)
                repo.paymentBreakdownFlow(bid, from, to)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Money refunded in the report window — subtract from gross for net takings. */
    val reportRefunds: StateFlow<Double> =
        combine(businessId.filterNotNull(), _reportRange) { bid, range -> bid to range }
            .flatMapLatest { (bid, range) ->
                val (from, to) = rangeBounds(range)
                repo.refundedSinceFlow(bid, from, to)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Count of fully-refunded sales in the window — subtract from the sale count so a
     *  refunded sale stops counting as live (prompt §5). */
    val reportFullyRefunded: StateFlow<Int> =
        combine(businessId.filterNotNull(), _reportRange) { bid, range -> bid to range }
            .flatMapLatest { (bid, range) ->
                val (from, to) = rangeBounds(range)
                repo.fullyRefundedCountFlow(bid, from, to)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setReportRange(range: ReportRange) { _reportRange.value = range }

    // ---- Dashboard (its own date window, independent of the Reports screen) ----
    private val _dashRange = MutableStateFlow(ReportRange.TODAY)
    val dashRange: StateFlow<ReportRange> = _dashRange.asStateFlow()
    fun setDashRange(range: ReportRange) { _dashRange.value = range }

    private val dashKey = combine(businessId.filterNotNull(), _dashRange) { bid, range -> bid to range }

    val dashSummary: StateFlow<SalesSummary> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.salesSummaryFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SalesSummary())

    val dashBreakdown: StateFlow<List<MethodBreakdown>> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.paymentBreakdownFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dashTopProducts: StateFlow<List<TopProduct>> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.topProductsFlow(bid, from, to, 5)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dashGrossProfit: StateFlow<Double> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.grossProfitFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Revenue over the same costed lines as [dashGrossProfit] — the margin denominator. */
    val dashCostedRevenue: StateFlow<Double> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.costedRevenueFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Posted (approved, non-template) expenses in the dashboard window — subtracted from
     *  gross profit for the "net profit after expenses" figure (B3). */
    val dashExpenses: StateFlow<Double> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.postedExpensesBetweenFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /**
     * ★ CASH-BASIS revenue / cost / profit for the dashboard window (§5).
     *
     * MEANING CHANGED, deliberately: an unpaid credit sale is NOT revenue. Only money
     * actually collected counts, on the day it arrives, and the cost of goods is
     * pro-rated to the collected share so a part-paid sale never reads as a loss. A later
     * repayment lands as revenue on ITS day. See [CashBasis] for the full contract and
     * the proof that a sale can never contribute more than it collected.
     *
     * [CashBasis.Period.uncollected] is the sale value billed in the window that has not
     * arrived — surfaced as a figure/alert, never as sales.
     */
    val dashCashBasis: StateFlow<CashBasis.Period> =
        combine(cashBasisInputs, _dashRange) { (sales, ledger), range ->
            val (from, to) = rangeBounds(range)
            CashBasis.compute(sales, ledger, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashBasis.Period())

    /**
     * CASH SHORT / OVER in the dashboard window: the signed sum of close-of-day variances.
     * A shortage counts as a LOSS against profit and an overage as a gain, but it is kept
     * OUT of gross profit and reported on its own line, so a shortage reads as a shortage
     * rather than quietly eating margin.
     *
     *   net profit = gross profit − expenses + cash short/over
     */
    val dashCashVariance: StateFlow<Double> =
        dashKey.flatMapLatest { (bid, range) ->
            val (from, to) = rangeBounds(range)
            repo.cashVarianceFlow(bid, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Cash-basis figures for the REPORTS window (same meaning as [dashCashBasis]). */
    val reportCashBasis: StateFlow<CashBasis.Period> =
        combine(cashBasisInputs, _reportRange) { (sales, ledger), range ->
            val (from, to) = rangeBounds(range)
            CashBasis.compute(sales, ledger, from, to)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashBasis.Period())

    /** Cash short/over over the REPORTS window. */
    val reportCashVariance: StateFlow<Double> =
        combine(businessId.filterNotNull(), _reportRange) { bid, range -> bid to range }
            .flatMapLatest { (bid, range) ->
                val (from, to) = rangeBounds(range)
                repo.cashVarianceFlow(bid, from, to)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** saleId → refunded value, for the Receipts "refunded / partial refund" badge.
     *  Presentation only: the sale row and every money aggregate are untouched. */
    val refundedBySale: StateFlow<Map<String, Double>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.refundedBySaleFlow(it) }
            .map { rows -> rows.associate { r -> r.saleId to r.refunded } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Always the last 7 calendar days, independent of the range chips above. */
    val dashDailyBars: StateFlow<List<DayBar>> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                repo.stampsSinceFlow(bid, startOfToday() - 6 * DAY_MS).map { bucketLast7Days(it) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), bucketLast7Days(emptyList()))

    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart: StateFlow<List<CartLine>> = _cart.asStateFlow()

    private val _lastReceipt = MutableStateFlow<LastReceipt?>(null)
    val lastReceipt: StateFlow<LastReceipt?> = _lastReceipt.asStateFlow()

    // ---- Cloud sync (bring-your-own database) ----
    val syncStatus: StateFlow<SyncStatus> = sync.status

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    private val _lastSyncAt = MutableStateFlow<Long?>(null)
    val lastSyncAt: StateFlow<Long?> = _lastSyncAt.asStateFlow()

    // Split direction timestamps + queue depth so the owner can SEE sync working.
    private val _lastUploadAt = MutableStateFlow<Long?>(null)
    val lastUploadAt: StateFlow<Long?> = _lastUploadAt.asStateFlow()

    private val _lastDownloadAt = MutableStateFlow<Long?>(null)
    val lastDownloadAt: StateFlow<Long?> = _lastDownloadAt.asStateFlow()

    private val _pendingUpload = MutableStateFlow(0)
    val pendingUpload: StateFlow<Int> = _pendingUpload.asStateFlow()

    /** Stage-2 master switch: whether local data is pushed UP to the cloud. Default
     *  off so the repoint is pull-only until the owner opts in (after clearing test
     *  data). See [setCloudPushEnabled]. */
    private val _cloudPushEnabled = MutableStateFlow(false)
    val cloudPushEnabled: StateFlow<Boolean> = _cloudPushEnabled.asStateFlow()

    // ---- Appearance theme (themeable accent/background/sidebar; device-local) ----
    private val _themeChoice = MutableStateFlow(DEFAULT_THEME)
    val themeChoice: StateFlow<ThemeChoice> = _themeChoice.asStateFlow()

    val cartTotal: Double get() = _cart.value.sumOf { it.lineTotal }
    val cartCount: Int get() = _cart.value.sumOf { it.qty }.toInt()

    // ---- Device-local shop preferences (receipt/tax/margins/printer) -----
    // NOTE: these MUST be declared BEFORE init{}. viewModelScope is backed by
    // Dispatchers.Main.immediate, so the coroutines launched in init run
    // synchronously during construction; a backing field declared *after* init
    // is still null when the coroutine captures it -> NullPointerException on
    // first launch (the "blinking and close" crash). Keep this above init.
    private val _shopPrefs = MutableStateFlow(DEFAULT_SHOP_PREFS)
    val shopPrefs: StateFlow<ShopPrefs> = _shopPrefs.asStateFlow()

    init {
        viewModelScope.launch {
            businessId.value = repo.ensureSeeded()
            // Sweep any pre-existing catalogue duplicates on startup so the fix is
            // self-healing and the manual "Remove duplicates" button is never needed
            // (prompt §1). New duplicates are prevented at the source in saveItem/pull.
            repo.dedupeItems()
        }
        viewModelScope.launch { _themeChoice.value = loadTheme() }
        viewModelScope.launch { _shopPrefs.value = loadPrefs() }
        viewModelScope.launch { businessId.filterNotNull().collect { _openingFloat.value = repo.openingFloat(it) } }
        viewModelScope.launch {
            _floatTarget.value = repo.floatTarget()
            _varianceNoteThreshold.value = repo.varianceNoteThreshold()
        }
        viewModelScope.launch {
            _appMode.value = if (repo.getSetting(KEY_APP_MODE) == "cloud") AppMode.Cloud else AppMode.Local
            _onboarded.value = repo.getSetting(KEY_ONBOARDED) == "1"
            _hasLocalPin.value = authManager.hasLocalPin()
            _bootLoaded.value = true
        }
        refreshSyncState()
        // Any completed sync pass (manual, debounced, or background) → refresh the split
        // timestamps and queue depth so the sync sheet/settings reflect it live.
        viewModelScope.launch {
            sync.status.collect { s ->
                if (s is SyncStatus.Done) refreshSyncState()
            }
        }
    }

    // ── App mode / onboarding / local PIN actions ─────────────────────────

    /** Record that the first-run choice has been made; the welcome screen won't return. */
    fun completeOnboarding() {
        _onboarded.value = true
        viewModelScope.launch { repo.putSetting(KEY_ONBOARDED, "1") }
    }

    /** Set (or replace) the local device PIN. Hashing runs off the main thread. */
    fun setLocalPin(pin: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            authManager.setLocalPin(pin)
            _hasLocalPin.value = true
            onDone()
        }
    }

    /** Remove the local device PIN (till opens without a lock). */
    fun clearLocalPin() {
        viewModelScope.launch {
            authManager.clearLocalPin()
            _hasLocalPin.value = false
        }
    }

    suspend fun verifyLocalPin(pin: String): Boolean = authManager.verifyLocalPin(pin)

    /** Switch to cloud (team) mode; the AuthGate login flow takes over from here. */
    fun connectCloud() {
        _appMode.value = AppMode.Cloud
        viewModelScope.launch { repo.putSetting(KEY_APP_MODE, "cloud") }
    }

    /** Return to local (phone-only) mode, e.g. backing out of cloud login. */
    fun useLocalMode() {
        _appMode.value = AppMode.Local
        viewModelScope.launch { repo.putSetting(KEY_APP_MODE, "local") }
    }

    /** True once at least one cloud account has been provisioned on this device. */
    fun hasCloudAccount(): Boolean = authManager.hasCloudAccount()

    // ---- Appearance theme ------------------------------------------------

    private suspend fun loadTheme(): ThemeChoice {
        val accent = repo.getSetting(KEY_THEME_ACCENT) ?: return DEFAULT_THEME
        return ThemeChoice(
            accent = accent,
            accentHex = repo.getSetting(KEY_THEME_ACCENT_HEX) ?: DEFAULT_THEME.accentHex,
            background = repo.getSetting(KEY_THEME_BG) ?: DEFAULT_THEME.background,
            sidebar = repo.getSetting(KEY_THEME_SIDEBAR) ?: DEFAULT_THEME.sidebar,
        )
    }

    /** Apply a theme instantly (live) and persist it on-device. */
    fun saveTheme(choice: ThemeChoice) {
        _themeChoice.value = choice
        viewModelScope.launch {
            repo.putSetting(KEY_THEME_ACCENT, choice.accent)
            repo.putSetting(KEY_THEME_ACCENT_HEX, choice.accentHex)
            repo.putSetting(KEY_THEME_BG, choice.background)
            repo.putSetting(KEY_THEME_SIDEBAR, choice.sidebar)
        }
    }

    // (_shopPrefs / shopPrefs are declared above init{} — see note there.)
    private suspend fun loadPrefs(): ShopPrefs {
        val d = DEFAULT_SHOP_PREFS
        return ShopPrefs(
            receiptFontScale = repo.getSetting(KEY_RC_FONT)?.toFloatOrNull() ?: d.receiptFontScale,
            receiptFeedLines = repo.getSetting(KEY_RC_FEED)?.toIntOrNull() ?: d.receiptFeedLines,
            receiptBoldName = repo.getSetting(KEY_RC_BOLD)?.toBooleanStrictOrNull() ?: d.receiptBoldName,
            receiptShowLogo = repo.getSetting(KEY_RC_LOGO)?.toBooleanStrictOrNull() ?: d.receiptShowLogo,
            receiptShowTagline = repo.getSetting(KEY_RC_TAGLINE)?.toBooleanStrictOrNull() ?: d.receiptShowTagline,
            receiptShowAddress = repo.getSetting(KEY_RC_ADDRESS)?.toBooleanStrictOrNull() ?: d.receiptShowAddress,
            receiptShowVat = repo.getSetting(KEY_RC_VAT)?.toBooleanStrictOrNull() ?: d.receiptShowVat,
            receiptShowCashier = repo.getSetting(KEY_RC_CASHIER)?.toBooleanStrictOrNull() ?: d.receiptShowCashier,
            receiptShowPayment = repo.getSetting(KEY_RC_PAYMENT)?.toBooleanStrictOrNull() ?: d.receiptShowPayment,
            receiptShowChange = repo.getSetting(KEY_RC_CHANGE)?.toBooleanStrictOrNull() ?: d.receiptShowChange,
            receiptBoldTotals = repo.getSetting(KEY_RC_BOLD_TOTALS)?.toBooleanStrictOrNull() ?: d.receiptBoldTotals,
            receiptShowFooter = repo.getSetting(KEY_RC_FOOTER)?.toBooleanStrictOrNull() ?: d.receiptShowFooter,
            wholesaleRounding = repo.getSetting(KEY_ROUND_WS)?.toDoubleOrNull() ?: d.wholesaleRounding,
            checkoutRounding = repo.getSetting(KEY_ROUND_CO)?.toDoubleOrNull() ?: d.checkoutRounding,
            defaultQuoteValidityDays = repo.getSetting(KEY_QUOTE_DAYS)?.toIntOrNull() ?: d.defaultQuoteValidityDays,
            discountThresholdPct = repo.getSetting(KEY_DISCOUNT_THRESHOLD)?.toDoubleOrNull() ?: d.discountThresholdPct,
            maxItemDiscount = repo.getSetting(KEY_MAX_ITEM_DISCOUNT)?.toDoubleOrNull() ?: d.maxItemDiscount,
            saleEditWindowMinutes = repo.getSetting(KEY_SALE_EDIT_WINDOW)?.toIntOrNull() ?: d.saleEditWindowMinutes,
            marginFormula = repo.getSetting(KEY_MARGIN_FORMULA) ?: d.marginFormula,
            autoConvertUnitsToBoxes = repo.getSetting(KEY_AUTO_BOXES)?.toBooleanStrictOrNull() ?: d.autoConvertUnitsToBoxes,
            printerType = repo.getSetting(KEY_PRINTER_TYPE) ?: d.printerType,
            receiptPreset = repo.getSetting(KEY_RC_PRESET) ?: d.receiptPreset,
            secondCurrencyCode = repo.getSetting(KEY_CUR2_CODE) ?: d.secondCurrencyCode,
            secondCurrencyRate = repo.getSetting(KEY_CUR2_RATE)?.toDoubleOrNull() ?: d.secondCurrencyRate,
            adminLargeSale = repo.getSetting(PosRepository.KEY_ADMIN_LARGE_SALE)?.toDoubleOrNull() ?: d.adminLargeSale,
            escalateHours = repo.getSetting(PosRepository.KEY_ESC_HOURS)?.toIntOrNull() ?: d.escalateHours,
            unsyncedHours = repo.getSetting(PosRepository.KEY_UNSYNCED_HOURS)?.toIntOrNull() ?: d.unsyncedHours,
        )
    }

    /** Apply shop prefs instantly and persist them on-device. */
    fun savePrefs(prefs: ShopPrefs) {
        _shopPrefs.value = prefs
        viewModelScope.launch {
            repo.putSetting(KEY_RC_FONT, prefs.receiptFontScale.toString())
            repo.putSetting(KEY_RC_FEED, prefs.receiptFeedLines.toString())
            repo.putSetting(KEY_RC_BOLD, prefs.receiptBoldName.toString())
            repo.putSetting(KEY_RC_LOGO, prefs.receiptShowLogo.toString())
            repo.putSetting(KEY_RC_TAGLINE, prefs.receiptShowTagline.toString())
            repo.putSetting(KEY_RC_ADDRESS, prefs.receiptShowAddress.toString())
            repo.putSetting(KEY_RC_VAT, prefs.receiptShowVat.toString())
            repo.putSetting(KEY_RC_CASHIER, prefs.receiptShowCashier.toString())
            repo.putSetting(KEY_RC_PAYMENT, prefs.receiptShowPayment.toString())
            repo.putSetting(KEY_RC_CHANGE, prefs.receiptShowChange.toString())
            repo.putSetting(KEY_RC_BOLD_TOTALS, prefs.receiptBoldTotals.toString())
            repo.putSetting(KEY_RC_FOOTER, prefs.receiptShowFooter.toString())
            repo.putSetting(KEY_ROUND_WS, prefs.wholesaleRounding.toString())
            repo.putSetting(KEY_ROUND_CO, prefs.checkoutRounding.toString())
            repo.putSetting(KEY_QUOTE_DAYS, prefs.defaultQuoteValidityDays.toString())
            repo.putSetting(KEY_DISCOUNT_THRESHOLD, prefs.discountThresholdPct.toString())
            repo.putSetting(KEY_MAX_ITEM_DISCOUNT, prefs.maxItemDiscount.toString())
            repo.putSetting(KEY_SALE_EDIT_WINDOW, prefs.saleEditWindowMinutes.toString())
            repo.putSetting(KEY_MARGIN_FORMULA, prefs.marginFormula)
            repo.putSetting(KEY_AUTO_BOXES, prefs.autoConvertUnitsToBoxes.toString())
            repo.putSetting(KEY_PRINTER_TYPE, prefs.printerType)
            repo.putSetting(KEY_RC_PRESET, prefs.receiptPreset)
            repo.putSetting(KEY_CUR2_CODE, prefs.secondCurrencyCode)
            repo.putSetting(KEY_CUR2_RATE, prefs.secondCurrencyRate.toString())
            repo.putSetting(PosRepository.KEY_ADMIN_LARGE_SALE, prefs.adminLargeSale.toString())
            repo.putSetting(PosRepository.KEY_ESC_HOURS, prefs.escalateHours.toString())
            repo.putSetting(PosRepository.KEY_UNSYNCED_HOURS, prefs.unsyncedHours.toString())
        }
    }

    // ---- Cart operations (live, in-memory) -------------------------------

    /**
     * Add one of [item] to the cart at the chosen price [mode]:
     *  - "box"       → one whole box (unitPrice = boxPrice, draws boxSize units)
     *  - "wholesale" → trade price each (falls back to retail if none set)
     *  - "retail"    → walk-in price each (default)
     * The same item at different modes stays on separate cart lines.
     */
    fun addToCart(item: Item, mode: String = "retail") {
        val (rawPrice, unitsPerLine) = when (mode) {
            "box" -> item.boxPrice to item.boxSize
            "wholesale" -> (if (item.wholesalePrice > 0) item.wholesalePrice else item.price) to 1
            else -> item.price to 1
        }
        // Wholesale prices can be rounded to a configurable step (Settings → Tax).
        val unitPrice = if (mode == "wholesale")
            applyRounding(rawPrice, _shopPrefs.value.wholesaleRounding)
        else rawPrice
        val key = "${item.id}#$mode"
        _cart.value = _cart.value.toMutableList().also { list ->
            val idx = list.indexOfFirst { it.lineKey == key }
            if (idx >= 0) {
                val existing = list[idx]
                list[idx] = existing.copy(qty = existing.qty + 1)
            } else {
                list.add(
                    CartLine(
                        itemId = item.id,
                        name = item.name,
                        unitPrice = unitPrice,
                        taxRate = item.taxRate,
                        qty = 1.0,
                        mode = mode,
                        unitsPerLine = unitsPerLine
                    )
                )
            }
        }
    }

    /**
     * Add a MEASURED (unit-priced) [item] to the cart at a decimal [qty] of its unit
     * (e.g. 2.35 kg). Line price = qty × pricePerUnit. Re-tapping the same item stacks
     * onto the existing measured line. A qty of 0 or less is ignored.
     */
    fun addMeasuredToCart(item: Item, qty: Double) {
        if (qty <= 0.0) return
        val key = "${item.id}#measured"
        _cart.value = _cart.value.toMutableList().also { list ->
            val idx = list.indexOfFirst { it.lineKey == key }
            if (idx >= 0) {
                val existing = list[idx]
                list[idx] = existing.copy(qty = existing.qty + qty)
            } else {
                list.add(
                    CartLine(
                        itemId = item.id,
                        name = item.name,
                        unitPrice = item.pricePerUnit,
                        taxRate = item.taxRate,
                        qty = qty,
                        mode = "measured",
                        unitsPerLine = 1,
                        measured = true,
                        unitLabel = item.unit.trim().ifBlank { "unit" }
                    )
                )
            }
        }
    }

    /**
     * Look up a scanned/typed [barcode] and add the matching item to the cart.
     * [onResult] receives the item name on a hit, or null when nothing matched so
     * the UI can show "No item for that barcode".
     */
    fun scanToCart(barcode: String, onResult: (String?) -> Unit = {}) {
        val code = barcode.trim()
        val bid = businessId.value
        if (code.isEmpty() || bid == null) { onResult(null); return }
        viewModelScope.launch {
            val item = repo.itemByBarcode(bid, code)
            if (item != null) addToCart(item)
            onResult(item?.name)
        }
    }

    fun changeQty(lineKey: String, delta: Double) {
        _cart.value = _cart.value.mapNotNull { line ->
            if (line.lineKey != lineKey) line
            else {
                val q = line.qty + delta
                if (q <= 0) null else line.copy(qty = q)
            }
        }
    }

    /** Set an explicit quantity on a line (fast qty entry from the cart). */
    fun setQty(lineKey: String, qty: Double) {
        if (qty <= 0) { removeLine(lineKey); return }
        _cart.value = _cart.value.map { line ->
            if (line.lineKey == lineKey) line.copy(qty = qty) else line
        }
    }

    /**
     * Set a fixed-amount discount on a cart line. The [amount] is clamped to the
     * line's own goods value AND to the admin's [ShopPrefs.maxItemDiscount] ceiling
     * (0 = no ceiling), so a cashier can never discount past either limit.
     */
    fun setLineDiscount(lineKey: String, amount: Double) {
        // A cashier without give_discounts can't set a per-line discount either.
        if (!can(Capability.GIVE_DISCOUNTS)) return
        val cap = _shopPrefs.value.maxItemDiscount
        _cart.value = _cart.value.map { line ->
            if (line.lineKey != lineKey) line
            else {
                var d = amount.coerceIn(0.0, line.lineGross)
                if (cap > 0.0) d = d.coerceAtMost(cap)
                line.copy(lineDiscount = d)
            }
        }
    }

    /** The admin-set ceiling on a single line's discount (0 = unlimited). */
    val maxItemDiscount: Double get() = _shopPrefs.value.maxItemDiscount

    /**
     * Set a fixed-amount markup on a cart line — the mirror of [setLineDiscount] that
     * ADDS to the line instead of subtracting. Markup has no cap (a shop can price up
     * as far as it likes), so the [amount] is only floored at 0.
     */
    fun setLineMarkup(lineKey: String, amount: Double) {
        _cart.value = _cart.value.map { line ->
            if (line.lineKey != lineKey) line
            else line.copy(lineMarkup = amount.coerceAtLeast(0.0))
        }
    }

    fun removeLine(lineKey: String) {
        _cart.value = _cart.value.filterNot { it.lineKey == lineKey }
    }

    fun clearCart() {
        _cart.value = emptyList()
    }

    // ---- Checkout --------------------------------------------------------

    /**
     * Complete the cart against one or more [payments] (a split sale has several).
     * [discount] is in currency units. [onCredit] puts the unpaid shortfall on the
     * [customer]'s account; [changeGiven] is how much of any overpayment change the
     * cashier handed over now — the remainder is recorded as change still owed.
     *
     * VAT is read from the active business so the cashier never has to think
     * about it — it is applied automatically when the business has it enabled.
     */
    /**
     * Re-entrancy guard for [checkout]. A double-tap on the pay-confirm button can fire
     * checkout() twice before the dialog recomposes away — each pass would mint a fresh
     * saleId + receiptNo and record a DUPLICATE completed sale. The flag is checked and
     * set synchronously (both Compose callbacks and viewModelScope.launch run on the main
     * thread) so the second call returns before it can start, and is cleared in a finally.
     */
    private var checkoutInFlight = false

    fun checkout(
        payments: List<Tender>,
        discount: Double = 0.0,
        customer: Customer? = null,
        onCredit: Boolean = false,
        changeGiven: Double = 0.0,
        tillDiscrepancy: Boolean = false
    ) {
        val bid = businessId.value ?: return
        val lines = _cart.value
        if (lines.isEmpty()) return
        if (checkoutInFlight) return
        checkoutInFlight = true
        // Handler-side discount gate: a cashier without give_discounts can never apply a
        // whole-sale discount, even if a stale screen sent one through.
        val effDiscount = if (can(Capability.GIVE_DISCOUNTS)) discount else 0.0
        val biz = business.value
        viewModelScope.launch {
            try {
                val saved = repo.checkout(
                    bid, lines, payments, effDiscount,
                    customer = customer,
                    onCredit = onCredit,
                    changeGiven = changeGiven,
                    tillDiscrepancy = tillDiscrepancy,
                    vatEnabled = biz?.vatEnabled ?: false,
                    vatPercent = biz?.vatPercent ?: 0.0,
                    totalRounding = _shopPrefs.value.checkoutRounding,
                    cashierId = currentCashierId,
                    cashierName = currentCashierName
                )
                _lastReceipt.value = LastReceipt(saved.sale, saved.lines)
                _cart.value = emptyList()
                // A credit sale can push a customer over their limit — reconcile the admin
                // feed now so the over-limit alert appears without waiting for the worker.
                if (onCredit && customer != null) sweepNotifications()
                // Money moved: get it to the cloud within seconds, not the 15-min cycle.
                nudgeSync("checkout")
            } finally {
                checkoutInFlight = false
            }
        }
    }

    fun dismissReceipt() {
        _lastReceipt.value = null
    }

    /**
     * Generate a QUOTE from the current cart (§1.2 parity): no payment is taken and
     * nothing is drawn down — it's a priced document. The result rides the SAME
     * [lastReceipt] path as a sale, so the receipt sheet prints/shares it (quote-aware
     * rendering keys off status='quote'). Clears the cart afterwards.
     */
    fun generateQuote(discount: Double = 0.0, customer: Customer? = null, note: String? = null) {
        val bid = businessId.value ?: return
        val lines = _cart.value
        if (lines.isEmpty()) return
        val effDiscount = if (can(Capability.GIVE_DISCOUNTS)) discount else 0.0
        val biz = business.value
        viewModelScope.launch {
            val saved = repo.saveQuote(
                bid, lines, effDiscount,
                note = note,
                customer = customer,
                vatEnabled = biz?.vatEnabled ?: false,
                vatPercent = biz?.vatPercent ?: 0.0,
                validityDays = _shopPrefs.value.defaultQuoteValidityDays,
                cashierId = currentCashierId,
                cashierName = currentCashierName
            )
            _lastReceipt.value = LastReceipt(saved.sale, saved.lines)
            _cart.value = emptyList()
        }
    }

    /** Saved quotes (newest first) for the Quotes list. */
    val quotes: StateFlow<List<SaleEntity>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.quotesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Load an accepted quote's items into the cart (the quote record is kept) so the
     *  cashier can ring it up as a normal sale. Replaces the current cart. */
    fun loadQuoteToCart(quoteId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _cart.value = repo.saleLinesToCart(quoteId)
            onDone()
        }
    }

    /** Create a customer on the fly from a typed name, then hand it back. */
    fun createCustomer(name: String, phone: String? = null, onCreated: (Customer) -> Unit) {
        val bid = businessId.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            onCreated(repo.createCustomer(bid, name, phone))
            nudgeSync("createCustomer")
        }
    }

    // ---- Parked / held sales ---------------------------------------------

    val parkedSales: StateFlow<List<SaleEntity>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.parkedSalesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val parkedCount: StateFlow<Int> =
        businessId.filterNotNull()
            .flatMapLatest { repo.parkedCountFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Hold the current cart for later. No-op on an empty cart. */
    fun parkSale(note: String? = null, customer: Customer? = null) {
        val bid = businessId.value ?: return
        val lines = _cart.value
        if (lines.isEmpty()) return
        viewModelScope.launch {
            repo.parkSale(
                bid, lines, note = note, customer = customer,
                cashierId = currentCashierId, cashierName = currentCashierName
            )
            _cart.value = emptyList()
        }
    }

    /** Resume a parked sale into the cart (replacing whatever is there). */
    fun resumeParked(saleId: String) {
        viewModelScope.launch { _cart.value = repo.resumeParked(saleId) }
    }

    /** Lines for a past sale — used when reprinting from the Receipts list. */
    suspend fun loadLines(saleId: String): List<SaleLine> = repo.linesForSale(saleId)

    // ---- View + edit a receipt (B5) --------------------------------------

    /** Tenders recorded against a past sale — the payment block of the detail view. */
    suspend fun loadPayments(saleId: String): List<SalePayment> = repo.paymentsForSale(saleId)

    /** Append-only edit history for one receipt, newest first. */
    fun auditForSale(saleId: String): Flow<List<AuditEntry>> = repo.auditForSaleFlow(saleId)

    /** How long after a sale the owner may still correct it (0 = editing off). */
    val saleEditWindowMinutes: Int get() = _shopPrefs.value.saleEditWindowMinutes

    /** True while [sale] is still correctable in place under the current shop setting. */
    fun canEditSale(sale: SaleEntity): Boolean = sale.isEditable(saleEditWindowMinutes)

    /**
     * Rewrite [sale] in place from [lines] (the full final basket). Same receipt, same
     * id — the repository re-runs checkout's math, moves only the stock delta, books any
     * payment difference on the existing change/credit ledger and appends the audit
     * trail. [onDone] reports whether the edit was accepted (false => the window closed,
     * or the receipt has a refund against it).
     */
    fun editSale(sale: SaleEntity, lines: List<CartLine>, onDone: (Boolean) -> Unit = {}) {
        if (!can(Capability.EDIT_RECEIPTS)) { onDone(false); return }
        viewModelScope.launch {
            val biz = business.value
            val result = repo.editSale(
                saleId = sale.id,
                cart = lines,
                windowMinutes = saleEditWindowMinutes,
                vatEnabled = biz?.vatEnabled ?: false,
                vatPercent = biz?.vatPercent ?: 0.0,
                totalRounding = _shopPrefs.value.checkoutRounding,
                cashierId = currentCashierId,
                cashierName = currentCashierName
            )
            if (result != null) nudgeSync("saleEdit")
            onDone(result != null)
        }
    }

    // ---- Settings --------------------------------------------------------

    fun saveBusiness(updated: Business) {
        viewModelScope.launch { repo.saveBusiness(updated) }
    }

    fun addItem(
        name: String,
        price: Double,                 // retail (per unit)
        wholesalePrice: Double = 0.0,
        boxPrice: Double = 0.0,
        boxSize: Int = 1,
        productType: String = "box",
        category: String? = null,
        sku: String? = null,
        barcode: String? = null,
        taxRate: Double = 0.0,
        trackStock: Boolean = false,
        stockQty: Double = 0.0,
        reorderLevel: Double = 0.0,
        cost: Double? = null,
        unit: String = "pc",
        pricePerUnit: Double = 0.0,
        stockMeasured: Double = 0.0,
        imageLocalPath: String? = null,
        showImage: Boolean = true
    ) {
        val bid = businessId.value ?: return
        if (name.isBlank()) return
        if (!can(Capability.MANAGE_INVENTORY)) return
        viewModelScope.launch {
            repo.saveItem(
                Item(
                    businessId = bid,
                    name = name.trim(),
                    sku = sku?.trim()?.ifBlank { null },
                    barcode = barcode?.trim()?.ifBlank { null },
                    category = category?.trim()?.ifBlank { null },
                    price = price,
                    wholesalePrice = wholesalePrice,
                    boxPrice = boxPrice,
                    boxSize = boxSize.coerceAtLeast(1),
                    productType = productType,
                    taxRate = taxRate,
                    trackStock = trackStock,
                    stockQty = if (trackStock) stockQty else 0.0,
                    reorderLevel = if (trackStock) reorderLevel else 0.0,
                    cost = cost,
                    unit = unit.trim().ifBlank { "pc" },
                    pricePerUnit = pricePerUnit,
                    stockMeasured = if (trackStock) stockMeasured else 0.0,
                    // A freshly-picked local image starts pending upload to Storage.
                    imageLocalPath = imageLocalPath,
                    imagePending = imageLocalPath != null,
                    showImage = showImage
                )
            )
            nudgeSync("addItem")
        }
    }

    /** Persist edits to an existing item (rename, reprice, restock, toggle tracking). */
    fun updateItem(item: Item) {
        if (!can(Capability.MANAGE_INVENTORY)) return
        viewModelScope.launch {
            repo.saveItem(item)
            nudgeSync("updateItem")
        }
    }

    // ---- Inventory (Stage B): low-stock, movements, adjustments ----------

    /** Items at or below their reorder level — powers the low-stock alert card. */
    val lowStock: StateFlow<List<Item>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.lowStockFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whole-shop stock-movement ledger (newest first). */
    val stockMovements: StateFlow<List<StockMovement>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.stockMovementsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun itemMovements(itemId: String): Flow<List<StockMovement>> =
        repo.itemMovementsFlow(itemId)

    /** Set an item's on-hand to [newQty], logging the change ("adjust"/"restock"). */
    fun adjustStock(itemId: String, newQty: Double, type: String = "adjust", note: String? = null) {
        if (!can(Capability.MANAGE_INVENTORY)) return
        viewModelScope.launch {
            repo.adjustStock(itemId, newQty, type, note, currentCashierId, currentCashierName)
        }
    }

    // ---- Danger zone ------------------------------------------------------

    fun resetAllStock() {
        val bid = businessId.value ?: return
        viewModelScope.launch { repo.resetAllStock(bid) }
    }

    fun wipeSalesData() {
        val bid = businessId.value ?: return
        viewModelScope.launch { repo.wipeSalesData(bid) }
    }

    // ---- Customers & credit ----------------------------------------------

    fun addCustomer(
        name: String,
        phone: String? = null,
        email: String? = null,
        address: String? = null,
        note: String? = null,
        wholesale: Boolean = false,
        creditLimit: Double? = null,
        onCreditLimitQueued: () -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        if (name.isBlank()) return
        // A cashier can create a customer, but a credit limit they set needs admin approval:
        // the customer is created with NO limit and a request is raised for it (§Job 1).
        val gateLimit = creditLimit != null && !currentIsAdmin
        val customer = Customer(
            businessId = bid,
            name = name.trim(),
            phone = phone?.trim()?.ifBlank { null },
            email = email?.trim()?.ifBlank { null },
            address = address?.trim()?.ifBlank { null },
            note = note?.trim()?.ifBlank { null },
            wholesale = wholesale,
            creditLimit = if (gateLimit) null else creditLimit
        )
        viewModelScope.launch {
            repo.saveCustomer(customer)
            nudgeSync("addCustomer")
            if (gateLimit) {
                submitStaffRequest(
                    type = "credit_limit", amount = creditLimit,
                    targetType = "customer", targetId = customer.id, targetName = customer.name
                )
                onCreditLimitQueued()
            }
        }
    }

    /**
     * Save edits to an existing customer's details (keeps the same id; re-flags for sync).
     *
     * Credit-limit rule (§Job 1): an ADMIN sets the limit directly. A NON-ADMIN cannot —
     * ANY change to the credit-limit figure (raise OR lower) is NOT applied locally; instead
     * it files a `credit_limit` approval request for the admin and the customer's real limit
     * is left untouched until approved. Every OTHER field still saves immediately.
     * [onCreditLimitQueued] fires when a request was raised so the UI can say so.
     */
    fun updateCustomer(
        customer: Customer,
        name: String,
        phone: String?,
        email: String?,
        address: String?,
        note: String?,
        wholesale: Boolean,
        creditLimit: Double?,
        onCreditLimitQueued: () -> Unit = {}
    ) {
        if (name.isBlank()) return
        // A cashier changing the figure ⇒ route it through approval; keep the old limit for now.
        val limitChanged = creditLimit != customer.creditLimit
        val gateLimit = limitChanged && !currentIsAdmin
        val effLimit = if (gateLimit) customer.creditLimit else creditLimit
        viewModelScope.launch {
            repo.saveCustomer(
                customer.copy(
                    name = name.trim(),
                    phone = phone?.trim()?.ifBlank { null },
                    email = email?.trim()?.ifBlank { null },
                    address = address?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    wholesale = wholesale,
                    creditLimit = effLimit,
                    updatedAt = System.currentTimeMillis(),
                    pendingSync = true
                )
            )
            nudgeSync("updateCustomer")
            if (gateLimit) {
                submitStaffRequest(
                    type = "credit_limit", amount = creditLimit,
                    targetType = "customer", targetId = customer.id, targetName = customer.name
                )
                onCreditLimitQueued()
            }
        }
    }

    /** Flip the local-only wholesale flag on an existing customer. */
    fun setCustomerWholesale(customer: Customer, wholesale: Boolean) {
        if (customer.wholesale == wholesale) return
        viewModelScope.launch {
            repo.saveCustomer(customer.copy(wholesale = wholesale))
            nudgeSync("customerWholesale")
        }
    }

    /** Pay down a customer's outstanding balance. */
    fun recordRepayment(customerId: String, amount: Double, note: String? = null) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        viewModelScope.launch {
            repo.recordRepayment(bid, customerId, amount, note, currentCashierId, currentCashierName)
            nudgeSync("repayment")
        }
    }

    fun balanceFlow(customerId: String): Flow<Double> = repo.balanceFlow(customerId)

    /** Change the shop still owes this customer (change_owed − change_paid). */
    fun changeBalanceFlow(customerId: String): Flow<Double> = repo.changeBalanceFlow(customerId)

    /** Shop-wide money owed back to customers (change + unpaid refunds) — summary card. */
    fun totalChangeOwedFlow(): Flow<Double> =
        businessId.filterNotNull().flatMapLatest { repo.totalChangeOwedFlow(it) }

    /** Hand over change the shop previously owed a customer. */
    fun recordChangePayment(customerId: String, amount: Double, note: String? = null) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        viewModelScope.launch {
            repo.recordChangePayment(bid, customerId, amount, note, currentCashierId, currentCashierName)
            nudgeSync("changePayment")
        }
    }

    fun creditHistory(customerId: String): Flow<List<CreditTxn>> =
        repo.creditHistoryFlow(customerId)

    /** Completed sales for one customer, newest first — Purchases tab of the
     *  customer detail dialog. */
    fun salesForCustomer(customerId: String): Flow<List<SaleEntity>> =
        repo.salesForCustomerFlow(customerId)

    // ---- Refunds & returns (prompt §11) ----------------------------------

    /** Whole-shop refund history (newest first), each with its returned lines. */
    val refunds: StateFlow<List<RefundWithLines>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.refundsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Units of a sale line already returned across prior refunds (refundable cap). */
    suspend fun qtyReturnedForLine(saleLineId: String): Double =
        repo.qtyReturnedForLine(saleLineId)

    /** Payout rows for a refund — used to print a refund receipt. */
    suspend fun refundPayments(refundId: String): List<RefundPayment> =
        repo.refundPaymentsFor(refundId)

    /**
     * Issue a refund against [sale]. [returns] are the chosen lines/quantities; [payout]
     * is the money handed back now (null or a short amount ⇒ the remainder is owed and
     * ages in Change & Credit). The owed-tracking customer is resolved from the sale.
     */
    fun createRefund(
        sale: SaleEntity,
        returns: List<RefundLineInput>,
        payout: Tender?,
        reason: String?,
        onDone: () -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        if (returns.isEmpty()) return
        if (!can(Capability.PROCESS_REFUNDS)) return
        viewModelScope.launch {
            val customer = sale.customerId?.let { repo.customerById(it) }
            repo.createRefund(
                businessId = bid,
                sale = sale,
                lines = returns,
                payouts = payout?.let { listOf(it) } ?: emptyList(),
                reason = reason,
                customer = customer,
                cashierId = currentCashierId,
                cashierName = currentCashierName
            )
            nudgeSync("refund")
            onDone()
        }
    }

    /** Pay off part/all of a refund the shop still owes (writes a payout + refund_paid). */
    fun recordRefundPayout(refundId: String, tender: Tender, onDone: () -> Unit = {}) {
        if (tender.amount <= 0) return
        viewModelScope.launch {
            repo.recordRefundPayout(refundId, tender, currentCashierId, currentCashierName)
            nudgeSync("refundPayout")
            onDone()
        }
    }

    // ---- Mobile-money SMS reconciliation (prompt §6) ---------------------

    /** Matched-to-a-customer, awaiting the cashier saying what it was for. */
    val mmNeedsVerification: StateFlow<List<MobileMoneyReceipt>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.mobileMoneyFlow(it, "needs_verification") }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Parsed but no customer matched — awaits manual assignment (§6.5). */
    val mmUnmatched: StateFlow<List<MobileMoneyReceipt>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.mobileMoneyFlow(it, "unmatched") }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Already applied/acknowledged (history). */
    val mmVerified: StateFlow<List<MobileMoneyReceipt>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.mobileMoneyFlow(it, "verified") }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Count awaiting the cashier (needs_verification + unmatched) — the "More" badge. */
    val mmPendingCount: StateFlow<Int> =
        businessId.filterNotNull()
            .flatMapLatest { repo.mobileMoneyPendingCountFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    suspend fun mobileMoneyById(id: String): MobileMoneyReceipt? = repo.mobileMoneyById(id)

    /** Assign an unmatched payment to a customer (then it awaits verification). */
    fun assignMobileMoneyCustomer(receiptId: String, customer: Customer) {
        viewModelScope.launch {
            repo.assignMobileMoneyCustomer(receiptId, customer)
            nudgeSync("mmAssign")
        }
    }

    /**
     * Verify a payment: `debt` applies it to the customer's account (credit_paid),
     * `sale` acknowledges it against a walk-in sale already rung up. Attribution is
     * stamped from the signed-in cashier.
     */
    fun verifyMobileMoney(
        receiptId: String,
        customer: Customer?,
        purpose: String,
        note: String? = null,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repo.verifyMobileMoney(receiptId, customer, purpose, note, currentCashierId, currentCashierName)
            nudgeSync("mmVerify")
            onDone()
        }
    }

    /** New receipts found by the last inbox backfill (0 = none / not run). UI shows a
     *  note then clears it. See [backfillSmsInbox]. */
    private val _smsBackfill = MutableStateFlow(0)
    val smsBackfill: StateFlow<Int> = _smsBackfill.asStateFlow()
    fun clearSmsBackfillNote() { _smsBackfill.value = 0 }

    /**
     * Re-scan the SMS inbox for payments that arrived while the app was closed (§6).
     * Idempotent — already-stored txns are skipped — so it is safe to call on every
     * screen open and from a manual "Scan inbox" action. [appContext] must be an
     * application context (the VM must not hold an Activity).
     */
    fun backfillSmsInbox(appContext: android.content.Context) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                com.portionspot.pos.sms.SmsInboxScanner.backfill(
                    appContext, repo, currentCashierId, currentCashierName
                )
            }
            if (n > 0) _smsBackfill.value = n
        }
    }

    /** Dismiss a receipt (not a real payment / handled elsewhere). Never deletes it. */
    fun ignoreMobileMoney(receiptId: String) {
        viewModelScope.launch { repo.ignoreMobileMoney(receiptId) }
    }

    /**
     * Undo a verification done in error (§6). Reverses any debt payment it applied
     * (soft-deletes the `credit_paid` ledger row) and returns the receipt to the
     * queue — back to "To verify" if it still has a matched customer, else "Unmatched".
     */
    fun unverifyMobileMoney(receiptId: String) {
        viewModelScope.launch {
            repo.unverifyMobileMoney(receiptId, currentCashierId, currentCashierName)
            nudgeSync("mmUnverify")
        }
    }

    // ---- Admin: notifications / audit / end-of-day / aging (Phase 7, §8) --

    /** Persisted admin notification feed (newest event first). */
    val notifications: StateFlow<List<AppNotification>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.notificationsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Unread notification count → the admin Alerts tab badge. */
    val unreadNotifications: StateFlow<Int> =
        businessId.filterNotNull()
            .flatMapLatest { repo.unreadNotificationCountFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun markNotificationRead(id: String) {
        viewModelScope.launch { repo.markNotificationRead(id) }
    }

    fun markAllNotificationsRead() {
        val bid = businessId.value ?: return
        viewModelScope.launch { repo.markAllNotificationsRead(bid) }
    }

    /**
     * Reconcile the feed against current state immediately (e.g. when the admin opens
     * Alerts) so it reflects the latest without waiting for the periodic worker. This
     * only updates the table; the background worker owns firing system notifications.
     */
    fun sweepNotifications() {
        viewModelScope.launch {
            val thresholds = repo.loadNotifThresholds()
            val pending = repo.pendingSyncCount()
            val lastSync = sync.lastSyncAt()
            repo.runNotificationSweep(thresholds, pending, lastSync)
        }
    }

    // ---- Admin⇄cashier approval channel (Phase 3, staff_requests) --------

    /** Admin side: pending requests awaiting a decision (oldest first). Drives the
     *  "Requests" section above the Alerts feed. */
    val pendingRequests: StateFlow<List<StaffRequest>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.pendingStaffRequestsFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Admin side: pending-request count (badges onto the Alerts tab alongside unread). */
    val pendingRequestCount: StateFlow<Int> =
        businessId.filterNotNull()
            .flatMapLatest { repo.pendingStaffRequestCountFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Cashier side: this cashier's own recent requests, newest first — the checkout
     *  status surface ("Waiting…", "Approved — apply", "Denied"). Re-subscribes when the
     *  active cashier changes on a shared device. */
    val myRequests: StateFlow<List<StaffRequest>> =
        combine(businessId.filterNotNull(), _cashierId) { bid, cid -> bid to cid }
            .flatMapLatest { (bid, cid) ->
                if (cid.isNullOrBlank()) flowOf(emptyList())
                else repo.myStaffRequestsFlow(bid, cid)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Cashier RAISES an approval request as an alternative to the admin-PIN gate. Fires a
     * pull immediately so the round-trip (admin decides → decision comes back) starts
     * without waiting for the poll cadence. [onCreated] returns the new request id so the
     * caller can track the exact row it just made.
     */
    fun submitStaffRequest(
        type: String,
        amount: Double?,
        targetType: String? = null,
        targetId: String? = null,
        targetName: String? = null,
        note: String? = null,
        onCreated: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val req = repo.submitStaffRequest(
                type = type, targetType = targetType, targetId = targetId,
                targetName = targetName, amount = amount, note = note,
                byId = currentCashierId, byName = currentCashierName
            )
            onCreated(req.id)
            // Reach the admin now, then poll fast until the decision rides back.
            sync.requestPullNow("request-submitted")
            sync.goHot()
        }
    }

    /**
     * Admin APPROVES/DENIES a pending request, then pulls so the cashier phone sees it.
     * [approvedAmount] (approve only) lets the admin confirm a DIFFERENT figure than was
     * requested — e.g. grant a smaller credit limit than the cashier asked for. On approval
     * of a `credit_limit` request the repository executes the change on this device.
     */
    fun decideStaffRequest(id: String, approve: Boolean, approvedAmount: Double? = null) {
        viewModelScope.launch {
            repo.decideStaffRequest(id, approve, currentCashierId, currentCashierName, approvedAmount)
            sync.requestPullNow("request-decided")
            sync.goHot()
        }
    }

    /** Cashier marks an approved request consumed (discount applied). Device-local. */
    fun markRequestApplied(id: String) {
        viewModelScope.launch { repo.markRequestApplied(id) }
    }

    /** Append-only audit trail (newest first) for the admin audit-log viewer. */
    val auditLog: StateFlow<List<AuditEntry>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.auditFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Completed sales that carried a discount, newest first — the admin's "Discounts given"
     *  review surface (§Job 2). Discounts are recorded here, not approved, so the admin can
     *  see who discounted what without being interrupted at the till. */
    val discountsGiven: StateFlow<List<SaleEntity>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.discountedSalesFlow(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // End-of-day / shift summary — the selected day (start-of-day millis).
    private val _eodDay = MutableStateFlow(startOfToday())
    val eodDay: StateFlow<Long> = _eodDay.asStateFlow()
    fun setEodDay(startOfDayMs: Long) { _eodDay.value = startOfDayMs }
    private val eodKey = combine(businessId.filterNotNull(), _eodDay) { b, d -> b to d }

    val eodCashiers: StateFlow<List<CashierDay>> =
        eodKey.flatMapLatest { (b, d) -> repo.cashierDayFlow(b, d, d + DAY_MS) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val eodMethods: StateFlow<List<MethodBreakdown>> =
        eodKey.flatMapLatest { (b, d) -> repo.paymentBreakdownFlow(b, d, d + DAY_MS) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val eodChangeGiven: StateFlow<Double> =
        eodKey.flatMapLatest { (b, d) -> repo.changeGivenFlow(b, d, d + DAY_MS) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Per-customer debt split into 30/60/90 aging buckets (recomputed live). */
    val debtAging: StateFlow<List<DebtAgingRow>> =
        businessId.filterNotNull()
            .flatMapLatest { bid ->
                combine(repo.creditLedgerFlow(bid), repo.customersFlow(bid)) { txns, custs ->
                    DebtAging.compute(txns, custs.associate { it.id to it.name }, System.currentTimeMillis())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Admin void of a wrongful refund (§8) — reverses stock + owed balance, audited. */
    fun voidRefund(refundId: String, onDone: () -> Unit = {}) {
        if (!can(Capability.VOID_SALES)) return
        viewModelScope.launch {
            repo.voidRefund(refundId, currentCashierId, currentCashierName)
            nudgeSync("voidRefund")
            onDone()
        }
    }

    /** Admin-only debt write-off (§8). */
    fun writeOffDebt(customerId: String, amount: Double, onDone: () -> Unit = {}) {
        val bid = businessId.value ?: return
        if (amount <= 0.0) return
        viewModelScope.launch {
            repo.writeOffDebt(bid, customerId, amount, currentCashierId, currentCashierName)
            nudgeSync("writeOff")
            onDone()
        }
    }

    /**
     * Force-enable/disable a payment method globally (§8). Updates the business record
     * (honored by the cashier tender picker) and audit-logs the change. Cross-device
     * propagation rides the existing business sync (deferred parity phase).
     */
    fun setPaymentMethodEnabled(method: String, enabled: Boolean) {
        val biz = business.value ?: return
        val updated = when (method) {
            "cash" -> biz.copy(cashEnabled = enabled)
            "card" -> biz.copy(cardEnabled = enabled)
            "bank" -> biz.copy(bankEnabled = enabled)
            "paynow" -> biz.copy(paynowEnabled = enabled)
            "ecocash" -> biz.copy(ecocashEnabled = enabled)
            "innbucks" -> biz.copy(innbucksEnabled = enabled)
            "onemoney" -> biz.copy(onemoneyEnabled = enabled)
            "omari" -> biz.copy(omariEnabled = enabled)
            else -> return
        }
        viewModelScope.launch {
            repo.saveBusiness(updated)
            repo.logAudit(
                businessId = biz.id,
                action = if (enabled) "payment_unlock" else "payment_lock",
                summary = "${if (enabled) "Enabled" else "Disabled"} $method payments",
                entityType = "business", entityId = biz.id,
                cashierId = currentCashierId, cashierName = currentCashierName
            )
        }
    }

    // ---- Expenses + cash ledger (accounting spine, B3) -------------------

    /**
     * SUBMIT an expense for admin approval (§9.2). Anyone may submit; nothing posts until
     * an admin approves. A recurring submission carries its period. Notifies the admin.
     */
    fun submitExpense(
        category: String, amount: Double, date: String, description: String?,
        recurring: Boolean = false, recurrencePeriod: String? = null,
        periodStart: String? = null, periodEnd: String? = null
    ) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        if (!can(Capability.MANAGE_EXPENSES_ORDERS)) return
        viewModelScope.launch {
            val e = repo.submitExpense(
                bid, category, amount, date, description, recurring, recurrencePeriod,
                periodStart, periodEnd, currentCashierId, currentCashierName
            ) ?: return@launch
            repo.notifyExpenseSubmitted(e)
            nudgeSync("submitExpense")
        }
    }

    /** Edit a still-pending submission before it's approved. */
    fun updatePendingExpense(
        id: String, category: String, amount: Double, date: String, description: String?,
        recurring: Boolean, recurrencePeriod: String?
    ) {
        if (amount <= 0) return
        if (!can(Capability.MANAGE_EXPENSES_ORDERS)) return
        viewModelScope.launch {
            repo.updatePendingExpense(id, category, amount, date, description, recurring, recurrencePeriod)
            nudgeSync("updateExpense")
        }
    }

    /**
     * Approve (post) a pending expense with the chosen funding source — the §4 waterfall
     * TILL → SAFE → OUTSIDE FUNDS → abort. [mode] is [PosRepository.planFunding]'s
     * vocabulary: "till" | "safe" | "waterfall" | "capital" | "loan" | "none".
     *
     * ★ SAFE GATE, enforced here because this is where the session role lives: if the plan
     * would open the safe and the person is NOT an admin, nothing is posted — a
     * `safe_withdrawal` request goes to the owner instead. Once approved, the money lands
     * in the till and the expense can be approved from the till normally. [onNeedsApproval]
     * fires so the UI can say so.
     */
    fun approveExpense(
        id: String, mode: String, outsideSource: String? = null,
        onNeedsApproval: () -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        viewModelScope.launch {
            val amount = pendingExpenses.value.firstOrNull { it.id == id }?.amount ?: 0.0
            val (till, safe) = cashLocationsNow()
            val plan = PosRepository.planFunding(amount, mode, till, safe)
            if (plan.needsSafe && !currentIsAdmin) {
                repo.requestSafeWithdrawal(
                    businessId = bid, amount = plan.fromSafe,
                    reason = "To pay an approved expense",
                    targetType = "expense", targetId = id, targetName = "Expense",
                    byId = currentCashierId, byName = currentCashierName
                )
                onNeedsApproval()
                return@launch
            }
            repo.approveExpense(id, mode, currentCashierId, currentCashierName, outsideSource)
            nudgeSync("approveExpense")
        }
    }

    /** Cash-on-hand right now (for deciding whether a shortfall dialog is needed). */
    suspend fun cashOnHandNow(): Double = businessId.value?.let { repo.cashOnHandOnce(it) } ?: 0.0

    fun rejectExpense(id: String) {
        viewModelScope.launch {
            repo.rejectExpense(id, currentCashierId, currentCashierName)
            nudgeSync("rejectExpense")
        }
    }

    fun setRecurringActive(templateId: String, active: Boolean) {
        viewModelScope.launch {
            repo.setRecurringActive(templateId, active)
            nudgeSync("recurringActive")
        }
    }

    fun editRecurringAmount(templateId: String, newAmount: Double) {
        if (newAmount <= 0) return
        viewModelScope.launch {
            repo.editRecurringAmount(templateId, newAmount)
            nudgeSync("recurringAmount")
        }
    }

    fun cancelRecurring(templateId: String) {
        viewModelScope.launch {
            repo.cancelRecurring(templateId)
            nudgeSync("cancelRecurring")
        }
    }

    fun deleteExpense(id: String) {
        viewModelScope.launch {
            repo.deleteExpense(id)
            nudgeSync("deleteExpense")
        }
    }

    /** Admin: set the opening cash float (persisted device-local). */
    fun setOpeningFloat(amount: Double) {
        _openingFloat.value = amount.coerceAtLeast(0.0)
        viewModelScope.launch { repo.setOpeningFloat(amount) }
    }

    /** Admin: record an ad-hoc cash top-up (+) or payout (−) in one location. */
    fun recordCashAdjustment(amount: Double, note: String, location: String = CashLocation.TILL) {
        val bid = businessId.value ?: return
        viewModelScope.launch {
            repo.recordCashAdjustment(bid, amount, note, currentCashierId, currentCashierName, location)
            nudgeSync("cashAdjustment")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TILL AND SAFE actions — every one of them ON COMMAND (§1)
    // ══════════════════════════════════════════════════════════════════════
    //  Nothing here is scheduled, nothing nags, nothing fires at a set hour. Closing the
    //  day and topping up the float are BUTTONS the person presses when they choose. The
    //  only automatic thing is the admin NOTIFICATION each one raises once it completes,
    //  so the owner learns it happened even if someone else did it.

    /** Read the figures the close-of-day sheet opens with (expected till, target, safe). */
    suspend fun dayCloseProposal(): PosRepository.DayCloseProposal? =
        businessId.value?.let { repo.dayCloseProposal(it) }

    /** Read where the till sits against its target, for the top-up sheet. */
    suspend fun floatTopUpProposal(): PosRepository.FloatTopUp? =
        businessId.value?.let { repo.floatTopUpProposal(it) }

    /**
     * CLOSE THE DAY (§2) — the owner has counted the drawer and physically moved the
     * excess. Trues the till up to [countedCash] (the count WINS), moves [moveToSafe]
     * into the safe as a transfer pair, records a permanent [DayClose] and notifies the
     * admin. [note] is required by the sheet when the variance exceeds
     * [varianceNoteThreshold]; the repository does not re-police that, it records what it
     * is given.
     */
    fun closeDay(
        countedCash: Double,
        moveToSafe: Double,
        floatTarget: Double,
        note: String?,
        onDone: (DayClose?) -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        viewModelScope.launch {
            val close = repo.closeDay(
                businessId = bid, countedCash = countedCash, moveToSafe = moveToSafe,
                floatTarget = floatTarget, note = note,
                cashierId = currentCashierId, cashierName = currentCashierName
            )
            _floatTarget.value = floatTarget.coerceAtLeast(0.0)
            nudgeSync("closeDay")
            onDone(close)
        }
    }

    /** TOP UP THE FLOAT (§3) — move [amount] from the safe into the till, on command. */
    fun topUpFloat(amount: Double, onDone: (Double) -> Unit = {}) {
        val bid = businessId.value ?: return
        viewModelScope.launch {
            val moved = repo.topUpFloat(bid, amount, currentCashierId, currentCashierName)
            nudgeSync("topUpFloat")
            onDone(moved)
        }
    }

    /** Set the float target. Written from INSIDE the close / top-up flows by design. */
    fun setFloatTarget(amount: Double) {
        _floatTarget.value = amount.coerceAtLeast(0.0)
        viewModelScope.launch { repo.setFloatTarget(amount) }
    }

    /** Admin: how far a close may miss before a note is demanded. */
    fun setVarianceNoteThreshold(amount: Double) {
        _varianceNoteThreshold.value = amount.coerceAtLeast(0.0)
        viewModelScope.launch { repo.setVarianceNoteThreshold(amount) }
    }

    /**
     * ★ THE SAFE GATE (§4). Opening the safe needs the owner's approval EVERY TIME.
     *
     *  - An ADMIN on their own device approves inline: the money moves straight away.
     *  - Anyone else raises a `safe_withdrawal` request through the existing
     *    `staff_requests` channel; it lands in the admin's Alerts feed on the other phone,
     *    and the ADMIN's device applies it (idempotently, via the `applied` flag) exactly
     *    like the credit-limit flow.
     *
     * Returns true when the money moved now, false when a request was raised instead —
     * so the caller can tell the user which of the two happened.
     */
    fun takeFromSafe(amount: Double, reason: String?, onDone: (Boolean) -> Unit = {}) {
        val bid = businessId.value ?: return
        viewModelScope.launch {
            if (currentIsAdmin) {
                val moved = repo.withdrawFromSafe(bid, amount, reason, currentCashierId, currentCashierName)
                nudgeSync("safeWithdrawal")
                onDone(moved > 0.0)
            } else {
                repo.requestSafeWithdrawal(
                    businessId = bid, amount = amount, reason = reason,
                    byId = currentCashierId, byName = currentCashierName
                )
                onDone(false)
            }
        }
    }

    /** Owner / lender money coming IN as cash (§4). Never sales, never profit. */
    fun recordOutsideCashIn(
        amount: Double, kind: String, source: String?, note: String?,
        location: String = CashLocation.SAFE
    ) {
        val bid = businessId.value ?: return
        if (amount <= 0.0) return
        viewModelScope.launch {
            repo.recordOutsideCashIn(
                bid, amount, kind, source, note, location, currentCashierId, currentCashierName
            )
            nudgeSync("outsideCashIn")
        }
    }

    /**
     * TAKE MONEY OUT (§6) — the owner drawing cash. Reduces cash and reduces what the shop
     * owes the owner. ★ It is NOT an expense and does NOT reduce profit.
     */
    fun recordOwnerDrawing(
        amount: Double, location: String = CashLocation.SAFE, note: String?,
        onDone: (Double) -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        if (amount <= 0.0) return
        viewModelScope.launch {
            val taken = repo.recordOwnerDrawing(
                bid, amount, location, note, currentCashierId, currentCashierName
            )
            nudgeSync("ownerDrawing")
            onDone(taken)
        }
    }

    /** Repay borrowed money out of shop cash (a liability going down, not an expense). */
    fun recordLoanRepayment(
        amount: Double, location: String = CashLocation.SAFE, source: String?, note: String?,
        onDone: (Double) -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        if (amount <= 0.0) return
        viewModelScope.launch {
            val paid = repo.recordLoanRepayment(
                bid, amount, location, source, note, currentCashierId, currentCashierName
            )
            nudgeSync("loanRepayment")
            onDone(paid)
        }
    }

    /** Live till + safe balances, read once (the funding pickers need them synchronously). */
    suspend fun cashLocationsNow(): Pair<Double, Double> {
        val bid = businessId.value ?: return 0.0 to 0.0
        return repo.tillBalanceOnce(bid) to repo.safeBalanceOnce(bid)
    }

    // ---- Suppliers --------------------------------------------------------

    /** Insert (id == null) or update a supplier. No-op on a blank name. */
    fun saveSupplier(
        id: String?,
        name: String,
        phone: String?,
        email: String?,
        address: String?,
        notes: String?
    ) {
        val bid = businessId.value ?: return
        if (name.isBlank()) return
        val base = Supplier(
            businessId = bid,
            name = name.trim(),
            phone = phone?.trim()?.ifBlank { null },
            email = email?.trim()?.ifBlank { null },
            address = address?.trim()?.ifBlank { null },
            notes = notes?.trim()?.ifBlank { null }
        )
        val supplier = if (id == null) base else base.copy(id = id)
        viewModelScope.launch {
            repo.saveSupplier(supplier)
            nudgeSync("saveSupplier")
        }
    }

    fun deleteSupplier(id: String) {
        viewModelScope.launch {
            repo.deleteSupplier(id)
            nudgeSync("deleteSupplier")
        }
    }

    // ---- Purchase orders --------------------------------------------------

    /**
     * Place a supplier order (B4) with its lines, PENDING stock and the cash payment.
     * [payNow] is cash paid up front; [fundingMode] is the B3-style funding choice
     * (cash | available | capital | none). No-op with no lines.
     */
    fun createPurchaseOrder(
        supplierId: String?,
        supplierName: String,
        notes: String?,
        eta: Long?,
        lines: List<PurchaseOrderLine>,
        payNow: Double,
        fundingMode: String,
        outsideSource: String? = null,
        onNeedsApproval: () -> Unit = {}
    ) {
        val bid = businessId.value ?: return
        if (lines.isEmpty()) return
        if (!can(Capability.MANAGE_EXPENSES_ORDERS)) return
        viewModelScope.launch {
            // ★ SAME SAFE GATE as expense approval (§4): a non-admin cannot open the safe
            // to pay a supplier. The order is NOT placed — the money has to arrive in the
            // till first, so the whole order can then be paid for as one honest record.
            val total = lines.sumOf { it.qty * it.unitCost }
            val (till, safe) = cashLocationsNow()
            val plan = PosRepository.planFunding(payNow.coerceIn(0.0, total), fundingMode, till, safe)
            if (plan.needsSafe && !currentIsAdmin) {
                repo.requestSafeWithdrawal(
                    businessId = bid, amount = plan.fromSafe,
                    reason = "To pay for stock from ${supplierName.trim().ifBlank { "a supplier" }}",
                    targetType = "purchase_order", targetName = "Stock purchase",
                    byId = currentCashierId, byName = currentCashierName
                )
                onNeedsApproval()
                return@launch
            }
            repo.createPurchaseOrder(
                bid, supplierId, supplierName.trim(),
                notes?.trim()?.ifBlank { null }, eta, lines, payNow, fundingMode,
                currentCashierId, currentCashierName, outsideSource
            )
            nudgeSync("createPurchaseOrder")
        }
    }

    /** Draft → placed. */
    fun markPoSent(poId: String) {
        viewModelScope.launch {
            repo.markPoSent(poId)
            nudgeSync("markPoSent")
        }
    }

    /** Cancel an open PO (rolls back its pending stock, clears the payable). */
    fun cancelPo(poId: String) {
        viewModelScope.launch {
            repo.cancelPo(poId)
            nudgeSync("cancelPo")
        }
    }

    /**
     * Confirm arrival of a PO, moving pending stock into sellable stock. [receivedByLine]
     * optionally supplies a per-line arrived quantity (partial arrival); null arrives the
     * whole outstanding order.
     */
    fun confirmArrival(poId: String, receivedByLine: Map<String, Double>? = null) {
        viewModelScope.launch {
            repo.confirmArrival(poId, receivedByLine, currentCashierId, currentCashierName)
            nudgeSync("confirmArrival")
        }
    }

    /** Settle a PO's supplier balance from cash. [mode]: cash (all) | available (what cash there is). */
    fun recordSupplierPayment(poId: String, mode: String) {
        viewModelScope.launch {
            repo.recordSupplierPayment(poId, mode, currentCashierId, currentCashierName)
            nudgeSync("supplierPayment")
        }
    }

    // ---- Cloud sync actions ----------------------------------------------

    private fun refreshSyncState() {
        viewModelScope.launch {
            _connection.value = sync.connection()
            _lastSyncAt.value = sync.lastSyncAt()
            _lastUploadAt.value = sync.lastUploadAt()
            _lastDownloadAt.value = sync.lastDownloadAt()
            _pendingUpload.value = sync.pendingUploadCount()
            _cloudPushEnabled.value = sync.config.pushEnabled()
        }
    }

    /** Refresh just the queue depth (cheap) — after a local write, for the badge. */
    fun refreshPendingUpload() {
        viewModelScope.launch { _pendingUpload.value = sync.pendingUploadCount() }
    }

    /**
     * Fire-and-forget: nudge cloud sync after a money-moving local write and refresh the
     * pending badge. Debounced inside [SyncManager], so calling it on every mutation is
     * cheap and a burst collapses to one pass. Never blocks the caller.
     */
    private fun nudgeSync(reason: String) {
        sync.requestSync(reason)
        refreshPendingUpload()
    }

    /**
     * Pull-on-open for the admin-facing screens (admin shell / alerts). Opening them
     * should show what the OTHER phones have done, not the last ~15-minute cycle's
     * snapshot. Coalesced inside [SyncManager] (a pass that ran seconds ago is reused),
     * so switching admin tabs costs no extra data.
     */
    fun refreshFromCloud(reason: String = "admin-open") {
        sync.requestPullNow(reason)
        refreshPendingUpload()
    }

    /** Turn cloud PUSH on/off (Stage 2). Off by default; only enable after the pull
     *  is verified and any local test data has been cleared. */
    fun setCloudPushEnabled(on: Boolean) {
        viewModelScope.launch {
            sync.config.setPushEnabled(on)
            _cloudPushEnabled.value = on
        }
    }

    /** Check a connection without saving it. */
    fun testConnection(url: String, key: String, onResult: (ConnectionTest) -> Unit) {
        viewModelScope.launch { onResult(sync.test(url, key)) }
    }

    /** Save the connection, start background sync, and sync once now. */
    fun connect(url: String, key: String, onResult: (SyncOutcome) -> Unit = {}) {
        viewModelScope.launch {
            val outcome = sync.connect(url, key)
            refreshSyncState()
            onResult(outcome)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            sync.disconnect()
            refreshSyncState()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            sync.runNow()
            // A pull can surface a cloud copy of a hand-added item; collapse any such
            // duplicate immediately so the catalogue self-cleans without user action.
            repo.dedupeItems()
            refreshSyncState()
            // Pick up any permission change an admin made to this user on another device.
            authManager.refreshCurrentPermissions()
        }
    }

    // ---- Account switching on this device (multi-account vault) ----

    /** Lock this device and return to the account picker. Every cached account is
     *  kept, so another cashier (or the admin) can unlock with their own PIN. */
    fun switchUser() = authManager.switchUser()

    /** Remove the CURRENT account from this device (session + PIN), then drop to the
     *  picker if other accounts remain, else to login. Local Room data is untouched. */
    fun signOut() {
        viewModelScope.launch { authManager.signOut() }
    }

    // ---- Staff / cashier accounts (admin, via the create-cashier Edge Function) ----

    private val _staff = MutableStateFlow<List<com.portionspot.pos.auth.StaffRow>>(emptyList())
    val staff: StateFlow<List<com.portionspot.pos.auth.StaffRow>> = _staff.asStateFlow()

    private suspend fun staffClient(): com.portionspot.pos.auth.StaffAdminClient? {
        val conn = sync.connection() ?: return null
        return com.portionspot.pos.auth.StaffAdminClient(conn) { authManager.accessTokenOrNull() }
    }

    /** Reload the staff list from the cloud (admin only; needs a connection). */
    fun refreshStaff() {
        viewModelScope.launch {
            val client = staffClient() ?: run { _staff.value = emptyList(); return@launch }
            _staff.value = withContext(Dispatchers.IO) { client.listStaff() }
        }
    }

    /** Create a cashier login via the Edge Function, then refresh the list. */
    fun createCashier(
        email: String, password: String, displayName: String, role: String = "cashier",
        onResult: (com.portionspot.pos.auth.StaffResult) -> Unit,
    ) {
        if (!can(Capability.MANAGE_STAFF)) {
            onResult(com.portionspot.pos.auth.StaffResult.Err("You don't have permission to manage staff"))
            return
        }
        viewModelScope.launch {
            val client = staffClient()
                ?: return@launch onResult(com.portionspot.pos.auth.StaffResult.Err("Connect cloud sync first"))
            val r = withContext(Dispatchers.IO) { client.createCashier(email, password, displayName, role) }
            if (r is com.portionspot.pos.auth.StaffResult.Ok) refreshStaff()
            onResult(r)
        }
    }

    /**
     * Save a staff member's capability grants (admin-only, direct PATCH to the staff row).
     *
     * The editor's map is normalised through [Permissions.toWireMap] before it goes up, so
     * the column is written with EVERY capability stated explicitly and under both this
     * app's and the web's spelling. The two clients read absence differently — this app
     * treats a missing key as denied, the web as allowed — so a partial map is the one
     * thing that could have them disagree about the same cashier. Stating everything
     * removes the question rather than answering it.
     */
    fun setStaffPermissions(
        staffId: String,
        permissions: Map<String, Boolean>,
        onResult: (com.portionspot.pos.auth.StaffResult) -> Unit = {},
    ) {
        if (!can(Capability.MANAGE_STAFF)) {
            onResult(com.portionspot.pos.auth.StaffResult.Err("You don't have permission to manage staff"))
            return
        }
        val wire = Permissions.fromKeyMap(permissions).toWireMap()
        viewModelScope.launch {
            val client = staffClient()
                ?: return@launch onResult(com.portionspot.pos.auth.StaffResult.Err("Connect cloud sync first"))
            val r = withContext(Dispatchers.IO) { client.setPermissions(staffId, wire) }
            if (r is com.portionspot.pos.auth.StaffResult.Ok) refreshStaff()
            onResult(r)
        }
    }

    /** Reset a staff member's password (admin-only, via the Edge Function). */
    fun resetCashierPassword(
        staffId: String, password: String,
        onResult: (com.portionspot.pos.auth.StaffResult) -> Unit = {},
    ) {
        if (!can(Capability.MANAGE_STAFF)) {
            onResult(com.portionspot.pos.auth.StaffResult.Err("You don't have permission to manage staff"))
            return
        }
        viewModelScope.launch {
            val client = staffClient()
                ?: return@launch onResult(com.portionspot.pos.auth.StaffResult.Err("Connect cloud sync first"))
            val r = withContext(Dispatchers.IO) { client.resetPassword(staffId, password) }
            onResult(r)
        }
    }

    /** Activate/deactivate a staff member (admin-only delete = deactivate). */
    fun setCashierActive(
        staffId: String, active: Boolean,
        onResult: (com.portionspot.pos.auth.StaffResult) -> Unit = {},
    ) {
        if (!can(Capability.MANAGE_STAFF)) {
            onResult(com.portionspot.pos.auth.StaffResult.Err("You don't have permission to manage staff"))
            return
        }
        viewModelScope.launch {
            val client = staffClient()
                ?: return@launch onResult(com.portionspot.pos.auth.StaffResult.Err("Connect cloud sync first"))
            val r = withContext(Dispatchers.IO) { client.setActive(staffId, active) }
            if (r is com.portionspot.pos.auth.StaffResult.Ok) refreshStaff()
            onResult(r)
        }
    }

    /** Merge duplicate catalogue items (safe — leaves sales/customers alone). Reports
     *  how many rows were removed. See [PosRepository.dedupeItems]. */
    fun dedupeItems(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val removed = repo.dedupeItems()
            onDone(removed)
        }
    }

    /** Danger zone: wipe this device's local test data, forget the pull cursors, then
     *  re-pull the shared dataset clean. */
    fun resetLocalData(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repo.resetLocalData()
            sync.config.resetCursors()
            sync.runNow()
            refreshSyncState()
            onDone()
        }
    }

    // ---- Paynow online --------------------------------------------------

    /**
     * True when the shop can take Paynow online: Cloud sync is connected (so the
     * Edge Functions exist) AND the owner has switched Paynow on in Settings.
     */
    val paynowOnlineReady: StateFlow<Boolean> =
        combine(_connection, business) { conn, biz ->
            conn != null && (biz?.paynowEnabled ?: false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Ask the shop's Edge Function to start a Paynow payment; returns ref + QR URL. */
    fun paynowInitiate(amount: Double, onResult: (PaynowInit) -> Unit) {
        viewModelScope.launch {
            val conn = sync.connection()
            if (conn == null) {
                onResult(PaynowInit.Err("Connect Cloud sync first"))
                return@launch
            }
            val biz = business.value
            val result = withContext(Dispatchers.IO) {
                PaynowClient(conn).initiate(
                    amount = amount,
                    authEmail = biz?.email,
                    businessId = biz?.id
                )
            }
            onResult(result)
        }
    }

    /** Poll a started Paynow payment once for its current status. */
    fun paynowPoll(reference: String, onResult: (PaynowPoll) -> Unit) {
        viewModelScope.launch {
            val conn = sync.connection()
            if (conn == null) {
                onResult(PaynowPoll.Err("Not connected"))
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                PaynowClient(conn).status(reference)
            }
            onResult(result)
        }
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfMonth(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Fold raw sale stamps into 7 day-buckets (oldest → today) with short labels. */
    private fun bucketLast7Days(stamps: List<SaleStamp>): List<DayBar> {
        val start = startOfToday() - 6 * DAY_MS
        val totals = DoubleArray(7)
        for (s in stamps) {
            val idx = ((s.soldAt - start) / DAY_MS).toInt()
            if (idx in 0..6) totals[idx] += s.total
        }
        val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
        val cal = Calendar.getInstance()
        return (0..6).map { i ->
            cal.timeInMillis = start + i * DAY_MS
            DayBar(fmt.format(cal.time), totals[i])
        }
    }

    /** [from, to) epoch-millis bounds for a report window. */
    private fun rangeBounds(range: ReportRange): Pair<Long, Long> {
        val dayMs = 24L * 60 * 60 * 1000
        val to = Long.MAX_VALUE   // sales can't be in the future; open upper bound
        val from = when (range) {
            ReportRange.TODAY -> startOfToday()
            ReportRange.WEEK -> startOfToday() - 6 * dayMs
            ReportRange.MONTH -> startOfMonth()
            ReportRange.ALL -> 0L
        }
        return from to to
    }

    companion object {
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_THEME_ACCENT_HEX = "theme_accent_hex"
        private const val KEY_THEME_BG = "theme_background"
        private const val KEY_THEME_SIDEBAR = "theme_sidebar"
        private const val KEY_APP_MODE = "app_mode"        // "local" | "cloud"
        private const val KEY_ONBOARDED = "onboarded"      // "1" once first-run choice made
        private const val KEY_RC_FONT = "rc_font_scale"
        private const val KEY_RC_FEED = "rc_feed_lines"
        private const val KEY_RC_BOLD = "rc_bold_name"
        private const val KEY_RC_LOGO = "rc_show_logo"
        private const val KEY_RC_TAGLINE = "rc_show_tagline"
        private const val KEY_RC_ADDRESS = "rc_show_address"
        private const val KEY_RC_VAT = "rc_show_vat"
        private const val KEY_RC_CASHIER = "rc_show_cashier"
        private const val KEY_RC_PAYMENT = "rc_show_payment"
        private const val KEY_RC_CHANGE = "rc_show_change"
        private const val KEY_RC_BOLD_TOTALS = "rc_bold_totals"
        private const val KEY_RC_FOOTER = "rc_show_footer"
        private const val KEY_ROUND_WS = "round_wholesale"
        private const val KEY_ROUND_CO = "round_checkout"
        private const val KEY_QUOTE_DAYS = "quote_validity_days"
        private const val KEY_DISCOUNT_THRESHOLD = "discount_threshold_pct"
        private const val KEY_MAX_ITEM_DISCOUNT = "max_item_discount"
        private const val KEY_SALE_EDIT_WINDOW = "sale_edit_window_min"
        private const val KEY_MARGIN_FORMULA = "margin_formula"
        private const val KEY_AUTO_BOXES = "auto_units_to_boxes"
        private const val KEY_PRINTER_TYPE = "printer_type"
        private const val KEY_RC_PRESET = "rc_preset"
        private const val KEY_CUR2_CODE = "second_currency_code"
        private const val KEY_CUR2_RATE = "second_currency_rate"

        fun factory(
            repo: PosRepository,
            sync: SyncManager,
            authManager: com.portionspot.pos.auth.AuthManager,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PosViewModel(repo, sync, authManager) as T
            }
    }
}
