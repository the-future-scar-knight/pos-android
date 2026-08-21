package com.portionspot.pos.sync.wire

import com.portionspot.pos.sync.IsoTime
import com.portionspot.pos.sync.normalizeProductType
import com.portionspot.pos.sync.syncJson
import com.portionspot.pos.data.AuditEntry
import com.portionspot.pos.data.CashSession
import com.portionspot.pos.data.CashTxn
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.CashMovementFromWire
import com.portionspot.pos.data.cashMovementTypeFromWire
import com.portionspot.pos.data.cashMovementTypeToWire
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.Expense
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.ItemAttribute
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.PurchaseOrderLine
import com.portionspot.pos.data.attrNorm
import com.portionspot.pos.data.now
import com.portionspot.pos.data.purchaseOrderStatusFromWire
import com.portionspot.pos.data.purchaseOrderStatusToWire
import com.portionspot.pos.data.uuidOrNull
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.RefundLine
import com.portionspot.pos.data.RefundPayment
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import com.portionspot.pos.data.SalePayment
import com.portionspot.pos.data.StockMovement
import com.portionspot.pos.data.StaffRequest
import com.portionspot.pos.data.Supplier
import com.portionspot.pos.data.saleLineDiscountTotal
import com.portionspot.pos.data.saleMarginFromLines
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

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
    /** SERVER arrival order. Monotonic, immune to a device with a wrong clock, and so
     *  the only safe thing to page the pull cursor on. It is NOT when the edit happened. */
    @SerialName("updated_at") val updatedAt: String? = null,
    /** When the DEVICE that wrote the row made the edit — a client clock, the same one
     *  `stock_movements.created_at` is stamped with. See [baselineStamp]. */
    @SerialName("client_updated_at") val clientUpdatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * The instant the shop's stock figure was true, on the CLIENT clock.
 *
 * This has to be the same clock as `stock_movements.created_at`, because the ledger is
 * measured from it — a movement counts only if it is stamped strictly after. `updated_at`
 * is the SERVER's, rewritten by a trigger when the row arrives, so it runs seconds ahead
 * of the very movement that produced the figure: measure from it and that movement is
 * silently discarded, and the sale it recorded is lost from every till that pulls the row.
 *
 * Falls back to the server stamp for a row written before the column existed, or by a bulk
 * SQL import. Those compare as "true when the server received them", which is the old
 * behaviour and the best that can be said about them.
 */
fun ItemDto.baselineStamp(): Long =
    IsoTime.toMillis(clientUpdatedAt ?: updatedAt)

/**
 * Does [local] keep the stock baseline it already has, rather than adopting this row's?
 *
 * True when the incoming figure is the SAME one the device is already measuring from.
 * `client_updated_at` bumps for any edit at all — a rename, a price change — and moving
 * the baseline on those was silently destructive: every movement made before the edit
 * then fell before the baseline and stopped counting. A product with 24 on the shelf and
 * a sale of 3 against it went back to reporting 24 the moment someone corrected its name.
 *
 * An item with no baseline yet (`stockBaseAt == 0`) always adopts — that is the first
 * reconciliation against the shop, and there is nothing to preserve.
 */
private fun ItemDto.keepsBaseline(local: Item?): Boolean {
    if (local == null || local.stockBaseAt <= 0L) return false
    val incoming = stockQty.toMoney()
    val held = local.stockBaseQty
    return incoming > held - 0.0005 && incoming < held + 0.0005
}

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
        //
        // ★ ONLY MOVED WHEN THE FIGURE ITSELF MOVED. `client_updated_at` is the row's
        // edit clock, not a stock clock: it bumps for a rename or a reprice just the
        // same. Re-baselining on those meant every movement made before the edit fell
        // BEFORE the new baseline and was discarded — rename a product with 24 on the
        // shelf and a sale of 3 against it, and the till went back to reporting 24. The
        // sale silently un-happened. Holding the old stamp while the figure is unchanged
        // keeps the ledger measured from when the count was actually taken.
        stockBaseQty = if (keepsBaseline(local)) local!!.stockBaseQty else onHand,
        stockBaseAt = if (keepsBaseline(local)) local!!.stockBaseAt else baselineStamp(),
        reorderLevel = reorderLevel.toMoney(),
        unit = unit?.ifBlank { null } ?: base.unit,
        colorHex = colorHex ?: base.colorHex,
        isActive = isActive,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

/**
 * A product as it goes UP — the catalogue push.
 *
 * ★ `stock_qty` IS ABSENT, and that is the whole design. The ledger is the authority for
 * what is on the shelf and `items.stock_qty` is the shop's baseline figure; a till that
 * wrote its own computed on-hand into it would re-baseline every device to a number
 * derived from whatever movements THAT phone happened to have seen. Two tills doing it
 * while offline from each other would each discard the other's sales, and neither would
 * report an error. Stock travels as `stock_movements`, only ever as movements.
 *
 * Consequences worth stating rather than discovering:
 *  - A product CREATED on a till arrives in the cloud with `stock_qty` at its default of
 *    0. Its opening count is carried by the `restock` movement [PosRepository.saveItem]
 *    writes, which is stamped after the row and so counts against the 0 baseline.
 *  - `price_per_unit`, the image columns and the purchase-order `pending` fields have no
 *    cloud column at all, so a measured product's per-unit price does not round-trip.
 *    Nothing here can fix that; it needs a column on the shared schema.
 *  - `category_id` is left to the web. This app models categories as the free-text
 *    `category` the same table already carries.
 *
 * `updated_at` is never sent either — it is the server's cursor column, and a device with
 * a skewed clock stamping it makes every other device skip everything behind it.
 */
