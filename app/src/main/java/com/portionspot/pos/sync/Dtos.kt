package com.portionspot.pos.sync

import com.portionspot.pos.data.Business
import com.portionspot.pos.data.CreditTxn
import com.portionspot.pos.data.Customer
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.SaleLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared JSON. Tolerant on read; sends every column on write so upserts overwrite. */
internal val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

/**
 * Fixed-format UTC ISO time. Room stores epoch millis; the cloud stores these
 * strings as TEXT. Because the format is fixed-width, lexicographic order ==
 * chronological order, which keeps the `gt.<cursor>` pull cursor dead simple
 * and dodges java.time (not available pre-API 26 without desugaring).
 */
internal object IsoTime {
    private const val PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    const val EPOCH = "1970-01-01T00:00:00.000Z"

    private fun formatter() = SimpleDateFormat(PATTERN, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun toIso(millis: Long): String = formatter().format(Date(millis))

    fun toMillis(iso: String): Long =
        runCatching { formatter().parse(iso)?.time }.getOrNull()
            ?: runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(iso)?.time
            }.getOrNull()
            ?: 0L
}

// ── businesses ──────────────────────────────────────────────────────────────
// NB: device-local fields (logo uri, printer config) are deliberately NOT part
// of this DTO — they stay on the phone and are never synced.
@Serializable
data class BusinessDto(
    val id: String,
    val name: String,
    val currency: String,
    val tagline: String? = null,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    @SerialName("receipt_header") val receiptHeader: String? = null,
    @SerialName("receipt_footer") val receiptFooter: String? = null,
    // ── VAT / ZIMRA ──
    @SerialName("vat_enabled") val vatEnabled: Boolean = false,
    @SerialName("vat_number") val vatNumber: String? = null,
    @SerialName("vat_percent") val vatPercent: Double = 15.0,
    // ── payment method toggles ──
    @SerialName("cash_enabled") val cashEnabled: Boolean = true,
    @SerialName("card_enabled") val cardEnabled: Boolean = false,
    @SerialName("bank_enabled") val bankEnabled: Boolean = false,
    @SerialName("paynow_enabled") val paynowEnabled: Boolean = false,
    @SerialName("ecocash_enabled") val ecocashEnabled: Boolean = false,
    @SerialName("innbucks_enabled") val innbucksEnabled: Boolean = false,
    @SerialName("onemoney_enabled") val onemoneyEnabled: Boolean = false,
    @SerialName("omari_enabled") val omariEnabled: Boolean = false,
    // ── bank transfer ──
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("bank_branch") val bankBranch: String? = null,
    @SerialName("bank_account_name") val bankAccountName: String? = null,
    @SerialName("bank_account_number") val bankAccountNumber: String? = null,
    // ── mobile money ──
    @SerialName("ecocash_account_name") val ecocashAccountName: String? = null,
    @SerialName("ecocash_phone") val ecocashPhone: String? = null,
    @SerialName("ecocash_merchant_code") val ecocashMerchantCode: String? = null,
    @SerialName("innbucks_account_name") val innbucksAccountName: String? = null,
    @SerialName("innbucks_phone") val innbucksPhone: String? = null,
    @SerialName("onemoney_account_name") val onemoneyAccountName: String? = null,
    @SerialName("onemoney_phone") val onemoneyPhone: String? = null,
    @SerialName("omari_account_name") val omariAccountName: String? = null,
    @SerialName("omari_phone") val omariPhone: String? = null,
    // ── Paynow: ID only; the secret KEY is never synced ──
    @SerialName("paynow_integration_id") val paynowIntegrationId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun Business.toDto() = BusinessDto(
    id = id, name = name, currency = currency, tagline = tagline, address = address,
    phone = phone, email = email, website = website, receiptHeader = receiptHeader,
    receiptFooter = receiptFooter,
    vatEnabled = vatEnabled, vatNumber = vatNumber, vatPercent = vatPercent,
    cashEnabled = cashEnabled, cardEnabled = cardEnabled, bankEnabled = bankEnabled,
    paynowEnabled = paynowEnabled, ecocashEnabled = ecocashEnabled, innbucksEnabled = innbucksEnabled,
    onemoneyEnabled = onemoneyEnabled, omariEnabled = omariEnabled,
    bankName = bankName, bankBranch = bankBranch, bankAccountName = bankAccountName,
    bankAccountNumber = bankAccountNumber,
    ecocashAccountName = ecocashAccountName, ecocashPhone = ecocashPhone,
    ecocashMerchantCode = ecocashMerchantCode,
    innbucksAccountName = innbucksAccountName, innbucksPhone = innbucksPhone,
    onemoneyAccountName = onemoneyAccountName, onemoneyPhone = onemoneyPhone,
    omariAccountName = omariAccountName, omariPhone = omariPhone,
    paynowIntegrationId = paynowIntegrationId,
    updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

/** Merge a pulled row into the local row, KEEPING device-local fields. */
fun BusinessDto.toEntity(local: Business?): Business =
    (local ?: Business(id = id)).copy(
        id = id, name = name, currency = currency, tagline = tagline, address = address,
        phone = phone, email = email, website = website, receiptHeader = receiptHeader,
        receiptFooter = receiptFooter,
        vatEnabled = vatEnabled, vatNumber = vatNumber, vatPercent = vatPercent,
        cashEnabled = cashEnabled, cardEnabled = cardEnabled, bankEnabled = bankEnabled,
        paynowEnabled = paynowEnabled, ecocashEnabled = ecocashEnabled, innbucksEnabled = innbucksEnabled,
        onemoneyEnabled = onemoneyEnabled, omariEnabled = omariEnabled,
        bankName = bankName, bankBranch = bankBranch, bankAccountName = bankAccountName,
        bankAccountNumber = bankAccountNumber,
        ecocashAccountName = ecocashAccountName, ecocashPhone = ecocashPhone,
        ecocashMerchantCode = ecocashMerchantCode,
        innbucksAccountName = innbucksAccountName, innbucksPhone = innbucksPhone,
        onemoneyAccountName = onemoneyAccountName, onemoneyPhone = onemoneyPhone,
        omariAccountName = omariAccountName, omariPhone = omariPhone,
        paynowIntegrationId = paynowIntegrationId,
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted, pendingSync = false
        // logoUri, btPrinterMac, btPrinterName, paperWidth, receiptLargeText,
        // paynowIntegrationKey (secret): all preserved from `local`.
    )

// ── items ───────────────────────────────────────────────────────────────────
@Serializable
data class ItemDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("category_id") val categoryId: String? = null,
    val name: String,
    val barcode: String? = null,
    val sku: String? = null,
    val category: String? = null,
    val price: Double = 0.0,
    @SerialName("wholesale_price") val wholesalePrice: Double = 0.0,
    @SerialName("box_price") val boxPrice: Double = 0.0,
    @SerialName("box_size") val boxSize: Int = 1,
    val cost: Double? = null,
    @SerialName("tax_rate") val taxRate: Double = 0.0,
    @SerialName("track_stock") val trackStock: Boolean = false,
    @SerialName("stock_qty") val stockQty: Double = 0.0,
    val unit: String = "pc",
    val color: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun Item.toDto() = ItemDto(
    id = id, businessId = businessId, categoryId = categoryId, name = name, barcode = barcode,
    sku = sku, category = category, price = price, wholesalePrice = wholesalePrice,
    boxPrice = boxPrice, boxSize = boxSize, cost = cost, taxRate = taxRate, trackStock = trackStock,
    stockQty = stockQty, unit = unit, color = colorHex, isActive = isActive,
    updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

fun ItemDto.toEntity() = Item(
    id = id, businessId = businessId, categoryId = categoryId, name = name, barcode = barcode,
    sku = sku, category = category, price = price, wholesalePrice = wholesalePrice,
    boxPrice = boxPrice, boxSize = boxSize, cost = cost, taxRate = taxRate, trackStock = trackStock,
    stockQty = stockQty, unit = unit, colorHex = color, isActive = isActive,
    updatedAt = IsoTime.toMillis(updatedAt), deleted = deleted, pendingSync = false
)

// ── sales ────────────────────────────────────────────────────────────────────
@Serializable
data class SaleDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("receipt_no") val receiptNo: String? = null,
    val status: String = "completed",
    val subtotal: Double = 0.0,
    @SerialName("discount_total") val discountTotal: Double = 0.0,
    @SerialName("tax_total") val taxTotal: Double = 0.0,
    val total: Double = 0.0,
    @SerialName("payment_method") val paymentMethod: String = "cash",
    val tendered: Double? = null,
    @SerialName("change_due") val changeDue: Double? = null,
    @SerialName("payment_ref") val paymentRef: String? = null,
    @SerialName("payment_status") val paymentStatus: String = "paid",
    val note: String? = null,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("sold_at") val soldAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun SaleEntity.toDto() = SaleDto(
    id = id, businessId = businessId, receiptNo = receiptNo, status = status, subtotal = subtotal,
    discountTotal = discountTotal, taxTotal = taxTotal, total = total, paymentMethod = paymentMethod,
    tendered = tendered, changeDue = changeDue, paymentRef = paymentRef, paymentStatus = paymentStatus,
    note = note, customerId = customerId,
    customerName = customerName, soldAt = IsoTime.toIso(soldAt),
    updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

fun SaleDto.toEntity() = SaleEntity(
    id = id, businessId = businessId, receiptNo = receiptNo, status = status, subtotal = subtotal,
    discountTotal = discountTotal, taxTotal = taxTotal, total = total, paymentMethod = paymentMethod,
    tendered = tendered, changeDue = changeDue, paymentRef = paymentRef, paymentStatus = paymentStatus,
    note = note, customerId = customerId,
    customerName = customerName, soldAt = IsoTime.toMillis(soldAt),
    updatedAt = IsoTime.toMillis(updatedAt), deleted = deleted, synced = true
)

// ── sale_items ────────────────────────────────────────────────────────────────
@Serializable
data class SaleLineDto(
    val id: String,
    @SerialName("sale_id") val saleId: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("item_id") val itemId: String? = null,
    val name: String,
    val qty: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    @SerialName("line_discount") val lineDiscount: Double = 0.0,
    @SerialName("line_tax") val lineTax: Double = 0.0,
    @SerialName("line_total") val lineTotal: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun SaleLine.toDto() = SaleLineDto(
    id = id, saleId = saleId, businessId = businessId, itemId = itemId, name = name, qty = qty,
    unitPrice = unitPrice, lineDiscount = lineDiscount, lineTax = lineTax, lineTotal = lineTotal,
    updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

fun SaleLineDto.toEntity() = SaleLine(
    id = id, saleId = saleId, businessId = businessId, itemId = itemId, name = name, qty = qty,
    unitPrice = unitPrice, lineDiscount = lineDiscount, lineTax = lineTax, lineTotal = lineTotal,
    updatedAt = IsoTime.toMillis(updatedAt), deleted = deleted
)

// ── customers ─────────────────────────────────────────────────────────────────
@Serializable
data class CustomerDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun Customer.toDto() = CustomerDto(
    id = id, businessId = businessId, name = name, phone = phone, email = email,
    address = address, note = note, updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

fun CustomerDto.toEntity() = Customer(
    id = id, businessId = businessId, name = name, phone = phone, email = email,
    address = address, note = note, updatedAt = IsoTime.toMillis(updatedAt),
    deleted = deleted, pendingSync = false
)

// ── credit_transactions ─────────────────────────────────────────────────────────
@Serializable
data class CreditTxnDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("sale_id") val saleId: String? = null,
    val type: String,
    val amount: Double = 0.0,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false
)

fun CreditTxn.toDto() = CreditTxnDto(
    id = id, businessId = businessId, customerId = customerId, saleId = saleId, type = type,
    amount = amount, note = note, createdAt = IsoTime.toIso(createdAt),
    updatedAt = IsoTime.toIso(updatedAt), deleted = deleted
)

fun CreditTxnDto.toEntity() = CreditTxn(
    id = id, businessId = businessId, customerId = customerId, saleId = saleId, type = type,
    amount = amount, note = note, createdAt = IsoTime.toMillis(createdAt),
    updatedAt = IsoTime.toMillis(updatedAt), deleted = deleted, pendingSync = false
)
