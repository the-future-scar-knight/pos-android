package com.portionspot.pos.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

/**
 * Data model for Spot POS.
 *
 * Design notes (mirrors the Supabase schema in pos-supabase-schema.sql):
 *  - Every table carries a [businessId] so the same app can serve PortionSpot
 *    Motors AND other tenants if this becomes a POS SaaS.
 *  - Primary keys are CLIENT-generated UUID strings, so rows created offline
 *    already hold their final id and sync never has to reconcile ids.
 *  - [updatedAt] (epoch millis) + [deleted] tombstone power last-write-wins
 *    sync to Supabase in a later milestone.
 *  - The live, editable cart is NOT a table — it lives in memory in the
 *    ViewModel. Only a COMPLETED sale is frozen into [SaleEntity]/[SaleLine].
 */

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

/** One tenant. Holds the configurable business name + logo (not hardcoded). */
@Entity(tableName = "businesses")
data class Business(
    @PrimaryKey val id: String = newId(),
    val name: String = "My Business",
    val logoUri: String? = null,          // local content:// or file:// uri
    val currency: String = "USD",
    val tagline: String? = null,          // printed under the name on receipts
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val receiptHeader: String? = null,    // extra lines printed above items
    val receiptFooter: String? = "Thank you!",
    // ---- Bluetooth thermal printer (paired ESC/POS) ----
    val btPrinterMac: String? = null,     // MAC of the chosen paired printer
    val btPrinterName: String? = null,    // friendly name for the UI
    val paperWidth: String = "58mm",      // "58mm" (32 cols) or "80mm" (48 cols)
    val receiptLargeText: Boolean = false,
    // ──── VAT / ZIMRA (synced) ────
    val vatEnabled: Boolean = false,
    val vatNumber: String? = null,        // ZIMRA VAT registration number
    val vatPercent: Double = 15.0,        // applied only when vatEnabled
    // ──── Which payment methods the cashier may offer (synced) ────
    val cashEnabled: Boolean = true,
    val cardEnabled: Boolean = false,     // card / swipe (manual capture)
    val bankEnabled: Boolean = false,
    val paynowEnabled: Boolean = false,
    val ecocashEnabled: Boolean = false,
    val innbucksEnabled: Boolean = false,
    val onemoneyEnabled: Boolean = false,
    val omariEnabled: Boolean = false,
    // ──── Bank transfer details (synced) ────
    val bankName: String? = null,
    val bankBranch: String? = null,
    val bankAccountName: String? = null,
    val bankAccountNumber: String? = null,
    // ──── Mobile money: the account the buyer pays into (synced) ────
    val ecocashAccountName: String? = null,
    val ecocashPhone: String? = null,
    val ecocashMerchantCode: String? = null,   // optional merchant code
    val innbucksAccountName: String? = null,
    val innbucksPhone: String? = null,
    val onemoneyAccountName: String? = null,
    val onemoneyPhone: String? = null,
    val omariAccountName: String? = null,
    val omariPhone: String? = null,
    // ──── Paynow online ────
    // Integration ID is a public identifier and IS synced. The Integration KEY
    // is a secret: it is LOCAL-ONLY (never put in BusinessDto, like btPrinterMac)
    // and, in Stage 3, lives in the business's own Supabase Edge Function secrets
    // — never in the APK and never in a synced cloud table.
    val paynowIntegrationId: String? = null,
    val paynowIntegrationKey: String? = null,  // LOCAL-ONLY — not synced
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/** A sellable product in the POS catalog. */
@Entity(
    tableName = "items",
    indices = [Index("businessId"), Index(value = ["businessId", "barcode"])]
)
data class Item(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val categoryId: String? = null,
    val name: String,
    val barcode: String? = null,
    val sku: String? = null,
    val category: String? = null,        // free-text category (drives POS chips)
    // ──── Wholesale pricing (mirrors the web catalog) ────
    // [price] is the RETAIL price (the big number on the product card).
    // A "box item" has [boxSize] > 1 and a [boxPrice]; [wholesalePrice] is the
    // per-unit trade price. Untracked/unit-only items leave boxSize = 1.
    val price: Double = 0.0,             // retail (per unit)
    @ColumnInfo(defaultValue = "0") val wholesalePrice: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val boxPrice: Double = 0.0,
    @ColumnInfo(defaultValue = "1") val boxSize: Int = 1,
    val cost: Double? = null,
    val taxRate: Double = 0.0,            // percent, e.g. 16.0
    val trackStock: Boolean = false,
    val stockQty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val reorderLevel: Double = 0.0,  // low-stock threshold
    val unit: String = "pc",
    val colorHex: String? = null,        // tile colour when no image
    val isActive: Boolean = true,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/** A COMPLETED receipt (frozen snapshot). The live cart stays in memory. */
@Entity(
    tableName = "sales",
    indices = [Index(value = ["businessId", "soldAt"])]
)
data class SaleEntity(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val receiptNo: String? = null,
    val status: String = "completed",     // completed | parked | refunded | void
    val subtotal: Double = 0.0,
    val discountTotal: Double = 0.0,
    val taxTotal: Double = 0.0,
    val total: Double = 0.0,
    val paymentMethod: String = "cash",   // single tender code, or "split" when >1 payment row
    val tendered: Double? = null,
    @ColumnInfo(defaultValue = "0") val amountPaid: Double = 0.0,  // sum of all SalePayment rows
    val changeDue: Double? = null,         // change actually given back to the customer
    val paymentRef: String? = null,        // mobile-money / Paynow reference number
    val paymentStatus: String = "paid",    // paid | unpaid (credit) | pending (Paynow)
    val note: String? = null,
    val customerId: String? = null,        // null => walk-in
    val customerName: String? = null,      // snapshot so receipts print without a lookup
    val soldAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** false until the WorkManager sync (later milestone) pushes it to Supabase. */
    val synced: Boolean = false
)

/** One line of a completed sale. name/unitPrice are SNAPSHOTS at sale time. */
@Entity(
    tableName = "sale_items",
    indices = [Index("saleId")]
)
data class SaleLine(
    @PrimaryKey val id: String = newId(),
    val saleId: String,
    val businessId: String,
    val itemId: String? = null,
    val name: String,                     // snapshot (item may change/delete later)
    val qty: Double = 1.0,
    val unitPrice: Double = 0.0,          // snapshot
    val lineDiscount: Double = 0.0,
    val lineTax: Double = 0.0,
    val lineTotal: Double = 0.0,
    // Price mode + pack size, snapshotted so a parked cart can be rebuilt and the
    // receipt can show "Box of 12". mode = box | wholesale | retail.
    @ColumnInfo(defaultValue = "retail") val mode: String = "retail",
    @ColumnInfo(defaultValue = "1") val unitsPerLine: Int = 1,
    val updatedAt: Long = now(),
    val deleted: Boolean = false
)

/**
 * One tender within a sale. Split payments mean a single [SaleEntity] can have
 * several of these (e.g. $50 cash + $30 EcoCash). LOCAL-ONLY for now — not pushed
 * to the cloud (the shared `sales` table keeps a single paymentMethod), so no
 * pendingSync column. The parent sale's [SaleEntity.paymentMethod] is the single
 * method, or "split" when more than one row exists.
 */
@Entity(tableName = "sale_payments", indices = [Index("saleId")])
data class SalePayment(
    @PrimaryKey val id: String = newId(),
    val saleId: String,
    val businessId: String,
    val method: String,                   // cash | card | bank | paynow | ecocash | …
    val amount: Double = 0.0,             // ALWAYS in the base currency — every sum/report reads this
    val reference: String? = null,        // mobile-money / bank / Paynow reference
    // ── Dual-currency tender (nullable => legacy/base-currency cash). The base
    // [amount] above stays the source of truth; these three are for display, the
    // Z-report currency split and receipt reprints only. ──
    val tenderCurrency: String? = null,   // second-currency code, e.g. "ZWG", when paid in ZiG
    val tenderAmount: Double? = null,     // amount actually handed over, in [tenderCurrency]
    val rate: Double? = null,             // second-per-base rate at sale time
    val createdAt: Long = now()
)

/**
 * A stock-level change for one item (inventory Stage B audit trail). [type] is
 * sale | restock | adjust | return | reset. [delta] is signed (negative = drawn
 * down). [balanceAfter] snapshots the resulting on-hand so the ledger reads
 * cleanly without recomputation. LOCAL-ONLY — not synced.
 */
@Entity(tableName = "stock_movements", indices = [Index("itemId"), Index("businessId")])
data class StockMovement(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val itemId: String,
    val type: String,                     // sale | restock | adjust | return | reset
    val delta: Double = 0.0,
    val balanceAfter: Double = 0.0,
    val note: String? = null,
    val createdAt: Long = now()
)

/** A completed sale together with its lines (read model for receipts/history). */
data class SaleWithLines(
    val sale: SaleEntity,
    val lines: List<SaleLine>
)

/**
 * Aggregate totals over a date range for the Reports screen (read model).
 *  - [gross] = money billed including VAT (SUM of sale totals).
 *  - [vat]   = VAT collected (SUM of taxTotal).
 *  - [net]   = VAT-exclusive sales (gross − vat).
 *  - [discount] = total discounts given.
 */
data class SalesSummary(
    val count: Int = 0,
    val gross: Double = 0.0,
    val vat: Double = 0.0,
    val discount: Double = 0.0,
    val net: Double = 0.0
)

/** Sales grouped by tender (read model for the Reports payment breakdown). */
data class MethodBreakdown(
    val method: String,
    val count: Int,
    val total: Double
)

/** A top-selling product over a date window (read model for the Dashboard). */
data class TopProduct(
    val name: String,
    val qty: Double = 0.0,
    val revenue: Double = 0.0
)

/** A lightweight (timestamp, total) pair powering the Dashboard's 7-day chart. */
data class SaleStamp(
    val soldAt: Long,
    val total: Double
)

/** A customer/account, so sales can be sold on credit and debt tracked. */
@Entity(
    tableName = "customers",
    indices = [Index("businessId")]
)
data class Customer(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val note: String? = null,
    /**
     * Local-only wholesale flag: when on, this customer is a trade buyer and the
     * cashier should reach for the Box / Wholesale price. NOT pushed to the cloud
     * (the shared `customers` table has no such column) and preserved across pulls
     * by the sync engine.
     */
    @ColumnInfo(defaultValue = "0") val wholesale: Boolean = false,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/**
 * One entry in a customer's credit ledger. The customer's balance is DERIVED
 * (never stored): SUM(credit_owed) − SUM(credit_paid). A sale on credit writes
 * one `credit_owed` row; a repayment writes one `credit_paid` row.
 */
@Entity(
    tableName = "credit_transactions",
    indices = [Index("businessId"), Index("customerId")]
)
data class CreditTxn(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val customerId: String,
    val saleId: String? = null,            // set for credit_owed / change_owed from a sale
    // credit_owed  = customer owes the shop (sold on account)
    // credit_paid  = customer repaid the shop
    // change_owed  = shop owes the customer change it couldn't give
    // change_paid  = shop handed that change over later
    val type: String,
    val amount: Double = 0.0,
    val note: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/** A customer paired with their derived outstanding balance (read model). */
data class CustomerWithBalance(
    val customer: Customer,
    val balance: Double
)

/** A single (customerId, derived balance) row from the credit ledger. */
data class BalanceRow(
    val customerId: String,
    val balance: Double
)

/**
 * A business running cost (rent, salaries, fuel…). LOCAL-ONLY, exactly like the
 * web: the cloud schema has no `expenses` table, so these never sync (no
 * pendingSync flag). [date] is a `yyyy-MM-dd` string (matches the web) so the
 * day-bucketed preset filters need no timezone math. [deleted] tombstones a row.
 */
@Entity(tableName = "expenses", indices = [Index("businessId")])
data class Expense(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val category: String = "Other",
    val amount: Double = 0.0,
    val date: String,                     // yyyy-MM-dd
    val description: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false
)

/**
 * A goods supplier / vendor. Local-only (the web keeps these in Dexie, not the
 * cloud schema), so there is no `pendingSync` column. Purchase orders denormalise
 * the supplier's name onto each PO, so a tombstoned supplier never breaks history.
 */
@Entity(tableName = "suppliers", indices = [Index("businessId")])
data class Supplier(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false
)

/**
 * A purchase order (restock request to a [Supplier]). LOCAL-ONLY, like the web's
 * Dexie store — the cloud schema has no `purchase_orders` table, so no pendingSync.
 * Lifecycle: draft → sent → received (or cancelled). [supplierName] is denormalised
 * so deleting a supplier never orphans PO history. [ref] is the human code
 * `PO-YYMMDD-NNNN`. Receiving a PO bumps each linked item's stock (see repository).
 */
@Entity(tableName = "purchase_orders", indices = [Index("businessId")])
data class PurchaseOrder(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val ref: String,
    val supplierId: String? = null,
    val supplierName: String = "",
    val status: String = "draft",          // draft | sent | received | cancelled
    val notes: String? = null,
    val createdAt: Long = now(),
    val sentAt: Long? = null,
    val receivedAt: Long? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false
)

/**
 * One line on a [PurchaseOrder]. [itemId] links to the catalog [Item] by UUID
 * (the Android catalog keys on UUID, not the web's sku) so receiving can find the
 * product to restock; [name]/[sku] are snapshotted for display. [qty] is ordered
 * quantity in units; [receivedQty] is filled in when the PO is received.
 */
@Entity(tableName = "purchase_order_items", indices = [Index("poId")])
data class PurchaseOrderLine(
    @PrimaryKey val id: String = newId(),
    val poId: String,
    val itemId: String? = null,
    val name: String = "",
    val sku: String? = null,
    val qty: Double = 1.0,
    val unitCost: Double = 0.0,
    val receivedQty: Double? = null
)

/**
 * Read-model that stitches a [PurchaseOrder] to its [PurchaseOrderLine]s in one
 * query (Room @Relation). Used everywhere the UI shows a PO with its items.
 */
data class PurchaseOrderWithLines(
    @Embedded val po: PurchaseOrder,
    @Relation(parentColumn = "id", entityColumn = "poId")
    val lines: List<PurchaseOrderLine>
)

/**
 * Local key/value store. Holds the bring-your-own database connection
 * (url + anon key) and per-table sync cursors. Purely on-device — never
 * pushed to the cloud.
 */
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)