@Serializable
data class ItemPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val category: String? = null,
    val price: Double,
    @SerialName("wholesale_price") val wholesalePrice: Double,
    @SerialName("box_price") val boxPrice: Double,
    @SerialName("box_size") val boxSize: Int,
    val cost: Double? = null,
    @SerialName("tax_rate") val taxRate: Double,
    @SerialName("track_stock") val trackStock: Boolean,
    @SerialName("reorder_level") val reorderLevel: Double,
    val unit: String? = null,
    @SerialName("color_hex") val colorHex: String? = null,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("product_type") val productType: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun Item.toPush(): ItemPushDto = ItemPushDto(
    id = id,
    businessId = businessId,
    name = name,
    sku = sku?.ifBlank { null },
    barcode = barcode?.ifBlank { null },
    category = category?.ifBlank { null },
    price = price,
    wholesalePrice = wholesalePrice,
    boxPrice = boxPrice,
    boxSize = boxSize.coerceAtLeast(1),
    // NULL means UNKNOWN on this column, and the cloud defaults it that way. Sending 0.0
    // for "no cost recorded" would report the whole line as profit on the other client.
    cost = cost?.takeIf { it > 0.0 },
    taxRate = taxRate,
    trackStock = trackStock,
    reorderLevel = reorderLevel,
    unit = unit.ifBlank { null },
    colorHex = colorHex,
    isActive = isActive,
    productType = productType,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

/** `box_size` is `numeric(14,3)` on the wire but a whole number of units in the app. */
private fun ItemDto.boxSizeInt(): Int =
    (boxSize?.toDoubleOrNull() ?: 1.0).toInt().coerceAtLeast(1)

// ─────────────────────── item_attributes (two-way) ───────────────────────

/**
 * An item's key/value tag, as it comes DOWN.
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
        // The cloud already has this row — it is where it just came from. Stated rather
        // than left to the default so the read direction can never queue an upload.
        pendingSync = false,
    )
}

/**
 * A tag as it goes UP.
 *
 * ★ `key_norm` and `value_norm` ARE ABSENT, and naming either one would fail the ENTIRE
 * batch. They are `GENERATED ALWAYS` columns on the cloud — the database folds `key` and
 * `value` itself, precisely so it can never disagree with a client about what "the same
 * tag" means — and Postgres rejects any write that mentions a generated column. Same trap
 * as `line_cost` / `line_profit` on [SaleItemPushDto].
 *
 * `updated_at` is absent for the usual reason: it is the server's cursor column, and a
 * device with a skewed clock stamping it makes every other device skip everything behind
 * it. The edit clock travels as `client_updated_at`.
 *
 * `id` is the DERIVED id (see [com.portionspot.pos.data.attributeId]) and the upsert
 * conflict target. That is what turns two tills adding the same fitment into one row, and
 * what lets a re-add resurrect a tombstone instead of colliding with it on the live unique
 * index. It must be derived from the SHOP's business id, not this device's local one —
 * [com.portionspot.pos.sync.PosSyncEngine] re-keys before sending.
 */
@Serializable
data class ItemAttributePushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("item_id") val itemId: String,
    val key: String,
    val value: String,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun ItemAttribute.toPush(): ItemAttributePushDto = ItemAttributePushDto(
    id = id,
    businessId = businessId,
    itemId = itemId,
    // As typed, not folded: the normalised pair is the database's to compute, and the
    // owner's own casing is what the counter reads back ("Ford Ranger T6", not "ford
    // ranger t6").
    key = key,
    value = value,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

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

/**
 * `line_total` means something DIFFERENT on the wire than it does in this app, and the
 * difference is money.
 *
 * Locally the column is the GROSS goods value, `unitPrice × qty`, because the receipt
 * prints the discount and the markup as their own lines underneath it. On the wire it is
 * the NET — `unitPrice × qty − lineDiscount + lineMarkup` — which is the web POS's `ep()`,
 * the meaning every row already in the cloud carries.
 *
 * It matters because `sale_items.line_profit` is GENERATED as
 * `line_total − (unit_cost × qty × units_per_line)`. Push the gross figure and a cashier's
 * $20 off a line reads in the cloud as $20 more profit — not a rounding difference, a
 * wrong number in the owner's reports on every discounted sale.
 *
 * Reversible on the way back ([recoverLocalLineTotal]) because `line_discount` and
 * `line_markup` travel in the same row, so the conversion is exact in both directions and
 * the local convention survives a round trip untouched.
 */
private fun SaleLine.wireLineTotal(): Double = unitPrice * qty - lineDiscount + lineMarkup

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
    lineTotal = wireLineTotal(),
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
 *
 * `line_total` arrives NET and is converted back to this app's gross convention on the way
 * in — see [wireLineTotal] for why the two sides spell it differently.
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
        // Back to the gross figure this app's receipts are rendered from. Without it a
        // sale rung on the web prints its discount twice — once folded into the line and
        // again on the discount line below it.
        lineTotal = recoverLocalLineTotal(),
        mode = mode,
        unitsPerLine = (unitsPerLine?.toDoubleOrNull() ?: 1.0).toInt().coerceAtLeast(1),
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
    )
}

