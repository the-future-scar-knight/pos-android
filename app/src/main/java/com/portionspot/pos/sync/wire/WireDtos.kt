package com.portionspot.pos.sync.wire

import com.portionspot.pos.sync.IsoTime
import com.portionspot.pos.sync.normalizeProductType
import com.portionspot.pos.data.CashSession
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.cashMovementTypeToWire
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.ItemAttribute
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.attrNorm
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalePayment
import com.portionspot.pos.data.StockMovement
import com.portionspot.pos.data.saleLineDiscountTotal
import com.portionspot.pos.data.saleMarginFromLines
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync DTOs for the REAL shared schema — the one the web POS actually runs on, verified
 * column by column against the live project rather than recalled from a handoff.
 *
 * These replace the DTOs in Dtos.kt, which speak a contract that no longer exists on the
 * other side: a `products` table (the catalogue is `items`), sales whose id is a receipt
 * REF and whose lines live in a JSONB blob (they are `sale_items` rows), refunds as
 * negative-total sales rows (they are `refunds` + `refund_items`), and a `local_id`
 * bridge column on tables that do not have one.
 *
 * ── FOUR RULES THIS FILE EXISTS TO ENFORCE ────────────────────────────────────
 *
 * 1. **The primary key is the Android id.** Every cloud table here is uuid-keyed and
 *    `newId()` is already `UUID.randomUUID()`, so a row goes up under the id it already
 *    has. There is no `local_id` anywhere and nothing to bridge — which also removes a
 *    whole class of "the referenced row hasn't synced yet" failure the old engine had to
 *    carry warnings for.
 *
 * 2. **Never name a GENERATED column.** `sale_items.line_cost` and `sale_items.line_profit`
 *    are `GENERATED ALWAYS`. Naming either in an INSERT does not fail that row — it fails
 *    the WHOLE batch, taking every other sale in the push down with it. They are absent
 *    from [SaleItemPushDto] deliberately and must stay absent.
 *
 * 3. **Push `client_updated_at`, never `updated_at`.** The server's `updated_at` defaults
 *    to `now()` and is what every pull cursor reads. If a device wrote it, a phone with a
 *    skewed clock could stamp a row in the future and every other device would skip
 *    everything behind it, silently and permanently. The client's own timestamp goes in
 *    `client_updated_at`, which exists for exactly this and is indexed for it.
 *
 * 4. **Money goes UP as a number and comes DOWN as a string.** PostgREST renders `numeric`
 *    as a JSON string to keep precision, so every pull field is `String?` parsed with
 *    [toMoney]; push fields are plain `Double`.
 */

/**
 * numeric-as-string (or null) → Double.
 *
 * Its own copy rather than a shared one: this package is the NEW contract and the old
 * `sync` package is on its way out, so nothing here should depend on a file that is
 * going to be deleted.
 */
internal fun String?.toMoney(): Double = this?.toDoubleOrNull() ?: 0.0

// ─────────────────────────────── items (PULL ONLY) ───────────────────────────────

/**
 * The catalogue. **Pull only** — the web owns it.
 *
 * Android does not push items at all, and that is a decision rather than an omission:
 * `items_sku_idx` is a plain btree, not unique, so there is no conflict target for the
 * old `on_conflict=sku` upsert, and a shop that edits its catalogue in two places has no
 * way to say which edit won. Stock is not pushed here either — `stock_movements` is the
 * authority and `items.stock_qty` is a derived cache each device recomputes after a pull.
 */
