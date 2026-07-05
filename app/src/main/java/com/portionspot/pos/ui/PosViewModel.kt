package com.portionspot.pos.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.CartLine
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.CustomerWithBalance
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.Supplier
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.MethodBreakdown
import com.portionspot.pos.data.PosRepository
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.PurchaseOrderWithLines
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
data class CreditLedgerRow(val txn: CreditTxn, val customerName: String)

private const val DAY_MS = 24L * 60 * 60 * 1000

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModel(
    private val repo: PosRepository,
    private val sync: SyncManager
) : ViewModel() {

    private val businessId = MutableStateFlow<String?>(null)

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
                combine(repo.creditLedgerFlow(bid), repo.customersFlow(bid)) { txns, custs ->
                    val nameById = custs.associate { it.id to it.name }
                    txns.map { CreditLedgerRow(it, nameById[it.customerId] ?: "Unknown") }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All (non-deleted) expenses for the shop, newest date first. */
    val expenses: StateFlow<List<Expense>> =
        businessId.filterNotNull()
            .flatMapLatest { repo.expensesFlow(it) }
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
        }
        viewModelScope.launch { _themeChoice.value = loadTheme() }
        viewModelScope.launch { _shopPrefs.value = loadPrefs() }
        refreshSyncState()
    }

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
            receiptShowFooter = repo.getSetting(KEY_RC_FOOTER)?.toBooleanStrictOrNull() ?: d.receiptShowFooter,
            wholesaleRounding = repo.getSetting(KEY_ROUND_WS)?.toDoubleOrNull() ?: d.wholesaleRounding,
            checkoutRounding = repo.getSetting(KEY_ROUND_CO)?.toDoubleOrNull() ?: d.checkoutRounding,
            marginFormula = repo.getSetting(KEY_MARGIN_FORMULA) ?: d.marginFormula,
            autoConvertUnitsToBoxes = repo.getSetting(KEY_AUTO_BOXES)?.toBooleanStrictOrNull() ?: d.autoConvertUnitsToBoxes,
            printerType = repo.getSetting(KEY_PRINTER_TYPE) ?: d.printerType,
            secondCurrencyCode = repo.getSetting(KEY_CUR2_CODE) ?: d.secondCurrencyCode,
            secondCurrencyRate = repo.getSetting(KEY_CUR2_RATE)?.toDoubleOrNull() ?: d.secondCurrencyRate,
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
            repo.putSetting(KEY_RC_FOOTER, prefs.receiptShowFooter.toString())
            repo.putSetting(KEY_ROUND_WS, prefs.wholesaleRounding.toString())
            repo.putSetting(KEY_ROUND_CO, prefs.checkoutRounding.toString())
            repo.putSetting(KEY_MARGIN_FORMULA, prefs.marginFormula)
            repo.putSetting(KEY_AUTO_BOXES, prefs.autoConvertUnitsToBoxes.toString())
            repo.putSetting(KEY_PRINTER_TYPE, prefs.printerType)
            repo.putSetting(KEY_CUR2_CODE, prefs.secondCurrencyCode)
            repo.putSetting(KEY_CUR2_RATE, prefs.secondCurrencyRate.toString())
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
     * [customer]'s account; [changeAsCredit] books any overpayment as change owed
     * to the customer instead of handing it back.
     *
     * VAT is read from the active business so the cashier never has to think
     * about it — it is applied automatically when the business has it enabled.
     */
    fun checkout(
        payments: List<Tender>,
        discount: Double = 0.0,
        customer: Customer? = null,
        onCredit: Boolean = false,
        changeAsCredit: Boolean = false
    ) {
        val bid = businessId.value ?: return
        val lines = _cart.value
        if (lines.isEmpty()) return
        val biz = business.value
        viewModelScope.launch {
            val saved = repo.checkout(
                bid, lines, payments, discount,
                customer = customer,
                onCredit = onCredit,
                changeAsCredit = changeAsCredit,
                vatEnabled = biz?.vatEnabled ?: false,
                vatPercent = biz?.vatPercent ?: 0.0,
                totalRounding = _shopPrefs.value.checkoutRounding
            )
            _lastReceipt.value = LastReceipt(saved.sale, saved.lines)
            _cart.value = emptyList()
        }
    }

    fun dismissReceipt() {
        _lastReceipt.value = null
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
            repo.parkSale(bid, lines, note = note, customer = customer)
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
        category: String? = null,
        sku: String? = null,
        barcode: String? = null,
        taxRate: Double = 0.0,
        trackStock: Boolean = false,
        stockQty: Double = 0.0,
        reorderLevel: Double = 0.0,
        cost: Double? = null,
        unit: String = "pc"
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
                    taxRate = taxRate,
                    trackStock = trackStock,
                    stockQty = if (trackStock) stockQty else 0.0,
                    reorderLevel = if (trackStock) reorderLevel else 0.0,
                    cost = cost,
                    unit = unit.trim().ifBlank { "pc" }
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
        viewModelScope.launch { repo.adjustStock(itemId, newQty, type, note) }
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
        wholesale: Boolean = false
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
                    wholesale = wholesale
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
        viewModelScope.launch { repo.recordRepayment(bid, customerId, amount, note) }
    }

    fun balanceFlow(customerId: String): Flow<Double> = repo.balanceFlow(customerId)

    /** Change the shop still owes this customer (change_owed − change_paid). */
    fun changeBalanceFlow(customerId: String): Flow<Double> = repo.changeBalanceFlow(customerId)

    /** Hand over change the shop previously owed a customer. */
    fun recordChangePayment(customerId: String, amount: Double, note: String? = null) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        viewModelScope.launch { repo.recordChangePayment(bid, customerId, amount, note) }
    }

    fun creditHistory(customerId: String): Flow<List<CreditTxn>> =
        repo.creditHistoryFlow(customerId)

    // ---- Expenses ---------------------------------------------------------

    /** Insert (id == null) or update an expense. No-op on a non-positive amount. */
    fun saveExpense(id: String?, category: String, amount: Double, date: String, description: String?) {
        val bid = businessId.value ?: return
        if (amount <= 0) return
        val base = Expense(
            businessId = bid, category = category, amount = amount,
            date = date, description = description?.trim()?.ifBlank { null }
        )
        val expense = if (id == null) base else base.copy(id = id)
        viewModelScope.launch { repo.saveExpense(expense) }
    }

    fun deleteExpense(id: String) {
        viewModelScope.launch { repo.deleteExpense(id) }
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

    /** Create a draft PO with the chosen supplier and lines. No-op with no lines. */
    fun createPurchaseOrder(
        supplierId: String?,
        supplierName: String,
        notes: String?,
        lines: List<PurchaseOrderLine>
    ) {
        val bid = businessId.value ?: return
        if (lines.isEmpty()) return
        viewModelScope.launch {
            repo.createPurchaseOrder(
                bid, supplierId, supplierName.trim(),
                notes?.trim()?.ifBlank { null }, lines
            )
        }
    }

    /** Draft → sent. */
    fun markPoSent(poId: String) {
        viewModelScope.launch { repo.markPoSent(poId) }
    }

    /** Cancel an open PO. */
    fun cancelPo(poId: String) {
        viewModelScope.launch { repo.cancelPo(poId) }
    }

    /**
     * Receive a PO, restocking each line. [enteredByLine] maps line id → quantity
     * entered; [boxMode] reads those as boxes (× pack size) instead of units.
     */
    fun receivePurchaseOrder(poId: String, enteredByLine: Map<String, Double>, boxMode: Boolean) {
        viewModelScope.launch { repo.receivePurchaseOrder(poId, enteredByLine, boxMode) }
    }

    // ---- Cloud sync actions ----------------------------------------------

    private fun refreshSyncState() {
        viewModelScope.launch {
            _connection.value = sync.connection()
            _lastSyncAt.value = sync.lastSyncAt()
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
            refreshSyncState()
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
        private const val KEY_RC_FONT = "rc_font_scale"
        private const val KEY_RC_FEED = "rc_feed_lines"
        private const val KEY_RC_BOLD = "rc_bold_name"
        private const val KEY_RC_LOGO = "rc_show_logo"
        private const val KEY_RC_TAGLINE = "rc_show_tagline"
        private const val KEY_RC_ADDRESS = "rc_show_address"
        private const val KEY_RC_VAT = "rc_show_vat"
        private const val KEY_RC_FOOTER = "rc_show_footer"
        private const val KEY_ROUND_WS = "round_wholesale"
        private const val KEY_ROUND_CO = "round_checkout"
        private const val KEY_MARGIN_FORMULA = "margin_formula"
        private const val KEY_AUTO_BOXES = "auto_units_to_boxes"
        private const val KEY_PRINTER_TYPE = "printer_type"
        private const val KEY_CUR2_CODE = "second_currency_code"
        private const val KEY_CUR2_RATE = "second_currency_rate"

        fun factory(repo: PosRepository, sync: SyncManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PosViewModel(repo, sync) as T
            }
    }
}
