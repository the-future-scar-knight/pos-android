@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.portionspot.pos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AssignmentReturn
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.portionspot.pos.data.Business
import com.portionspot.pos.data.CartLine
import com.portionspot.pos.data.baseToSecond
import com.portionspot.pos.data.secondToBase
import com.portionspot.pos.data.secondCurrencyActive
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.CustomerWithBalance
import com.portionspot.pos.data.DebtAgingRow
import com.portionspot.pos.device.CallLogAccess
import com.portionspot.pos.device.PickedContact
import com.portionspot.pos.device.RecentCall
import com.portionspot.pos.device.phoneKey
import android.content.pm.PackageManager
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.device.rememberContactPicker
import com.portionspot.pos.data.Tender
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.Supplier
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.MethodBreakdown
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.PurchaseOrderWithLines
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundLineInput
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.RefundWithLines
import com.portionspot.pos.data.computeRefundTotal
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalesSummary
import com.portionspot.pos.data.TopProduct
import com.portionspot.pos.payments.PaymentMethod
import com.portionspot.pos.payments.PayInstructions
import com.portionspot.pos.payments.PaynowInit
import com.portionspot.pos.payments.PaynowPoll
import com.portionspot.pos.payments.QrCodes
import com.portionspot.pos.payments.enabledPaymentMethods
import com.portionspot.pos.payments.payInstructions
import com.portionspot.pos.print.BluetoothPrinter
import com.portionspot.pos.print.PrintResult
import com.portionspot.pos.print.PrinterDevice
import com.portionspot.pos.pdf.PdfDocs
import com.portionspot.pos.pdf.PdfFiles
import com.portionspot.pos.print.ReceiptPrinter
import com.portionspot.pos.print.ReceiptStyle
import com.portionspot.pos.sync.ConnectionTest
import com.portionspot.pos.sync.SyncOutcome
import com.portionspot.pos.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App destinations. Mirrors the web AppShell NAV — the bottom bar pins
 * POS · Sales · Sync · Inventory and tucks the rest under "More", exactly like
 * the mobile web. [short] is the bottom-nav label, [label] the drawer label.
 */
private enum class Screen(val label: String, val short: String) {
    Sell("Point of Sale", "POS"),
    Dashboard("Dashboard", "Dash"),
    Reports("Sales & Reports", "Sales"),
    Sync("Sync", "Sync"),
    Items("Inventory", "Inventory"),
    Customers("Customers", "Customers"),
    Credit("Change & Credit", "Credit"),
    Expenses("Expenses", "Expenses"),
    Suppliers("Suppliers", "Suppliers"),
    Purchases("Purchase Orders", "POs"),
    Receipts("Receipts", "Receipts"),
    Refunds("Refunds", "Refunds"),
    MobileMoney("Mobile Money", "MoMo"),
    Settings("Settings", "Settings"),
}

/** Bottom-nav pinned set (matches web PINNED_IDS: pos, sales, sync, inventory). */
private val PINNED_SCREENS = listOf(Screen.Sell, Screen.Reports, Screen.Sync, Screen.Items)
private val OVERFLOW_SCREENS =
    listOf(
        Screen.Dashboard, Screen.Customers, Screen.Credit,
        Screen.Expenses, Screen.Suppliers, Screen.Purchases, Screen.Receipts,
        Screen.Refunds, Screen.MobileMoney, Screen.Settings
    )

private fun screenIcon(s: Screen): androidx.compose.ui.graphics.vector.ImageVector = when (s) {
    Screen.Sell -> Icons.Filled.ShoppingCart
    Screen.Dashboard -> Icons.Filled.Dashboard
    Screen.Reports -> Icons.Filled.BarChart
    Screen.Sync -> Icons.Filled.Sync
    Screen.Items -> Icons.Filled.Inventory2
    Screen.Customers -> Icons.Filled.People
    Screen.Credit -> Icons.Filled.Payments
    Screen.Expenses -> Icons.Filled.Receipt
    Screen.Suppliers -> Icons.Filled.LocalShipping
    Screen.Purchases -> Icons.AutoMirrored.Filled.Assignment
    Screen.Receipts -> Icons.AutoMirrored.Filled.ReceiptLong
    Screen.Refunds -> Icons.Filled.AssignmentReturn
    Screen.MobileMoney -> Icons.Filled.Sms
    Screen.Settings -> Icons.Filled.Settings
}

fun money(amount: Double, currency: String = "USD"): String {
    val symbol = when (currency.uppercase()) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "ZAR" -> "R"
        "ZWL", "ZWG" -> "Z$"
        else -> "$currency "
    }
    return symbol + String.format("%.2f", amount)
}

// ───────────────────────── PRINTING ─────────────────────────

/**
 * Small UI-side helper that owns the Bluetooth permission flow + toasts so the
 * individual screens just call print()/test() without repeating the plumbing.
 */
private class PrinterUi(
    val context: Context,
    private val scope: CoroutineScope,
    private val permLauncher: ManagedActivityResultLauncher<String, Boolean>,
    private val pending: MutableState<(() -> Unit)?>,
    private val prefsProvider: () -> ShopPrefs
) {
    private fun style(): ReceiptStyle {
        val p = prefsProvider()
        // Layer the chosen preset (Compact/Standard/Detailed) over the individual toggles.
        val flags = applyReceiptPreset(
            ReceiptStyleFlags(
                largeText = p.receiptFontScale >= 1.2f,
                feedLines = p.receiptFeedLines,
                showTagline = p.receiptShowTagline,
                showAddress = p.receiptShowAddress,
                showFooter = p.receiptShowFooter,
            ),
            p.receiptPreset
        )
        return ReceiptStyle(
            largeText = flags.largeText,
            feedLines = flags.feedLines,
            boldName = p.receiptBoldName,
            showLogo = p.receiptShowLogo,
            showTagline = flags.showTagline,
            showAddress = flags.showAddress,
            showVat = p.receiptShowVat,
            showFooter = flags.showFooter,
            secondCode = p.secondCurrencyCode,
            secondRate = p.secondCurrencyRate
        )
    }

    /** Chosen printer target: "bluetooth" (paired ESC/POS) · "sunmi" · "rawbt". */
    private fun target(): String = prefsProvider().printerType
    /** Run [action] now if we hold BLUETOOTH_CONNECT, else request it first. */
    private fun withPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !BluetoothPrinter.hasConnectPermission(context)
        ) {
            pending.value = action
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else action()
    }

    fun pairedPrinters(): List<PrinterDevice> = BluetoothPrinter.pairedPrinters(context)

    /** Ask permission (if needed) then run [block] — used to open the picker. */
    fun ensurePermission(block: () -> Unit) = withPermission(block)

    /** Only the Bluetooth path needs BLUETOOTH_CONNECT; Sunmi/RawBT do not. */
    private fun withPrinterReady(action: () -> Unit) =
        if (target() == "bluetooth") withPermission(action) else action()

    fun printReceipt(business: Business, sale: SaleEntity, loadLines: suspend () -> List<SaleLine>) =
        withPrinterReady {
            scope.launch {
                toast("Printing…")
                val r = ReceiptPrinter.print(context, business, sale, loadLines(), style(), target())
                when (r) {
                    is PrintResult.Success -> toast("Printed")
                    is PrintResult.Error -> toast("Print failed: ${r.message}")
                }
            }
        }

    fun printRefund(
        business: Business,
        refund: Refund,
        lines: List<RefundLine>,
        loadPayments: suspend () -> List<RefundPayment>
    ) = withPrinterReady {
        scope.launch {
            toast("Printing…")
            val r = ReceiptPrinter.printRefund(context, business, refund, lines, loadPayments(), style(), target())
            when (r) {
                is PrintResult.Success -> toast("Printed")
                is PrintResult.Error -> toast("Print failed: ${r.message}")
            }
        }
    }

    fun test(business: Business) = withPrinterReady {
        scope.launch {
            when (val r = ReceiptPrinter.testPrint(context, business, target())) {
                is PrintResult.Success -> toast("Test sent to printer")
                is PrintResult.Error -> toast(r.message)
            }
        }
    }

    // ---- PDF export (Phase 6, §5) — no printer/permission needed ----

    fun sharePdfReceipt(business: Business, sale: SaleEntity, loadLines: suspend () -> List<SaleLine>) {
        scope.launch {
            toast("Building PDF…")
            val file = withContext(Dispatchers.IO) { PdfDocs.saleReceipt(context, business, sale, loadLines()) }
            PdfFiles.share(context, file, "Receipt #${sale.receiptNo ?: sale.id.takeLast(6)}")
        }
    }

    fun sharePdfRefund(
        business: Business,
        refund: Refund,
        lines: List<RefundLine>,
        loadPayments: suspend () -> List<RefundPayment>
    ) {
        scope.launch {
            toast("Building PDF…")
            val file = withContext(Dispatchers.IO) {
                PdfDocs.refundReceipt(context, business, refund, lines, loadPayments())
            }
            PdfFiles.share(context, file, "Refund #${refund.saleReceiptNo ?: ""}")
        }
    }

    fun sharePdfStatement(
        business: Business,
        heading: String,
        customerName: String,
        entries: List<PdfDocs.StatementEntry>
    ) {
        scope.launch {
            toast("Building PDF…")
            val file = withContext(Dispatchers.IO) {
                PdfDocs.customerStatement(context, business, heading, customerName, entries)
            }
            PdfFiles.share(context, file, "$heading — $customerName")
        }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

@Composable
private fun rememberPrinterUi(prefsProvider: () -> ShopPrefs): PrinterUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pending.value
        pending.value = null
        if (granted) action?.invoke()
        else Toast.makeText(context, "Bluetooth permission needed to print", Toast.LENGTH_SHORT).show()
    }
    return remember { PrinterUi(context, scope, launcher, pending, prefsProvider) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    vm: PosViewModel,
    onExitToAdmin: (() -> Unit)? = null,
    // Set when launched from a mobile-money notification (§6.2). Jumps to the
    // Mobile Money screen once, then calls [onOpenConsumed] so it doesn't re-fire.
    openMobileMoney: Boolean = false,
    onOpenConsumed: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val business by vm.business.collectAsState()
    val cart by vm.cart.collectAsState()
    var screen by remember { mutableStateOf(Screen.Sell) }
    var drawerOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val printer = rememberPrinterUi { vm.shopPrefs.value }

    LaunchedEffect(openMobileMoney) {
        if (openMobileMoney) { screen = Screen.MobileMoney; onOpenConsumed() }
    }

    val currency = business?.currency ?: "USD"
    val shopName = business?.name ?: "Spot POS"
    val cartCount = cart.sumOf { it.qty }.toInt()
    val mmPending by vm.mmPendingCount.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = t.canvas,
            topBar = {
                MobileTopBar(
                    shopName = shopName,
                    logoUri = business?.logoUri,
                    adminBack = onExitToAdmin,
                    onMenu = { drawerOpen = true }
                )
            },
            bottomBar = {
                MobileBottomNav(
                    current = screen,
                    cartCount = cartCount,
                    moreOpen = moreOpen,
                    onSelect = { screen = it; moreOpen = false },
                    onMore = { moreOpen = !moreOpen },
                    moreBadge = mmPending
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(t.canvasBrush)) {
                if (business == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else when (screen) {
                    Screen.Sell -> SellScreen(vm, business!!, printer)
                    Screen.Dashboard -> DashboardScreen(vm, business!!)
                    Screen.Items -> ItemsScreen(vm, currency)
                    Screen.Customers -> CustomersScreen(vm, currency)
                    Screen.Credit -> ChangeCreditScreen(vm, currency)
                    Screen.Expenses -> ExpensesScreen(vm, currency)
                    Screen.Suppliers -> SuppliersScreen(vm)
                    Screen.Purchases -> PurchaseOrdersScreen(vm, currency)
                    Screen.Reports -> ReportsScreen(vm, business!!)
                    Screen.Receipts -> ReceiptsScreen(vm, business!!, printer)
                    Screen.Refunds -> RefundsScreen(vm, business!!, printer)
                    Screen.MobileMoney -> MobileMoneyScreen(vm, currency)
                    Screen.Sync -> SyncScreen(vm)
                    Screen.Settings -> SettingsScreen(vm, business!!, printer)
                }
            }
        }

        if (moreOpen) {
            MoreSheet(
                current = screen,
                onSelect = { screen = it; moreOpen = false },
                onDismiss = { moreOpen = false }
            )
        }
        if (drawerOpen) {
            SideDrawer(
                shopName = shopName,
                current = screen,
                onSelect = { screen = it; drawerOpen = false },
                onDismiss = { drawerOpen = false },
                onSwitchUser = { drawerOpen = false; vm.switchUser() },
                onSignOut = { drawerOpen = false; vm.signOut() }
            )
        }
    }
}

// ─────────────────── MOBILE MONEY RECONCILIATION (Phase 5, §6) ───────────────────

private enum class MmTab(val label: String) {
    NEEDS("To verify"), UNMATCHED("Unmatched"), DONE("Verified")
}

/**
 * Mobile-money reconciliation (prompt §6). Payments parsed from SMS land here in
 * three buckets: matched-to-a-customer awaiting the cashier's confirmation, unmatched
 * awaiting manual assignment, and a verified history. Verifying a debt payment applies
 * it to the customer's account; a walk-in sale payment is simply acknowledged.
 */
@Composable
private fun MobileMoneyScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val needs by vm.mmNeedsVerification.collectAsState()
    val unmatched by vm.mmUnmatched.collectAsState()
    val verified by vm.mmVerified.collectAsState()
    val customers by vm.customers.collectAsState()
    var tab by remember { mutableStateOf(MmTab.NEEDS) }
    var verifyTarget by remember { mutableStateOf<MobileMoneyReceipt?>(null) }
    var undoTarget by remember { mutableStateOf<MobileMoneyReceipt?>(null) }

    // Ask for SMS + notification permissions on first visit (declaration ≠ grant on
    // 13+). If denied, reconciliation just stays empty — nothing else breaks.
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Whatever the grant outcome, try a backfill: the scanner no-ops without READ_SMS.
        vm.backfillSmsInbox(context.applicationContext)
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECEIVE_SMS)
            if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.READ_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
        // Already granted from a prior visit → pull anything that arrived while closed.
        else vm.backfillSmsInbox(context.applicationContext)
    }

    // Surface the result of an inbox scan (messages caught while the app was closed).
    val backfillCount by vm.smsBackfill.collectAsState()
    LaunchedEffect(backfillCount) {
        if (backfillCount > 0) {
            val n = backfillCount
            Toast.makeText(
                context,
                "Found $n payment${if (n == 1) "" else "s"} from your inbox",
                Toast.LENGTH_LONG
            ).show()
            vm.clearSmsBackfillNote()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mobile money",
                color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.backfillSmsInbox(context.applicationContext) }) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Scan inbox")
            }
        }
        Text(
            "EcoCash & mobile-money payments read from SMS. Match each to a customer and confirm what it paid for.",
            color = t.inkSecondary, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MmTabChip(MmTab.NEEDS, tab, needs.size) { tab = it }
            MmTabChip(MmTab.UNMATCHED, tab, unmatched.size) { tab = it }
            MmTabChip(MmTab.DONE, tab, verified.size) { tab = it }
        }
        Spacer(Modifier.height(10.dp))

        val list = when (tab) {
            MmTab.NEEDS -> needs
            MmTab.UNMATCHED -> unmatched
            MmTab.DONE -> verified
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Sms, contentDescription = null, tint = t.inkTertiary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (tab) {
                            MmTab.NEEDS -> "No payments waiting to be verified."
                            MmTab.UNMATCHED -> "No unmatched payments."
                            MmTab.DONE -> "No verified payments yet."
                        },
                        color = t.inkTertiary, fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { r ->
                    MmReceiptCard(
                        r = r,
                        onVerify = { verifyTarget = r },
                        onLogAsSale = { vm.verifyMobileMoney(r.id, null, "sale") },
                        onIgnore = { vm.ignoreMobileMoney(r.id) },
                        onUndo = { undoTarget = r }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    verifyTarget?.let { r ->
        MmVerifyDialog(
            r = r,
            customers = customers,
            currency = currency,
            onDismiss = { verifyTarget = null },
            onConfirm = { cw, purpose, note ->
                vm.verifyMobileMoney(r.id, cw?.customer, purpose, note)
                verifyTarget = null
            }
        )
    }

    undoTarget?.let { r ->
        val reverses = r.purpose == "debt" && r.appliedCreditTxnId != null
        ConfirmDialog(
            title = "Undo this verification?",
            message = if (reverses)
                "This reverses the ${money(r.amount, r.currency)} payment applied to " +
                    "${r.matchedCustomerName ?: "the customer"}'s account and puts the payment back in the queue to verify again."
            else
                "This puts the ${money(r.amount, r.currency)} payment back in the queue to verify again.",
            confirmLabel = "Undo",
            onConfirm = { vm.unverifyMobileMoney(r.id); undoTarget = null },
            onDismiss = { undoTarget = null }
        )
    }
}

@Composable
private fun MmTabChip(tab: MmTab, current: MmTab, count: Int, onSelect: (MmTab) -> Unit) {
    val label = if (count > 0) "${tab.label} ($count)" else tab.label
    FilterChip(selected = current == tab, onClick = { onSelect(tab) }, label = { Text(label) })
}

@Composable
private fun MmReceiptCard(
    r: MobileMoneyReceipt,
    onVerify: () -> Unit,
    onLogAsSale: () -> Unit,
    onIgnore: () -> Unit,
    onUndo: () -> Unit
) {
    val t = LocalPosTokens.current
    val who = r.matchedCustomerName ?: r.senderName ?: r.senderPhone ?: "Unknown sender"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = t.surface1)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(t.brand.s500.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Sms, contentDescription = null, tint = t.brand.s600, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        money(r.amount, r.currency),
                        color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp
                    )
                    Text(
                        "${r.provider.replaceFirstChar { it.uppercase() }} · $who",
                        color = t.inkSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(relativeAgo(r.receivedAt), color = t.inkTertiary, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Ref ${r.txnCode}", color = t.inkTertiary, fontSize = 11.sp)
            if (r.status == "verified") {
                val what = when (r.purpose) {
                    "debt" -> "Applied to ${r.matchedCustomerName ?: "account"}"
                    "sale" -> "Logged as a walk-in sale"
                    else -> "Verified"
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = t.success, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(what, color = t.success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onUndo) { Text("Undo", color = t.danger) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onVerify,
                        colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand),
                        modifier = Modifier.weight(1f)
                    ) { Text("Verify") }
                    if (r.matchedCustomerId == null) {
                        OutlinedButton(onClick = onLogAsSale) { Text("Log sale") }
                    }
                    TextButton(onClick = onIgnore) { Text("Ignore", color = t.inkTertiary) }
                }
            }
        }
    }
}

/**
 * Verify dialog (§6.3): confirm what the money was for and, for a debt payment,
 * which customer's account it settles. Over-payment on a debt lands as a negative
 * balance the Change & Credit screen surfaces (like any repayment).
 */