/** The wire's NET `line_total` read back as this app's GROSS one. Inverse of [wireLineTotal]. */
private fun SaleItemDto.recoverLocalLineTotal(): Double =
    lineTotal.toMoney() + lineDiscount.toMoney() - lineMarkup.toMoney()

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
    /**
     * How much of [refundTotal] may actually cross the counter as money.
     *
     * NULL is NOT zero and must never be read as zero — see [toRefund]. It means the
     * writer did not state a cap, which is true of every row written before the column
     * existed and of every refund the web raises (it does not settle debt first, so for
     * one of its refunds the goods value IS the payout).
     */
    @SerialName("payable_total") val payableTotal: String? = null,
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
    /**
     * ★ THE CAP TRAVELS, OR THE NEXT PHONE PAYS THE CUSTOMER TWICE.
     *
     * A refund of $100 of goods against a customer who owed $60 hands back $40 and writes
     * the other $60 off the debt. Without this column that refund went up as a plain $100
     * refund with nothing anywhere recording the cap, so the next device to pull it read
     * "owed: $100", showed $100 to hand back, and the shop gave away the $60 it had just
     * collected in goods. Two tills, one till roll, and nothing in the books saying which
     * one was right.
     */
    @SerialName("payable_total") val payableTotal: Double,
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
        // ★ THE WIRE NOW HAS A SAY, AND SILENCE STILL MEANS "THE WHOLE TOTAL".
        //
        // `payableTotal` is the cap on how much of a refund may cross the counter once the
        // unpaid part has been cancelled off the account (see [PosRepository.createRefund]).
        // It travels now — `refunds.payable_total` exists — so a cap raised on one phone is
        // no longer a private fact of that handset. The order below is the whole rule, and
        // each step is a way this has already gone wrong or would:
        //
        //  1. A STATED wire value wins. This is what closes the hole: an Android refund that
        //     correctly capped its payout at $40 of a $100 return used to travel as a plain
        //     $100 refund, and the next device to pull it would hand over the other $60.
        //     Reaching the local row first instead would re-open that hole from the other
        //     end, because a device that has never seen the refund has no local row at all.
        //  2. NULL is "not stated", NEVER zero. Read as zero, a refund raised in the WEB
        //     arrives with nothing payable and the phone quietly refuses to pay the customer
        //     anything at all. Rows written before the column existed carry NULL too.
        //  3. On a NULL, the LOCAL cap is preserved. This is the sibling failure: a refund
        //     this device capped at $40, re-pulled from a row the web later touched without
        //     understanding the column, would come back down un-capped at the full goods
        //     value and be paid out in full.
        //  4. With neither a wire value nor a local row, the total IS the payout. That is
        //     exactly right for a web-raised refund — the web does not settle debt first, so
        //     it never caps — and it is the only safe reading of a legacy row.
        payableTotal = payableTotal?.toDoubleOrNull()
            ?: local?.payableTotal
            ?: refundTotal?.toDoubleOrNull() ?: base.refundTotal,
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
    payableTotal = payableTotal,
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
 * A trading shift — which, on this app, is a trading DAY (see
 * [com.portionspot.pos.data.planDayRollover]).
 *
 * **`variance` is GENERATED on the cloud** (`counted_cash − expected_cash`) and so is
 * absent from the push DTO — naming it fails the whole batch, exactly like
 * `sale_items.line_cost`. It is readable on the way down but not mapped: [CashSession]
 * derives its own, and holding two copies of one figure is how they come to disagree.
 *
 * `moved_to_safe` / `float_target` are the closing half of a day and are the reason the
 * device's own [com.portionspot.pos.data.DayClose] record now HAS a cloud analogue: the
 * day-close writes both onto the day's session, so a shop reading the shared table sees
 * what was counted, what was left as float and what went to the safe, without `day_closes`
 * ever having to be a cloud table.
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
    @SerialName("moved_to_safe") val movedToSafe: String? = null,
    @SerialName("float_target") val floatTarget: String? = null,
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
        movedToSafe = movedToSafe.toMoney(),
        floatTarget = floatTarget.toMoney(),
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
    @SerialName("moved_to_safe") val movedToSafe: Double = 0.0,
    @SerialName("float_target") val floatTarget: Double = 0.0,
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
    movedToSafe = movedToSafe,
    floatTarget = floatTarget,
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
    // ★ ALWAYS POSITIVE. The shared schema puts the direction in the `type` and reads the
    // amount as a magnitude — its `effectOn` multiplies a `pay_out` by −1. Sending this
    // app's signed figure means a $5 payout arrives as −5, is negated again, and ADDS $5
    // to the expected drawer: a refund that makes the till look fuller than before it.
    amount = kotlin.math.abs(amount),
    // The cloud calls it `reason`; locally the same text is a free `note`.
    reason = note ?: source,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

/**
 * The same movement on the way DOWN — and the half that was missing.
 *
 * `cash_movements` was pushed and never pulled. So a phone that closed the trading day
 * (which writes a `variance` true-up and a till→safe transfer) and recorded an owner
 * drawing sent all of it to the database, and none of it ever reached the second phone.
 * Two tills standing over ONE physical drawer therefore disagreed about what was in it and
 * what was in the safe, and the day-close count on the phone that had not heard measured
 * the real drawer against a figure missing the other phone's cash handling — booking the
 * difference permanently as a variance, which by the owner's rule is a hit to profit.
 *
 * `client_updated_at` is deliberately NOT read. That column is the AUTHORING device's
 * clock, while the pull cursor runs on `updated_at`, the server's. Mixing the two is how a
 * phone with a fast clock writes a stamp that every other device then treats as "already
 * seen", and rows nobody ever pulled are skipped forever with nothing reporting it.
 *
 * There is no `ref_type` / `ref_id` on this table, so a pulled row can say nothing about
 * what it belonged to. That is not a gap to work around: it is the reason a sale's and a
 * refund's drawer movement are never pushed in the first place (see
 * [com.portionspot.pos.data.cashMovementCountedElsewhere]), which is what keeps this pull
 * from importing money the local mirror has already counted.
 */
