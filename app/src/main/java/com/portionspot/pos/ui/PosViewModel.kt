package com.portionspot.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.CartLine
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.CreditTxn
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
import com.portionspot.pos.data.SalesSummary
import com.portionspot.pos.data.SaleStamp
import com.portionspot.pos.data.StockMovement
import com.portionspot.pos.data.Tender
import com.portionspot.pos.data.TopProduct
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

    // The signed-in cashier, pushed in from AuthGate (see MainActivity). Stamped onto
    // refunds now, and onto the other financial writes as Phase-2 wiring continues.
    private var currentCashierId: String? = null
    private var currentCashierName: String? = null
    private var currentIsAdmin: Boolean = false
    fun setCurrentCashier(id: String?, name: String?, isAdmin: Boolean = false) {
        currentCashierId = id
        currentCashierName = name
        currentIsAdmin = isAdmin
    }

    /**
     * Does a [discount] (currency units) on goods worth [subtotal] need manager
     * approval before it can be applied? True only for a cashier whose discount
     * exceeds the shop's [ShopPrefs.discountThresholdPct]. Admins are never gated,
     * and a threshold of 0 disables the gate. See [verifyAdminPin].
     */
    fun discountNeedsApproval(discount: Double, subtotal: Double): Boolean {
        if (currentIsAdmin || discount <= 0.0 || subtotal <= 0.0) return false
        val threshold = _shopPrefs.value.discountThresholdPct
        if (threshold <= 0.0) return false
        return (discount / subtotal) * 100.0 > threshold
    }

    /** Verify a manager/admin PIN to authorise an over-threshold discount. */
    suspend fun verifyAdminPin(pin: String): Boolean = authManager.verifyAdminPin(pin)

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

    // Opening cash float (B3): the starting cash-on-hand the admin sets. Device-local;
    // combined with the cash-ledger movements to give [cashOnHand]. Declared before
    // init{} for the same reason as _shopPrefs above.
    private val _openingFloat = MutableStateFlow(0.0)
    val openingFloat: StateFlow<Double> = _openingFloat.asStateFlow()

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
            _appMode.value = if (repo.getSetting(KEY_APP_MODE) == "cloud") AppMode.Cloud else AppMode.Local
            _onboarded.value = repo.getSetting(KEY_ONBOARDED) == "1"
            _hasLocalPin.value = authManager.hasLocalPin()
            _bootLoaded.value = true
        }
        refreshSyncState()
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
        val biz = business.value
        viewModelScope.launch {
            try {
                val saved = repo.checkout(
                    bid, lines, payments, discount,
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
        val biz = business.value
        viewModelScope.launch {
            val saved = repo.saveQuote(
                bid, lines, discount,
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
        viewModelScope.launch { onCreated(repo.createCustomer(bid, name, phone)) }
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
        }
    }

    /** Persist edits to an existing item (rename, reprice, restock, toggle tracking). */
    fun updateItem(item: Item) {
        viewModelScope.launch { repo.saveItem(item) }
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
        creditLimit: Double? = null
    ) {
        val bid = businessId.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.saveCustomer(
                Customer(
                    businessId = bid,
                    name = name.trim(),
                    phone = phone?.trim()?.ifBlank { null },
                    email = email?.trim()?.ifBlank { null },
                    address = address?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    wholesale = wholesale,
                    creditLimit = creditLimit
                )
            )
        }
    }

    /** Save edits to an existing customer's details (keeps the same id; re-flags for sync). */
    fun updateCustomer(
        customer: Customer,
        name: String,
        phone: String?,
        email: String?,
        address: String?,
        note: String?,
        wholesale: Boolean,
        creditLimit: Double?
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.saveCustomer(
                customer.copy(
                    name = name.trim(),
                    phone = phone?.trim()?.ifBlank { null },
                    email = email?.trim()?.ifBlank { null },
                    address = address?.trim()?.ifBlank { null },
                    note = note?.trim()?.ifBlank { null },
                    wholesale = wholesale,
                    creditLimit = creditLimit,
                    updatedAt = System.currentTimeMillis(),
                    pendingSync = true
                )
            )
        }
    }

    /** Flip the local-only wholesale flag on an existing customer. */
    fun setCustomerWholesale(customer: Customer, wholesale: Boolean) {
        if (customer.wholesale == wholesale) return
        viewModelScope.launch { repo.saveCustomer(customer.copy(wholesale = wholesale)) }
    }

    /** Pay down a customer's outstanding balance. */
    fun recordRepayment(customerId: String, amount: Double, note: String? = null) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        viewModelScope.launch {
            repo.recordRepayment(bid, customerId, amount, note, currentCashierId, currentCashierName)
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
            onDone()
        }
    }

    /** Pay off part/all of a refund the shop still owes (writes a payout + refund_paid). */
    fun recordRefundPayout(refundId: String, tender: Tender, onDone: () -> Unit = {}) {
        if (tender.amount <= 0) return
        viewModelScope.launch {
            repo.recordRefundPayout(refundId, tender, currentCashierId, currentCashierName)
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
        viewModelScope.launch { repo.assignMobileMoneyCustomer(receiptId, customer) }
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
        viewModelScope.launch { repo.unverifyMobileMoney(receiptId, currentCashierId, currentCashierName) }
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

    /** Append-only audit trail (newest first) for the admin audit-log viewer. */
    val auditLog: StateFlow<List<AuditEntry>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.auditFlow(it) }
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
        viewModelScope.launch {
            repo.voidRefund(refundId, currentCashierId, currentCashierName)
            onDone()
        }
    }

    /** Admin-only debt write-off (§8). */
    fun writeOffDebt(customerId: String, amount: Double, onDone: () -> Unit = {}) {
        val bid = businessId.value ?: return
        if (amount <= 0.0) return
        viewModelScope.launch {
            repo.writeOffDebt(bid, customerId, amount, currentCashierId, currentCashierName)
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
        viewModelScope.launch {
            val e = repo.submitExpense(
                bid, category, amount, date, description, recurring, recurrencePeriod,
                periodStart, periodEnd, currentCashierId, currentCashierName
            ) ?: return@launch
            repo.notifyExpenseSubmitted(e)
        }
    }

    /** Edit a still-pending submission before it's approved. */
    fun updatePendingExpense(
        id: String, category: String, amount: Double, date: String, description: String?,
        recurring: Boolean, recurrencePeriod: String?
    ) {
        if (amount <= 0) return
        viewModelScope.launch {
            repo.updatePendingExpense(id, category, amount, date, description, recurring, recurrencePeriod)
        }
    }

    /** Approve (post) a pending expense with the chosen funding mode: cash | available |
     *  capital (the shortfall decision from §9.4). */
    fun approveExpense(id: String, mode: String) {
        viewModelScope.launch { repo.approveExpense(id, mode, currentCashierId, currentCashierName) }
    }

    /** Cash-on-hand right now (for deciding whether a shortfall dialog is needed). */
    suspend fun cashOnHandNow(): Double = businessId.value?.let { repo.cashOnHandOnce(it) } ?: 0.0

    fun rejectExpense(id: String) {
        viewModelScope.launch { repo.rejectExpense(id, currentCashierId, currentCashierName) }
    }

    fun setRecurringActive(templateId: String, active: Boolean) {
        viewModelScope.launch { repo.setRecurringActive(templateId, active) }
    }

    fun editRecurringAmount(templateId: String, newAmount: Double) {
        if (newAmount <= 0) return
        viewModelScope.launch { repo.editRecurringAmount(templateId, newAmount) }
    }

    fun cancelRecurring(templateId: String) {
        viewModelScope.launch { repo.cancelRecurring(templateId) }
    }

    fun deleteExpense(id: String) {
        viewModelScope.launch { repo.deleteExpense(id) }
    }

    /** Admin: set the opening cash float (persisted device-local). */
    fun setOpeningFloat(amount: Double) {
        _openingFloat.value = amount.coerceAtLeast(0.0)
        viewModelScope.launch { repo.setOpeningFloat(amount) }
    }

    /** Admin: record an ad-hoc cash top-up (+) or payout (−) against the drawer. */
    fun recordCashAdjustment(amount: Double, note: String) {
        val bid = businessId.value ?: return
        viewModelScope.launch { repo.recordCashAdjustment(bid, amount, note, currentCashierId, currentCashierName) }
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
        viewModelScope.launch { repo.saveSupplier(supplier) }
    }

    fun deleteSupplier(id: String) {
        viewModelScope.launch { repo.deleteSupplier(id) }
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
        fundingMode: String
    ) {
        val bid = businessId.value ?: return
        if (lines.isEmpty()) return
        viewModelScope.launch {
            repo.createPurchaseOrder(
                bid, supplierId, supplierName.trim(),
                notes?.trim()?.ifBlank { null }, eta, lines, payNow, fundingMode,
                currentCashierId, currentCashierName
            )
        }
    }

    /** Draft → placed. */
    fun markPoSent(poId: String) {
        viewModelScope.launch { repo.markPoSent(poId) }
    }

    /** Cancel an open PO (rolls back its pending stock, clears the payable). */
    fun cancelPo(poId: String) {
        viewModelScope.launch { repo.cancelPo(poId) }
    }

    /**
     * Confirm arrival of a PO, moving pending stock into sellable stock. [receivedByLine]
     * optionally supplies a per-line arrived quantity (partial arrival); null arrives the
     * whole outstanding order.
     */
    fun confirmArrival(poId: String, receivedByLine: Map<String, Double>? = null) {
        viewModelScope.launch { repo.confirmArrival(poId, receivedByLine, currentCashierId, currentCashierName) }
    }

    /** Settle a PO's supplier balance from cash. [mode]: cash (all) | available (what cash there is). */
    fun recordSupplierPayment(poId: String, mode: String) {
        viewModelScope.launch { repo.recordSupplierPayment(poId, mode, currentCashierId, currentCashierName) }
    }

    // ---- Cloud sync actions ----------------------------------------------

    private fun refreshSyncState() {
        viewModelScope.launch {
            _connection.value = sync.connection()
            _lastSyncAt.value = sync.lastSyncAt()
            _cloudPushEnabled.value = sync.config.pushEnabled()
        }
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
        viewModelScope.launch {
            val client = staffClient()
                ?: return@launch onResult(com.portionspot.pos.auth.StaffResult.Err("Connect cloud sync first"))
            val r = withContext(Dispatchers.IO) { client.createCashier(email, password, displayName, role) }
            if (r is com.portionspot.pos.auth.StaffResult.Ok) refreshStaff()
            onResult(r)
        }
    }

    /** Reset a staff member's password (admin-only, via the Edge Function). */
    fun resetCashierPassword(
        staffId: String, password: String,
        onResult: (com.portionspot.pos.auth.StaffResult) -> Unit = {},
    ) {
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