@Composable
private fun MmVerifyDialog(
    r: MobileMoneyReceipt,
    customers: List<CustomerWithBalance>,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (CustomerWithBalance?, String, String?) -> Unit
) {
    val t = LocalPosTokens.current
    var purpose by remember { mutableStateOf("debt") }
    var selected by remember {
        mutableStateOf(customers.firstOrNull { it.customer.id == r.matchedCustomerId })
    }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val filtered = remember(query, customers) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) customers
        else customers.filter {
            it.customer.name.lowercase().contains(q) || (it.customer.phone ?: "").contains(q)
        }
    }
    val canConfirm = purpose == "sale" || selected != null

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onConfirm(if (purpose == "debt") selected else selected, purpose, note.ifBlank { null }) },
                enabled = canConfirm
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Verify ${money(r.amount, r.currency)}") },
        text = {
            Column {
                Text(
                    "From ${r.senderName ?: r.senderPhone ?: "unknown"} · Ref ${r.txnCode}",
                    color = t.inkTertiary, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Text("What was this for?", color = t.inkSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = purpose == "debt", onClick = { purpose = "debt" }, label = { Text("Settle debt") })
                    FilterChip(selected = purpose == "sale", onClick = { purpose = "sale" }, label = { Text("Walk-in sale") })
                }
                if (purpose == "debt") {
                    Spacer(Modifier.height(10.dp))
                    Text("Customer", color = t.inkSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search name or number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxWidth().height(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { it.customer.id }) { cw ->
                            val on = selected?.customer?.id == cw.customer.id
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) t.brand.s500.copy(alpha = 0.16f) else t.surface2)
                                    .clickable { selected = cw }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(cw.customer.name, color = t.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    cw.customer.phone?.let { Text(it, color = t.inkTertiary, fontSize = 11.sp) }
                                }
                                if (cw.balance > 0.005) {
                                    Text("owes ${money(cw.balance, currency)}", color = t.warning, fontSize = 11.sp)
                                }
                                if (on) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = t.brand.s600, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ─────────────────────────── ADMIN SHELL (Phase 8) ───────────────────────────

/** Admin-mode destinations. Own bottom nav, distinct from the cashier POS (§9). */
private enum class AdminTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Dashboard),
    Reports("Reports", Icons.Filled.BarChart),
    Inventory("Inventory", Icons.Filled.Inventory2),
    Alerts("Alerts", Icons.Filled.Notifications),
    Manage("Admin", Icons.Filled.AdminPanelSettings),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * The admin shell (prompt §9). Admins land here on login. Every tab but Alerts
 * REUSES the existing cashier composables (Dashboard/Reports/Inventory/Settings)
 * rather than duplicating them — the admin difference is the framing (a distinct
 * nav + the ability to drop into the cashier POS via [onExitToCashier]) plus the
 * new computed Alerts feed. Wiring is to existing data only; the §7 admin backend
 * (cashier CRUD, force-disable methods, persisted notifications) is still to come.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRoot(
    vm: PosViewModel,
    onExitToCashier: () -> Unit,
    openAlerts: Boolean = false,
    onOpenConsumed: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val business by vm.business.collectAsState()
    var tab by remember { mutableStateOf(AdminTab.Dashboard) }
    val printer = rememberPrinterUi { vm.shopPrefs.value }
    val currency = business?.currency ?: "USD"
    val unread by vm.unreadNotifications.collectAsState()

    // Keep the persisted feed fresh whenever the admin is in the shell.
    LaunchedEffect(Unit) { vm.sweepNotifications() }
    // Deep-link from an admin notification → jump to the Alerts tab.
    LaunchedEffect(openAlerts) { if (openAlerts) { tab = AdminTab.Alerts; onOpenConsumed() } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = t.canvas,
            topBar = { AdminTopBar(business?.name ?: "Admin", onExitToCashier) },
            bottomBar = { AdminBottomNav(current = tab, alertsBadge = unread, onSelect = { tab = it }) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(t.canvasBrush)) {
                if (business == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else when (tab) {
                    AdminTab.Dashboard -> DashboardScreen(vm, business!!)
                    AdminTab.Reports -> ReportsScreen(vm, business!!)
                    AdminTab.Inventory -> ItemsScreen(vm, currency)
                    AdminTab.Alerts -> AdminAlertsScreen(vm, currency)
                    AdminTab.Manage -> AdminManageScreen(vm, currency)
                    AdminTab.Settings -> SettingsScreen(vm, business!!, printer)
                }
            }
        }
    }
}

/** Admin top bar: an ADMIN badge + shop name + a switch into the cashier POS. */
@Composable
private fun AdminTopBar(shopName: String, onExitToCashier: () -> Unit) {
    val t = LocalPosTokens.current
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LogoMark(shopName, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    shopName, color = t.inkPrimary, fontWeight = FontWeight.Black,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "ADMIN", color = t.brand.s600, fontSize = 10.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 1.sp
                )
            }
            OutlinedButton(
                onClick = onExitToCashier,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.PointOfSale, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("POS", fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = t.surfaceBorder)
    }
}

/** Admin bottom nav — reuses the cashier [BottomNavItem] look; Alerts shows unread. */
@Composable
private fun AdminBottomNav(current: AdminTab, alertsBadge: Int, onSelect: (AdminTab) -> Unit) {
    val t = LocalPosTokens.current
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        HorizontalDivider(color = t.surfaceBorder)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            AdminTab.values().forEach { tabItem ->
                BottomNavItem(
                    icon = tabItem.icon,
                    label = tabItem.label,
                    active = current == tabItem,
                    badge = if (tabItem == AdminTab.Alerts) alertsBadge else 0,
                    modifier = Modifier.weight(1f)
                ) { onSelect(tabItem) }
            }
        }
    }
}

private val ALERT_CATS = listOf(
    "all" to "All", "inventory" to "Inventory", "sales" to "Sales",
    "payments" to "Payments", "refunds" to "Refunds", "system" to "System"
)

/**
 * Admin Alerts feed (prompt §8), now backed by the PERSISTED `notifications` table
 * (Phase 7). The background [com.portionspot.pos.notify.AdminNotificationWorker]
 * computes + escalates; this screen reads the stored rows, shows unread state, marks
 * them read on tap, and filters by category. A sweep runs when the shell opens so it
 * is current without waiting for the periodic worker.
 */