@Serializable
data class CashMovementDto(
    val id: String,
    // Read but not applied: [CashTxn] has no session column, and never needed one — the
    // shift a movement belongs to is derived from its own day, which is what the push
    // stamps it with on the way out. Kept on the DTO so the wire shape is complete and so
    // the next reader does not have to go and find out whether the column exists.
    @SerialName("session_id") val sessionId: String? = null,
    val type: String = "pay_in",
    val amount: String? = null,
    val reason: String? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

/**
 * One wire movement as a local ledger row.
 *
 * ★ AN EXISTING LOCAL ROW IS NEVER RE-TYPED FROM THE WIRE, and that is the whole reason
 * this takes [local] at all. The trip out is LOSSY: this app's vocabulary is far wider
 * than the seven types the cloud allows, so a `drawing` goes up as `pay_out` and a
 * `variance` as `pay_in`/`pay_out`. Let the row come back down onto itself — which it will,
 * because the server stamps `updated_at` later than the client wrote it — and the phone
 * that recorded the owner taking $100 out would rewrite its own row as a bare payout. The
 * drawer total would still be right and `observeEquityCashSum` would silently drop the
 * drawing, so the four-part cash split would report money that was never profit as profit.
 * A cash movement is append-only and immutable by design anyway (a correction is a new
 * `adjust` row, never an edit), so the only thing that can legitimately change on one is
 * the tombstone — and a tombstone is the one field that is not lossy.
 *
 * `pendingSync` is carried across rather than forced to false for the same reason a pulled
 * row is never marked dirty: a row that has NOT been uploaded yet must not be told it has.
 * A brand-new row is pulled clean, which is what stops a device re-uploading what it just
 * downloaded and bouncing it round the shop forever.
 */
fun CashMovementDto.toCashTxn(businessId: String, local: CashTxn?): CashTxn {
    if (local != null) {
        return local.copy(
            deleted = deleted,
            // Bumped to the server's stamp so the same row cannot qualify again on a later
            // pass and rewrite a row that did not change.
            updatedAt = maxOf(local.updatedAt, IsoTime.toMillis(cursorStamp())),
            pendingSync = local.pendingSync,
        )
    }
    val wire = cashMovementTypeFromWire(type, amount.toMoney())
    return buildCashTxn(wire, businessId, rowId = id)
}

/**
 * The SECOND local row for a wire movement that moved money between two pockets, or null
 * when the movement touched only one.
 *
 * The web writes a till→safe drop as ONE row; this app keeps one row per location, so both
 * halves have to exist locally or half the money vanishes on this device. See
 * [cashMovementTypeFromWire] for why adopting the web's meaning is safe.
 *
 * ★ THE ID IS DERIVED AND DETERMINISTIC — `<wire id>:2`. It has to be stable, because the
 * pull re-reads a row every time the server stamps it and an invented id would insert a
 * fresh duplicate on every pass, walking the safe balance away from the till's. It also
 * must not collide with any real wire id, which a uuid with a suffix cannot.
 */
fun CashMovementDto.counterpartCashTxn(businessId: String): CashTxn? =
    cashMovementTypeFromWire(type, amount.toMoney()).counterpart
        ?.let { buildCashTxn(it, businessId, rowId = "$id:2") }

/**
 * One local ledger row, from a wire movement and an already-resolved [wire] meaning.
 *
 * Takes the row id rather than reading [CashMovementDto.id], because a movement between
 * two pockets produces TWO local rows and only one of them can wear the wire's own id.
 */
private fun CashMovementDto.buildCashTxn(
    wire: CashMovementFromWire,
    businessId: String,
    rowId: String,
): CashTxn {
    return CashTxn(
        id = rowId,
        localId = rowId,
        businessId = businessId,
        type = wire.type,
        amount = wire.amount,
        location = wire.location,
        // No cloud column for `source`. Named for what it honestly is, because a drawer
        // figure the owner cannot account for is a figure they stop trusting.
        source = "sync",
        // The cloud calls it `reason`; locally the same text is a free `note`.
        note = reason,
        // ★ LEFT NULL, NOT GUESSED. `refType` is what marks a row as a sale's or refund's
        // own drawer movement, and those are excluded from the ledger's cash-up sums by
        // name. Inventing one here would hide a real movement from the count; the wire has
        // no such column, so null is the only true answer.
        refType = null,
        refId = null,
        createdBy = createdBy,
        createdByName = createdByName,
        // The movement's OWN instant, never the moment of the sync. Stamped with the pull
        // time, a phone coming back online after midnight would file yesterday's petty cash
        // in today's cash-up and put the discrepancy in the wrong day.
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: IsoTime.toMillis(cursorStamp()),
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

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
        // How the money moved. The web has always written this column and this app did not
        // read it, so a repayment taken in the browser reached the phone with no tender
        // against it — and the phone had no way to tell an EcoCash settlement from notes.
        method = method ?: base.method,
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
    // The column has existed on the shared schema the whole time and only the web filled
    // it in. Null on a row that is pure bookkeeping — a `credit_owed` is a debt arising,
    // and no tender was involved in it.
    method = method,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ────────────────────────────────── suppliers ──────────────────────────────────
// The simplest of the five and the pattern the rest follow: uuid key, ten columns, no
// generated column, no vocabulary to translate.

@Serializable
data class SupplierDto(
    val id: String,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * Merge a pulled supplier onto the local row.
 *
 * `createdAt` and `localId` have NO cloud column, so they come from [local] untouched —
 * a pull must never blank what the device knows and the shared schema has no opinion
 * about. On a first pull `createdAt` takes the entity's own default (now), which is the
 * honest answer to "when did this device first hear of this supplier".
 */
fun SupplierDto.toSupplier(businessId: String, local: Supplier?): Supplier {
    val base = local ?: Supplier(id = id, businessId = businessId, name = name.orEmpty())
    return base.copy(
        id = id,
        businessId = businessId,
        name = name?.ifBlank { null } ?: base.name,
        phone = phone,
        email = email,
        address = address,
        notes = notes,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class SupplierPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun Supplier.toPush(): SupplierPushDto = SupplierPushDto(
    id = id,
    businessId = businessId,
    // NOT NULL on the cloud. A supplier with no name is a data-entry accident, not a
    // reason to fail the batch every other supplier is riding in.
    name = name.ifBlank { "Supplier" },
    phone = phone?.ifBlank { null },
    email = email?.ifBlank { null },
    address = address?.ifBlank { null },
    notes = notes?.ifBlank { null },
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ─────────────────────────────────── expenses ───────────────────────────────────
// SIXTEEN columns on the cloud against THIRTY-ONE fields locally. The funding split
// (cash / payable / capital) and the whole recurrence engine still have no home on the
// shared schema, which is why the pull below is a MERGE onto the local row and not a
// replacement: applying a wire row wholesale would wipe both.
//
// * THE APPROVAL LIFECYCLE TRAVELS AS OF 19 AUGUST and did not before. It had nowhere to
// go, and the cost of that landed on two real phones: a cashier posted an expense, it
// arrived on the owner's phone already APPROVED - synthesised, because the pull had no
// status to read - dropped his net profit, and stayed `pending` on hers. Two phones
// reporting different profit for one shop, with nothing on either saying why.
//
// The funding split stays local ON PURPOSE, and it is not the same kind of fact: a cash
// portion means "out of THIS drawer", and there is no `cash_txns` row on another device
// for money this one spent. That money reaches the other phones as `cash_movements`,
// which is its own table and already pulls. See
// `supabase/2026-08-19-expense-approval.sql`.

@Serializable
data class ExpenseDto(
    val id: String,
    val category: String? = null,
    val amount: String? = null,
    /** A real Postgres DATE, so it arrives as "yyyy-MM-dd" — which is exactly how this
     *  app already stores it. It is NOT a timestamp and must never go through [IsoTime];
     *  parsing it as one yields 0 and buckets the cost on 1 January 1970. */
    val date: String? = null,
    val description: String? = null,
    // -- the approval lifecycle, added 19 Aug --
    val status: String? = null,
    @SerialName("submitted_by") val submittedBy: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("posted_at") val postedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * Merge a pulled expense onto the local row.
 *
 * A ROW FROM A CLIENT WITH NO APPROVAL STEP still arrives with no status - the web does
 * not write one, and the column defaults to `approved` precisely so that it reads as what
 * it is. Left at the ENTITY's default it would land as `pending`, which withdraws a cost
 * somebody has already paid from the books and files it in an approval queue for a
 * decision nobody remembers making. So when the wire says nothing, this still synthesises
 * approved-and-posted, stamped with the row's own clock. That was the whole of the old
 * behaviour and it was correct - for the only writer that existed at the time.
 *
 * WHEN THE WIRE DOES CARRY A STATUS, THE WHOLE LIFECYCLE IS TAKEN FROM IT, nulls and all.
 * Falling back to the local value per field would leave a stale `approvedAt` sitting on a
 * row the other phone has since put back to pending - an expense approved by nobody, at a
 * time, which is worse than either state on its own. The caller only reaches here when the
 * wire row is the newer one, so the wire is the better-informed writer by construction.
 *
 * The funding split is deliberately left alone. There is no `cash_txns` row on this device
 * for money another device spent, so claiming it came out of this drawer would make the
 * shop's cash-on-hand disagree with the money in it.
 */
fun ExpenseDto.toExpense(businessId: String, local: Expense?): Expense {
    val stamp = IsoTime.toMillis(updatedAt)
    val wireStatus = status?.trim()?.ifBlank { null }
    val approvedAtMs = IsoTime.toMillis(approvedAt).takeIf { it > 0 }
    val postedAtMs = IsoTime.toMillis(postedAt).takeIf { it > 0 }
    val base = local ?: Expense(
        id = id,
        businessId = businessId,
        date = date?.ifBlank { null }.orEmpty(),
        status = wireStatus ?: "approved",
        // Synthesised ONLY for the writer that has no lifecycle. A row that did carry one
        // gets exactly the instants it carried, including none at all.
        approvedAt = if (wireStatus == null) stamp.takeIf { it > 0 } else approvedAtMs,
        postedAt = if (wireStatus == null) stamp.takeIf { it > 0 } else postedAtMs,
        createdAt = stamp.takeIf { it > 0 } ?: now(),
    )
    val lifecycle =
        if (wireStatus == null) base
        else base.copy(
            status = wireStatus,
            submittedBy = submittedBy,
            submittedByName = submittedByName,
            approvedBy = approvedBy,
            approvedByName = approvedByName,
            approvedAt = approvedAtMs,
            postedAt = postedAtMs,
        )
    return lifecycle.copy(
        id = id,
        businessId = businessId,
        category = category?.ifBlank { null } ?: base.category,
        amount = amount.toMoney(),
        date = date?.ifBlank { null } ?: base.date,
        description = description,
        updatedAt = stamp,
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class ExpensePushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val category: String? = null,
    val amount: Double,
    /** Straight through: both sides speak "yyyy-MM-dd". */
    val date: String? = null,
    val description: String? = null,
    val status: String = "pending",
    @SerialName("submitted_by") val submittedBy: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("posted_at") val postedAt: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * THE STATUS IS ALWAYS SENT, never left to the column default. That default is `approved`
 * - it exists for the web, which has no approval step - so a phone that omitted the field
 * would have every pending expense it raised arrive at the owner already approved, which
 * is the exact failure the column was added to end.
 */
fun Expense.toPush(): ExpensePushDto = ExpensePushDto(
    id = id,
    businessId = businessId,
    category = category.ifBlank { null },
    amount = amount,
    date = date.ifBlank { null },
    description = description?.ifBlank { null },
    status = status.ifBlank { "pending" },
    submittedBy = submittedBy?.ifBlank { null },
    submittedByName = submittedByName?.ifBlank { null },
    approvedBy = approvedBy?.ifBlank { null },
    approvedByName = approvedByName?.ifBlank { null },
    approvedAt = approvedAt?.let { IsoTime.toIso(it) },
    postedAt = postedAt?.let { IsoTime.toIso(it) },
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ───────────────────────────────── audit_entries ─────────────────────────────────
// The cloud table is `audit_entries`; the LOCAL Room table is `audit_log`. Append-only in
// spirit on both sides.
//
// ★ `meta` is JSONB on the cloud and a JSON *string* on this side. Declaring the DTO
// field `String?` compiles perfectly and then fails at RUNTIME the first time a real row
// comes down, because kotlinx cannot decode a JSON object into a String — and going up it
// would double-encode, storing the text of the JSON rather than the JSON. It travels as a
// [JsonElement] and is rendered back to the local string form with `toString()`.

/**
 * Local meta text → a jsonb value.
 *
 * ★ On THIS side the column is free text, not JSON: everything the app writes into it is
 * a human note ("$12.50", "qty 2 → 3; price 8.00 → 7.50", "No line changes"). Only text
 * SHAPED like a JSON object or array is sent as structured jsonb; the rest travels as a
 * JSON string, so it comes home byte for byte.
 *
 * Handing every value to a lenient parser instead would quietly rewrite a note of "12.50"
 * as the NUMBER 12.5 and hand it back as "12.5" — a shop's audit trail editing itself.
 */
private fun String?.toWireMeta(): JsonElement? {
    val raw = this?.trim()?.ifBlank { null } ?: return null
    val structured = (raw.startsWith("{") && raw.endsWith("}")) ||
        (raw.startsWith("[") && raw.endsWith("]"))
    if (!structured) return JsonPrimitive(raw)
    // Shaped like JSON but not parseable — a note that merely begins with a brace. It
    // still travels, as text: losing an audit entry over its punctuation is the worse bug.
    return runCatching { syncJson.parseToJsonElement(raw) }.getOrNull() ?: JsonPrimitive(raw)
}

/**
 * A jsonb value → the local meta text. SQL NULL and JSON `null` both read as absent.
 *
 * ★ A jsonb STRING unwraps to its CONTENT. `toString()` would hand back the quoted form,
 * so a plain note would come home as `"No line changes"`, quotes and all, and grow another
 * pair on every round trip after that.
 */
private fun JsonElement?.toLocalMeta(): String? {
    val el = this?.takeIf { it !is JsonNull } ?: return null
    if (el is JsonPrimitive && el.isString) return el.content
    return el.toString()
}

@Serializable
data class AuditEntryDto(
    val id: String,
    val action: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    /** `text` on the cloud, NOT `uuid` — an audit entry can point at anything, including
     *  a row that no longer exists, so it is never validated as a key. */
    @SerialName("entity_id") val entityId: String? = null,
    val summary: String? = null,
    val meta: JsonElement? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

/**
 * Wire row → local trail entry. No merge parameter: an entry this device already holds is
 * never rewritten (see PosSyncEngine.pullAudit), so there is nothing to fold onto.
 */
fun AuditEntryDto.toAuditEntry(businessId: String): AuditEntry {
    val created = IsoTime.toMillis(createdAt).takeIf { it > 0 }
        ?: IsoTime.toMillis(cursorStamp())
    return AuditEntry(
        id = id,
        businessId = businessId,
        // NOT NULL on the cloud, but a row written by another client could still be blank.
        action = action?.ifBlank { null } ?: "unknown",
        entityType = entityType,
        entityId = entityId,
        summary = summary.orEmpty(),
        meta = meta.toLocalMeta(),
        createdBy = createdBy,
        createdByName = createdByName,
        createdAt = created,
        updatedAt = IsoTime.toMillis(cursorStamp()).takeIf { it > 0 } ?: created,
        pendingSync = false,
    )
}

/**
 * ★ `deleted` IS ABSENT, and that is deliberate. [AuditEntry] has no such field, so this
 * side has no opinion about it — and PostgREST only writes the columns a payload names,
 * so leaving it out preserves whatever the cloud row already says instead of resurrecting
 * an entry somebody tombstoned there.
 */
@Serializable
data class AuditEntryPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val action: String,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    val summary: String? = null,
    val meta: JsonElement? = null,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun AuditEntry.toPush(): AuditEntryPushDto = AuditEntryPushDto(
    id = id,
    businessId = businessId,
    action = action.ifBlank { "unknown" },
    entityType = entityType,
    entityId = entityId,
    summary = summary.ifBlank { null },
    meta = meta.toWireMeta(),
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = IsoTime.toIso(createdAt),
    clientUpdatedAt = IsoTime.toIso(if (updatedAt > 0) updatedAt else createdAt),
)

// ─────────────────────────────── purchase_orders ───────────────────────────────
// The riskiest of the five. Two things here fail the WHOLE batch rather than one row:
//
//  1. `status` is CHECK-constrained to draft/sent/received/cancelled, and this app writes
//     `placed` and `partial` — see [purchaseOrderStatusToWire].
//  2. `supplier_id` is a `uuid` column and the local field is a free String — see
//     [uuidOrNull].
//
// The local money fields (`cashPaid`, `capitalPaid`, `payableRemainder`), the arrival
// prompt (`eta`, `arrivalPromptedAt`) and `localId` have no cloud column at all and are
// preserved from the local row on every pull.

@Serializable
data class PurchaseOrderDto(
    val id: String,
    val ref: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String = "draft",
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun PurchaseOrderDto.toPurchaseOrder(businessId: String, local: PurchaseOrder?): PurchaseOrder {
    val base = local ?: PurchaseOrder(
        id = id,
        businessId = businessId,
        ref = ref?.ifBlank { null } ?: "PO-${id.take(8)}",
    )
    return base.copy(
        id = id,
        businessId = businessId,
        ref = ref?.ifBlank { null } ?: base.ref,
        supplierId = supplierId?.ifBlank { null } ?: base.supplierId,
        supplierName = supplierName?.ifBlank { null } ?: base.supplierName,
        // ★ Told what it is landing on, because the trip up was lossy: a `partial` order
        // comes back as `sent` and must STAY partial, or a half-received PO reverts to
        // "placed" and the same goods can be received twice.
        status = purchaseOrderStatusFromWire(status, local?.status),
        notes = notes ?: base.notes,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        sentAt = IsoTime.toMillis(sentAt).takeIf { it > 0 } ?: base.sentAt,
        receivedAt = IsoTime.toMillis(receivedAt).takeIf { it > 0 } ?: base.receivedAt,
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class PurchaseOrderPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val ref: String? = null,
    @SerialName("supplier_id") val supplierId: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    val status: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

fun PurchaseOrder.toPush(): PurchaseOrderPushDto = PurchaseOrderPushDto(
    id = id,
    businessId = businessId,
    ref = ref.ifBlank { null },
    // Nulled rather than sent as-is when it is not a uuid: the column is `uuid`, and
    // Postgres rejecting one value takes every other PO in the batch with it.
    supplierId = uuidOrNull(supplierId),
    supplierName = supplierName.ifBlank { null },
    status = purchaseOrderStatusToWire(status),
    notes = notes?.ifBlank { null },
    createdAt = IsoTime.toIso(createdAt),
    sentAt = sentAt?.let { IsoTime.toIso(it) },
    receivedAt = receivedAt?.let { IsoTime.toIso(it) },
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

// ──────────────────────────── purchase_order_items ────────────────────────────
// The PO's lines. Two shapes to keep in mind:
//
//  • the cloud REQUIRES `business_id` and [PurchaseOrderLine] has no such field, so it is
//    injected on push exactly like [SaleItemPushDto]'s;
//  • the line has no clock of its own on this side — it only ever changes as part of a PO
//    write — so `client_updated_at` comes from the parent order.
//
// `sellPrice`, `stockOnArrival`, `productType` and `localId` have no cloud column and are
// preserved from the local row.

@Serializable
data class PurchaseOrderItemDto(
    val id: String,
    @SerialName("po_id") val poId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String? = null,
    val sku: String? = null,
    val qty: String? = null,
    @SerialName("unit_cost") val unitCost: String? = null,
    /** Nullable `numeric`: NULL means NOT YET RECEIVED, which is a different fact from
     *  "received none of it". Parsed with `toDoubleOrNull`, never [toMoney], so the
     *  difference survives the trip. */
    @SerialName("received_qty") val receivedQty: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: IsoTime.EPOCH
}

fun PurchaseOrderItemDto.toPurchaseOrderLine(local: PurchaseOrderLine?): PurchaseOrderLine {
    val base = local ?: PurchaseOrderLine(id = id, poId = poId)
    return base.copy(
        id = id,
        poId = poId,
        itemId = itemId?.ifBlank { null } ?: base.itemId,
        name = name?.ifBlank { null } ?: base.name,
        sku = sku?.ifBlank { null } ?: base.sku,
        qty = qty?.toDoubleOrNull() ?: base.qty,
        unitCost = unitCost.toMoney(),
        receivedQty = receivedQty?.toDoubleOrNull(),
        pendingSync = false,
    )
}

/**
 * ★ `deleted` is ABSENT for the same reason as [AuditEntryPushDto]'s: [PurchaseOrderLine]
 * has no such field, and a payload that named the column would overwrite a tombstone set
 * on the other side with a hard-coded false.
 */
@Serializable
data class PurchaseOrderItemPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("po_id") val poId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String? = null,
    val sku: String? = null,
    val qty: Double,
    @SerialName("unit_cost") val unitCost: Double,
    @SerialName("received_qty") val receivedQty: Double? = null,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * [stamp] is the PARENT order's `updatedAt` — the line has no clock of its own.
 *
 * `businessId` goes out EMPTY and is filled in by the engine with `.copy(businessId =
 * cloudBid)`, the same as every other push row: what belongs on the wire is the SHOP's id
 * learned from the cloud, never the one this device invented on first run.
 */
fun PurchaseOrderLine.toPush(stamp: Long): PurchaseOrderItemPushDto = PurchaseOrderItemPushDto(
    id = id,
    businessId = "",
    poId = poId,
    // `uuid` on the cloud, free String here. Nulled rather than risking the batch.
    itemId = uuidOrNull(itemId),
    name = name.ifBlank { null },
    sku = sku?.ifBlank { null },
    qty = qty,
    unitCost = unitCost,
    // Stays NULL while nothing has been received. Sending 0.0 would read as "delivered,
    // nothing in the box" and close the arrival prompt on an order still in transit.
    receivedQty = receivedQty,
    clientUpdatedAt = IsoTime.toIso(stamp),
)


// ─────────────────────────────── staff_requests ───────────────────────────────
/**
 * A cashier asking the owner for permission, and the owner's answer — on the wire at last.
 *
 * ══ WHY THIS WAS MISSING ══
 * The whole raise → decide → apply lifecycle has existed on the device for months, and none
 * of it ever left the phone. An older DTO pair was DELETED from `sync/Dtos.kt` with the note
 * that "`staff_requests` has no cloud table at all, on this database or any other" — true
 * when it was written, and no longer: the table is there, twenty columns of it, with a
 * permissive tenant read/write policy. Until now a cashier tapping "ask the owner" raised a
 * request that only the cashier's own phone could see, and an owner who approved one approved
 * it into a screen the cashier would never look at. It was a doorbell wired to its own hallway.
 *
 * ══ WHAT DOES NOT TRAVEL ══
 * [StaffRequest.applied] is on the cloud but is DEVICE-LOCAL in effect: it records that the
 * approval was consumed — the discount actually taken, the void actually performed — and the
 * device that consumes it is the cashier's. It is pulled (an admin device may legitimately set
 * it) and pushed, but a pull never clears a local `true`: the goods are already out of the
 * door, and a wire row saying otherwise cannot put them back.
 */
@Serializable
data class StaffRequestDto(
    val id: String,
    val type: String = "",
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("target_name") val targetName: String? = null,
    val amount: String? = null,
    val note: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    val status: String = "pending",
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decided_by_name") val decidedByName: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    val applied: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("local_id") val localId: String? = null,
    val deleted: Boolean = false,
) {
    fun cursorStamp(): String = updatedAt ?: createdAt ?: IsoTime.EPOCH
}

fun StaffRequestDto.toStaffRequest(businessId: String, local: StaffRequest?): StaffRequest {
    val base = local ?: StaffRequest(id = id, businessId = businessId, type = type)
    return base.copy(
        id = id,
        // The cloud's own key for the row. Kept from [local] when the wire has none, so a
        // row this device raised keeps pushing under the id it first went up with.
        localId = localId?.ifBlank { null } ?: base.localId,
        businessId = businessId,
        type = type.ifBlank { base.type },
        targetType = targetType,
        targetId = targetId,
        targetName = targetName,
        amount = amount?.toDoubleOrNull(),
        note = note,
        requestedBy = requestedBy,
        requestedByName = requestedByName,
        status = status.ifBlank { base.status },
        decidedBy = decidedBy,
        decidedByName = decidedByName,
        decidedAt = IsoTime.toMillis(decidedAt).takeIf { it > 0 },
        // Never un-applied by the wire — see the header. An approval already spent on a
        // discount or a void cannot be returned to the shelf by a row arriving late.
        applied = applied || base.applied,
        createdAt = IsoTime.toMillis(createdAt).takeIf { it > 0 } ?: base.createdAt,
        updatedAt = IsoTime.toMillis(cursorStamp()),
        deleted = deleted,
        pendingSync = false,
    )
}

@Serializable
data class StaffRequestPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val type: String,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("target_name") val targetName: String? = null,
    val amount: Double? = null,
    val note: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    val status: String,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decided_by_name") val decidedByName: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    val applied: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("local_id") val localId: String? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * `id` is a `uuid` on the cloud with NO default, so a row whose local id is not one cannot
 * go up at all — and a rejected batch fails WHOLE, taking every other request with it. This
 * app mints uuids ([newId]), so the only rows this can catch are foreign or hand-made ones,
 * which the engine drops rather than sending.
 */
fun StaffRequest.toPush(): StaffRequestPushDto = StaffRequestPushDto(
    id = id,
    businessId = businessId,
    // NOT NULL on the cloud, free-form here. A request with no type is still a request
    // somebody is waiting on an answer to.
    type = type.ifBlank { "other" },
    targetType = targetType?.ifBlank { null },
    targetId = targetId?.ifBlank { null },
    targetName = targetName?.ifBlank { null },
    amount = amount,
    note = note?.ifBlank { null },
    requestedBy = requestedBy?.ifBlank { null },
    requestedByName = requestedByName?.ifBlank { null },
    status = status.ifBlank { "pending" },
    decidedBy = decidedBy?.ifBlank { null },
    decidedByName = decidedByName?.ifBlank { null },
    decidedAt = decidedAt?.let { IsoTime.toIso(it) },
    applied = applied,
    createdAt = IsoTime.toIso(createdAt),
    localId = localId.ifBlank { null },
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)