@Serializable
data class ItemDto(
    val id: String,
    val name: String? = null,
    val barcode: String? = null,
    val sku: String? = null,
    val category: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    /** The price of ONE STOCK UNIT. For a `measure` product that unit is the kg / L / m —
     *  there is no second per-unit price column, and expecting one is how a measured
     *  product ends up priced per kilogram at the price of a whole sack. */
    val price: String? = null,
    @SerialName("wholesale_price") val wholesalePrice: String? = null,
    @SerialName("box_price") val boxPrice: String? = null,
    @SerialName("box_size") val boxSize: String? = null,
    val cost: String? = null,
    @SerialName("tax_rate") val taxRate: String? = null,
    @SerialName("track_stock") val trackStock: Boolean = true,
    /** On-hand in stock units. For a `measure` product this carries the FRACTIONAL
     *  quantity — measured stock deliberately has no column of its own. */
    @SerialName("stock_qty") val stockQty: String? = null,
    @SerialName("reorder_level") val reorderLevel: String? = null,
    val unit: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("product_type") val productType: String = "piece",
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * Merge a pulled item onto the local row, keyed by id.
 *
 * The cloud has no column for images (`imageUrl`, `showImage`), incoming purchase-order
 * stock (`pendingQty`, `pendingNew`) or the local image cache, so every one of those is
 * carried over from [local] untouched. A pull must never blank what the device knows and
 * the shared schema simply has no opinion about.
 */
fun ItemDto.toItem(businessId: String, local: Item?): Item {
    val type = normalizeProductType(productType, boxSizeInt())
    val base = local ?: Item(id = id, businessId = businessId, name = name.orEmpty())
    val onHand = stockQty.toMoney()
    val unitPrice = price.toMoney()
    return base.copy(
        id = id,
        businessId = businessId,
        categoryId = categoryId,
        name = name?.ifBlank { null } ?: base.name,
        barcode = barcode,
        sku = sku,
        category = category,
        // A measured product's `price` IS its per-unit price. Writing it to both keeps
        // whichever field the UI reads correct without a second cloud column.
        price = unitPrice,
        pricePerUnit = if (type == "measured") unitPrice else base.pricePerUnit,
        wholesalePrice = wholesalePrice.toMoney(),
        boxPrice = boxPrice.toMoney(),
        boxSize = boxSizeInt(),
        productType = type,
        // The cloud defaults cost to NULL, and null means UNKNOWN, not free. Booking an
        // unknown cost as zero would report the whole line as profit.
        cost = cost?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: base.cost,
        taxRate = taxRate?.toDoubleOrNull() ?: base.taxRate,
        trackStock = trackStock,
        // Measured stock lives in the SAME column; route it to the field the app reads
        // for that product type and leave the other alone.
        stockQty = if (type == "measured") base.stockQty else onHand,
        stockMeasured = if (type == "measured") onHand else base.stockMeasured,
        // The shop's own figure, and the instant it was true. `stock_movements` is a log
        // of CHANGES with no opening entry, so this is the only thing that makes the
        // ledger add up to an on-hand rather than to "how much this has moved".
        stockBaseQty = onHand,
        stockBaseAt = IsoTime.toMillis(updatedAt),
        reorderLevel = reorderLevel.toMoney(),
        unit = unit?.ifBlank { null } ?: base.unit,
        colorHex = colorHex ?: base.colorHex,
        isActive = isActive,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

/** `box_size` is `numeric(14,3)` on the wire but a whole number of units in the app. */
private fun ItemDto.boxSizeInt(): Int =
    (boxSize?.toDoubleOrNull() ?: 1.0).toInt().coerceAtLeast(1)

// ─────────────────────── item_attributes (PULL ONLY) ───────────────────────

/**
 * An item's key/value tag. **Pull only**, for the same reason as [ItemDto]: the catalogue
 * belongs to the web, and these are part of the catalogue.
 *
 * In the live shop every row is `key = "car"` and the value is a vehicle the part fits —
 * 671 rows over 80 items, which is 671 ways to find a product that its own name never
 * mentions.
 *
 * `key_norm` and `value_norm` are read but not trusted: both are nullable on the wire, and
 * a null one on a hand-inserted row would drop that fitment out of every search on this
 * side. [toItemAttribute] falls back to computing them, and the fallback is the normal
 * path rather than an edge case worth avoiding.
 */
@Serializable
data class ItemAttributeDto(
    val id: String,
    @SerialName("item_id") val itemId: String,
    val key: String? = null,
    val value: String? = null,
    @SerialName("key_norm") val keyNorm: String? = null,
    @SerialName("value_norm") val valueNorm: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * Wire row → local tag. Returns null for a row with no key or no value: an empty tag is
 * not a fitment, it is a row that would match every search for the empty string.
 */
fun ItemAttributeDto.toItemAttribute(businessId: String): ItemAttribute? {
    val k = key?.trim().orEmpty()
    val v = value?.trim().orEmpty()
    if (k.isEmpty() || v.isEmpty()) return null
    return ItemAttribute(
        id = id,
        businessId = businessId,
        itemId = itemId,
        key = k,
        value = v,
        keyNorm = keyNorm?.trim()?.ifBlank { null } ?: attrNorm(k),
        valueNorm = valueNorm?.trim()?.ifBlank { null } ?: attrNorm(v),
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
    )
}

// ────────────────────────────────── customers ──────────────────────────────────

@Serializable
data class CustomerDto(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null,
    val wholesale: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * `creditLimit` is preserved from [local] and never read from the wire: the shared
 * `customers` table has no such column, so a pull that "applied" it would blank every
 * limit the owner had set.
 */
fun CustomerDto.toCustomer(businessId: String, local: Customer?): Customer {
    val base = local ?: Customer(id = id, businessId = businessId, name = name.orEmpty())
    return base.copy(
        id = id,
        businessId = businessId,
        name = name?.ifBlank { null } ?: base.name,
        phone = phone,
        email = email,
        address = address,
        note = note,
        wholesale = wholesale,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class CustomerPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null,
    val wholesale: Boolean = false,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun Customer.toPush(): CustomerPushDto = CustomerPushDto(
    id = id,
    businessId = businessId,
    name = name,
    phone = phone,
    email = email,
    address = address,
    note = note,
    wholesale = wholesale,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ──────────────────────────────────── sales ────────────────────────────────────

@Serializable
data class SaleDto(
    val id: String,
    @SerialName("receipt_no") val receiptNo: String? = null,
    val status: String = "completed",
    val subtotal: String? = null,
    @SerialName("discount_total") val discountTotal: String? = null,
    @SerialName("line_discount_total") val lineDiscountTotal: String? = null,
    @SerialName("markup_total") val markupTotal: String? = null,
    @SerialName("cost_total") val costTotal: String? = null,
    @SerialName("profit_total") val profitTotal: String? = null,
    @SerialName("tax_total") val taxTotal: String? = null,
    val total: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    val tendered: String? = null,
    @SerialName("amount_paid") val amountPaid: String? = null,
    @SerialName("change_due") val changeDue: String? = null,
    @SerialName("payment_ref") val paymentRef: String? = null,
    @SerialName("payment_status") val paymentStatus: String = "unpaid",
    val note: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("sold_at") val soldAt: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: soldAt ?: IsoTime.EPOCH
}

fun SaleDto.toSaleEntity(businessId: String): SaleEntity = SaleEntity(
    id = id,
    businessId = businessId,
    receiptNo = receiptNo,
    status = status,
    subtotal = subtotal.toMoney(),
    discountTotal = discountTotal.toMoney(),
    markupTotal = markupTotal.toMoney(),
    taxTotal = taxTotal.toMoney(),
    total = total.toMoney(),
    paymentMethod = paymentMethod ?: "cash",
    tendered = tendered?.toDoubleOrNull(),
    amountPaid = amountPaid.toMoney(),
    changeDue = changeDue?.toDoubleOrNull(),
    paymentRef = paymentRef,
    paymentStatus = paymentStatus,
    note = note?.ifBlank { null },
    customerId = customerId?.ifBlank { null },
    customerName = customerName?.ifBlank { null },
    soldAt = IsoTime.toMillis(soldAt),
    createdBy = createdBy?.ifBlank { null },
    createdByName = createdByName?.ifBlank { null },
    updatedAt = IsoTime.toMillis(cursorStamp()),
    deleted = deleted,
    synced = true,
)

/**
 * Fold a pulled sale onto the EXISTING local row — the receipt-edit half of the pull.
 *
 * The local primary key never moves: it is referenced by lines, tenders, refunds, credit
 * rows and the audit trail. A money column that is absent or unparseable keeps what the
 * device already had rather than becoming 0.0.
 */
fun SaleDto.mergeIntoSale(local: SaleEntity): SaleEntity {
    fun money(raw: String?, fallback: Double): Double = raw?.toDoubleOrNull() ?: fallback
    return local.copy(
        receiptNo = receiptNo ?: local.receiptNo,
        status = status,
        subtotal = money(subtotal, local.subtotal),
        discountTotal = money(discountTotal, local.discountTotal),
        markupTotal = money(markupTotal, local.markupTotal),
        taxTotal = money(taxTotal, local.taxTotal),
        total = money(total, local.total),
        paymentMethod = paymentMethod?.ifBlank { null } ?: local.paymentMethod,
        tendered = tendered?.toDoubleOrNull() ?: local.tendered,
        amountPaid = money(amountPaid, local.amountPaid),
        changeDue = changeDue?.toDoubleOrNull() ?: local.changeDue,
        paymentRef = paymentRef ?: local.paymentRef,
        paymentStatus = paymentStatus,
        note = note?.ifBlank { null } ?: local.note,
        customerId = customerId?.ifBlank { null } ?: local.customerId,
        customerName = customerName?.ifBlank { null } ?: local.customerName,
        createdBy = createdBy?.ifBlank { null } ?: local.createdBy,
        createdByName = createdByName?.ifBlank { null } ?: local.createdByName,
        updatedAt = IsoTime.toMillis(cursorStamp()),
        synced = true,
    )
}

/**
 * The sale header as it goes UP.
 *
 * Carries the two figures the shared money model asks each client to state outright
 * rather than leave to be reconstructed — see [com.portionspot.pos.data.saleMarginFromLines]
 * and [com.portionspot.pos.data.saleLineDiscountTotal] for what they mean and why they
 * are derived from the lines instead of stored on the row.
 */
@Serializable
data class SalePushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("receipt_no") val receiptNo: String? = null,
    val status: String,
    val subtotal: Double,
    @SerialName("discount_total") val discountTotal: Double,
    @SerialName("line_discount_total") val lineDiscountTotal: Double,
    @SerialName("markup_total") val markupTotal: Double,
    @SerialName("cost_total") val costTotal: Double,
    @SerialName("profit_total") val profitTotal: Double,
    @SerialName("tax_total") val taxTotal: Double,
    val total: Double,
    @SerialName("payment_method") val paymentMethod: String,
    val tendered: Double? = null,
    @SerialName("amount_paid") val amountPaid: Double,
    @SerialName("change_due") val changeDue: Double? = null,
    @SerialName("payment_ref") val paymentRef: String? = null,
    @SerialName("payment_status") val paymentStatus: String,
    val note: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("sold_at") val soldAt: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * ★ `line_cost` and `line_profit` are GENERATED ALWAYS on the cloud and are therefore
 * ABSENT here. Adding either "for completeness" fails the entire batch with a Postgres
 * error about a generated column, not just the offending row.
 */
@Serializable
data class SaleItemPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("sale_id") val saleId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String,
    val qty: Double,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("unit_cost") val unitCost: Double? = null,
    @SerialName("line_discount") val lineDiscount: Double,
    @SerialName("line_markup") val lineMarkup: Double,
    @SerialName("line_tax") val lineTax: Double,
    @SerialName("line_total") val lineTotal: Double,
    val mode: String,
    @SerialName("units_per_line") val unitsPerLine: Double,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

@Serializable
data class SalePaymentPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("sale_id") val saleId: String,
    val method: String,
    val amount: Double,
    val reference: String? = null,
    @SerialName("tender_currency") val tenderCurrency: String? = null,
    @SerialName("tender_amount") val tenderAmount: Double? = null,
    val rate: Double? = null,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * Build the header push for a sale, deriving the two wire figures from [lines].
 *
 * [lines] must already be filtered to the LIVE ones — a tombstoned line removed by an
 * in-place receipt edit is not part of what the receipt says now, and including it would
 * put goods on the cloud row that the printed receipt does not have.
 */
fun buildSalePush(sale: SaleEntity, lines: List<SaleLine>): SalePushDto {
    val margin = saleMarginFromLines(sale, lines)
    return SalePushDto(
        id = sale.id,
        businessId = sale.businessId,
        receiptNo = sale.receiptNo,
        status = sale.status,
        subtotal = sale.subtotal,
        discountTotal = sale.discountTotal,
        lineDiscountTotal = saleLineDiscountTotal(sale, lines),
        markupTotal = sale.markupTotal,
        costTotal = margin.costTotal,
        profitTotal = margin.profit,
        taxTotal = sale.taxTotal,
        total = sale.total,
        paymentMethod = sale.paymentMethod,
        tendered = sale.tendered,
        amountPaid = sale.amountPaid,
        changeDue = sale.changeDue,
        paymentRef = sale.paymentRef,
        paymentStatus = sale.paymentStatus,
        note = sale.note,
        customerId = sale.customerId,
        customerName = sale.customerName,
        soldAt = IsoTime.toIso(sale.soldAt),
        createdBy = sale.createdBy,
        createdByName = sale.createdByName,
        deleted = sale.deleted,
        clientUpdatedAt = IsoTime.toIso(sale.updatedAt),
    )
}

fun SaleLine.toPush(): SaleItemPushDto = SaleItemPushDto(
    id = id,
    businessId = businessId,
    saleId = saleId,
    itemId = itemId,
    name = name,
    qty = qty,
    unitPrice = unitPrice,
    unitCost = unitCost,
    lineDiscount = lineDiscount,
    lineMarkup = lineMarkup,
    lineTax = lineTax,
    lineTotal = lineTotal,
    mode = mode,
    unitsPerLine = unitsPerLine.toDouble(),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

fun SalePayment.toPush(): SalePaymentPushDto = SalePaymentPushDto(
    id = id,
    businessId = businessId,
    saleId = saleId,
    method = method,
    amount = amount,
    reference = reference,
    tenderCurrency = tenderCurrency,
    tenderAmount = tenderAmount,
    rate = rate,
    createdAt = IsoTime.toIso(createdAt),
    clientUpdatedAt = IsoTime.toIso(createdAt),
)

/**
 * A `sale_items` row coming DOWN. Pulled on its OWN cursor rather than fetched per sale:
 * one request for the whole page beats one request per receipt, and a pull that dies
 * part-way resumes where it stopped instead of re-reading every sale the shop has made.
 *
 * `line_cost` and `line_profit` are readable here (they are generated, not secret) but
 * are deliberately not mapped — Android derives its own from `unit_cost`, and importing
 * the cloud's copy would give the device two costs for one line.
 */
@Serializable
data class SaleItemDto(
    val id: String,
    @SerialName("sale_id") val saleId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String? = null,
    val qty: String? = null,
    @SerialName("unit_price") val unitPrice: String? = null,
    @SerialName("unit_cost") val unitCost: String? = null,
    @SerialName("line_discount") val lineDiscount: String? = null,
    @SerialName("line_markup") val lineMarkup: String? = null,
    @SerialName("line_tax") val lineTax: String? = null,
    @SerialName("line_total") val lineTotal: String? = null,
    val mode: String = "retail",
    @SerialName("units_per_line") val unitsPerLine: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: IsoTime.EPOCH
}

fun SaleItemDto.toSaleLine(businessId: String, local: SaleLine?): SaleLine {
    val base = local ?: SaleLine(
        id = id, saleId = saleId, businessId = businessId, name = name.orEmpty()
    )
    return base.copy(
        id = id,
        saleId = saleId,
        businessId = businessId,
        itemId = itemId ?: base.itemId,
        name = name?.ifBlank { null } ?: base.name,
        qty = qty.toMoney(),
        unitPrice = unitPrice.toMoney(),
        // null is UNKNOWN cost, not zero — the margin math depends on the difference.
        unitCost = unitCost?.toDoubleOrNull(),
        lineDiscount = lineDiscount.toMoney(),
        lineMarkup = lineMarkup.toMoney(),
        lineTax = lineTax.toMoney(),
        lineTotal = lineTotal.toMoney(),
        mode = mode,
        unitsPerLine = (unitsPerLine?.toDoubleOrNull() ?: 1.0).toInt().coerceAtLeast(1),
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
    )
}

@Serializable
data class SalePaymentDto(
    val id: String,
    @SerialName("sale_id") val saleId: String,
    val method: String = "cash",
    val amount: String? = null,
    val reference: String? = null,
    @SerialName("tender_currency") val tenderCurrency: String? = null,
    @SerialName("tender_amount") val tenderAmount: String? = null,
    val rate: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun SalePaymentDto.toSalePayment(businessId: String): SalePayment = SalePayment(
    id = id,
    saleId = saleId,
    businessId = businessId,
    method = method,
    amount = amount.toMoney(),
    reference = reference,
    tenderCurrency = tenderCurrency,
    tenderAmount = tenderAmount?.toDoubleOrNull(),
    rate = rate?.toDoubleOrNull(),
    createdAt = IsoTime.toMillis(createdAt),
)

// ─────────────────────────────────── refunds ───────────────────────────────────
// First-class rows now, not `type='return'` sales with negative totals. That change is
// what restores the refund→sale link the old shared schema dropped: `refunds.sale_id`
// and `refund_items.sale_line_id` say exactly what came back off which receipt, so a
// returned line no longer has to be reconstructed by matching names and amounts.

@Serializable
data class RefundDto(
    val id: String,
    @SerialName("sale_id") val saleId: String? = null,
    @SerialName("sale_receipt_no") val saleReceiptNo: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val reason: String? = null,
    @SerialName("refund_total") val refundTotal: String? = null,
    val status: String = "owed",
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

@Serializable
data class RefundItemDto(
    val id: String,
    @SerialName("refund_id") val refundId: String,
    @SerialName("sale_line_id") val saleLineId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val name: String? = null,
    val qty: String? = null,
    @SerialName("unit_price") val unitPrice: String? = null,
    @SerialName("line_total") val lineTotal: String? = null,
    val mode: String = "retail",
    @SerialName("units_per_line") val unitsPerLine: String? = null,
    val restock: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun RefundItemDto.toRefundLine(businessId: String): RefundLine = RefundLine(
    id = id,
    refundId = refundId,
    businessId = businessId,
    saleLineId = saleLineId,
    itemId = itemId,
    name = name.orEmpty(),
    qty = qty.toMoney(),
    unitPrice = unitPrice.toMoney(),
    lineTotal = lineTotal.toMoney(),
    mode = mode,
    unitsPerLine = (unitsPerLine?.toDoubleOrNull() ?: 1.0).toInt().coerceAtLeast(1),
    // `restock = false` means the goods came back damaged and did NOT go on the shelf.
    // Defaulting a missing value to true would put broken stock back into the count.
    restock = restock,
    createdAt = IsoTime.toMillis(createdAt),
)

@Serializable
data class RefundPaymentDto(
    val id: String,
    @SerialName("refund_id") val refundId: String,
    val method: String = "cash",
    val amount: String? = null,
    val reference: String? = null,
    @SerialName("tender_currency") val tenderCurrency: String? = null,
    @SerialName("tender_amount") val tenderAmount: String? = null,
    val rate: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun RefundPaymentDto.toRefundPayment(businessId: String): RefundPayment = RefundPayment(
    id = id,
    refundId = refundId,
    businessId = businessId,
    method = method,
    amount = amount.toMoney(),
    reference = reference,
    tenderCurrency = tenderCurrency,
    tenderAmount = tenderAmount?.toDoubleOrNull(),
    rate = rate?.toDoubleOrNull(),
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toMillis(createdAt),
)

@Serializable
data class RefundPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("sale_id") val saleId: String? = null,
    @SerialName("sale_receipt_no") val saleReceiptNo: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    val reason: String? = null,
    @SerialName("refund_total") val refundTotal: Double,
    val status: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("session_id") val sessionId: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

@Serializable
data class RefundItemPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("refund_id") val refundId: String,
    @SerialName("sale_line_id") val saleLineId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val name: String,
    val qty: Double,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("line_total") val lineTotal: Double,
    val mode: String,
    @SerialName("units_per_line") val unitsPerLine: Double,
    val restock: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

@Serializable
data class RefundPaymentPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("refund_id") val refundId: String,
    val method: String,
    val amount: Double,
    val reference: String? = null,
    @SerialName("tender_currency") val tenderCurrency: String? = null,
    @SerialName("tender_amount") val tenderAmount: Double? = null,
    val rate: Double? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * Merge a pulled refund onto the local row.
 *
 * `saleId` is NOT NULL locally but nullable on the wire (the web allows a refund with no
 * originating sale). A pulled row that carries none keeps whatever the local row had,
 * and a genuinely sale-less refund pulled onto a device that has never seen it is left
 * for the caller to reject — inventing a sale id here would attach the money to the
 * wrong receipt, which is worse than not importing it.
 */
fun RefundDto.toRefund(businessId: String, local: Refund?): Refund? {
    val sale = saleId?.ifBlank { null } ?: local?.saleId ?: return null
    val base = local ?: Refund(id = id, businessId = businessId, saleId = sale)
    return base.copy(
        id = id,
        businessId = businessId,
        saleId = sale,
        saleReceiptNo = saleReceiptNo ?: base.saleReceiptNo,
        customerId = customerId?.ifBlank { null } ?: base.customerId,
        customerName = customerName?.ifBlank { null } ?: base.customerName,
        reason = reason ?: base.reason,
        refundTotal = refundTotal?.toDoubleOrNull() ?: base.refundTotal,
        status = status,
        createdBy = createdBy?.ifBlank { null } ?: base.createdBy,
        createdByName = createdByName?.ifBlank { null } ?: base.createdByName,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

fun Refund.toPush(): RefundPushDto = RefundPushDto(
    id = id,
    businessId = businessId,
    saleId = saleId,
    saleReceiptNo = saleReceiptNo,
    customerId = customerId,
    customerName = customerName,
    reason = reason,
    refundTotal = refundTotal,
    status = status,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

fun RefundLine.toPush(): RefundItemPushDto = RefundItemPushDto(
    id = id,
    businessId = businessId,
    refundId = refundId,
    saleLineId = saleLineId,
    itemId = itemId,
    name = name,
    qty = qty,
    unitPrice = unitPrice,
    lineTotal = lineTotal,
    mode = mode,
    unitsPerLine = unitsPerLine.toDouble(),
    restock = restock,
    createdAt = IsoTime.toIso(createdAt),
    clientUpdatedAt = IsoTime.toIso(createdAt),
)

fun RefundPayment.toPush(): RefundPaymentPushDto = RefundPaymentPushDto(
    id = id,
    businessId = businessId,
    refundId = refundId,
    method = method,
    amount = amount,
    reference = reference,
    tenderCurrency = tenderCurrency,
    tenderAmount = tenderAmount,
    rate = rate,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    clientUpdatedAt = IsoTime.toIso(createdAt),
)

// ─────────────────────────────── stock_movements ───────────────────────────────
// The AUTHORITY for stock. `items.stock_qty` is a cache of these rows, which is why the
// catalogue can be pull-only and stock still move in both directions: a till never edits
// the product, it only ever appends a movement.

@Serializable
data class StockMovementDto(
    val id: String,
    @SerialName("item_id") val itemId: String? = null,
    val type: String = "adjust",
    val delta: String? = null,
    @SerialName("balance_after") val balanceAfter: String? = null,
    val note: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

/** Null when the row names no item — a movement that belongs to nothing cannot be
 *  applied to a stock figure, and guessing which product it meant would move real goods. */
fun StockMovementDto.toStockMovement(businessId: String): StockMovement? {
    val item = itemId?.ifBlank { null } ?: return null
    return StockMovement(
        id = id,
        businessId = businessId,
        itemId = item,
        type = type,
        delta = delta.toMoney(),
        balanceAfter = balanceAfter.toMoney(),
        note = note,
        createdBy = createdBy,
        createdByName = createdByName,
        createdAt = IsoTime.toMillis(createdAt),
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class StockMovementPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("item_id") val itemId: String,
    val type: String,
    val delta: Double,
    @SerialName("balance_after") val balanceAfter: Double,
    val note: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun StockMovement.toPush(): StockMovementPushDto = StockMovementPushDto(
    id = id,
    businessId = businessId,
    itemId = itemId,
    type = type,
    delta = delta,
    balanceAfter = balanceAfter,
    note = note,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(if (updatedAt > 0) updatedAt else createdAt),
)

// ──────────────────────────── cash sessions & movements ────────────────────────────

/**
 * A trading shift. **`variance` is GENERATED on the cloud** (`counted_cash − expected_cash`)
 * and so is absent from the push DTO — naming it fails the whole batch, exactly like
 * `sale_items.line_cost`. It is readable on the way down but not mapped: [CashSession]
 * derives its own, and holding two copies of one figure is how they come to disagree.
 */
@Serializable
data class CashSessionDto(
    val id: String,
    val status: String = "open",
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("opened_by") val openedBy: String? = null,
    @SerialName("opened_by_name") val openedByName: String? = null,
    @SerialName("opening_float") val openingFloat: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("closed_by") val closedBy: String? = null,
    @SerialName("closed_by_name") val closedByName: String? = null,
    @SerialName("counted_cash") val countedCash: String? = null,
    @SerialName("expected_cash") val expectedCash: String? = null,
    val note: String? = null,
    @SerialName("till_code") val tillCode: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: openedAt ?: IsoTime.EPOCH
}

fun CashSessionDto.toCashSession(businessId: String, local: CashSession?): CashSession {
    val base = local ?: CashSession(id = id, businessId = businessId)
    return base.copy(
        id = id,
        businessId = businessId,
        status = status,
        openedAt = IsoTime.toMillis(openedAt).takeIf { it > 0 } ?: base.openedAt,
        openedBy = openedBy ?: base.openedBy,
        openedByName = openedByName ?: base.openedByName,
        openingFloat = openingFloat.toMoney(),
        closedAt = IsoTime.toMillis(closedAt).takeIf { it > 0 },
        closedBy = closedBy ?: base.closedBy,
        closedByName = closedByName ?: base.closedByName,
        countedCash = countedCash?.toDoubleOrNull(),
        expectedCash = expectedCash?.toDoubleOrNull(),
        note = note ?: base.note,
        tillCode = tillCode ?: base.tillCode,
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class CashSessionPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val status: String,
    @SerialName("opened_at") val openedAt: String,
    @SerialName("opened_by") val openedBy: String? = null,
    @SerialName("opened_by_name") val openedByName: String? = null,
    @SerialName("opening_float") val openingFloat: Double,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("closed_by") val closedBy: String? = null,
    @SerialName("closed_by_name") val closedByName: String? = null,
    @SerialName("counted_cash") val countedCash: Double? = null,
    @SerialName("expected_cash") val expectedCash: Double? = null,
    val note: String? = null,
    @SerialName("till_code") val tillCode: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun CashSession.toPush(): CashSessionPushDto = CashSessionPushDto(
    id = id,
    businessId = businessId,
    status = status,
    openedAt = IsoTime.toIso(openedAt),
    openedBy = openedBy,
    openedByName = openedByName,
    openingFloat = openingFloat,
    closedAt = closedAt?.let { IsoTime.toIso(it) },
    closedBy = closedBy,
    closedByName = closedByName,
    countedCash = countedCash,
    expectedCash = expectedCash,
    note = note,
    tillCode = tillCode,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

/**
 * A movement of cash. **There is no `location` column** — the shared schema derives
 * TILL / SAFE / OUTSIDE from [type], whose vocabulary is CHECK-constrained to
 * `pay_in, pay_out, drop, petty, float_topup, safe_in, bank_deposit`. This app stores the
 * location explicitly, so the translation happens at the boundary via
 * [com.portionspot.pos.data.cashMovementTypeToWire], which is guaranteed to emit a legal
 * value — a CHECK violation fails the entire batch, not the offending row.
 */
@Serializable
data class CashMovementPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("session_id") val sessionId: String? = null,
    val type: String,
    val amount: Double,
    val reason: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun CashTxn.toMovementPush(sessionId: String?): CashMovementPushDto = CashMovementPushDto(
    id = id,
    businessId = businessId,
    sessionId = sessionId,
    type = cashMovementTypeToWire(type, location, amount),
    amount = amount,
    // The cloud calls it `reason`; locally the same text is a free `note`.
    reason = note ?: source,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ───────────────────────────── mobile_money_receipts ─────────────────────────────

/**
 * The one table whose push may legitimately IGNORE duplicates: `(business_id, txn_code)`
 * is a real unique index and a genuine idempotency key, because the same provider SMS
 * read twice is the same receipt. Everywhere else a skipped row is a lost fact.
 *
 * Named `toReceiptPush` rather than `toPush` on purpose — the outgoing `sync` package
 * still declares a `MobileMoneyReceipt.toPush`, and two extensions with the same
 * receiver and name resolving across packages is exactly the ambiguity that would send
 * the OLD `local_id` shape to a table that has no such column.
 */
@Serializable
data class MobileMoneyPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val provider: String,
    @SerialName("raw_body") val rawBody: String? = null,
    val sender: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_phone") val senderPhone: String? = null,
    val amount: Double,
    val currency: String,
    @SerialName("txn_code") val txnCode: String,
    @SerialName("received_at") val receivedAt: String,
    val status: String,
    @SerialName("matched_customer_id") val matchedCustomerId: String? = null,
    @SerialName("matched_customer_name") val matchedCustomerName: String? = null,
    val purpose: String? = null,
    @SerialName("applied_credit_txn_id") val appliedCreditTxnId: String? = null,
    @SerialName("applied_sale_id") val appliedSaleId: String? = null,
    val note: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun MobileMoneyReceipt.toReceiptPush(): MobileMoneyPushDto = MobileMoneyPushDto(
    id = id,
    businessId = businessId,
    provider = provider,
    rawBody = rawBody,
    sender = sender,
    senderName = senderName,
    senderPhone = senderPhone,
    amount = amount,
    currency = currency,
    txnCode = txnCode,
    receivedAt = IsoTime.toIso(receivedAt),
    status = status,
    matchedCustomerId = matchedCustomerId,
    matchedCustomerName = matchedCustomerName,
    purpose = purpose,
    appliedCreditTxnId = appliedCreditTxnId,
    appliedSaleId = appliedSaleId,
    note = note,
    createdBy = createdBy,
    createdByName = createdByName,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ────────────────────────────────── credit_txns ──────────────────────────────────
// `credit_txns`, not `credit_transactions`. customer_id is a plain uuid FK now — the old
// bigint-to-local_id translation table is gone along with the bridge it existed to serve.

@Serializable
data class CreditDto(
    val id: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("sale_id") val saleId: String? = null,
    val type: String,
    val amount: String? = null,
    val note: String? = null,
    val method: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun CreditDto.toCreditTxn(businessId: String, local: CreditTxn?): CreditTxn {
    val base = local ?: CreditTxn(
        id = id, businessId = businessId, customerId = customerId.orEmpty(), type = type
    )
    return base.copy(
        id = id,
        businessId = businessId,
        customerId = customerId ?: base.customerId,
        saleId = saleId ?: base.saleId,
        type = type,
        amount = amount.toMoney(),
        note = note,
        createdBy = createdBy ?: base.createdBy,
        createdByName = createdByName ?: base.createdByName,
        createdAt = IsoTime.toMillis(createdAt),
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class CreditPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("sale_id") val saleId: String? = null,
    val type: String,
    val amount: Double,
    val note: String? = null,
    val method: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun CreditTxn.toPush(): CreditPushDto = CreditPushDto(
    id = id,
    businessId = businessId,
    // Blank, not null, is how a walk-in's change row records "no customer" locally. The
    // cloud column is a real uuid FK, so a blank string would be rejected outright.
    customerId = customerId.ifBlank { null },
    saleId = saleId,
    type = type,
    amount = amount,
    note = note,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)
