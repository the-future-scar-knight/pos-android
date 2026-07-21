package com.portionspot.pos.sync

import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalePayment
import com.portionspot.pos.data.Supplier
import com.portionspot.pos.data.newId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sync DTOs for the SHARED PortionSpot Supabase (the same database the web POS and
 * the marketing site use). These mirror the WEB POS contract exactly — see
 * Reference/POS-main.zip src/lib/sync.js + db.js — so Android is a peer on the live
 * dataset, not a separate schema:
 *
 *  - products      keyed by `sku`             (bigint id is server-owned; we bridge on sku)
 *  - customers     keyed by `local_id`        (= the Android UUID)
 *  - sales         id = the ref string, line items + tenders live in JSONB columns
 *  - refunds       are `type='return'` sales rows with NEGATIVE totals
 *
 * PostgREST returns `numeric` columns as JSON STRINGS (to keep precision), so every
 * money field below is a String parsed with [toMoney]; `integer`/`bigint` come as
 * numbers. Stage 1 is PULL-ONLY (see PosSyncEngine) — the push mappings arrive with
 * Stage 2, which is why only the read (`toEntity`) direction is defined here.
 */
internal val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/** numeric-as-string (or null) → Double. */
private fun String?.toMoney(): Double = this?.toDoubleOrNull() ?: 0.0

/**
 * Fixed-format UTC ISO time. Room stores epoch millis; the cloud stores TEXT/timestamptz
 * strings. Fixed-width format ⇒ lexicographic order == chronological, which keeps the
 * `updated_at=gt.<cursor>` pull cursor trivial and dodges java.time (pre-API-26).
 */
internal object IsoTime {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    const val EPOCH = "1970-01-01T00:00:00.000Z"

