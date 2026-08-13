@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.portionspot.pos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
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
import com.portionspot.pos.device.ConnectivityObserver
import com.portionspot.pos.media.ProductImages
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
import com.portionspot.pos.data.TagValue
import com.portionspot.pos.data.searchCatalog
import com.portionspot.pos.data.tagCaption
import com.portionspot.pos.data.ItemAttribute
import com.portionspot.pos.data.attrNorm
import com.portionspot.pos.data.groupAttributes
import com.portionspot.pos.data.keySuggestions
import com.portionspot.pos.data.valueSuggestions
import com.portionspot.pos.data.isMeasured
import com.portionspot.pos.data.onHand
import com.portionspot.pos.data.sellableBlocked
import com.portionspot.pos.data.stockIsShort
import com.portionspot.pos.data.MethodBreakdown
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.PurchaseOrderWithLines
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundLineInput
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.RefundWithLines
import com.portionspot.pos.data.computeRefundTotal
import com.portionspot.pos.data.returnedLineValue
import com.portionspot.pos.data.saleGoodsValue
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.StaffRequest
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalePayment
import com.portionspot.pos.data.AuditEntry
import com.portionspot.pos.data.computeSaleTotals
import com.portionspot.pos.data.isEditable
import com.portionspot.pos.data.wasEdited
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
import com.portionspot.pos.sync.SUPABASE_SETUP_SQL
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
    Cash("Till & Safe", "Cash"),
    Settings("Settings", "Settings"),
}

/**
 * The capability a CASHIER must hold to even SEE this screen (null = always visible).
 * Action-level gates (add item, submit expense, refund, discount, …) live at each
 * control; this only hides whole screens whose entire purpose is gated — the reports
 * surfaces. Admins hold every capability, so nothing is hidden for them.
 */
private fun Screen.viewCap(): com.portionspot.pos.auth.Capability? = when (this) {
    Screen.Dashboard, Screen.Reports -> com.portionspot.pos.auth.Capability.VIEW_REPORTS
    // Gated on CLOSE_DAY specifically, not on "holds any cash grant": revoking the
    // close takes the whole screen away, balances included, so a cashier who is not
    // trusted to shut up shop is not shown how much is sitting in the safe either.
    Screen.Cash -> com.portionspot.pos.auth.Capability.CLOSE_DAY
    else -> null
}

/** Bottom-nav pinned set (matches web PINNED_IDS: pos, sales, sync, inventory). */
private val PINNED_SCREENS = listOf(Screen.Sell, Screen.Reports, Screen.Sync, Screen.Items)
private val OVERFLOW_SCREENS =
    listOf(
        Screen.Dashboard, Screen.Customers, Screen.Credit,
        Screen.Expenses, Screen.Suppliers, Screen.Purchases, Screen.Receipts,
        Screen.Refunds, Screen.MobileMoney, Screen.Cash, Screen.Settings
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
    Screen.Cash -> Icons.Filled.AccountBalanceWallet
    Screen.Settings -> Icons.Filled.Settings
}

/**
 * A notification tap resolved to a record — "open the thing this alert is about".
 * [refType] is the vocabulary NotificationEngine already stamps on every alert
 * (item | sale | refund | customer | purchase_order | mm_receipt | device) and [refId]
 * the row's id (null for device-level alerts, which have a screen but no record).
 * Carried from MainActivity into [AppRoot], which is the shell every signed-in person
 * can reach — deliberately not the admin one, so a cashier is never sent somewhere they
 * would only be bounced out of.
 */
data class PosDeepLink(val refType: String, val refId: String?)

/** Where a [PosDeepLink.refType] lives in the POS shell; null = we can't land on it. */
private fun screenForRef(refType: String): Screen? = when (refType) {
    "item" -> Screen.Items
    "sale" -> Screen.Receipts
    "refund" -> Screen.Refunds
    "customer" -> Screen.Customers
    "purchase_order" -> Screen.Purchases
    "mm_receipt" -> Screen.MobileMoney
    "device" -> Screen.Sync
    // Cash events (a day closed, a float topped up, owner money moved) land on Till &
    // Safe. The row itself is not pre-opened: like Refunds and Sync, everything actionable
    // on that screen moves real money. `day_close` also carries a refId, but the close
    // history is right there in the section, so the screen alone answers "what happened".
    "cash", "day_close" -> Screen.Cash
    else -> null
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
            showCashier = p.receiptShowCashier,
            showPayment = p.receiptShowPayment,
            showChange = p.receiptShowChange,
            boldTotals = p.receiptBoldTotals,
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
    // Set when launched from any other alert: the record to land on ("Low stock: brake
    // pads" → the Inventory screen with that product open). Consumed the same way.
    deepLink: PosDeepLink? = null,
    onOpenConsumed: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val business by vm.business.collectAsState()
    val cart by vm.cart.collectAsState()
    var screen by remember { mutableStateOf(Screen.Sell) }
    var drawerOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val printer = rememberPrinterUi { vm.shopPrefs.value }

    // Per-person permissions: hide whole screens a cashier can't view (reports), and
    // steer off one if the admin revokes access while it's open.
    val caps by vm.allowedCaps.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()
    val screenVisible: (Screen) -> Boolean = { s -> s.viewCap()?.let { it in caps } ?: true }
    LaunchedEffect(caps) { if (!screenVisible(screen)) screen = Screen.Sell }

    LaunchedEffect(openMobileMoney) {
        // A payment notification sets BOTH this and the record link; let the link's effect
        // below do the consuming in that case, so clearing the intent can never race it.
        if (openMobileMoney) { screen = Screen.MobileMoney; if (deepLink == null) onOpenConsumed() }
    }

    // The record a notification asked us to land on. Held HERE rather than read straight
    // off [deepLink] because consuming the intent (so a rotation doesn't re-fire it) must
    // not close the sheet we just opened; each screen clears it once it has opened the row.
    var linked by remember { mutableStateOf<PosDeepLink?>(null) }
    LaunchedEffect(deepLink) {
        val dl = deepLink ?: return@LaunchedEffect
        // A cashier whose admin revoked a screen still gets the alert; send them to the
        // screen only if they can see it, otherwise leave them where they are.
        screenForRef(dl.refType)?.let { target -> if (screenVisible(target)) screen = target }
        linked = dl
        onOpenConsumed()
    }
    /** The id to open on [type]'s screen, or null when the link points elsewhere. */
    val linkedId: (String) -> String? = { type -> linked?.takeIf { it.refType == type }?.refId }
    val clearLink: () -> Unit = { linked = null }

    val currency = business?.currency ?: "USD"
    val shopName = business?.name ?: "Spot POS"
    val cartCount = cart.sumOf { it.qty }.toInt()
    val mmPending by vm.mmPendingCount.collectAsState()
    val pendingUpload by vm.pendingUpload.collectAsState()
    var showSyncSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = t.canvas,
            topBar = {
                MobileTopBar(
                    shopName = shopName,
                    logoUri = business?.logoUri,
                    adminBack = onExitToAdmin,
                    pendingUpload = pendingUpload,
                    onSyncClick = { showSyncSheet = true },
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
                    moreBadge = mmPending,
                    visible = screenVisible
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(t.canvasBrush)) {
                // Asks for POST_NOTIFICATIONS once, and says so out loud when alerts are
                // switched off — the owner should never be silently un-notified.
                NotificationAccessBar()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (business == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (!screenVisible(screen)) {
                        NoPermissionScreen()
                    } else when (screen) {
                        Screen.Sell -> SellScreen(vm, business!!, printer)
                        Screen.Dashboard -> DashboardScreen(vm, business!!)
                        Screen.Items -> ItemsScreen(vm, currency, linkedId("item"), clearLink)
                        Screen.Customers -> CustomersScreen(vm, currency, linkedId("customer"), clearLink)
                        Screen.Credit -> ChangeCreditScreen(vm, currency)
                        Screen.Cash -> CashScreen(vm, currency)
                        Screen.Expenses -> ExpensesScreen(vm, currency)
                        Screen.Suppliers -> SuppliersScreen(vm)
                        Screen.Purchases -> PurchaseOrdersScreen(vm, currency, linkedId("purchase_order"), clearLink)
                        Screen.Reports -> ReportsScreen(vm, business!!)
                        Screen.Receipts -> ReceiptsScreen(vm, business!!, printer, linkedId("sale"), clearLink)
                        // Refunds and Sync land on the SCREEN only: the row's own actions
                        // there are money moves (record a payout, push a sync), and a
                        // notification tap must never pre-open one of those.
                        Screen.Refunds -> RefundsScreen(vm, business!!, printer)
                        Screen.MobileMoney -> MobileMoneyScreen(vm, currency, linkedId("mm_receipt"), clearLink)
                        Screen.Sync -> SyncScreen(vm)
                        Screen.Settings -> SettingsScreen(vm, business!!, printer)
                    }
                }
            }
        }

        // Floating cart (§1.2 parity): on every cashier screen EXCEPT Sell (which has
        // its own cart bar) and while a sheet/drawer is open, so a cart-in-progress
        // stays visible and one tap jumps back to the till.
        if (screen != Screen.Sell && !drawerOpen && !moreOpen) {
            FloatingCart(vm, currency, onGoToSell = { screen = Screen.Sell })
        }

        // Tap the top-bar status icon → a quick sync status sheet (what's queued, when
        // it last uploaded/downloaded, and a manual "Sync now").
        if (showSyncSheet) {
            SyncStatusSheet(vm, onDismiss = { showSyncSheet = false })
        }

        if (moreOpen) {
            MoreSheet(
                current = screen,
                onSelect = { screen = it; moreOpen = false },
                onDismiss = { moreOpen = false },
                visible = screenVisible
            )
        }
        if (drawerOpen) {
            SideDrawer(
                shopName = shopName,
                current = screen,
                onSelect = { screen = it; drawerOpen = false },
                onDismiss = { drawerOpen = false },
                onSwitchUser = { drawerOpen = false; vm.switchUser() },
                onSignOut = { drawerOpen = false; vm.signOut() },
                // ★ Sign-out stays an ADMIN action. Both routes now land on the SAME staff
                // sign-in screen — the empty-account-list fall-through into a login screen
                // with a back arrow into local admin is gone — so this is no longer the
                // gate it once was. It is kept because a cashier ending their shift wants
                // the lock screen, not a device that has forgotten the session the SMS
                // receiver and the alert workers attribute by.
                canSignOut = isAdmin,
                logoUri = business?.logoUri,
                visible = screenVisible
            )
        }
    }
}

/** Placeholder shown when the signed-in cashier lacks the capability to view a screen. */
@Composable
private fun NoPermissionScreen() {
    val t = LocalPosTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = t.inkTertiary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("You don't have permission to view this", color = t.inkSecondary, fontWeight = FontWeight.SemiBold)
            Text("Ask an admin to grant access.", color = t.inkTertiary, fontSize = 12.sp)
        }
    }
}

// ─────────────────── NOTIFICATION ACCESS (§6.2 / §8 delivery) ───────────────────

private const val NOTIF_PREFS = "notif_access"
private const val NOTIF_ASKED_KEY = "post_notifications_asked"

/**
 * The one place the app asks for POST_NOTIFICATIONS, and the one place it admits when
 * alerts are off. It used to be asked for on the Mobile Money screen, bundled with the
 * two SMS permissions: an owner who never opened that screen was NEVER asked, so on
 * Android 13+ areNotificationsEnabled() stayed false and both Notifier entry points
 * returned silently — no low-stock alert, no payment prompt, and no hint why. Bundled
 * with SMS it also got denied as one lump. So: ask here, where every session lands
 * (this bar is rendered by both the POS and the admin shell), ask ONCE ever (a
 * SharedPreferences flag, so a "no" is respected rather than nagged at every launch),
 * and if alerts are off for ANY reason — denied, or switched off in system settings
 * later — show a dismissible strip with a one-tap route to the settings screen.
 */