@Composable
private fun AdminAlertsScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val all by vm.notifications.collectAsState()
    var cat by remember { mutableStateOf("all") }

    val shown = if (cat == "all") all else all.filter { it.category == cat }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Alerts", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Low stock, refunds, payments, aging debts & sync health.", color = t.inkTertiary, fontSize = 11.sp)
            }
            if (all.any { it.readAt == null }) {
                TextButton(onClick = { vm.markAllNotificationsRead() }) { Text("Mark all read") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ALERT_CATS.forEach { (id, label) ->
                FilterChip(selected = cat == id, onClick = { cat = id }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No alerts. Everything looks healthy.", color = t.inkTertiary)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { n ->
                    NotificationCard(n, onClick = { if (n.readAt == null) vm.markNotificationRead(n.id) })
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: com.portionspot.pos.data.AppNotification, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    val tint = when (n.severity) {
        "danger" -> t.danger
        "warn" -> t.warning
        else -> t.accentBlue
    }
    val icon = when (n.category) {
        "inventory" -> Icons.Filled.Inventory2
        "sales" -> Icons.Filled.Payments
        "refunds" -> Icons.Filled.AssignmentReturn
        "payments" -> Icons.Filled.Sms
        else -> Icons.Filled.Sync
    }
    val unread = n.readAt == null
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (unread) t.surface2 else t.surface1)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    n.title, color = t.inkPrimary,
                    fontWeight = if (unread) FontWeight.Black else FontWeight.SemiBold, fontSize = 14.sp
                )
                Text(n.body, color = t.inkSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
                Spacer(Modifier.height(2.dp))
                Text(relativeAgo(n.eventAt), color = t.inkTertiary, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Admin console (prompt §8, Phase 7) — the admin-only tools that don't fit the reused
 * cashier screens: end-of-day / shift summary, debt aging + write-offs, force-disable
 * payment methods, void a wrongful refund, and the audit-log viewer. (Server-enforced
 * cashier account CRUD is deferred with the sync-parity phase.)
 */
@Composable
private fun AdminManageScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val business by vm.business.collectAsState()
    val cashiers by vm.eodCashiers.collectAsState()
    val methods by vm.eodMethods.collectAsState()
    val changeGiven by vm.eodChangeGiven.collectAsState()
    val eodDay by vm.eodDay.collectAsState()
    val aging by vm.debtAging.collectAsState()
    val refunds by vm.refunds.collectAsState()
    val audit by vm.auditLog.collectAsState()

    var writeOffFor by remember { mutableStateOf<DebtAgingRow?>(null) }
    var voidFor by remember { mutableStateOf<String?>(null) }
    var countedCash by remember { mutableStateOf("") }
    var showAddCashier by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    val staff by vm.staff.collectAsState()
    val syncConnection by vm.connection.collectAsState()
    LaunchedEffect(syncConnection) { if (syncConnection != null) vm.refreshStaff() }
    val dayMs = 24L * 60 * 60 * 1000
    val dayFmt = remember { SimpleDateFormat("EEE dd MMM yyyy", Locale.getDefault()) }
    val cashRecorded = (methods.firstOrNull { it.method == "cash" }?.total ?: 0.0) - changeGiven

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(10.dp))
            Text("Admin console", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }

        // ---- Account (switch to a cashier / sign this admin out) ----
        item { AdminSectionHeader("Account") }
        item {
            Text(
                "Hand this device to a cashier: 'Switch user' returns to the account picker where they unlock with their own PIN (or 'Add another account' to sign them in the first time).",
                color = t.inkTertiary, fontSize = 12.sp
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.switchUser() }) {
                    Icon(Icons.Filled.SwitchAccount, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Switch user")
                }
                OutlinedButton(
                    onClick = { vm.signOut() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
                ) { Text("Sign out") }
            }
        }

        // ---- End of day / shift summary ----
        item { AdminSectionHeader("End of day") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setEodDay(eodDay - dayMs) }) { Text("‹ Prev") }
                Text(dayFmt.format(Date(eodDay)), color = t.inkPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                val isToday = eodDay >= todayStartMs()
                TextButton(onClick = { if (!isToday) vm.setEodDay(eodDay + dayMs) }, enabled = !isToday) { Text("Next ›") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                Column(Modifier.padding(14.dp)) {
                    if (cashiers.isEmpty()) {
                        Text("No sales this day.", color = t.inkTertiary, fontSize = 13.sp)
                    } else {
                        cashiers.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(c.cashierName ?: "Unattributed", color = t.inkPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("${c.count} sales", color = t.inkTertiary, fontSize = 12.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(money(c.total, currency), color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = t.surfaceBorder)
                        methods.forEach { m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(m.method.replaceFirstChar { it.uppercase() }, color = t.inkSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(money(m.total, currency), color = t.inkSecondary, fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = t.surfaceBorder)
                        Row(Modifier.fillMaxWidth()) {
                            Text("Expected cash in drawer", color = t.inkPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(money(cashRecorded, currency), color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = countedCash, onValueChange = { countedCash = it },
                            label = { Text("Counted cash") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        countedCash.toDoubleOrNull()?.let { counted ->
                            val variance = counted - cashRecorded
                            Text(
                                "Variance: ${money(variance, currency)}",
                                color = if (kotlin.math.abs(variance) < 0.005) t.success else t.danger,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- Debt aging (30/60/90) ----
        item { AdminSectionHeader("Debt aging") }
        if (aging.isEmpty()) {
            item { Text("No outstanding debts.", color = t.inkTertiary, fontSize = 13.sp) }
        } else {
            items(aging, key = { it.customerId }) { row ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.customerName, color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(money(row.total, currency), color = t.danger, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "0–30: ${money(row.bucket0to30, currency)} · 30–60: ${money(row.bucket30to60, currency)} · " +
                                "60–90: ${money(row.bucket60to90, currency)} · 90+: ${money(row.bucket90plus, currency)}",
                            color = t.inkTertiary, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { writeOffFor = row }) { Text("Write off") }
                    }
                }
            }
        }

        // ---- Force-disable payment methods ----
        item { AdminSectionHeader("Payment methods") }
        item {
            Text("Disabled methods are hidden on all cashier devices (on next sync).", color = t.inkTertiary, fontSize = 11.sp)
        }
        business?.let { biz ->
            val methodFlags = listOf(
                Triple("cash", "Cash", biz.cashEnabled),
                Triple("card", "Card", biz.cardEnabled),
                Triple("bank", "Bank transfer", biz.bankEnabled),
                Triple("ecocash", "EcoCash", biz.ecocashEnabled),
                Triple("innbucks", "InnBucks", biz.innbucksEnabled),
                Triple("onemoney", "OneMoney", biz.onemoneyEnabled),
                Triple("omari", "O'mari", biz.omariEnabled),
                Triple("paynow", "Paynow", biz.paynowEnabled),
            )
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        methodFlags.forEach { (id, label, enabled) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = t.inkPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Switch(checked = enabled, onCheckedChange = { vm.setPaymentMethodEnabled(id, it) })
                            }
                        }
                    }
                }
            }
        }

        // ---- Void a wrongful refund ----
        item { AdminSectionHeader("Void a refund") }
        val recentRefunds = refunds.take(15)
        if (recentRefunds.isEmpty()) {
            item { Text("No refunds recorded.", color = t.inkTertiary, fontSize = 13.sp) }
        } else {
            items(recentRefunds, key = { it.refund.id }) { rw ->
                val r = rw.refund
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${money(r.refundTotal, currency)} · ${r.customerName ?: "Walk-in"}", color = t.inkPrimary, fontSize = 13.sp)
                        Text("#${r.saleReceiptNo ?: r.saleId.takeLast(6)} · ${relativeAgo(r.createdAt)}", color = t.inkTertiary, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { voidFor = r.id },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
                    ) { Text("Void") }
                }
            }
        }

        // ---- Audit log ----
        item { AdminSectionHeader("Audit log") }
        if (audit.isEmpty()) {
            item { Text("No audited actions yet.", color = t.inkTertiary, fontSize = 13.sp) }
        } else {
            items(audit, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(e.summary, color = t.inkPrimary, fontSize = 13.sp)
                        Text(
                            (e.createdByName?.let { "$it · " } ?: "") + relativeAgo(e.createdAt),
                            color = t.inkTertiary, fontSize = 11.sp
                        )
                    }
                    Text(e.action, color = t.inkTertiary, fontSize = 10.sp)
                }
            }
        }
        // ---- Cashiers (staff accounts) ----
        item { AdminSectionHeader("Cashiers") }
        if (syncConnection == null) {
            item {
                Text(
                    "Connect cloud sync to add cashier accounts. Each cashier logs in on their own device and is attributed on every sale.",
                    color = t.inkTertiary, fontSize = 12.sp
                )
            }
        } else {
            if (staff.isEmpty()) {
                item { Text("No staff loaded yet — tap Refresh.", color = t.inkTertiary, fontSize = 13.sp) }
            } else {
                items(staff, key = { it.id }) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.displayName.ifBlank { "(no name)" }, color = t.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                s.role.replaceFirstChar { it.uppercase() } + if (!s.active) " · inactive" else "",
                                color = if (s.active) t.inkTertiary else t.danger, fontSize = 11.sp
                            )
                        }
                        // Admins aren't toggled here; only cashiers are (de)activated.
                        if (s.role != "admin") {
                            Switch(checked = s.active, onCheckedChange = { on -> vm.setCashierActive(s.id, on) })
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAddCashier = true }) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Add cashier")
                    }
                    OutlinedButton(onClick = { vm.refreshStaff() }) { Text("Refresh") }
                }
            }
        }

        // ---- Danger zone ----
        item { AdminSectionHeader("Danger zone") }
        item {
            Text(
                "Clear this device's local data (sales, catalog, customers) and re-pull the shared database fresh. Use this to remove test data before turning on upload in Sync.",
                color = t.inkTertiary, fontSize = 12.sp
            )
        }
        item {
            OutlinedButton(
                onClick = { resetConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
            ) { Text("Reset local data") }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAddCashier) {
        AddCashierDialog(
            onDismiss = { showAddCashier = false },
            onCreate = { email, pass, name, cb -> vm.createCashier(email, pass, name, "cashier", cb) }
        )
    }
    if (resetConfirm) {
        ConfirmDialog(
            title = "Reset this device's data?",
            message = "Deletes local sales, catalog and customers, then re-pulls the shared database. The cloud data is not affected. Do this to clear test data before enabling upload.",
            confirmLabel = "Reset & re-pull",
            onConfirm = { vm.resetLocalData(); resetConfirm = false },
            onDismiss = { resetConfirm = false }
        )
    }

    writeOffFor?.let { row ->
        WriteOffDialog(row, currency, onDismiss = { writeOffFor = null }, onConfirm = { amt ->
            vm.writeOffDebt(row.customerId, amt); writeOffFor = null
        })
    }
    voidFor?.let { id ->
        ConfirmDialog(
            title = "Void this refund?",
            message = "The refund is reversed: restocked goods are drawn back down and any balance owed to the customer is cleared. This is audit-logged.",
            confirmLabel = "Void refund",
            onConfirm = { vm.voidRefund(id); voidFor = null },
            onDismiss = { voidFor = null }
        )
    }
}

@Composable
private fun AdminSectionHeader(title: String) {
    val t = LocalPosTokens.current
    Text(
        title, color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 15.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun WriteOffDialog(
    row: DebtAgingRow,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var amount by remember { mutableStateOf(String.format(Locale.US, "%.2f", row.total)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { amount.toDoubleOrNull()?.let { if (it > 0) onConfirm(it) } },
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0
            ) { Text("Write off") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Write off ${row.customerName}'s debt") },
        text = {
            Column {
                Text("Owes ${money(row.total, currency)}. Writing off records a payment that clears the balance (audit-logged).", fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount") }, singleLine = true)
            }
        }
    )
}

/** Admin dialog to create a cashier login via the create-cashier Edge Function. */
@Composable
private fun AddCashierDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, (com.portionspot.pos.auth.StaffResult) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && email.contains("@") && password.length >= 6
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                onClick = {
                    busy = true; error = null
                    onCreate(email.trim(), password, name.trim()) { res ->
                        busy = false
                        when (res) {
                            is com.portionspot.pos.auth.StaffResult.Ok -> onDismiss()
                            is com.portionspot.pos.auth.StaffResult.Err -> error = res.message
                        }
                    }
                }
            ) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        title = { Text("Add cashier") },
        text = {
            Column {
                Text(
                    "Creates a login. The cashier signs in with it — on their own device, or on this one via 'Add another account' on the lock screen. They're attributed on every sale; only an admin can deactivate them.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password (6+ characters)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    )
}

private fun todayStartMs(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Logo square (PSM mark or shop initials) used in the topbar + drawer. */
@Composable
private fun LogoMark(shopName: String, size: androidx.compose.ui.unit.Dp = 32.dp) {
    val t = LocalPosTokens.current
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(t.brand.s600),
        contentAlignment = Alignment.Center
    ) {
        Text(
            shopName.take(3).uppercase(),
            color = t.inkOnBrand,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.30f).sp,
            letterSpacing = (-0.5).sp
        )
    }
}

/** Mobile topbar: hamburger · logo · shop name · online pill (mirrors web).
 *  [adminBack], when set, shows a shortcut back to the admin shell (an admin who
 *  dropped into the cashier POS to make a sale). */
@Composable
private fun MobileTopBar(
    shopName: String,
    logoUri: String?,
    adminBack: (() -> Unit)? = null,
    onMenu: () -> Unit
) {
    val t = LocalPosTokens.current
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = t.inkSecondary)
            }
            LogoMark(shopName, 30.dp)
            Text(
                shopName,
                color = t.inkPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (adminBack != null) {
                IconButton(onClick = adminBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.AdminPanelSettings,
                        contentDescription = "Back to admin",
                        tint = t.brand.s600
                    )
                }
            }
            Icon(Icons.Filled.Wifi, contentDescription = "Online", tint = t.onlinePill, modifier = Modifier.size(16.dp))
        }
        HorizontalDivider(color = t.surfaceBorder)
    }
}

/** Bottom nav: pinned POS·Sales·Sync·Inventory + More (matches web MobileBottomNav). */
@Composable
private fun MobileBottomNav(
    current: Screen,
    cartCount: Int,
    moreOpen: Boolean,
    onSelect: (Screen) -> Unit,
    onMore: () -> Unit,
    moreBadge: Int = 0
) {
    val t = LocalPosTokens.current
    val overflowActive = current in OVERFLOW_SCREENS
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        HorizontalDivider(color = t.surfaceBorder)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            PINNED_SCREENS.forEach { s ->
                BottomNavItem(
                    icon = screenIcon(s),
                    label = s.short,
                    active = current == s,
                    badge = if (s == Screen.Sell && cartCount > 0) cartCount else 0,
                    modifier = Modifier.weight(1f)
                ) { onSelect(s) }
            }
            BottomNavItem(
                icon = if (moreOpen) Icons.Filled.ExpandLess else Icons.Filled.MoreHoriz,
                label = "More",
                active = overflowActive || moreOpen,
                badge = moreBadge,
                modifier = Modifier.weight(1f),
                onClick = onMore
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    badge: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val t = LocalPosTokens.current
    val color = if (active) t.brand.s500 else t.inkTertiary
    Box(modifier.clickable(onClick = onClick)) {
        if (active) {
            Box(
                Modifier.align(Alignment.TopCenter).width(24.dp).height(3.dp)
                    .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                    .background(t.brand.s500)
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
                if (badge > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
                            .size(15.dp).clip(CircleShape).background(t.warning),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$badge", color = Color(0xFF1A1A1A), fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "More" bottom sheet listing overflow destinations (web MoreSheet). */
@Composable
private fun MoreSheet(current: Screen, onSelect: (Screen) -> Unit, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    Box(
        Modifier.fillMaxSize().background(Color(0x73000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(t.surface1)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clickable(enabled = false) {}
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                    .width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.surface4)
            )
            Text(
                "MORE PAGES",
                color = t.inkTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            OVERFLOW_SCREENS.forEach { s ->
                val active = current == s
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (active) t.brand.s50 else Color.Transparent)
                        .clickable { onSelect(s) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        screenIcon(s),
                        contentDescription = s.label,
                        tint = if (active) t.brand.s500 else t.inkTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        s.label,
                        color = if (active) t.brand.s700 else t.inkPrimary,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (active) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = t.brand.s400, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

/** Dark side drawer (hamburger): full nav list, mirrors web SidebarContent. */
@Composable
private fun SideDrawer(
    shopName: String,
    current: Screen,
    onSelect: (Screen) -> Unit,
    onDismiss: () -> Unit,
    onSwitchUser: () -> Unit,
    onSignOut: () -> Unit
) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onDismiss)
    ) {
        Column(
            Modifier.fillMaxHeight().width(280.dp).background(t.navBg)
                .clickable(enabled = false) {}
        ) {
            // Brand header
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LogoMark(shopName, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(shopName, color = t.navInk, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Point of Sale", color = t.navInk.copy(alpha = 0.6f), fontSize = 10.sp)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = t.navInk.copy(alpha = 0.8f))
                }
            }
            HorizontalDivider(color = t.navInk.copy(alpha = 0.12f))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Screen.entries.forEach { s ->
                    val active = current == s
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (active) t.brand.s600 else Color.Transparent)
                            .clickable { onSelect(s) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            screenIcon(s),
                            contentDescription = s.label,
                            tint = if (active) t.inkOnBrand else t.navInk.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            s.label,
                            color = if (active) t.inkOnBrand else t.navInk,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (active) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = t.inkOnBrand.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                    }
                }
            }
            HorizontalDivider(color = t.navInk.copy(alpha = 0.12f))
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DrawerAction(Icons.Filled.SwitchAccount, "Switch user", onSwitchUser)
                DrawerAction(Icons.AutoMirrored.Filled.Logout, "Sign out", onSignOut)
            }
        }
    }
}

/** A tappable action row in the side drawer footer (switch user / sign out). */
@Composable
private fun DrawerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = label, tint = t.navInk.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        Text(label, color = t.navInk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// ───────────────────────── SELL ─────────────────────────

@Composable
private fun SellScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val prefs by vm.shopPrefs.collectAsState()
    val items by vm.items.collectAsState()
    val cart by vm.cart.collectAsState()
    val customers by vm.customers.collectAsState()
    val lastReceipt by vm.lastReceipt.collectAsState()
    val paynowReady by vm.paynowOnlineReady.collectAsState()
    val parkedSales by vm.parkedSales.collectAsState()
    val parkedCount by vm.parkedCount.collectAsState()
    var showParked by remember { mutableStateOf(false) }

    var search by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("All") }
    var showCart by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf(false) }
    var quoteMode by remember { mutableStateOf(false) }
    var showQuote by remember { mutableStateOf(false) }
    var priceModalItem by remember { mutableStateOf<Item?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val total = cart.sumOf { it.lineTotal }
    val count = cart.sumOf { it.qty }.toInt()

    // Free-text categories from the catalog → chips (first word only, like the web).
    val categories = remember(items) {
        listOf("All") + items.mapNotNull { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }.distinct()
    }
    val q = search.trim().lowercase()
    val filtered = remember(items, q, selectedCat) {
        items.filter { it.isActive && !it.deleted }.filter { item ->
            val matchesSearch = q.isEmpty() ||
                item.name.lowercase().contains(q) ||
                (item.sku?.lowercase()?.contains(q) == true) ||
                (item.barcode?.lowercase()?.contains(q) == true)
            val matchesCat = q.isNotEmpty() || selectedCat == "All" ||
                (item.category?.equals(selectedCat, ignoreCase = true) == true)
            matchesSearch && matchesCat
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Search + category chips (white header, mirrors the web POS) ──
        Column(Modifier.fillMaxWidth().background(t.surface1).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SearchField(value = search, onValue = { search = it }, onClear = { search = "" })
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = { scanning = true }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan to cart")
                }
                if (parkedCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showParked = true }) {
                        Text("Held ($parkedCount)")
                    }
                }
            }
            if (categories.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        CatChip(
                            label = if (cat == "All") "All" else cat.split(" ").first(),
                            active = selectedCat == cat && search.isEmpty()
                        ) { selectedCat = cat; search = "" }
                    }
                }
            }
        }
        HorizontalDivider(color = t.surfaceBorder)

        if (search.isNotEmpty()) {
            Text(
                "${filtered.size} of ${items.size} products",
                color = t.inkTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(t.surface3)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inventory2, null, tint = t.inkTertiary.copy(alpha = 0.35f),
                        modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No products found", color = t.inkTertiary, fontWeight = FontWeight.SemiBold)
                    if (search.isNotEmpty()) {
                        Text("Try a different search term", color = t.inkTertiary.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { item ->
                    val inCart = cart.filter { it.itemId == item.id }.sumOf { it.qty }.toInt()
                    ProductCard(item, currency, inCart) {
                        val hasBox = item.boxSize > 1 && item.boxPrice > 0.0
                        val hasWs = item.wholesalePrice > 0.0 && item.wholesalePrice != item.price
                        if (hasBox || hasWs) priceModalItem = item else vm.addToCart(item, "retail")
                    }
                }
            }
        }

        CartBar(
            count = count,
            total = total,
            currency = currency,
            quoteMode = quoteMode,
            onToggleMode = { quoteMode = it },
            onOpenCart = { if (count > 0) showCart = true },
            onHold = {
                if (count > 0) {
                    vm.parkSale()
                    Toast.makeText(context, "Sale held", Toast.LENGTH_SHORT).show()
                }
            },
            onCharge = { if (count > 0) { if (quoteMode) showQuote = true else showPayment = true } }
        )
    }

    if (showParked) {
        ParkedSalesDialog(
            parked = parkedSales,
            currency = currency,
            onResume = { id -> vm.resumeParked(id); showParked = false },
            onDismiss = { showParked = false }
        )
    }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code ->
                scanning = false
                vm.scanToCart(code) { name ->
                    Toast.makeText(
                        context,
                        if (name != null) "Added $name" else "No item for barcode $code",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { scanning = false }
        )
    }

    priceModalItem?.let { item ->
        PriceModeDialog(
            item = item,
            currency = currency,
            onPick = { mode -> vm.addToCart(item, mode); priceModalItem = null },
            onDismiss = { priceModalItem = null }
        )
    }
    if (showCart) {
        CartDialog(cart, currency, vm, onDismiss = { showCart = false })
    }
    if (showPayment) {
        PaymentDialog(
            business = business,
            subtotal = cart.sumOf { it.lineSubtotal },
            currency = currency,
            secondCode = prefs.secondCurrencyCode,
            secondRate = prefs.secondCurrencyRate,
            customers = customers,
            paynowAvailable = paynowReady,
            onCreateCustomer = { name, onCreated -> vm.createCustomer(name, onCreated = onCreated) },
            onPaynowInitiate = { amount, onResult -> vm.paynowInitiate(amount, onResult) },
            onPaynowPoll = { reference, onResult -> vm.paynowPoll(reference, onResult) },
            onDismiss = { showPayment = false }
        ) { payments, discount, customer, onCredit, changeAsCredit ->
            vm.checkout(payments, discount, customer, onCredit, changeAsCredit)
            showPayment = false
        }
    }
    if (showQuote) {
        QuoteDialog(
            subtotal = cart.sumOf { it.lineSubtotal },
            currency = currency,
            customers = customers,
            validityDays = prefs.defaultQuoteValidityDays,
            onDismiss = { showQuote = false }
        ) { discount, customer ->
            vm.generateQuote(discount, customer)
            showQuote = false
            quoteMode = false
        }
    }
    lastReceipt?.let { receipt ->
        ReceiptDialog(
            sale = receipt.sale,
            lines = receipt.lines,
            business = business,
            currency = currency,
            onPrint = { printer.printReceipt(business, receipt.sale) { receipt.lines } },
            onDismiss = { vm.dismissReceipt() }
        )
    }
}

/**
 * Web-faithful product card: white tile, retail price dominant in brand-600,
 * name, then Box / WS secondary prices, SKU at the foot. A cart-count bubble
 * (top-left) and stock badge (top-right) float over a reserved top band.
 */
@Composable
private fun ProductCard(item: Item, currency: String, inCart: Int, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    val tracked = item.trackStock
    val units = item.stockQty
    val isOut = tracked && units <= 0.0
    val hasBox = item.boxSize > 1 && item.boxPrice > 0.0
    val hasWs = item.wholesalePrice > 0.0

    Box(
        Modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(16.dp))
            .alpha(if (isOut) 0.45f else 1f)
            .clickable(enabled = !isOut, onClick = onClick)
            .padding(12.dp)
    ) {
        Box(Modifier.align(Alignment.TopEnd)) { StockBadge(item) }
        if (inCart > 0) {
            Box(
                Modifier.align(Alignment.TopStart).size(20.dp).clip(CircleShape).background(t.brand.s600),
                contentAlignment = Alignment.Center
            ) {
                Text(inCart.toString(), color = t.inkOnBrand, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp)) // reserved band for bubble + stock badge
            Text(
                money(item.price, currency),
                color = t.brand.s600, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.name,
                color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            if (hasBox) {
                Row {
                    Text("Box ", color = t.inkTertiary, fontSize = 10.sp)
                    Text(money(item.boxPrice, currency), color = t.inkSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (hasWs) {
                Row {
                    Text("WS ", color = t.accentBlue, fontSize = 10.sp)
                    Text(money(item.wholesalePrice, currency), color = t.accentBlue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item.sku?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = t.inkTertiary.copy(alpha = 0.7f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Stock pill: Out (danger), Low<5 (warning + count), else faint count. Untracked → nothing. */
@Composable
private fun StockBadge(item: Item) {
    val t = LocalPosTokens.current
    if (!item.trackStock) return
    val units = item.stockQty
    when {
        units <= 0.0 -> Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(t.danger.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text("Out", color = t.danger, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        units < 5.0 -> Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(t.warning.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) { Text(trimQty(units), color = t.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        else -> Text(trimQty(units), color = t.inkTertiary, fontSize = 9.sp)
    }
}

/** Search/scan field — pill input matching the web `search-input-wrap`. */
@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, onClear: () -> Unit) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.surface3)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = t.inkTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search or scan SKU…", color = t.inkTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = TextStyle(color = t.inkPrimary, fontSize = 13.sp, fontFamily = Poppins),
                cursorBrush = SolidColor(t.brand.s600),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, "Clear search", tint = t.inkTertiary,
                modifier = Modifier.size(16.dp).clickable(onClick = onClear)
            )
        }
    }
}

/** Rounded category chip. Active = brand fill; inactive = soft surface. */
@Composable
private fun CatChip(label: String, active: Boolean, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (active) t.brand.s600 else t.surface3)
            .border(1.dp, if (active) t.brand.s600 else t.surfaceBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (active) t.inkOnBrand else t.inkSecondary,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}

@Composable
private fun CartBar(
    count: Int,
    total: Double,
    currency: String,
    quoteMode: Boolean,
    onToggleMode: (Boolean) -> Unit,
    onOpenCart: () -> Unit,
    onHold: () -> Unit,
    onCharge: () -> Unit
) {
    val t = LocalPosTokens.current
    HorizontalDivider(color = t.surfaceBorder)
    Column(Modifier.fillMaxWidth().background(t.surface1).padding(horizontal = 12.dp, vertical = 10.dp)) {
        // Sale ⇄ Quote mode toggle (§1.2 parity). Quote mode swaps the action for
        // "Generate quote": a priced document with no payment taken.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !quoteMode, onClick = { onToggleMode(false) }, label = { Text("Sale") })
            FilterChip(
                selected = quoteMode, onClick = { onToggleMode(true) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Quote") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = count > 0, onClick = onOpenCart)) {
                Text(
                    if (count == 0) "Cart empty" else "$count item${if (count == 1) "" else "s"} · tap to edit",
                    color = t.inkTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
                Text(money(total, currency), color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            if (!quoteMode) {
                OutlinedButton(onClick = onHold, enabled = count > 0, shape = RoundedCornerShape(12.dp)) {
                    Text("Hold")
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = onCharge,
                enabled = count > 0,
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (quoteMode) "Generate quote" else "Charge", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Quote dialog (§1.2 parity): pick an optional customer + discount, then generate a
 * priced quote (no payment). The quote prints/shares via the same receipt sheet as a
 * sale, headed "QUOTATION" with a valid-until date.
 */
@Composable
private fun QuoteDialog(
    subtotal: Double,
    currency: String,
    customers: List<CustomerWithBalance>,
    validityDays: Int,
    onDismiss: () -> Unit,
    onConfirm: (discount: Double, customer: Customer?) -> Unit
) {
    var customer by remember { mutableStateOf<Customer?>(null) }
    var discountText by remember { mutableStateOf("") }
    val discount = (discountText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
    val total = (subtotal - discount).coerceAtLeast(0.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(discount, customer) }) { Text("Generate quote") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New quote") },
        text = {
            Column {
                CustomerPicker(customers = customers, selected = customer, onSelect = { customer = it })
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = discountText,
                    onValueChange = { discountText = it },
                    label = { Text("Discount ($currency)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Quote total", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(money(total, currency), fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Valid for $validityDays day${if (validityDays == 1) "" else "s"}. No payment is taken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/** Held sales: tap one to load it back into the cart (replacing the current cart). */
@Composable
private fun ParkedSalesDialog(
    parked: List<SaleEntity>,
    currency: String,
    onResume: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalPosTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Held sales") },
        text = {
            if (parked.isEmpty()) {
                Text("No held sales.", color = t.inkTertiary)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(parked, key = { it.id }) { sale ->
                        Card(
                            onClick = { onResume(sale.id) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        sale.customerName?.takeIf { it.isNotBlank() }
                                            ?: "Held #${sale.id.takeLast(6).uppercase()}",
                                        fontWeight = FontWeight.Medium
                                    )
                                    sale.note?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                                    }
                                }
                                Text(money(sale.total, currency), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    )
}

/** Box / Wholesale / Retail picker — mirrors the web price modal. */
@Composable
private fun PriceModeDialog(item: Item, currency: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val hasBox = item.boxSize > 1 && item.boxPrice > 0.0
    val hasWs = item.wholesalePrice > 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = {
            Column {
                Text(item.name, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                item.sku?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = t.inkTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select price type:", color = t.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (hasBox) {
                    PriceOption(
                        title = "Box of ${item.boxSize}",
                        subtitle = "Per unit: ${money(item.boxPrice / item.boxSize, currency)}",
                        price = money(item.boxPrice, currency),
                        accent = t.brand.s600
                    ) { onPick("box") }
                }
                if (hasWs) {
                    PriceOption(
                        title = "Wholesale",
                        subtitle = "Trade price",
                        price = money(item.wholesalePrice, currency),
                        accent = t.accentBlue
                    ) { onPick("wholesale") }
                }
                PriceOption(
                    title = "Retail",
                    subtitle = "Walk-in price",
                    price = money(item.price, currency),
                    accent = t.success
                ) { onPick("retail") }
            }
        }
    )
}

@Composable
private fun PriceOption(title: String, subtitle: String, price: String, accent: Color, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .border(2.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = t.inkPrimary)
            Text(subtitle, color = t.inkTertiary, fontSize = 12.sp)
        }
        Text(price, color = accent, fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

@Composable
private fun CartDialog(cart: List<CartLine>, currency: String, vm: PosViewModel, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    var editingQtyLine by remember { mutableStateOf<CartLine?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { vm.clearCart(); onDismiss() }) { Text("Clear all", color = t.danger) } },
        title = { Text("Cart", fontWeight = FontWeight.Bold, color = t.inkPrimary) },
        text = {
            if (cart.isEmpty()) {
                Text("Cart is empty", color = t.inkTertiary)
            } else {
                LazyColumn {
                    items(cart, key = { it.lineKey }) { line ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, fontWeight = FontWeight.SemiBold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${modeLabel(line.mode)} · ${money(line.unitPrice, currency)}/ea",
                                    color = t.inkTertiary, fontSize = 11.sp
                                )
                            }
                            QtyStepper(
                                qty = line.qty.toInt(),
                                onMinus = { vm.changeQty(line.lineKey, -1.0) },
                                onPlus = { vm.changeQty(line.lineKey, +1.0) },
                                onQtyClick = { editingQtyLine = line }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(money(line.lineTotal, currency), fontWeight = FontWeight.Black, color = t.inkPrimary, fontSize = 13.sp)
                            IconButton(onClick = { vm.removeLine(line.lineKey) }) {
                                Icon(Icons.Filled.Delete, "Remove", tint = t.inkTertiary)
                            }
                        }
                    }
                }
            }
        }
    )

    editingQtyLine?.let { line ->
        SetQtyDialog(
            line = line,
            onDismiss = { editingQtyLine = null },
            onConfirm = { qty -> vm.setQty(line.lineKey, qty); editingQtyLine = null }
        )
    }
}

/** Tap-the-number fast quantity entry for a cart line (type an exact amount). */
@Composable
private fun SetQtyDialog(line: CartLine, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    val t = LocalPosTokens.current
    var text by remember { mutableStateOf(trimQty(line.qty)) }
    val qty = text.replace(',', '.').toDoubleOrNull()
    val valid = qty != null && qty > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(qty ?: 0.0) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Quantity", fontWeight = FontWeight.Bold, color = t.inkPrimary) },
        text = {
            Column {
                Text(line.name, color = t.inkSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

private fun modeLabel(mode: String): String = when (mode) {
    "box" -> "Box"
    "wholesale" -> "WS"
    else -> "Retail"
}

@Composable
private fun QtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit, onQtyClick: (() -> Unit)? = null) {
    val t = LocalPosTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBtn(Icons.Filled.Remove, "Decrease", onMinus)
        Text(
            qty.toString(),
            modifier = Modifier
                .widthIn(min = 34.dp)
                .then(
                    if (onQtyClick != null)
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onQtyClick)
                    else Modifier
                )
                .padding(vertical = 4.dp, horizontal = 4.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Black,
            color = t.inkPrimary
        )
        StepBtn(Icons.Filled.Add, "Increase", onPlus)
    }
}

@Composable
private fun StepBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, tint = t.inkSecondary, modifier = Modifier.size(14.dp))
    }
}

/** Sync page (its own bottom-nav destination, like the web). Wraps the cloud panel. */
@Composable
private fun SyncScreen(vm: PosViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        CloudSyncSection(vm)
    }
}

@Composable
private fun PaymentDialog(
    business: Business,
    subtotal: Double,
    currency: String,
    secondCode: String = "",
    secondRate: Double = 0.0,
    customers: List<CustomerWithBalance>,
    paynowAvailable: Boolean = false,
    onCreateCustomer: (name: String, onCreated: (Customer) -> Unit) -> Unit = { _, _ -> },
    onPaynowInitiate: (amount: Double, onResult: (PaynowInit) -> Unit) -> Unit = { _, _ -> },
    onPaynowPoll: (reference: String, onResult: (PaynowPoll) -> Unit) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onConfirm: (payments: List<Tender>, discount: Double, customer: Customer?, onCredit: Boolean, changeAsCredit: Boolean) -> Unit
) {
    // Tender options the owner switched on in Settings (always at least Cash).
    val methods = remember(business) {
        business.enabledPaymentMethods().ifEmpty { listOf(PaymentMethod.CASH) }
    }
    var discountText by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Customer?>(null) }
    val tenders = remember { mutableStateListOf<Tender>() }
    var method by remember { mutableStateOf(methods.first()) }
    var amountText by remember { mutableStateOf("") }
    var referenceText by remember { mutableStateOf("") }
    var onCredit by remember { mutableStateOf(false) }
    var changeAsCredit by remember { mutableStateOf(false) }

    // Dual-currency: the shop keeps its books in [currency] but may also take tender
    // in a SECOND currency (e.g. ZiG). entryCur2 = the cashier is typing the amount in
    // that second currency; we convert it to base so every coverage/change figure —
    // and every stored Tender.amount — stays in the base currency.
    val cur2On = secondCurrencyActive(secondCode, secondRate)
    var entryCur2 by remember { mutableStateOf(false) }

    // Business-level VAT mirrors PosRepository.checkout(): tax on the discounted base.
    val discount = (discountText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, subtotal)
    val taxableBase = subtotal - discount
    val vat = if (business.vatEnabled) taxableBase * business.vatPercent / 100.0 else 0.0
    val total = taxableBase + vat

    val paid = tenders.sumOf { it.amount }
    val remaining = (total - paid).coerceAtLeast(0.0)
    val overpay = (paid - total).coerceAtLeast(0.0)

    val needsRef = method.capturesReference
    val enteredAmount = amountText.toDoubleOrNull()
    val canAdd = enteredAmount != null && enteredAmount > 0.0 && (!needsRef || referenceText.isNotBlank())

    // Second-currency entry is offered for every method EXCEPT a live Paynow QR (an
    // online USD gateway that auto-fills the base amount). useCur2 = the cashier is
    // actually typing this tender in the second currency right now.
    val canPickCur2 = cur2On && !(method == PaymentMethod.PAYNOW && paynowAvailable)
    val useCur2 = canPickCur2 && entryCur2

    // Fully covered by tenders, or the shortfall goes on the customer's account.
    val fullyPaid = paid + 0.0001 >= total
    val creditValid = onCredit && selected != null
    val valid = fullyPaid || creditValid

    fun addTender() {
        val entered = enteredAmount ?: return
        if (entered <= 0.0) return
        if (useCur2) {
            // Typed in the second currency → store the base equivalent in `amount`
            // (keeps sums/reports in base) and keep the tendered figure + rate too.
            tenders.add(
                Tender(
                    method.code,
                    secondToBase(entered, secondRate),
                    referenceText.trim().ifBlank { null },
                    currency = secondCode,
                    tenderAmount = entered,
                    rate = secondRate
                )
            )
        } else {
            tenders.add(Tender(method.code, entered, referenceText.trim().ifBlank { null }))
        }
        amountText = ""
        referenceText = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onConfirm(
                        tenders.toList(),
                        discount,
                        selected,
                        onCredit && remaining > 0.0,
                        changeAsCredit && overpay > 0.0 && selected != null
                    )
                }
            ) { Text(if (!fullyPaid && creditValid) "Charge to credit" else "Complete sale") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Take payment") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TotalRow("Subtotal", money(subtotal, currency))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = discountText,
                    onValueChange = { discountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Discount (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (discount > 0) {
                    Spacer(Modifier.height(4.dp))
                    TotalRow("Discount", "-${money(discount, currency)}")
                }
                if (business.vatEnabled) {
                    Spacer(Modifier.height(4.dp))
                    TotalRow("VAT (${trimPct(business.vatPercent)}%)", money(vat, currency))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total due", fontWeight = FontWeight.Bold)
                    Text(money(total, currency), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                if (cur2On) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "≈ ${money(baseToSecond(total, secondRate), secondCode)} @ ${trimPct(secondRate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Customer — searchable, with create-on-the-fly from a typed name.
                CustomerSearchField(
                    customers = customers,
                    selected = selected,
                    onSelect = { selected = it },
                    onCreate = { name -> onCreateCustomer(name) { selected = it } }
                )
                Spacer(Modifier.height(12.dp))

                // ── Tenders collected so far ──
                if (tenders.isNotEmpty()) {
                    tenders.forEachIndexed { idx, t ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                PaymentMethod.fromCode(t.method)?.label ?: t.method,
                                modifier = Modifier.weight(1f)
                            )
                            val tc = t.currency
                            val ta = t.tenderAmount
                            if (tc != null && ta != null) {
                                // Taken in the second currency: show what was handed over,
                                // with the base-currency equivalent (the booked value) beneath.
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(money(ta, tc), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "= ${money(t.amount, currency)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(money(t.amount, currency), fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { tenders.removeAt(idx) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove payment")
                            }
                        }
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    TotalRow("Paid", money(paid, currency))
                    if (remaining > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Remaining", fontWeight = FontWeight.SemiBold)
                            Text(
                                money(remaining, currency),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (cur2On) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    "or ${money(baseToSecond(remaining, secondRate), secondCode)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (overpay > 0) {
                        TotalRow("Change", money(overpay, currency))
                        if (cur2On) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    "or ${money(baseToSecond(overpay, secondRate), secondCode)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── Add a tender (hidden once the total is fully covered) ──
                if (remaining > 0 || tenders.isEmpty()) {
                    Text(
                        if (tenders.isEmpty()) "Payment" else "Add another payment",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    if (methods.size > 1) {
                        PaymentMethodPicker(methods, method) { method = it; referenceText = "" }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Pay-into account details for manual methods (bank / mobile money).
                    business.payInstructions(method)?.let { instr ->
                        PayInstructionsCard(instr)
                        Spacer(Modifier.height(8.dp))
                    }
                    // Currency toggle — which currency the cashier is keying this tender in.
                    if (canPickCur2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !entryCur2,
                                onClick = { entryCur2 = false },
                                label = { Text(currency) }
                            )
                            FilterChip(
                                selected = entryCur2,
                                onClick = { entryCur2 = true },
                                label = { Text(secondCode) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = {
                            Text(if (useCur2) "${method.label} amount ($secondCode)" else "${method.label} amount")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // When keying the second currency, echo the base value that gets booked.
                    val baseEquiv = enteredAmount?.let { secondToBase(it, secondRate) }
                    if (useCur2 && baseEquiv != null && baseEquiv > 0.0) {
                        Text(
                            "= ${money(baseEquiv, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // Quick-fill: exact remaining + rounded notes, in the entry currency.
                    val quickBasis = if (useCur2) baseToSecond(remaining, secondRate) else remaining
                    val quickCur = if (useCur2) secondCode else currency
                    val quick = remember(quickBasis) { quickAmounts(quickBasis) }
                    if (quick.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            quick.forEachIndexed { i, amt ->
                                AssistChip(
                                    onClick = { amountText = trimAmount(amt) },
                                    label = {
                                        Text(if (i == 0) "Exact ${money(amt, quickCur)}" else money(amt, quickCur))
                                    }
                                )
                            }
                        }
                    }
                    // Paynow online: generate a QR and watch for payment live.
                    if (method == PaymentMethod.PAYNOW && paynowAvailable) {
                        Spacer(Modifier.height(10.dp))
                        PaynowPanel(
                            amount = if (remaining > 0) remaining else total,
                            currency = currency,
                            onInitiate = onPaynowInitiate,
                            onPoll = onPaynowPoll,
                            onPaid = { ref ->
                                referenceText = ref
                                if (amountText.isBlank()) {
                                    amountText = trimAmount(if (remaining > 0) remaining else total)
                                }
                            }
                        )
                    }
                    if (needsRef) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = referenceText,
                            onValueChange = { referenceText = it },
                            label = { Text("${method.label} reference") },
                            singleLine = true,
                            isError = referenceText.isBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { addTender() },
                        enabled = canAdd,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add payment")
                    }
                }

                // ── Settling the gap (credit) or the overpayment (change owed) ──
                if (selected != null && remaining > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Put ${money(remaining, currency)} on ${selected!!.name}'s account")
                        Switch(checked = onCredit, onCheckedChange = { onCredit = it })
                    }
                }
                if (selected != null && overpay > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Owe ${money(overpay, currency)} change to account")
                        Switch(checked = changeAsCredit, onCheckedChange = { changeAsCredit = it })
                    }
                }
                if (remaining > 0 && selected == null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Add payment to cover the total, or pick a customer to sell on credit.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}

/** Exact-remaining + a couple of rounded notes for the cash quick-fill chips. */
private fun quickAmounts(remaining: Double): List<Double> {
    if (remaining <= 0.0) return emptyList()
    val out = linkedSetOf(remaining)
    for (step in listOf(1.0, 5.0, 10.0, 20.0, 50.0)) {
        val up = kotlin.math.ceil(remaining / step) * step
        if (up > remaining) out.add(up)
        if (out.size >= 4) break
    }
    return out.toList()
}

/** Money entry display: drops a trailing ".0", otherwise two decimals. */
private fun trimAmount(a: Double): String =
    if (a % 1.0 == 0.0) a.toInt().toString() else String.format(java.util.Locale.US, "%.2f", a)

/** Trims a VAT percentage for display: 15.0 -> "15", 14.5 -> "14.5". */
private fun trimPct(p: Double): String =
    if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()

/** At/below this on-hand quantity a tracked item is flagged "low" (Stage A: fixed). */
private const val LOW_STOCK_THRESHOLD = 5.0

/** Drops the trailing ".0" so 12.0 -> "12" but 1.5 stays "1.5". */
private fun trimQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toInt().toString() else q.toString()

/**
 * Paynow online panel: asks the shop's Edge Function to start a payment, shows
 * the returned URL as a scannable QR, and polls every few seconds until Paynow
 * reports it paid. On payment it fills the reference (via [onPaid]) so the
 * cashier can complete the sale. Manual reference entry remains as a fallback.
 */
@Composable
private fun PaynowPanel(
    amount: Double,
    currency: String,
    onInitiate: (amount: Double, onResult: (PaynowInit) -> Unit) -> Unit,
    onPoll: (reference: String, onResult: (PaynowPoll) -> Unit) -> Unit,
    onPaid: (reference: String) -> Unit
) {
    var reference by remember { mutableStateOf<String?>(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val qr = remember(browserUrl) { browserUrl?.let { QrCodes.bitmap(it) } }

    // Poll for status while we have a started payment that isn't paid yet. The
    // effect is cancelled automatically when the panel leaves composition.
    LaunchedEffect(reference, paid) {
        val ref = reference
        if (ref == null || paid) return@LaunchedEffect
        while (!paid) {
            delay(4500)
            onPoll(ref) { poll ->
                when (poll) {
                    is PaynowPoll.Ok -> {
                        statusText = poll.status
                        if (poll.paid) {
                            paid = true
                            onPaid(poll.paynowReference ?: ref)
                        }
                    }
                    is PaynowPoll.Err -> errorText = poll.message
                }
            }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                paid -> {
                    Icon(
                        Icons.Filled.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Payment received", fontWeight = FontWeight.Bold)
                    Text(
                        "Press \"Mark paid\" to finish the sale.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
                reference != null -> {
                    val bmp = qr
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Paynow QR code",
                            modifier = Modifier.size(220.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Scan to pay ${money(amount, currency)}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            paynowStatusLabel(statusText),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                else -> {
                    Text(
                        "Show a Paynow QR the customer can scan with their phone.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !starting,
                        onClick = {
                            starting = true
                            errorText = null
                            onInitiate(amount) { init ->
                                starting = false
                                when (init) {
                                    is PaynowInit.Ok -> {
                                        reference = init.reference
                                        browserUrl = init.browserUrl
                                    }
                                    is PaynowInit.Err -> errorText = init.message
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Payments, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (starting) "Starting…" else "Generate Paynow QR")
                    }
                }
            }
            errorText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Friendly label for a raw Paynow status code. */
private fun paynowStatusLabel(status: String?): String = when (status?.lowercase()) {
    null, "", "created", "sent" -> "Waiting for payment…"
    "paid" -> "Paid"
    "cancelled" -> "Payment cancelled"
    "failed" -> "Payment failed"
    else -> "Waiting for payment…"
}

/** Dropdown to pick which tender the cashier is taking. */
@Composable
private fun PaymentMethodPicker(
    methods: List<PaymentMethod>,
    selected: PaymentMethod,
    onSelect: (PaymentMethod) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Payments, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(selected.label, modifier = Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            methods.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = { onSelect(m); expanded = false }
                )
            }
        }
    }
}

/** Shows the owner's pay-into account details for a manual tender. */
@Composable
private fun PayInstructionsCard(instr: PayInstructions) {
    if (instr.lines.isEmpty()) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(instr.title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            instr.lines.forEach { (label, value) -> TotalRow(label, value) }
        }
    }
}

/** Dropdown to pick a customer for a sale (or Walk-in = none). */
@Composable
private fun CustomerPicker(
    customers: List<CustomerWithBalance>,
    selected: Customer?,
    onSelect: (Customer?) -> Unit
) {
    if (customers.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Person, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(selected?.name ?: "Walk-in customer", modifier = Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Walk-in (no customer)") },
                onClick = { onSelect(null); expanded = false }
            )
            customers.forEach { cb ->
                DropdownMenuItem(
                    text = { Text(cb.customer.name) },
                    onClick = { onSelect(cb.customer); expanded = false }
                )
            }
        }
    }
}

/**
 * Searchable customer field for the checkout. Type to filter the existing
 * customers; tap a match to attach it to the sale. When the typed name has no
 * exact match a "+ Create" row appears, so a brand-new customer can be added
 * on the fly without leaving the payment screen. Once a customer is picked the
 * field collapses to a chip with a clear (×) control.
 */
@Composable
private fun CustomerSearchField(
    customers: List<CustomerWithBalance>,
    selected: Customer?,
    onSelect: (Customer?) -> Unit,
    onCreate: (name: String) -> Unit
) {
    if (selected != null) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Person, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(selected.name, fontWeight = FontWeight.SemiBold)
                if (selected.wholesale) {
                    Text("Wholesale", style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear customer")
            }
        }
        return
    }

    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val matches = remember(query, customers) {
        if (trimmed.isBlank()) emptyList()
        else customers.filter { it.customer.name.contains(trimmed, ignoreCase = true) }.take(6)
    }
    val exact = customers.any { it.customer.name.equals(trimmed, ignoreCase = true) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Customer (optional)") },
            placeholder = { Text("Search or type a new name") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        if (trimmed.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.fillMaxWidth()) {
                    matches.forEach { cb ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(cb.customer); query = "" }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(cb.customer.name, modifier = Modifier.weight(1f))
                            cb.customer.phone?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (!exact) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onCreate(trimmed); query = "" }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Create \"$trimmed\"", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A simple destructive-action confirmation dialog. */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { Text(message) }
    )
}

/**
 * Appearance picker: accent presets + custom hex, background, and side-drawer
 * style. Every change applies live (the whole app re-themes) and is persisted
 * device-locally via [onChange].
 */
@Composable
private fun AppearanceSection(theme: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    Text("Accent", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ACCENT_PRESETS.forEach { preset ->
            val sel = theme.accent == preset.id
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(hexToColor(preset.hex))
                    .border(
                        width = if (sel) 3.dp else 1.dp,
                        color = if (sel) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onChange(theme.copy(accent = preset.id, accentHex = preset.hex)) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))

    // Custom hex
    val isCustom = theme.accent == "custom"
    var hexText by remember(theme.accent, theme.accentHex) {
        mutableStateOf(if (isCustom) theme.accentHex.removePrefix("#") else "")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = hexText,
            onValueChange = { raw ->
                val cleaned = raw.removePrefix("#").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(6)
                hexText = cleaned
                if (cleaned.length == 6) onChange(theme.copy(accent = "custom", accentHex = "#$cleaned"))
            },
            label = { Text("Custom hex") },
            singleLine = true,
            prefix = { Text("#") },
            modifier = Modifier.weight(1f)
        )
        if (hexText.length == 6) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(hexToColor(hexText))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("Background", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BACKGROUND_PRESETS.forEach { bg ->
            FilterChip(
                selected = theme.background == bg.id,
                onClick = { onChange(theme.copy(background = bg.id)) },
                label = { Text(bg.name) }
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    Text("Side drawer", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SIDEBAR_PRESETS.forEach { sb ->
            FilterChip(
                selected = theme.sidebar == sb.id,
                onClick = { onChange(theme.copy(sidebar = sb.id)) },
                label = { Text(sb.name) }
            )
        }
    }
}

/** Tax/price rounding + margin-formula preferences. Saves live via [onChange]. */
@Composable
private fun TaxMarginsSection(prefs: ShopPrefs, onChange: (ShopPrefs) -> Unit) {
    Text("Wholesale price rounding", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ROUNDING_STEPS.forEach { (step, label) ->
            FilterChip(
                selected = prefs.wholesaleRounding == step,
                onClick = { onChange(prefs.copy(wholesaleRounding = step)) },
                label = { Text(label) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Checkout total rounding", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ROUNDING_STEPS.forEach { (step, label) ->
            FilterChip(
                selected = prefs.checkoutRounding == step,
                onClick = { onChange(prefs.copy(checkoutRounding = step)) },
                label = { Text(label) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Margin formula", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = prefs.marginFormula == "markup",
            onClick = { onChange(prefs.copy(marginFormula = "markup")) },
            label = { Text("Markup on cost") }
        )
        FilterChip(
            selected = prefs.marginFormula == "gross",
            onClick = { onChange(prefs.copy(marginFormula = "gross")) },
            label = { Text("Gross of price") }
        )
    }

    Spacer(Modifier.height(8.dp))
    SettingsSwitch("Show stock as boxes + loose units", prefs.autoConvertUnitsToBoxes) {
        onChange(prefs.copy(autoConvertUnitsToBoxes = it))
    }
}

/** Receipt-template look: font size, feed lines, bold name, show/hide sections. */
@Composable
private fun ReceiptTemplateSection(prefs: ShopPrefs, onChange: (ShopPrefs) -> Unit) {
    Text("Font size", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RECEIPT_FONT_SCALES.forEach { (scale, label) ->
            FilterChip(
                selected = prefs.receiptFontScale == scale,
                onClick = { onChange(prefs.copy(receiptFontScale = scale)) },
                label = { Text(label) }
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Feed lines after print: ${prefs.receiptFeedLines}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = prefs.receiptFeedLines.toFloat(),
        onValueChange = { onChange(prefs.copy(receiptFeedLines = it.toInt())) },
        valueRange = 0f..8f,
        steps = 7
    )

    Spacer(Modifier.height(4.dp))
    SettingsSwitch("Bold shop name", prefs.receiptBoldName) { onChange(prefs.copy(receiptBoldName = it)) }
    SettingsSwitch("Show logo", prefs.receiptShowLogo) { onChange(prefs.copy(receiptShowLogo = it)) }
    SettingsSwitch("Show tagline", prefs.receiptShowTagline) { onChange(prefs.copy(receiptShowTagline = it)) }
    SettingsSwitch("Show address", prefs.receiptShowAddress) { onChange(prefs.copy(receiptShowAddress = it)) }
    SettingsSwitch("Show VAT line", prefs.receiptShowVat) { onChange(prefs.copy(receiptShowVat = it)) }
    SettingsSwitch("Show footer", prefs.receiptShowFooter) { onChange(prefs.copy(receiptShowFooter = it)) }
}

@Composable
private fun ReceiptDialog(
    sale: SaleEntity,
    lines: List<SaleLine>,
    business: Business,
    currency: String,
    onPrint: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isQuote = sale.status == "quote"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text(if (isQuote) "Done" else "New sale") } },
        title = { Text(if (isQuote) "Quote ready" else "Sale complete") },
        text = {
            Column {
                Text("${if (isQuote) "Quote" else "Receipt"} #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}")
                sale.customerName?.takeIf { it.isNotBlank() }?.let {
                    Text("Customer: $it", style = MaterialTheme.typography.bodySmall)
                }
                if (isQuote) sale.validUntil?.let {
                    val vu = remember(it) {
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))
                    }
                    Text("Valid until: $vu", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                TotalRow("Subtotal", money(sale.subtotal, currency))
                if (sale.discountTotal > 0) TotalRow("Discount", "-${money(sale.discountTotal, currency)}")
                if (sale.taxTotal > 0) TotalRow("VAT", money(sale.taxTotal, currency))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        when {
                            isQuote -> "Quote total"
                            sale.paymentMethod == "credit" -> "Charged to account"
                            else -> "Total paid"
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Text(money(sale.total, currency), fontWeight = FontWeight.Bold)
                }
                // Payment/change lines are meaningless on a quote — sale only.
                if (!isQuote) {
                    if (sale.paymentMethod != "cash" && sale.paymentMethod != "credit") {
                        TotalRow("Paid via", PaymentMethod.fromCode(sale.paymentMethod)?.label ?: sale.paymentMethod)
                    }
                    sale.paymentRef?.takeIf { it.isNotBlank() }?.let { TotalRow("Reference", it) }
                    sale.changeDue?.takeIf { it > 0 }?.let { TotalRow("Change given", money(it, currency)) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Print, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isQuote) "Print quote" else "Print receipt")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { shareReceipt(context, business, sale, lines, currency) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share via WhatsApp")
                }
            }
        }
    )
}

/** Build a plain-text receipt and hand it to WhatsApp (falling back to the
 *  system chooser if WhatsApp isn't installed). */
private fun shareReceipt(
    context: Context,
    business: Business,
    sale: SaleEntity,
    lines: List<SaleLine>,
    currency: String
) {
    val isQuote = sale.status == "quote"
    val sb = StringBuilder()
    sb.appendLine(business.name)
    business.receiptHeader?.takeIf { it.isNotBlank() }?.let { sb.appendLine(it) }
    sb.appendLine("${if (isQuote) "QUOTATION" else "Receipt"} #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}")
    sale.customerName?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Customer: $it") }
    if (isQuote) sale.validUntil?.let {
        sb.appendLine("Valid until: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it))}")
    }
    sb.appendLine("--------------------------------")
    lines.forEach { line ->
        sb.appendLine("${trimQty(line.qty)} x ${line.name}")
        sb.appendLine("    ${money(line.lineTotal, currency)}")
    }
    sb.appendLine("--------------------------------")
    sb.appendLine("Subtotal: ${money(sale.subtotal, currency)}")
    if (sale.discountTotal > 0) sb.appendLine("Discount: -${money(sale.discountTotal, currency)}")
    if (sale.taxTotal > 0) sb.appendLine("VAT: ${money(sale.taxTotal, currency)}")
    sb.appendLine("TOTAL: ${money(sale.total, currency)}")
    if (!isQuote) {
        if (sale.paymentMethod != "cash" && sale.paymentMethod != "credit") {
            sb.appendLine("Paid via: ${PaymentMethod.fromCode(sale.paymentMethod)?.label ?: sale.paymentMethod}")
        }
        sale.changeDue?.takeIf { it > 0 }?.let { sb.appendLine("Change: ${money(it, currency)}") }
    }
    business.receiptFooter?.takeIf { it.isNotBlank() }?.let { sb.appendLine(); sb.appendLine(it) }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, sb.toString())
    }
    val whatsapp = Intent(send).setPackage("com.whatsapp")
    runCatching { context.startActivity(whatsapp) }.getOrElse {
        context.startActivity(Intent.createChooser(send, "Share receipt"))
    }
}

// ───────────────────────── ITEMS ─────────────────────────

@Composable
private fun ItemsScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val items by vm.items.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var search by remember { mutableStateOf("") }

    val q = search.trim().lowercase()
    val shown = remember(items, q) {
        if (q.isEmpty()) items
        else items.filter { item ->
            item.name.lowercase().contains(q) ||
                (item.sku?.lowercase()?.contains(q) == true) ||
                (item.barcode?.lowercase()?.contains(q) == true) ||
                (item.category?.lowercase()?.contains(q) == true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(t.surface1).padding(horizontal = 12.dp, vertical = 8.dp)) {
                SearchField(value = search, onValue = { search = it }, onClear = { search = "" })
            }
            HorizontalDivider(color = t.surfaceBorder)
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (items.isEmpty()) "No items yet.\nAdd your first product." else "No items match your search.",
                        color = t.inkTertiary, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    items(shown, key = { it.id }) { item ->
                        Card(
                            onClick = { editing = item },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.Medium)
                                    if (item.trackStock) {
                                        val out = item.stockQty <= 0.0
                                        // Per-item reorder level wins; fall back to the global default.
                                        val threshold = if (item.reorderLevel > 0.0) item.reorderLevel else LOW_STOCK_THRESHOLD
                                        val low = !out && item.stockQty <= threshold
                                        val (label, tint) = when {
                                            out -> "Out of stock" to MaterialTheme.colorScheme.error
                                            low -> "Low: ${trimQty(item.stockQty)} ${item.unit} left" to MaterialTheme.colorScheme.error
                                            else -> "In stock: ${trimQty(item.stockQty)} ${item.unit}" to
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(label, color = tint, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text(money(item.price, currency), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add item")
        }
    }

    if (showAdd) {
        ItemDialog(vm = vm, existing = null, onClose = { showAdd = false })
    }
    editing?.let { current ->
        ItemDialog(vm = vm, existing = current, onClose = { editing = null })
    }
}

/**
 * Add or edit a catalogue item. When [existing] is null this creates a new item
 * via [vm].addItem; otherwise it persists edits through [vm].updateItem. Retail,
 * wholesale and box prices are all captured here; stock fields only appear when
 * "Track stock" is on. [onClose] dismisses the dialog after a save or cancel.
 */
@Composable
private fun ItemDialog(
    vm: PosViewModel,
    existing: Item?,
    onClose: () -> Unit
) {
    val prefs by vm.shopPrefs.collectAsState()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var price by remember { mutableStateOf(existing?.price?.let { trimQty(it) } ?: "") }
    var wholesale by remember { mutableStateOf(existing?.wholesalePrice?.takeIf { it > 0 }?.let { trimQty(it) } ?: "") }
    var boxPrice by remember { mutableStateOf(existing?.boxPrice?.takeIf { it > 0 }?.let { trimQty(it) } ?: "") }
    var boxSize by remember { mutableStateOf(existing?.boxSize?.takeIf { it > 1 }?.toString() ?: "") }
    var cost by remember { mutableStateOf(existing?.cost?.let { trimQty(it) } ?: "") }
    var tax by remember { mutableStateOf(existing?.taxRate?.takeIf { it > 0 }?.let { trimPct(it) } ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var sku by remember { mutableStateOf(existing?.sku ?: "") }
    var barcode by remember { mutableStateOf(existing?.barcode ?: "") }
    var scanning by remember { mutableStateOf(false) }
    var unit by remember { mutableStateOf(existing?.unit ?: "pc") }
    var track by remember { mutableStateOf(existing?.trackStock ?: false) }
    var stock by remember {
        mutableStateOf(existing?.let { if (it.trackStock) trimQty(it.stockQty) else "" } ?: "")
    }
    // Box items are stocked as N boxes + loose units (like the web POS); we split the
    // existing total by the item's box size and re-sum to total units on save.
    var stockBoxes by remember {
        mutableStateOf(existing?.takeIf { it.trackStock && it.boxSize > 1 }
            ?.let { (it.stockQty.toInt() / it.boxSize).toString() } ?: "")
    }
    var stockLoose by remember {
        mutableStateOf(existing?.takeIf { it.trackStock && it.boxSize > 1 }
            ?.let { trimQty(it.stockQty % it.boxSize) } ?: "")
    }
    var reorder by remember {
        mutableStateOf(existing?.reorderLevel?.takeIf { it > 0 }?.let { trimQty(it) } ?: "")
    }
    var showHistory by remember { mutableStateOf(false) }

    val priceVal = price.toDoubleOrNull()
    val taxVal = tax.toDoubleOrNull() ?: 0.0
    val costVal = cost.toDoubleOrNull()
    val wholesaleVal = wholesale.toDoubleOrNull() ?: 0.0
    val boxPriceVal = boxPrice.toDoubleOrNull() ?: 0.0
    val boxSizeVal = boxSize.toIntOrNull()?.coerceAtLeast(1) ?: 1
    // Total on-hand units. For a box item it's dynamically summed from boxes + loose;
    // otherwise it's the single unit count.
    val boxesVal = stockBoxes.toIntOrNull() ?: 0
    val looseVal = stockLoose.toDoubleOrNull() ?: 0.0
    val stockVal = if (boxSizeVal > 1) boxesVal * boxSizeVal + looseVal
    else (stock.toDoubleOrNull() ?: 0.0)
    val unitText = unit.trim().ifBlank { "pc" }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && priceVal != null,
                onClick = {
                    if (existing == null) {
                        vm.addItem(
                            name = name, price = priceVal ?: 0.0,
                            wholesalePrice = wholesaleVal, boxPrice = boxPriceVal, boxSize = boxSizeVal,
                            category = category.trim().ifBlank { null }, sku = sku.trim().ifBlank { null },
                            barcode = barcode.trim().ifBlank { null },
                            taxRate = taxVal, trackStock = track, stockQty = stockVal,
                            reorderLevel = reorder.toDoubleOrNull() ?: 0.0,
                            cost = costVal, unit = unitText
                        )
                    } else {
                        vm.updateItem(
                            existing.copy(
                                name = name.trim(),
                                price = priceVal ?: 0.0,
                                wholesalePrice = wholesaleVal,
                                boxPrice = boxPriceVal,
                                boxSize = boxSizeVal,
                                category = category.trim().ifBlank { null },
                                sku = sku.trim().ifBlank { null },
                                barcode = barcode.trim().ifBlank { null },
                                taxRate = taxVal,
                                trackStock = track,
                                stockQty = if (track) stockVal else 0.0,
                                reorderLevel = if (track) (reorder.toDoubleOrNull() ?: 0.0) else 0.0,
                                cost = costVal,
                                unit = unitText
                            )
                        )
                    }
                    onClose()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text(if (existing == null) "New item" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Retail price") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = cost,
                        onValueChange = { cost = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Cost  (opt)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                // Live margin readout. The Settings → Tax "Margin formula" choice
                // picks markup-on-cost vs gross-of-price so the number matches how
                // the shop owner thinks about their margins.
                if (priceVal != null && costVal != null && costVal > 0.0 && priceVal > 0.0) {
                    val profit = priceVal - costVal
                    val pct = if (prefs.marginFormula == "gross") profit / priceVal * 100
                    else profit / costVal * 100
                    val label = if (prefs.marginFormula == "gross") "Gross margin" else "Markup"
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$label: ${trimPct(pct)}%  (${money(profit, vm.business.value?.currency ?: "USD")} profit)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profit >= 0) LocalPosTokens.current.success
                        else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wholesale,
                    onValueChange = { wholesale = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Wholesale price  (opt)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = boxPrice,
                        onValueChange = { boxPrice = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Box price  (opt)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = boxSize,
                        onValueChange = { boxSize = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Units / box") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                // With "auto-convert units to boxes" on (Settings → Tax), the box
                // price is derived from the retail price × units-per-box so the owner
                // only maintains one number. Editing the box field by hand still wins.
                if (prefs.autoConvertUnitsToBoxes && boxSizeVal > 1 && priceVal != null) {
                    LaunchedEffect(priceVal, boxSizeVal, prefs.autoConvertUnitsToBoxes) {
                        boxPrice = trimQty(priceVal * boxSizeVal)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = category, onValueChange = { category = it },
                        label = { Text("Category  (opt)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sku, onValueChange = { sku = it },
                        label = { Text("SKU  (opt)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it.trim() },
                        label = { Text("Barcode  (opt)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { scanning = true }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan barcode")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tax,
                        onValueChange = { tax = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Tax %  (opt)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Unit") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Track stock", Modifier.weight(1f))
                    Switch(checked = track, onCheckedChange = { track = it })
                }
                if (track) {
                    if (boxSizeVal > 1) {
                        // Box item: enter boxes + loose units; total is computed live.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = stockBoxes,
                                onValueChange = { stockBoxes = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Boxes") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = stockLoose,
                                onValueChange = { stockLoose = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text("Loose $unitText") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Total: ${trimQty(stockVal)} $unitText  ($boxesVal box${if (boxesVal == 1) "" else "es"} × $boxSizeVal + $looseVal loose)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = reorder,
                            onValueChange = { reorder = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Reorder at ($unitText)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = stock,
                                onValueChange = { stock = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text(if (existing == null) "Opening stock" else "Stock on hand") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = reorder,
                                onValueChange = { reorder = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                label = { Text("Reorder at") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (existing != null) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { showHistory = true }) {
                            Text("View stock history")
                        }
                    }
                }
            }
        }
    )

    if (showHistory && existing != null) {
        StockHistoryDialog(vm = vm, item = existing, onDismiss = { showHistory = false })
    }

    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code -> barcode = code; scanning = false },
            onDismiss = { scanning = false }
        )
    }
}

/** Per-item stock-movement ledger: sales, restocks, adjustments, resets. */
@Composable
private fun StockHistoryDialog(vm: PosViewModel, item: Item, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val movements by vm.itemMovements(item.id).collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Stock history · ${item.name}") },
        text = {
            if (movements.isEmpty()) {
                Text("No stock movements yet.", color = t.inkTertiary)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(movements, key = { it.id }) { m ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.type.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Medium)
                                Text(
                                    m.note?.takeIf { it.isNotBlank() } ?: dashTime(m.createdAt),
                                    style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                                )
                            }
                            Text(
                                (if (m.delta >= 0) "+" else "") + trimQty(m.delta),
                                fontWeight = FontWeight.Bold,
                                color = if (m.delta >= 0) t.success else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("= ${trimQty(m.balanceAfter)}", color = t.inkTertiary, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = t.surfaceBorder)
                    }
                }
            }
        }
    )
}

// ───────────────────────── CUSTOMERS ─────────────────────────

@Composable
private fun CustomersScreen(vm: PosViewModel, currency: String) {
    val customers by vm.customers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Customer?>(null) }
    var showRecentCalls by remember { mutableStateOf(false) }
    // Non-null => open the Add dialog pre-filled from a contact pick or a recent call.
    var prefill by remember { mutableStateOf<PickedContact?>(null) }

    val totalOutstanding = customers.sumOf { it.balance }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (totalOutstanding > 0) {
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total owed to you", color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            money(totalOutstanding, currency), fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            if (customers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No customers yet.\nAdd one to sell on credit.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    items(customers, key = { it.customer.id }) { cb ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                .clickable { selected = cb.customer }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(cb.customer.name, fontWeight = FontWeight.Medium)
                                        if (cb.customer.wholesale) {
                                            Spacer(Modifier.width(6.dp))
                                            WholesaleBadge()
                                        }
                                    }
                                    cb.customer.phone?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (cb.balance > 0) {
                                    Text(
                                        money(cb.balance, currency), fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text(
                                        "Settled", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { showRecentCalls = true }) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Recent callers")
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add customer")
            }
        }
    }

    if (showAdd || prefill != null) {
        AddCustomerDialog(
            initialName = prefill?.name.orEmpty(),
            initialPhone = prefill?.phone.orEmpty(),
            onDismiss = { showAdd = false; prefill = null }
        ) { name, phone, email, address, note, wholesale ->
            vm.addCustomer(name, phone, email, address, note, wholesale)
            showAdd = false; prefill = null
        }
    }
    if (showRecentCalls) {
        RecentCallersDialog(
            customers = customers,
            currency = currency,
            onDismiss = { showRecentCalls = false },
            onOpenCustomer = { c -> showRecentCalls = false; selected = c },
            onAddFromCall = { call ->
                showRecentCalls = false
                prefill = PickedContact(name = call.cachedName, phone = call.number)
            }
        )
    }
    selected?.let { cust ->
        CustomerDetailDialog(vm, cust, currency, onDismiss = { selected = null })
    }
}

/** Small blue pill marking a trade buyer in the customer list and detail header. */
@Composable
private fun WholesaleBadge() {
    val t = LocalPosTokens.current
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(t.accentBlue.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("Wholesale", color = t.accentBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AddCustomerDialog(
    initialName: String = "",
    initialPhone: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var email by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var wholesale by remember { mutableStateOf(false) }
    // System number picker — fills name + phone from the phone's contacts. Needs no
    // permission (the picker grants a one-shot read on the chosen contact).
    val pickContact = rememberContactPicker { picked ->
        picked.name?.let { name = it }
        picked.phone?.let { phone = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, phone, email, address, note, wholesale) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New customer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = pickContact, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Filled.Contacts, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pick from contacts")
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone  (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email  (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address  (optional)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note  (optional)") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wholesale customer")
                        Text(
                            "Charge trade / box prices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wholesale, onCheckedChange = { wholesale = it })
                }
            }
        }
    )
}

/**
 * "Recent callers" quick-add (prompt §5). Lists distinct recent phone callers so a
 * customer who just rang in an order can be opened or added in one tap. Callers already
 * saved as customers show their name and any outstanding balance ("owes $42") and open
 * that account on tap; unknown callers open the Add-customer dialog pre-filled with the
 * number. Needs READ_CALL_LOG — requested on open, and the whole screen degrades to a
 * permission prompt if denied.
 */
@Composable
private fun RecentCallersDialog(
    customers: List<CustomerWithBalance>,
    currency: String,
    onDismiss: () -> Unit,
    onOpenCustomer: (Customer) -> Unit,
    onAddFromCall: (RecentCall) -> Unit
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(CallLogAccess.hasPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.READ_CALL_LOG) }

    var calls by remember { mutableStateOf<List<RecentCall>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (granted) {
            loading = true
            calls = withContext(Dispatchers.IO) { CallLogAccess.recentCallers(context) }
            loading = false
        }
    }

    // Match callers to saved customers by the last-9-digits key.
    val byKey = remember(customers) {
        customers.mapNotNull { cb -> phoneKey(cb.customer.phone)?.let { it to cb } }.toMap()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Recent callers") },
        text = {
            when {
                !granted -> Column {
                    Text(
                        "Call-log access is needed to show recent callers.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.READ_CALL_LOG) }) {
                        Text("Grant access")
                    }
                }
                loading -> Box(
                    Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                calls.isEmpty() -> Text(
                    "No recent calls found.", color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    calls.forEach { call ->
                        val match = byKey[phoneKey(call.number)]
                        RecentCallerRow(
                            call = call,
                            match = match,
                            currency = currency,
                            onClick = {
                                if (match != null) onOpenCustomer(match.customer)
                                else onAddFromCall(call)
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun RecentCallerRow(
    call: RecentCall,
    match: CustomerWithBalance?,
    currency: String,
    onClick: () -> Unit
) {
    val title = match?.customer?.name ?: call.cachedName ?: call.number
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                "${call.number} • ${callTypeLabel(call.type)} • ${relativeAgo(call.timeMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (match != null && match.balance > 0) {
                Text(
                    "Owes ${money(match.balance, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (match != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Open account")
        } else {
            Icon(Icons.Filled.PersonAdd, contentDescription = "Add customer")
        }
    }
}

private fun callTypeLabel(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "Incoming"
    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
    CallLog.Calls.MISSED_TYPE -> "Missed"
    CallLog.Calls.REJECTED_TYPE -> "Rejected"
    CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
    else -> "Call"
}

/** Human "5 min ago" / "2 hr ago" / "3 d ago" for call timestamps. */
private fun relativeAgo(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    if (diff < 60_000) return "just now"
    val min = diff / 60_000
    return when {
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} hr ago"
        min < 60 * 24 * 7 -> "${min / (60 * 24)} d ago"
        else -> "${min / (60 * 24 * 7)} wk ago"
    }
}

@Composable
private fun CustomerDetailDialog(
    vm: PosViewModel,
    customer: Customer,
    currency: String,
    onDismiss: () -> Unit
) {
    val balance by vm.balanceFlow(customer.id).collectAsState(initial = 0.0)
    val history by vm.creditHistory(customer.id).collectAsState(initial = emptyList())
    val bizForPdf by vm.business.collectAsState()
    val pdfCtx = LocalContext.current
    val pdfScope = rememberCoroutineScope()
    var showPay by remember { mutableStateOf(false) }
    var wholesale by remember { mutableStateOf(customer.wholesale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (balance > 0) {
                Button(onClick = { showPay = true }) { Text("Record payment") }
            }
        },
        title = { Text(customer.name) },
        text = {
            Column {
                customer.phone?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                customer.email?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                customer.address?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Wholesale customer", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Switch(
                        checked = wholesale,
                        onCheckedChange = { wholesale = it; vm.setCustomerWholesale(customer, it) }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Balance owed", fontWeight = FontWeight.Bold)
                    Text(
                        money(balance, currency), fontWeight = FontWeight.Bold,
                        color = if (balance > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val biz = bizForPdf ?: return@TextButton
                        val entries = history.sortedBy { it.createdAt }
                            .filter { it.type == "credit_owed" || it.type == "credit_paid" }
                            .map {
                                PdfDocs.StatementEntry(
                                    date = it.createdAt,
                                    label = if (it.type == "credit_owed") "Charged" else "Payment",
                                    amount = if (it.type == "credit_owed") it.amount else -it.amount
                                )
                            }
                        pdfScope.launch {
                            val file = withContext(Dispatchers.IO) {
                                PdfDocs.customerStatement(pdfCtx, biz, "Debt Statement", customer.name, entries)
                            }
                            PdfFiles.share(pdfCtx, file, "Statement — ${customer.name}")
                        }
                    }
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share debt statement PDF")
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Text("No credit activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        history.forEach { txn ->
                            val owed = txn.type == "credit_owed"
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (owed) "Charged" else "Payment",
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(syncTimeLabel(txn.createdAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    (if (owed) "+" else "-") + money(txn.amount, currency),
                                    color = if (owed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    if (showPay) {
        RecordPaymentDialog(
            maxAmount = balance,
            currency = currency,
            onDismiss = { showPay = false }
        ) { amount, note ->
            vm.recordRepayment(customer.id, amount, note.ifBlank { null })
            showPay = false
        }
    }
}

@Composable
private fun RecordPaymentDialog(
    maxAmount: Double,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double, String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toDoubleOrNull()
    val valid = amount != null && amount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(enabled = valid, onClick = { onConfirm(amount ?: 0.0, note) }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Record payment") },
        text = {
            Column {
                Text("Owed: ${money(maxAmount, currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Amount received") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note  (optional)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ───────────────────────── EXPENSES ─────────────────────────

private val EXPENSE_CATEGORIES = listOf(
    "Rent", "Salaries", "Utilities", "Fuel", "Transport",
    "Maintenance", "Advertising", "Supplies", "Bank Charges", "Other"
)

/** Local yyyy-MM-dd, the format stored in `Expense.date` (sorts chronologically). */
private val EXPENSE_DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun expenseToday(): String = EXPENSE_DATE_FMT.format(Date())

/** Inclusive yyyy-MM-dd cutoff [days] before today, or null for "all time". */
private fun expenseCutoff(days: Int?): String? {
    if (days == null) return null
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, -days)
    return EXPENSE_DATE_FMT.format(c.time)
}

/**
 * Local-only expense tracker — a faithful port of the web Expenses page. Records
 * shop overheads (rent, salaries, fuel…) in a Room table that is never synced
 * (there is no cloud `expenses` table). Preset date windows + a category filter
 * narrow a newest-first list topped by a red running total. Add and Edit share
 * one modal; Delete (a soft tombstone) asks first.
 */
@Composable
private fun ExpensesScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val expenses by vm.expenses.collectAsState()

    // PRESETS mirror the web: Today(0d) · This Week(6d) · This Month(29d) · All.
    val presets = remember {
        listOf("Today" to 0, "This Week" to 6, "This Month" to 29, "All Time" to null)
    }
    var preset by remember { mutableStateOf(1) }        // default: This Week
    var catFilter by remember { mutableStateOf("all") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var deleting by remember { mutableStateOf<Expense?>(null) }

    val cutoff = remember(preset) { expenseCutoff(presets[preset].second) }
    val filtered = remember(expenses, cutoff, catFilter) {
        expenses.filter { e ->
            (cutoff == null || e.date >= cutoff) &&
                (catFilter == "all" || e.category == catFilter)
        }
    }
    val total = filtered.sumOf { it.amount }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Expenses", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Shop overheads & running costs", color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                // Preset date windows.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = preset == i, onClick = { preset = i }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Category filter.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = catFilter == "all", onClick = { catFilter = "all" }, label = { Text("All") })
                    EXPENSE_CATEGORIES.forEach { c ->
                        FilterChip(selected = catFilter == c, onClick = { catFilter = c }, label = { Text(c) })
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Summary bar: count + red running total.
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${filtered.size} expense" + if (filtered.size == 1) "" else "s",
                            color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        Text(money(total, currency), color = t.danger, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Receipt, contentDescription = null,
                            tint = t.inkTertiary, modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No expenses recorded.", color = t.inkTertiary)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp)
                ) {
                    items(filtered, key = { it.id }) { e ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = e }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                        .background(t.danger.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Receipt, contentDescription = null,
                                        tint = t.danger, modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.category, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                    Text(
                                        e.date + (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(money(e.amount, currency), fontWeight = FontWeight.Black, color = t.danger)
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { deleting = e }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = t.inkTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add expense")
        }
    }

    if (adding) {
        ExpenseModal(initial = null, onDismiss = { adding = false }) { cat, amt, date, desc ->
            vm.saveExpense(null, cat, amt, date, desc); adding = false
        }
    }
    editing?.let { e ->
        ExpenseModal(initial = e, onDismiss = { editing = null }) { cat, amt, date, desc ->
            vm.saveExpense(e.id, cat, amt, date, desc); editing = null
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete expense?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteExpense(e.id); deleting = null }) {
                    Text("Delete", color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Add/Edit sheet for one expense: category, amount, date, optional note. */
@Composable
private fun ExpenseModal(
    initial: Expense?,
    onDismiss: () -> Unit,
    onSave: (category: String, amount: Double, date: String, description: String?) -> Unit
) {
    var category by remember { mutableStateOf(initial?.category ?: EXPENSE_CATEGORIES.first()) }
    var amount by remember { mutableStateOf(initial?.amount?.takeIf { it > 0 }?.let { trimQty(it) } ?: "") }
    var date by remember { mutableStateOf(initial?.date ?: expenseToday()) }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var catOpen by remember { mutableStateOf(false) }

    val parsedAmount = amount.replace(',', '.').toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = parsedAmount > 0 && date.isNotBlank(),
                onClick = { onSave(category, parsedAmount, date.trim(), description.trim().ifBlank { null }) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New expense" else "Edit expense") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Category picker (OutlinedButton + dropdown, like the tender picker).
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { catOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(category, modifier = Modifier.weight(1f))
                        Text("Change", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = catOpen, onDismissRequest = { catOpen = false }) {
                        EXPENSE_CATEGORIES.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = { category = c; catOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    label = { Text("Date  (yyyy-mm-dd)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Description  (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ───────────────────────── SUPPLIERS ─────────────────────────

/**
 * Local-only supplier directory — a faithful port of the web Suppliers page.
 * Vendor contacts (name, phone, email, address, notes) live in a Room table that
 * is never synced. Search narrows by name/phone/email; tapping a card edits it,
 * the trash icon tombstones it. Add and Edit share one modal; name is required.
 */
@Composable
private fun SuppliersScreen(vm: PosViewModel) {
    val t = LocalPosTokens.current
    val suppliers by vm.suppliers.collectAsState()

    var search by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Supplier?>(null) }
    var deleting by remember { mutableStateOf<Supplier?>(null) }

    val filtered = remember(suppliers, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) suppliers
        else suppliers.filter {
            it.name.lowercase().contains(q) ||
                (it.phone?.contains(q) == true) ||
                (it.email?.lowercase()?.contains(q) == true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Suppliers", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Vendor contacts & lead times", color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    placeholder = { Text("Search suppliers…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.LocalShipping, contentDescription = null,
                            tint = t.inkTertiary, modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (search.isBlank()) "No suppliers yet." else "No matches.",
                            color = t.inkTertiary
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp)
                ) {
                    items(filtered, key = { it.id }) { s ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { editing = s }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                    listOfNotNull(
                                        s.phone?.takeIf { it.isNotBlank() },
                                        s.email?.takeIf { it.isNotBlank() },
                                        s.address?.takeIf { it.isNotBlank() }
                                    ).forEach { line ->
                                        Text(
                                            line, style = MaterialTheme.typography.bodySmall,
                                            color = t.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    s.notes?.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it, style = MaterialTheme.typography.bodySmall,
                                            color = t.inkTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = { deleting = s }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = t.inkTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add supplier")
        }
    }

    if (adding) {
        SupplierModal(initial = null, onDismiss = { adding = false }) { name, phone, email, address, notes ->
            vm.saveSupplier(null, name, phone, email, address, notes); adding = false
        }
    }
    editing?.let { s ->
        SupplierModal(initial = s, onDismiss = { editing = null }) { name, phone, email, address, notes ->
            vm.saveSupplier(s.id, name, phone, email, address, notes); editing = null
        }
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete supplier?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSupplier(s.id); deleting = null }) {
                    Text("Delete", color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Add/Edit sheet for one supplier: name (required) + phone, email, address, notes. */
@Composable
private fun SupplierModal(
    initial: Supplier?,
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, email: String, address: String, notes: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onSave(name, phone, email, address, notes) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New supplier" else "Edit supplier") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Supplier name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone  (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email  (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address  (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes  (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ───────────────────────── PURCHASE ORDERS ─────────────────────────

private val PO_FILTERS = listOf("all", "draft", "sent", "received", "cancelled")

/** Small status pill (draft=grey, sent=blue, received=green, cancelled=red). */
@Composable
private fun PoStatusBadge(status: String) {
    val t = LocalPosTokens.current
    val color = when (status) {
        "sent" -> t.accentBlue
        "received" -> t.success
        "cancelled" -> t.danger
        else -> t.inkTertiary           // draft
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            status.replaceFirstChar { it.uppercase() },
            color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Local-only purchase-order workbench — a faithful port of the web Purchase
 * Orders page. POs are restock requests to a [Supplier]; their lines snapshot
 * catalog items. Lifecycle is draft → sent → received (or cancelled): receiving
 * a PO adds each line's quantity to the linked item's stock. Status chips (with
 * counts) filter the newest-first list; the FAB opens the create sheet; tapping a
 * card opens its detail sheet, from which a sent PO can be received.
 */
@Composable
private fun PurchaseOrdersScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val pos by vm.purchaseOrders.collectAsState()

    var statusFilter by remember { mutableStateOf("all") }
    var creating by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PurchaseOrderWithLines?>(null) }
    var receiving by remember { mutableStateOf<PurchaseOrderWithLines?>(null) }

    val counts = remember(pos) {
        mapOf(
            "all" to pos.size,
            "draft" to pos.count { it.po.status == "draft" },
            "sent" to pos.count { it.po.status == "sent" },
            "received" to pos.count { it.po.status == "received" },
            "cancelled" to pos.count { it.po.status == "cancelled" }
        )
    }
    val filtered = remember(pos, statusFilter) {
        if (statusFilter == "all") pos else pos.filter { it.po.status == statusFilter }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Purchase Orders", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Restock requests to suppliers", color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PO_FILTERS.forEach { s ->
                        val label = (if (s == "all") "All" else s.replaceFirstChar { it.uppercase() }) +
                            "  (${counts[s] ?: 0})"
                        FilterChip(selected = statusFilter == s, onClick = { statusFilter = s }, label = { Text(label) })
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Assignment, contentDescription = null,
                            tint = t.inkTertiary, modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (statusFilter == "all") "No purchase orders yet." else "No $statusFilter orders.",
                            color = t.inkTertiary
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp)
                ) {
                    items(filtered, key = { it.po.id }) { pwl ->
                        val po = pwl.po
                        val total = pwl.lines.sumOf { it.qty * it.unitCost }
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { detail = pwl }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(t.brand.s50),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.LocalShipping, contentDescription = null,
                                        tint = t.brand.s600, modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(po.ref, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                        Spacer(Modifier.width(8.dp))
                                        PoStatusBadge(po.status)
                                    }
                                    Text(
                                        po.supplierName.ifBlank { "No supplier" },
                                        style = MaterialTheme.typography.bodySmall, color = t.inkSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${pwl.lines.size} item" + (if (pwl.lines.size == 1) "" else "s") +
                                            " · " + dashTime(po.createdAt),
                                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                                    )
                                }
                                Text(money(total, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = t.inkTertiary)
                            }
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New PO")
        }
    }

    if (creating) {
        PoCreateDialog(vm, currency, onDismiss = { creating = false })
    }
    detail?.let { pwl ->
        PoDetailDialog(
            pwl, currency,
            onDismiss = { detail = null },
            onMarkSent = { vm.markPoSent(pwl.po.id); detail = null },
            onCancel = { vm.cancelPo(pwl.po.id); detail = null },
            onReceive = { detail = null; receiving = pwl }
        )
    }
    receiving?.let { pwl ->
        PoReceiveDialog(
            pwl, currency,
            onDismiss = { receiving = null },
            onConfirm = { entered, boxMode ->
                vm.receivePurchaseOrder(pwl.po.id, entered, boxMode); receiving = null
            }
        )
    }
}

/** One editable line in the create sheet: name, unit-cost field, qty stepper, remove. */
@Composable
private fun PoLineRow(
    line: PurchaseOrderLine,
    onQty: (Double) -> Unit,
    onCost: (Double) -> Unit,
    onRemove: () -> Unit
) {
    val t = LocalPosTokens.current
    var costText by remember(line.id) {
        mutableStateOf(if (line.unitCost > 0) trimQty(line.unitCost) else "")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(line.name, fontWeight = FontWeight.SemiBold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it; onCost(it.replace(',', '.').toDoubleOrNull() ?: 0.0) },
                label = { Text("Unit cost") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(onClick = { onQty((line.qty - 1).coerceAtLeast(1.0)) }) {
            Icon(Icons.Filled.Remove, contentDescription = "Less")
        }
        Text(trimQty(line.qty), fontWeight = FontWeight.Bold, color = t.inkPrimary)
        IconButton(onClick = { onQty(line.qty + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = "More")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = t.inkTertiary)
        }
    }
}

/** Create sheet: pick a supplier, search the catalog to add lines, set qty/cost, notes. */
@Composable
private fun PoCreateDialog(vm: PosViewModel, currency: String, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val suppliers by vm.suppliers.collectAsState()
    val catalog by vm.items.collectAsState()

    var supplierId by remember { mutableStateOf<String?>(null) }
    var supplierName by remember { mutableStateOf("") }
    var supplierOpen by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<PurchaseOrderLine>() }

    val matches = remember(catalog, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else catalog.filter {
            it.name.lowercase().contains(q) || (it.sku?.lowercase()?.contains(q) == true)
        }.take(20)
    }
    val orderTotal = lines.sumOf { it.qty * it.unitCost }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                enabled = lines.isNotEmpty(),
                onClick = { vm.createPurchaseOrder(supplierId, supplierName, notes, lines.toList()); onDismiss() }
            ) { Text("Save draft") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New purchase order") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Supplier picker.
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { supplierOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(supplierName.ifBlank { "Select supplier  (optional)" }, modifier = Modifier.weight(1f))
                        Text("Change", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = supplierOpen, onDismissRequest = { supplierOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("— No supplier —") },
                            onClick = { supplierId = null; supplierName = ""; supplierOpen = false }
                        )
                        suppliers.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.name) },
                                onClick = { supplierId = s.id; supplierName = s.name; supplierOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Product search → tap a result to add a line.
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Add product (name / SKU)") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                matches.forEach { item ->
                    val already = lines.any { it.itemId == item.id }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !already) {
                                lines.add(
                                    PurchaseOrderLine(
                                        poId = "", itemId = item.id, name = item.name,
                                        sku = item.sku, qty = 1.0, unitCost = item.cost ?: 0.0
                                    )
                                )
                                search = ""
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = if (already) t.inkTertiary else t.inkPrimary)
                            item.sku?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                            }
                        }
                        Text(money(item.cost ?: 0.0, currency), style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (already) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (already) t.success else t.brand.s600
                        )
                    }
                }

                // Chosen lines.
                if (lines.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Order items", fontWeight = FontWeight.Bold, color = t.inkSecondary)
                    lines.forEachIndexed { idx, line ->
                        PoLineRow(
                            line,
                            onQty = { lines[idx] = line.copy(qty = it) },
                            onCost = { lines[idx] = line.copy(unitCost = it) },
                            onRemove = { lines.removeAt(idx) }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Order total", color = t.inkSecondary, modifier = Modifier.weight(1f))
                        Text(money(orderTotal, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes  (optional)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

/** Read-only detail sheet + lifecycle actions (mark sent / receive / cancel). */
@Composable
private fun PoDetailDialog(
    pwl: PurchaseOrderWithLines,
    currency: String,
    onDismiss: () -> Unit,
    onMarkSent: () -> Unit,
    onCancel: () -> Unit,
    onReceive: () -> Unit
) {
    val t = LocalPosTokens.current
    val po = pwl.po
    val total = pwl.lines.sumOf { it.qty * it.unitCost }
    val open = po.status == "draft" || po.status == "sent"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            when (po.status) {
                "draft" -> Button(onClick = onMarkSent) { Text("Mark as sent") }
                "sent" -> Button(onClick = onReceive) { Text("Receive stock") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (open) TextButton(onClick = onCancel) { Text("Cancel PO", color = t.danger) }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(po.ref, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                PoStatusBadge(po.status)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(po.supplierName.ifBlank { "No supplier" }, color = t.inkSecondary)
                Text(dashTime(po.createdAt), style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                Spacer(Modifier.height(10.dp))
                pwl.lines.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${trimQty(l.qty)} × ${money(l.unitCost, currency)}" +
                                    (l.receivedQty?.let { " · recv ${trimQty(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                            )
                        }
                        Text(money(l.qty * l.unitCost, currency), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Total", fontWeight = FontWeight.Bold, color = t.inkSecondary, modifier = Modifier.weight(1f))
                    Text(money(total, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
                }
                po.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Notes", fontWeight = FontWeight.Bold, color = t.inkSecondary)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                }
            }
        }
    )
}

/** Receive sheet: per-line quantity (defaults to ordered), optional box→unit mode. */
@Composable
private fun PoReceiveDialog(
    pwl: PurchaseOrderWithLines,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (entered: Map<String, Double>, boxMode: Boolean) -> Unit
) {
    val t = LocalPosTokens.current
    var boxMode by remember { mutableStateOf(false) }
    // Plain map (read only at confirm); each row owns its text state below.
    val entered = remember {
        mutableMapOf<String, String>().apply { pwl.lines.forEach { put(it.id, trimQty(it.qty)) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val parsed = entered.mapValues { (_, v) -> v.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                onConfirm(parsed, boxMode)
            }) { Text("Confirm receive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Receive ${pwl.po.ref}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = boxMode, onCheckedChange = { boxMode = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Quantities are in boxes (× pack size)",
                        color = t.inkSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                pwl.lines.forEach { l ->
                    var qtyText by remember(l.id) { mutableStateOf(entered[l.id] ?: trimQty(l.qty)) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("ordered ${trimQty(l.qty)}", style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                        }
                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it; entered[l.id] = it },
                            label = { Text(if (boxMode) "Boxes" else "Units") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(120.dp)
                        )
                    }
                }
            }
        }
    )
}

// ───────────────────────── CHANGE & CREDIT ─────────────────────────

/**
 * Whole-shop credit ledger — a faithful port of the web Change & Credit page,
 * adapted to the Android data model. The web tracks per-transaction `settled`
 * flags and "change owed" rows; here balances are DERIVED (credit_owed −
 * credit_paid) and only those two ledger types exist, so this screen focuses on
 * customer credit: outstanding-credit summary cards, type + search filters, and
 * a flat newest-first ledger. Tapping a customer who still owes opens Record
 * payment (which pays down their whole balance, reusing the Customers flow).
 */
@Composable
private fun ChangeCreditScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val ledger by vm.creditLedger.collectAsState()
    val customers by vm.customers.collectAsState()

    var search by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("all") }      // all | credit_owed | credit_paid
    var payFor by remember { mutableStateOf<String?>(null) }  // customerId being settled

    val totalOwed = customers.sumOf { it.balance.coerceAtLeast(0.0) }
    val activeAccounts = customers.count { it.balance > 0.0 }

    val filtered = remember(ledger, search, typeFilter) {
        val q = search.trim().lowercase()
        ledger.filter { row ->
            (typeFilter == "all" || row.txn.type == typeFilter) &&
                (q.isEmpty() || row.customerName.lowercase().contains(q))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text("Change & Credit", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(
                "$activeAccounts active account" + if (activeAccounts == 1) "" else "s",
                color = t.inkTertiary, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            // Summary cards (two-up, like the web's owe/owed pair).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CreditSummaryCard(
                    Modifier.weight(1f), "Customers owe you",
                    money(totalOwed, currency), "credit outstanding",
                    t.danger, t.surface1, t.inkTertiary
                )
                CreditSummaryCard(
                    Modifier.weight(1f), "Active accounts",
                    activeAccounts.toString(), "with a balance",
                    t.brand.s600, t.surface1, t.inkTertiary
                )
            }
            Spacer(Modifier.height(12.dp))

            // Type filter chips.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("all" to "All", "credit_owed" to "Charged", "credit_paid" to "Payments")
                    .forEach { (key, label) ->
                        FilterChip(
                            selected = typeFilter == key,
                            onClick = { typeFilter = key },
                            label = { Text(label) }
                        )
                    }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Search customer…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Payments, contentDescription = null,
                        tint = t.inkTertiary, modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("No credit activity yet.", color = t.inkTertiary)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.txn.id }) { row ->
                    val owed = row.txn.type == "credit_owed"
                    val stillOwes =
                        (customers.firstOrNull { it.customer.id == row.txn.customerId }?.balance ?: 0.0) > 0.0
                    val canPay = owed && stillOwes
                    val rowMod = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    Card(if (canPay) rowMod.clickable { payFor = row.txn.customerId } else rowMod) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                    .background(
                                        (if (owed) t.danger else t.success).copy(alpha = 0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (owed) Icons.Filled.Add else Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (owed) t.danger else t.success,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.customerName, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                Text(
                                    (if (owed) "Charged" else "Payment") + " · " + dashTime(row.txn.createdAt),
                                    style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                                )
                                row.txn.note?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it, style = MaterialTheme.typography.bodySmall,
                                        color = t.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                (if (owed) "+" else "−") + money(row.txn.amount, currency),
                                fontWeight = FontWeight.Black,
                                color = if (owed) t.danger else t.success
                            )
                        }
                    }
                }
            }
        }
    }

    payFor?.let { cid ->
        val balance by vm.balanceFlow(cid).collectAsState(initial = 0.0)
        RecordPaymentDialog(maxAmount = balance, currency = currency, onDismiss = { payFor = null }) { amount, note ->
            vm.recordRepayment(cid, amount, note.ifBlank { null })
            payFor = null
        }
    }
}

/** Compact KPI card used by the Change & Credit summary row. */
@Composable
private fun CreditSummaryCard(
    modifier: Modifier,
    label: String,
    value: String,
    sub: String,
    accent: Color,
    container: Color,
    subColor: Color
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = accent, letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = accent)
            Text(sub, fontSize = 9.sp, color = subColor)
        }
    }
}

// ───────────────────────── DASHBOARD ─────────────────────────

/**
 * Business overview — a faithful port of the web Dashboard: a revenue hero,
 * KPI tiles, a 7-day bar chart, payment-method + top-product breakdowns,
 * inventory/customer health and recent sales, plus a Z-Report cash-up modal.
 * Read-only: every figure is derived from sales/items/customers already in Room.
 */
@Composable
private fun DashboardScreen(vm: PosViewModel, business: Business) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val range by vm.dashRange.collectAsState()
    val summary by vm.dashSummary.collectAsState()
    val breakdown by vm.dashBreakdown.collectAsState()
    val topProducts by vm.dashTopProducts.collectAsState()
    val grossProfit by vm.dashGrossProfit.collectAsState()
    val costedRevenue by vm.dashCostedRevenue.collectAsState()
    val dailyBars by vm.dashDailyBars.collectAsState()
    val items by vm.items.collectAsState()
    val customers by vm.customers.collectAsState()
    val recent by vm.recentSales.collectAsState()

    var showZ by remember { mutableStateOf(false) }

    // Health figures, computed from the live catalog/ledger (like the web).
    val outStock = items.count { it.trackStock && it.stockQty <= 0.0 }
    val lowStock = items.count {
        it.trackStock && it.stockQty > 0.0 &&
            it.stockQty <= (if (it.reorderLevel > 0.0) it.reorderLevel else LOW_STOCK_THRESHOLD)
    }
    val noCost = items.count { it.cost == null }
    val pendingCredit = customers.sumOf { it.balance.coerceAtLeast(0.0) }
    // Show profit as long as SOME item is costed (profit/margin are computed over the
    // costed lines only). Previously this required EVERY item to have a cost, so one
    // un-costed product hid profit entirely.
    val showProfit = items.any { it.cost != null }
    val avgSale = if (summary.count > 0) summary.gross / summary.count else 0.0
    val marginPct = if (showProfit && costedRevenue > 0) grossProfit / costedRevenue * 100 else 0.0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        // Header: title + Z-Report.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dashboard", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Business overview", color = t.inkTertiary, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showZ = true }) { Text("Z-Report") }
        }
        Spacer(Modifier.height(12.dp))

        // Date-window chips.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReportRange.values().forEach { r ->
                FilterChip(selected = range == r, onClick = { vm.setDashRange(r) }, label = { Text(r.label) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // Revenue hero (brand fill, like the web's accent gradient card).
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.brand.s600)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Total revenue", color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(money(summary.gross, currency), color = t.inkOnBrand, fontWeight = FontWeight.Black, fontSize = 32.sp)
                Text(
                    "${summary.count} sale${if (summary.count == 1) "" else "s"} · ${range.label}",
                    color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // KPI tiles.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashKpiCard("Transactions", summary.count.toString(), Modifier.weight(1f))
            DashKpiCard("Average sale", money(avgSale, currency), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showProfit) {
                DashKpiCard("Gross profit", money(grossProfit, currency), Modifier.weight(1f), valueColor = t.success)
                DashKpiCard("Margin", "${trimPct(marginPct)}%", Modifier.weight(1f))
            } else {
                DashKpiCard("Pending credit", money(pendingCredit, currency), Modifier.weight(1f), valueColor = t.warning)
                DashKpiCard("Discounts", money(summary.discount, currency), Modifier.weight(1f))
            }
        }
        // Guide the owner to complete profit data (the #1 reason profit reads low/blank).
        if (items.isNotEmpty()) {
            val hint = when {
                !showProfit -> "Set a cost price on your items (Inventory) to see profit and margin."
                noCost > 0 -> "$noCost of ${items.size} items have no cost set — profit and margin exclude them."
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(hint, color = t.warning, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 7-day revenue chart.
        DashSectionCard("7-day revenue") {
            val maxV = (dailyBars.maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1.0)
            val chartH = 96.dp
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                dailyBars.forEach { bar ->
                    val frac = (bar.total / maxV).toFloat().coerceIn(0f, 1f)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(chartH), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth(0.62f)
                                    .height((chartH.value * frac).dp.coerceAtLeast(3.dp))
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(if (frac > 0f) t.brand.s500 else t.surface3)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(bar.label, color = t.inkTertiary, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }

        // Payment methods.
        DashSectionCard("Payment methods") {
            if (breakdown.isEmpty()) {
                Text("No sales in this period.", color = t.inkTertiary, fontSize = 12.sp)
            } else {
                val maxTotal = breakdown.maxOf { it.total }.coerceAtLeast(0.01)
                breakdown.forEach { row ->
                    val label = PaymentMethod.fromCode(row.method)?.label
                        ?: if (row.method == "credit") "Credit (unpaid)" else row.method
                    DashBarRow(label, money(row.total, currency), (row.total / maxTotal).toFloat(), t.brand.s500)
                }
            }
        }

        // Top products.
        DashSectionCard("Top products") {
            if (topProducts.isEmpty()) {
                Text("No sales in this period.", color = t.inkTertiary, fontSize = 12.sp)
            } else {
                val maxRev = topProducts.maxOf { it.revenue }.coerceAtLeast(0.01)
                topProducts.forEach { p ->
                    DashBarRow(p.name, money(p.revenue, currency), (p.revenue / maxRev).toFloat(), t.accentBlue, sub = "${trimQty(p.qty)} sold")
                }
            }
        }

        // Inventory + customer health, side by side.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("INVENTORY", color = t.inkTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(6.dp))
                    DashAlertRow("Out of stock", outStock, t.danger)
                    DashAlertRow("Low stock", lowStock, t.warning)
                    DashAlertRow("Need cost", noCost, t.inkTertiary)
                }
            }
            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("CUSTOMERS", color = t.inkTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(6.dp))
                    DashAlertRow("Accounts", customers.size, t.accentBlue)
                    Spacer(Modifier.height(6.dp))
                    Text("Owed to you", color = t.inkSecondary, fontSize = 12.sp)
                    Text(money(pendingCredit, currency), color = t.warning, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Recent sales.
        DashSectionCard("Recent sales") {
            if (recent.isEmpty()) {
                Text("No sales yet.", color = t.inkTertiary, fontSize = 12.sp)
            } else {
                recent.take(6).forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = t.surfaceBorder)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.customerName ?: "Walk-in", color = t.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(dashTime(s.soldAt), color = t.inkTertiary, fontSize = 10.sp)
                        }
                        Text(money(s.total, currency), color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showZ) ZReportDialog(business, recent) { showZ = false }
}

@Composable
private fun DashKpiCard(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    val t = LocalPosTokens.current
    Card(modifier, colors = CardDefaults.cardColors(containerColor = t.surface1)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label.uppercase(), color = t.inkTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = valueColor ?: t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp, maxLines = 1)
        }
    }
}

/** Card with a bold title and arbitrary body, followed by spacing. */
@Composable
private fun DashSectionCard(title: String, content: @Composable () -> Unit) {
    val t = LocalPosTokens.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.surface1)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** A label + value row over a proportional progress track (payment / product bars). */
@Composable
private fun DashBarRow(label: String, value: String, frac: Float, barColor: Color, sub: String? = null) {
    val t = LocalPosTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = t.inkSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(value, color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surface3)) {
            Box(
                Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).background(barColor)
            )
        }
        if (sub != null) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = t.inkTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DashAlertRow(label: String, count: Int, dot: Color) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, color = t.inkSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("$count", color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Z-Report cash-up: reconcile today's cash drawer (opening float + cash sales vs counted). */
@Composable
private fun ZReportDialog(business: Business, recent: List<SaleEntity>, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val startToday = startOfTodayMs()
    val todays = recent.filter { it.soldAt >= startToday && it.status == "completed" }
    val cashSales = todays.filter { it.paymentMethod == "cash" }.sumOf { it.total }
    val totalSales = todays.sumOf { it.total }

    var opening by remember { mutableStateOf("") }
    var counted by remember { mutableStateOf("") }
    val open = opening.toDoubleOrNull() ?: 0.0
    val cnt = counted.toDoubleOrNull()
    val expected = open + cashSales
    val variance = (cnt ?: expected) - expected

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Z-Report · Cash up") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Today, ${todays.size} sale${if (todays.size == 1) "" else "s"}", color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ZRow("Sales today", money(totalSales, currency))
                ZRow("Cash sales", money(cashSales, currency))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    opening, { opening = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Opening float") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                ZRow("Expected in drawer", money(expected, currency))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    counted, { counted = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Counted cash") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (cnt != null) {
                    Spacer(Modifier.height(10.dp))
                    val (lbl, col) = when {
                        kotlin.math.abs(variance) < 0.005 -> "Balanced" to t.success
                        variance > 0 -> "Over by ${money(variance, currency)}" to t.warning
                        else -> "Short by ${money(-variance, currency)}" to t.danger
                    }
                    Text(lbl, color = col, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
    )
}

@Composable
private fun ZRow(label: String, value: String) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = t.inkSecondary, fontSize = 13.sp)
        Text(value, color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Local midnight today, in epoch millis (UI-side mirror of the VM helper). */
private fun startOfTodayMs(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun dashTime(ms: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

// ───────────────────────── REPORTS ─────────────────────────

@Composable
private fun ReportsScreen(vm: PosViewModel, business: Business) {
    val currency = business.currency
    val range by vm.reportRange.collectAsState()
    val summary by vm.reportSummary.collectAsState()
    val breakdown by vm.reportBreakdown.collectAsState()
    val refunds by vm.reportRefunds.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        // Date-window picker.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReportRange.values().forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { vm.setReportRange(r) },
                    label = { Text(r.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Headline: total sales for the window.
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Total sales", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    money(summary.gross, currency),
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "${summary.count} sale${if (summary.count == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // VAT / discounts / averages.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Breakdown", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (business.vatEnabled) {
                    ReportStatRow("Sales excl. VAT", money(summary.net, currency))
                    ReportStatRow(
                        "VAT collected (${trimPct(business.vatPercent)}%)",
                        money(summary.vat, currency)
                    )
                }
                ReportStatRow("Discounts given", money(summary.discount, currency))
                if (refunds > 0.0) {
                    ReportStatRow("Refunds paid", "-${money(refunds, currency)}")
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    ReportStatRow("Net sales", money(summary.gross - refunds, currency))
                }
                if (summary.count > 0) {
                    ReportStatRow("Average sale", money(summary.gross / summary.count, currency))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Money in, grouped by tender.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("By payment method", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (breakdown.isEmpty()) {
                    Text(
                        "No sales in this period.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    breakdown.forEach { row ->
                        val label = PaymentMethod.fromCode(row.method)?.label
                            ?: if (row.method == "credit") "Credit (unpaid)" else row.method
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(
                                    "${row.count} sale${if (row.count == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(money(row.total, currency), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportStatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ───────────────────────── RECEIPTS ─────────────────────────

@Composable
private fun ReceiptsScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val currency = business.currency
    val sales by vm.recentSales.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val refundedBySale by vm.refundedBySale.collectAsState()
    val takings by vm.todayTakings.collectAsState()
    val countToday by vm.todayCount.collectAsState()
    var refundFor by remember { mutableStateOf<SaleEntity?>(null) }
    var showQuotes by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Today", style = MaterialTheme.typography.labelLarge)
                Text(money(takings, currency), fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text("$countToday sale${if (countToday == 1) "" else "s"}")
            }
        }
        // Receipts ⇄ Quotes (§1.2 parity). Quotes are local documents, never a sale.
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showQuotes, onClick = { showQuotes = false }, label = { Text("Receipts") })
            FilterChip(
                selected = showQuotes, onClick = { showQuotes = true },
                label = { Text(if (quotes.isNotEmpty()) "Quotes (${quotes.size})" else "Quotes") }
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (showQuotes) {
            QuotesList(quotes, currency, business, printer, vm)
        } else if (sales.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sales yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(sales, key = { it.id }) { sale ->
                    val refunded = refundedBySale[sale.id] ?: 0.0
                    val fullyRefunded = refunded > 0.0 && refunded >= sale.total - 0.01
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}", fontWeight = FontWeight.Medium)
                                if (refunded > 0.0) {
                                    Spacer(Modifier.width(6.dp))
                                    RefundedBadge(fully = fullyRefunded)
                                }
                            }
                            Text(
                                if (sale.synced) "Synced" else "On device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            money(sale.total, currency),
                            fontWeight = FontWeight.Bold,
                            textDecoration = if (fullyRefunded) TextDecoration.LineThrough else null
                        )
                        IconButton(onClick = {
                            printer.printReceipt(business, sale) { vm.loadLines(sale.id) }
                        }) {
                            Icon(Icons.Filled.Print, contentDescription = "Reprint")
                        }
                        IconButton(onClick = {
                            printer.sharePdfReceipt(business, sale) { vm.loadLines(sale.id) }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                        }
                        IconButton(onClick = { refundFor = sale }) {
                            Icon(Icons.Filled.AssignmentReturn, contentDescription = "Refund")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    refundFor?.let { sale ->
        RefundDialog(vm, business, sale, onDismiss = { refundFor = null })
    }
}

/** Saved quotes (§1.2 parity): reprint or re-share; no refund (a quote isn't a sale). */
@Composable
private fun QuotesList(
    quotes: List<SaleEntity>,
    currency: String,
    business: Business,
    printer: PrinterUi,
    vm: PosViewModel
) {
    if (quotes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No quotes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        items(quotes, key = { it.id }) { q ->
            val expired = q.validUntil != null && q.validUntil < now
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${q.receiptNo ?: q.id.takeLast(6).uppercase()}", fontWeight = FontWeight.Medium)
                    val vu = q.validUntil?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) }
                    Text(
                        when {
                            vu == null -> q.customerName ?: "Quote"
                            expired -> "Expired $vu"
                            else -> "Valid until $vu"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(money(q.total, currency), fontWeight = FontWeight.Bold)
                IconButton(onClick = { printer.printReceipt(business, q) { vm.loadLines(q.id) } }) {
                    Icon(Icons.Filled.Print, contentDescription = "Reprint quote")
                }
                IconButton(onClick = { printer.sharePdfReceipt(business, q) { vm.loadLines(q.id) } }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share quote PDF")
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Small pill marking a receipt that has been refunded. "REFUNDED" when the whole sale
 * value came back, "PART REFUND" for a partial return. Purely a marker — the receipt
 * stays fully tappable (reprint / share / further partial refund).
 */
@Composable
private fun RefundedBadge(fully: Boolean) {
    val bg = if (fully) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (fully) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            if (fully) "REFUNDED" else "PART REFUND",
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ───────────────────────── REFUNDS ─────────────────────────

/** Human age for a timestamp ("3d ago"). Used in the refunds ledger. */
private fun agoText(epoch: Long): String {
    val mins = (System.currentTimeMillis() - epoch) / 60000
    val hrs = mins / 60
    val days = hrs / 24
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        hrs < 24 -> "${hrs}h ago"
        days < 30 -> "${days}d ago"
        else -> "${days / 30}mo ago"
    }
}

/** Whole numbers print without a trailing ".0" (qty steppers, ledger lines). */
private fun fmtQty(n: Double): String =
    if (n == n.toLong().toDouble()) n.toLong().toString() else String.format("%.2f", n)

private fun refundMethodLabel(code: String): String = when (code) {
    "cash" -> "Cash"
    "ecocash" -> "EcoCash"
    "innbucks" -> "InnBucks"
    "onemoney" -> "OneMoney"
    "omari" -> "O'mari"
    "card" -> "Card"
    "bank" -> "Bank"
    "paynow" -> "Paynow"
    "store_credit" -> "Store credit"
    else -> code.replaceFirstChar { it.uppercase() }
}

private val REFUND_METHODS = listOf("cash", "ecocash", "innbucks", "card", "bank", "store_credit")

/**
 * Cashier refund flow (prompt §11): pick returned lines/quantities, toggle restock
 * per line, choose the payout method and how much goes back now. The refund total is
 * proportional to what was paid (discount + VAT carried). Any shortfall is owed to the
 * customer and ages in Change & Credit — walk-ins must be paid in full.
 */
@Composable
private fun RefundDialog(
    vm: PosViewModel,
    business: Business,
    sale: SaleEntity,
    onDismiss: () -> Unit
) {
    val currency = business.currency
    val context = LocalContext.current
    var lines by remember(sale.id) { mutableStateOf<List<SaleLine>?>(null) }
    val returnQty = remember(sale.id) { mutableStateMapOf<String, Double>() }
    val restock = remember(sale.id) { mutableStateMapOf<String, Boolean>() }
    val alreadyReturned = remember(sale.id) { mutableStateMapOf<String, Double>() }
    var reason by remember(sale.id) { mutableStateOf("") }
    var payoutMethod by remember(sale.id) { mutableStateOf("cash") }
    var payoutText by remember(sale.id) { mutableStateOf("") }
    var methodOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(sale.id) {
        val loaded = vm.loadLines(sale.id)
        loaded.forEach { l ->
            restock[l.id] = true
            returnQty[l.id] = 0.0
            alreadyReturned[l.id] = vm.qtyReturnedForLine(l.id)
        }
        lines = loaded
    }

    val ls = lines
    val returnedSubtotal = ls?.sumOf { (returnQty[it.id] ?: 0.0) * it.unitPrice } ?: 0.0
    val refundTotal = computeRefundTotal(returnedSubtotal, sale.subtotal, sale.total).refundTotal
    val payoutNow = (payoutText.toDoubleOrNull() ?: refundTotal).coerceIn(0.0, refundTotal)
    val outstanding = (refundTotal - payoutNow).coerceAtLeast(0.0)
    val canOwe = sale.customerId != null
    val payoutOk = canOwe || outstanding <= 0.005
    val confirmEnabled = refundTotal > 0.0 && payoutOk && !submitting

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Refund #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                if (ls == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        "Choose what's coming back. The refund is proportional to what was paid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ls.forEach { line ->
                        val already = alreadyReturned[line.id] ?: 0.0
                        val maxReturn = (line.qty - already).coerceAtLeast(0.0)
                        val qty = returnQty[line.id] ?: 0.0
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(line.name, fontWeight = FontWeight.Medium)
                            Text(
                                "Sold ${fmtQty(line.qty)} @ ${money(line.unitPrice, currency)}" +
                                    if (already > 0) " · ${fmtQty(already)} already returned" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    enabled = qty > 0.0,
                                    onClick = { returnQty[line.id] = (qty - 1).coerceAtLeast(0.0) }
                                ) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
                                Text(
                                    fmtQty(qty),
                                    Modifier.widthIn(min = 28.dp),
                                    textAlign = TextAlign.Center
                                )
                                IconButton(
                                    enabled = qty < maxReturn,
                                    onClick = { returnQty[line.id] = (qty + 1).coerceAtMost(maxReturn) }
                                ) { Icon(Icons.Filled.Add, contentDescription = "More") }
                                Spacer(Modifier.weight(1f))
                                Text("Restock", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(6.dp))
                                Switch(
                                    checked = restock[line.id] ?: true,
                                    onCheckedChange = { restock[line.id] = it }
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Refund total", fontWeight = FontWeight.Medium)
                        Text(money(refundTotal, currency), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(onClick = { methodOpen = true }) {
                            Text("Refund via: ${refundMethodLabel(payoutMethod)}")
                        }
                        DropdownMenu(expanded = methodOpen, onDismissRequest = { methodOpen = false }) {
                            REFUND_METHODS.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(refundMethodLabel(m)) },
                                    onClick = { payoutMethod = m; methodOpen = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = payoutText,
                        onValueChange = { payoutText = it },
                        label = { Text("Paying back now") },
                        placeholder = { Text(money(refundTotal, currency)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (outstanding > 0.005) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (canOwe)
                                "Owed to ${sale.customerName ?: "customer"}: ${money(outstanding, currency)} — tracked in Change & Credit"
                            else
                                "Walk-in refund must be paid in full (${money(refundTotal, currency)}). Leave the amount blank to pay it all now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (canOwe) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = confirmEnabled,
                onClick = {
                    val src = ls ?: return@Button
                    submitting = true
                    val returns = src.mapNotNull { line ->
                        val q = returnQty[line.id] ?: 0.0
                        if (q <= 0.0) null
                        else RefundLineInput(
                            saleLine = line,
                            qtyReturned = q,
                            restock = restock[line.id] ?: true
                        )
                    }
                    val payout = if (payoutNow > 0.0) Tender(method = payoutMethod, amount = payoutNow) else null
                    vm.createRefund(sale, returns, payout, reason.ifBlank { null }) {
                        Toast.makeText(context, "Refund recorded", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            ) { Text("Refund " + money(refundTotal, currency)) }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Refund history (immutable ledger). Owed refunds get a "Record payout" action. */
@Composable
private fun RefundsScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val currency = business.currency
    val refunds by vm.refunds.collectAsState()
    var payoutFor by remember { mutableStateOf<Refund?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (refunds.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No refunds yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(refunds, key = { it.refund.id }) { rw ->
                    val r = rw.refund
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Refund on #${r.saleReceiptNo ?: r.saleId.takeLast(6).uppercase()}",
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    (r.customerName ?: "Walk-in") + (r.createdByName?.let { " · by $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    agoText(r.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(r.refundTotal, currency), fontWeight = FontWeight.Bold)
                                val owed = r.status == "owed"
                                Text(
                                    if (owed) "Balance owed" else "Settled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (owed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                                )
                            }
                            IconButton(onClick = {
                                printer.printRefund(business, r, rw.lines) { vm.refundPayments(r.id) }
                            }) {
                                Icon(Icons.Filled.Print, contentDescription = "Print refund")
                            }
                            IconButton(onClick = {
                                printer.sharePdfRefund(business, r, rw.lines) { vm.refundPayments(r.id) }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share refund PDF")
                            }
                        }
                        val retLines = rw.lines
                        if (retLines.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                retLines.joinToString(", ") { "${fmtQty(it.qty)}× ${it.name}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (r.status == "owed") {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { payoutFor = r }) { Text("Record payout") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    payoutFor?.let { r ->
        RefundPayoutDialog(vm, business, r, onDismiss = { payoutFor = null })
    }
}

/** Pay off part/all of a refund the shop still owes a customer (prompt §11). */
@Composable
private fun RefundPayoutDialog(
    vm: PosViewModel,
    business: Business,
    refund: Refund,
    onDismiss: () -> Unit
) {
    val currency = business.currency
    val context = LocalContext.current
    var amountText by remember(refund.id) { mutableStateOf("") }
    var method by remember(refund.id) { mutableStateOf("cash") }
    var methodOpen by remember { mutableStateOf(false) }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record refund payout") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Refund on #${refund.saleReceiptNo ?: refund.saleId.takeLast(6).uppercase()} — total ${money(refund.refundTotal, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { methodOpen = true }) {
                        Text("Via: ${refundMethodLabel(method)}")
                    }
                    DropdownMenu(expanded = methodOpen, onDismissRequest = { methodOpen = false }) {
                        REFUND_METHODS.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(refundMethodLabel(m)) },
                                onClick = { method = m; methodOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount handed back") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(enabled = amount > 0.0, onClick = {
                vm.recordRefundPayout(refund.id, Tender(method = method, amount = amount)) {
                    Toast.makeText(context, "Payout recorded", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ───────────────────────── SETTINGS ─────────────────────────

@Composable
private fun SettingsScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val theme by vm.themeChoice.collectAsState()
    val prefs by vm.shopPrefs.collectAsState()
    var showResetStock by remember { mutableStateOf(false) }
    var showWipeSales by remember { mutableStateOf(false) }

    var name by remember(business.id) { mutableStateOf(business.name) }
    var tagline by remember(business.id) { mutableStateOf(business.tagline ?: "") }
    var currency by remember(business.id) { mutableStateOf(business.currency) }
    var phone by remember(business.id) { mutableStateOf(business.phone ?: "") }
    var email by remember(business.id) { mutableStateOf(business.email ?: "") }
    var website by remember(business.id) { mutableStateOf(business.website ?: "") }
    var address by remember(business.id) { mutableStateOf(business.address ?: "") }
    var footer by remember(business.id) { mutableStateOf(business.receiptFooter ?: "") }
    var logoUri by remember(business.id) { mutableStateOf(business.logoUri) }

    // Printer settings
    var printerMac by remember(business.id) { mutableStateOf(business.btPrinterMac) }
    var printerName by remember(business.id) { mutableStateOf(business.btPrinterName) }
    var paperWidth by remember(business.id) { mutableStateOf(business.paperWidth) }
    var largeText by remember(business.id) { mutableStateOf(business.receiptLargeText) }
    var showPicker by remember { mutableStateOf(false) }

    // ---- VAT / ZIMRA ----
    var vatEnabled by remember(business.id) { mutableStateOf(business.vatEnabled) }
    var vatNumber by remember(business.id) { mutableStateOf(business.vatNumber ?: "") }
    var vatPercentText by remember(business.id) {
        mutableStateOf(if (business.vatPercent == 0.0) "" else business.vatPercent.toString())
    }

    // ---- Payment method toggles ----
    var cashEnabled by remember(business.id) { mutableStateOf(business.cashEnabled) }
    var cardEnabled by remember(business.id) { mutableStateOf(business.cardEnabled) }
    var bankEnabled by remember(business.id) { mutableStateOf(business.bankEnabled) }
    var paynowEnabled by remember(business.id) { mutableStateOf(business.paynowEnabled) }
    var ecocashEnabled by remember(business.id) { mutableStateOf(business.ecocashEnabled) }
    var innbucksEnabled by remember(business.id) { mutableStateOf(business.innbucksEnabled) }
    var onemoneyEnabled by remember(business.id) { mutableStateOf(business.onemoneyEnabled) }
    var omariEnabled by remember(business.id) { mutableStateOf(business.omariEnabled) }

    // ---- Bank transfer ----
    var bankName by remember(business.id) { mutableStateOf(business.bankName ?: "") }
    var bankBranch by remember(business.id) { mutableStateOf(business.bankBranch ?: "") }
    var bankAccountName by remember(business.id) { mutableStateOf(business.bankAccountName ?: "") }
    var bankAccountNumber by remember(business.id) { mutableStateOf(business.bankAccountNumber ?: "") }

    // ---- Mobile money ----
    var ecocashAccountName by remember(business.id) { mutableStateOf(business.ecocashAccountName ?: "") }
    var ecocashPhone by remember(business.id) { mutableStateOf(business.ecocashPhone ?: "") }
    var ecocashMerchantCode by remember(business.id) { mutableStateOf(business.ecocashMerchantCode ?: "") }
    var innbucksAccountName by remember(business.id) { mutableStateOf(business.innbucksAccountName ?: "") }
    var innbucksPhone by remember(business.id) { mutableStateOf(business.innbucksPhone ?: "") }
    var onemoneyAccountName by remember(business.id) { mutableStateOf(business.onemoneyAccountName ?: "") }
    var onemoneyPhone by remember(business.id) { mutableStateOf(business.onemoneyPhone ?: "") }
    var omariAccountName by remember(business.id) { mutableStateOf(business.omariAccountName ?: "") }
    var omariPhone by remember(business.id) { mutableStateOf(business.omariPhone ?: "") }

    // ---- Paynow (key is device-local; never synced) ----
    var paynowIntegrationId by remember(business.id) { mutableStateOf(business.paynowIntegrationId ?: "") }
    var paynowIntegrationKey by remember(business.id) { mutableStateOf(business.paynowIntegrationKey ?: "") }

    // The business as currently edited on screen — used for save AND test print
    // so a freshly-picked (unsaved) printer can still be tested.
    fun edited() = business.copy(
        name = name.ifBlank { "My Business" },
        tagline = tagline.ifBlank { null },
        currency = currency.ifBlank { "USD" },
        phone = phone.ifBlank { null },
        email = email.ifBlank { null },
        website = website.ifBlank { null },
        address = address.ifBlank { null },
        receiptFooter = footer.ifBlank { null },
        logoUri = logoUri,
        btPrinterMac = printerMac,
        btPrinterName = printerName,
        paperWidth = paperWidth,
        receiptLargeText = largeText,
        // VAT / ZIMRA
        vatEnabled = vatEnabled,
        vatNumber = vatNumber.ifBlank { null },
        vatPercent = vatPercentText.toDoubleOrNull() ?: 0.0,
        // payment method toggles
        cashEnabled = cashEnabled,
        cardEnabled = cardEnabled,
        bankEnabled = bankEnabled,
        paynowEnabled = paynowEnabled,
        ecocashEnabled = ecocashEnabled,
        innbucksEnabled = innbucksEnabled,
        onemoneyEnabled = onemoneyEnabled,
        omariEnabled = omariEnabled,
        // bank transfer
        bankName = bankName.ifBlank { null },
        bankBranch = bankBranch.ifBlank { null },
        bankAccountName = bankAccountName.ifBlank { null },
        bankAccountNumber = bankAccountNumber.ifBlank { null },
        // mobile money
        ecocashAccountName = ecocashAccountName.ifBlank { null },
        ecocashPhone = ecocashPhone.ifBlank { null },
        ecocashMerchantCode = ecocashMerchantCode.ifBlank { null },
        innbucksAccountName = innbucksAccountName.ifBlank { null },
        innbucksPhone = innbucksPhone.ifBlank { null },
        onemoneyAccountName = onemoneyAccountName.ifBlank { null },
        onemoneyPhone = onemoneyPhone.ifBlank { null },
        omariAccountName = omariAccountName.ifBlank { null },
        omariPhone = omariPhone.ifBlank { null },
        // Paynow
        paynowIntegrationId = paynowIntegrationId.ifBlank { null },
        paynowIntegrationKey = paynowIntegrationKey.ifBlank { null }
    )

    val context = LocalContext.current
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // OpenDocument grants a persistable permission, so the logo survives restarts.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            logoUri = uri.toString()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoPreview(logoUri)
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = { logoPicker.launch(arrayOf("image/*")) }) { Text("Choose logo") }
            }
            Spacer(Modifier.height(16.dp))
            SettingsField("Business name", name) { name = it }
            SettingsField("Tagline", tagline) { tagline = it }
            SettingsField("Currency (USD, ZWG, ZAR…)", currency) { currency = it.uppercase() }
            SettingsField("Phone", phone) { phone = it }
            SettingsField("Email", email) { email = it }
            SettingsField("Website", website) { website = it }
            SettingsField("Address", address) { address = it }
            SettingsField("Receipt footer", footer) { footer = it }

            // ---- Appearance (theme; saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Appearance")
            AppearanceSection(theme = theme, onChange = { vm.saveTheme(it) })

            // ---- VAT / ZIMRA ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("VAT / ZIMRA")
            SettingsSwitch("Charge VAT", vatEnabled) { vatEnabled = it }
            if (vatEnabled) {
                SettingsField("VAT registration number", vatNumber) { vatNumber = it }
                SettingsField("VAT percentage (e.g. 15)", vatPercentText) {
                    vatPercentText = it.filter { ch -> ch.isDigit() || ch == '.' }
                }
            }

            // ---- Payment methods ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Payment methods")
            Text(
                "Choose which methods cashiers can use at checkout. Money goes directly " +
                    "to your own accounts — never through ON-SPOT POS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SettingsSwitch("Cash", cashEnabled) { cashEnabled = it }
            SettingsSwitch("Card / Swipe", cardEnabled) { cardEnabled = it }

            SettingsSwitch("Bank transfer", bankEnabled) { bankEnabled = it }
            if (bankEnabled) {
                SettingsField("Bank name", bankName) { bankName = it }
                SettingsField("Branch", bankBranch) { bankBranch = it }
                SettingsField("Account name", bankAccountName) { bankAccountName = it }
                SettingsField("Account number", bankAccountNumber) { bankAccountNumber = it }
            }

            SettingsSwitch("Paynow (online)", paynowEnabled) { paynowEnabled = it }
            if (paynowEnabled) {
                SettingsField("Paynow Integration ID", paynowIntegrationId) { paynowIntegrationId = it }
                SettingsField("Paynow Integration Key", paynowIntegrationKey) { paynowIntegrationKey = it }
                Text(
                    "The Integration Key is kept on this device only — it is never uploaded " +
                        "to the cloud or shared with the ON-SPOT POS platform.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsSwitch("EcoCash", ecocashEnabled) { ecocashEnabled = it }
            if (ecocashEnabled) {
                SettingsField("EcoCash account name", ecocashAccountName) { ecocashAccountName = it }
                SettingsField("EcoCash phone", ecocashPhone) { ecocashPhone = it }
                SettingsField("Merchant code (optional)", ecocashMerchantCode) { ecocashMerchantCode = it }
            }

            SettingsSwitch("InnBucks", innbucksEnabled) { innbucksEnabled = it }
            if (innbucksEnabled) {
                SettingsField("InnBucks account name", innbucksAccountName) { innbucksAccountName = it }
                SettingsField("InnBucks phone", innbucksPhone) { innbucksPhone = it }
            }

            SettingsSwitch("OneMoney", onemoneyEnabled) { onemoneyEnabled = it }
            if (onemoneyEnabled) {
                SettingsField("OneMoney account name", onemoneyAccountName) { onemoneyAccountName = it }
                SettingsField("OneMoney phone", onemoneyPhone) { onemoneyPhone = it }
            }

            SettingsSwitch("Omari", omariEnabled) { omariEnabled = it }
            if (omariEnabled) {
                SettingsField("Omari account name", omariAccountName) { omariAccountName = it }
                SettingsField("Omari phone", omariPhone) { omariPhone = it }
            }

            // ---- Tax & price rounding + Margins (saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Tax & margins")
            TaxMarginsSection(prefs = prefs, onChange = { vm.savePrefs(it) })

            // ---- Quotes (saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Quotes")
            SettingsField("Quote validity (days)", prefs.defaultQuoteValidityDays.toString()) {
                it.toIntOrNull()?.coerceIn(0, 365)?.let { d ->
                    vm.savePrefs(prefs.copy(defaultQuoteValidityDays = d))
                }
            }

            // ---- Second currency (dual-currency tender, saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Second currency")
            Text(
                "Keep your books in ${currency.ifBlank { "USD" }} but also accept a second " +
                    "currency at the till (handy for USD + ZiG). Leave the code blank or the " +
                    "rate at 0 to switch it off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsField("Second currency code (e.g. ZWG)", prefs.secondCurrencyCode) {
                vm.savePrefs(prefs.copy(secondCurrencyCode = it.uppercase().trim()))
            }
            var rateText by remember(business.id) {
                mutableStateOf(if (prefs.secondCurrencyRate > 0.0) trimPct(prefs.secondCurrencyRate) else "")
            }
            // Re-seed from prefs when it loads/changes externally, but never while the
            // user is mid-edit (only when the buffer no longer matches the stored value).
            LaunchedEffect(prefs.secondCurrencyRate) {
                if ((rateText.toDoubleOrNull() ?: 0.0) != prefs.secondCurrencyRate) {
                    rateText = if (prefs.secondCurrencyRate > 0.0) trimPct(prefs.secondCurrencyRate) else ""
                }
            }
            OutlinedTextField(
                value = rateText,
                onValueChange = {
                    rateText = it.filter { ch -> ch.isDigit() || ch == '.' }
                    vm.savePrefs(prefs.copy(secondCurrencyRate = rateText.toDoubleOrNull() ?: 0.0))
                },
                label = {
                    Text("Rate — ${prefs.secondCurrencyCode.ifBlank { "second currency" }} per 1 ${currency.ifBlank { "USD" }}")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
            if (secondCurrencyActive(prefs.secondCurrencyCode, prefs.secondCurrencyRate)) {
                Text(
                    "Example: ${money(1.0, currency.ifBlank { "USD" })} = " +
                        money(baseToSecond(1.0, prefs.secondCurrencyRate), prefs.secondCurrencyCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ---- Printer ----
            Spacer(Modifier.height(20.dp))
            Text("Receipt printer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Printer type", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRINTER_TYPES.forEach { (id, label) ->
                    FilterChip(
                        selected = prefs.printerType == id,
                        onClick = { vm.savePrefs(prefs.copy(printerType = id)) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (prefs.printerType) {
                "bluetooth" -> {
                    Text(
                        printerName ?: "No printer selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { printer.ensurePermission { showPicker = true } }) {
                            Text("Select Bluetooth printer")
                        }
                        if (printerMac != null) {
                            OutlinedButton(onClick = { printer.test(edited()) }) {
                                Icon(Icons.Filled.Print, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Test")
                            }
                        }
                    }
                }
                "rawbt" -> {
                    Text(
                        "Prints through the RawBT app (install it separately). RawBT can drive Bluetooth, USB and network printers it supports.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { printer.test(edited()) }) {
                        Icon(Icons.Filled.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test print via RawBT")
                    }
                }
                else -> {
                    Text(
                        "Prints to the built-in printer on a Sunmi handheld device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { printer.test(edited()) }) {
                        Icon(Icons.Filled.Print, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Test print")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Paper width", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = paperWidth == "58mm",
                    onClick = { paperWidth = "58mm" },
                    label = { Text("58 mm") }
                )
                FilterChip(
                    selected = paperWidth == "80mm",
                    onClick = { paperWidth = "80mm" },
                    label = { Text("80 mm") }
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Large receipt text", Modifier.weight(1f))
                Switch(checked = largeText, onCheckedChange = { largeText = it })
            }

            Spacer(Modifier.height(12.dp))
            Text("Receipt style preset", style = MaterialTheme.typography.bodyMedium)
            Text(
                "A quick look layered over the toggles below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RECEIPT_PRESETS.forEach { (id, label) ->
                    FilterChip(
                        selected = prefs.receiptPreset == id,
                        onClick = { vm.savePrefs(prefs.copy(receiptPreset = id)) },
                        label = { Text(label) }
                    )
                }
            }

            // ---- Receipt template (saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Receipt template")
            ReceiptTemplateSection(prefs = prefs, onChange = { vm.savePrefs(it) })

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.saveBusiness(edited()) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save settings") }

            // ---- Danger zone ----
            Spacer(Modifier.height(24.dp))
            SettingsSectionHeader("Danger zone")
            Text(
                "These actions cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showResetStock = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Reset all stock to zero") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showWipeSales = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Wipe all sales history") }

            CloudSyncSection(vm)
        }
    }

    if (showResetStock) {
        ConfirmDialog(
            title = "Reset all stock?",
            message = "Every item's on-hand quantity will be set to zero. Sales history is kept. This cannot be undone.",
            confirmLabel = "Reset stock",
            onConfirm = { vm.resetAllStock(); showResetStock = false },
            onDismiss = { showResetStock = false }
        )
    }
    if (showWipeSales) {
        ConfirmDialog(
            title = "Wipe sales history?",
            message = "All recorded sales, their lines and payments will be permanently deleted. Stock and catalog are kept. This cannot be undone.",
            confirmLabel = "Wipe sales",
            onConfirm = { vm.wipeSalesData(); showWipeSales = false },
            onDismiss = { showWipeSales = false }
        )
    }

    if (showPicker) {
        PrinterPickerDialog(
            devices = printer.pairedPrinters(),
            onSelect = { dev ->
                printerMac = dev.mac
                printerName = dev.name
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

// ───────────────────────── CLOUD SYNC ─────────────────────────

/**
 * Bring-your-own-database panel. The app is fully usable with this left blank;
 * connecting the user's *own* Supabase just mirrors local data to the cloud.
 */
@Composable
private fun CloudSyncSection(vm: PosViewModel) {
    val connection by vm.connection.collectAsState()
    val status by vm.syncStatus.collectAsState()
    val lastSyncAt by vm.lastSyncAt.collectAsState()
    val connected = connection != null

    var url by remember(connection) { mutableStateOf(connection?.url ?: "") }
    var key by remember(connection) { mutableStateOf(connection?.anonKey ?: "") }
    var testing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(connection) { mutableStateOf<String?>(null) }
    var isError by remember(connection) { mutableStateOf(false) }

    Spacer(Modifier.height(28.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (connected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
            contentDescription = null,
            tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(8.dp))
        Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Optional. The app works fully offline. Connect your own Supabase database " +
            "to back up sales and sync across devices — the data stays in your account, not ours.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    if (!connected) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); message = null },
            label = { Text("Supabase URL (https://xxxx.supabase.co)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim(); message = null },
            label = { Text("Anon (public) API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        message?.let { SyncMessage(it, isError) }
        val canAct = url.isNotBlank() && key.isNotBlank() && !testing && !busy
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = canAct,
                onClick = {
                    testing = true; message = null
                    vm.testConnection(url, key) { result ->
                        testing = false
                        isError = result !is ConnectionTest.Ok
                        message = result.label()
                    }
                }
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Test")
            }
            Button(
                enabled = canAct,
                onClick = {
                    busy = true; message = null
                    vm.connect(url, key) { outcome ->
                        busy = false
                        isError = outcome !is SyncOutcome.Success
                        message = outcome.label()
                    }
                }
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Connect & sync")
            }
        }
    } else {
        Text(
            connection!!.url,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        val statusText = when (val s = status) {
            SyncStatus.Idle ->
                lastSyncAt?.let { "Last synced ${syncTimeLabel(it)}" } ?: "Connected — not synced yet"
            SyncStatus.Syncing -> "Syncing…"
            is SyncStatus.Done -> "Synced ${s.pushed} up · ${s.pulled} down · ${syncTimeLabel(s.at)}"
            is SyncStatus.Error -> "Sync error: ${s.message}"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (status is SyncStatus.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = status !is SyncStatus.Syncing,
                onClick = { vm.syncNow() }
            ) {
                if (status is SyncStatus.Syncing) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Sync, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
            OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        val pushOn by vm.cloudPushEnabled.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Upload this device's data", fontWeight = FontWeight.Medium)
                Text(
                    "Off = pull only (safe). On also PUSHES this device's sales, refunds " +
                        "and edited products up to the shared database — clear any test data first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = pushOn, onCheckedChange = { vm.setCloudPushEnabled(it) })
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SyncMessage(text: String, isError: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(8.dp))
}

private fun ConnectionTest.label(): String = when (this) {
    ConnectionTest.Ok -> "Connection works — tables found. You're good to connect."
    ConnectionTest.TablesMissing ->
        "Reached the database, but the tables aren't set up yet. Run the setup SQL " +
            "(see SUPABASE_SETUP.md) in your project, then try again."
    ConnectionTest.Unauthorized ->
        "Key rejected. Make sure you pasted the anon (public) key and the URL is correct."
    is ConnectionTest.Failed -> "Couldn't connect: $message"
}

private fun SyncOutcome.label(): String = when (this) {
    is SyncOutcome.Success -> "Connected. Synced $pushed up · $pulled down."
    SyncOutcome.NotConfigured -> "Enter your URL and key first."
    is SyncOutcome.Failed -> "Connected, but first sync failed: $message"
}

private fun syncTimeLabel(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun PrinterPickerDialog(
    devices: List<PrinterDevice>,
    onSelect: (PrinterDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Paired printers") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth devices found. Pair your thermal printer in Android Settings → Bluetooth first, then come back.")
            } else {
                LazyColumn {
                    items(devices, key = { it.mac }) { dev ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onSelect(dev) }.padding(vertical = 12.dp)
                        ) {
                            Text(dev.name, fontWeight = FontWeight.Medium)
                            Text(dev.mac, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogoPreview(uriString: String?) {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        bitmap = uriString?.let { s ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(s)).use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp, contentDescription = "Logo", modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
        } else {
            Text("Logo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