    private fun formatter() = SimpleDateFormat(PATTERN, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun toIso(millis: Long): String = formatter().format(Date(millis))

    /** Parse the several timestamp shapes PostgREST emits (with/without millis or zone). */
    fun toMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val patterns = listOf(
            PATTERN,
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        )
        for (p in patterns) {
            runCatching {
                SimpleDateFormat(p, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(iso)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }
}

// ── products (keyed by sku) ───────────────────────────────────────────────────
@Serializable
data class ProductDto(
    val id: Long? = null,                                   // server-owned bigint; unused on pull
    val sku: String,
    val name: String,
    val category: String? = null,
    @SerialName("box_price") val boxPrice: String? = null,
    @SerialName("box_size") val boxSize: Int = 1,
    @SerialName("wholesale_price") val wholesalePrice: String? = null,
    @SerialName("retail_price") val retailPrice: String? = null,
    @SerialName("cost_price") val costPrice: String? = null,
    @SerialName("stock_boxes") val stockBoxes: Int = 0,
    @SerialName("stock_units") val stockUnits: Int = 0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Int = 5,
    val active: Boolean = true,
    @SerialName("product_type") val productType: String = "box",
    @SerialName("box_only") val boxOnly: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("show_image") val showImage: Boolean = true,
    // Measured products (unit-priced). These cloud columns are NEW: older rows have
    // them null, which must never wipe what the device already holds — see [toItem].
    val unit: String? = null,
    @SerialName("price_per_unit") val pricePerUnit: String? = null,
    @SerialName("stock_measured") val stockMeasured: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Coerce a cloud/legacy product_type to the three values the app understands. Older
 *  rows (and the app's own earlier push) used "unit" for a single-unit item; map that —
 *  and anything unrecognised — to box vs piece by box size. */
fun normalizeProductType(raw: String?, boxSize: Int): String = when (raw?.trim()?.lowercase()) {
    "box" -> "box"
    "set" -> "set"
    "piece" -> "piece"
    "measured" -> "measured"
    else -> if (boxSize > 1) "box" else "piece"
}

/** Merge a pulled product onto the local [Item] (bridged by sku), preserving the
 *  Android-only fields the cloud doesn't carry (barcode, colour, unit, tax rate). */
fun ProductDto.toItem(businessId: String, local: Item?): Item {
    val boxSz = if (boxSize < 1) 1 else boxSize
    val base = local ?: Item(businessId = businessId, name = name, sku = sku)
    // Remote is the source of truth for the image. Keep the local cached copy only
    // when the remote URL is unchanged; otherwise drop it (and any pending flag) so
    // display falls back to the new remote image (Coil fetches + caches it).
    val remoteImageUnchanged = imageUrl == base.imageUrl
    // Guard the type: if the local item is measured and the cloud row doesn't explicitly
    // declare a product_type, keep it measured so its unit fields aren't stranded. An
    // explicit cloud type (box/set/piece/measured) still wins.
    val mergedType =
        if (base.productType == "measured" && productType.isBlank()) "measured"
        else normalizeProductType(productType, boxSz)
    // Measured fields now EXIST in the cloud, so the pull applies them — but a null
    // (never-written, pre-migration) cloud value means "not set" and must keep the
    // local value rather than zeroing a product the device knows how to price.
    val mergedUnit = unit?.trim()?.ifBlank { null } ?: base.unit
    val mergedPricePerUnit = pricePerUnit?.toDoubleOrNull() ?: base.pricePerUnit
    val mergedStockMeasured = stockMeasured?.toDoubleOrNull() ?: base.stockMeasured
    return base.copy(
        businessId = businessId,
        name = name,
        // Never let a blank cloud sku wipe a real local sku (keeps the merge bridge stable).
        sku = sku.ifBlank { base.sku?.ifBlank { null } ?: sku },
        category = category,
        price = retailPrice.toMoney(),
        wholesalePrice = wholesalePrice.toMoney(),
        boxPrice = boxPrice.toMoney(),
        boxSize = boxSz,
        productType = mergedType,
        // cloud cost_price defaults to 0 = "unknown"; keep Android's null-means-unknown
        cost = costPrice?.toDoubleOrNull()?.takeIf { it > 0.0 },
        trackStock = true,
        stockQty = (stockBoxes * boxSz + stockUnits).toDouble(),
        reorderLevel = lowStockThreshold.toDouble(),
        unit = mergedUnit,
        pricePerUnit = mergedPricePerUnit,
        stockMeasured = mergedStockMeasured,
        imageUrl = imageUrl,
        imageLocalPath = if (remoteImageUnchanged) base.imageLocalPath else null,
        imagePending = if (remoteImageUnchanged) base.imagePending else false,
        showImage = showImage,
        isActive = active,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = false,
        pendingSync = false,
    )
}

// ── customers (keyed by local_id = Android UUID) ──────────────────────────────
@Serializable
data class CustomerDto(
    val id: Long? = null,
    @SerialName("local_id") val localId: String? = null,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val balance: String? = null,
    @SerialName("credit_limit") val creditLimit: String? = null,
    @SerialName("is_trade_account") val isTradeAccount: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Stable Android id for a pulled customer: its local_id, or a cloud-id-derived one. */
fun CustomerDto.bridgeId(): String = localId?.ifBlank { null } ?: "cust-${id ?: newId()}"

fun CustomerDto.toCustomer(businessId: String, local: Customer?): Customer {
    val bid = bridgeId()
    val base = local ?: Customer(id = bid, businessId = businessId, name = name)
    return base.copy(
        id = bid,
        businessId = businessId,
        name = name,
        phone = phone,
        email = email,
        address = address,
        note = notes,
        wholesale = isTradeAccount,
        // credit_limit is user-set master data on the cloud (the web has an editable
        // field); take the cloud value, falling back to the local one when unset.
        creditLimit = creditLimit?.toDoubleOrNull() ?: local?.creditLimit,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = false,
        pendingSync = false,
    )
}

// ── customers id map (bridges credit.customer_id bigint → local_id) ───────────
// Cloud credit_transactions.customer_id holds the customers BIGINT id (as text),
// NOT the local_id — so credit sync must translate through this.
@Serializable
data class CustomerIdRow(
    val id: Long,
    @SerialName("local_id") val localId: String? = null,
) {
    fun bridge(): String = localId?.ifBlank { null } ?: "cust-$id"
}

// ── credit_transactions (keyed by local_id) ───────────────────────────────────
@Serializable
data class CreditDto(
    val id: Long? = null,
    @SerialName("local_id") val localId: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val type: String,
    val amount: String? = null,
    val note: String? = null,
    val cashier: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

/** [androidCustomerId] is the resolved local customer id (credit.customer_id →
 *  customers.id → local_id). Bridged on local_id like customers. */
fun CreditDto.toCreditTxn(businessId: String, androidCustomerId: String, local: CreditTxn?): CreditTxn {
    val bid = localId?.ifBlank { null } ?: local?.id ?: "ctx-${id ?: newId()}"
    val base = local ?: CreditTxn(id = bid, businessId = businessId, customerId = androidCustomerId, type = type)
    return base.copy(
        id = bid,
        businessId = businessId,
        customerId = androidCustomerId,
        type = type,
        amount = amount.toMoney(),
        note = note,
        createdByName = cashier,
        createdAt = IsoTime.toMillis(createdAt),
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = false,
        pendingSync = false,
    )
}

// ── mobile_money_receipts (keyed by txn_code; local_id bridge) ────────────────
@Serializable
data class MobileMoneyDto(
    val id: Long? = null,
    @SerialName("local_id") val localId: String? = null,
    val provider: String = "unknown",
    @SerialName("txn_code") val txnCode: String,
    val amount: String? = null,
    val currency: String = "USD",
    val sender: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_phone") val senderPhone: String? = null,
    @SerialName("raw_body") val rawBody: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    val status: String = "unmatched",
    @SerialName("matched_customer_id") val matchedCustomerId: String? = null,
    @SerialName("matched_customer_name") val matchedCustomerName: String? = null,
    val purpose: String? = null,
    val note: String? = null,
    val cashier: String? = null,
    @SerialName("cashier_id") val cashierId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun cursorStamp(): String = updatedAt ?: IsoTime.EPOCH
}

fun MobileMoneyDto.toReceipt(businessId: String, local: MobileMoneyReceipt?): MobileMoneyReceipt {
    val rid = localId?.ifBlank { null } ?: local?.id ?: newId()
    val base = local ?: MobileMoneyReceipt(id = rid, businessId = businessId, txnCode = txnCode)
    val recvAt = IsoTime.toMillis(receivedAt).takeIf { it > 0 } ?: base.receivedAt
    return base.copy(
        id = rid,
        businessId = businessId,
        provider = provider,
        txnCode = txnCode,
        amount = amount.toMoney(),
        currency = currency,
        sender = sender,
        senderName = senderName,
        senderPhone = senderPhone,
        rawBody = rawBody ?: base.rawBody,
        receivedAt = recvAt,
        status = status,
        matchedCustomerId = matchedCustomerId,
        matchedCustomerName = matchedCustomerName,
        purpose = purpose,
        note = note,
        createdBy = cashierId,
        createdByName = cashier,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = false,
        pendingSync = false,
    )
}

// ── sales (id = ref string; line items + tenders in JSONB) ────────────────────
/** One line inside `sales.items` JSONB — camelCase keys, real numbers (not strings). */
@Serializable
data class SaleItemJson(
    val qty: Double = 1.0,
    val sku: String? = null,
    val mode: String = "retail",         // retail | wholesale | box
    val name: String = "",
    val label: String? = null,
    val subMode: String? = null,
    val unitPrice: Double = 0.0,
    val lineDiscount: Double = 0.0,
    // Per-line markup (cashier-only; folded into the price on customer receipts, but
    // recorded here so the shop can report on it in the cloud/web). Mirrors lineDiscount.
    val lineMarkup: Double = 0.0,
    val unitsPerLine: Int = 1,
    val boxSize: Int? = null,
)

/** One tender inside `sales.payments` JSONB. */
@Serializable
data class SalePaymentJson(
    val amount: Double = 0.0,
    val method: String = "cash",
)

@Serializable
data class SaleDto(
    val id: String,
    val ref: String? = null,
    val type: String = "sale",            // sale | return | quote
    val status: String = "completed",
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val items: List<SaleItemJson> = emptyList(),
    val subtotal: String? = null,
    @SerialName("total_discount") val totalDiscount: String? = null,
    @SerialName("markup_total") val markupTotal: String? = null,
    @SerialName("vat_amount") val vatAmount: String? = null,
    @SerialName("grand_total") val grandTotal: String? = null,
    val payments: List<SalePaymentJson> = emptyList(),
    @SerialName("amount_paid") val amountPaid: String? = null,
    @SerialName("change_given") val changeGiven: String? = null,
    @SerialName("change_owed") val changeOwed: String? = null,
    @SerialName("amount_owing") val amountOwing: String? = null,
    @SerialName("pay_method") val payMethod: String? = null,
    val cashier: String? = null,
    @SerialName("cashier_id") val cashierId: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Cursor value — prefer updated_at, fall back to created_at, then EPOCH. */
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun SaleDto.toSaleEntity(businessId: String): SaleEntity = SaleEntity(
    id = id,
    businessId = businessId,
    receiptNo = ref ?: id,
    status = status,
    subtotal = subtotal.toMoney(),
    discountTotal = totalDiscount.toMoney(),
    taxTotal = vatAmount.toMoney(),
    total = grandTotal.toMoney(),
    markupTotal = markupTotal.toMoney(),
    paymentMethod = payMethod ?: "cash",
    amountPaid = amountPaid.toMoney(),
    changeDue = changeGiven?.toDoubleOrNull(),
    changeOwed = changeOwed?.toDoubleOrNull(),
    paymentStatus = "paid",
    note = notes?.ifBlank { null },
    customerId = customerId?.ifBlank { null },
    customerName = customerName?.ifBlank { null },
    soldAt = IsoTime.toMillis(createdAt),
    createdBy = cashierId?.ifBlank { null },
    createdByName = cashier?.ifBlank { null },
    updatedAt = IsoTime.toMillis(updatedAt ?: createdAt),
    deleted = false,
    synced = true,
)

/** Materialise the JSONB line items into Room [SaleLine]s. [resolveItemId] maps a sku
 *  to the local item id so reports/profit joins work; unknown skus stay null. */
fun SaleDto.toSaleLines(businessId: String, resolveItemId: (String?) -> String?): List<SaleLine> =
    items.map { li ->
        SaleLine(
            saleId = id,
            businessId = businessId,
            itemId = resolveItemId(li.sku),
            name = li.name,
            qty = li.qty,
            unitPrice = li.unitPrice,
            lineDiscount = li.lineDiscount,
            lineMarkup = li.lineMarkup,
            lineTotal = li.unitPrice * li.qty - li.lineDiscount,
            mode = li.mode,
            unitsPerLine = if (li.unitsPerLine < 1) 1 else li.unitsPerLine,
        )
    }

// ── push DTOs (Stage 2) ───────────────────────────────────────────────────────
// The exact row shape the web writes (src/lib/sync.js pushSales). Stage 2 push is
// SALES + REFUNDS only — both are append-only / insert-once, so they can never
// clobber existing shared rows. Products push (overwrite-on-sku) and customers/
// credit push (the derived `balance` column is a corruption risk until credit is
// pulled) are deliberately left for later sub-stages.

@Serializable
data class ProductPushDto(
    val sku: String,
    val name: String,
    val category: String? = null,
    @SerialName("product_type") val productType: String = "box",
    @SerialName("box_price") val boxPrice: Double = 0.0,
    @SerialName("box_size") val boxSize: Int = 1,
    @SerialName("wholesale_price") val wholesalePrice: Double = 0.0,
    @SerialName("retail_price") val retailPrice: Double = 0.0,
    @SerialName("cost_price") val costPrice: Double = 0.0,
    @SerialName("stock_boxes") val stockBoxes: Int = 0,
    @SerialName("stock_units") val stockUnits: Int = 0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Int = 5,
    val active: Boolean = true,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("show_image") val showImage: Boolean = true,
    // Measured products — the cloud now carries these, so they two-way sync.
    val unit: String = "pc",
    @SerialName("price_per_unit") val pricePerUnit: Double = 0.0,
    @SerialName("stock_measured") val stockMeasured: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String,
)

/** Local [Item] → the web `products` row (upsert on sku). Total on-hand units are
 *  split back into boxes + loose the way the web stores them. Caller must ensure a
 *  non-blank sku (products.sku is NOT NULL and is the conflict key).
 *
 *  Only a REMOTE image URL is pushed: a still-pending local file path is never a
 *  valid cloud value, so the sync engine uploads it to Storage (which sets [imageUrl]
 *  to the public URL and clears [imagePending]) BEFORE calling this. */
fun Item.toProductPush(): ProductPushDto {
    val bs = if (boxSize < 1) 1 else boxSize
    val totalUnits = stockQty.toInt()
    return ProductPushDto(
        sku = sku.orEmpty(),
        name = name,
        category = category,
        productType = normalizeProductType(productType, bs),
        boxPrice = boxPrice,
        boxSize = bs,
        wholesalePrice = wholesalePrice,
        retailPrice = price,
        costPrice = cost ?: 0.0,
        stockBoxes = if (bs > 1) totalUnits / bs else 0,
        stockUnits = if (bs > 1) totalUnits % bs else totalUnits,
        lowStockThreshold = reorderLevel.toInt(),
        active = isActive,
        imageUrl = if (imagePending) null else imageUrl,
        showImage = showImage,
        unit = unit,
        pricePerUnit = pricePerUnit,
        stockMeasured = stockMeasured,
        updatedAt = IsoTime.toIso(updatedAt),
    )
}

@Serializable
data class SalePushDto(
    val id: String,                       // web keys sales by the ref string
    val ref: String,
    val type: String = "sale",            // sale | return
    val status: String = "completed",
    @SerialName("customer_id") val customerId: String = "",
    @SerialName("customer_name") val customerName: String = "",
    val items: List<SaleItemJson> = emptyList(),
    val subtotal: Double = 0.0,
    @SerialName("total_discount") val totalDiscount: Double = 0.0,
    @SerialName("markup_total") val markupTotal: Double = 0.0,
    @SerialName("vat_amount") val vatAmount: Double = 0.0,
    @SerialName("grand_total") val grandTotal: Double = 0.0,
    val payments: List<SalePaymentJson> = emptyList(),
    @SerialName("amount_paid") val amountPaid: Double = 0.0,
    @SerialName("change_given") val changeGiven: Double = 0.0,
    @SerialName("change_owed") val changeOwed: Double = 0.0,
    @SerialName("amount_owing") val amountOwing: Double = 0.0,
    @SerialName("pay_method") val payMethod: String = "cash",
    val cashier: String = "",
    @SerialName("cashier_id") val cashierId: String = "",
    val notes: String = "",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

private fun SaleLine.toItemJson(sku: String?): SaleItemJson = SaleItemJson(
    qty = qty,
    sku = sku,
    mode = mode,
    name = name,
    label = when (mode) {
        "box" -> "Box of $unitsPerLine"
        "wholesale" -> "Wholesale"
        else -> "Retail"
    },
    subMode = if (mode == "box") "boxes" else "",
    unitPrice = unitPrice,
    lineDiscount = lineDiscount,
    lineMarkup = lineMarkup,
    unitsPerLine = unitsPerLine,
    boxSize = if (mode == "box") unitsPerLine else null,
)

/** Just an `id`, for read-back verification of a push (see SupabaseRest.selectIdsIn). */
@Serializable
data class IdRow(val id: String)

/** A completed sale + its lines/tenders → the web `sales` row shape (type='sale'). */
fun buildSalePush(
    sale: SaleEntity,
    lines: List<SaleLine>,
    payments: List<SalePayment>,
    skuOf: (String?) -> String?,
): SalePushDto {
    val ref = sale.receiptNo ?: sale.id
    return SalePushDto(
        id = ref,
        ref = ref,
        type = "sale",
        status = sale.status,
        customerId = sale.customerId ?: "",
        customerName = sale.customerName ?: "",
        items = lines.map { it.toItemJson(skuOf(it.itemId)) },
        subtotal = sale.subtotal,
        totalDiscount = sale.discountTotal,
        markupTotal = sale.markupTotal,
        vatAmount = sale.taxTotal,
        grandTotal = sale.total,
        payments = payments.map { SalePaymentJson(amount = it.amount, method = it.method) },
        amountPaid = sale.amountPaid,
        changeGiven = sale.changeDue ?: 0.0,
        changeOwed = sale.changeOwed ?: 0.0,
        payMethod = sale.paymentMethod,
        cashier = sale.createdByName ?: "",
        cashierId = sale.createdBy ?: "",
        notes = sale.note ?: "",
        createdAt = IsoTime.toIso(sale.soldAt),
        updatedAt = IsoTime.toIso(sale.updatedAt),
    )
}

/** A refund + its returned lines → a `type='return'` sales row with NEGATIVE totals
 *  (the web's convention: "negative = cash going back out", nets revenue automatically). */
fun buildRefundPush(
    refund: Refund,
    lines: List<RefundLine>,
    skuOf: (String?) -> String?,
): SalePushDto = SalePushDto(
    id = refund.id,
    ref = "RTN-${refund.saleReceiptNo ?: refund.id.take(8)}",
    type = "return",
    status = "completed",
    customerId = refund.customerId ?: "",
    customerName = refund.customerName ?: "",
    items = lines.map { rl ->
        SaleItemJson(
            qty = rl.qty,
            sku = skuOf(rl.itemId),
            mode = rl.mode,
            name = rl.name,
            unitPrice = rl.unitPrice,
            unitsPerLine = rl.unitsPerLine,
            boxSize = if (rl.mode == "box") rl.unitsPerLine else null,
        )
    },
    subtotal = -refund.refundTotal,
    grandTotal = -refund.refundTotal,
    payMethod = "cash",
    cashier = refund.createdByName ?: "",
    cashierId = refund.createdBy ?: "",
    notes = refund.reason ?: "",
    createdAt = IsoTime.toIso(refund.createdAt),
    updatedAt = IsoTime.toIso(refund.updatedAt),
)

/** Customer push (upsert on local_id). Deliberately OMITS `balance` — it is
 *  ledger-derived and owned by whoever computes it, so we never overwrite it.
 *  `credit_limit` IS synced: it's user-set master data (the web exposes an editable
 *  field), so it two-way syncs like name/phone. */
@Serializable
data class CustomerPushDto(
    @SerialName("local_id") val localId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    @SerialName("credit_limit") val creditLimit: Double? = null,
    @SerialName("is_trade_account") val isTradeAccount: Boolean = false,
    val notes: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

fun Customer.toCustomerPush() = CustomerPushDto(
    localId = id,
    name = name,
    phone = phone,
    email = email,
    address = address,
    creditLimit = creditLimit,
    isTradeAccount = wholesale,
    notes = note,
    updatedAt = IsoTime.toIso(updatedAt),
)

/** Credit push (upsert on local_id). [cloudCustomerId] is the customers BIGINT id
 *  (as text) the web keys credit on — resolved from the local customer at push time. */
@Serializable
data class CreditPushDto(
    @SerialName("local_id") val localId: String,
    // Nullable: a walk-in change/refund row has no customer, and the cloud column is
    // nullable — pushing null is correct, never a reason to silently drop a money row.
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val type: String,
    val amount: Double = 0.0,
    val note: String? = null,
    val cashier: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun CreditTxn.toCreditPush(cloudCustomerId: String?, customerName: String?) = CreditPushDto(
    localId = id,
    customerId = cloudCustomerId,
    customerName = customerName,
    type = type,
    amount = amount,
    note = note,
    cashier = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
)

/** Mobile-money receipt push (upsert on txn_code — idempotent). */
@Serializable
data class MobileMoneyPushDto(
    @SerialName("local_id") val localId: String,
    val provider: String,
    @SerialName("txn_code") val txnCode: String,
    val amount: Double = 0.0,
    val currency: String = "USD",
    val sender: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_phone") val senderPhone: String? = null,
    @SerialName("raw_body") val rawBody: String? = null,
    @SerialName("received_at") val receivedAt: String,
    val status: String = "unmatched",
    @SerialName("matched_customer_id") val matchedCustomerId: String? = null,
    @SerialName("matched_customer_name") val matchedCustomerName: String? = null,
    val purpose: String? = null,
    val note: String? = null,
    val cashier: String? = null,
    @SerialName("cashier_id") val cashierId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

fun MobileMoneyReceipt.toPush() = MobileMoneyPushDto(
    localId = id,
    provider = provider,
    txnCode = txnCode,
    amount = amount,
    currency = currency,
    sender = sender,
    senderName = senderName,
    senderPhone = senderPhone,
    rawBody = rawBody,
    receivedAt = IsoTime.toIso(receivedAt),
    status = status,
    matchedCustomerId = matchedCustomerId,
    matchedCustomerName = matchedCustomerName,
    purpose = purpose,
    note = note,
    cashier = createdByName,
    cashierId = createdBy,
    updatedAt = IsoTime.toIso(updatedAt),
)

// ─────────────────────────────────────────────────────────────────────────────
// Accounting spine + supplier orders (expenses, cash_txns, suppliers,
// purchase_orders, purchase_order_items). These cloud tables ALL upsert on
// `local_id` (= the Android UUID), so the device owns row identity end to end.
//
// TYPE RULES (the cloud schema is mixed on purpose):
//   • created_at / updated_at are TIMESTAMPTZ  -> send the fixed-format UTC ISO
//     string from [IsoTime] (the `updated_at=gt.<cursor>` pull depends on it).
//   • approved_at / posted_at / next_run_at / last_run_at / eta /
//     arrival_prompted_at / sent_at / received_at are plain BIGINT epoch ms ->
//     send the Kotlin Long as-is.
//   • date / period_start / period_end are DATE -> already yyyy-MM-dd strings
//     locally, so they pass straight through.
// numeric columns come BACK as JSON strings (PostgREST precision), hence the
// String? + [toMoney] on every pull-side money field.
// ─────────────────────────────────────────────────────────────────────────────

// ── expenses ─────────────────────────────────────────────────────────────────
@Serializable
data class ExpenseDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    val category: String = "Other",
    val amount: String? = null,
    val date: String = "",
    val description: String? = null,
    val status: String = "pending",
    @SerialName("submitted_by") val submittedBy: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
    @SerialName("approved_at") val approvedAt: Long? = null,
    @SerialName("posted_at") val postedAt: Long? = null,
    @SerialName("cash_portion") val cashPortion: String? = null,
    @SerialName("payable_portion") val payablePortion: String? = null,
    @SerialName("capital_portion") val capitalPortion: String? = null,
    val recurring: Boolean = false,
    @SerialName("recurrence_period") val recurrencePeriod: String? = null,
    @SerialName("recurrence_active") val recurrenceActive: Boolean = true,
    @SerialName("is_template") val isTemplate: Boolean = false,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "exp-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun ExpenseDto.toExpense(businessId: String, local: Expense?): Expense {
    val bid = local?.id ?: bridgeId()
    val base = local ?: Expense(id = bid, businessId = businessId, date = date)
    return base.copy(
        id = bid,
        localId = bid,
        businessId = businessId,
        category = category,
        amount = amount.toMoney(),
        date = date.ifBlank { base.date },
        description = description,
        status = status,
        submittedBy = submittedBy,
        submittedByName = submittedByName,
        approvedBy = approvedBy,
        approvedByName = approvedByName,
        approvedAt = approvedAt,
        postedAt = postedAt,
        cashPortion = cashPortion.toMoney(),
        payablePortion = payablePortion.toMoney(),
        capitalPortion = capitalPortion.toMoney(),
        recurring = recurring,
        recurrencePeriod = recurrencePeriod,
        recurrenceActive = recurrenceActive,
        isTemplate = isTemplate,
        templateId = templateId,
        nextRunAt = nextRunAt,
        lastRunAt = lastRunAt,
        periodStart = periodStart,
        periodEnd = periodEnd,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class ExpensePushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("business_id") val businessId: String,
    val category: String,
    val amount: Double = 0.0,
    val date: String,
    val description: String? = null,
    val status: String = "pending",
    @SerialName("submitted_by") val submittedBy: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
    @SerialName("approved_at") val approvedAt: Long? = null,
    @SerialName("posted_at") val postedAt: Long? = null,
    @SerialName("cash_portion") val cashPortion: Double = 0.0,
    @SerialName("payable_portion") val payablePortion: Double = 0.0,
    @SerialName("capital_portion") val capitalPortion: Double = 0.0,
    val recurring: Boolean = false,
    @SerialName("recurrence_period") val recurrencePeriod: String? = null,
    @SerialName("recurrence_active") val recurrenceActive: Boolean = true,
    @SerialName("is_template") val isTemplate: Boolean = false,
    @SerialName("template_id") val templateId: String? = null,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

fun Expense.toExpensePush() = ExpensePushDto(
    localId = localId.ifBlank { id },
    businessId = businessId,
    category = category,
    amount = amount,
    date = date,
    description = description,
    status = status,
    submittedBy = submittedBy,
    submittedByName = submittedByName,
    approvedBy = approvedBy,
    approvedByName = approvedByName,
    approvedAt = approvedAt,
    postedAt = postedAt,
    cashPortion = cashPortion,
    payablePortion = payablePortion,
    capitalPortion = capitalPortion,
    recurring = recurring,
    recurrencePeriod = recurrencePeriod,
    recurrenceActive = recurrenceActive,
    isTemplate = isTemplate,
    templateId = templateId,
    nextRunAt = nextRunAt,
    lastRunAt = lastRunAt,
    periodStart = periodStart,
    periodEnd = periodEnd,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
    deleted = deleted,
)

// ── cash_txns ────────────────────────────────────────────────────────────────
@Serializable
data class CashTxnDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    val type: String = "adjust",
    val amount: String? = null,
    val source: String? = null,
    val note: String? = null,
    @SerialName("ref_type") val refType: String? = null,
    @SerialName("ref_id") val refId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "cash-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun CashTxnDto.toCashTxn(businessId: String, local: CashTxn?): CashTxn {
    val bid = local?.id ?: bridgeId()
    val base = local ?: CashTxn(id = bid, businessId = businessId, type = type)
    return base.copy(
        id = bid,
        localId = bid,
        businessId = businessId,
        type = type,
        amount = amount.toMoney(),
        source = source,
        note = note,
        refType = refType,
        refId = refId,
        createdBy = createdBy,
        createdByName = createdByName,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class CashTxnPushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("business_id") val businessId: String,
    val type: String,
    val amount: Double = 0.0,
    val source: String? = null,
    val note: String? = null,
    @SerialName("ref_type") val refType: String? = null,
    @SerialName("ref_id") val refId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

fun CashTxn.toCashTxnPush() = CashTxnPushDto(
    localId = localId.ifBlank { id },
    businessId = businessId,
    type = type,
    amount = amount,
    source = source,
    note = note,
    refType = refType,
    refId = refId,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
    deleted = deleted,
)

// ── suppliers ────────────────────────────────────────────────────────────────
@Serializable
data class SupplierDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    val name: String = "",
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "sup-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun SupplierDto.toSupplier(businessId: String, local: Supplier?): Supplier {
    val bid = local?.id ?: bridgeId()
    val base = local ?: Supplier(id = bid, businessId = businessId, name = name)
    return base.copy(
        id = bid,
        localId = bid,
        businessId = businessId,
        name = name.ifBlank { base.name },
        phone = phone,
        email = email,
        address = address,
        notes = notes,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class SupplierPushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

fun Supplier.toSupplierPush() = SupplierPushDto(
    localId = localId.ifBlank { id },
    businessId = businessId,
    name = name,
    phone = phone,
    email = email,
    address = address,
    notes = notes,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
    deleted = deleted,
)

// ── purchase_orders ──────────────────────────────────────────────────────────
@Serializable
data class PurchaseOrderDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    val ref: String = "",
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String = "draft",
    val notes: String? = null,
    val eta: Long? = null,
    @SerialName("cash_paid") val cashPaid: String? = null,
    @SerialName("capital_paid") val capitalPaid: String? = null,
    @SerialName("payable_remainder") val payableRemainder: String? = null,
    @SerialName("arrival_prompted_at") val arrivalPromptedAt: Long? = null,
    @SerialName("sent_at") val sentAt: Long? = null,
    @SerialName("received_at") val receivedAt: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "po-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun PurchaseOrderDto.toPurchaseOrder(businessId: String, local: PurchaseOrder?): PurchaseOrder {
    val bid = local?.id ?: bridgeId()
    val base = local ?: PurchaseOrder(id = bid, businessId = businessId, ref = ref)
    return base.copy(
        id = bid,
        localId = bid,
        businessId = businessId,
        ref = ref.ifBlank { base.ref },
        supplierId = supplierId,
        supplierName = supplierName ?: base.supplierName,
        status = status,
        notes = notes,
        eta = eta,
        cashPaid = cashPaid.toMoney(),
        capitalPaid = capitalPaid.toMoney(),
        payableRemainder = payableRemainder.toMoney(),
        arrivalPromptedAt = arrivalPromptedAt,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        sentAt = sentAt,
        receivedAt = receivedAt,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class PurchaseOrderPushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("business_id") val businessId: String,
    val ref: String,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("supplier_name") val supplierName: String = "",
    val status: String = "draft",
    val notes: String? = null,
    val eta: Long? = null,
    @SerialName("cash_paid") val cashPaid: Double = 0.0,
    @SerialName("capital_paid") val capitalPaid: Double = 0.0,
    @SerialName("payable_remainder") val payableRemainder: Double = 0.0,
    @SerialName("arrival_prompted_at") val arrivalPromptedAt: Long? = null,
    @SerialName("sent_at") val sentAt: Long? = null,
    @SerialName("received_at") val receivedAt: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

fun PurchaseOrder.toPurchaseOrderPush() = PurchaseOrderPushDto(
    localId = localId.ifBlank { id },
    businessId = businessId,
    ref = ref,
    supplierId = supplierId,
    supplierName = supplierName,
    status = status,
    notes = notes,
    eta = eta,
    cashPaid = cashPaid,
    capitalPaid = capitalPaid,
    payableRemainder = payableRemainder,
    arrivalPromptedAt = arrivalPromptedAt,
    sentAt = sentAt,
    receivedAt = receivedAt,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
    deleted = deleted,
)

// ── purchase_order_items ─────────────────────────────────────────────────────
// Linked to its header by `po_local_id` (a plain value link — the cloud has NO hard
// FK — so a line can never be rejected for arriving before/without its parent).
// The line carries no timestamps of its own on-device: it only ever changes as part
// of a PO write, so both cloud stamps come from the parent PO's updatedAt.
@Serializable
data class PurchaseOrderLineDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("po_local_id") val poLocalId: String = "",
    @SerialName("item_id") val itemId: String? = null,
    val name: String = "",
    val sku: String? = null,
    val qty: String? = null,
    @SerialName("unit_cost") val unitCost: String? = null,
    @SerialName("sell_price") val sellPrice: String? = null,
    @SerialName("stock_on_arrival") val stockOnArrival: Boolean = true,
    @SerialName("product_type") val productType: String = "piece",
    @SerialName("received_qty") val receivedQty: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "pol-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun PurchaseOrderLineDto.toPurchaseOrderLine(local: PurchaseOrderLine?): PurchaseOrderLine {
    val bid = local?.id ?: bridgeId()
    val base = local ?: PurchaseOrderLine(id = bid, poId = poLocalId)
    return base.copy(
        id = bid,
        localId = bid,
        poId = poLocalId.ifBlank { base.poId },
        itemId = itemId,
        name = name.ifBlank { base.name },
        sku = sku,
        qty = qty?.toDoubleOrNull() ?: base.qty,
        unitCost = unitCost.toMoney(),
        sellPrice = sellPrice?.toDoubleOrNull(),
        stockOnArrival = stockOnArrival,
        productType = productType,
        receivedQty = receivedQty?.toDoubleOrNull(),
        pendingSync = false,
    )
}

@Serializable
data class PurchaseOrderLinePushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("po_local_id") val poLocalId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String = "",
    val sku: String? = null,
    val qty: Double = 0.0,
    @SerialName("unit_cost") val unitCost: Double = 0.0,
    @SerialName("sell_price") val sellPrice: Double? = null,
    @SerialName("stock_on_arrival") val stockOnArrival: Boolean = true,
    @SerialName("product_type") val productType: String = "piece",
    @SerialName("received_qty") val receivedQty: Double? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** [stamp] is the parent PO's `updatedAt` (epoch ms): lines have no clock of their
 *  own, and the pull cursor needs a moving `updated_at` on every push. */
fun PurchaseOrderLine.toPurchaseOrderLinePush(stamp: Long) = PurchaseOrderLinePushDto(
    localId = localId.ifBlank { id },
    poLocalId = poId,
    itemId = itemId,
    name = name,
    sku = sku,
    qty = qty,
    unitCost = unitCost,
    sellPrice = sellPrice,
    stockOnArrival = stockOnArrival,
    productType = productType,
    receivedQty = receivedQty,
    createdAt = IsoTime.toIso(stamp),
    updatedAt = IsoTime.toIso(stamp),
)

// ─────────────────────────────────────────────────────────────────────────────
// notifications — the admin alert feed, shared across the cashier phones and the
// admin phone.
//
// UPSERT KEY IS COMPOSITE: `business_id,dedupe_key`, NOT `local_id`. Two devices
// computing the same condition (the same low-stock item, the same owed refund) mint
// DIFFERENT local ids but the SAME dedupeKey; conflicting on local_id would leave one
// cloud row per device. `local_id` is still sent (it is this device's row id, useful
// for tracing) but the cloud column is deliberately non-unique.
//
// TYPE RULES:
//   • created_at / updated_at are TIMESTAMPTZ -> fixed-format UTC ISO via [IsoTime]
//     (the `updated_at=gt.<cursor>` pull depends on it).
//   • event_at / read_at are plain BIGINT epoch ms -> send the Long as-is.
//   • pushedAt is NOT in the contract at all: it is device-local state recording
//     whether THIS phone already fired its own heads-up notification. It is never
//     sent, and a pull must preserve the local value.
// ─────────────────────────────────────────────────────────────────────────────
@Serializable
data class NotificationDto(
    @SerialName("local_id") val localId: String? = null,
    @SerialName("business_id") val businessId: String? = null,
    val category: String = "system",
    val severity: String = "info",
    val title: String = "",
    val body: String = "",
    @SerialName("dedupe_key") val dedupeKey: String = "",
    @SerialName("ref_type") val refType: String? = null,
    @SerialName("ref_id") val refId: String? = null,
    @SerialName("event_at") val eventAt: Long? = null,
    @SerialName("read_at") val readAt: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun bridgeId(): String = localId?.ifBlank { null } ?: "notif-${newId()}"
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

/**
 * Merge an incoming cloud row onto [local] (matched by `(businessId, dedupeKey)`, never
 * by id). The local row's `id` and `pushedAt` survive untouched, so this device keeps
 * its own record of whether it has already raised a heads-up for the alert.
 */
fun NotificationDto.toNotification(businessId: String, local: AppNotification?): AppNotification {
    val bid = local?.id ?: bridgeId()
    val base = local ?: AppNotification(
        id = bid, businessId = businessId, category = category,
        title = title, body = body, dedupeKey = dedupeKey
    )
    return base.copy(
        id = bid,                       // keep THIS device's row id
        businessId = businessId,
        category = category,
        severity = severity,
        title = title,
        body = body,
        dedupeKey = dedupeKey.ifBlank { base.dedupeKey },
        refType = refType,
        refId = refId,
        eventAt = eventAt ?: base.eventAt,
        readAt = readAt,                // read-state IS shared
        pushedAt = base.pushedAt,       // device-local: never overwritten by a pull
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class NotificationPushDto(
    @SerialName("local_id") val localId: String,
    @SerialName("business_id") val businessId: String,
    val category: String,
    val severity: String,
    val title: String,
    val body: String,
    @SerialName("dedupe_key") val dedupeKey: String,
    @SerialName("ref_type") val refType: String? = null,
    @SerialName("ref_id") val refId: String? = null,
    @SerialName("event_at") val eventAt: Long,
    @SerialName("read_at") val readAt: Long? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

/** NOTE: no `pushed_at` — see the header. */
fun AppNotification.toNotificationPush() = NotificationPushDto(
    localId = id,
    businessId = businessId,
    category = category,
    severity = severity,
    title = title,
    body = body,
    dedupeKey = dedupeKey,
    refType = refType,
    refId = refId,
    eventAt = eventAt,
    readAt = readAt,
    createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt),
    deleted = deleted,
)