@Composable
private fun NotificationAccessBar() {
    val t = LocalPosTokens.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var blocked by remember { mutableStateOf(!NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = context.getSharedPreferences(NOTIF_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(NOTIF_ASKED_KEY, false)) {
                // Mark BEFORE launching: the system dialog only ever appears twice, and a
                // silent no-op third ask must not be mistaken for "not asked yet".
                prefs.edit().putBoolean(NOTIF_ASKED_KEY, true).apply()
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Re-read on every resume — the fix for this bar is a trip to system settings, and we
    // must notice when the owner comes back having flipped the switch.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!blocked || dismissed) return

    Row(
        Modifier
            .fillMaxWidth()
            .background(t.warning.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.NotificationsOff, contentDescription = null,
            tint = t.warning, modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Alerts are off on this phone",
                color = t.inkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "You won't be told about low stock or payments to verify.",
                color = t.inkSecondary, fontSize = 11.sp
            )
        }
        TextButton(onClick = { openNotificationSettings(context) }) { Text("Turn on") }
        IconButton(onClick = { dismissed = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close, contentDescription = "Dismiss",
                tint = t.inkTertiary, modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Jump to this app's notification settings. Deliberately not another permission request:
 * by the time the bar is showing, the runtime ask has already been spent (or the owner
 * turned alerts off in settings, which no in-app dialog can undo). Falls back to the
 * app-details page on pre-O, where the per-app notification screen doesn't exist.
 */
private fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
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
private fun MobileMoneyScreen(
    vm: PosViewModel,
    currency: String,
    // Set when a "payment received — verify" notification was tapped: the receipt to put
    // under the owner's thumb rather than dropping him on a three-tab list.
    openReceiptId: String? = null,
    onOpened: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val needs by vm.mmNeedsVerification.collectAsState()
    val unmatched by vm.mmUnmatched.collectAsState()
    val verified by vm.mmVerified.collectAsState()
    val customers by vm.customers.collectAsState()
    var tab by remember { mutableStateOf(MmTab.NEEDS) }
    var verifyTarget by remember { mutableStateOf<MobileMoneyReceipt?>(null) }
    var undoTarget by remember { mutableStateOf<MobileMoneyReceipt?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Ask for the SMS permissions on first visit (declaration ≠ grant on 23+). If denied,
    // reconciliation just stays empty — nothing else breaks. POST_NOTIFICATIONS used to be
    // bundled in here; it lives in NotificationAccessBar now, because an owner who never
    // opened this screen was never asked and so never got a single notification.
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
        }
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
        // Already granted from a prior visit → pull anything that arrived while closed.
        else vm.backfillSmsInbox(context.applicationContext)
    }

    // Deep link: open the BUCKET the payment is actually in (it may already have been
    // verified from another phone by the time he taps) and mark it for the scroll below.
    LaunchedEffect(openReceiptId, needs, unmatched, verified) {
        val id = openReceiptId ?: return@LaunchedEffect
        val bucket = when {
            needs.any { it.id == id } -> MmTab.NEEDS
            unmatched.any { it.id == id } -> MmTab.UNMATCHED
            verified.any { it.id == id } -> MmTab.DONE
            else -> return@LaunchedEffect
        }
        tab = bucket
        highlightId = id
        onOpened()
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

    val list = when (tab) {
        MmTab.NEEDS -> needs
        MmTab.UNMATCHED -> unmatched
        MmTab.DONE -> verified
    }
    // Scroll the linked payment into view once its bucket is on screen. Keyed on the list
    // too because the row may only arrive with the next flow emission — but done ONCE per
    // link, so a later refresh doesn't yank the list back while he is reading it.
    var scrolledFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightId, list) {
        val id = highlightId ?: return@LaunchedEffect
        if (scrolledFor == id) return@LaunchedEffect
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            scrolledFor = id
            runCatching { listState.animateScrollToItem(idx) }
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
            // Switching bucket by hand drops the deep-link highlight — he's moved on.
            MmTabChip(MmTab.NEEDS, tab, needs.size) { tab = it; highlightId = null }
            MmTabChip(MmTab.UNMATCHED, tab, unmatched.size) { tab = it; highlightId = null }
            MmTabChip(MmTab.DONE, tab, verified.size) { tab = it; highlightId = null }
        }
        Spacer(Modifier.height(10.dp))

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
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // Clear the floating cart (bottom-anchored pill) so it can't sit over the last row.
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { r ->
                    MmReceiptCard(
                        r = r,
                        highlight = r.id == highlightId,
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
    onUndo: () -> Unit,
    // True for the payment a notification tap pointed at: a heavier brand-coloured edge,
    // so "which one was it warning me about" is answered without reading amounts.
    highlight: Boolean = false
) {
    val t = LocalPosTokens.current
    val who = r.matchedCustomerName ?: r.senderName ?: r.senderPhone ?: "Unknown sender"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) t.surface2 else t.surface1)
            .border(
                if (highlight) 2.dp else 1.dp,
                if (highlight) t.brand.s600 else t.surfaceBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
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

    PosContainedForm(
        title = "Verify ${money(r.amount, r.currency)}",
        onDismiss = onDismiss,
        confirmLabel = "Confirm",
        confirmEnabled = canConfirm,
        onConfirm = { onConfirm(if (purpose == "debt") selected else selected, purpose, note.ifBlank { null }) }
    ) {
        Text(
            "From ${r.senderName ?: r.senderPhone ?: "unknown"} · Ref ${r.txnCode}",
            color = t.inkTertiary, fontSize = 11.sp
        )
        Text("What was this for?", color = t.inkSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = purpose == "debt", onClick = { purpose = "debt" }, label = { Text("Settle debt") })
            FilterChip(selected = purpose == "sale", onClick = { purpose = "sale" }, label = { Text("Walk-in sale") })
        }
        if (purpose == "debt") {
            PosField(
                value = query, onValueChange = { query = it },
                label = "Customer", placeholder = "Search name or number", modifier = Modifier.fillMaxWidth()
            )
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
        PosField(
            value = note, onValueChange = { note = it },
            label = "Note (optional)", modifier = Modifier.fillMaxWidth()
        )
    }
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
    // Tapping an alert CARD in the feed asks the host to open that record. It goes back
    // up to MainActivity rather than being handled here so an in-app tap and a system
    // notification tap travel the same road — the POS shell, which holds those screens.
    onOpenRecord: (PosDeepLink) -> Unit = {},
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
    // Pull-on-open: entering the admin shell fetches what the cashier phones have done
    // instead of waiting for the poll cadence (coalesced inside SyncManager).
    LaunchedEffect(Unit) { vm.refreshFromCloud("admin-open") }
    // Re-pull when the admin lands on a data-heavy tab.
    LaunchedEffect(tab) {
        if (tab == AdminTab.Dashboard || tab == AdminTab.Alerts) vm.refreshFromCloud("admin-$tab")
    }
    // Deep-link from an admin notification → jump to the Alerts tab.
    LaunchedEffect(openAlerts) { if (openAlerts) { tab = AdminTab.Alerts; onOpenConsumed() } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = t.canvas,
            topBar = { AdminTopBar(business?.name ?: "Admin", onExitToCashier, business?.logoUri) },
            bottomBar = { AdminBottomNav(current = tab, alertsBadge = unread, onSelect = { tab = it }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(t.canvasBrush)) {
                // The admin phone is the one that most needs alerts to work — same bar,
                // same one-time ask, whichever shell the session happens to be in.
                NotificationAccessBar()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (business == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else when (tab) {
                        AdminTab.Dashboard -> DashboardScreen(vm, business!!)
                        AdminTab.Reports -> ReportsScreen(vm, business!!)
                        AdminTab.Inventory -> ItemsScreen(vm, currency)
                        AdminTab.Alerts -> AdminAlertsScreen(vm, currency, onOpenRecord)
                        AdminTab.Manage -> AdminManageScreen(vm, currency)
                        AdminTab.Settings -> SettingsScreen(vm, business!!, printer)
                    }
                }
            }
        }
    }
}

/** Admin top bar: an ADMIN badge + shop name + a switch into the cashier POS. */
@Composable
private fun AdminTopBar(shopName: String, onExitToCashier: () -> Unit, logoUri: String? = null) {
    val t = LocalPosTokens.current
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LogoMark(shopName, 30.dp, logoUri)
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
    // "cash" carries the till/safe events the owner asked to be told about: a completed
    // day close (with any shortage), a float top-up, and the safe being opened.
    "cash" to "Cash", "payments" to "Payments", "refunds" to "Refunds", "system" to "System"
)

/**
 * Admin Alerts feed (prompt §8), now backed by the PERSISTED `notifications` table
 * (Phase 7). The background [com.portionspot.pos.notify.AdminNotificationWorker]
 * computes + escalates; this screen reads the stored rows, shows unread state, marks
 * them read on tap, and filters by category. A sweep runs when the shell opens so it
 * is current without waiting for the periodic worker.
 */
@Composable
private fun AdminAlertsScreen(
    vm: PosViewModel,
    currency: String,
    onOpenRecord: (PosDeepLink) -> Unit = {}
) {
    val t = LocalPosTokens.current
    val all by vm.notifications.collectAsState()
    val requests by vm.pendingRequests.collectAsState()
    val customers by vm.customers.collectAsState()
    var cat by remember { mutableStateOf("all") }
    var approveFor by remember { mutableStateOf<StaffRequest?>(null) }

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

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ---- Requests (§Job 1): cashier approval requests awaiting a decision ----
            if (requests.isNotEmpty()) {
                item {
                    Text(
                        "Requests (${requests.size})",
                        color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(requests, key = { "req_${it.id}" }) { req ->
                    val currentLimit = customers.firstOrNull { it.customer.id == req.targetId }?.customer?.creditLimit
                    RequestCard(
                        req = req,
                        currentLimit = currentLimit,
                        currency = currency,
                        onApprove = { approveFor = req },
                        onDeny = { vm.decideStaffRequest(req.id, approve = false) }
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
            }

            // ---- Category filter + alert feed ----
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ALERT_CATS.forEach { (id, label) ->
                        FilterChip(selected = cat == id, onClick = { cat = id }, label = { Text(label) })
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            if (shown.isEmpty() && requests.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No alerts. Everything looks healthy.", color = t.inkTertiary)
                    }
                }
            } else {
                items(shown, key = { it.id }) { n ->
                    NotificationCard(
                        n,
                        onClick = {
                            if (n.readAt == null) vm.markNotificationRead(n.id)
                            // Same destination as tapping the system notification: the row
                            // it is about, when the alert names one we can land on.
                            val ref = n.refType
                            if (ref != null && screenForRef(ref) != null) {
                                onOpenRecord(PosDeepLink(ref, n.refId))
                            }
                        }
                    )
                }
            }
        }
    }

    approveFor?.let { req ->
        val currentLimit = customers.firstOrNull { it.customer.id == req.targetId }?.customer?.creditLimit
        ApproveRequestDialog(
            req = req,
            currentLimit = currentLimit,
            currency = currency,
            onConfirm = { amount ->
                vm.decideStaffRequest(req.id, approve = true, approvedAmount = amount)
                approveFor = null
            },
            onDismiss = { approveFor = null }
        )
    }
}

/**
 * One pending staff request in the admin Alerts "Requests" section (§Job 1). For the
 * credit-limit type it shows requester, customer, the current limit → requested figure,
 * an optional note and age, with Approve (which opens an editable-amount confirm) and Deny.
 */
@Composable
private fun RequestCard(
    req: StaffRequest,
    currentLimit: Double?,
    currency: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    val t = LocalPosTokens.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(t.surface2).border(1.dp, t.warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = t.warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (req.type == "credit_limit") "Credit limit" else req.type,
                    color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp
                )
                Text(
                    (req.requestedByName ?: "A cashier") +
                        (req.targetName?.let { " · $it" } ?: ""),
                    color = t.inkSecondary, fontSize = 12.sp
                )
            }
            Text(relativeAgo(req.createdAt), color = t.inkTertiary, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Current ${currentLimit?.let { money(it, currency) } ?: "none"}",
                color = t.inkTertiary, fontSize = 12.sp
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = "to", tint = t.inkTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                req.amount?.let { money(it, currency) } ?: "—",
                color = t.brand.s600, fontWeight = FontWeight.Black, fontSize = 15.sp
            )
        }
        req.note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text("\"$it\"", color = t.inkTertiary, fontSize = 11.sp, fontStyle = FontStyle.Italic)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Approve")
            }
            OutlinedButton(
                onClick = onDeny,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Deny")
            }
        }
    }
}

/**
 * Approve-a-request confirm (§Job 1): the admin sets/confirms the FINAL figure before it is
 * applied — pre-filled with the requested amount but fully editable, so they can grant a
 * different limit than was asked. Confirming executes the change on this device.
 */
@Composable
private fun ApproveRequestDialog(
    req: StaffRequest,
    currentLimit: Double?,
    currency: String,
    onConfirm: (Double?) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalPosTokens.current
    var amount by remember {
        mutableStateOf(req.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    PosContainedForm(
        title = "Approve credit limit",
        onDismiss = onDismiss,
        confirmLabel = "Approve",
        confirmEnabled = true,
        onConfirm = { onConfirm(amount.trim().toDoubleOrNull()) }
    ) {
        Text(
            (req.requestedByName ?: "A cashier") + " asked to set " +
                (req.targetName ?: "this customer") + "'s credit limit " +
                "(currently ${currentLimit?.let { money(it, currency) } ?: "none"}).",
            style = MaterialTheme.typography.bodySmall, color = t.inkSecondary
        )
        PosFormCard {
            PosField(
                value = amount,
                onValueChange = { amount = it },
                label = "Approved credit limit ($)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            "Leave blank to approve no limit. You can change the figure before approving.",
            fontSize = 11.sp, color = t.inkTertiary
        )
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
        "cash" -> Icons.Filled.AccountBalanceWallet
        else -> Icons.Filled.Sync
    }
    val unread = n.readAt == null
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (unread) t.surface2 else t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

/**
 * Admin console (prompt §8, Phase 7) — the admin-only tools that don't fit the reused
 * cashier screens: end-of-day / shift summary, debt aging + write-offs, force-disable
 * payment methods, void a wrongful refund, and the audit-log viewer. (Server-enforced
 * cashier account CRUD is deferred with the sync-parity phase.)
 */
@Composable
private fun AdminManageScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    // The admin console is only reached by admins (who hold every capability), but gate
    // the money/staff controls on the caps too so the rule is enforced in one place.
    val caps by vm.allowedCaps.collectAsState()
    val canVoid = com.portionspot.pos.auth.Capability.VOID_SALES in caps
    val canManageStaff = com.portionspot.pos.auth.Capability.MANAGE_STAFF in caps
    val business by vm.business.collectAsState()
    val cashiers by vm.eodCashiers.collectAsState()
    val methods by vm.eodMethods.collectAsState()
    val changeGiven by vm.eodChangeGiven.collectAsState()
    val eodDay by vm.eodDay.collectAsState()
    val eodSession by vm.eodSession.collectAsState()
    val aging by vm.debtAging.collectAsState()
    val refunds by vm.refunds.collectAsState()
    val audit by vm.auditLog.collectAsState()
    val discountsGiven by vm.discountsGiven.collectAsState()
    // Accounting spine (B3): cash position + expense approvals + recurring schedules.
    val cashOnHand by vm.cashOnHand.collectAsState()
    // The two locations, so the funding picker can offer them in the right order (§4).
    val tillBalance by vm.tillBalance.collectAsState()
    val safeBalance by vm.safeBalance.collectAsState()
    val isAdminSession by vm.isAdmin.collectAsState()
    var fundingNotice by remember { mutableStateOf<String?>(null) }
    val payables by vm.payablesTotal.collectAsState()
    // Owner money now reads the OUTSIDE-FUNDS ledger, not the expense capital column:
    // that column funds bills from outside the shop generally, so a LOAN would otherwise
    // be reported here as the owner's own money.
    val outsideTotals by vm.outsideFundTotals.collectAsState()
    val openingFloat by vm.openingFloat.collectAsState()
    val pendingExpenses by vm.pendingExpenses.collectAsState()
    val templates by vm.recurringTemplates.collectAsState()

    var writeOffFor by remember { mutableStateOf<DebtAgingRow?>(null) }
    var voidFor by remember { mutableStateOf<String?>(null) }
    var countedCash by remember { mutableStateOf("") }
    var approveFor by remember { mutableStateOf<Expense?>(null) }   // shortfall dialog target
    var editTemplate by remember { mutableStateOf<Expense?>(null) }
    var editFloat by remember { mutableStateOf(false) }
    var addExpense by remember { mutableStateOf(false) }
    var showAddCashier by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    var dedupeConfirm by remember { mutableStateOf(false) }
    var resetPwFor by remember { mutableStateOf<com.portionspot.pos.auth.StaffRow?>(null) }
    var permsFor by remember { mutableStateOf<com.portionspot.pos.auth.StaffRow?>(null) }
    var removeStaffFor by remember { mutableStateOf<com.portionspot.pos.auth.StaffRow?>(null) }
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
                "Hand this device to a cashier: 'Switch user' returns to the staff list, where they tap their own name and enter their own PIN. 'Sign out' does the same and forgets this session as well.",
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

        // ---- Till & safe (the owner's own cash model, §1–§6) ----
        // Put FIRST in the cash area on purpose: "where is my money and how much of it is
        // mine" is the question the owner opens this screen to answer. Closing the day,
        // topping up the float and moving owner money all live in here, and every one of
        // them is a button pressed on command — nothing in this section runs on a timer.
        item { AdminSectionHeader("Till & safe") }
        item { TillAndSafeSection(vm, currency) }

        // ---- Cash & expenses (accounting spine, B3) ----
        item { AdminSectionHeader("Cash & expenses") }
        item {
            // Cash position card: on-hand, opening float, payables, owner contributions.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(t.surface1).border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = t.brand.s500, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cash on hand", color = t.inkTertiary, fontSize = 12.sp)
                        Text(money(cashOnHand, currency), color = if (cashOnHand < 0) t.danger else t.inkPrimary,
                            fontWeight = FontWeight.Black, fontSize = 24.sp)
                    }
                    TextButton(onClick = { editFloat = true }) { Text("Opening ${money(openingFloat, currency)}") }
                }
                Text(
                    "= till + safe. Opening float + cash from sales − cash paid out.",
                    color = t.inkTertiary, fontSize = 10.sp
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = t.surfaceBorder)
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Owed to payees", color = t.inkTertiary, fontSize = 11.sp)
                        Text(money(payables, currency), color = if (payables > 0.005) t.warning else t.inkSecondary,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Owner put in (net)", color = t.inkTertiary, fontSize = 11.sp)
                        Text(
                            money(outsideTotals.ownerNet, currency),
                            color = if (outsideTotals.ownerNet < 0) t.danger else t.inkSecondary,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { addExpense = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Submit an expense")
            }
        }

        // Pending approvals (§9.2/§9.4).
        item {
            Text(
                "Awaiting approval" + if (pendingExpenses.isNotEmpty()) " (${pendingExpenses.size})" else "",
                color = t.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (pendingExpenses.isEmpty()) {
            item { Text("Nothing to approve.", color = t.inkTertiary, fontSize = 12.sp) }
        } else {
            items(pendingExpenses, key = { it.id }) { e ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(t.surface1).border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp)).padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.category, color = t.inkPrimary, fontWeight = FontWeight.Bold)
                                if (e.recurring) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.Repeat, contentDescription = "Recurring", tint = t.inkTertiary, modifier = Modifier.size(13.dp))
                                    Text(" ${e.recurrencePeriod ?: ""}", color = t.inkTertiary, fontSize = 10.sp)
                                }
                            }
                            Text(
                                (e.description ?: "").ifBlank { e.date } +
                                    (e.submittedByName?.let { " · $it" } ?: ""),
                                color = t.inkTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(money(e.amount, currency), color = t.danger, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                // The till alone covers it => post straight from the drawer,
                                // no question asked. Anything else and the payer picks the
                                // source: TILL → SAFE → OUTSIDE FUNDS → abort (§4).
                                if (tillBalance + 0.005 >= e.amount) vm.approveExpense(e.id, "till")
                                else approveFor = e
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Approve")
                        }
                        OutlinedButton(
                            onClick = { vm.rejectExpense(e.id) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Reject")
                        }
                    }
                }
            }
        }

        // Recurring schedules (§9.3) — pause / edit amount / cancel.
        if (templates.isNotEmpty()) {
            item {
                Text("Recurring schedules", color = t.inkSecondary, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            items(templates, key = { it.id }) { tpl ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(t.surface1).border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${tpl.category} · ${money(tpl.amount, currency)}", color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "${tpl.recurrencePeriod ?: "monthly"} · " +
                                (if (tpl.recurrenceActive) "next ${tpl.nextRunAt?.let { dayFmt.format(Date(it)) } ?: "—"}" else "paused"),
                            color = if (tpl.recurrenceActive) t.inkTertiary else t.warning, fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = { vm.setRecurringActive(tpl.id, !tpl.recurrenceActive) }) {
                        Icon(
                            if (tpl.recurrenceActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (tpl.recurrenceActive) "Pause" else "Resume", tint = t.inkSecondary
                        )
                    }
                    IconButton(onClick = { editTemplate = tpl }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit amount", tint = t.inkSecondary)
                    }
                    IconButton(onClick = { vm.cancelRecurring(tpl.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Cancel", tint = t.danger)
                    }
                }
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.surface1)
                    .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                    // The day's SHIFT. A shift is the trading day here, so this line says
                    // what state the day the owner is looking at is actually in — open and
                    // still trading, ended but never counted, or counted and settled. A
                    // figure with no idea which of those it is behind it is not a cash-up.
                    eodSession?.let { s ->
                        val counted = s.countedCash
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Shift", color = t.inkSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    counted != null -> "Counted · ${money(counted, currency)}"
                                    s.isOpen -> "Open"
                                    else -> "Ended, not counted"
                                },
                                color = if (counted != null) t.success else t.inkSecondary,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
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

        // ---- Debt aging (30/60/90) ----
        item { AdminSectionHeader("Debt aging") }
        if (aging.isEmpty()) {
            item { Text("No outstanding debts.", color = t.inkTertiary, fontSize = 13.sp) }
        } else {
            items(aging, key = { it.customerId }) { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
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
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    methodFlags.forEach { (id, label, enabled) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = t.inkPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { vm.setPaymentMethodEnabled(id, it) })
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
                    if (canVoid) {
                        OutlinedButton(
                            onClick = { voidFor = r.id },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
                        ) { Text("Void") }
                    }
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
        // ---- Discounts given (§Job 2: recorded, not approved — the admin reviews here) ----
        item { AdminSectionHeader("Discounts given") }
        if (discountsGiven.isEmpty()) {
            item { Text("No discounts given yet.", color = t.inkTertiary, fontSize = 12.sp) }
        } else {
            item {
                val total = discountsGiven.sumOf { it.discountTotal }
                Text(
                    "${discountsGiven.size} discounted ${if (discountsGiven.size == 1) "sale" else "sales"} · ${money(total, currency)} off in total",
                    color = t.inkTertiary, fontSize = 12.sp
                )
            }
            items(discountsGiven, key = { "disc_${it.id}" }) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(t.surface1).border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (s.createdByName ?: "Unattributed") +
                                (s.customerName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                        )
                        Text(
                            "#${s.receiptNo ?: s.id.takeLast(6).uppercase()} · ${relativeAgo(s.soldAt)}",
                            color = t.inkTertiary, fontSize = 11.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("-${money(s.discountTotal, currency)}", color = t.danger, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Text("of ${money(s.subtotal, currency)}", color = t.inkTertiary, fontSize = 10.sp)
                    }
                }
            }
        }

        // ---- Cashiers (staff accounts) ----
        item { AdminSectionHeader("Cashiers") }
        if (syncConnection == null) {
            item {
                Text(
                    "Connect cloud sync to add cashier accounts. Each cashier signs in with their own name and PIN — on this phone or their own — and is attributed on every sale.",
                    color = t.inkTertiary, fontSize = 12.sp
                )
            }
        } else {
            if (staff.isEmpty()) {
                item { Text("No staff loaded yet — tap Refresh.", color = t.inkTertiary, fontSize = 13.sp) }
            } else {
                items(staff, key = { it.id }) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.label, color = t.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append(s.role.replaceFirstChar { it.uppercase() })
                                        if (s.username.isNotBlank()) append(" · ${s.username}")
                                        // A member with no PIN cannot sign in anywhere. Said
                                        // here because this is the only screen that can fix it.
                                        if (!s.hasPin) append(" · no PIN")
                                        if (!s.active) append(" · inactive")
                                    },
                                    color = if (s.active && s.hasPin) t.inkTertiary else t.danger, fontSize = 11.sp
                                )
                            }
                            if (canManageStaff) {
                                TextButton(onClick = { resetPwFor = s }) {
                                    Text(if (s.hasPin) "Set PIN" else "Give PIN")
                                }
                                // Admins aren't removed here; only cashiers are (de)activated.
                                // Remove = soft-deactivate (history/attribution kept); a
                                // deactivated account can't log in and drops off the roster.
                                if (s.role != "admin") {
                                    if (s.active) {
                                        TextButton(
                                            onClick = { removeStaffFor = s },
                                            colors = ButtonDefaults.textButtonColors(contentColor = t.danger)
                                        ) { Text("Remove") }
                                    } else {
                                        TextButton(onClick = { vm.setCashierActive(s.id, true) }) { Text("Reactivate") }
                                    }
                                }
                            }
                        }
                        // Per-person permissions — cashiers only (admins implicitly hold all).
                        if (s.role != "admin" && canManageStaff) {
                            val granted = s.perms()
                            val onCount = com.portionspot.pos.auth.Capability.entries.count { granted.allows(it) }
                            TextButton(
                                onClick = { permsFor = s },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Permissions ($onCount of ${com.portionspot.pos.auth.Capability.entries.size})", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canManageStaff) {
                        Button(onClick = { showAddCashier = true }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Add cashier")
                        }
                    }
                    OutlinedButton(onClick = { vm.refreshStaff() }) { Text("Refresh") }
                }
            }
        }

        // ---- Catalogue maintenance (safe — leaves sales/customers alone) ----
        item { AdminSectionHeader("Catalogue") }
        item {
            Text(
                "If a cloud sync left duplicate products (e.g. a hand-added item plus its cloud copy), merge them here. Your sales and customers are not touched.",
                color = t.inkTertiary, fontSize = 12.sp
            )
        }
        item {
            OutlinedButton(onClick = { dedupeConfirm = true }) { Text("Remove duplicate items") }
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
            onCreate = { name, username, pin, cb -> vm.createCashier(name, username, pin, "cashier", cb) }
        )
    }
    resetPwFor?.let { s ->
        SetPinDialog(
            staffName = s.label,
            onDismiss = { resetPwFor = null },
            onSet = { pin, cb -> vm.setCashierPin(s.id, pin, cb) }
        )
    }
    permsFor?.let { s ->
        val ctx = LocalContext.current
        StaffPermissionsDialog(
            staffName = s.label,
            initial = s.perms(),
            onDismiss = { permsFor = null },
            onSave = { map ->
                vm.setStaffPermissions(s.id, map) { res ->
                    when (res) {
                        is com.portionspot.pos.auth.StaffResult.Ok ->
                            Toast.makeText(ctx, "Permissions saved", Toast.LENGTH_SHORT).show()
                        is com.portionspot.pos.auth.StaffResult.Err ->
                            Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
                permsFor = null
            }
        )
    }
    removeStaffFor?.let { s ->
        val ctx = LocalContext.current
        ConfirmDialog(
            title = "Remove ${s.label}?",
            message = "They can no longer sign in and won't appear on any till's staff list. " +
                "Their past sales and attribution stay intact, and you can reactivate them later.",
            confirmLabel = "Remove",
            onConfirm = {
                val id = s.id
                removeStaffFor = null
                vm.setCashierActive(id, false) { res ->
                    when (res) {
                        is com.portionspot.pos.auth.StaffResult.Ok ->
                            Toast.makeText(ctx, "Staff member removed", Toast.LENGTH_SHORT).show()
                        is com.portionspot.pos.auth.StaffResult.Err ->
                            Toast.makeText(ctx, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { removeStaffFor = null }
        )
    }
    if (dedupeConfirm) {
        val ctx = LocalContext.current
        ConfirmDialog(
            title = "Remove duplicate items?",
            message = "Merges catalogue duplicates: items sharing a code (SKU), and a hand-added item whose name uniquely matches a cloud product. The cloud copy is kept. Your sales and customers are not touched.",
            confirmLabel = "Merge duplicates",
            onConfirm = {
                dedupeConfirm = false
                vm.dedupeItems { n ->
                    Toast.makeText(
                        ctx,
                        if (n > 0) "Merged $n duplicate item${if (n == 1) "" else "s"}" else "No duplicate items found",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { dedupeConfirm = false }
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

    // §4 funding waterfall: the till alone won't cover this expense, so the payer chooses
    // the source in the owner's own order — TILL → SAFE → OUTSIDE FUNDS → abort. Picking
    // the SAFE is admin-approved every time; a non-admin's choice raises a request to the
    // owner instead of posting (enforced in the ViewModel, surfaced here as a message).
    approveFor?.let { e ->
        FundingSourceDialog(
            title = "How is this paid?",
            amount = e.amount,
            till = tillBalance,
            safe = safeBalance,
            currency = currency,
            isAdmin = isAdminSession,
            onDismiss = { approveFor = null },
            onChoose = { mode ->
                vm.approveExpense(e.id, mode) {
                    fundingNotice = "Sent to the owner for approval — the safe can only be " +
                        "opened by them. Once approved the cash moves into the till."
                }
                approveFor = null
            }
        )
    }
    fundingNotice?.let { msg ->
        AlertDialog(
            onDismissRequest = { fundingNotice = null },
            title = { Text("Waiting on the owner") },
            text = { Text(msg, color = t.inkSecondary, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { fundingNotice = null }) { Text("OK") } }
        )
    }
    // Opening cash float editor.
    if (editFloat) {
        AmountDialog(
            title = "Opening cash float",
            hint = "Starting cash in the drawer. Cash on hand builds from here.",
            initial = openingFloat, currency = currency,
            onDismiss = { editFloat = false },
            onConfirm = { v -> vm.setOpeningFloat(v); editFloat = false }
        )
    }
    // Edit a recurring schedule's future amount.
    editTemplate?.let { tpl ->
        AmountDialog(
            title = "Edit ${tpl.category} amount",
            hint = "Applies to future charges only; posted charges stay as they were.",
            initial = tpl.amount, currency = currency,
            onDismiss = { editTemplate = null },
            onConfirm = { v -> vm.editRecurringAmount(tpl.id, v); editTemplate = null }
        )
    }
    // Admin submits an expense (then approves it below).
    if (addExpense) {
        ExpenseModal(initial = null, onDismiss = { addExpense = false }) { cat, amt, date, desc, recurring, period ->
            vm.submitExpense(cat, amt, date, desc, recurring, period); addExpense = false
        }
    }
}

/** Minimal single-amount editor dialog (opening float, recurring amount…). */
@Composable
private fun AmountDialog(
    title: String,
    hint: String,
    initial: Double,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val t = LocalPosTokens.current
    var value by remember { mutableStateOf(if (initial > 0) trimQty(initial) else "") }
    val parsed = value.replace(',', '.').toDoubleOrNull() ?: -1.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text("Amount ($currency)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed) }, enabled = parsed >= 0.0) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
    PosContainedForm(
        title = "Write off ${row.customerName}'s debt",
        onDismiss = onDismiss,
        confirmLabel = "Write off",
        confirmEnabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0,
        onConfirm = { amount.toDoubleOrNull()?.let { if (it > 0) onConfirm(it) } }
    ) {
        val t = LocalPosTokens.current
        Text(
            "Owes ${money(row.total, currency)}. Writing off records a payment that clears the balance (audit-logged).",
            fontSize = 12.sp, color = t.inkSecondary
        )
        PosFormCard {
            PosField(value = amount, onValueChange = { amount = it }, label = "Amount", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Admin dialog to add a cashier — one `staff` row with a username and a PIN.
 *
 * No email and no password. The credential the shop shares between the web POS and every
 * phone is `staff.pin_hash`, so what the owner sets here is the same four-to-six digits
 * that cashier types on any till in the shop.
 */
@Composable
private fun AddCashierDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, (com.portionspot.pos.auth.StaffResult) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && username.isNotBlank() &&
        pin.length in 4..6 && pin == confirm
    PosContainedForm(
        title = "Add cashier",
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = if (busy) "Creating…" else "Create",
        confirmEnabled = valid && !busy,
        onConfirm = {
            busy = true; error = null
            onCreate(name.trim(), username.trim(), pin) { res ->
                busy = false
                when (res) {
                    is com.portionspot.pos.auth.StaffResult.Ok -> onDismiss()
                    is com.portionspot.pos.auth.StaffResult.Err -> error = res.message
                }
            }
        }
    ) {
        val t = LocalPosTokens.current
        Text(
            "Adds them to the shop's staff list. They tap their name and enter this PIN — on " +
                "their own phone, on this one, or on the web POS. Once a phone has synced the " +
                "staff list they can sign in with no internet. They're attributed on every sale.",
            fontSize = 12.sp, color = t.inkSecondary
        )
        PosFormCard {
            PosField(name, { name = it }, "Name", modifier = Modifier.fillMaxWidth())
            PosField(username, { username = it }, "Username", modifier = Modifier.fillMaxWidth())
            PosField(
                pin, { new -> if (new.length <= 6 && new.all { it.isDigit() }) pin = new },
                "PIN (4-6 digits)",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            PosField(
                confirm, { new -> if (new.length <= 6 && new.all { it.isDigit() }) confirm = new },
                "Confirm PIN",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, color = t.danger, fontSize = 12.sp) }
    }
}

/** Admin dialog to set (or replace) a staff member's till PIN. */
@Composable
private fun SetPinDialog(
    staffName: String,
    onDismiss: () -> Unit,
    onSet: (String, (com.portionspot.pos.auth.StaffResult) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = pin.length in 4..6 && pin == confirm
    PosContainedForm(
        title = "Set PIN",
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = if (busy) "Saving…" else "Set PIN",
        confirmEnabled = valid && !busy,
        onConfirm = {
            busy = true; error = null
            onSet(pin) { res ->
                busy = false
                when (res) {
                    is com.portionspot.pos.auth.StaffResult.Ok -> {
                        Toast.makeText(context, "PIN set for $staffName", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    is com.portionspot.pos.auth.StaffResult.Err -> error = res.message
                }
            }
        }
    ) {
        val t = LocalPosTokens.current
        Text(
            "Sets the PIN $staffName types to sign in — on any till in the shop and on the web POS. " +
                "The old one stops working everywhere as soon as each device syncs.",
            fontSize = 12.sp, color = t.inkSecondary
        )
        PosFormCard {
            PosField(
                pin, { new -> if (new.length <= 6 && new.all { it.isDigit() }) pin = new },
                "New PIN (4-6 digits)",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            PosField(
                confirm, { new -> if (new.length <= 6 && new.all { it.isDigit() }) confirm = new },
                "Confirm PIN",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, color = t.danger, fontSize = 12.sp) }
    }
}

/**
 * Admin editor for one cashier's capability grants (auth/Permissions.kt). One switch per
 * capability, pre-filled from the cashier's current grants (empty ⇒ cashier defaults).
 * Saving PATCHes the full map to `staff.permissions` via [PosViewModel.setStaffPermissions].
 */
@Composable
private fun StaffPermissionsDialog(
    staffName: String,
    initial: com.portionspot.pos.auth.Permissions,
    onDismiss: () -> Unit,
    onSave: (Map<String, Boolean>) -> Unit,
) {
    val t = LocalPosTokens.current
    val caps = com.portionspot.pos.auth.Capability.entries
    val state = remember(staffName) {
        mutableStateMapOf<com.portionspot.pos.auth.Capability, Boolean>().apply {
            caps.forEach { put(it, initial.allows(it)) }
        }
    }
    PosContainedForm(
        title = "Permissions",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        onConfirm = { onSave(caps.associate { it.key to (state[it] ?: false) }) }
    ) {
        Text(
            "What $staffName can do. An admin always has every permission; these apply to this cashier only.",
            fontSize = 12.sp, color = t.inkSecondary
        )
        PosFormCard {
            caps.forEachIndexed { i, cap ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cap.label, color = t.inkPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state[cap] ?: false,
                        onCheckedChange = { state[cap] = it }
                    )
                }
                if (i < caps.size - 1) HorizontalDivider(color = t.surfaceBorder)
            }
        }
    }
}

/** Delegates to the ONE day boundary, in [com.portionspot.pos.data.startOfDay]. A private
 *  copy here was a second definition of "today" and the shift is now a third consumer of
 *  it — three implementations is how one screen's day quietly stops matching another's. */
private fun todayStartMs(): Long = com.portionspot.pos.data.startOfDay(System.currentTimeMillis())

/**
 * A count rendered inside a small circular badge. Compose's default text layout
 * adds font padding and lays the glyph in a line box taller than the digit, so a
 * lone number sits visibly BELOW the circle's centre. Stripping the font padding
 * and centring the line height plants the number dead-centre in the bubble
 * (prompt §1 — badge numbers off-centre).
 */
@Composable
private fun BadgeNumber(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Black
) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = fontSize,
        textAlign = TextAlign.Center,
        maxLines = 1,
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            )
        )
    )
}

/** Logo square: the shop's uploaded logo when set, else its initials. Used in the
 *  topbar + drawer (prompt §3 — show the logo, not just letters). */
@Composable
private fun LogoMark(
    shopName: String,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    logoUri: String? = null
) {
    val t = LocalPosTokens.current
    val context = LocalContext.current
    var bitmap by remember(logoUri) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(logoUri) {
        bitmap = logoUri?.takeIf { it.isNotBlank() }?.let { s ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(s)).use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(t.brand.s600),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = "$shopName logo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                shopName.take(3).uppercase(),
                color = t.inkOnBrand,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.30f).sp,
                letterSpacing = (-0.5).sp
            )
        }
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
    pendingUpload: Int = 0,
    onSyncClick: () -> Unit = {},
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
            LogoMark(shopName, 30.dp, logoUri)
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
            // Real connectivity, not a hardcoded green Wi-Fi: online => Wi-Fi in the
            // online tint, offline => WifiOff muted. Tap for the sync status sheet; a
            // warning dot shows when local rows are still waiting to upload.
            val online by ConnectivityObserver.rememberOnlineState()
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onSyncClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (online) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = if (online) "Online — sync status" else "Offline — sync status",
                    tint = if (online) t.onlinePill else t.inkTertiary,
                    modifier = Modifier.size(16.dp)
                )
                if (pendingUpload > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                            .size(8.dp).clip(CircleShape).background(t.warning)
                    )
                }
            }
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
    moreBadge: Int = 0,
    visible: (Screen) -> Boolean = { true },
) {
    val t = LocalPosTokens.current
    val overflowActive = current in OVERFLOW_SCREENS
    Column(Modifier.fillMaxWidth().background(t.surface1)) {
        HorizontalDivider(color = t.surfaceBorder)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            PINNED_SCREENS.filter(visible).forEach { s ->
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
                        BadgeNumber("$badge", color = Color(0xFF1A1A1A), fontSize = 9.sp)
                    }
                }
            }
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "More" bottom sheet listing overflow destinations (web MoreSheet). */
@Composable
private fun MoreSheet(
    current: Screen,
    onSelect: (Screen) -> Unit,
    onDismiss: () -> Unit,
    visible: (Screen) -> Boolean = { true },
) {
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
            OVERFLOW_SCREENS.filter(visible).forEach { s ->
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

/** Quick sync status sheet (tap the top-bar Wi-Fi icon): what's queued to upload,
 *  when data last went up / came down, any per-table warnings, and a manual "Sync
 *  now". A lightweight peek at the fuller Sync screen. */
@Composable
private fun SyncStatusSheet(vm: PosViewModel, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val connection by vm.connection.collectAsState()
    val status by vm.syncStatus.collectAsState()
    val lastUpload by vm.lastUploadAt.collectAsState()
    val lastDownload by vm.lastDownloadAt.collectAsState()
    val pending by vm.pendingUpload.collectAsState()
    val online by ConnectivityObserver.rememberOnlineState()

    LaunchedEffect(Unit) { vm.refreshPendingUpload() }

    fun clock(ts: Long?): String =
        if (ts == null || ts <= 0L) "never"
        else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    Box(
        Modifier.fillMaxSize().background(Color(0x73000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(t.surface1)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(enabled = false) {}
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                    .width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(t.surface4)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (online) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = if (online) t.onlinePill else t.inkTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    if (online) "Online" else "Offline",
                    color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (status is SyncStatus.Syncing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = t.brand.s500)
                }
            }

            if (connection == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cloud sync is off. Connect your database in Settings to back up and sync across devices.",
                    fontSize = 12.sp, color = t.inkTertiary
                )
            } else {
                Spacer(Modifier.height(8.dp))
                SyncStatRow("Last upload", clock(lastUpload))
                SyncStatRow("Last download", clock(lastDownload))
                SyncStatRow(
                    "Waiting to upload",
                    if (pending > 0) "$pending row(s)" else "all synced",
                    highlight = pending > 0
                )
                val warnings = (status as? SyncStatus.Done)?.warnings.orEmpty()
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    warnings.forEach { w -> Text("• $w", fontSize = 11.sp, color = t.warning) }
                }
                (status as? SyncStatus.Error)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it.message, fontSize = 11.sp, color = t.danger)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.syncNow() },
                    enabled = online && status !is SyncStatus.Syncing,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.brand.s600, contentColor = t.inkOnBrand
                    )
                ) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SyncStatRow(label: String, value: String, highlight: Boolean = false) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = t.inkSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) t.warning else t.inkPrimary
        )
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
    onSignOut: () -> Unit,
    /** Sign-out is offered to ADMINS only; a cashier gets "Switch user" instead. */
    canSignOut: Boolean = true,
    logoUri: String? = null,
    visible: (Screen) -> Boolean = { true },
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
                LogoMark(shopName, 36.dp, logoUri)
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
                Screen.entries.filter(visible).forEach { s ->
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
            // navigationBarsPadding keeps "Sign out" clear of the phone's gesture/recents
            // bar, which was overlapping it at the very bottom (prompt §3).
            Column(
                Modifier.navigationBarsPadding().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DrawerAction(Icons.Filled.SwitchAccount, "Switch user", onSwitchUser)
                if (canSignOut) {
                    DrawerAction(Icons.AutoMirrored.Filled.Logout, "Sign out", onSignOut)
                }
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
    val tags by vm.itemTags.collectAsState()
    val cart by vm.cart.collectAsState()
    val customers by vm.customers.collectAsState()
    val lastReceipt by vm.lastReceipt.collectAsState()
    val paynowReady by vm.paynowOnlineReady.collectAsState()
    val parkedSales by vm.parkedSales.collectAsState()
    val parkedCount by vm.parkedCount.collectAsState()
    val caps by vm.allowedCaps.collectAsState()
    // A cart change parked because it would sell past the on-hand. Lives on the ViewModel,
    // not here, so it survives the cashier switching screens mid-decision and so the cart
    // and the question about it can never be applied out of order.
    val oversell by vm.oversellPrompt.collectAsState()
    val canGiveDiscounts = com.portionspot.pos.auth.Capability.GIVE_DISCOUNTS in caps
    // The four the app declared and never consulted. Each has a matching handler-side
    // return in the ViewModel — hiding the control alone is not a gate.
    val canPriceOverride = com.portionspot.pos.auth.Capability.PRICE_OVERRIDE in caps
    val canSellOnCredit = com.portionspot.pos.auth.Capability.SELL_ON_CREDIT in caps
    val canParkSales = com.portionspot.pos.auth.Capability.PARK_SALES in caps
    val canMakeQuotes = com.portionspot.pos.auth.Capability.MAKE_QUOTES in caps
    var showParked by remember { mutableStateOf(false) }

    var search by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("All") }
    // Grid (default) vs dense LIST browsing. Persisted for the session so the cashier's
    // preference survives config changes / screen switches (rememberSaveable, no data layer).
    var listView by rememberSaveable { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var showPayment by remember { mutableStateOf(false) }
    var quoteMode by remember { mutableStateOf(false) }
    var showQuote by remember { mutableStateOf(false) }
    var priceModalItem by remember { mutableStateOf<Item?>(null) }
    var measuredItem by remember { mutableStateOf<Item?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val total = cart.sumOf { it.lineTotal }
    val count = cart.sumOf { it.qty }.toInt()

    // Free-text categories from the catalog → chips (first word only, like the web).
    val categories = remember(items) {
        listOf("All") + items.mapNotNull { it.category?.trim()?.takeIf { c -> c.isNotEmpty() } }.distinct()
    }
    val q = search.trim()
    // Search covers the item's TAGS as well as its name/sku/barcode — in this shop the
    // tags are the cars a part fits, so "Hilux" finds the filter whose name never says
    // Hilux. Each hit carries the tag values that matched, so the card can show WHY it is
    // on screen instead of looking like a bad result.
    val hits = remember(items, q, selectedCat, tags) {
        val visible = items.filter { it.isActive && !it.deleted }.filter { item ->
            q.isNotEmpty() || selectedCat == "All" ||
                (item.category?.equals(selectedCat, ignoreCase = true) == true)
        }
        searchCatalog(visible, q, tags)
    }
    val filtered = hits.map { it.item }

    Column(Modifier.fillMaxSize()) {
        // ── Search + category chips (white header, mirrors the web POS) ──
        Column(Modifier.fillMaxWidth().background(t.surface1).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SearchField(value = search, onValue = { search = it }, onClear = { search = "" })
                }
                Spacer(Modifier.width(8.dp))
                ViewToggle(listView = listView, onToggle = { listView = it })
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
            val gridDimens = LocalPosDimens.current
            // Shared add-to-cart handler: box/WS items open the price picker, everything
            // else goes straight in at retail. Grid card and list row both call THIS —
            // no duplicated pricing logic.
            val onPick: (Item) -> Unit = { item ->
                val hasBox = item.boxSize > 1 && item.boxPrice > 0.0
                val hasWs = item.wholesalePrice > 0.0 && item.wholesalePrice != item.price
                when {
                    // Measured items always prompt for a decimal quantity of their unit.
                    item.productType == "measured" -> measuredItem = item
                    hasBox || hasWs -> priceModalItem = item
                    else -> vm.addToCart(item, "retail")
                }
            }
            if (listView) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(gridDimens.gridPadding),
                    verticalArrangement = Arrangement.spacedBy(gridDimens.gridSpacing)
                ) {
                    items(hits, key = { it.item.id }) { hit ->
                        val item = hit.item
                        val inCart = cart.filter { it.itemId == item.id }.sumOf { it.qty }.toInt()
                        ProductListRow(item, currency, inCart, hit.matchedTags) { onPick(item) }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridDimens.productColumns),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(gridDimens.gridPadding),
                    horizontalArrangement = Arrangement.spacedBy(gridDimens.gridSpacing),
                    verticalArrangement = Arrangement.spacedBy(gridDimens.gridSpacing)
                ) {
                    items(hits, key = { it.item.id }) { hit ->
                        val item = hit.item
                        val inCart = cart.filter { it.itemId == item.id }.sumOf { it.qty }.toInt()
                        ProductCard(item, currency, inCart, hit.matchedTags) { onPick(item) }
                    }
                }
            }
        }

        // Quote mode can't survive the grant being revoked mid-session, or the charge
        // button would still say "Generate quote" over a handler that now refuses.
        LaunchedEffect(canMakeQuotes) { if (!canMakeQuotes) quoteMode = false }
        CartBar(
            count = count,
            total = total,
            currency = currency,
            quoteMode = quoteMode,
            canQuote = canMakeQuotes,
            canPark = canParkSales,
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
    measuredItem?.let { item ->
        MeasuredQtyDialog(
            item = item,
            currency = currency,
            onConfirm = { qty -> vm.addMeasuredToCart(item, qty); measuredItem = null },
            onDismiss = { measuredItem = null }
        )
    }
    oversell?.let { prompt ->
        OversellDialog(
            prompt = prompt,
            onProceed = { vm.confirmOversell() },
            onDismiss = { vm.dismissOversell() }
        )
    }
    if (showCart) {
        CartDialog(
            cart, currency, vm,
            canGiveDiscounts = canGiveDiscounts,
            canPriceOverride = canPriceOverride,
            onDismiss = { showCart = false }
        )
    }
    if (showPayment) {
        PaymentDialog(
            business = business,
            subtotal = cart.sumOf { it.lineSubtotal },
            itemDiscount = cart.sumOf { it.lineDiscountApplied },
            itemMarkup = cart.sumOf { it.lineMarkupApplied },
            currency = currency,
            secondCode = prefs.secondCurrencyCode,
            secondRate = prefs.secondCurrencyRate,
            customers = customers,
            paynowAvailable = paynowReady,
            canGiveDiscounts = canGiveDiscounts,
            canSellOnCredit = canSellOnCredit,
            onCreateCustomer ={ name, onCreated -> vm.createCustomer(name, onCreated = onCreated) },
            onPaynowInitiate = { amount, onResult -> vm.paynowInitiate(amount, onResult) },
            onPaynowPoll = { reference, onResult -> vm.paynowPoll(reference, onResult) },
            onDismiss = { showPayment = false }
        ) { payments, discount, customer, onCredit, changeGiven, tillDiscrepancy ->
            showPayment = false
            // Discounts are RECORDED, never approved (§Job 2): the give_discounts permission
            // gate already decided whether a discount was allowed at all; the admin reviews
            // discounts given after the fact in the console rather than being interrupted here.
            vm.checkout(payments, discount, customer, onCredit, changeGiven, tillDiscrepancy)
        }
    }
    if (showQuote) {
        QuoteDialog(
            subtotal = cart.sumOf { it.lineSubtotal },
            itemDiscount = cart.sumOf { it.lineDiscountApplied },
            itemMarkup = cart.sumOf { it.lineMarkupApplied },
            currency = currency,
            customers = customers,
            validityDays = prefs.defaultQuoteValidityDays,
            canGiveDiscounts = canGiveDiscounts,
            onDismiss = { showQuote = false }
        ) { discount, customer ->
            showQuote = false
            vm.generateQuote(discount, customer)
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
 * A product image loaded from the on-device copy first (offline-first, instant),
 * falling back to the remote Supabase Storage URL (Coil memory/disk caches it).
 * Renders nothing when [model] is blank, so products with no image are unaffected.
 */
@Composable
private fun ProductImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    if (model.isNullOrBlank()) return
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape),
    )
}

/** The display model for an item's image: local copy first, else the remote URL. */
private val Item.imageModel: String? get() = imageLocalPath ?: imageUrl

/**
 * Web-faithful product card: white tile, retail price dominant in brand-600,
 * name, then Box / WS secondary prices, SKU at the foot. A cart-count bubble
 * (top-left) and stock badge (top-right) float over a reserved top band.
 */
@Composable
private fun ProductCard(
    item: Item,
    currency: String,
    inCart: Int,
    matchedTags: List<TagValue> = emptyList(),
    onClick: () -> Unit,
) {
    val t = LocalPosTokens.current
    val d = LocalPosDimens.current
    val tracked = item.trackStock
    val measured = item.isMeasured
    // Dimmed when there is nothing recorded on the shelf — but NOT unclickable for it.
    // A card that refuses the tap is a hard block, and the shop really does sell stock the
    // system has not caught up with; the tap now raises the oversell warning instead, which
    // states the figures and is itself gated on SELL_BELOW_STOCK. What stays a hard block is
    // [sellableBlocked]: a product ordered but never arrived isn't miscounted, it isn't here.
    val isOut = (tracked && item.onHand <= 0.0) || item.sellableBlocked
    // Measured items show their per-unit price (never a box/WS price).
    val hasBox = !measured && item.boxSize > 1 && item.boxPrice > 0.0
    val hasWs = !measured && item.wholesalePrice > 0.0
    val heroImage = item.imageModel?.takeIf { item.showImage }

    Box(
        Modifier
            .fillMaxWidth()
            .height(d.cardHeight)
            .clip(RoundedCornerShape(d.cardCorner))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(d.cardCorner))
            .alpha(if (isOut) 0.45f else 1f)
            .clickable(enabled = !item.sellableBlocked, onClick = onClick)
            .padding(d.cardPadding)
    ) {
        Box(Modifier.align(Alignment.TopEnd)) { StockBadge(item) }
        if (inCart > 0) {
            Box(
                Modifier.align(Alignment.TopStart).size(20.dp).clip(CircleShape).background(t.brand.s600),
                contentAlignment = Alignment.Center
            ) {
                BadgeNumber(inCart.toString(), color = t.inkOnBrand, fontSize = 10.sp)
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (heroImage != null) {
                // Photo hero. Badges float over its top corners; text sits below.
                ProductImage(
                    model = heroImage,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth().height(d.cardImageHeight),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(6.dp))
            } else {
                Spacer(Modifier.height(20.dp)) // reserved band for bubble + stock badge
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    money(if (measured) item.pricePerUnit else item.price, currency),
                    color = t.brand.s600, fontWeight = FontWeight.Black, fontSize = d.cardPriceSize, maxLines = 1
                )
                if (measured) {
                    Text(
                        "/${item.unit.trim().ifBlank { "unit" }}",
                        color = t.inkTertiary, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 2.dp, bottom = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.name,
                color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = d.cardNameSize,
                lineHeight = (d.cardNameSize.value * 1.25f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            // Only when the search found this product THROUGH a tag: without it a filter
            // for a Hilux appears in the results under a name that never says Hilux, and
            // reads as a wrong result rather than the right one.
            if (matchedTags.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    tagCaption(matchedTags),
                    color = t.accentBlue, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.weight(1f))
            if (item.productType == "set" || item.productType == "piece" || measured) {
                TypeBadge(item)
                Spacer(Modifier.height(3.dp))
            }
            if (hasBox) {
                Row {
                    Text("Box ", color = t.inkTertiary, fontSize = d.cardMetaSize)
                    Text(money(item.boxPrice, currency), color = t.inkSecondary, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold)
                }
            }
            if (hasWs) {
                Row {
                    Text("WS ", color = t.accentBlue, fontSize = d.cardMetaSize)
                    Text(money(item.wholesalePrice, currency), color = t.accentBlue, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold)
                }
            }
            item.sku?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = t.inkTertiary.copy(alpha = 0.7f), fontSize = (d.cardMetaSize.value * 0.9f).sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Grid ⇄ List view switch for the POS product browser. A tight two-icon segmented
 * pill (brand-filled active segment) matching the PosSegmented look, sized to sit
 * inline with the search field. Material icons only — no emoji.
 */
@Composable
private fun ViewToggle(listView: Boolean, onToggle: (Boolean) -> Unit) {
    val t = LocalPosTokens.current
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        @Composable
        fun seg(active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
            Box(
                Modifier
                    .size(width = 34.dp, height = 38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) t.brand.s600 else Color.Transparent)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = desc,
                    tint = if (active) t.inkOnBrand else t.inkSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        seg(!listView, Icons.Filled.GridView, "Grid view") { onToggle(false) }
        seg(listView, Icons.AutoMirrored.Filled.ViewList, "List view") { onToggle(true) }
    }
}

/**
 * Dense single-row product presentation for the POS list view. Same tap behaviour as
 * [ProductCard] (caller passes the shared add-to-cart handler): retail price prominent,
 * name, then Box/WS secondary prices inline, plus stock badge and cart-count bubble.
 * Deliberately short so many products are visible at once on the small handheld.
 */
@Composable
private fun ProductListRow(
    item: Item,
    currency: String,
    inCart: Int,
    matchedTags: List<TagValue> = emptyList(),
    onClick: () -> Unit,
) {
    val t = LocalPosTokens.current
    val d = LocalPosDimens.current
    val tracked = item.trackStock
    val measured = item.isMeasured
    // Dimmed, not disabled — see [ProductCard]. The oversell warning is the gate now.
    val isOut = (tracked && item.onHand <= 0.0) || item.sellableBlocked
    val hasBox = !measured && item.boxSize > 1 && item.boxPrice > 0.0
    val hasWs = !measured && item.wholesalePrice > 0.0
    val heroImage = item.imageModel?.takeIf { item.showImage }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = d.listRowMinHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .alpha(if (isOut) 0.45f else 1f)
            .clickable(enabled = !item.sellableBlocked, onClick = onClick)
            .padding(horizontal = d.listRowPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional thumbnail, or a cart-count bubble stand-in on the left edge.
        if (heroImage != null) {
            ProductImage(
                model = heroImage,
                contentDescription = item.name,
                modifier = Modifier.size(d.listThumb),
                shape = RoundedCornerShape(9.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inCart > 0) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(t.brand.s600),
                        contentAlignment = Alignment.Center
                    ) { BadgeNumber(inCart.toString(), color = t.inkOnBrand, fontSize = 9.sp) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    item.name,
                    color = t.inkPrimary, fontWeight = FontWeight.SemiBold,
                    fontSize = d.cardNameSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.productType == "set" || item.productType == "piece" || measured) {
                    Spacer(Modifier.width(6.dp))
                    TypeBadge(item)
                }
            }
            if (matchedTags.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    tagCaption(matchedTags),
                    color = t.accentBlue, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (hasBox || hasWs) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasBox) {
                        Text("Box ", color = t.inkTertiary, fontSize = d.cardMetaSize)
                        Text(money(item.boxPrice, currency), color = t.inkSecondary, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold)
                    }
                    if (hasBox && hasWs) Spacer(Modifier.width(10.dp))
                    if (hasWs) {
                        Text("WS ", color = t.accentBlue, fontSize = d.cardMetaSize)
                        Text(money(item.wholesalePrice, currency), color = t.accentBlue, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    money(if (measured) item.pricePerUnit else item.price, currency),
                    color = t.brand.s600, fontWeight = FontWeight.Black, fontSize = d.cardPriceSize, maxLines = 1
                )
                if (measured) {
                    Text(
                        "/${item.unit.trim().ifBlank { "unit" }}",
                        color = t.inkTertiary, fontSize = d.cardMetaSize, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 2.dp, bottom = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            StockBadge(item)
        }
    }
}

/** Stock pill: Recount (negative), Out (danger), Low<5 (warning + count), else faint count.
 *  Untracked → nothing. */
@Composable
private fun StockBadge(item: Item) {
    val t = LocalPosTokens.current
    if (!item.trackStock && item.pendingQty <= 0.0) return
    // Measured items are counted in their decimal unit (e.g. "2.5 kg"); everything else
    // is a whole-unit count. The low warning threshold (< 5) is unchanged.
    val units = item.onHand
    val suffix = if (item.isMeasured) " ${item.unit.trim().ifBlank { "unit" }}" else ""
    Column(horizontalAlignment = Alignment.End) {
        when {
            // A brand-new PO product with no arrived stock yet: not sellable — say so.
            item.pendingNew && units <= 0.0 -> Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(t.accentBlue.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("Pending", color = t.accentBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            !item.trackStock -> { /* untracked item with a pending addition: badge below only */ }
            // ★ BELOW ZERO is not "out". An empty shelf is an ordinary Tuesday; a shelf the
            // record says holds -2 is the record being wrong, and the only thing that fixes
            // it is somebody counting. Rendered SOLID rather than as the faint "Out" tint so
            // it can't be read past — the whole reason the owner's -2 went unnoticed is that
            // it wore the same badge as every product that had simply sold out.
            item.stockIsShort() -> Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(t.danger)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "Recount ${trimQty(units)}$suffix", color = Color.White,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
            units <= 0.0 -> Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(t.danger.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("Out", color = t.danger, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            units < 5.0 -> Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(t.warning.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("${trimQty(units)}$suffix", color = t.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            else -> Text("${trimQty(units)}$suffix", color = t.inkTertiary, fontSize = 9.sp)
        }
        // Incoming (ordered, not yet arrived) stock: "+N pending" hint (never sellable).
        if (item.pendingQty > 0.0 && !(item.pendingNew && units <= 0.0)) {
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(t.accentBlue.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("+${trimQty(item.pendingQty)} pending", color = t.accentBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Product-type pill. A Set (brand tint) or Piece (green) gets a small colour-coded
 *  badge so it's distinguishable at a glance; a Box is the default and shows nothing. */
@Composable
private fun TypeBadge(item: Item) {
    val t = LocalPosTokens.current
    val (label, color) = when (item.productType) {
        "set" -> "Set" to t.brand.s600
        "piece" -> "Piece" to t.success
        // Measured items badge with their unit (kg/L/m…) so the basis is obvious.
        "measured" -> item.unit.trim().ifBlank { "Unit" } to t.accentBlue
        else -> return
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
    /** make_quotes — hides the Sale/Quote toggle entirely when not held. */
    canQuote: Boolean = true,
    /** park_sales — hides "Hold" when not held. */
    canPark: Boolean = true,
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
        // Without make_quotes there is only one mode, so the toggle is dropped rather
        // than shown with a chip that leads to a handler which refuses.
        if (canQuote) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !quoteMode, onClick = { onToggleMode(false) }, label = { Text("Sale") })
                FilterChip(
                    selected = quoteMode, onClick = { onToggleMode(true) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Quote") }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = count > 0, onClick = onOpenCart)) {
                Text(
                    if (count == 0) "Cart empty" else "$count item${if (count == 1) "" else "s"} · tap to edit",
                    color = t.inkTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
                Text(money(total, currency), color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            if (!quoteMode && canPark) {
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
    itemDiscount: Double = 0.0,
    itemMarkup: Double = 0.0,
    currency: String,
    customers: List<CustomerWithBalance>,
    validityDays: Int,
    canGiveDiscounts: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (discount: Double, customer: Customer?) -> Unit
) {
    var customer by remember { mutableStateOf<Customer?>(null) }
    var discountText by remember { mutableStateOf("") }
    val netGoods = (subtotal - itemDiscount + itemMarkup).coerceAtLeast(0.0)
    // Cashiers without the give_discounts capability can't enter one.
    val discount = if (canGiveDiscounts) (discountText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, netGoods) else 0.0
    val total = (netGoods - discount).coerceAtLeast(0.0)
    PosContainedForm(
        title = "New quote",
        onDismiss = onDismiss,
        confirmLabel = "Generate quote",
        onConfirm = { onConfirm(discount, customer) }
    ) {
        val t = LocalPosTokens.current
        CustomerPicker(customers = customers, selected = customer, onSelect = { customer = it })
        PosFormCard {
            if (canGiveDiscounts) {
                PosField(
                    value = discountText,
                    onValueChange = { discountText = it },
                    label = "Discount ($currency)", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text("Quote total", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                Text(money(total, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
            }
        }
        Text(
            "Valid for $validityDays day${if (validityDays == 1) "" else "s"}. No payment is taken.",
            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
        )
    }
}

/**
 * Floating cart (web SessionHUD parity): a draggable pill that appears on non-Sell
 * cashier screens whenever the cart has items or held sales exist. Tap to expand a
 * mini-cart (qty ±, remove, clear, Go to POS) with a Held tab to resume a parked sale.
 */
@Composable
private fun BoxScope.FloatingCart(
    vm: PosViewModel,
    currency: String,
    onGoToSell: () -> Unit
) {
    val cart by vm.cart.collectAsState()
    val parked by vm.parkedSales.collectAsState()
    val count = cart.sumOf { it.qty }.toInt()
    val total = cart.sumOf { it.lineTotal }
    if (count == 0 && parked.isEmpty()) return

    val t = LocalPosTokens.current
    var expanded by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }            // 0 = cart, 1 = held
    var offset by remember { mutableStateOf(Offset.Zero) }
    // If the cart empties out while showing the cart tab and holds remain, flip tabs.
    if (count == 0 && tab == 0 && parked.isNotEmpty()) tab = 1

    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .padding(end = 16.dp, bottom = 84.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (expanded) {
            Card(
                Modifier.width(300.dp),
                colors = CardDefaults.cardColors(containerColor = t.surface1),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 },
                            label = { Text(if (count > 0) "Cart ($count)" else "Cart") })
                        if (parked.isNotEmpty()) FilterChip(selected = tab == 1, onClick = { tab = 1 },
                            label = { Text("Held (${parked.size})") })
                    }
                    Spacer(Modifier.height(8.dp))
                    if (tab == 1) {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            items(parked, key = { it.id }) { p ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .clickable { expanded = false; vm.resumeParked(p.id); onGoToSell() }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.customerName?.takeIf { it.isNotBlank() } ?: "Held sale",
                                            color = t.inkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(money(p.total, currency), color = t.inkTertiary, fontSize = 10.sp)
                                    }
                                    Text("Resume", color = t.brand.s600, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else if (cart.isEmpty()) {
                        Text("Cart is empty", color = t.inkTertiary, fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp))
                    } else {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(cart, key = { it.lineKey }) { line ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.name, color = t.inkPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${modeLabel(line.mode)} · ${money(line.unitPrice, currency)}",
                                            color = t.inkTertiary, fontSize = 10.sp)
                                    }
                                    QtyStepper(
                                        qty = line.qty.toInt(),
                                        onMinus = { vm.changeQty(line.lineKey, -1.0) },
                                        onPlus = { vm.changeQty(line.lineKey, +1.0) },
                                        onQtyClick = {}
                                    )
                                    IconButton(onClick = { vm.removeLine(line.lineKey) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Filled.Delete, "Remove", tint = t.inkTertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text("Total", Modifier.weight(1f), color = t.inkPrimary, fontWeight = FontWeight.Black)
                            Text(money(total, currency), color = t.brand.s600, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { expanded = false; onGoToSell() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
                        ) { Text("Go to POS") }
                        TextButton(onClick = { vm.clearCart() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Clear cart", color = t.inkTertiary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = t.brand.s600,
            contentColor = t.inkOnBrand,
            shadowElevation = 6.dp,
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = Offset(offset.x + dragAmount.x, offset.y + dragAmount.y)
                    }
                }
                .clickable { expanded = !expanded }
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = "Cart")
                Text(if (count > 0) money(total, currency) else "${parked.size} held", fontWeight = FontWeight.Bold)
                if (count > 0) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(t.inkOnBrand), contentAlignment = Alignment.Center) {
                        Text("$count", color = t.brand.s600, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
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
    PosDialog(title = "Held sales", onDismiss = onDismiss) {
        if (parked.isEmpty()) {
            Text("No held sales.", color = t.inkTertiary)
        } else {
            parked.forEach { sale ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                        .clickable { onResume(sale.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            sale.customerName?.takeIf { it.isNotBlank() }
                                ?: "Held #${sale.id.takeLast(6).uppercase()}",
                            fontWeight = FontWeight.Bold, color = t.inkPrimary
                        )
                        sale.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                        }
                    }
                    Text(money(sale.total, currency), fontWeight = FontWeight.Bold, color = t.inkPrimary)
                }
            }
        }
    }
}

/** Box / Wholesale / Retail picker — mirrors the web price modal. */
@Composable
private fun PriceModeDialog(item: Item, currency: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val hasBox = item.boxSize > 1 && item.boxPrice > 0.0
    val hasWs = item.wholesalePrice > 0.0
    PosDialog(title = item.name, onDismiss = onDismiss) {
        item.sku?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = t.inkTertiary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Text("Select price type", color = t.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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

/**
 * Decimal-quantity entry for a MEASURED (unit-priced) product. The cashier types how
 * much of the unit they're selling (e.g. 2.35 kg); the line price previews live as
 * quantity × pricePerUnit. Confirm adds a measured line to the cart.
 */
@Composable
private fun MeasuredQtyDialog(
    item: Item,
    currency: String,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalPosTokens.current
    val unitLabel = item.unit.trim().ifBlank { "unit" }
    var text by remember { mutableStateOf("") }
    val qty = text.replace(',', '.').toDoubleOrNull()
    val valid = qty != null && qty > 0
    val lineTotal = (qty ?: 0.0) * item.pricePerUnit
    PosContainedForm(
        title = item.name,
        onDismiss = onDismiss,
        confirmLabel = "Add to cart",
        confirmEnabled = valid,
        onConfirm = { onConfirm(qty ?: 0.0) }
    ) {
        Text(
            "${money(item.pricePerUnit, currency)} per $unitLabel",
            color = t.inkSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
        if (item.trackStock) {
            Text(
                "On hand: ${trimQty(item.stockMeasured)} $unitLabel",
                color = t.inkTertiary, fontSize = 12.sp
            )
        }
        PosFormCard {
            PosField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                label = "Quantity ($unitLabel)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Line total: ${money(lineTotal, currency)}",
                color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp
            )
        }
    }
}

/**
 * The oversell warning: this cart takes more off the shelf than the shop's figure says is
 * there. Raised at the moment the quantity crosses, not held back to the payment screen,
 * because the point of it is to be answerable while the customer is still standing there.
 *
 * Two readings, and the difference matters at the counter:
 *  - the ordinary case is a shelf that has run down or a delivery not yet booked in, and
 *    the cashier is told the two numbers and sells anyway if that is what is true;
 *  - [Oversell.alreadyShort] means the record is ALREADY below zero, i.e. this has happened
 *    before and nobody has counted since. That is a data problem, so it says so and asks
 *    for a stock take rather than implying the shelf is merely empty.
 *
 * Without [Capability.SELL_BELOW_STOCK] the same figures show with the confirm disabled —
 * the cashier still learns what is wrong and can go and ask, which is the difference
 * between a gate and a dead button.
 */
@Composable
private fun OversellDialog(
    prompt: PosViewModel.OversellPrompt,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalPosTokens.current
    val d = prompt.detail
    // Measured products read in their own unit ("1.5 kg"); everything else is a bare count.
    val suffix = if (d.measured) " ${d.unitLabel}" else ""
    PosContainedForm(
        title = if (d.alreadyShort) "Stock is already short" else "More than you have",
        onDismiss = onDismiss,
        confirmLabel = if (prompt.allowed) "Sell anyway" else "Needs the owner",
        confirmEnabled = prompt.allowed,
        dismissLabel = if (prompt.allowed) "Cancel" else "OK",
        onConfirm = onProceed,
    ) {
        Text(d.itemName, color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        PosFormCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("On hand", color = t.inkTertiary, fontSize = 12.sp)
                    Text(
                        "${trimQty(d.onHand)}$suffix",
                        color = if (d.onHand < 0) t.danger else t.inkPrimary,
                        fontWeight = FontWeight.Black, fontSize = 20.sp
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Selling", color = t.inkTertiary, fontSize = 12.sp)
                    Text(
                        "${trimQty(d.requested)}$suffix",
                        color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp
                    )
                }
            }
            Text(
                "Short by ${trimQty(d.shortfall)}$suffix",
                color = t.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
        }
        Text(
            if (d.alreadyShort)
                "This product is already recorded below zero, so the count is wrong — " +
                    "count the shelf and correct it in Inventory."
            else
                "Selling this will put the product below zero until someone counts the shelf.",
            color = t.inkSecondary, fontSize = 12.sp
        )
        if (!prompt.allowed) {
            Text(
                "You can't sell past the recorded stock. Ask the owner to count it in, " +
                    "or to allow it for you.",
                color = t.danger, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
        }
    }
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
private fun CartDialog(
    cart: List<CartLine>,
    currency: String,
    vm: PosViewModel,
    canGiveDiscounts: Boolean = true,
    canPriceOverride: Boolean = true,
    onDismiss: () -> Unit,
) {
    val t = LocalPosTokens.current
    var editingQtyLine by remember { mutableStateOf<CartLine?>(null) }
    var editingDiscountLine by remember { mutableStateOf<CartLine?>(null) }
    var editingMarkupLine by remember { mutableStateOf<CartLine?>(null) }
    PosDialog(title = "Cart", onDismiss = onDismiss) {
        if (cart.isEmpty()) {
            Text("Cart is empty", color = t.inkTertiary)
        } else {
            cart.forEach { line ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, fontWeight = FontWeight.SemiBold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (line.measured)
                                "${trimQty(line.qty)} ${line.unitLabel} × ${money(line.unitPrice, currency)}"
                            else "${modeLabel(line.mode)} · ${money(line.unitPrice, currency)}/ea",
                            color = t.inkTertiary, fontSize = 11.sp
                        )
                        // Per-item discount affordance (tap to set/edit). Hidden entirely
                        // for a cashier without the give_discounts capability.
                        if (canGiveDiscounts) {
                            Text(
                                if (line.lineDiscountApplied > 0)
                                    "Less ${money(line.lineDiscountApplied, currency)} — edit"
                                else "Add discount",
                                color = if (line.lineDiscountApplied > 0) t.danger else t.inkTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { editingDiscountLine = line }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                            )
                        }
                        // Per-item markup affordance (tap to set/edit) — mirror of the
                        // discount above, but ADDS to the line. Hidden the same way, on
                        // price_override: marking a line up is the same discretion as
                        // marking it down, and it rendered unconditionally before.
                        if (canPriceOverride) {
                            Text(
                                if (line.lineMarkupApplied > 0)
                                    "Plus ${money(line.lineMarkupApplied, currency)} — edit"
                                else "Add markup",
                                color = if (line.lineMarkupApplied > 0) t.accentBlue else t.inkTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { editingMarkupLine = line }
                                    .padding(vertical = 2.dp, horizontal = 2.dp)
                            )
                        }
                    }
                    QtyStepper(
                        qty = line.qty.toInt(),
                        onMinus = { vm.changeQty(line.lineKey, -1.0) },
                        onPlus = { vm.changeQty(line.lineKey, +1.0) },
                        onQtyClick = { editingQtyLine = line }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        if (line.lineDiscountApplied > 0) {
                            Text(
                                money(line.lineGross, currency),
                                color = t.inkTertiary, fontSize = 10.sp,
                                textDecoration = TextDecoration.LineThrough
                            )
                        }
                        Text(money(line.lineTotal, currency), fontWeight = FontWeight.Black, color = t.inkPrimary, fontSize = 13.sp)
                    }
                    IconButton(onClick = { vm.removeLine(line.lineKey) }) {
                        Icon(Icons.Filled.Delete, "Remove", tint = t.inkTertiary)
                    }
                }
            }
            HorizontalDivider(color = t.surfaceBorder)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { vm.clearCart(); onDismiss() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
                ) { Text("Clear all") }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
                ) { Text("Done") }
            }
        }
    }

    editingQtyLine?.let { line ->
        SetQtyDialog(
            line = line,
            onDismiss = { editingQtyLine = null },
            onConfirm = { qty -> vm.setQty(line.lineKey, qty); editingQtyLine = null }
        )
    }

    editingDiscountLine?.let { line ->
        // Re-read the live line so the dialog reflects the latest qty/price.
        val current = cart.firstOrNull { it.lineKey == line.lineKey } ?: line
        SetLineDiscountDialog(
            line = current,
            currency = currency,
            maxDiscount = vm.maxItemDiscount,
            onDismiss = { editingDiscountLine = null },
            onConfirm = { amount -> vm.setLineDiscount(line.lineKey, amount); editingDiscountLine = null }
        )
    }

    editingMarkupLine?.let { line ->
        // Re-read the live line so the dialog reflects the latest qty/price.
        val current = cart.firstOrNull { it.lineKey == line.lineKey } ?: line
        SetLineMarkupDialog(
            line = current,
            currency = currency,
            onDismiss = { editingMarkupLine = null },
            onConfirm = { amount -> vm.setLineMarkup(line.lineKey, amount); editingMarkupLine = null }
        )
    }
}

/**
 * Per-item discount entry (§ per-line fixed discount). The cashier types a currency
 * amount off this one line; it's clamped to the line's own value and to the admin's
 * [maxDiscount] ceiling (0 = no ceiling). Confirm with 0 to clear the discount.
 */
@Composable
private fun SetLineDiscountDialog(
    line: CartLine,
    currency: String,
    maxDiscount: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val t = LocalPosTokens.current
    var text by remember { mutableStateOf(if (line.lineDiscount > 0) trimQty(line.lineDiscount) else "") }
    val typed = text.replace(',', '.').toDoubleOrNull() ?: 0.0
    // The hard ceiling: the smaller of the line's value and any admin cap.
    val ceiling = if (maxDiscount > 0.0) minOf(line.lineGross, maxDiscount) else line.lineGross
    val amount = typed.coerceIn(0.0, ceiling)
    val overCeiling = typed > ceiling + 0.0001
    PosContainedForm(
        title = "Line discount",
        onDismiss = onDismiss,
        confirmLabel = "Apply",
        onConfirm = { onConfirm(amount) }
    ) {
        Text(line.name, color = t.inkSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${trimQty(line.qty)} × ${money(line.unitPrice, currency)} = ${money(line.lineGross, currency)}",
            color = t.inkTertiary, fontSize = 12.sp
        )
        PosFormCard {
            PosField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                label = "Discount ($currency off this line)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            if (maxDiscount > 0.0) {
                Text(
                    "Max ${money(maxDiscount, currency)} per item (set by admin).",
                    color = if (overCeiling) t.danger else t.inkTertiary, fontSize = 11.sp
                )
            }
            Text(
                "Line total: ${money(line.lineGross - amount, currency)}",
                color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }
    }
}

/**
 * Per-item markup entry — the mirror of [SetLineDiscountDialog] that ADDS a currency
 * amount to this one line instead of taking it off. There is no cap (markup has no
 * admin ceiling), so the typed amount is only floored at 0. Confirm with 0 to clear.
 */
@Composable
private fun SetLineMarkupDialog(
    line: CartLine,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val t = LocalPosTokens.current
    var text by remember { mutableStateOf(if (line.lineMarkup > 0) trimQty(line.lineMarkup) else "") }
    val typed = text.replace(',', '.').toDoubleOrNull() ?: 0.0
    val amount = typed.coerceAtLeast(0.0)
    PosContainedForm(
        title = "Line markup",
        onDismiss = onDismiss,
        confirmLabel = "Apply",
        onConfirm = { onConfirm(amount) }
    ) {
        Text(line.name, color = t.inkSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${trimQty(line.qty)} × ${money(line.unitPrice, currency)} = ${money(line.lineGross, currency)}",
            color = t.inkTertiary, fontSize = 12.sp
        )
        PosFormCard {
            PosField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                label = "Markup ($ added to this line)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Line total: ${money(line.lineGross + amount, currency)}",
                color = t.inkPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
            )
        }
    }
}

/** Tap-the-number fast quantity entry for a cart line (type an exact amount). */
@Composable
private fun SetQtyDialog(line: CartLine, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    val t = LocalPosTokens.current
    var text by remember { mutableStateOf(trimQty(line.qty)) }
    val qty = text.replace(',', '.').toDoubleOrNull()
    val valid = qty != null && qty > 0
    PosContainedForm(
        title = "Quantity",
        onDismiss = onDismiss,
        confirmLabel = "Set",
        confirmEnabled = valid,
        onConfirm = { onConfirm(qty ?: 0.0) }
    ) {
        Text(line.name, color = t.inkSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PosFormCard {
            PosField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                label = "Quantity", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
            )
        }
    }
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

/** Sync page (its own bottom-nav destination, like the web). In local (no-cloud)
 *  mode it becomes the discoverable entry point to turn cloud on; once connected it
 *  shows the bring-your-own-database sync panel. */
@Composable
private fun SyncScreen(vm: PosViewModel) {
    val appMode by vm.appMode.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // ★ The database panel shows in BOTH modes.
        //
        // It used to render only in Cloud mode, which put a staff login in front of the
        // one screen you need to connect a database — so an owner with a database and no
        // account yet could not reach it, and the app gave no hint that the two were
        // even related. That is the same trap as the cashier lock-screen: a credential
        // gate in front of the thing you need in order to create the credential.
        //
        // The gate was never a database requirement. Signing in buys per-cashier
        // ATTRIBUTION (createdBy on each sale), not access: with no account on the
        // device the anon key is the intended identity, and this schema's row-level
        // security passes it on the `auth_org_id() IS NULL` branch for every table.
        //
        // So a local-mode device can sync fully. What it cannot do is say WHICH cashier
        // rang up a sale — which is exactly what the Connect-cloud card below is for,
        // and why that card stays offered rather than being replaced.
        CloudSyncSection(vm)
        if (appMode != AppMode.Cloud) {
            Spacer(Modifier.height(24.dp))
            SettingsSectionHeader("Cloud & staff accounts")
            ConnectCloudCard(vm)
        }
    }
}

@Composable
private fun PaymentDialog(
    business: Business,
    subtotal: Double,
    itemDiscount: Double = 0.0,
    itemMarkup: Double = 0.0,
    currency: String,
    secondCode: String = "",
    secondRate: Double = 0.0,
    customers: List<CustomerWithBalance>,
    paynowAvailable: Boolean = false,
    canGiveDiscounts: Boolean = true,
    /** sell_on_credit — without it the "put it on account" switch is not offered and the
     *  sale must be covered by tender. [PosViewModel.checkout] refuses a credit sale too. */
    canSellOnCredit: Boolean = true,
    onCreateCustomer: (name: String, onCreated: (Customer) -> Unit) -> Unit = { _, _ -> },
    onPaynowInitiate: (amount: Double, onResult: (PaynowInit) -> Unit) -> Unit = { _, _ -> },
    onPaynowPoll: (reference: String, onResult: (PaynowPoll) -> Unit) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onConfirm: (payments: List<Tender>, discount: Double, customer: Customer?, onCredit: Boolean, changeGiven: Double, tillDiscrepancy: Boolean) -> Unit
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
    // When there's overpayment change, completing opens a prompt to capture how much
    // change was actually handed over now (the rest is recorded as change owed, or —
    // if the cashier over-gives — as customer debt).
    var showChangePrompt by remember { mutableStateOf(false) }
    // Walk-in (no customer) left with a change imbalance: the cashier must choose to
    // attach a customer or book it as a till discrepancy. Non-null => that prompt is up.
    var walkInImbalance by remember { mutableStateOf<WalkInImbalance?>(null) }
    // Double-tap guard: the instant we hand a sale off to be completed we disable the
    // confirm control so a second tap (before the dialog recomposes away) can't fire a
    // duplicate checkout. Belt-and-suspenders alongside the ViewModel in-flight guard.
    var submitting by remember { mutableStateOf(false) }

    // Dual-currency: the shop keeps its books in [currency] but may also take tender
    // in a SECOND currency (e.g. ZiG). entryCur2 = the cashier is typing the amount in
    // that second currency; we convert it to base so every coverage/change figure —
    // and every stored Tender.amount — stays in the base currency.
    val cur2On = secondCurrencyActive(secondCode, secondRate)
    var entryCur2 by remember { mutableStateOf(false) }

    // Business-level VAT mirrors PosRepository.checkout(): tax on the discounted +
    // marked-up base. Per-item discounts ([itemDiscount]) already came off and per-item
    // markups ([itemMarkup]) already went on before this whole-sale discount; the
    // taxable base is the goods value net of both, plus markup.
    val netGoods = (subtotal - itemDiscount + itemMarkup).coerceAtLeast(0.0)
    // Cashiers without give_discounts can't apply a whole-sale discount.
    val discount = if (canGiveDiscounts) (discountText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, netGoods) else 0.0
    val taxableBase = netGoods - discount
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
    val creditValid = onCredit && selected != null && canSellOnCredit
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

    PosContainedForm(
        title = "Take payment",
        onDismiss = onDismiss,
        confirmLabel = if (!fullyPaid && creditValid) "Charge to credit" else "Complete sale",
        confirmEnabled = valid && !submitting,
        onConfirm = {
            // Overpayment => prompt for the change actually given before completing;
            // otherwise complete straight away with no change to reconcile. Mark
            // submitting on the direct path so the button can't be tapped twice; the
            // change-prompt path defers completion to that dialog's own confirm.
            if (overpay > 0.0) {
                showChangePrompt = true
            } else {
                submitting = true
                onConfirm(tenders.toList(), discount, selected, onCredit && remaining > 0.0, 0.0, false)
            }
        }
    ) {
        val t = LocalPosTokens.current
        TotalRow("Subtotal", money(subtotal, currency))
        if (itemDiscount > 0) {
            TotalRow("Item discounts", "-${money(itemDiscount, currency)}")
        }
        if (itemMarkup > 0) {
            TotalRow("Item markups", "+${money(itemMarkup, currency)}")
        }
        if (canGiveDiscounts) {
            PosField(
                value = discountText,
                onValueChange = { discountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = "Discount (optional)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            if (discount > 0) {
                TotalRow("Discount", "-${money(discount, currency)}")
            }
        }
        if (business.vatEnabled) {
            TotalRow("VAT (${trimPct(business.vatPercent)}%)", money(vat, currency))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total due", fontWeight = FontWeight.Bold, color = t.inkPrimary)
            Text(money(total, currency), fontWeight = FontWeight.Black, fontSize = 22.sp, color = t.inkPrimary)
        }
        if (cur2On) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "≈ ${money(baseToSecond(total, secondRate), secondCode)} @ ${trimPct(secondRate)}",
                    fontSize = 12.sp, color = t.inkTertiary
                )
            }
        }

        // Customer — searchable, with create-on-the-fly from a typed name.
        CustomerSearchField(
            customers = customers,
            selected = selected,
            onSelect = { selected = it },
            onCreate = { name -> onCreateCustomer(name) { selected = it } }
        )

        // ── Tenders collected so far ──
        if (tenders.isNotEmpty()) {
            tenders.forEachIndexed { idx, tn ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        PaymentMethod.fromCode(tn.method)?.label ?: tn.method,
                        modifier = Modifier.weight(1f), color = t.inkPrimary
                    )
                    val tc = tn.currency
                    val ta = tn.tenderAmount
                    if (tc != null && ta != null) {
                        // Taken in the second currency: show what was handed over,
                        // with the base-currency equivalent (the booked value) beneath.
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(ta, tc), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                            Text("= ${money(tn.amount, currency)}", fontSize = 12.sp, color = t.inkTertiary)
                        }
                    } else {
                        Text(money(tn.amount, currency), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                    }
                    IconButton(onClick = { tenders.removeAt(idx) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove payment", tint = t.inkTertiary)
                    }
                }
            }
            HorizontalDivider(color = t.surfaceBorder)
            TotalRow("Paid", money(paid, currency))
            if (remaining > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Remaining", fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                    Text(money(remaining, currency), fontWeight = FontWeight.SemiBold, color = t.danger)
                }
                if (cur2On) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("or ${money(baseToSecond(remaining, secondRate), secondCode)}", fontSize = 12.sp, color = t.inkTertiary)
                    }
                }
            } else if (overpay > 0) {
                TotalRow("Change", money(overpay, currency))
                if (cur2On) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("or ${money(baseToSecond(overpay, secondRate), secondCode)}", fontSize = 12.sp, color = t.inkTertiary)
                    }
                }
            }
        }

        // ── Exact payment: opt-in "I gave change anyway" ──
        // No change is due, so the change prompt never opens on its own and the normal
        // exact-payment sale still completes in one tap. If the cashier DID hand money
        // back by mistake, this opens the same prompt with a change due of zero —
        // whatever is entered books as "over-given (customer owes)".
        if (tenders.isNotEmpty() && fullyPaid && overpay <= 0.005) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { showChangePrompt = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = t.brand.s600)
                ) { Text("Record change given") }
            }
        }

        // ── Add a tender (hidden once the total is fully covered) ──
        if (remaining > 0 || tenders.isEmpty()) {
            PosSectionLabel(if (tenders.isEmpty()) "Payment" else "Add another payment")
            if (methods.size > 1) {
                PaymentMethodPicker(methods, method) { method = it; referenceText = "" }
            }
            // Pay-into account details for manual methods (bank / mobile money).
            business.payInstructions(method)?.let { instr ->
                PayInstructionsCard(instr)
            }
            // Currency toggle — which currency the cashier is keying this tender in.
            if (canPickCur2) {
                PosSegmented(
                    options = listOf(currency to currency, secondCode to secondCode),
                    selected = if (entryCur2) secondCode else currency,
                    onSelect = { entryCur2 = it == secondCode }
                )
            }
            PosField(
                value = amountText,
                onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = if (useCur2) "${method.label} amount ($secondCode)" else "${method.label} amount",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            // When keying the second currency, echo the base value that gets booked.
            val baseEquiv = enteredAmount?.let { secondToBase(it, secondRate) }
            if (useCur2 && baseEquiv != null && baseEquiv > 0.0) {
                Text("= ${money(baseEquiv, currency)}", fontSize = 12.sp, color = t.inkTertiary)
            }
            // Quick-fill: exact remaining + rounded notes, in the entry currency.
            val quickBasis = if (useCur2) baseToSecond(remaining, secondRate) else remaining
            val quickCur = if (useCur2) secondCode else currency
            val quick = remember(quickBasis) { quickAmounts(quickBasis) }
            if (quick.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quick.forEachIndexed { i, amt ->
                        QuickAmountPill(
                            label = if (i == 0) "Exact ${money(amt, quickCur)}" else money(amt, quickCur),
                            onClick = { amountText = trimAmount(amt) }
                        )
                    }
                }
            }
            // Paynow online: generate a QR and watch for payment live.
            if (method == PaymentMethod.PAYNOW && paynowAvailable) {
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
                PosField(
                    value = referenceText,
                    onValueChange = { referenceText = it },
                    label = "${method.label} reference",
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
        if (selected != null && remaining > 0 && canSellOnCredit) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Put ${money(remaining, currency)} on ${selected!!.name}'s account",
                    modifier = Modifier.weight(1f).padding(end = 12.dp), color = t.inkPrimary
                )
                Switch(checked = onCredit, onCheckedChange = { onCredit = it })
            }
            // Advisory credit-limit warning: this is NOT a block — the cashier can still
            // complete, and the admin is informed afterwards (over-limit notification).
            if (onCredit) {
                val bal = customers.firstOrNull { it.customer.id == selected!!.id }?.balance ?: 0.0
                val limit = selected!!.creditLimit
                if (limit != null && bal + remaining > limit + 0.005) {
                    Text(
                        "Over ${selected!!.name}'s ${money(limit, currency)} credit limit — they'd owe ${money(bal + remaining, currency)}. The sale can still go through; an admin will be notified.",
                        color = t.warning, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (remaining > 0 && (selected == null || !canSellOnCredit)) {
            Text(
                if (canSellOnCredit)
                    "Add payment to cover the total, or pick a customer to sell on credit."
                else "Add payment to cover the total. You can't sell on credit.",
                color = t.danger, fontSize = 12.sp
            )
        }
    }

    // Finalise a sale once the change has been reconciled. [cust] is who any imbalance
    // attaches to (may be a customer chosen in the walk-in prompt); [till] books an
    // unattached imbalance as a till discrepancy instead.
    fun complete(given: Double, cust: Customer?, till: Boolean) {
        submitting = true
        onConfirm(tenders.toList(), discount, cust, onCredit && remaining > 0.0, given, till)
    }

    // Overpayment => capture how much change was actually handed back now. Under-giving
    // leaves change owed (shop owes); over-giving leaves the customer owing the shop.
    if (showChangePrompt) {
        ChangePromptDialog(
            changeDue = overpay,
            currency = currency,
            onDismiss = { showChangePrompt = false },
            onConfirm = { given ->
                showChangePrompt = false
                val owed = (overpay - given).coerceAtLeast(0.0)   // shop owes customer
                val over = (given - overpay).coerceAtLeast(0.0)   // customer owes shop
                val imbalanced = owed > 0.005 || over > 0.005
                if (imbalanced && selected == null) {
                    // No customer to carry it — ask the cashier to attach one or record
                    // it as a till discrepancy.
                    walkInImbalance = WalkInImbalance(
                        customerOwes = over > 0.005,
                        amount = if (over > 0.005) over else owed,
                        changeGiven = given
                    )
                } else {
                    complete(given, selected, false)
                }
            }
        )
    }

    // Walk-in imbalance: attach a customer (the imbalance posts to their ledger) or book
    // it as a till shortage/overage the admin can see in the audit log.
    walkInImbalance?.let { wi ->
        WalkInImbalanceDialog(
            info = wi,
            currency = currency,
            customers = customers,
            onCreateCustomer = onCreateCustomer,
            onAttach = { cust -> walkInImbalance = null; complete(wi.changeGiven, cust, false) },
            onTill = { walkInImbalance = null; complete(wi.changeGiven, null, true) },
            onDismiss = { walkInImbalance = null }
        )
    }
}

/** A change imbalance on a walk-in sale awaiting the cashier's attribution choice. */
private data class WalkInImbalance(
    val customerOwes: Boolean,   // true => cashier over-gave (customer owes); false => shop owes change
    val amount: Double,
    val changeGiven: Double
)

/**
 * Prompt shown when a sale overpays — or opened by hand on an exact-payment sale, in
 * which case [changeDue] is 0 and anything entered is over-given. It states the change
 * due and asks how much the
 * cashier is actually handing over now (blank => 0). The entry is NOT capped at the
 * change due — under-giving books the remainder as change owed (shop owes), and
 * over-giving books the excess as a debt (customer owes). The parent decides where
 * an imbalance is attributed.
 */
@Composable
private fun ChangePromptDialog(
    changeDue: Double,
    currency: String,
    onDismiss: () -> Unit,
    onConfirm: (changeGiven: Double) -> Unit
) {
    var givenText by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val entered = givenText.toDoubleOrNull()
    // Blank is valid (=> 0 given, full change owed); a typed value must be non-negative.
    val valid = givenText.isBlank() || (entered != null && entered >= 0.0)
    val given = (entered ?: 0.0).coerceAtLeast(0.0)
    val owed = (changeDue - given).coerceAtLeast(0.0)   // shop owes the customer
    val over = (given - changeDue).coerceAtLeast(0.0)   // customer owes the shop

    PosContainedForm(
        // changeDue == 0 => the cashier opened this deliberately on an exact-payment
        // sale to record money handed back; title it for what they're doing.
        title = if (changeDue > 0.005) "Change to give" else "Record change given",
        onDismiss = onDismiss,
        confirmLabel = "Complete sale",
        confirmEnabled = valid && !submitting,
        onConfirm = { submitting = true; onConfirm(given) }
    ) {
        val t = LocalPosTokens.current
        TotalRow("Change due", money(changeDue, currency))
        PosField(
            value = givenText,
            onValueChange = { givenText = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = "Change given now",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        if (owed > 0.005) {
            TotalRow("Change owed (you owe)", money(owed, currency))
        }
        if (over > 0.005) {
            TotalRow("Over-given (customer owes)", money(over, currency))
            Text(
                "You're handing back ${money(over, currency)} more than the change due — the customer will owe it back.",
                color = t.warning, fontSize = 12.sp
            )
        }
    }
}

/**
 * Walk-in change imbalance: a sale with no customer left money owed one way or the
 * other. The cashier either attaches/creates a customer (the imbalance posts to that
 * customer's ledger) or records it as an un-attributed till shortage/overage that
 * lands in the admin audit log.
 */
@Composable
private fun WalkInImbalanceDialog(
    info: WalkInImbalance,
    currency: String,
    customers: List<CustomerWithBalance>,
    onCreateCustomer: (name: String, onCreated: (Customer) -> Unit) -> Unit,
    onAttach: (Customer) -> Unit,
    onTill: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalPosTokens.current
    var picked by remember { mutableStateOf<Customer?>(null) }
    val tillLabel = if (info.customerOwes) "Record as till shortage" else "Record as till overage"

    PosContainedForm(
        title = "Who owes this?",
        onDismiss = onDismiss,
        confirmLabel = picked?.let { "Put on ${it.name}" } ?: "Attach a customer",
        confirmEnabled = picked != null,
        onConfirm = { picked?.let(onAttach) }
    ) {
        Text(
            if (info.customerOwes)
                "The customer owes ${money(info.amount, currency)} (you handed back more change than was due)."
            else
                "You still owe the customer ${money(info.amount, currency)} in change.",
            color = t.inkPrimary
        )
        Text(
            "This sale has no customer. Attach one so it's tracked, or record it against the till.",
            color = t.inkSecondary, fontSize = 12.sp
        )
        // Pick or create a customer to carry the imbalance.
        CustomerSearchField(
            customers = customers,
            selected = picked,
            onSelect = { picked = it },
            onCreate = { name -> onCreateCustomer(name) { picked = it } }
        )
        // Or leave it unattached as a till discrepancy the admin sees in the audit log.
        OutlinedButton(onClick = onTill, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PointOfSale, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(tillLabel)
        }
    }
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

/**
 * The quantity at or below which an item counts as LOW — the single rule behind the
 * inventory badge and the dashboard tile, and deliberately identical to the one
 * [com.portionspot.pos.notify.NotificationEngine] applies. When these drifted apart the
 * app contradicted itself about the same shelf, which is exactly how the owner ended up
 * with "Low stock" alerts for products that were plainly full.
 *
 * ★ A measured item (kg/L/m) gets NO default. The bare 5 means five of something, and
 * five litres of cooking oil next to five metres of hose share a number and nothing else
 * — one of them is absurd whichever number you pick. The product form gives measured
 * items their own "Reorder at (kg)" field, so with none set the item is never low; it is
 * only ever OUT, which is unambiguous in any unit.
 */
private fun lowStockLevel(item: Item): Double = when {
    item.reorderLevel > 0.0 -> item.reorderLevel
    item.isMeasured -> 0.0
    else -> LOW_STOCK_THRESHOLD
}

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

    val t = LocalPosTokens.current
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

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            paid -> {
                Icon(
                    Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = t.success,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("Payment received", fontWeight = FontWeight.Bold, color = t.inkPrimary)
                Text(
                    "Press \"Mark paid\" to finish the sale.",
                    fontSize = 12.sp, color = t.inkTertiary,
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
                Text("Scan to pay ${money(amount, currency)}", fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.brand.s600)
                    Spacer(Modifier.width(8.dp))
                    Text(paynowStatusLabel(statusText), fontSize = 12.sp, color = t.inkSecondary)
                }
            }
            else -> {
                Text(
                    "Show a Paynow QR the customer can scan with their phone.",
                    fontSize = 12.sp, color = t.inkTertiary,
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
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
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
                color = t.danger,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
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
    val t = LocalPosTokens.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(instr.title, fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
        Spacer(Modifier.height(4.dp))
        instr.lines.forEach { (label, value) -> TotalRow(label, value) }
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
    val t = LocalPosTokens.current
    if (selected != null) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = t.inkSecondary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(selected.name, fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                if (selected.wholesale) {
                    Text("Wholesale", fontSize = 11.sp, color = t.accentBlue)
                }
            }
            IconButton(onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear customer", tint = t.inkTertiary)
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
        PosField(
            value = query,
            onValueChange = { query = it },
            label = "Customer (optional)",
            placeholder = "Search or type a new name",
            modifier = Modifier.fillMaxWidth()
        )
        if (trimmed.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface2)
                    .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
            ) {
                matches.forEach { cb ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cb.customer); query = "" }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(cb.customer.name, modifier = Modifier.weight(1f), color = t.inkPrimary)
                        cb.customer.phone?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 11.sp, color = t.inkTertiary)
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
                        Icon(Icons.Filled.Add, contentDescription = null, tint = t.brand.s600)
                        Spacer(Modifier.width(8.dp))
                        Text("Create \"$trimmed\"", fontWeight = FontWeight.SemiBold, color = t.brand.s600)
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = t.inkSecondary)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.inkPrimary)
    }
}

/** Small brand-tinted tap pill for the payment quick-fill amounts (replaces AssistChip). */
@Composable
private fun QuickAmountPill(label: String, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(t.brand.s600.copy(alpha = 0.10f))
            .border(1.dp, t.brand.s600.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = t.brand.s600, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
    SettingsSwitch("Show cashier", prefs.receiptShowCashier) { onChange(prefs.copy(receiptShowCashier = it)) }
    SettingsSwitch("Show payment", prefs.receiptShowPayment) { onChange(prefs.copy(receiptShowPayment = it)) }
    SettingsSwitch("Show change", prefs.receiptShowChange) { onChange(prefs.copy(receiptShowChange = it)) }
    SettingsSwitch("Bold totals", prefs.receiptBoldTotals) { onChange(prefs.copy(receiptBoldTotals = it)) }
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
    PosContainedForm(
        title = if (isQuote) "Quote ready" else "Sale complete",
        onDismiss = onDismiss,
        confirmLabel = if (isQuote) "Done" else "New sale",
        onConfirm = onDismiss
    ) {
        val t = LocalPosTokens.current
        Text(
            "${if (isQuote) "Quote" else "Receipt"} #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}",
            color = t.inkPrimary, fontWeight = FontWeight.Bold
        )
        sale.customerName?.takeIf { it.isNotBlank() }?.let {
            Text("Customer: $it", style = MaterialTheme.typography.bodySmall, color = t.inkSecondary)
        }
        if (isQuote) sale.validUntil?.let {
            val vu = remember(it) { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) }
            Text("Valid until: $vu", style = MaterialTheme.typography.bodySmall, color = t.inkSecondary)
        }
        PosFormCard {
            // Markup is folded into the subtotal (never shown as its own line) so this
            // receipt view matches the printed/shared one and hides markup from the customer.
            TotalRow("Subtotal", money(sale.subtotal + sale.markupTotal, currency))
            if (sale.discountTotal > 0) TotalRow("Discount", "-${money(sale.discountTotal, currency)}")
            if (sale.taxTotal > 0) TotalRow("VAT", money(sale.taxTotal, currency))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        isQuote -> "Quote total"
                        sale.paymentMethod == "credit" -> "Charged to account"
                        else -> "Total paid"
                    },
                    fontWeight = FontWeight.Bold, color = t.inkPrimary
                )
                Text(money(sale.total, currency), fontWeight = FontWeight.Bold, color = t.inkPrimary)
            }
            // Payment/change lines are meaningless on a quote — sale only.
            if (!isQuote) {
                if (sale.paymentMethod != "cash" && sale.paymentMethod != "credit") {
                    TotalRow("Paid via", PaymentMethod.fromCode(sale.paymentMethod)?.label ?: sale.paymentMethod)
                }
                sale.paymentRef?.takeIf { it.isNotBlank() }?.let { TotalRow("Reference", it) }
                sale.changeOwed?.takeIf { it > 0 }?.let { TotalRow("Change owed", money(it, currency)) }
            }
        }
        OutlinedButton(onClick = onPrint, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Print, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isQuote) "Print quote" else "Print receipt")
        }
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
        // Markup folded into the line amount — never itemised on a customer receipt.
        sb.appendLine("    ${money(line.lineTotal + line.lineMarkup, currency)}")
    }
    sb.appendLine("--------------------------------")
    sb.appendLine("Subtotal: ${money(sale.subtotal + sale.markupTotal, currency)}")
    if (sale.discountTotal > 0) sb.appendLine("Discount: -${money(sale.discountTotal, currency)}")
    if (sale.taxTotal > 0) sb.appendLine("VAT: ${money(sale.taxTotal, currency)}")
    sb.appendLine("TOTAL: ${money(sale.total, currency)}")
    if (!isQuote) {
        if (sale.paymentMethod != "cash" && sale.paymentMethod != "credit") {
            sb.appendLine("Paid via: ${PaymentMethod.fromCode(sale.paymentMethod)?.label ?: sale.paymentMethod}")
        }
        sale.changeOwed?.takeIf { it > 0 }?.let { sb.appendLine("Change owed: ${money(it, currency)}") }
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
private fun ItemsScreen(
    vm: PosViewModel,
    currency: String,
    // Set when a "Low stock"/"Out of stock" notification was tapped: the product to land on.
    openItemId: String? = null,
    onOpened: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val items by vm.items.collectAsState()
    val tags by vm.itemTags.collectAsState()
    val business by vm.business.collectAsState()
    val caps by vm.allowedCaps.collectAsState()
    val canManageInventory = com.portionspot.pos.auth.Capability.MANAGE_INVENTORY in caps
    var showAdd by remember { mutableStateOf(false) }
    var showPriceList by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    // What a cashier gets instead of the editor: the part's tags, read-only. The whole
    // value of a fitment is at the counter, and the person holding the part is exactly the
    // one who needs to know what it fits — but the editor is admin-only, so without this
    // the tap simply did nothing and the answer was unreachable.
    var viewingTags by remember { mutableStateOf<Item?>(null) }
    var search by remember { mutableStateOf("") }

    // Deep link. Keyed on [items] as well because the catalogue arrives a frame or two
    // after the screen does. Someone without MANAGE_INVENTORY can't be dropped into the
    // edit sheet, so the list is filtered down to the product instead — they still land
    // on the thing they were warned about. A row that no longer exists just gives up.
    LaunchedEffect(openItemId, items) {
        val id = openItemId ?: return@LaunchedEffect
        val target = items.firstOrNull { it.id == id }
        if (target == null) {
            if (items.isNotEmpty()) onOpened()
            return@LaunchedEffect
        }
        if (canManageInventory) {
            editing = target
        } else {
            search = target.name
        }
        onOpened()
    }

    // Tags count here too: "what do I stock for a Vezel" is an inventory question before
    // it is a till question.
    val q = search.trim()
    val shown = remember(items, q, tags) {
        searchCatalog(items, q, tags, includeCategory = true).map { it.item }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(t.surface1).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SearchField(value = search, onValue = { search = it }, onClear = { search = "" })
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { showPriceList = true }, enabled = items.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Price list")
                    }
                }
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
                            onClick = { if (canManageInventory) editing = item else viewingTags = item },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Leading thumbnail when the product has an image (shown
                                // here regardless of "show on card" — this is the manage view).
                                item.imageModel?.let { model ->
                                    ProductImage(
                                        model = model,
                                        contentDescription = item.name,
                                        modifier = Modifier.size(44.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.name, fontWeight = FontWeight.Medium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (item.productType == "set" || item.productType == "piece" || item.isMeasured) {
                                            Spacer(Modifier.width(6.dp))
                                            TypeBadge(item)
                                        }
                                    }
                                    if (item.trackStock) {
                                        // Measured items count in their decimal unit; box/piece in whole units.
                                        val onHand = item.onHand
                                        val out = onHand <= 0.0
                                        // Below zero is a COUNTING problem, not an empty shelf,
                                        // and this is the screen where it gets fixed — so it
                                        // says what is wrong and what to do, rather than
                                        // sharing "Out of stock" with every sold-out product.
                                        val short = item.stockIsShort()
                                        // Per-item reorder level wins; fall back to the global
                                        // default, except on a measured item — see lowStockLevel.
                                        val threshold = lowStockLevel(item)
                                        val low = !out && onHand <= threshold
                                        val (label, tint) = when {
                                            short -> "Stock take needed: ${trimQty(onHand)} ${item.unit}".trimEnd() to
                                                MaterialTheme.colorScheme.error
                                            out -> "Out of stock" to MaterialTheme.colorScheme.error
                                            low -> "Low: ${trimQty(onHand)} ${item.unit} left" to MaterialTheme.colorScheme.error
                                            else -> "In stock: ${trimQty(onHand)} ${item.unit}" to
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(label, color = tint, style = MaterialTheme.typography.bodySmall)
                                    }
                                    // The item's tags, always — not only when they matched.
                                    // This is the manage view, and "which parts have no
                                    // fitments recorded yet" is a question only the owner
                                    // can answer, and only if the app shows the gap.
                                    tags[item.id]?.takeIf { it.isNotEmpty() }?.let { itemTags ->
                                        Text(
                                            tagCaption(itemTags),
                                            color = t.accentBlue,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Text(
                                    money(if (item.isMeasured) item.pricePerUnit else item.price, currency),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        if (canManageInventory) {
            FilledTonalButton(
                onClick = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add item")
            }
        }
    }

    if (showAdd) {
        ItemDialog(vm = vm, existing = null, onClose = { showAdd = false })
    }
    if (showPriceList) {
        PriceListDialog(items = items, business = business, currency = currency, onDismiss = { showPriceList = false })
    }
    editing?.let { current ->
        ItemDialog(vm = vm, existing = current, onClose = { editing = null })
    }
    viewingTags?.let { current ->
        ItemAttributesSheet(vm = vm, item = current, onDismiss = { viewingTags = null })
    }
}

/** A price-list line resolved for one basis (retail or wholesale, box-first). */
private data class PricedItem(val name: String, val category: String, val note: String?, val price: Double)

/**
 * Resolve the catalogue into priced lines for the chosen [basis] ("retail" or
 * "wholesale"). Wholesale is box-first (a real box quotes the box price with an
 * "(x N)" note; otherwise the per-unit wholesale price); retail uses the retail
 * price. Items with no price in the basis are dropped. Inventory order is kept.
 */
private fun priceCatalogue(items: List<Item>, basis: String, showUnit: Boolean, currency: String): List<PricedItem> =
    items.filter { it.isActive && !it.deleted }.mapNotNull { p ->
        val cat = p.category?.takeIf { it.isNotBlank() } ?: "Uncategorised"
        if (basis == "retail") {
            if (p.price > 0.0) PricedItem(p.name, cat, null, p.price) else null
        } else {
            val hasBox = p.boxSize > 1 && p.boxPrice > 0.0
            when {
                hasBox -> {
                    val note = buildString {
                        append("(x${p.boxSize})")
                        if (showUnit && p.wholesalePrice > 0.0) append(" · ${money(p.wholesalePrice, currency)}/unit")
                    }
                    PricedItem(p.name, cat, note, p.boxPrice)
                }
                p.wholesalePrice > 0.0 -> PricedItem(p.name, cat, null, p.wholesalePrice)
                else -> null
            }
        }
    }

/**
 * Price-list generator (web Inventory "Wholesale Price List" parity + improvement).
 * Improvement over the web: a Retail vs Wholesale basis toggle (web was wholesale-only)
 * and a proper saved/shared PDF via [PdfDocs.priceList]. Pick categories, then share
 * the list as WhatsApp text or a PDF. Prices are box-first for wholesale.
 */
@Composable
private fun PriceListDialog(
    items: List<Item>,
    business: Business?,
    currency: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var basis by remember { mutableStateOf("wholesale") }        // "wholesale" | "retail"
    var showUnit by remember { mutableStateOf(false) }           // add "$X/unit" on box lines
    val priced = remember(items, basis, showUnit, currency) { priceCatalogue(items, basis, showUnit, currency) }
    val orderedCategories = remember(priced) {
        val seen = LinkedHashSet<String>()
        priced.forEach { seen.add(it.category) }
        seen.toList()
    }
    // Selected categories; re-seeded to "all" whenever the category set changes.
    var selected by remember(orderedCategories) { mutableStateOf(orderedCategories.toSet()) }
    val groups = remember(priced, selected, orderedCategories) {
        orderedCategories.filter { it in selected }
            .map { cat -> cat to priced.filter { it.category == cat } }
            .filter { it.second.isNotEmpty() }
    }
    val itemCount = groups.sumOf { it.second.size }
    val heading = if (basis == "retail") "Retail Price List" else "Wholesale Price List"

    fun buildText(): String {
        val sb = StringBuilder()
        sb.appendLine("*${business?.name?.takeIf { it.isNotBlank() } ?: "PortionSpot"} — $heading*")
        business?.phone?.takeIf { it.isNotBlank() }?.let { sb.appendLine(it) }
        sb.appendLine("Updated: ${SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())}")
        groups.forEach { (cat, list) ->
            sb.appendLine()
            sb.appendLine("*${cat.uppercase()}*")
            list.forEach { p ->
                sb.appendLine("  ${p.name}${p.note?.let { " $it" } ?: ""} — ${money(p.price, currency)}")
            }
        }
        return sb.toString().trim()
    }

    PosDialog(title = "Price list", onDismiss = onDismiss) {
        val t = LocalPosTokens.current
        // Basis toggle (the improvement over the web's wholesale-only list).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = basis == "wholesale", onClick = { basis = "wholesale" }, label = { Text("Wholesale") })
            FilterChip(selected = basis == "retail", onClick = { basis = "retail" }, label = { Text("Retail") })
        }
        Text(
            if (basis == "wholesale") "Box-first: a boxed item shows its box price."
            else "Retail (per-unit) prices for a customer-facing list.",
            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
        )
        if (basis == "wholesale") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show unit price on boxes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = t.inkPrimary)
                Switch(checked = showUnit, onCheckedChange = { showUnit = it })
            }
        }
        if (orderedCategories.isEmpty()) {
            Text("No priced items for this basis.", color = t.inkTertiary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Categories", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                TextButton(onClick = { selected = orderedCategories.toSet() }) { Text("All") }
                TextButton(onClick = { selected = emptySet() }) { Text("None") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                orderedCategories.forEach { cat ->
                    val on = cat in selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { selected = if (on) selected - cat else selected + cat }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = { selected = if (on) selected - cat else selected + cat })
                        Spacer(Modifier.width(6.dp))
                        Text(cat, modifier = Modifier.weight(1f), color = t.inkPrimary)
                    }
                }
            }
            Text(
                "$itemCount item${if (itemCount == 1) "" else "s"} · ${groups.size}/${orderedCategories.size} categories",
                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, buildText())
                        }
                        runCatching { context.startActivity(Intent(send).setPackage("com.whatsapp")) }
                            .getOrElse { context.startActivity(Intent.createChooser(send, "Share price list")) }
                    },
                    enabled = itemCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Text")
                }
                Button(
                    onClick = {
                        val biz = business ?: return@Button
                        val file = PdfDocs.priceList(
                            context, biz, heading, currency,
                            groups.map { (c, list) -> c to list.map { PdfDocs.PriceListLine(it.name, it.note, it.price) } }
                        )
                        PdfFiles.share(context, file, heading)
                    },
                    enabled = itemCount > 0 && business != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("PDF")
                }
            }
        }
    }
}

/** One selectable product-type card in the item form's 2×2 type grid. */
@Composable
private fun RowScope.ProductTypeCell(
    title: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val t = LocalPosTokens.current
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) t.brand.s50 else t.surface2)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) t.brand.s500 else t.surfaceBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            title, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) t.brand.s700 else t.inkPrimary, maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(desc, fontSize = 10.sp, lineHeight = 12.sp, color = t.inkTertiary, maxLines = 2)
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
    // How the product is sold: box | set | piece (mirrors the web catalog).
    var productType by remember { mutableStateOf(existing?.productType ?: "box") }
    // Per-unit cost (used for set/piece, and the stored value for every type).
    var cost by remember { mutableStateOf(existing?.cost?.let { trimQty(it) } ?: "") }
    // Cost entered PER BOX for box items; the per-unit cost is derived from it on save.
    // Seed it from an existing box item's stored per-unit cost × its box size.
    var boxCost by remember {
        val e = existing
        mutableStateOf(
            if (e != null && e.productType == "box" && e.cost != null && e.cost > 0.0)
                trimQty(e.cost * e.boxSize.coerceAtLeast(1)) else ""
        )
    }
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
    // ── Measured (unit-priced) product state ──
    // Price for ONE unit, and the decimal on-hand quantity. Both apply only when
    // productType == "measured"; the unit label reuses the existing [unit] field.
    var pricePerUnit by remember {
        mutableStateOf(existing?.pricePerUnit?.takeIf { it > 0 }?.let { trimQty(it) } ?: "")
    }
    var stockMeasured by remember {
        mutableStateOf(existing?.takeIf { it.trackStock && it.productType == "measured" }
            ?.let { trimQty(it.stockMeasured) } ?: "")
    }
    var showHistory by remember { mutableStateOf(false) }

    // ── Product image ──
    // [imageLocalPath] is the on-device copy to display/save; [imageChanged] tracks
    // whether the user picked or removed an image this session (so an edit marks the
    // image pending re-upload / clears the cloud URL). [showImage] mirrors show_image.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageLocalPath by remember { mutableStateOf(existing?.imageLocalPath) }
    var imageChanged by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(existing?.showImage ?: true) }
    val imagePreviewModel = imageLocalPath ?: existing?.imageUrl?.takeIf { !imageChanged }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { ProductImages.saveLocalCopy(context, uri) }
            if (saved != null) {
                // Drop the previous session-staged copy to avoid orphan files.
                imageLocalPath?.takeIf { it != existing?.imageLocalPath }
                    ?.let { ProductImages.deleteLocal(it) }
                imageLocalPath = saved
                imageChanged = true
            }
        }
    }

    val priceVal = price.toDoubleOrNull()
    val taxVal = tax.toDoubleOrNull() ?: 0.0
    val wholesaleVal = wholesale.toDoubleOrNull() ?: 0.0
    val boxPriceVal = boxPrice.toDoubleOrNull() ?: 0.0
    val boxSizeVal = boxSize.toIntOrNull()?.coerceAtLeast(1) ?: 1
    // Product type drives which fields apply. Only a "box" item uses the box price /
    // units-per-box and the boxes+loose stock split; a set or piece is a single count.
    val isBox = productType == "box"
    val isMeasured = productType == "measured"
    val typeSuffix = when (productType) { "set" -> "  (per set)"; "piece" -> "  (each)"; else -> "" }
    val useBoxStock = isBox && boxSizeVal > 1
    // Cost basis is type-aware: a box's cost is entered PER BOX and the per-unit cost is
    // derived (box cost ÷ units per box); a set/piece/measured cost is already per unit.
    val unitCostVal = if (isBox) boxCost.toDoubleOrNull()?.let { it / boxSizeVal }
    else cost.toDoubleOrNull()
    // Values actually persisted: a set/piece/measured never keeps a box size or box price.
    val savedBoxSize = if (isBox) boxSizeVal else 1
    val savedBoxPrice = if (isBox) boxPriceVal else 0.0
    // Measured pricing/stock. The unit label reuses the [unit] field; for a measured
    // item the "retail price" stored is the per-unit price (so cards/reports read right).
    val pricePerUnitVal = pricePerUnit.toDoubleOrNull() ?: 0.0
    val stockMeasuredVal = stockMeasured.toDoubleOrNull() ?: 0.0
    // Total on-hand units. For a box item it's dynamically summed from boxes + loose;
    // otherwise it's the single unit/set/piece count. Measured tracks stock separately.
    val boxesVal = stockBoxes.toIntOrNull() ?: 0
    val looseVal = stockLoose.toDoubleOrNull() ?: 0.0
    val stockVal = if (useBoxStock) boxesVal * boxSizeVal + looseVal
    else (stock.toDoubleOrNull() ?: 0.0)
    val unitText = unit.trim().ifBlank { "pc" }
    // The retail price persisted: a measured item stores its per-unit price here.
    val savedPrice = if (isMeasured) pricePerUnitVal else (priceVal ?: 0.0)
    // Save is enabled once there's a name and a usable price for the chosen type.
    val priceReady = if (isMeasured) pricePerUnitVal > 0.0 else priceVal != null

    PosContainedForm(
        title = if (existing == null) "New item" else "Edit item",
        onDismiss = onClose,
        confirmLabel = "Save item",
        confirmEnabled = name.isNotBlank() && priceReady,
        onConfirm = {
            if (existing == null) {
                vm.addItem(
                    name = name, price = savedPrice,
                    wholesalePrice = wholesaleVal, boxPrice = savedBoxPrice, boxSize = savedBoxSize,
                    productType = productType,
                    category = category.trim().ifBlank { null }, sku = sku.trim().ifBlank { null },
                    barcode = barcode.trim().ifBlank { null },
                    taxRate = taxVal, trackStock = track,
                    stockQty = if (isMeasured) 0.0 else stockVal,
                    reorderLevel = reorder.toDoubleOrNull() ?: 0.0,
                    cost = unitCostVal, unit = unitText,
                    pricePerUnit = if (isMeasured) pricePerUnitVal else 0.0,
                    stockMeasured = if (isMeasured) stockMeasuredVal else 0.0,
                    imageLocalPath = imageLocalPath, showImage = showImage
                )
            } else {
                // Resolve the image fields. Untouched → keep as-is; removed → clear
                // url+path and mark pending (so the clear reaches the cloud); added/
                // replaced → new local path, mark pending for Storage upload.
                val (finalPath, finalUrl, finalPending) = when {
                    !imageChanged -> Triple(existing.imageLocalPath, existing.imageUrl, existing.imagePending)
                    imageLocalPath == null -> Triple(null, null, true)
                    else -> Triple(imageLocalPath, existing.imageUrl, true)
                }
                if (imageChanged && existing.imageLocalPath != null && existing.imageLocalPath != finalPath) {
                    ProductImages.deleteLocal(existing.imageLocalPath)
                }
                vm.updateItem(
                    existing.copy(
                        name = name.trim(),
                        price = savedPrice,
                        wholesalePrice = wholesaleVal,
                        boxPrice = savedBoxPrice,
                        boxSize = savedBoxSize,
                        productType = productType,
                        category = category.trim().ifBlank { null },
                        sku = sku.trim().ifBlank { null },
                        barcode = barcode.trim().ifBlank { null },
                        taxRate = taxVal,
                        trackStock = track,
                        stockQty = if (isMeasured) 0.0 else if (track) stockVal else 0.0,
                        reorderLevel = if (track) (reorder.toDoubleOrNull() ?: 0.0) else 0.0,
                        cost = unitCostVal,
                        unit = unitText,
                        pricePerUnit = if (isMeasured) pricePerUnitVal else 0.0,
                        stockMeasured = if (isMeasured && track) stockMeasuredVal else 0.0,
                        imageLocalPath = finalPath,
                        imageUrl = finalUrl,
                        imagePending = finalPending,
                        showImage = showImage
                    )
                )
            }
            onClose()
        }
    ) {
        val t = LocalPosTokens.current
        val currency = vm.business.value?.currency ?: "USD"

        // ── Product image ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(t.surface2),
                contentAlignment = Alignment.Center
            ) {
                if (imagePreviewModel != null) {
                    ProductImage(
                        model = imagePreviewModel,
                        contentDescription = "Product image",
                        modifier = Modifier.size(64.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.AddPhotoAlternate, contentDescription = null,
                        tint = t.inkTertiary, modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text(if (imagePreviewModel != null) "Change" else "Add photo") }
                    if (imagePreviewModel != null) {
                        TextButton(onClick = {
                            imageLocalPath?.takeIf { it != existing?.imageLocalPath }
                                ?.let { ProductImages.deleteLocal(it) }
                            imageLocalPath = null
                            imageChanged = true
                        }) { Text("Remove") }
                    }
                }
                if (imagePreviewModel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show on card", style = MaterialTheme.typography.bodySmall, color = t.inkSecondary)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = showImage, onCheckedChange = { showImage = it })
                    }
                }
            }
        }

        // ── Product type (Box / Set / Piece / Measured) — drives the fields below ──
        Column {
            PosSectionLabel("Product type")
            Spacer(Modifier.height(6.dp))
            // 2×2 grid: four options read comfortably on a phone (a single row of four
            // is too cramped). Each cell is a tappable card.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductTypeCell("Box", "Box or loose unit", productType == "box") { productType = "box" }
                ProductTypeCell("Set", "Complete set only", productType == "set") { productType = "set" }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProductTypeCell("Piece", "Sold individually", productType == "piece") { productType = "piece" }
                ProductTypeCell("Measured", "By weight / volume", productType == "measured") { productType = "measured" }
            }
        }

        // ── Pricing ──
        PosFormCard {
            PosField(value = name, onValueChange = { name = it }, label = "Name", modifier = Modifier.fillMaxWidth())
            if (isMeasured) {
                // Measured items: pick a unit, then price ONE unit. No box / wholesale.
                PosSectionLabel("Unit of measure")
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("kg", "g", "L", "ml", "m").forEach { u ->
                        val selected = unit.trim().equals(u, ignoreCase = true)
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) t.brand.s600 else t.surface2)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) t.brand.s600 else t.surfaceBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { unit = u }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                u, fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (selected) t.inkOnBrand else t.inkPrimary, maxLines = 1
                            )
                        }
                    }
                }
                PosField(
                    value = unit, onValueChange = { unit = it },
                    label = "Custom unit (e.g. kg, L, m, roll)", modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosField(
                        value = pricePerUnit,
                        onValueChange = { pricePerUnit = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = "Price / $unitText", keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                    PosField(
                        value = cost,
                        onValueChange = { cost = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = "Cost / $unitText  (opt)", keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pricePerUnitVal > 0.0 && unitCostVal != null && unitCostVal > 0.0) {
                    val profit = pricePerUnitVal - unitCostVal
                    val pct = if (prefs.marginFormula == "gross") profit / pricePerUnitVal * 100
                    else profit / unitCostVal * 100
                    val marginLabel = if (prefs.marginFormula == "gross") "Gross margin" else "Markup"
                    Text(
                        "$marginLabel: ${trimPct(pct)}%  (${money(profit, currency)} / $unitText)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profit >= 0) t.success else MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosField(
                        value = price,
                        onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = "Retail price$typeSuffix", keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f)
                    )
                    PosField(
                        value = if (isBox) boxCost else cost,
                        onValueChange = { v ->
                            val f = v.filter { ch -> ch.isDigit() || ch == '.' }
                            if (isBox) boxCost = f else cost = f
                        },
                        label = when (productType) {
                            "box" -> "Cost / box"
                            "set" -> "Cost / set"
                            else -> "Cost / piece"
                        },
                        keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                    )
                }
                if (isBox && boxSizeVal > 1 && unitCostVal != null) {
                    Text(
                        "Per unit ≈ ${money(unitCostVal, currency)}",
                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                    )
                }
                if (priceVal != null && unitCostVal != null && unitCostVal > 0.0 && priceVal > 0.0) {
                    val profit = priceVal - unitCostVal
                    val pct = if (prefs.marginFormula == "gross") profit / priceVal * 100
                    else profit / unitCostVal * 100
                    val marginLabel = if (prefs.marginFormula == "gross") "Gross margin" else "Markup"
                    Text(
                        "$marginLabel: ${trimPct(pct)}%  (${money(profit, currency)} profit)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profit >= 0) t.success else MaterialTheme.colorScheme.error
                    )
                }
                PosField(
                    value = wholesale,
                    onValueChange = { wholesale = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = "Wholesale price  (opt)", keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isBox) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosField(
                            value = boxPrice,
                            onValueChange = { boxPrice = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Box price  (opt)", keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f)
                        )
                        PosField(
                            value = boxSize,
                            onValueChange = { boxSize = it.filter { ch -> ch.isDigit() } },
                            label = "Units / box", keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (prefs.autoConvertUnitsToBoxes && boxSizeVal > 1 && priceVal != null) {
                        LaunchedEffect(priceVal, boxSizeVal, prefs.autoConvertUnitsToBoxes) {
                            boxPrice = trimQty(priceVal * boxSizeVal)
                        }
                    }
                }
            }
        }

        // ── Details ──
        PosFormCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosField(value = category, onValueChange = { category = it }, label = "Category  (opt)", modifier = Modifier.weight(1f))
                PosField(value = sku, onValueChange = { sku = it }, label = "SKU  (opt)", modifier = Modifier.weight(1f))
            }
            PosField(
                value = barcode, onValueChange = { barcode = it.trim() },
                label = "Barcode  (opt)", modifier = Modifier.fillMaxWidth(),
                trailing = {
                    Icon(
                        Icons.Filled.QrCodeScanner, contentDescription = "Scan barcode",
                        tint = t.brand.s600, modifier = Modifier.size(20.dp).clickable { scanning = true }
                    )
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosField(
                    value = tax, onValueChange = { tax = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = "Tax %  (opt)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                )
                // Measured items set their unit in the pricing card; others keep the plain Unit field.
                if (!isMeasured) {
                    PosField(value = unit, onValueChange = { unit = it }, label = "Unit", modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // ── Attributes (the cars this part fits, the brand, the part number) ──
        // Only once the product exists. A tag is its own row against an item id, so there
        // is nothing to attach one to until the item has been saved — and a form that
        // accepted tags and then dropped them on save would be worse than not offering
        // them. Inside the dialog's scrolling column, so a part with a dozen fitments
        // scrolls rather than pushing the Save button off a small screen.
        if (existing != null) {
            AttributeEditorCard(vm = vm, item = existing)
        } else {
            PosFormCard {
                PosSectionLabel("Attributes")
                Text(
                    "Save the item first, then add what staff would search by — the car it " +
                        "fits, the brand, the part number.",
                    style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                )
            }
        }

        // ── Stock ──
        PosFormCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Track stock", Modifier.weight(1f), color = t.inkPrimary, fontWeight = FontWeight.Medium)
                Switch(checked = track, onCheckedChange = { track = it })
            }
            if (track) {
                if (isMeasured) {
                    // Measured item: a single decimal on-hand quantity in the chosen unit.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosField(
                            value = stockMeasured,
                            onValueChange = { stockMeasured = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = if (existing == null) "Opening $unitText" else "On hand ($unitText)",
                            keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                        )
                        PosField(
                            value = reorder, onValueChange = { reorder = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Reorder at ($unitText)", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                        )
                    }
                } else if (useBoxStock) {
                    // Box item: enter boxes + loose units; total is computed live.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosField(
                            value = stockBoxes, onValueChange = { stockBoxes = it.filter { ch -> ch.isDigit() } },
                            label = "Boxes", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f)
                        )
                        PosField(
                            value = stockLoose, onValueChange = { stockLoose = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Loose $unitText", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "Total: ${trimQty(stockVal)} $unitText  ($boxesVal box${if (boxesVal == 1) "" else "es"} × $boxSizeVal + $looseVal loose)",
                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                    )
                    PosField(
                        value = reorder, onValueChange = { reorder = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = "Reorder at ($unitText)", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosField(
                            value = stock, onValueChange = { stock = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = when (productType) {
                                "set" -> if (existing == null) "Sets in stock" else "Sets on hand"
                                "piece" -> if (existing == null) "Pieces in stock" else "Pieces on hand"
                                else -> if (existing == null) "Opening stock" else "Stock on hand"
                            },
                            keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                        )
                        PosField(
                            value = reorder, onValueChange = { reorder = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = "Reorder at", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (existing != null) {
                    TextButton(onClick = { showHistory = true }) { Text("View stock history") }
                }
            }
        }
    }

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

/**
 * The tags on one item — "car: Vezel", "brand: NewBlu", "part_number: A111K" — with add,
 * edit and remove.
 *
 * These are what make a parts counter searchable. A product's NAME can only name one or two
 * of the cars it fits; "Oil Filter 164" fits seventy-two and says so nowhere, so before
 * these existed a customer asking for an oil filter for a Navara was told the shop had none
 * while two dozen sat on the shelf. Everything typed here is matched by the till's search.
 *
 * ★ Tags save the moment they are added, not with the rest of the form. They are their own
 * rows with their own derived ids, so there is nothing to hold back — and holding them
 * would only risk losing them if the dialog were dismissed. That does mean the editor needs
 * a SAVED item to hang off, which is why a brand-new product is asked to be saved first
 * rather than given a form that silently discards what is typed into it.
 */
@Composable
private fun AttributeEditorCard(vm: PosViewModel, item: Item) {
    val t = LocalPosTokens.current
    // Remembered on the item id, not rebuilt per recomposition: this composable
    // recomposes on every keystroke in the two fields below, and a fresh Flow each time
    // would restart the Room query on each letter typed.
    val tagFlow = remember(item.id) { vm.itemAttributes(item.id) }
    val rows by tagFlow.collectAsState(initial = emptyList())
    val vocab by vm.attributeVocabulary.collectAsState()

    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    // Non-null while an EXISTING tag is being changed rather than a new one added. The
    // distinction matters: changing a tag's text changes its identity, so it is a
    // tombstone plus a new row, not an edit in place (see PosRepository.editItemAttribute).
    var editing by remember { mutableStateOf<ItemAttribute?>(null) }

    val groups = remember(rows) { groupAttributes(rows) }
    // Values this item already carries under the key being typed — excluded from the
    // suggestions, since tapping one could only be a no-op.
    val onItem = remember(rows, key) {
        val k = attrNorm(key)
        rows.filter { !it.deleted && it.keyNorm == k }.map { it.valueNorm }.toSet()
    }
    val keyOptions = remember(vocab, key) { keySuggestions(vocab, key) }
    val valueOptions = remember(vocab, key, value, onItem) {
        if (key.isBlank()) emptyList<String>() else valueSuggestions(vocab, key, onItem, value)
    }
    val canSave = key.isNotBlank() && value.isNotBlank()

    fun commit(v: String = value) {
        if (key.isBlank() || v.isBlank()) return
        val target = editing
        if (target == null) {
            vm.addItemAttribute(item.id, key, v)
        } else {
            vm.editItemAttribute(target, key, v)
        }
        // The KEY stays behind. Tagging one part with three cars in a row is the normal
        // case, and retyping "car" each time would be friction for nothing.
        value = ""
        editing = null
    }

    PosFormCard {
        PosSectionLabel("Attributes")
        Text(
            "Anything staff might search by at the counter — the car it fits, the brand, " +
                "the part number. These are searchable on the till.",
            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
        )

        groups.forEach { group ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    group.label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = t.inkTertiary, maxLines = 1
                )
                group.rows.forEach { row ->
                    val isEditing = editing?.id == row.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isEditing) t.brand.s50 else t.surface2)
                            .border(
                                width = if (isEditing) 1.5.dp else 1.dp,
                                color = if (isEditing) t.brand.s500 else t.surfaceBorder,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .padding(start = 11.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            row.value, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = t.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            editing = row
                            key = row.key
                            value = row.value
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Change ${group.label} ${row.value}",
                                tint = t.inkSecondary, modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = {
                            if (editing?.id == row.id) {
                                editing = null; key = ""; value = ""
                            }
                            vm.removeItemAttribute(row)
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove ${group.label} ${row.value}",
                                tint = t.danger, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PosField(
                value = key, onValueChange = { key = it }, label = "Attribute",
                placeholder = "car", modifier = Modifier.weight(0.85f)
            )
            PosField(
                value = value, onValueChange = { value = it }, label = "Value",
                placeholder = "Honda Fit", modifier = Modifier.weight(1.15f)
            )
        }

        // Existing keys first, then the standard ones the shop has not adopted. Free text
        // either way — the field is not a dropdown, because the next shop will want
        // something this list has never heard of.
        if (keyOptions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                keyOptions.forEach { k -> AttrSuggestionChip(k) { key = k } }
            }
        }

        // One tap to reuse a value the shop already uses. This is the control that stops
        // the catalogue rotting: "Toyota Hilux" retyped slightly differently is a second
        // tag that no amount of folding will ever merge back into the first.
        if (valueOptions.isNotEmpty()) {
            Text(
                "Already used for ${key.trim().lowercase()} — tap to add",
                fontSize = 10.sp, color = t.inkTertiary
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                valueOptions.forEach { v ->
                    AttrSuggestionChip(v) {
                        // While ADDING, one tap is the whole interaction — that is the
                        // point of the chip. While EDITING it only fills the box, because
                        // changing a tag rewrites an existing row and should be confirmed.
                        if (editing == null) {
                            commit(v)
                        } else {
                            value = v
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing != null) {
                OutlinedButton(
                    onClick = { editing = null; key = ""; value = "" },
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
            }
            FilledTonalButton(
                onClick = { commit() },
                enabled = canSave,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    if (editing == null) Icons.Filled.Add else Icons.Filled.Check,
                    contentDescription = null, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (editing == null) "Add" else "Save change")
            }
        }
    }
}

/** A tappable suggestion pill — a value or key the shop has already used. */
@Composable
private fun AttrSuggestionChip(label: String, onClick: () -> Unit) {
    val t = LocalPosTokens.current
    Text(
        label,
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = t.inkSecondary, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(t.surface2)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/**
 * Read-only tags for one item — what a counter hand gets when they tap a product they are
 * not allowed to edit.
 *
 * The whole value of these tags is at the counter, and a cashier holding the part is
 * exactly the person who needs to know what it fits. Without this the only way to see the
 * fitments is the editor, which a cashier cannot open.
 */
@Composable
private fun ItemAttributesSheet(vm: PosViewModel, item: Item, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val tagFlow = remember(item.id) { vm.itemAttributes(item.id) }
    val rows by tagFlow.collectAsState(initial = emptyList())
    val groups = remember(rows) { groupAttributes(rows) }
    PosDialog(title = item.name, onDismiss = onDismiss) {
        if (groups.isEmpty()) {
            Text("No attributes recorded for this item.", color = t.inkTertiary)
        } else {
            groups.forEach { group ->
                Column(Modifier.fillMaxWidth()) {
                    PosSectionLabel(group.label)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        group.rows.joinToString(", ") { it.value },
                        color = t.inkPrimary, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** Per-item stock-movement ledger: sales, restocks, adjustments, resets. */
@Composable
private fun StockHistoryDialog(vm: PosViewModel, item: Item, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val movements by vm.itemMovements(item.id).collectAsState(initial = emptyList())
    PosDialog(title = "Stock history · ${item.name}", onDismiss = onDismiss) {
        if (movements.isEmpty()) {
            Text("No stock movements yet.", color = t.inkTertiary)
        } else {
            movements.forEach { m ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.type.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Medium, color = t.inkPrimary)
                        Text(
                            m.note?.takeIf { it.isNotBlank() } ?: dashTime(m.createdAt),
                            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                        )
                    }
                    Text(
                        (if (m.delta >= 0) "+" else "") + trimQty(m.delta),
                        fontWeight = FontWeight.Bold,
                        color = if (m.delta >= 0) t.success else t.danger
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("= ${trimQty(m.balanceAfter)}", color = t.inkTertiary, fontSize = 12.sp)
                }
                HorizontalDivider(color = t.surfaceBorder)
            }
        }
    }
}

// ───────────────────────── CUSTOMERS ─────────────────────────

@Composable
private fun CustomersScreen(
    vm: PosViewModel,
    currency: String,
    // Set when an "Aging debt" / "Over credit limit" notification was tapped.
    openCustomerId: String? = null,
    onOpened: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val ctx = LocalContext.current
    val customers by vm.customers.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Customer?>(null) }
    var showRecentCalls by remember { mutableStateOf(false) }
    // Non-null => open the Add dialog pre-filled from a contact pick or a recent call.
    var prefill by remember { mutableStateOf<PickedContact?>(null) }
    var search by remember { mutableStateOf("") }

    // Deep link: open the account the alert was about (the detail sheet is where the debt,
    // its age and the "record payment" action live). Same wait-for-the-flow shape as Inventory.
    LaunchedEffect(openCustomerId, customers) {
        val id = openCustomerId ?: return@LaunchedEffect
        val target = customers.firstOrNull { it.customer.id == id }
        if (target == null) {
            if (customers.isNotEmpty()) onOpened()
            return@LaunchedEffect
        }
        selected = target.customer
        onOpened()
    }

    val totalOutstanding = customers.sumOf { it.balance }
    val q = search.trim().lowercase()
    val shown = remember(customers, q) {
        if (q.isEmpty()) customers
        else customers.filter {
            it.customer.name.lowercase().contains(q) ||
                (it.customer.phone?.lowercase()?.contains(q) == true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Customers", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    "${customers.size} account" + if (customers.size == 1) "" else "s",
                    color = t.inkTertiary, fontSize = 12.sp
                )
                if (totalOutstanding > 0) {
                    Spacer(Modifier.height(10.dp))
                    PosMetricCard(
                        "Owed to you", money(totalOutstanding, currency),
                        Modifier.fillMaxWidth(), accent = t.danger, sub = "across all accounts"
                    )
                }
                Spacer(Modifier.height(10.dp))
                PosField(
                    value = search, onValueChange = { search = it },
                    label = "Search customer", placeholder = "Name or phone",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (customers.isEmpty()) "No customers yet.\nAdd one to sell on credit."
                        else "No customers match your search.",
                        color = t.inkTertiary, textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.customer.id }) { cb ->
                        val owes = cb.balance > 0.0
                        val accent = if (owes) t.danger else t.brand.s600
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(t.surface1)
                                .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                                .clickable { selected = cb.customer }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(cb.customer.name.take(1).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(cb.customer.name, fontWeight = FontWeight.Bold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (cb.customer.wholesale) {
                                        Spacer(Modifier.width(6.dp))
                                        WholesaleBadge()
                                    }
                                }
                                cb.customer.phone?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (owes) {
                                Text(money(cb.balance, currency), fontWeight = FontWeight.Black, fontSize = 16.sp, color = t.danger)
                            } else {
                                Box(
                                    Modifier.clip(RoundedCornerShape(6.dp)).background(t.success.copy(alpha = 0.13f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) { Text("Settled", color = t.success, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
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
            title = "New customer",
            onDismiss = { showAdd = false; prefill = null }
        ) { name, phone, email, address, note, wholesale, creditLimit ->
            vm.addCustomer(name, phone, email, address, note, wholesale, creditLimit) {
                Toast.makeText(ctx, "Credit limit sent to admin for approval", Toast.LENGTH_LONG).show()
            }
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

/**
 * Shared Add / Edit customer form. Defaults produce the "New customer" flow; passing
 * the `initial*` values + a title/confirmLabel drives it as an in-place editor. The
 * onSave lambda hands back every editable field (name, phone, email, address, note,
 * wholesale, creditLimit) so the caller can create or update as appropriate.
 */
@Composable
private fun AddCustomerDialog(
    initialName: String = "",
    initialPhone: String = "",
    initialEmail: String = "",
    initialAddress: String = "",
    initialNote: String = "",
    initialWholesale: Boolean = false,
    initialCreditLimit: Double? = null,
    title: String = "New customer",
    confirmLabel: String = "Save",
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, Boolean, Double?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var phone by remember { mutableStateOf(initialPhone) }
    var email by remember { mutableStateOf(initialEmail) }
    var address by remember { mutableStateOf(initialAddress) }
    var note by remember { mutableStateOf(initialNote) }
    var wholesale by remember { mutableStateOf(initialWholesale) }
    var creditLimit by remember {
        mutableStateOf(initialCreditLimit?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "")
    }
    // System number picker — fills name + phone from the phone's contacts. Needs no
    // permission (the picker grants a one-shot read on the chosen contact).
    val pickContact = rememberContactPicker { picked ->
        picked.name?.let { name = it }
        picked.phone?.let { phone = it }
    }
    PosContainedForm(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onSave(name, phone, email, address, note, wholesale, creditLimit.trim().toDoubleOrNull()) }
    ) {
        val t = LocalPosTokens.current
        TextButton(onClick = pickContact, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Filled.Contacts, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Pick from contacts")
        }
        PosFormCard {
            PosField(value = name, onValueChange = { name = it }, label = "Name", modifier = Modifier.fillMaxWidth())
            PosField(value = phone, onValueChange = { phone = it }, label = "Phone  (optional)", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
            PosField(value = email, onValueChange = { email = it }, label = "Email  (optional)", keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            PosField(value = address, onValueChange = { address = it }, label = "Address  (optional)", modifier = Modifier.fillMaxWidth())
            PosField(value = note, onValueChange = { note = it }, label = "Note  (optional)", modifier = Modifier.fillMaxWidth())
            PosField(value = creditLimit, onValueChange = { creditLimit = it }, label = "Credit Limit ($)  (optional)", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth())
        }
        PosFormCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Trade account", color = t.inkPrimary, fontWeight = FontWeight.Medium)
                    Text("Charge trade / box prices", style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                }
                Switch(checked = wholesale, onCheckedChange = { wholesale = it })
            }
        }
    }
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

    PosDialog(title = "Recent callers", onDismiss = onDismiss) {
        when {
            !granted -> Column {
                Text(
                    "Call-log access is needed to show recent callers.",
                    style = MaterialTheme.typography.bodyMedium, color = LocalPosTokens.current.inkSecondary
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.READ_CALL_LOG) }) {
                    Text("Grant access")
                }
            }
            loading -> Box(
                Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            calls.isEmpty() -> Text("No recent calls found.", color = LocalPosTokens.current.inkTertiary)
            else -> calls.forEach { call ->
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
    // Money the SHOP owes THIS customer — change booked to their account + unpaid refunds.
    val changeOwed by vm.changeBalanceFlow(customer.id).collectAsState(initial = 0.0)
    val history by vm.creditHistory(customer.id).collectAsState(initial = emptyList())
    val sales by vm.salesForCustomer(customer.id).collectAsState(initial = emptyList())
    val bizForPdf by vm.business.collectAsState()
    val pdfCtx = LocalContext.current
    val pdfScope = rememberCoroutineScope()
    var showPay by remember { mutableStateOf(false) }
    var showPayout by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var wholesale by remember { mutableStateOf(customer.wholesale) }
    // Top-level view switch + the receipt opened by tapping a purchase.
    var tab by remember { mutableStateOf("purchases") }
    var receiptFor by remember { mutableStateOf<SaleEntity?>(null) }
    val printer = rememberPrinterUi { vm.shopPrefs.value }

    PosDialog(title = customer.name, onDismiss = onDismiss) {
        val t = LocalPosTokens.current
        val changeTypes = remember { setOf("change_owed", "refund_owed", "change_paid", "refund_paid") }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (wholesale) "Wholesale Customer" else "Retail Customer",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = t.inkSecondary, fontWeight = FontWeight.Medium
            )
            TextButton(onClick = { showEdit = true }, colors = ButtonDefaults.textButtonColors(contentColor = t.brand.s600)) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
        val contactLines = listOfNotNull(
            customer.phone?.takeIf { it.isNotBlank() },
            customer.email?.takeIf { it.isNotBlank() },
            customer.address?.takeIf { it.isNotBlank() }
        )
        if (contactLines.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                contactLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary) }
            }
        }
        customer.creditLimit?.let { limit ->
            Text(
                "Credit limit: ${money(limit, currency)}",
                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
            )
        }

        PosSegmented(
            options = listOf("purchases" to "Purchases", "credit" to "Credit & Change"),
            selected = tab,
        ) { tab = it }

        if (tab == "purchases") {
            CustomerPurchasesTab(
                vm = vm,
                sales = sales,
                currency = currency,
                onOpenReceipt = { receiptFor = it }
            )
            return@PosDialog
        }

        // ───── CREDIT & CHANGE tab ─────
        // Two independent derived balances: DEBT (credit_owed − credit_paid) and
        // WE-OWE (change/refund owed − paid). The headline is the NET of the two, so a
        // customer who owes 5 while you owe them 3 reads as "owes you 2"; the breakdown
        // underneath keeps both ledgers visible (that's what makes this auditable).
        val net = balance - changeOwed
        val settled = abs(net) <= 0.005
        val netAccent = when {
            settled -> t.success
            net > 0 -> t.danger
            else -> t.brand.s600
        }
        PosMetricCard(
            label = when {
                settled -> "Net position"
                net > 0 -> "Net: owes you"
                else -> "Net: you owe"
            },
            value = if (settled) "Settled" else money(abs(net), currency),
            modifier = Modifier.fillMaxWidth(),
            accent = netAccent,
            sub = "credit owed minus change owed"
        )
        PosFormCard {
            TotalRow("Owes you (credit)", money(balance.coerceAtLeast(0.0), currency))
            TotalRow("You owe (change/refund)", money(changeOwed.coerceAtLeast(0.0), currency))
        }
        // Both actions can be live at once — a customer may owe you AND be owed change.
        if (balance > 0.005) {
            Button(
                onClick = { showPay = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("Record payment") }
        }
        if (changeOwed > 0.005) {
            Button(
                onClick = { showPayout = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("Pay out change") }
        }

        PosFormCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Wholesale customer", Modifier.weight(1f), color = t.inkPrimary, fontWeight = FontWeight.Medium)
                Switch(
                    checked = wholesale,
                    onCheckedChange = { wholesale = it; vm.setCustomerWholesale(customer, it) }
                )
            }
        }

        // Statement PDFs.
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
            if (history.any { it.type in changeTypes }) {
                TextButton(
                    onClick = {
                        val biz = bizForPdf ?: return@TextButton
                        val entries = history.sortedBy { it.createdAt }
                            .filter { it.type in changeTypes }
                            .map {
                                val owed = it.type == "change_owed" || it.type == "refund_owed"
                                PdfDocs.StatementEntry(
                                    date = it.createdAt,
                                    label = when (it.type) {
                                        "change_owed" -> "Change owed"
                                        "refund_owed" -> "Refund owed"
                                        "change_paid" -> "Change paid"
                                        else -> "Refund paid"
                                    },
                                    amount = if (owed) it.amount else -it.amount
                                )
                            }
                        pdfScope.launch {
                            val file = withContext(Dispatchers.IO) {
                                PdfDocs.customerStatement(pdfCtx, biz, "Change & Refund Statement", customer.name, entries)
                            }
                            PdfFiles.share(pdfCtx, file, "Change statement — ${customer.name}")
                        }
                    }
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share change/refund statement PDF")
                }
            }
        }

        // Activity — scrolls with the dialog (no nested scroll).
        PosSectionLabel("Activity")
        if (history.isEmpty()) {
            Text("No credit activity yet.", color = t.inkTertiary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                history.forEach { txn ->
                    val meta = creditRowMeta(txn.type)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(meta.label, style = MaterialTheme.typography.bodyMedium, color = t.inkPrimary)
                            Text(syncTimeLabel(txn.createdAt), style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                        }
                        Text(
                            (if (meta.positive) "+" else "-") + money(txn.amount, currency),
                            fontWeight = FontWeight.Bold,
                            color = if (meta.positive && !meta.weOwe) t.danger else t.brand.s600
                        )
                    }
                }
            }
        }
    }

    if (showEdit) {
        AddCustomerDialog(
            initialName = customer.name,
            initialPhone = customer.phone.orEmpty(),
            initialEmail = customer.email.orEmpty(),
            initialAddress = customer.address.orEmpty(),
            initialNote = customer.note.orEmpty(),
            initialWholesale = customer.wholesale,
            initialCreditLimit = customer.creditLimit,
            title = "Edit customer",
            confirmLabel = "Save changes",
            onDismiss = { showEdit = false }
        ) { name, phone, email, address, note, ws, creditLimit ->
            vm.updateCustomer(customer, name, phone, email, address, note, ws, creditLimit) {
                Toast.makeText(pdfCtx, "Credit limit change sent to admin for approval", Toast.LENGTH_LONG).show()
            }
            wholesale = ws
            showEdit = false
        }
    }
    if (showPay) {
        // ★ ASK HOW THEY PAID. Only cash reaches the drawer, and until this was asked the
        // whole repayment chain wrote credit rows and no cash movement at all — so a debt
        // settled in notes left the till holding money the app did not know about, and the
        // day close booked the difference as a variance against profit.
        // Falls back to Cash alone if the shop has somehow disabled every tender, so the
        // dialog can never present an empty picker.
        val biz = bizForPdf
        val repaymentMethods = remember(biz) {
            (biz?.enabledPaymentMethods() ?: emptyList()).ifEmpty { listOf(PaymentMethod.CASH) }
        }
        RecordPaymentDialog(
            maxAmount = balance,
            currency = currency,
            methods = repaymentMethods,
            onDismiss = { showPay = false }
        ) { amount, note, method ->
            vm.recordRepayment(customer.id, amount, note.ifBlank { null }, method)
            showPay = false
        }
    }
    if (showPayout) {
        RecordPaymentDialog(
            maxAmount = changeOwed,
            currency = currency,
            title = "Pay out change",
            owedLabel = "We owe",
            actionLabel = "Pay out",
            allowOverpay = true,
            overWarning = { over ->
                "You're handing back ${money(over, currency)} more than we owe — the customer will owe it back."
            },
            onDismiss = { showPayout = false }
        ) { amount, note, _ ->
            // No tender picker here on purpose: change and refunds owed are handed back
            // over the counter in notes, and [PosRepository.recordChangePayment] already
            // takes the full amount out of the till. The method is ignored rather than
            // asked for, because there is only one answer.
            // Paying out MORE than we owe is allowed: the repository settles what we owe
            // and books the excess as customer debt ("Over-paid change") — never dropped.
            vm.recordChangePayment(customer.id, amount, note.ifBlank { null })
            showPayout = false
        }
    }
    // Tap a purchase → the same on-screen receipt viewer used after checkout. Lines are
    // fetched lazily for just the opened sale.
    receiptFor?.let { sale ->
        val biz = bizForPdf
        if (biz != null) {
            var lines by remember(sale.id) { mutableStateOf<List<SaleLine>?>(null) }
            LaunchedEffect(sale.id) { lines = vm.loadLines(sale.id) }
            lines?.let { loaded ->
                ReceiptDialog(
                    sale = sale,
                    lines = loaded,
                    business = biz,
                    currency = currency,
                    onPrint = { printer.printReceipt(biz, sale) { loaded } },
                    onDismiss = { receiptFor = null }
                )
            }
        }
    }
}

/** Start-of-current-month, local, epoch-millis (mirrors PosViewModel.startOfMonth). */
private fun startOfThisMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Start-of-current-year, local, epoch-millis. */
private fun startOfThisYear(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private val PURCHASE_DATE_FMT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
private val PURCHASE_DAY_FMT = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

/**
 * Purchases tab of the customer detail dialog — web-parity. Two metric cards
 * (Total Spent / Avg Purchase) over a time-range filter that narrows BOTH the metrics
 * and the newest-first list of completed sales. Each row shows the receipt no, when +
 * who rang it up, a short item summary, a payment-method badge, an optional change
 * badge, and the sale total; tapping opens that sale's on-screen receipt.
 *
 * Rendered inside PosDialog's scrolling Column, so the list is a plain forEach (no
 * nested LazyColumn). Item summaries are fetched lazily for the filtered set.
 */
@Composable
private fun CustomerPurchasesTab(
    vm: PosViewModel,
    sales: List<SaleEntity>,
    currency: String,
    onOpenReceipt: (SaleEntity) -> Unit,
) {
    val t = LocalPosTokens.current
    var range by remember { mutableStateOf("all") }
    val from = remember(range) {
        when (range) {
            "month" -> startOfThisMonth()
            "year" -> startOfThisYear()
            else -> 0L
        }
    }
    val shown = remember(sales, from) { sales.filter { it.soldAt >= from } }

    // Metrics over the filtered window.
    val total = shown.sumOf { it.total }
    val count = shown.size
    val avg = if (count > 0) total / count else 0.0
    val lastDate = shown.maxByOrNull { it.soldAt }?.soldAt
    val lastLabel = lastDate?.let { PURCHASE_DAY_FMT.format(Date(it)) } ?: "—"

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PosMetricCard(
            "Total Spent", money(total, currency), Modifier.weight(1f),
            sub = "$count purchase${if (count == 1) "" else "s"}"
        )
        PosMetricCard(
            "Avg Purchase", money(avg, currency), Modifier.weight(1f),
            sub = "Last: $lastLabel"
        )
    }

    PosSegmented(
        options = listOf("all" to "All Time", "month" to "This Month", "year" to "This Year"),
        selected = range,
    ) { range = it }

    // Lazily resolve a short item summary per shown sale (loads only what's on screen).
    val summaries = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(shown) {
        shown.forEach { sale ->
            if (!summaries.containsKey(sale.id)) {
                val lines = vm.loadLines(sale.id)
                summaries[sale.id] = purchaseSummary(lines)
            }
        }
    }

    if (shown.isEmpty()) {
        Text("No purchases in this period.", color = t.inkTertiary)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { sale ->
            val payLabel = PaymentMethod.fromCode(sale.paymentMethod)?.label ?: sale.paymentMethod
            val change = sale.changeDue ?: 0.0
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface1)
                    .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                    .clickable { onOpenReceipt(sale) }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "#${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}",
                        fontWeight = FontWeight.Bold, color = t.inkPrimary
                    )
                    Text(
                        "${PURCHASE_DATE_FMT.format(Date(sale.soldAt))} · ${sale.createdByName ?: "Admin"}",
                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                    )
                    summaries[sale.id]?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkSecondary)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PurchaseBadge(payLabel, t.accentBlue)
                        if (change > 0) PurchaseBadge("Change: ${money(change, currency)}", t.brand.s600)
                    }
                }
                Text(
                    money(sale.total, currency),
                    fontWeight = FontWeight.Bold, color = t.inkPrimary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** Small tinted pill used on purchase rows (payment method, change). */
@Composable
private fun PurchaseBadge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Compact web-style summary of a sale's lines, e.g. "2× Delo Oil 5L · 1× Filter". */
private fun purchaseSummary(lines: List<SaleLine>): String =
    lines.joinToString("  ·  ") { "${trimQty(it.qty)}× ${it.name}" }

/** Display metadata for a credit-ledger row: label, whether it ADDS to its balance
 *  (shows "+"), and whether it belongs to the shop-owes-customer ledger (vs debt). */
private data class CreditRowMeta(val label: String, val positive: Boolean, val weOwe: Boolean)

private fun creditRowMeta(type: String): CreditRowMeta = when (type) {
    "credit_owed" -> CreditRowMeta("Charged to account", positive = true, weOwe = false)
    "credit_paid" -> CreditRowMeta("Repaid you", positive = false, weOwe = false)
    "change_owed" -> CreditRowMeta("Change you owe", positive = true, weOwe = true)
    "refund_owed" -> CreditRowMeta("Refund you owe", positive = true, weOwe = true)
    "change_paid" -> CreditRowMeta("Change paid back", positive = false, weOwe = true)
    "refund_paid" -> CreditRowMeta("Refund paid back", positive = false, weOwe = true)
    else -> CreditRowMeta("Payment", positive = false, weOwe = false)
}

/**
 * Shared money-in/money-out dialog. [allowOverpay] is opt-in: when true the typed
 * amount may EXCEED [maxAmount] and the excess is surfaced before confirming (the
 * caller books it — see the change-payout path). Left false, behaviour is unchanged
 * for every other caller.
 *
 * [methods] is the TENDER the money came in as, and it is opt-in for the same reason:
 * pass the shop's enabled tenders to ask "how did they pay", or leave it empty for a
 * flow where the answer is already known. It matters because only CASH moves the till —
 * a debt settled by EcoCash clears the account without a note reaching the drawer, and a
 * repayment chain that assumed cash left the day close counting over. The selected code
 * is handed back to [onConfirm]; with no picker that is always [PaymentMethod.CASH].
 */
@Composable
private fun RecordPaymentDialog(
    maxAmount: Double,
    currency: String,
    title: String = "Record payment",
    owedLabel: String = "Owed",
    actionLabel: String = "Record",
    allowOverpay: Boolean = false,
    overLabel: String = "Over-paid (customer owes)",
    overWarning: (Double) -> String = { "" },
    methods: List<PaymentMethod> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (Double, String, String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // Unkeyed on purpose: the shop's tender list cannot change while this dialog is open,
    // and keying on the list would re-default the cashier's choice on every recomposition
    // (the caller builds a fresh List each time it recomposes).
    var method by remember { mutableStateOf(methods.firstOrNull() ?: PaymentMethod.CASH) }
    val amount = amountText.toDoubleOrNull()
    val valid = amount != null && amount > 0
    val over = if (allowOverpay) ((amount ?: 0.0) - maxAmount).coerceAtLeast(0.0) else 0.0
    PosContainedForm(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = actionLabel,
        confirmEnabled = valid,
        onConfirm = { onConfirm(amount ?: 0.0, note, method.code) }
    ) {
        val t = LocalPosTokens.current
        Text("$owedLabel: ${money(maxAmount, currency)}", style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
        PosFormCard {
            PosField(
                value = amountText,
                onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = "Amount received", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
            )
            if (methods.isNotEmpty()) {
                // Same picker the till uses at checkout, so "how did they pay" is asked
                // and stored in one vocabulary across the app.
                PaymentMethodPicker(methods, method) { method = it }
                if (method != PaymentMethod.CASH) {
                    Text(
                        "${method.label} does not go into the till — the drawer stays as it is.",
                        style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                    )
                }
            }
            PosField(value = note, onValueChange = { note = it }, label = "Note  (optional)", modifier = Modifier.fillMaxWidth())
        }
        if (over > 0.005) {
            TotalRow(overLabel, money(over, currency))
            Text(overWarning(over), color = t.warning, fontSize = 12.sp)
        }
    }
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
    val pending by vm.pendingExpenses.collectAsState()
    val caps by vm.allowedCaps.collectAsState()
    val canManageExpenses = com.portionspot.pos.auth.Capability.MANAGE_EXPENSES_ORDERS in caps

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
    // Only POSTED (approved) expenses count toward the red running total; pending/rejected
    // submissions haven't hit the books.
    val total = filtered.filter { it.status == "approved" }.sumOf { it.amount }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Expenses", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Submit shop costs — an admin approves before they post", color = t.inkTertiary, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))

                // Awaiting-approval banner (informational; the admin acts in the console).
                if (pending.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(t.warning.copy(alpha = 0.12f))
                            .border(1.dp, t.warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.HourglassEmpty, contentDescription = null, tint = t.warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${pending.size} expense${if (pending.size == 1) "" else "s"} awaiting admin approval",
                            color = t.inkPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

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

                // Summary bar: count + red posted total.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Posted this period", color = t.inkSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    Text(money(total, currency), color = t.danger, fontWeight = FontWeight.Black, fontSize = 22.sp)
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
                        val editable = e.status == "pending" && canManageExpenses
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(t.surface1)
                                .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                                .then(if (editable) Modifier.clickable { editing = e } else Modifier)
                                .padding(14.dp),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(e.category, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                    if (e.recurring || e.templateId != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Filled.Repeat, contentDescription = "Recurring", tint = t.inkTertiary, modifier = Modifier.size(13.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    ExpenseStatusBadge(e.status)
                                }
                                Text(
                                    e.date + (e.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                                        fundingSuffix(e, currency),
                                    fontSize = 12.sp, color = t.inkTertiary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                money(e.amount, currency), fontWeight = FontWeight.Black,
                                color = if (e.status == "rejected") t.inkTertiary else t.danger
                            )
                            if (e.status != "approved") {
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

        if (canManageExpenses) {
            FilledTonalButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Submit expense")
            }
        }
    }

    if (adding) {
        ExpenseModal(initial = null, onDismiss = { adding = false }) { cat, amt, date, desc, recurring, period ->
            vm.submitExpense(cat, amt, date, desc, recurring, period); adding = false
        }
    }
    editing?.let { e ->
        ExpenseModal(initial = e, onDismiss = { editing = null }) { cat, amt, date, desc, recurring, period ->
            vm.updatePendingExpense(e.id, cat, amt, date, desc, recurring, period); editing = null
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Discard this expense?") },
            text = { Text("It hasn't been posted, so nothing on the books changes.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteExpense(e.id); deleting = null }) {
                    Text("Discard", color = t.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** Small pill for an expense's lifecycle state. */
@Composable
private fun ExpenseStatusBadge(status: String) {
    val t = LocalPosTokens.current
    val (label, color) = when (status) {
        "approved" -> "Posted" to t.success
        "rejected" -> "Rejected" to t.inkTertiary
        else -> "Pending" to t.warning
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** " · $X on account" / " · owner-funded" trailer on a posted expense's meta line. */
private fun fundingSuffix(e: Expense, currency: String): String = when {
    e.status != "approved" -> ""
    e.capitalPortion > 0.005 && e.cashPortion < 0.005 -> " · owner-funded"
    e.payablePortion > 0.005 -> " · ${money(e.payablePortion, currency)} on account"
    else -> ""
}

/** Recurrence periods offered when submitting a recurring expense. */
private val RECURRENCE_PERIODS = listOf("daily" to "Daily", "weekly" to "Weekly", "monthly" to "Monthly")

/** Submit/Edit sheet for one expense: category, amount, date, note + recurring schedule. */
@Composable
private fun ExpenseModal(
    initial: Expense?,
    onDismiss: () -> Unit,
    onSave: (category: String, amount: Double, date: String, description: String?, recurring: Boolean, period: String?) -> Unit
) {
    var category by remember { mutableStateOf(initial?.category ?: EXPENSE_CATEGORIES.first()) }
    var amount by remember { mutableStateOf(initial?.amount?.takeIf { it > 0 }?.let { trimQty(it) } ?: "") }
    var date by remember { mutableStateOf(initial?.date ?: expenseToday()) }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var recurring by remember { mutableStateOf(initial?.recurring ?: false) }
    var period by remember { mutableStateOf(initial?.recurrencePeriod ?: "monthly") }
    var catOpen by remember { mutableStateOf(false) }

    val parsedAmount = amount.replace(',', '.').toDoubleOrNull() ?: 0.0

    PosContainedForm(
        title = if (initial == null) "Submit expense" else "Edit expense",
        onDismiss = onDismiss,
        confirmLabel = if (initial == null) "Submit" else "Save",
        confirmEnabled = parsedAmount > 0 && date.isNotBlank(),
        onConfirm = {
            onSave(category, parsedAmount, date.trim(), description.trim().ifBlank { null }, recurring, if (recurring) period else null)
        }
    ) {
        val t = LocalPosTokens.current
        PosFormCard {
            Column {
                Text("Category", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = t.inkTertiary)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { catOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(category, modifier = Modifier.weight(1f))
                        Text("Change", style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = catOpen, onDismissRequest = { catOpen = false }) {
                        EXPENSE_CATEGORIES.forEach { c ->
                            DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catOpen = false })
                        }
                    }
                }
            }
            PosField(value = amount, onValueChange = { amount = it }, label = "Amount", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth())
            PosField(value = date, onValueChange = { date = it }, label = "Date  (yyyy-mm-dd)", modifier = Modifier.fillMaxWidth())
            PosField(value = description, onValueChange = { description = it }, label = "Description  (optional)", modifier = Modifier.fillMaxWidth())

            // Recurring schedule (e.g. rent): approved once, then auto-posts each period.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recurring", color = t.inkPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Auto-posts every period after the first approval", color = t.inkTertiary, fontSize = 11.sp)
                }
                Switch(checked = recurring, onCheckedChange = { recurring = it })
            }
            if (recurring) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RECURRENCE_PERIODS.forEach { (code, label) ->
                        FilterChip(selected = period == code, onClick = { period = code }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

// ───────────────────────── TILL & SAFE (cashier) ─────────────────────────

/**
 * The cashier's own Till & Safe screen — the same [TillAndSafeSection] the admin console
 * renders, on the shell a cashier can actually reach.
 *
 * ★ WHY IT EXISTS. Closing the day lived only inside the admin console, so a cashier
 * finishing a shift could not count the drawer and shut up shop: they had to phone the
 * owner to come and do it, or borrow the owner's login. Which commands appear is decided
 * inside the section by capability, not here — see [TillAndSafeSection]. Taking money OUT
 * of the business stays admin-only and is not grantable at all.
 *
 * The screen itself is hidden entirely from a cashier without [Capability.CLOSE_DAY]
 * (see `Screen.viewCap`), so revoking the close also stops them reading the safe balance.
 */
@Composable
private fun CashScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("Till & Safe", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text(
                    "Count the drawer and close the day without calling the owner.",
                    color = t.inkTertiary, fontSize = 12.sp
                )
            }
        }
        item { TillAndSafeSection(vm, currency) }
    }
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(t.surface1)
                                .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                                .clickable { editing = s }
                                .padding(14.dp),
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
                                        line, fontSize = 12.sp,
                                        color = t.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                s.notes?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it, fontSize = 12.sp,
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

    PosContainedForm(
        title = if (initial == null) "New supplier" else "Edit supplier",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onSave(name, phone, email, address, notes) }
    ) {
        PosFormCard {
            PosField(value = name, onValueChange = { name = it }, label = "Supplier name", modifier = Modifier.fillMaxWidth())
            PosField(value = phone, onValueChange = { phone = it }, label = "Phone  (optional)", keyboardType = KeyboardType.Phone, modifier = Modifier.fillMaxWidth())
            PosField(value = email, onValueChange = { email = it }, label = "Email  (optional)", keyboardType = KeyboardType.Email, modifier = Modifier.fillMaxWidth())
            PosField(value = address, onValueChange = { address = it }, label = "Address  (optional)", modifier = Modifier.fillMaxWidth())
            PosField(value = notes, onValueChange = { notes = it }, label = "Notes  (optional)", modifier = Modifier.fillMaxWidth())
        }
    }
}

// ───────────────────────── PURCHASE ORDERS ─────────────────────────

private val PO_FILTERS = listOf("all", "placed", "partial", "received", "cancelled")

/** Small status pill (placed=blue, partial=amber, received=green, cancelled=red, draft=grey). */
@Composable
private fun PoStatusBadge(status: String) {
    val t = LocalPosTokens.current
    val color = when (status) {
        "placed", "sent" -> t.accentBlue
        "partial" -> t.warning
        "received" -> t.success
        "cancelled" -> t.danger
        else -> t.inkTertiary           // draft
    }
    val label = when (status) {
        "sent" -> "Placed"
        else -> status.replaceFirstChar { it.uppercase() }
    }
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            label,
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
private fun PurchaseOrdersScreen(
    vm: PosViewModel,
    currency: String,
    // Set when an "Order arriving / overdue — arrived?" notification was tapped.
    openPoId: String? = null,
    onOpened: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val pos by vm.purchaseOrders.collectAsState()
    val payables by vm.supplierPayables.collectAsState()
    val caps by vm.allowedCaps.collectAsState()
    val canManageOrders = com.portionspot.pos.auth.Capability.MANAGE_EXPENSES_ORDERS in caps
    val nowMs = remember { System.currentTimeMillis() }

    var statusFilter by remember { mutableStateOf("all") }
    var creating by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PurchaseOrderWithLines?>(null) }
    var receiving by remember { mutableStateOf<PurchaseOrderWithLines?>(null) }

    // Deep link: open that order's detail sheet — the same one the "arrivals due" banner
    // below opens, from which he confirms what actually arrived.
    LaunchedEffect(openPoId, pos) {
        val id = openPoId ?: return@LaunchedEffect
        val target = pos.firstOrNull { it.po.id == id }
        if (target == null) {
            if (pos.isNotEmpty()) onOpened()
            return@LaunchedEffect
        }
        statusFilter = "all"
        detail = target
        onOpened()
    }

    val counts = remember(pos) {
        mapOf(
            "all" to pos.size,
            "placed" to pos.count { it.po.status == "placed" || it.po.status == "sent" },
            "partial" to pos.count { it.po.status == "partial" },
            "received" to pos.count { it.po.status == "received" },
            "cancelled" to pos.count { it.po.status == "cancelled" }
        )
    }
    val filtered = remember(pos, statusFilter) {
        when (statusFilter) {
            "all" -> pos
            "placed" -> pos.filter { it.po.status == "placed" || it.po.status == "sent" }
            else -> pos.filter { it.po.status == statusFilter }
        }
    }
    // Orders near / past their ETA that still have pending lines — the visible "arrived?"
    // prompt (mirrors the admin notification the sweep raises).
    val arrivalsDue = remember(pos, nowMs) {
        pos.filter { pwl ->
            (pwl.po.status == "placed" || pwl.po.status == "sent" || pwl.po.status == "partial") &&
                pwl.po.eta?.let { nowMs >= it - 86_400_000L } == true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp)) {
                Text("Purchase Orders", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Restock requests to suppliers", color = t.inkTertiary, fontSize = 12.sp)
                if (payables > 0.005) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Owed to suppliers: ${money(payables, currency)}",
                        color = t.danger, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
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
                // Arrival prompt banner: tap an order to confirm what arrived.
                if (arrivalsDue.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(t.warning.copy(alpha = 0.12f))
                            .border(1.dp, t.warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, contentDescription = null, tint = t.warning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${arrivalsDue.size} order${if (arrivalsDue.size == 1) "" else "s"} due — confirm arrival to stock the goods",
                                color = t.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        arrivalsDue.take(3).forEach { pwl ->
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { detail = pwl }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${pwl.po.ref} · ${pwl.po.supplierName.ifBlank { "supplier" }}",
                                    color = t.inkPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text("Review", color = t.brand.s600, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(t.surface1)
                                .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                                .clickable { detail = pwl }
                                .padding(14.dp),
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
                                    fontSize = 12.sp, color = t.inkSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${pwl.lines.size} item" + (if (pwl.lines.size == 1) "" else "s") +
                                        " · " + dashTime(po.createdAt) +
                                        (po.eta?.let { " · ETA " + dashDate(it) } ?: ""),
                                    fontSize = 12.sp, color = t.inkTertiary
                                )
                                if (po.payableRemainder > 0.005) {
                                    Text(
                                        "Owed ${money(po.payableRemainder, currency)}",
                                        fontSize = 11.sp, color = t.danger, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(money(total, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = t.inkTertiary)
                        }
                    }
                }
            }
        }

        if (canManageOrders) {
            FilledTonalButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New PO")
            }
        }
    }

    if (creating) {
        PoCreateDialog(vm, currency, onDismiss = { creating = false })
    }
    detail?.let { pwl ->
        PoDetailDialog(
            pwl, currency,
            onDismiss = { detail = null },
            onConfirmAll = { vm.confirmArrival(pwl.po.id); detail = null },
            onReceivePartial = { detail = null; receiving = pwl },
            onSettle = { mode -> vm.recordSupplierPayment(pwl.po.id, mode); detail = null },
            onCancel = { vm.cancelPo(pwl.po.id); detail = null }
        )
    }
    receiving?.let { pwl ->
        PoArrivalDialog(
            pwl,
            onDismiss = { receiving = null },
            onConfirm = { received -> vm.confirmArrival(pwl.po.id, received); receiving = null }
        )
    }
}

/** One editable line in the create sheet: name, unit-cost + optional sell-price fields,
 *  qty stepper, a "stock on arrival" toggle, remove. A line with no linked item is a
 *  brand-new product (tag shown). */
@Composable
private fun PoLineRow(
    line: PurchaseOrderLine,
    onQty: (Double) -> Unit,
    onCost: (Double) -> Unit,
    onSell: (Double?) -> Unit,
    onStockToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val t = LocalPosTokens.current
    var costText by remember(line.id) {
        mutableStateOf(if (line.unitCost > 0) trimQty(line.unitCost) else "")
    }
    var sellText by remember(line.id) {
        mutableStateOf(line.sellPrice?.let { trimQty(it) } ?: "")
    }
    val isNew = line.itemId == null
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.name, fontWeight = FontWeight.SemiBold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isNew) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(t.success.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) { Text("New", color = t.success, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it; onCost(it.replace(',', '.').toDoubleOrNull() ?: 0.0) },
                label = { Text("Unit cost") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = sellText,
                onValueChange = { sellText = it; onSell(it.replace(',', '.').toDoubleOrNull()) },
                label = { Text("Sell price") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Switch(checked = line.stockOnArrival, onCheckedChange = onStockToggle)
            Spacer(Modifier.width(8.dp))
            Text(
                "Add to stock on arrival",
                color = t.inkSecondary, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** ETA quick-pick options: label → day offset from today. */
private val PO_ETA_CHOICES = listOf(
    "Today" to 0, "Tomorrow" to 1, "3 days" to 3, "1 week" to 7, "2 weeks" to 14
)

/**
 * Create sheet (B4): pick / create a supplier, add catalog OR brand-new-product lines
 * (each with qty, unit cost, optional sell price, and a "stock on arrival" flag), choose
 * a rough ETA, then pay now via the cash ledger. Paying more than the drawer holds raises
 * the same 3-option shortfall as B3 (take available → owe the supplier, owner covers, or
 * abort). The unpaid balance becomes accounts payable to the supplier.
 */
@Composable
private fun PoCreateDialog(vm: PosViewModel, currency: String, onDismiss: () -> Unit) {
    val t = LocalPosTokens.current
    val suppliers by vm.suppliers.collectAsState()
    val catalog by vm.items.collectAsState()
    val cashOnHand by vm.cashOnHand.collectAsState()
    // Stock is paid for out of the same two pots as anything else (§4).
    val tillBalance by vm.tillBalance.collectAsState()
    val safeBalance by vm.safeBalance.collectAsState()
    val isAdminSession by vm.isAdmin.collectAsState()
    var poFundingNotice by remember { mutableStateOf<String?>(null) }

    var supplierId by remember { mutableStateOf<String?>(null) }
    var supplierName by remember { mutableStateOf("") }
    var supplierOpen by remember { mutableStateOf(false) }
    var newSupplier by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var etaDays by remember { mutableStateOf<Int?>(3) }        // default rough ETA
    val lines = remember { mutableStateListOf<PurchaseOrderLine>() }

    val orderTotal = lines.sumOf { it.qty * it.unitCost }
    var payText by remember { mutableStateOf("") }
    // Default pay-now to the full order total whenever the total changes and the field is
    // untouched-blank; the owner can lower it to buy (partly) on account.
    val payNow = payText.replace(',', '.').toDoubleOrNull() ?: orderTotal
    var shortfall by remember { mutableStateOf(false) }

    val matches = remember(catalog, search, lines.size) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else catalog.filter {
            it.name.lowercase().contains(q) || (it.sku?.lowercase()?.contains(q) == true)
        }.take(20)
    }
    val exactMatch = matches.any { it.name.equals(search.trim(), ignoreCase = true) }

    fun submit(mode: String) {
        val eta = etaDays?.let { System.currentTimeMillis() + it * 86_400_000L }
        // ★ The safe is admin-only (§4). Work out here, synchronously, whether this plan
        // would open it — the ViewModel enforces the same rule, but deciding it in
        // composition is what lets the form STAY OPEN and explain itself instead of
        // vanishing while a request quietly goes to the owner.
        val want = payNow.coerceIn(0.0, orderTotal)
        val needsSafe = when (mode) {
            "safe" -> want > 0.005
            "waterfall", "available", "cash" -> want > tillBalance.coerceAtLeast(0.0) + 0.005
            else -> false
        }
        vm.createPurchaseOrder(
            supplierId, supplierName, notes, eta, lines.toList(), payNow, mode
        )
        if (needsSafe && !isAdminSession) {
            poFundingNotice = "Sent to the owner for approval — only they can open the " +
                "safe. Once they approve, the cash moves into the till and you can place " +
                "this order from there."
        } else {
            onDismiss()
        }
    }

    PosContainedForm(
        title = "New purchase order",
        onDismiss = onDismiss,
        confirmLabel = "Place order",
        confirmEnabled = lines.isNotEmpty(),
        onConfirm = {
            // The TILL alone covers it => pay from the drawer, no question asked.
            // Anything else and the payer picks the source (§4 waterfall).
            if (payNow.coerceAtMost(orderTotal) > tillBalance + 0.005) shortfall = true
            else submit("till")
        }
    ) {
        // Supplier picker (with create-on-the-fly).
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
                DropdownMenuItem(
                    text = { Text("+ New supplier…", color = t.brand.s600) },
                    onClick = { supplierOpen = false; newSupplier = true }
                )
                suppliers.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.name) },
                        onClick = { supplierId = s.id; supplierName = s.name; supplierOpen = false }
                    )
                }
            }
        }

        // Product search → tap a result to add a line, or add it as a brand-new product.
        PosField(
            value = search, onValueChange = { search = it },
            label = "Add product (name / SKU)", modifier = Modifier.fillMaxWidth()
        )
        matches.forEach { item ->
            val already = lines.any { it.itemId == item.id }
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !already) {
                        lines.add(
                            PurchaseOrderLine(
                                poId = "", itemId = item.id, name = item.name,
                                sku = item.sku, qty = 1.0, unitCost = item.cost ?: 0.0,
                                sellPrice = item.price.takeIf { it > 0 },
                                productType = item.productType
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
        // Offer to add the typed name as a brand-new product (no catalog match).
        if (search.trim().length >= 2 && !exactMatch) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        lines.add(PurchaseOrderLine(poId = "", itemId = null, name = search.trim(), qty = 1.0))
                        search = ""
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = t.brand.s600)
                Spacer(Modifier.width(8.dp))
                Text("Add \"${search.trim()}\" as a new product", color = t.brand.s600, fontWeight = FontWeight.SemiBold)
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
                    onSell = { lines[idx] = line.copy(sellPrice = it) },
                    onStockToggle = { lines[idx] = line.copy(stockOnArrival = it) },
                    onRemove = { lines.removeAt(idx) }
                )
                HorizontalDivider(color = t.surfaceBorder)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Order total", color = t.inkSecondary, modifier = Modifier.weight(1f))
                Text(money(orderTotal, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
            }
        }

        // Expected arrival (rough) — quick chips offset from today.
        Spacer(Modifier.height(8.dp))
        Text("Expected arrival", fontWeight = FontWeight.Bold, color = t.inkSecondary)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = etaDays == null, onClick = { etaDays = null }, label = { Text("None") })
            PO_ETA_CHOICES.forEach { (label, days) ->
                FilterChip(selected = etaDays == days, onClick = { etaDays = days }, label = { Text(label) })
            }
        }

        // Payment now (cash ledger). Blank = pay the full total.
        Spacer(Modifier.height(8.dp))
        Text("Pay now (cash)", fontWeight = FontWeight.Bold, color = t.inkSecondary)
        Text(
            "Cash on hand ${money(cashOnHand, currency)} · buying stock moves cash into inventory (not an expense).",
            color = t.inkTertiary, fontSize = 11.sp
        )
        OutlinedTextField(
            value = payText, onValueChange = { payText = it },
            label = { Text("Amount  (blank = full ${money(orderTotal, currency)})") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        val payableNow = (orderTotal - payNow.coerceIn(0.0, orderTotal)).coerceAtLeast(0.0)
        if (payableNow > 0.005) {
            Text("Owed to supplier: ${money(payableNow, currency)}", color = t.danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        PosField(
            value = notes, onValueChange = { notes = it },
            label = "Notes  (optional)", modifier = Modifier.fillMaxWidth()
        )
    }

    if (newSupplier) {
        SupplierModal(initial = null, onDismiss = { newSupplier = false }) { name, phone, email, address, snotes ->
            val id = java.util.UUID.randomUUID().toString()
            vm.saveSupplier(id, name, phone, email, address, snotes)
            supplierId = id; supplierName = name; newSupplier = false
        }
    }
    if (shortfall) {
        FundingSourceDialog(
            title = "How is this paid?",
            amount = payNow.coerceAtMost(orderTotal),
            till = tillBalance,
            safe = safeBalance,
            currency = currency,
            isAdmin = isAdminSession,
            onDismiss = { shortfall = false },
            onChoose = { mode -> shortfall = false; submit(mode) }
        )
    }
    poFundingNotice?.let { msg ->
        AlertDialog(
            onDismissRequest = { poFundingNotice = null },
            title = { Text("Waiting on the owner") },
            text = { Text(msg, color = t.inkSecondary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { poFundingNotice = null; onDismiss() }) { Text("OK") }
            }
        )
    }
}

/**
 * Read-only detail sheet + lifecycle actions (B4). Shows the ETA, the payment split
 * (cash paid / owner-covered / still owed) and, for an open order, the arrival actions:
 * "Confirm arrival (all)" stocks every pending line at once, "Receive part…" opens the
 * per-line arrival sheet. A supplier balance can be settled from cash.
 */
@Composable
private fun PoDetailDialog(
    pwl: PurchaseOrderWithLines,
    currency: String,
    onDismiss: () -> Unit,
    onConfirmAll: () -> Unit,
    onReceivePartial: () -> Unit,
    onSettle: (mode: String) -> Unit,
    onCancel: () -> Unit
) {
    val t = LocalPosTokens.current
    val po = pwl.po
    val total = pwl.lines.sumOf { it.qty * it.unitCost }
    val open = po.status == "placed" || po.status == "sent" || po.status == "partial" || po.status == "draft"
    val canReceive = po.status == "placed" || po.status == "sent" || po.status == "partial"
    var settleShort by remember { mutableStateOf(false) }

    PosDialog(title = po.ref, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PoStatusBadge(po.status)
            Spacer(Modifier.width(8.dp))
            Text(po.supplierName.ifBlank { "No supplier" }, color = t.inkSecondary)
        }
        Text(
            dashTime(po.createdAt) + (po.eta?.let { " · ETA " + dashDate(it) } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
        )
        PosFormCard {
            pwl.lines.forEach { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.name, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (l.itemId == null) {
                                Spacer(Modifier.width(6.dp))
                                Text("(new)", color = t.success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val recvd = l.receivedQty ?: 0.0
                        val pending = (l.qty - recvd).coerceAtLeast(0.0)
                        Text(
                            "${trimQty(l.qty)} × ${money(l.unitCost, currency)}" +
                                (if (recvd > 0.0) " · received ${trimQty(recvd)}" else "") +
                                (if (l.stockOnArrival && pending > 0.0) " · ${trimQty(pending)} pending" else "") +
                                (if (!l.stockOnArrival) " · not stocked" else ""),
                            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                        )
                    }
                    Text(money(l.qty * l.unitCost, currency), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("Total", fontWeight = FontWeight.Bold, color = t.inkSecondary, modifier = Modifier.weight(1f))
                Text(money(total, currency), fontWeight = FontWeight.Black, color = t.inkPrimary)
            }
        }
        // Payment split.
        PosFormCard {
            if (po.cashPaid > 0.005) DetailMoneyRow("Paid (cash)", po.cashPaid, currency, t.inkSecondary)
            if (po.capitalPaid > 0.005) DetailMoneyRow("Owner covered", po.capitalPaid, currency, t.inkSecondary)
            if (po.payableRemainder > 0.005) DetailMoneyRow("Owed to supplier", po.payableRemainder, currency, t.danger)
            if (po.cashPaid <= 0.005 && po.capitalPaid <= 0.005 && po.payableRemainder <= 0.005) {
                Text("Fully settled.", color = t.inkTertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        po.notes?.takeIf { it.isNotBlank() }?.let {
            Text("Notes", fontWeight = FontWeight.Bold, color = t.inkSecondary)
            Text(it, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
        }
        // Arrival actions.
        if (canReceive) {
            Button(
                onClick = onConfirmAll, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("Confirm arrival (all)") }
            OutlinedButton(onClick = onReceivePartial, modifier = Modifier.fillMaxWidth()) {
                Text("Receive part…")
            }
        }
        // Settle a supplier balance from cash.
        if (po.payableRemainder > 0.005 && po.status != "cancelled") {
            Button(
                onClick = { settleShort = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.surface2, contentColor = t.inkPrimary)
            ) { Text("Pay supplier ${money(po.payableRemainder, currency)}") }
        }
        if (open) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel PO", color = t.danger)
            }
        }
    }

    if (settleShort) {
        AlertDialog(
            onDismissRequest = { settleShort = false },
            title = { Text("Pay supplier balance") },
            text = {
                Text(
                    "Pay ${money(po.payableRemainder, currency)} to ${po.supplierName.ifBlank { "the supplier" }} from cash on hand. If the drawer is short, pay what's there and keep the rest owed.",
                    color = t.inkSecondary, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { settleShort = false; onSettle("cash") }) { Text("Pay in full") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { settleShort = false; onSettle("available") }) { Text("Pay available") }
                    TextButton(onClick = { settleShort = false }) { Text("Cancel") }
                }
            }
        )
    }
}

/** One label/amount row in the PO detail payment card. */
@Composable
private fun DetailMoneyRow(label: String, amount: Double, currency: String, valueColor: Color) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = t.inkSecondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(money(amount, currency), color = valueColor, fontWeight = FontWeight.Bold)
    }
}

/**
 * Arrival sheet (§10.5): per-line quantity that has ARRIVED, defaulting to the still-
 * outstanding quantity so a full arrival is one tap. Confirming moves the entered
 * quantity from pending into real sellable stock.
 */
@Composable
private fun PoArrivalDialog(
    pwl: PurchaseOrderWithLines,
    onDismiss: () -> Unit,
    onConfirm: (received: Map<String, Double>) -> Unit
) {
    val t = LocalPosTokens.current
    // Only lines still awaiting stock are actionable.
    val open = pwl.lines.filter { it.stockOnArrival && (it.qty - (it.receivedQty ?: 0.0)) > 0.0 }
    val entered = remember {
        mutableMapOf<String, String>().apply {
            open.forEach { put(it.id, trimQty((it.qty - (it.receivedQty ?: 0.0)).coerceAtLeast(0.0))) }
        }
    }

    PosContainedForm(
        title = "Arrival — ${pwl.po.ref}",
        onDismiss = onDismiss,
        confirmLabel = "Confirm arrival",
        onConfirm = {
            val parsed = entered.mapValues { (_, v) -> v.replace(',', '.').toDoubleOrNull() ?: 0.0 }
            onConfirm(parsed)
        }
    ) {
        Text(
            "Enter what has arrived. It moves from pending into sellable stock.",
            color = t.inkTertiary, style = MaterialTheme.typography.bodySmall
        )
        open.forEach { l ->
            val outstanding = (l.qty - (l.receivedQty ?: 0.0)).coerceAtLeast(0.0)
            var qtyText by remember(l.id) { mutableStateOf(entered[l.id] ?: trimQty(outstanding)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("pending ${trimQty(outstanding)}", style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                }
                PosField(
                    value = qtyText,
                    onValueChange = { qtyText = it; entered[l.id] = it },
                    label = "Arrived",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.width(120.dp)
                )
            }
        }
    }
}

// ───────────────────────── CHANGE & CREDIT ─────────────────────────

/**
 * Whole-shop credit ledger — a port of the web Change & Credit page. Balances are
 * DERIVED (never stored) over TWO ledgers: what customers owe the shop (credit_owed −
 * credit_paid) and what the shop owes customers (change_owed/refund_owed − change_paid/
 * refund_paid). Two summary cards surface both totals. The ledger renders every row
 * with the right label/sign/colour: tapping a debt charge opens Record payment; tapping
 * a change/refund the shop owes opens Pay out. (Overpaying a debt is booked as change
 * owed by the repository, so it lands here too.)
 */
/** One customer's rolled-up position in each ledger, derived from their transactions:
 *  [creditBal] = what they owe the shop (debt); [changeBal] = what the shop owes them. */
private data class CcAccount(
    val customer: Customer,
    val name: String,
    val creditBal: Double,
    val changeBal: Double,
    val hasCredit: Boolean,
    val hasChange: Boolean,
    val lastAt: Long,
    val lastRef: String?,
)

@Composable
private fun ChangeCreditScreen(vm: PosViewModel, currency: String) {
    val t = LocalPosTokens.current
    val ledger by vm.creditLedger.collectAsState()
    val customers by vm.customers.collectAsState()
    // Money the shop owes back to customers (change booked to account + unpaid refunds).
    val totalOwedToCustomers by vm.totalChangeOwedFlow().collectAsState(initial = 0.0)

    var search by remember { mutableStateOf("") }
    var ledgerMode by remember { mutableStateOf("credit") }   // credit (they owe you) | change (you owe them)
    var status by remember { mutableStateOf("unsettled") }    // unsettled | settled
    var detailFor by remember { mutableStateOf<Customer?>(null) }

    val totalOwed = customers.sumOf { it.balance.coerceAtLeast(0.0) }

    // Roll the flat ledger up per customer into the two derived balances.
    val accounts = remember(ledger, customers) {
        val custById = customers.associateBy { it.customer.id }
        ledger.groupBy { it.txn.customerId }.mapNotNull { (cid, rows) ->
            val cust = custById[cid]?.customer ?: return@mapNotNull null
            var creditOwed = 0.0; var creditPaid = 0.0
            var changeOwed = 0.0; var changePaid = 0.0
            var hasCredit = false; var hasChange = false
            var lastAt = 0L; var lastRef: String? = null
            rows.forEach { r ->
                when (r.txn.type) {
                    "credit_owed" -> { creditOwed += r.txn.amount; hasCredit = true }
                    "credit_paid" -> { creditPaid += r.txn.amount; hasCredit = true }
                    "change_owed", "refund_owed" -> { changeOwed += r.txn.amount; hasChange = true }
                    "change_paid", "refund_paid" -> { changePaid += r.txn.amount; hasChange = true }
                }
                if (r.txn.createdAt >= lastAt) {
                    lastAt = r.txn.createdAt
                    r.saleRef?.let { lastRef = it }
                }
            }
            CcAccount(cust, cust.name, creditOwed - creditPaid, changeOwed - changePaid, hasCredit, hasChange, lastAt, lastRef)
        }
    }

    val q = search.trim().lowercase()
    val isCredit = ledgerMode == "credit"
    val settledView = status == "settled"
    val rows = accounts.filter { a ->
        val hasLedger = if (isCredit) a.hasCredit else a.hasChange
        val bal = if (isCredit) a.creditBal else a.changeBal
        val matchesStatus = if (settledView) bal <= 0.005 else bal > 0.005
        hasLedger && matchesStatus && (q.isEmpty() || a.name.lowercase().contains(q))
    }.sortedWith(
        compareByDescending<CcAccount> { if (isCredit) it.creditBal else it.changeBal }.thenBy { it.name }
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text("Change & Credit", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("What customers owe you, and what you owe them", color = t.inkTertiary, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PosMetricCard(
                    "Customers owe you", money(totalOwed, currency),
                    Modifier.weight(1f), accent = t.danger, sub = "credit outstanding"
                )
                PosMetricCard(
                    "You owe customers", money(totalOwedToCustomers, currency),
                    Modifier.weight(1f), accent = t.brand.s600, sub = "change + refunds"
                )
            }
            Spacer(Modifier.height(12.dp))

            // View 1 — whose ledger: customer debt vs change the shop owes back.
            PosSegmented(
                listOf("credit" to "Credit", "change" to "Change"),
                ledgerMode
            ) { ledgerMode = it }
            Spacer(Modifier.height(8.dp))
            // View 2 — outstanding vs cleared.
            PosSegmented(
                listOf("unsettled" to "Unsettled", "settled" to "Settled"),
                status
            ) { status = it }
            Spacer(Modifier.height(10.dp))

            PosField(
                value = search, onValueChange = { search = it },
                label = "Search customer", placeholder = "Name", modifier = Modifier.fillMaxWidth()
            )
        }

        if (rows.isEmpty()) {
            // weight(1f) (not fillMaxSize) so the empty state only claims the space LEFT
            // under the header — on the short Sunmi screen fillMaxSize pushed the centred
            // text down past the bottom nav, clipping "No one owes you right now."
            // verticalScroll guarantees it stays reachable even if the header is tall.
            Box(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.Payments, contentDescription = null, tint = t.inkTertiary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            settledView && isCredit -> "No settled debts yet."
                            settledView -> "No settled change yet."
                            isCredit -> "No one owes you right now."
                            else -> "You don't owe any change."
                        },
                        color = t.inkTertiary, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                // Bottom room clears the floating cart FAB on this non-Sell screen.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.customer.id }) { a ->
                    val bal = if (isCredit) a.creditBal else a.changeBal
                    val accent = when {
                        settledView -> t.success
                        isCredit -> t.danger
                        else -> t.brand.s600
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(t.surface1)
                            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                            .clickable { detailFor = a.customer }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(a.name.take(1).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.Bold, color = t.inkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = if (a.lastRef != null) "From sale #${a.lastRef} · ${dashTime(a.lastAt)}"
                            else dashTime(a.lastAt)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Both ledgers running at once → say where they net out, so
                            // the number on the right isn't read as the whole story.
                            if (a.creditBal > 0.005 && a.changeBal > 0.005) {
                                val netBal = a.creditBal - a.changeBal
                                val netSettled = abs(netBal) <= 0.005
                                Text(
                                    when {
                                        netSettled -> "Net: settled"
                                        netBal > 0 -> "Net: owes you ${money(netBal, currency)}"
                                        else -> "Net: you owe ${money(-netBal, currency)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        netSettled -> t.success
                                        netBal > 0 -> t.danger
                                        else -> t.brand.s600
                                    },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (settledView) {
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(t.success.copy(alpha = 0.13f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) { Text("Settled", color = t.success, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        } else {
                            Text(money(bal, currency), fontWeight = FontWeight.Black, fontSize = 16.sp, color = accent)
                        }
                    }
                }
            }
        }
    }

    detailFor?.let { c ->
        CustomerDetailDialog(vm = vm, customer = c, currency = currency, onDismiss = { detailFor = null })
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
    // ★ CASH BASIS (§5): revenue and profit now count money that has actually ARRIVED.
    // An unpaid credit sale is not revenue; a repayment is revenue on the day it lands;
    // cost of goods is pro-rated to the collected share. See CashBasis for the contract.
    val cashBasis by vm.dashCashBasis.collectAsState()
    val cashVariance by vm.dashCashVariance.collectAsState()
    val grossProfit = cashBasis.grossProfit
    val costedRevenue = cashBasis.costedRevenue
    val dashExpenses by vm.dashExpenses.collectAsState()
    val cashOnHand by vm.cashOnHand.collectAsState()
    val dailyBars by vm.dashDailyBars.collectAsState()
    val items by vm.items.collectAsState()
    val customers by vm.customers.collectAsState()
    val recent by vm.recentSales.collectAsState()

    var showZ by remember { mutableStateOf(false) }

    // Health figures, computed from the live catalog/ledger (like the web).
    // Below zero is counted SEPARATELY from out-of-stock, and deliberately not in both:
    // rolled together, the one figure that means "the record is wrong" disappears into the
    // one that means "we sold out", which is the count an owner scrolls past.
    val shortStock = items.count { it.trackStock && it.stockIsShort() }
    val outStock = items.count { it.trackStock && it.onHand <= 0.0 && !it.stockIsShort() }
    val lowStock = items.count {
        it.trackStock && it.onHand > 0.0 && it.onHand <= lowStockLevel(it)
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

        // Revenue hero — MONEY COLLECTED, not money billed (§5). A sale on account is
        // recorded, stocked and owed, but it is not revenue until the money arrives; when
        // it does, it counts on that day. The billed-but-unpaid figure sits underneath as
        // a figure, never as sales.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = t.brand.s600)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Money collected", color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(money(cashBasis.revenue, currency), color = t.inkOnBrand, fontWeight = FontWeight.Black, fontSize = 32.sp)
                Text(
                    "${summary.count} sale${if (summary.count == 1) "" else "s"} · ${range.label}",
                    color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 12.sp
                )
                if (cashBasis.uncollected > 0.005) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Plus ${money(cashBasis.uncollected, currency)} sold on credit and not " +
                            "yet paid — it counts when the money comes in.",
                        color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 11.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // Every tile says WHICH CLOCK it is on. Most are windowed by the range chips
        // above; a couple are running balances that ignore the window entirely, and
        // two of those used to sit un-labelled beside a windowed figure.
        val periodLabel = range.label
        val liveLabel = "Live balance"

        // KPI tiles.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashKpiCard("Transactions", summary.count.toString(), Modifier.weight(1f), sub = periodLabel)
            DashKpiCard("Average sale", money(avgSale, currency), Modifier.weight(1f), sub = periodLabel)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showProfit) {
                DashKpiCard("Gross profit", money(grossProfit, currency), Modifier.weight(1f),
                    valueColor = t.success, sub = periodLabel)
                DashKpiCard("Margin", "${trimPct(marginPct)}%", Modifier.weight(1f), sub = periodLabel)
            } else {
                // Outstanding customer debt is a running total, not a window figure.
                DashKpiCard("Pending credit", money(pendingCredit, currency), Modifier.weight(1f),
                    valueColor = t.warning, sub = liveLabel)
                DashKpiCard("Discounts", money(summary.discount, currency), Modifier.weight(1f), sub = periodLabel)
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
        Spacer(Modifier.height(10.dp))

        // Accounting spine (B3): cash-on-hand + net profit AFTER expenses. Net profit is
        // DERIVED — gross profit less the expenses posted in this window — never a stored
        // pot, so recording an expense lowers it automatically.
        //
        // These two are on DIFFERENT CLOCKS and sit side by side: cash on hand is the
        // opening float plus every cash movement ever (no date filter at all), while net
        // profit only covers the selected range. Each carries its own period label so the
        // pair can never be read as one comparison.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashKpiCard("Cash on hand", money(cashOnHand, currency), Modifier.weight(1f),
                valueColor = if (cashOnHand < 0) t.danger else t.inkPrimary, sub = liveLabel)
            if (showProfit) {
                // ★ A day-close shortage is a real LOSS and an overage a real gain, so it
                // belongs in net profit — but on its own line, never folded into gross
                // profit, so a shortage reads as a shortage instead of eating margin.
                val netProfit = grossProfit - dashExpenses + cashVariance
                DashKpiCard("Net profit", money(netProfit, currency), Modifier.weight(1f),
                    valueColor = if (netProfit < 0) t.danger else t.success, sub = periodLabel)
            } else {
                DashKpiCard("Expenses", money(dashExpenses, currency), Modifier.weight(1f),
                    valueColor = t.danger, sub = periodLabel)
            }
        }
        if (kotlin.math.abs(cashVariance) > 0.005) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (cashVariance < 0) "Cash short ${money(-cashVariance, currency)} — counted at close, taken off profit."
                else "Cash over ${money(cashVariance, currency)} — counted at close, added to profit.",
                color = if (cashVariance < 0) t.danger else t.warning,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
        }
        if (showProfit && (dashExpenses > 0.0 || kotlin.math.abs(cashVariance) > 0.005)) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Net profit = gross profit ${money(grossProfit, currency)} − expenses " +
                    "${money(dashExpenses, currency)}" +
                    (if (kotlin.math.abs(cashVariance) > 0.005)
                        " ${if (cashVariance < 0) "−" else "+"} cash ${if (cashVariance < 0) "short" else "over"} ${money(kotlin.math.abs(cashVariance), currency)}"
                    else ""),
                color = t.inkTertiary, fontSize = 11.sp
            )
        }
        if (showProfit) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Profit counts goods sold AND paid for. Cost is shared out to match what " +
                    "has been collected, so a part-paid sale never looks like a loss.",
                color = t.inkTertiary, fontSize = 10.sp
            )
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
                    // Only when there IS one — an owner who has never oversold should not
                    // be taught to ignore a row that always reads zero.
                    if (shortStock > 0) DashAlertRow("Stock take needed", shortStock, t.danger)
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

/**
 * One dashboard metric tile. [sub] is the PERIOD the figure covers ("Today",
 * "Last 7 days", or "Live balance" for a running total that ignores the date chips) —
 * without it, a windowed figure and an all-time one read as the same kind of number.
 */
@Composable
private fun DashKpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    sub: String? = null,
) {
    // Shares the design-system metric tile so the dashboard matches every other screen.
    PosMetricCard(label = label, value = value, modifier = modifier, accent = valueColor, sub = sub)
}

/** Card with a bold title and arbitrary body, followed by spacing. */
@Composable
private fun DashSectionCard(title: String, content: @Composable () -> Unit) {
    val t = LocalPosTokens.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface1)
            .border(1.dp, t.surfaceBorder, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, color = t.inkPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        content()
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

    PosDialog(title = "Z-Report · Cash up", onDismiss = onDismiss) {
        Text("Today, ${todays.size} sale${if (todays.size == 1) "" else "s"}", color = t.inkTertiary, fontSize = 12.sp)
        PosFormCard {
            ZRow("Sales today", money(totalSales, currency))
            ZRow("Cash sales", money(cashSales, currency))
            PosField(
                opening, { opening = it.filter { c -> c.isDigit() || c == '.' } },
                "Opening float", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
            )
            ZRow("Expected in drawer", money(expected, currency))
            PosField(
                counted, { counted = it.filter { c -> c.isDigit() || c == '.' } },
                "Counted cash", keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth()
            )
            if (cnt != null) {
                val (lbl, col) = when {
                    kotlin.math.abs(variance) < 0.005 -> "Balanced" to t.success
                    variance > 0 -> "Over by ${money(variance, currency)}" to t.warning
                    else -> "Short by ${money(-variance, currency)}" to t.danger
                }
                Text(lbl, color = col, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
    }
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

/** Date-only formatter (e.g. an order ETA — no time component). */
private fun dashDate(ms: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))

// ───────────────────────── REPORTS ─────────────────────────

@Composable
private fun ReportsScreen(vm: PosViewModel, business: Business) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val range by vm.reportRange.collectAsState()
    val summary by vm.reportSummary.collectAsState()
    val breakdown by vm.reportBreakdown.collectAsState()
    val refunds by vm.reportRefunds.collectAsState()
    val fullyRefunded by vm.reportFullyRefunded.collectAsState()
    // ★ CASH BASIS (§5): what was actually COLLECTED in this window, and the cost of the
    // goods behind it. Billed-but-unpaid credit is reported separately, never as sales.
    val cashBasis by vm.reportCashBasis.collectAsState()
    val cashVariance by vm.reportCashVariance.collectAsState()
    // Net takings: refunded money comes off gross, and a fully-refunded sale stops
    // counting as a live sale (prompt §5). Gross stays visible in the breakdown.
    val netSales = (summary.gross - refunds).coerceAtLeast(0.0)
    val netCount = (summary.count - fullyRefunded).coerceAtLeast(0)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
    ) {
        Text("Sales & Reports", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))

        // Date-window picker.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReportRange.values().forEach { r ->
                FilterChip(selected = range == r, onClick = { vm.setReportRange(r) }, label = { Text(r.label) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // Headline hero — net takings for the window.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.brand.s600).padding(20.dp)
        ) {
            Text(
                if (refunds > 0.0) "Total sales (net)" else "Total sales",
                color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            Text(money(netSales, currency), color = t.inkOnBrand, fontWeight = FontWeight.Black, fontSize = 30.sp)
            Text(
                "$netCount sale${if (netCount == 1) "" else "s"} · ${range.label}",
                color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        // VAT / discounts / averages.
        PosFormCard {
            PosSectionLabel("Breakdown")
            if (business.vatEnabled) {
                ReportStatRow("Sales excl. VAT", money(summary.net, currency))
                ReportStatRow("VAT collected (${trimPct(business.vatPercent)}%)", money(summary.vat, currency))
            }
            ReportStatRow("Discounts given", money(summary.discount, currency))
            if (refunds > 0.0) {
                ReportStatRow("Gross sales", money(summary.gross, currency))
                ReportStatRow("Refunds paid", "-${money(refunds, currency)}")
                HorizontalDivider(color = t.surfaceBorder)
                ReportStatRow("Net sales", money(netSales, currency))
            }
            if (netCount > 0) {
                ReportStatRow("Average sale", money(netSales / netCount, currency))
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── §5 What actually came in, and what it cost ──
        // The card above is BILLED value (what the receipts add up to). This one is CASH
        // BASIS: money that arrived. The two differ by exactly the credit that has not
        // been collected, which is why both are shown rather than one silently replacing
        // the other.
        PosFormCard {
            PosSectionLabel("Money actually collected")
            ReportStatRow("Collected in this period", money(cashBasis.revenue, currency))
            ReportStatRow("Cost of what was sold", "-${money(cashBasis.cogs, currency)}")
            HorizontalDivider(color = t.surfaceBorder)
            ReportStatRow("Gross profit", money(cashBasis.grossProfit, currency))
            if (kotlin.math.abs(cashVariance) > 0.005) {
                ReportStatRow(
                    if (cashVariance < 0) "Cash short at close" else "Cash over at close",
                    (if (cashVariance < 0) "-" else "+") + money(kotlin.math.abs(cashVariance), currency)
                )
            }
            if (cashBasis.uncollected > 0.005) {
                ReportStatRow("Sold on credit, not yet paid", money(cashBasis.uncollected, currency))
            }
            Text(
                "A sale counts when the money arrives, not when the receipt is written. " +
                    "A later repayment counts on its own day, and the cost of the goods is " +
                    "shared out to match — so nothing is ever counted twice.",
                color = t.inkTertiary, fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        // Money in, grouped by tender.
        PosFormCard {
            PosSectionLabel("By payment method")
            if (breakdown.isEmpty()) {
                Text("No sales in this period.", color = t.inkTertiary, style = MaterialTheme.typography.bodySmall)
            } else {
                breakdown.forEach { row ->
                    val label = PaymentMethod.fromCode(row.method)?.label
                        ?: if (row.method == "credit") "Credit (unpaid)" else row.method
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = t.inkPrimary)
                            Text(
                                "${row.count} sale${if (row.count == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                            )
                        }
                        Text(money(row.total, currency), fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportStatRow(label: String, value: String) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = t.inkSecondary)
        Text(value, fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
    }
}

// ───────────────────────── RECEIPTS ─────────────────────────

@Composable
private fun ReceiptsScreen(
    vm: PosViewModel,
    business: Business,
    printer: PrinterUi,
    // Set when a "Large sale" notification was tapped: the receipt to open.
    openSaleId: String? = null,
    onOpened: () -> Unit = {}
) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val caps by vm.allowedCaps.collectAsState()
    val canRefund = com.portionspot.pos.auth.Capability.PROCESS_REFUNDS in caps
    val canEditReceipts = com.portionspot.pos.auth.Capability.EDIT_RECEIPTS in caps
    val sales by vm.recentSales.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val refundedBySale by vm.refundedBySale.collectAsState()
    // ★ CASH BASIS (§5), the same source the Dashboard hero reads. The figure is money
    // that ARRIVED today — today's settled sales plus repayments of older debts — not the
    // day's billed value. An unpaid credit sale is a receipt and a debt; it is not takings
    // until the money comes in, and it shows underneath as exactly that.
    val todayMoney by vm.todayCashBasis.collectAsState()
    val countToday by vm.todayCount.collectAsState()
    var refundFor by remember { mutableStateOf<SaleEntity?>(null) }
    var detailFor by remember { mutableStateOf<SaleEntity?>(null) }
    var editFor by remember { mutableStateOf<SaleEntity?>(null) }
    var showQuotes by remember { mutableStateOf(false) }

    // Deep link: open that receipt's detail sheet. [recentSales] is a window, not the whole
    // ledger — an older sale simply lands the owner on the Receipts list, which is honest.
    LaunchedEffect(openSaleId, sales) {
        val id = openSaleId ?: return@LaunchedEffect
        val target = sales.firstOrNull { it.id == id }
        if (target == null) {
            if (sales.isNotEmpty()) onOpened()
            return@LaunchedEffect
        }
        showQuotes = false
        detailFor = target
        onOpened()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text("Receipts", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Spacer(Modifier.height(10.dp))
            // Today's MONEY COLLECTED hero (§5) — not the day's billed total.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.brand.s600).padding(16.dp)
            ) {
                // Says what it is, in the Dashboard's words: a reader must be able to tell
                // this is money RECEIVED and not the day's billed total, or the two
                // screens read as a contradiction rather than as two different questions.
                Text(
                    "Money collected today", color = t.inkOnBrand.copy(alpha = 0.85f),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Text(money(todayMoney.revenue, currency), color = t.inkOnBrand, fontWeight = FontWeight.Black, fontSize = 28.sp)
                Text("$countToday sale${if (countToday == 1) "" else "s"}", color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 12.sp)
                if (todayMoney.uncollected > 0.005) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Plus ${money(todayMoney.uncollected, currency)} sold on credit and not " +
                            "yet paid — it counts when the money comes in.",
                        color = t.inkOnBrand.copy(alpha = 0.85f), fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Receipts ⇄ Quotes (§1.2 parity). Quotes are local documents, never a sale.
            PosSegmented(
                listOf(
                    "receipts" to "Receipts",
                    "quotes" to (if (quotes.isNotEmpty()) "Quotes (${quotes.size})" else "Quotes")
                ),
                if (showQuotes) "quotes" else "receipts"
            ) { showQuotes = it == "quotes" }
        }
        if (showQuotes) {
            QuotesList(quotes, currency, business, printer, vm)
        } else if (sales.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No sales yet", color = t.inkTertiary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                // Extra bottom room so the floating cart FAB (shown on non-Sell screens
                // when a sale is in progress) never covers the last receipt's actions.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sales, key = { it.id }) { sale ->
                    val refunded = refundedBySale[sale.id] ?: 0.0
                    val fullyRefunded = refunded > 0.0 && refunded >= sale.total - 0.01
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(t.surface1)
                            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                            // Tapping the receipt opens the full detail view (B5 item 6).
                            .clickable { detailFor = sale }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}", fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                if (refunded > 0.0) {
                                    Spacer(Modifier.width(6.dp))
                                    RefundedBadge(fully = fullyRefunded)
                                }
                                if (sale.wasEdited) {
                                    Spacer(Modifier.width(6.dp))
                                    EditedBadge()
                                }
                            }
                            Text(
                                if (sale.synced) "Synced" else "On device",
                                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                            )
                        }
                        Text(
                            money(sale.total, currency),
                            fontWeight = FontWeight.Bold, color = t.inkPrimary,
                            textDecoration = if (fullyRefunded) TextDecoration.LineThrough else null
                        )
                        IconButton(onClick = { printer.printReceipt(business, sale) { vm.loadLines(sale.id) } }) {
                            Icon(Icons.Filled.Print, contentDescription = "Reprint", tint = t.inkSecondary)
                        }
                        IconButton(onClick = { printer.sharePdfReceipt(business, sale) { vm.loadLines(sale.id) } }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share PDF", tint = t.inkSecondary)
                        }
                        if (canRefund) {
                            IconButton(onClick = { refundFor = sale }) {
                                Icon(Icons.Filled.AssignmentReturn, contentDescription = "Refund", tint = t.inkSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    refundFor?.let { sale ->
        RefundDialog(vm, business, sale, onDismiss = { refundFor = null })
    }
    detailFor?.let { sale ->
        // Re-read the live row so the sheet reflects an edit made moments ago.
        val live = sales.firstOrNull { it.id == sale.id } ?: sale
        SaleDetailDialog(
            vm, business, live,
            refunded = refundedBySale[live.id] ?: 0.0,
            canEditReceipts = canEditReceipts,
            onDismiss = { detailFor = null },
            onEdit = { detailFor = null; editFor = live }
        )
    }
    editFor?.let { sale ->
        val live = sales.firstOrNull { it.id == sale.id } ?: sale
        EditReceiptDialog(vm, business, live, onDismiss = { editFor = null })
    }
}

/**
 * Small pill marking a receipt that was corrected in place inside the edit window.
 * The receipt itself is a single row that was rewritten; the "what changed" history
 * lives in the append-only audit log, shown in the detail view.
 */
@Composable
private fun EditedBadge() {
    val t = LocalPosTokens.current
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(t.brand.s600.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("Edited", color = t.brand.s600, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
    val t = LocalPosTokens.current
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(quotes, key = { it.id }) { q ->
            val expired = q.validUntil != null && q.validUntil < now
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface1)
                    .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("#${q.receiptNo ?: q.id.takeLast(6).uppercase()}", fontWeight = FontWeight.Bold, color = t.inkPrimary)
                    val vu = q.validUntil?.let { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(it)) }
                    Text(
                        when {
                            vu == null -> q.customerName ?: "Quote"
                            expired -> "Expired $vu"
                            else -> "Valid until $vu"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) t.danger else t.inkTertiary
                    )
                }
                Text(money(q.total, currency), fontWeight = FontWeight.Bold, color = t.inkPrimary)
                IconButton(onClick = {
                    vm.loadQuoteToCart(q.id) {
                        Toast.makeText(ctx, "Loaded into cart — open Sell to check out", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.ShoppingCart, contentDescription = "Load quote into cart", tint = t.inkSecondary)
                }
                IconButton(onClick = { printer.printReceipt(business, q) { vm.loadLines(q.id) } }) {
                    Icon(Icons.Filled.Print, contentDescription = "Reprint quote", tint = t.inkSecondary)
                }
                IconButton(onClick = { printer.sharePdfReceipt(business, q) { vm.loadLines(q.id) } }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share quote PDF", tint = t.inkSecondary)
                }
            }
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
    val t = LocalPosTokens.current
    val color = if (fully) t.danger else t.warning
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            if (fully) "Refunded" else "Part refund",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────── RECEIPT: VIEW + EDIT (B5) ───────────────────

/** One label/value line in the receipt breakdown. [strong] emboldens the grand total. */
@Composable
private fun ReceiptMoneyRow(
    label: String,
    value: String,
    strong: Boolean = false,
    accent: Color? = null,
) {
    val t = LocalPosTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            fontSize = if (strong) 15.sp else 13.sp,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
            color = accent ?: if (strong) t.inkPrimary else t.inkSecondary
        )
        Text(
            value,
            fontSize = if (strong) 15.sp else 13.sp,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
            color = accent ?: t.inkPrimary
        )
    }
}

/**
 * Full receipt detail (B5 item 6): the line items, the discount/markup/VAT/total
 * breakdown, the tenders, and whatever change or balance is still outstanding.
 *
 * Also the entry point to correcting it: while the sale is inside the shop's edit
 * window an "Edit receipt" action shows; once the window has passed the sheet says so
 * and the receipt is read-only, because a later correction has to go through a
 * void/refund so the money leaves a trail. An edited receipt shows its append-only
 * audit history at the bottom.
 */
@Composable
private fun SaleDetailDialog(
    vm: PosViewModel,
    business: Business,
    sale: SaleEntity,
    refunded: Double,
    canEditReceipts: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val t = LocalPosTokens.current
    val currency = business.currency
    var lines by remember(sale.id) { mutableStateOf<List<SaleLine>?>(null) }
    var payments by remember(sale.id) { mutableStateOf<List<SalePayment>>(emptyList()) }
    val history by vm.auditForSale(sale.id).collectAsState(initial = emptyList())

    LaunchedEffect(sale.id, sale.editCount) {
        lines = vm.loadLines(sale.id)
        payments = vm.loadPayments(sale.id)
    }

    val windowMins = vm.saleEditWindowMinutes
    val editable = sale.isEditable(windowMins) && refunded <= 0.005 && canEditReceipts
    val minutesLeft = ((sale.soldAt + windowMins * 60_000L - System.currentTimeMillis()) / 60_000L)
        .coerceAtLeast(0L)
    val stamp = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(sale.soldAt))
    // Money still moving in either direction after the sale settled.
    val owing = (sale.total - sale.amountPaid + (sale.changeDue ?: 0.0)).coerceAtLeast(0.0)

    PosDialog(
        title = "Receipt #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}",
        onDismiss = onDismiss
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stamp, fontSize = 12.sp, color = t.inkTertiary)
            if (sale.wasEdited) {
                Spacer(Modifier.width(8.dp))
                EditedBadge()
            }
        }
        sale.customerName?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 13.sp, color = t.inkSecondary)
        }
        sale.createdByName?.takeIf { it.isNotBlank() }?.let {
            Text("Served by $it", fontSize = 12.sp, color = t.inkTertiary)
        }

        HorizontalDivider(color = t.surfaceBorder)

        // ── line items ──
        val ls = lines
        if (ls == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = t.brand.s600)
            }
        } else if (ls.isEmpty()) {
            Text("No items on this receipt", fontSize = 13.sp, color = t.inkTertiary)
        } else {
            ls.forEach { line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, fontSize = 14.sp, color = t.inkPrimary)
                        Text(
                            "${fmtQty(line.qty)} @ ${money(line.unitPrice, currency)}",
                            fontSize = 12.sp, color = t.inkTertiary
                        )
                        if (line.lineDiscount > 0.0) {
                            Text(
                                "Less ${money(line.lineDiscount, currency)}",
                                fontSize = 12.sp, color = t.danger
                            )
                        }
                        if (line.lineMarkup > 0.0) {
                            Text(
                                "Plus ${money(line.lineMarkup, currency)}",
                                fontSize = 12.sp, color = t.inkTertiary
                            )
                        }
                    }
                    Text(
                        money(line.lineTotal, currency),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.inkPrimary
                    )
                }
            }
        }

        HorizontalDivider(color = t.surfaceBorder)

        // ── totals breakdown ──
        ReceiptMoneyRow("Subtotal", money(sale.subtotal, currency))
        if (sale.discountTotal > 0.0) {
            ReceiptMoneyRow("Discount", "-" + money(sale.discountTotal, currency), accent = t.danger)
        }
        if (sale.markupTotal > 0.0) {
            ReceiptMoneyRow("Markup", "+" + money(sale.markupTotal, currency))
        }
        if (sale.taxTotal > 0.0) {
            ReceiptMoneyRow("VAT", money(sale.taxTotal, currency))
        }
        ReceiptMoneyRow("Total", money(sale.total, currency), strong = true)

        HorizontalDivider(color = t.surfaceBorder)

        // ── tenders ──
        if (payments.isEmpty()) {
            ReceiptMoneyRow(refundMethodLabel(sale.paymentMethod), money(sale.amountPaid, currency))
        } else {
            payments.forEach { p ->
                val label = refundMethodLabel(p.method) +
                    (p.reference?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                ReceiptMoneyRow(label, money(p.amount, currency))
            }
        }
        ReceiptMoneyRow("Paid", money(sale.amountPaid, currency))
        sale.changeDue?.takeIf { it > 0.0 }?.let {
            ReceiptMoneyRow("Change given", money(it, currency))
        }
        sale.changeOwed?.takeIf { it > 0.0 }?.let {
            ReceiptMoneyRow("Change still owed", money(it, currency), accent = t.warning)
        }
        if (owing > 0.005) {
            ReceiptMoneyRow("Balance owing", money(owing, currency), accent = t.danger)
        }
        if (refunded > 0.0) {
            ReceiptMoneyRow("Refunded", money(refunded, currency), accent = t.danger)
        }

        // ── edit affordance / lock note ──
        HorizontalDivider(color = t.surfaceBorder)
        when {
            editable -> {
                Button(
                    onClick = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.brand.s600, contentColor = t.inkOnBrand
                    )
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit receipt")
                }
                Text(
                    "Editable for another ${minutesLeft}m. Changes are recorded against this same receipt.",
                    fontSize = 12.sp, color = t.inkTertiary
                )
            }
            !canEditReceipts && sale.isEditable(windowMins) && refunded <= 0.005 -> Text(
                "You don't have permission to edit receipts — ask an admin.",
                fontSize = 12.sp, color = t.inkTertiary
            )
            refunded > 0.005 -> Text(
                "This receipt has a refund against it, so it can no longer be edited.",
                fontSize = 12.sp, color = t.inkTertiary
            )
            windowMins <= 0 -> Text(
                "Receipt editing is switched off in Settings.",
                fontSize = 12.sp, color = t.inkTertiary
            )
            else -> Text(
                "The ${windowMins}-minute edit window has passed — this receipt is locked. " +
                    "Corrections now need a void or refund.",
                fontSize = 12.sp, color = t.inkTertiary
            )
        }

        // ── append-only edit history ──
        val edits = history.filter { it.action == "sale_edit" || it.action == "sale_edit_line" }
        if (edits.isNotEmpty()) {
            HorizontalDivider(color = t.surfaceBorder)
            PosSectionLabel("Edit history")
            edits.forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(e.summary, fontSize = 13.sp, color = t.inkPrimary)
                    Text(
                        agoText(e.createdAt) +
                            (e.createdByName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        fontSize = 11.sp, color = t.inkTertiary
                    )
                }
            }
        }
    }
}

/**
 * Edit a receipt in place (B5 item 7): add items, drop items, change quantities. It is
 * the SAME sale — same id, same receipt number — so saving corrects the original rather
 * than issuing a second document.
 *
 * The running totals shown here come from [computeSaleTotals], the very function
 * checkout uses, so the preview and the saved receipt cannot drift apart. The repository
 * re-runs it on save and settles the difference on the customer's ledger.
 */
@Composable
private fun EditReceiptDialog(
    vm: PosViewModel,
    business: Business,
    sale: SaleEntity,
    onDismiss: () -> Unit,
) {
    val t = LocalPosTokens.current
    val context = LocalContext.current
    val currency = business.currency
    val catalogue by vm.items.collectAsState()
    val prefs by vm.shopPrefs.collectAsState()
    var loaded by remember(sale.id) { mutableStateOf(false) }
    val lines = remember(sale.id) { mutableStateListOf<CartLine>() }
    var search by remember(sale.id) { mutableStateOf("") }
    var adding by remember(sale.id) { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    // Seed the editable basket from the frozen receipt. A box line's name was snapshotted
    // with its pack size appended at checkout — strip it so re-saving can't stack a second
    // "(Box of N)" onto the name.
    LaunchedEffect(sale.id) {
        val src = vm.loadLines(sale.id)
        val byId = catalogue.associateBy { it.id }
        lines.clear()
        src.forEach { l ->
            val item = l.itemId?.let { byId[it] }
            val bareName = if (l.mode == "box") l.name.replace(Regex("\\s*\\(Box of \\d+\\)$"), "") else l.name
            lines += CartLine(
                itemId = l.itemId ?: "",
                name = bareName,
                unitPrice = l.unitPrice,
                taxRate = 0.0,
                qty = l.qty,
                mode = l.mode,
                unitsPerLine = l.unitsPerLine,
                measured = item?.isMeasured == true,
                unitLabel = item?.unit.orEmpty(),
                lineDiscount = l.lineDiscount,
                lineMarkup = l.lineMarkup
            )
        }
        loaded = true
    }

    // Same math as checkout, run live for the preview.
    val subtotal = lines.sumOf { it.lineSubtotal }
    val perItemDiscount = lines.sumOf { it.lineDiscountApplied }
    val perItemMarkup = lines.sumOf { it.lineMarkupApplied }
    val totals = computeSaleTotals(
        subtotal, perItemDiscount, business.vatEnabled, business.vatPercent, perItemMarkup
    )
    val newTotal = if (prefs.checkoutRounding > 0.0)
        Math.round(totals.total / prefs.checkoutRounding) * prefs.checkoutRounding
    else totals.total
    val delta = newTotal - sale.total

    PosContainedForm(
        title = "Edit #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}",
        onDismiss = { if (!submitting) onDismiss() },
        confirmLabel = if (submitting) "Saving" else "Save receipt",
        confirmEnabled = loaded && lines.isNotEmpty() && !submitting,
        onConfirm = {
            submitting = true
            vm.editSale(sale, lines.toList()) { ok ->
                submitting = false
                if (ok) {
                    Toast.makeText(context, "Receipt updated", Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    Toast.makeText(
                        context,
                        "Can't edit this receipt — the window has closed or it has a refund",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = t.brand.s600)
            }
            return@PosContainedForm
        }

        Text(
            "Same receipt, corrected in place. Stock and the customer's balance follow the change, " +
                "and every edit is written to the audit log.",
            fontSize = 12.sp, color = t.inkTertiary
        )

        // ── current basket ──
        lines.forEachIndexed { idx, line ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            line.name + if (line.mode == "box") " (Box of ${line.unitsPerLine})" else "",
                            fontSize = 14.sp, color = t.inkPrimary
                        )
                        Text(
                            money(line.unitPrice, currency) +
                                (if (line.measured && line.unitLabel.isNotBlank()) " / ${line.unitLabel}" else " each"),
                            fontSize = 12.sp, color = t.inkTertiary
                        )
                    }
                    Text(
                        money(line.lineSubtotal, currency),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = t.inkPrimary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (line.measured) {
                        // Measured goods (B1) are sold by a decimal quantity — type it.
                        OutlinedTextField(
                            value = fmtQty(line.qty),
                            onValueChange = { raw ->
                                val q = raw.toDoubleOrNull()
                                if (q != null && q >= 0.0) lines[idx] = line.copy(qty = q)
                            },
                            label = { Text(line.unitLabel.ifBlank { "Qty" }) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(130.dp)
                        )
                    } else {
                        IconButton(
                            enabled = line.qty > 1.0,
                            onClick = { lines[idx] = line.copy(qty = (line.qty - 1).coerceAtLeast(1.0)) }
                        ) { Icon(Icons.Filled.Remove, contentDescription = "Less", tint = t.inkSecondary) }
                        Text(
                            fmtQty(line.qty),
                            Modifier.widthIn(min = 32.dp),
                            textAlign = TextAlign.Center, color = t.inkPrimary
                        )
                        IconButton(
                            onClick = { lines[idx] = line.copy(qty = line.qty + 1) }
                        ) { Icon(Icons.Filled.Add, contentDescription = "More", tint = t.inkSecondary) }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { lines.removeAt(idx) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove item", tint = t.danger)
                    }
                }
            }
            HorizontalDivider(color = t.surfaceBorder)
        }
        if (lines.isEmpty()) {
            Text(
                "A receipt can't be emptied — add an item, or cancel and refund the sale instead.",
                fontSize = 12.sp, color = t.danger
            )
        }

        // ── add an item ──
        if (!adding) {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add item")
            }
        } else {
            PosField(
                value = search,
                onValueChange = { search = it },
                label = "Search products"
            )
            val matches = catalogue
                .filter { !it.sellableBlocked }
                .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
                .take(8)
            matches.forEach { item ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            // Merge into an existing retail line for the same product, so a
                            // double-add bumps quantity instead of splitting the receipt.
                            val at = lines.indexOfFirst { it.itemId == item.id && it.mode == "retail" }
                            if (at >= 0) {
                                lines[at] = lines[at].copy(qty = lines[at].qty + 1)
                            } else {
                                lines += CartLine(
                                    itemId = item.id,
                                    name = item.name,
                                    unitPrice = item.price,
                                    taxRate = 0.0,
                                    qty = 1.0,
                                    mode = "retail",
                                    unitsPerLine = 1,
                                    measured = item.isMeasured,
                                    unitLabel = item.unit.orEmpty()
                                )
                            }
                            search = ""
                            adding = false
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.name, Modifier.weight(1f), fontSize = 14.sp, color = t.inkPrimary)
                    Text(money(item.price, currency), fontSize = 13.sp, color = t.inkSecondary)
                }
            }
            if (matches.isEmpty()) {
                Text("No matching products", fontSize = 12.sp, color = t.inkTertiary)
            }
            TextButton(onClick = { adding = false; search = "" }) { Text("Done adding") }
        }

        // ── live totals ──
        HorizontalDivider(color = t.surfaceBorder)
        ReceiptMoneyRow("Subtotal", money(subtotal, currency))
        if (totals.discount > 0.0) {
            ReceiptMoneyRow("Discount", "-" + money(totals.discount, currency), accent = t.danger)
        }
        if (perItemMarkup > 0.0) ReceiptMoneyRow("Markup", "+" + money(perItemMarkup, currency))
        if (totals.taxTotal > 0.0) ReceiptMoneyRow("VAT", money(totals.taxTotal, currency))
        ReceiptMoneyRow("Was", money(sale.total, currency))
        ReceiptMoneyRow("New total", money(newTotal, currency), strong = true)

        // Never let a money difference disappear silently — say exactly where it lands.
        if (kotlin.math.abs(delta) > 0.005) {
            val toCustomer = sale.customerName?.takeIf { it.isNotBlank() }
            val msg = when {
                delta > 0 && toCustomer != null ->
                    "$toCustomer will owe a further ${money(delta, currency)} on account."
                delta > 0 ->
                    "This walk-in receipt goes up by ${money(delta, currency)} — collect it, or the till will read short."
                toCustomer != null ->
                    "${money(-delta, currency)} goes back to $toCustomer as change owed."
                else ->
                    "This walk-in receipt drops by ${money(-delta, currency)} — hand it back, or the till will read over."
            }
            Text(
                msg,
                fontSize = 12.sp,
                color = if (delta > 0) t.warning else t.brand.s600
            )
        }
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
    // Must mirror PosRepository.createRefund exactly, or the dialog quotes one figure and
    // the till hands over another: each line net of its OWN discount/markup, measured
    // against the sale's goods value on the same basis (NOT the gross sale.subtotal).
    val returnedSubtotal = ls?.sumOf { returnedLineValue(it, returnQty[it.id] ?: 0.0) } ?: 0.0
    val refundTotal =
        computeRefundTotal(returnedSubtotal, saleGoodsValue(ls.orEmpty()), sale.total).refundTotal
    val payoutNow = (payoutText.toDoubleOrNull() ?: refundTotal).coerceIn(0.0, refundTotal)
    val outstanding = (refundTotal - payoutNow).coerceAtLeast(0.0)
    val canOwe = sale.customerId != null
    val payoutOk = canOwe || outstanding <= 0.005
    val confirmEnabled = refundTotal > 0.0 && payoutOk && !submitting

    val t = LocalPosTokens.current
    PosContainedForm(
        title = "Refund #${sale.receiptNo ?: sale.id.takeLast(6).uppercase()}",
        onDismiss = { if (!submitting) onDismiss() },
        confirmLabel = "Refund " + money(refundTotal, currency),
        confirmEnabled = confirmEnabled,
        onConfirm = {
            val src = ls ?: return@PosContainedForm
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
    ) {
        if (ls == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = t.brand.s600)
            }
        } else {
            Text(
                "Choose what's coming back. The refund is proportional to what was paid.",
                fontSize = 12.sp, color = t.inkTertiary
            )
            ls.forEach { line ->
                val already = alreadyReturned[line.id] ?: 0.0
                val maxReturn = (line.qty - already).coerceAtLeast(0.0)
                val qty = returnQty[line.id] ?: 0.0
                Column(Modifier.fillMaxWidth()) {
                    Text(line.name, fontWeight = FontWeight.Medium, color = t.inkPrimary)
                    Text(
                        "Sold ${fmtQty(line.qty)} @ ${money(line.unitPrice, currency)}" +
                            if (already > 0) " · ${fmtQty(already)} already returned" else "",
                        fontSize = 12.sp, color = t.inkTertiary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = qty > 0.0,
                            onClick = { returnQty[line.id] = (qty - 1).coerceAtLeast(0.0) }
                        ) { Icon(Icons.Filled.Remove, contentDescription = "Less", tint = t.inkSecondary) }
                        Text(
                            fmtQty(qty),
                            Modifier.widthIn(min = 28.dp),
                            textAlign = TextAlign.Center, color = t.inkPrimary
                        )
                        IconButton(
                            enabled = qty < maxReturn,
                            onClick = { returnQty[line.id] = (qty + 1).coerceAtMost(maxReturn) }
                        ) { Icon(Icons.Filled.Add, contentDescription = "More", tint = t.inkSecondary) }
                        Spacer(Modifier.weight(1f))
                        Text("Restock", fontSize = 12.sp, color = t.inkSecondary)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = restock[line.id] ?: true,
                            onCheckedChange = { restock[line.id] = it }
                        )
                    }
                }
                HorizontalDivider(color = t.surfaceBorder)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Refund total", fontWeight = FontWeight.Medium, color = t.inkPrimary)
                Text(money(refundTotal, currency), fontWeight = FontWeight.Bold, color = t.inkPrimary)
            }
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
            PosField(
                value = payoutText,
                onValueChange = { payoutText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = "Paying back now",
                placeholder = money(refundTotal, currency),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            if (outstanding > 0.005) {
                Text(
                    if (canOwe)
                        "Owed to ${sale.customerName ?: "customer"}: ${money(outstanding, currency)} — tracked in Change & Credit"
                    else
                        "Walk-in refund must be paid in full (${money(refundTotal, currency)}). Leave the amount blank to pay it all now.",
                    fontSize = 12.sp,
                    color = if (canOwe) t.inkTertiary else t.danger
                )
            }
            PosField(
                value = reason,
                onValueChange = { reason = it },
                label = "Reason (optional)",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Refund history (immutable ledger). Owed refunds get a "Record payout" action. */
@Composable
private fun RefundsScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val t = LocalPosTokens.current
    val currency = business.currency
    val refunds by vm.refunds.collectAsState()
    // Recording a payout hands cash back across the counter, so it needs the same grant
    // as raising the refund did. It was ungated in BOTH places before this.
    val caps by vm.allowedCaps.collectAsState()
    val canPayOut = com.portionspot.pos.auth.Capability.PROCESS_REFUNDS in caps
    var payoutFor by remember { mutableStateOf<Refund?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text("Refunds", color = t.inkPrimary, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
        if (refunds.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No refunds yet", color = t.inkTertiary)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                // bottom clears the floating cart pill so it never covers the last refund.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(refunds, key = { it.refund.id }) { rw ->
                    val r = rw.refund
                    val owed = r.status == "owed"
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(t.surface1)
                            .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Refund on #${r.saleReceiptNo ?: r.saleId.takeLast(6).uppercase()}",
                                    fontWeight = FontWeight.Bold, color = t.inkPrimary
                                )
                                Text(
                                    (r.customerName ?: "Walk-in") + (r.createdByName?.let { " · by $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                                )
                                Text(agoText(r.createdAt), style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(r.refundTotal, currency), fontWeight = FontWeight.Bold, color = t.inkPrimary)
                                Text(
                                    if (owed) "Balance owed" else "Settled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (owed) t.danger else t.success
                                )
                            }
                            IconButton(onClick = { printer.printRefund(business, r, rw.lines) { vm.refundPayments(r.id) } }) {
                                Icon(Icons.Filled.Print, contentDescription = "Print refund", tint = t.inkSecondary)
                            }
                            IconButton(onClick = { printer.sharePdfRefund(business, r, rw.lines) { vm.refundPayments(r.id) } }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share refund PDF", tint = t.inkSecondary)
                            }
                        }
                        val retLines = rw.lines
                        if (retLines.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                retLines.joinToString(", ") { "${fmtQty(it.qty)}× ${it.name}" },
                                style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
                            )
                        }
                        if (owed && canPayOut) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { payoutFor = r }) { Text("Record payout") }
                        }
                    }
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
    val t = LocalPosTokens.current
    val currency = business.currency
    val context = LocalContext.current
    var amountText by remember(refund.id) { mutableStateOf("") }
    var method by remember(refund.id) { mutableStateOf("cash") }
    var methodOpen by remember { mutableStateOf(false) }
    val amount = amountText.toDoubleOrNull() ?: 0.0

    PosContainedForm(
        title = "Record refund payout",
        onDismiss = onDismiss,
        confirmLabel = "Record",
        confirmEnabled = amount > 0.0,
        onConfirm = {
            vm.recordRefundPayout(refund.id, Tender(method = method, amount = amount)) {
                Toast.makeText(context, "Payout recorded", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    ) {
        Text(
            "Refund on #${refund.saleReceiptNo ?: refund.saleId.takeLast(6).uppercase()} — total ${money(refund.refundTotal, currency)}",
            fontSize = 12.sp, color = t.inkTertiary
        )
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
        PosField(
            value = amountText,
            onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = "Amount handed back",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ───────────────────────── SETTINGS ─────────────────────────

/** Setting groups shown one at a time in [SettingsScreen] (mobile-first: no endless scroll). */
private enum class SettingsCat(val label: String, val editsBusiness: Boolean) {
    Business("Business", true),
    Appearance("Appearance", false),
    Payments("Payments", true),
    TaxPricing("Tax & pricing", true),
    Discounts("Discounts", false),
    Receipt("Receipt & printer", true),
    Data("Data & sync", false),
}

@Composable
private fun SettingsScreen(vm: PosViewModel, business: Business, printer: PrinterUi) {
    val t = LocalPosTokens.current
    val theme by vm.themeChoice.collectAsState()
    val prefs by vm.shopPrefs.collectAsState()
    var showResetStock by remember { mutableStateOf(false) }
    var showWipeSales by remember { mutableStateOf(false) }
    // Settings are grouped into categories so it isn't one endless scroll; the picker
    // below swaps which group is shown. All the editable state lives in this one
    // composable, so switching categories never loses an unsaved edit.
    //
    // ★ MOST OF THIS SCREEN IS THE OWNER'S, NOT THE SHIFT'S. Settings has no view
    // capability (see Screen.viewCap) so a cashier can open it, and from here could raise
    // their OWN per-item discount ceiling, switch VAT off, zero every shelf, wipe the sales
    // history, or repoint the Supabase connection at a database of their choosing. Only the
    // two genuinely per-device groups stay open to everyone: the theme and the receipt
    // printer, which belong to the phone in your hand rather than to the business.
    val isAdmin by vm.isAdmin.collectAsState()
    val visibleCats = remember(isAdmin) {
        if (isAdmin) SettingsCat.entries.toList()
        else listOf(SettingsCat.Appearance, SettingsCat.Receipt)
    }
    var settingsCat by remember { mutableStateOf(SettingsCat.Business) }
    // Also covers a demotion landing while the screen is open: the group goes away rather
    // than staying put because it happened to be selected first.
    LaunchedEffect(visibleCats) {
        if (settingsCat !in visibleCats) settingsCat = visibleCats.first()
    }

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

    LazyColumn(
        Modifier.fillMaxSize(),
        // bottom clears the floating cart pill so it never covers the last setting.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)
    ) {
        item {
            SettingsCategoryBar(
                selected = settingsCat,
                categories = visibleCats,
                onSelect = { settingsCat = it }
            )
            Spacer(Modifier.height(16.dp))

          if (settingsCat == SettingsCat.Business) {
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
          }

          if (settingsCat == SettingsCat.Appearance) {
            // ---- Appearance (theme; saves live, device-local) ----
            SettingsSectionHeader("Appearance")
            AppearanceSection(theme = theme, onChange = { vm.saveTheme(it) })
          }

          if (settingsCat == SettingsCat.TaxPricing) {
            // ---- VAT / ZIMRA ----
            SettingsSectionHeader("VAT / ZIMRA")
            SettingsSwitch("Charge VAT", vatEnabled) { vatEnabled = it }
            if (vatEnabled) {
                SettingsField("VAT registration number", vatNumber) { vatNumber = it }
                SettingsField("VAT percentage (e.g. 15)", vatPercentText) {
                    vatPercentText = it.filter { ch -> ch.isDigit() || ch == '.' }
                }
            }
          }

          if (settingsCat == SettingsCat.Payments) {
            // ---- Payment methods ----
            SettingsSectionHeader("Payment methods")
            Text(
                "Choose which methods cashiers can use at checkout. Money goes directly " +
                    "to your own accounts — never through ON-SPOT POS.",
                fontSize = 12.sp,
                color = t.inkTertiary
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
                    fontSize = 12.sp,
                    color = t.inkTertiary
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
          }

          if (settingsCat == SettingsCat.TaxPricing) {
            // ---- Tax & price rounding + Margins (saves live, device-local) ----
            SettingsSectionHeader("Tax & margins")
            TaxMarginsSection(prefs = prefs, onChange = { vm.savePrefs(it) })
          }

          if (settingsCat == SettingsCat.Discounts) {
            // ---- Quotes (saves live, device-local) ----
            SettingsSectionHeader("Quotes")
            SettingsField("Quote validity (days)", prefs.defaultQuoteValidityDays.toString()) {
                it.toIntOrNull()?.coerceIn(0, 365)?.let { d ->
                    vm.savePrefs(prefs.copy(defaultQuoteValidityDays = d))
                }
            }

            // ---- Discounts (saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Discounts")
            Text(
                "Only staff with the 'Give discounts' permission can discount a sale. Discounts " +
                    "aren't approved one-by-one — they're recorded on the sale and the admin " +
                    "reviews them in Admin console → Discounts given.",
                fontSize = 12.sp,
                color = t.inkTertiary
            )
            Spacer(Modifier.height(12.dp))

            // Per-item discount ceiling: a hard cap the till enforces at the cart, so a
            // cashier can knock money off a single line but never past this amount.
            Spacer(Modifier.height(12.dp))
            Text(
                "The most a cashier may take off a single cart line. This is a hard limit — " +
                    "they can't go past it, no PIN overrides it. Set 0 for no limit.",
                fontSize = 12.sp,
                color = t.inkTertiary
            )
            SettingsField(
                "Max discount per item (${currency.ifBlank { "USD" }})",
                trimPct(prefs.maxItemDiscount)
            ) {
                it.toDoubleOrNull()?.coerceAtLeast(0.0)?.let { m ->
                    vm.savePrefs(prefs.copy(maxItemDiscount = m))
                }
            }

            // ---- Receipt editing window (B5) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Receipt editing")
            Text(
                "How long after a sale you can still correct the receipt itself — add or " +
                    "remove items and change quantities on the SAME receipt. Past the window " +
                    "the receipt locks and a correction has to go through a refund. Every edit " +
                    "is written to the audit log either way.",
                fontSize = 12.sp,
                color = t.inkTertiary
            )
            Spacer(Modifier.height(8.dp))
            PosSegmented(
                SALE_EDIT_WINDOWS.map { (mins, label) -> mins.toString() to label },
                prefs.saleEditWindowMinutes.toString()
            ) { picked ->
                picked.toIntOrNull()?.let { m ->
                    vm.savePrefs(prefs.copy(saleEditWindowMinutes = m))
                }
            }
          }

          if (settingsCat == SettingsCat.TaxPricing) {
            // ---- Second currency (dual-currency tender, saves live, device-local) ----
            Spacer(Modifier.height(20.dp))
            SettingsSectionHeader("Second currency")
            Text(
                "Keep your books in ${currency.ifBlank { "USD" }} but also accept a second " +
                    "currency at the till (handy for USD + ZiG). Leave the code blank or the " +
                    "rate at 0 to switch it off.",
                fontSize = 12.sp,
                color = t.inkTertiary
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
            Spacer(Modifier.height(6.dp))
            PosField(
                value = rateText,
                onValueChange = {
                    rateText = it.filter { ch -> ch.isDigit() || ch == '.' }
                    vm.savePrefs(prefs.copy(secondCurrencyRate = rateText.toDoubleOrNull() ?: 0.0))
                },
                label = "Rate — ${prefs.secondCurrencyCode.ifBlank { "second currency" }} per 1 ${currency.ifBlank { "USD" }}",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth()
            )
            if (secondCurrencyActive(prefs.secondCurrencyCode, prefs.secondCurrencyRate)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Example: ${money(1.0, currency.ifBlank { "USD" })} = " +
                        money(baseToSecond(1.0, prefs.secondCurrencyRate), prefs.secondCurrencyCode),
                    fontSize = 12.sp,
                    color = t.brand.s600
                )
            }
          }

          if (settingsCat == SettingsCat.Receipt) {
            // ---- Printer ----
            Text("Receipt printer", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
            Spacer(Modifier.height(6.dp))
            Text("Printer type", fontSize = 14.sp, color = t.inkSecondary)
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
                        fontSize = 14.sp,
                        color = t.inkTertiary
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
                        fontSize = 12.sp,
                        color = t.inkTertiary
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
                        fontSize = 12.sp,
                        color = t.inkTertiary
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
            Text("Paper width", fontSize = 14.sp, color = t.inkSecondary)
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
            Text("Receipt style preset", fontSize = 14.sp, color = t.inkSecondary)
            Text(
                "A quick look layered over the toggles below.",
                fontSize = 12.sp,
                color = t.inkTertiary
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
          }

          // Save writes the business record (name, VAT, payment methods, printer). The
          // prefs-only groups (Appearance, Discounts) save live, so no button there.
          if (settingsCat.editsBusiness) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.saveBusiness(edited()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) { Text("Save settings") }
          }

          if (settingsCat == SettingsCat.Data) {
            val dataAppMode by vm.appMode.collectAsState()

            // In local (phone-only) mode: manage the optional device PIN here, and
            // offer the opt-in to cloud. The login / per-cashier PIN / staff
            // management all live behind that opt-in (cloud mode).
            if (dataAppMode == AppMode.Local) {
                SettingsSectionHeader("Security (this device)")
                DevicePinSettings(vm)
                Spacer(Modifier.height(16.dp))
                SettingsSectionHeader("Cloud & staff accounts")
                ConnectCloudCard(vm)
                Spacer(Modifier.height(16.dp))
            }

            // ---- Danger zone ----
            SettingsSectionHeader("Danger zone")
            Text(
                "These actions cannot be undone.",
                fontSize = 12.sp,
                color = t.inkTertiary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showResetStock = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
            ) { Text("Reset all stock to zero") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showWipeSales = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
            ) { Text("Wipe all sales history") }

            // The bring-your-own-database sync panel is a cloud-mode feature.
            if (dataAppMode == AppMode.Cloud) {
                CloudSyncSection(vm)
            }
          }
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

// ─────────────────── LOCAL SECURITY / CLOUD OPT-IN ───────────────────

/** Set / change / remove the optional local device PIN (no-cloud mode). */
@Composable
private fun DevicePinSettings(vm: PosViewModel) {
    val t = LocalPosTokens.current
    val hasPin by vm.hasLocalPin.collectAsState()
    var showSet by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    Text(
        if (hasPin) "A PIN is required each time the app opens."
        else "The till opens without a lock. Add a PIN to require it every time the app opens.",
        fontSize = 12.sp,
        color = t.inkTertiary
    )
    Spacer(Modifier.height(8.dp))
    if (!hasPin) {
        Button(
            onClick = { showSet = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
        ) {
            Text("Set device PIN")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSet = true }, modifier = Modifier.weight(1f)) {
                Text("Change PIN")
            }
            OutlinedButton(
                onClick = { showRemove = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = t.danger)
            ) { Text("Remove PIN") }
        }
    }

    if (showSet) {
        LocalPinDialog(
            title = if (hasPin) "Change device PIN" else "Set device PIN",
            onDismiss = { showSet = false },
            onSave = { pin -> vm.setLocalPin(pin); showSet = false }
        )
    }
    if (showRemove) {
        ConfirmDialog(
            title = "Remove device PIN?",
            message = "The till will open without a lock. Anyone with the phone can use it.",
            confirmLabel = "Remove PIN",
            onConfirm = { vm.clearLocalPin(); showRemove = false },
            onDismiss = { showRemove = false }
        )
    }
}

/** Enter + confirm a 4-6 digit PIN in a dialog. Used for set/change in Settings. */
@Composable
private fun LocalPinDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    PosContainedForm(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = "Save",
        onConfirm = {
            when {
                pin.length < 4 -> error = "PIN must be at least 4 digits"
                pin != confirm -> error = "PINs don't match"
                else -> onSave(pin)
            }
        }
    ) {
        val t = LocalPosTokens.current
        PosFormCard {
            PosField(
                value = pin,
                onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) { pin = new; error = null } },
                label = "PIN (4-6 digits)",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            PosField(
                value = confirm,
                onValueChange = { new -> if (new.length <= 6 && new.all { it.isDigit() }) { confirm = new; error = null } },
                label = "Confirm PIN",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (error != null) {
            Text(error!!, color = t.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The opt-in that turns on cloud (team) mode: login, staff accounts, attribution, sync. */
@Composable
private fun ConnectCloudCard(vm: PosViewModel) {
    val t = LocalPosTokens.current
    Text(
        "Cloud mode adds staff logins, per-cashier PINs, sales attribution and backup/sync " +
            "across devices. Everything already on this device stays put.",
        fontSize = 12.sp,
        color = t.inkTertiary
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { vm.connectCloud() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
    ) {
        Text("Connect cloud")
    }
}

// ───────────────────────── CLOUD SYNC ─────────────────────────

/**
 * Bring-your-own-database panel. The app is fully usable with this left blank;
 * connecting the user's *own* Supabase just mirrors local data to the cloud.
 */
@Composable
private fun CloudSyncSection(vm: PosViewModel) {
    val t = LocalPosTokens.current
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
    // Set when a Test reveals the project is reachable but empty → surfaces the guided
    // "Set up your database" flow (the app can't create tables itself — see the sheet).
    var tablesMissing by remember(connection) { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = t.surfaceBorder)
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (connected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
            contentDescription = null,
            tint = if (connected) t.brand.s600 else t.inkTertiary
        )
        Spacer(Modifier.width(8.dp))
        Text("Cloud sync", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Optional. The app works fully offline. Connect your own Supabase database " +
            "to back up sales and sync across devices — the data stays in your account, not ours.",
        fontSize = 12.sp,
        color = t.inkTertiary
    )
    Spacer(Modifier.height(12.dp))

    if (!connected) {
        PosField(
            value = url,
            onValueChange = { url = it.trim(); message = null },
            label = "Supabase URL (https://xxxx.supabase.co)",
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        PosField(
            value = key,
            onValueChange = { key = it.trim(); message = null },
            label = "Anon (public) API key",
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
                        tablesMissing = result is ConnectionTest.TablesMissing
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
                },
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = t.inkOnBrand
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Connect & sync")
            }
        }
        if (tablesMissing) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showSetup = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) {
                Icon(Icons.Filled.Storage, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Set up your database")
            }
        }
        if (showSetup) {
            DatabaseSetupSheet(
                url = url,
                recheck = { cb -> vm.testConnection(url, key, cb) },
                onReady = {
                    tablesMissing = false
                    isError = false
                    message = "Tables found. Tap \"Connect & sync\"."
                },
                onDismiss = { showSetup = false }
            )
        }
    } else {
        Text(
            connection!!.url,
            fontWeight = FontWeight.Medium,
            color = t.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        val statusText = when (val s = status) {
            SyncStatus.Idle ->
                lastSyncAt?.let { "Last synced ${syncTimeLabel(it)}" } ?: "Connected — not synced yet"
            SyncStatus.Syncing -> "Syncing…"
            is SyncStatus.Done ->
                "Synced ${s.pushed} up · ${s.pulled} down · ${syncTimeLabel(s.at)}" +
                    if (s.warnings.isEmpty()) "" else "\nUpload issues — ${s.warnings.joinToString("; ")}"
            is SyncStatus.Error -> "Sync error: ${s.message}"
        }
        val hasWarnings = (status as? SyncStatus.Done)?.warnings?.isNotEmpty() == true
        Text(
            statusText,
            fontSize = 12.sp,
            color = if (status is SyncStatus.Error || hasWarnings) t.danger else t.inkTertiary
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = status !is SyncStatus.Syncing,
                onClick = { vm.syncNow() },
                colors = ButtonDefaults.buttonColors(containerColor = t.brand.s600, contentColor = t.inkOnBrand)
            ) {
                if (status is SyncStatus.Syncing) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = t.inkOnBrand
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
                Text("Upload this device's data", fontWeight = FontWeight.Medium, color = t.inkPrimary)
                Text(
                    "Off = pull only (safe). On also PUSHES this device's sales, refunds " +
                        "and edited products up to the shared database — clear any test data first.",
                    fontSize = 12.sp,
                    color = t.inkTertiary
                )
            }
            Switch(checked = pushOn, onCheckedChange = { vm.setCloudPushEnabled(it) })
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SyncMessage(text: String, isError: Boolean) {
    val t = LocalPosTokens.current
    Text(
        text,
        fontSize = 12.sp,
        color = if (isError) t.danger else t.brand.s600
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * Guided one-time database setup, shown when a Test finds the project reachable but
 * EMPTY. The app cannot create tables itself (PostgREST runs no DDL from the anon key),
 * so this hands the user the exact SQL to run once in their Supabase SQL editor, then
 * re-checks. Only ever opened for an empty DB, so it never touches a configured one.
 */
@Composable
private fun DatabaseSetupSheet(
    url: String,
    recheck: ((ConnectionTest) -> Unit) -> Unit,
    onReady: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var checking by remember { mutableStateOf(false) }
    var checkMsg by remember { mutableStateOf<String?>(null) }

    PosContainedForm(
        title = "Set up your database",
        onDismiss = onDismiss,
        confirmLabel = if (checking) "Checking…" else "I've run it — re-check",
        confirmEnabled = !checking,
        dismissLabel = "Close",
        onConfirm = {
            checking = true; checkMsg = null
            recheck { res ->
                checking = false
                if (res is ConnectionTest.Ok) { onReady(); onDismiss() }
                else checkMsg = res.label()
            }
        }
    ) {
        val t = LocalPosTokens.current
        Text(
            "Your database is empty. It needs a one-time setup — about 30 seconds. " +
                "Copy the setup script, run it once in your Supabase SQL editor, then re-check. " +
                "Running it again later is harmless — it never deletes your data.",
            style = MaterialTheme.typography.bodyMedium, color = t.inkSecondary
        )
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(SUPABASE_SETUP_SQL))
                Toast.makeText(context, "Setup SQL copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Copy setup SQL")
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sqlEditorUrl(url))))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open Supabase SQL editor")
        }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "ON-SPOT POS — Supabase setup SQL")
                    putExtra(Intent.EXTRA_TEXT, SUPABASE_SETUP_SQL)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "Share setup SQL")) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share SQL (send to a PC)")
        }
        Text("Steps", fontWeight = FontWeight.SemiBold, color = t.inkPrimary)
        Text(
            "1. Open your Supabase SQL editor (button above).\n" +
                "2. Paste the copied SQL into a new query.\n" +
                "3. Press Run.\n" +
                "4. Come back and tap \"I've run it — re-check\".",
            style = MaterialTheme.typography.bodySmall, color = t.inkTertiary
        )
        checkMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = t.danger)
        }
    }
}

/** Deep-link to the SQL editor of the project in [url] (`https://<ref>.supabase.co`),
 *  falling back to the dashboard root when the ref can't be parsed. */
private fun sqlEditorUrl(url: String): String {
    val host = runCatching { Uri.parse(url.trim()).host }.getOrNull()
    val ref = host?.takeIf { it.contains(".supabase.") }
        ?.substringBefore('.')?.takeIf { it.isNotBlank() }
    return if (ref != null) "https://supabase.com/dashboard/project/$ref/sql/new"
    else "https://supabase.com/dashboard"
}

private fun ConnectionTest.label(): String = when (this) {
    ConnectionTest.Ok -> "Connection works — tables found. You're good to connect."
    ConnectionTest.TablesMissing ->
        "Reached the database, but it's empty — the tables aren't set up yet. " +
            "Tap \"Set up your database\" below to finish in about 30 seconds."
    ConnectionTest.Unauthorized ->
        "Key rejected. Make sure you pasted the anon (public) key and the URL is correct."
    is ConnectionTest.Failed -> "Couldn't connect: $message"
}

private fun SyncOutcome.label(): String = when (this) {
    is SyncOutcome.Success ->
        "Connected. Synced $pushed up · $pulled down." +
            if (pushErrors.isEmpty()) "" else " Upload issues — ${pushErrors.joinToString("; ")}"
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
    PosDialog(title = "Paired printers", onDismiss = onDismiss) {
        val t = LocalPosTokens.current
        if (devices.isEmpty()) {
            Text(
                "No paired Bluetooth devices found. Pair your thermal printer in Android Settings → Bluetooth first, then come back.",
                color = t.inkSecondary
            )
        } else {
            devices.forEach { dev ->
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(t.surface1)
                        .border(1.dp, t.surfaceBorder, RoundedCornerShape(12.dp))
                        .clickable { onSelect(dev) }
                        .padding(14.dp)
                ) {
                    Text(dev.name, fontWeight = FontWeight.Bold, color = t.inkPrimary)
                    Text(dev.mac, style = MaterialTheme.typography.bodySmall, color = t.inkTertiary)
                }
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onChange: (String) -> Unit) {
    // Dense design-system field (propagates to every Settings input).
    PosField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    PosSectionLabel(title)
    Spacer(Modifier.height(6.dp))
}

/** Horizontal, scrollable category picker at the top of Settings (mobile-first). */
@Composable
private fun SettingsCategoryBar(
    selected: SettingsCat,
    /** Only the groups this session may open — cashiers get the per-device ones. */
    categories: List<SettingsCat> = SettingsCat.entries.toList(),
    onSelect: (SettingsCat) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { cat ->
            FilterChip(
                selected = selected == cat,
                onClick = { onSelect(cat) },
                label = { Text(cat.label) }
            )
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalPosTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), color = t.inkPrimary)
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
