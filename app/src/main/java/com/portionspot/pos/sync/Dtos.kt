package com.portionspot.pos.sync

import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
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
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Merge a pulled product onto the local [Item] (bridged by sku), preserving the
 *  Android-only fields the cloud doesn't carry (barcode, colour, unit, tax rate). */
fun ProductDto.toItem(businessId: String, local: Item?): Item {
    val boxSz = if (boxSize < 1) 1 else boxSize
    val base = local ?: Item(businessId = businessId, name = name, sku = sku)
    return base.copy(
        businessId = businessId,
        name = name,
        sku = sku,
        category = category,
        price = retailPrice.toMoney(),
        wholesalePrice = wholesalePrice.toMoney(),
        boxPrice = boxPrice.toMoney(),
        boxSize = boxSz,
        // cloud cost_price defaults to 0 = "unknown"; keep Android's null-means-unknown
        cost = costPrice?.toDoubleOrNull()?.takeIf { it > 0.0 },
        trackStock = true,
        stockQty = (stockBoxes * boxSz + stockUnits).toDouble(),
        reorderLevel = lowStockThreshold.toDouble(),
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
    @SerialName("vat_amount") val vatAmount: String? = null,
    @SerialName("grand_total") val grandTotal: String? = null,
    val payments: List<SalePaymentJson> = emptyList(),
    @SerialName("amount_paid") val amountPaid: String? = null,
    @SerialName("change_given") val changeGiven: String? = null,
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
    paymentMethod = payMethod ?: "cash",
    amountPaid = amountPaid.toMoney(),
    changeDue = changeGiven?.toDoubleOrNull(),
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
            lineTotal = li.unitPrice * li.qty - li.lineDiscount,
            mode = li.mode,
            unitsPerLine = if (li.unitsPerLine < 1) 1 else li.unitsPerLine,
        )
    }
