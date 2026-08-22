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
    // ──── Shop-wide capability locks (synced: businesses.lock_*) ────
    // A lock switches a capability off for EVERY cashier at once, whatever their own
    // per-staff permissions say. The two gates compose one way only:
    //
    //     allowed = isAdmin OR (NOT shopLocked AND staffPermitted)
    //
    // so a per-staff grant can never open what the shop has closed — it can only ever
    // subtract. The admin bypasses both. See Capability.shopLockColumn for the mapping
    // and PosUser.can for the check.
    @ColumnInfo(defaultValue = "0") val lockRefunds: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockDiscounts: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockCredit: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockPriceOverride: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockParking: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockQuotes: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lockStockAdjust: Boolean = false,
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
    // How the product is sold (mirrors the web catalog's product_type):
    //   "box"      — sold by the box AND/OR as loose units (uses boxSize/boxPrice).
    //   "set"      — sold only as a complete set (no box split; stock counts sets).
    //   "piece"    — sold individually, no box (stock counts pieces).
    //   "measured" — sold by a DECIMAL quantity of [unit] (e.g. 2.35 kg). Pricing is
    //                [pricePerUnit] × qty and on-hand lives in [stockMeasured]; the
    //                integer box/piece stock (stockQty) is NOT used for measured items.
    // Drives the product form's field set and the type-aware stock wording.
    @ColumnInfo(defaultValue = "box") val productType: String = "box",
    val cost: Double? = null,
    val taxRate: Double = 0.0,            // percent, e.g. 16.0
    val trackStock: Boolean = false,
    val stockQty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val reorderLevel: Double = 0.0,  // low-stock threshold
    val unit: String = "pc",
    // ──── Measured (unit-priced) products — productType == "measured" ────
    // [pricePerUnit] is the price of ONE [unit] (kg, L, m, …); a measured line costs
    // pricePerUnit × the decimal quantity the cashier enters. [stockMeasured] is the
    // decimal on-hand quantity (in [unit]s), drawn down by the sold quantity at
    // checkout — the integer stockQty is left untouched for measured items.
    // Both now SYNC: the cloud `products` table carries `unit`, `price_per_unit` and
    // `stock_measured`, so they push up and pull down like any other product field (a
    // null cloud value is treated as "not set" and never wipes the local one).
    @ColumnInfo(defaultValue = "0") val pricePerUnit: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val stockMeasured: Double = 0.0,
    // ──── Pending (incoming) stock from a purchase order (B4) — LOCAL-ONLY ────
    // [pendingQty] is stock that has been ORDERED from a supplier but not yet arrived:
    // it shows as a "+N pending" badge and is NOT sellable. On arrival it moves into the
    // real sellable stock (stockQty / stockMeasured) and this drops back to 0.
    // [pendingNew] marks a product that was CREATED by a purchase order and has never
    // had a confirmed arrival — it is fully blocked from sale until its first arrival is
    // confirmed (an existing product keeps selling its current stock, unaffected). The
    // cloud `products` table has no matching columns, so both stay on-device.
    @ColumnInfo(defaultValue = "0") val pendingQty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val pendingNew: Boolean = false,
    // ──── Stock baseline — the anchor the ledger is measured FROM (LOCAL-ONLY) ────
    // `stock_movements` is a log of CHANGES, and nothing ever writes an opening entry:
    // an item pulled with 20 on hand, or typed in with 20, has no movement saying so.
    // Summing the deltas therefore yields "how much this item has moved", not what is
    // on the shelf — and reading that sum as an on-hand emptied a shelf of 2 after a
    // single sale of 1, because the only movement the device held was the -1.
    //
    // [stockBaseQty] is the shop's own figure (cloud `items.stock_qty`) and
    // [stockBaseAt] is the cloud row's `client_updated_at` — the DEVICE clock, the same
    // one `stock_movements.created_at` uses, because the ledger is measured from it. On-hand
    // is then baseline + every movement created AFTER it, which is well-defined however
    // many tills contributed and in whatever order their rows arrive.
    //
    // stockBaseAt == 0 means NO baseline is known — this item has never been reconciled
    // against the shop, so the ledger says nothing absolute about it and its stockQty is
    // left exactly as the till's own bookkeeping has it.
    @ColumnInfo(defaultValue = "0") val stockBaseQty: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val stockBaseAt: Long = 0L,
    val colorHex: String? = null,        // tile colour when no image
    // ──── Product image (mirrors the cloud `products.image_url` + `show_image`) ────
    // [imageUrl] is the REMOTE Supabase Storage public URL — this is what syncs to the
    // cloud. [imageLocalPath] is a downscaled on-device copy for offline/instant display
    // AND the source bytes uploaded to Storage on push; it is LOCAL-ONLY. [imagePending]
    // flags a locally picked/removed image not yet pushed to Storage (LOCAL-ONLY).
    // Display prefers imageLocalPath, falling back to imageUrl.
    val imageUrl: String? = null,
    val imageLocalPath: String? = null,
    @ColumnInfo(defaultValue = "0") val imagePending: Boolean = false,
    @ColumnInfo(defaultValue = "1") val showImage: Boolean = true,
    val isActive: Boolean = true,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/** Measured (unit-priced) product: sold by a decimal quantity of [Item.unit]. */
val Item.isMeasured: Boolean get() = productType == "measured"

/** On-hand quantity used for badges / low-stock: the decimal [stockMeasured] for a
 *  measured item, otherwise the integer/box unit count [stockQty]. */
val Item.onHand: Double get() = if (isMeasured) stockMeasured else stockQty

/** True when the item exists only as PENDING (incoming) stock and cannot be sold yet:
 *  a purchase-order product whose first arrival has not been confirmed and which has no
 *  real on-hand stock. Blocks add-to-cart until arrival (B4). An existing product with a
 *  "+N pending" addition is NOT blocked — it keeps selling its current stock. */
val Item.sellableBlocked: Boolean get() = pendingNew && onHand <= 0.0

/**
 * One key/value tag on a catalogue item — the web's `item_attributes`.
 *
 * In this shop every row is `key = "car"` and the value is a vehicle the part fits
 * ("Vezel", "Ford Ranger T6", "Fit GK3"), which is why an item can carry a dozen of them.
 * That is the point of the table: a part's name can only name one or two of the cars it
 * fits, so without these a customer asking for "brake pads for a Hilux" only finds
 * anything when the word happens to have been typed into the product name.
 *
 * **TWO-WAY, unlike [Item].** The catalogue itself is still the web's, but the fitments
 * are edited on the till — the owner is the one holding the part when he learns it also
 * fits a Vezel — so this table pushes as well as pulls. See [PosSyncEngine]'s
 * `item_attributes` block.
 *
 * ★ [id] IS NOT RANDOM. It is derived from (business, item, canonical key, canonical value)
 * by [attributeId], and that is what makes two offline tills adding the same fitment
 * converge onto one row instead of racing each other into a duplicate the cloud's live
 * unique index would then reject — failing the whole push batch. Never mint one with
 * [newId]; go through the repository, which derives it.
 *
 * [keyNorm] / [valueNorm] are computed on THIS side rather than taken from the wire: both
 * cloud columns are nullable, and a null on a hand-inserted row would silently drop that
 * fitment out of every search. Search reads the normalised pair, so it has to exist. They
 * are GENERATED ALWAYS on the cloud, which is why the push DTO must not name them.
 */
@Entity(
    tableName = "item_attributes",
    indices = [
        Index("businessId"),
        Index("itemId"),
        Index(value = ["businessId", "keyNorm", "valueNorm"])
    ]
)
data class ItemAttribute(
    @PrimaryKey val id: String,
    val businessId: String,
    val itemId: String,
    val key: String,
    val value: String,
    val keyNorm: String = attrNorm(key),
    val valueNorm: String = attrNorm(value),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud.
     *  Defaults FALSE, the opposite of every other synced entity here: the overwhelming
     *  majority of these rows are born on the wire (671 of them arrived in one pull), and
     *  a default of true would queue the shop's entire tag list straight back up on the
     *  first pass. The three repository writes that create a tag locally set it. */
    val pendingSync: Boolean = false,
)

/**
 * Fold an attribute key or value for matching: trimmed, inner runs of whitespace
 * collapsed, lower-cased. Matches what the web stores in `key_norm` / `value_norm` for
 * every row in the live table ("Ford Ranger T6" → "ford ranger t6"), and gives a stable
 * answer for the rows where those columns are null.
 */
fun attrNorm(raw: String): String =
    raw.trim().replace(WHITESPACE_RUN, " ").lowercase()

private val WHITESPACE_RUN = Regex("\\s+")

/** A COMPLETED receipt (frozen snapshot). The live cart stays in memory. */
@Entity(
    tableName = "sales",
    indices = [Index(value = ["businessId", "soldAt"])]
)
data class SaleEntity(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val receiptNo: String? = null,
    val status: String = "completed",     // completed | parked | refunded | void | quote
    /** For quotes (status='quote'): the date the quote lapses. Null for real sales. */
    val validUntil: Long? = null,
    val subtotal: Double = 0.0,
    val discountTotal: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val markupTotal: Double = 0.0,  // sum of per-item markups (mirrors discountTotal)
    val taxTotal: Double = 0.0,
    val total: Double = 0.0,
    val paymentMethod: String = "cash",   // single tender code, or "split" when >1 payment row
    val tendered: Double? = null,
    @ColumnInfo(defaultValue = "0") val amountPaid: Double = 0.0,  // sum of all SalePayment rows
    val changeDue: Double? = null,         // change actually given back to the customer
    val changeOwed: Double? = null,        // change the shop still owes the customer (given back < change due)
    val paymentRef: String? = null,        // mobile-money / Paynow reference number
    val paymentStatus: String = "paid",    // paid | unpaid (credit) | pending (Paynow)
    val note: String? = null,
    val customerId: String? = null,        // null => walk-in
    val customerName: String? = null,      // snapshot so receipts print without a lookup
    val soldAt: Long = now(),
    /** The shift this sale belongs to ([CashSession]); null for a sale rung up with no
     *  shift open. This is what a cash-up counts by — a sale that points at a session the
     *  shop cannot resolve is silently left out of the drawer count, which is the whole
     *  reason the merge rule repoints these rather than letting a loser session linger. */
    val sessionId: String? = null,
    // ──── Attribution & audit (Phase 2) ────
    // Stamped from the signed-in cashier's cached session AT creation time (offline
    // included) so ownership survives device sharing and network drops. Nullable
    // until Phase-2 wiring stamps them; serverCreatedAt is set from server time on sync.
    val createdBy: String? = null,        // auth user uuid of the cashier
    val createdByName: String? = null,    // display-name snapshot (survives account edits)
    val serverCreatedAt: Long? = null,    // server time, set on sync
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    // ──── In-place edit within the admin window (B5) ────
    // The receipt is edited IN PLACE (same id, same receiptNo) — never duplicated —
    // so there is exactly one row per sale. These two are the visible marker; the
    // append-only `audit_log` holds the actual before/after history.
    /** When the receipt was last edited; null => never edited. */
    val editedAt: Long? = null,
    /** How many times it has been edited. */
    @ColumnInfo(defaultValue = "0") val editCount: Int = 0,
    /** false until the WorkManager sync (later milestone) pushes it to Supabase. */
    val synced: Boolean = false
)

/** True when this receipt has been edited in place at least once (B5). */
val SaleEntity.wasEdited: Boolean get() = editedAt != null

/**
 * Is [this] sale still inside the shop's edit window? Editing is deliberately narrow:
 * only a COMPLETED sale (never a quote, park, void or refund row) and only while
 * `now - soldAt <= windowMinutes`. Past that the receipt is locked and a correction
 * has to go through a void/refund, which leaves its own trail.
 */
fun SaleEntity.isEditable(windowMinutes: Int, now: Long = now()): Boolean =
    status == "completed" &&
        !deleted &&
        windowMinutes > 0 &&
        now - soldAt <= windowMinutes * 60_000L

/**
 * The [SaleEntity.status] values that mean "this row is a real receipt": money moved and
 * goods left the shop.
 *
 * `sales` also stores documents that are NOT sales — a `quote` priced for a customer who
 * paid nothing, a `parked` cart still on the counter — and a `void` row is a sale that has
 * already been reversed. Every list that presents receipts to a human, and every action
 * that reverses one, has to say which of those it means.
 */
val RECEIPT_STATUSES = setOf("completed", "refunded")

/**
 * Can this row be refunded?
 *
 * ★ THE GUARD SITS AT THE ACTION, not only on the list that offers it. The Receipts query
 * ([SaleDao.observeRecent]) no longer returns quotes or parked carts, but a query
 * is a presentation decision and the next screen that wants a sale list can be written
 * without knowing that. Refunding a quote restocks goods that never left the shop and
 * books a real `refund_owed` debt against a document that was never a sale — a hole worth
 * closing twice, so the money path checks for itself.
 *
 * A `refunded` sale is refundable again on purpose: refunds are per line and partial, and
 * [PosRepository.qtyReturnedForLine] is what caps a second return at what is still owed.
 */
fun SaleEntity.isRefundable(): Boolean = !deleted && status in RECEIPT_STATUSES

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
    /**
     * COST OF GOODS, FROZEN AT SALE TIME — the [Item.cost] the product carried the
     * moment this line was rung up. Without it, gross profit joined the LIVE catalog
     * cost, so editing a product's cost price silently rewrote the profit of every
     * past sale. Null means "not captured" (rows written before this column existed,
     * or a line with no matching item); the profit query falls back to the catalog
     * cost for exactly those rows.
     *
     * BASIS: cost of ONE STOCK UNIT — the same basis the profit query has always used
     * against [unitPrice]. (Known pre-existing caveat, unchanged here: on a `box` line
     * [unitPrice] is the price of a whole box while this is the cost of one unit, so a
     * box line's margin reads high. Fixing that would move historical numbers and is a
     * separate decision.)
     */
    val unitCost: Double? = null,
    val lineDiscount: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val lineMarkup: Double = 0.0,
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
    // Attribution (Phase 2): which cashier caused this movement (a "return" movement
    // is created by a refund; a "sale" by checkout; "adjust"/"restock" by inventory).
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: Long = now(),
    // ──── Sync. The ledger is the AUTHORITY for stock, so it has to travel ────
    // This was device-local, which meant a sale drew down the till that rang it up and
    // no other till ever heard about it. Because `items.stock_qty` is only a CACHE of
    // these rows, that left a shop with as many stock figures as it had tills — none of
    // them wrong from where it was standing.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    @ColumnInfo(defaultValue = "1") val pendingSync: Boolean = true
)

/** On-hand for one item, summed from its movement ledger (read model). */
data class ItemOnHand(val itemId: String, val onHand: Double)

/** A completed sale together with its lines (read model for receipts/history). */
data class SaleWithLines(
    val sale: SaleEntity,
    val lines: List<SaleLine>
)

/**
 * A refund / return against a completed [SaleEntity] (prompt §11). Append-only,
 * immutable-ledger style: the original sale is NEVER edited — a refund is a
 * reversal linked back to it, so the full history (sold → refunded → repaid) can
 * always be reconstructed. A refund is full or partial (specific returned lines).
 *
 * Money model:
 *  - [refundTotal] is what the shop owes the customer for the returned goods,
 *    computed PROPORTIONALLY from the original sale so a partial return carries its
 *    share of the sale's discount + VAT (see [computeRefundTotal]).
 *  - The money actually handed back lives in [RefundPayment] rows (method + time),
 *    so a refund supports split payouts and being paid over time.
 *  - If it isn't fully paid at once AND a customer is set, the outstanding amount is
 *    booked as a `refund_owed` credit-ledger row that ages in the Change & Credit
 *    screen exactly like change/credit; each later payout writes a `refund_paid` row.
 *
 * Attribution: [createdBy] is the cashier, stamped at creation (offline included).
 */
@Entity(
    tableName = "refunds",
    indices = [Index("businessId"), Index("saleId"), Index("customerId")]
)
data class Refund(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val saleId: String,                    // the original sale being refunded
    val saleReceiptNo: String? = null,     // snapshot of the sale's receipt no for display
    val customerId: String? = null,        // null => walk-in (must be paid out in full now)
    val customerName: String? = null,      // snapshot
    val reason: String? = null,
    val refundTotal: Double = 0.0,         // what the returned goods were worth on the sale
    /**
     * The part of [refundTotal] that may actually be handed back in money — the rest was
     * never paid for and is cancelled off the customer's account instead (see
     * [planRefundSettlement]).
     *
     * ★ SEPARATE FROM [refundTotal] BECAUSE THE TWO ARE GENUINELY DIFFERENT NUMBERS, and
     * conflating them is what let a $100 refund of a sale that had collected $40 pay out
     * the full hundred. [refundTotal] stays the goods figure — recognition pro-rates the
     * sale by it, and the printed slip states it — while THIS is what "paid in full" means
     * for a refund. Without it a capped refund could never reach `settled`: the payout
     * would stop at $40 and the status test would keep comparing it against $100, leaving
     * the shop chasing a balance it does not owe.
     *
     * Defaults to [refundTotal] for rows written before the distinction existed, which is
     * exactly right for them — every one was a fully-collected sale or was over-paid, and
     * either way the figure it was measured against at the time was the total.
     */
    @ColumnInfo(defaultValue = "0") val payableTotal: Double = refundTotal,
    val status: String = "settled",        // settled (paid in full) | owed (balance outstanding)
    val createdBy: String? = null,         // cashier auth uuid (Phase 2)
    val createdByName: String? = null,
    val createdAt: Long = now(),           // device time at creation (offline-safe)
    val serverCreatedAt: Long? = null,     // server time, set on sync
    /** The shift this payout belongs to ([CashSession]) — money leaving the drawer has to
     *  be counted against the same shift the sales were. */
    val sessionId: String? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/**
 * One returned line within a [Refund]. name/unitPrice are SNAPSHOTS from the
 * original [SaleLine]. [qty] is how many line-units came back; [restock] controls
 * whether they went back on the shelf (false for damaged/faulty goods).
 */
@Entity(tableName = "refund_items", indices = [Index("refundId")])
data class RefundLine(
    @PrimaryKey val id: String = newId(),
    val refundId: String,
    val businessId: String,
    val saleLineId: String? = null,        // original sale line, when known
    val itemId: String? = null,
    val name: String,                      // snapshot
    val qty: Double = 1.0,                 // line-units returned
    val unitPrice: Double = 0.0,           // snapshot
    val lineTotal: Double = 0.0,           // returned value NET of the line's own discount/markup
    val mode: String = "retail",           // box | wholesale | retail (snapshot)
    val unitsPerLine: Int = 1,             // stock units per line-unit (box size), for restock
    val restock: Boolean = true,           // false => damaged, do NOT return to stock
    val createdAt: Long = now()
)

/**
 * One payout within a [Refund] — money actually handed back to the customer.
 * Mirrors [SalePayment]: several rows model a split payout, and rows added later
 * model a refund paid off over time. [amount] is ALWAYS base currency (the books
 * read this); the dual-currency trio is display/reporting only.
 */
@Entity(tableName = "refund_payments", indices = [Index("refundId")])
data class RefundPayment(
    @PrimaryKey val id: String = newId(),
    val refundId: String,
    val businessId: String,
    val method: String,                    // cash | ecocash | card | store_credit | …
    val amount: Double = 0.0,              // base currency — every sum/report reads this
    val reference: String? = null,
    val tenderCurrency: String? = null,    // second-currency code (e.g. "ZWG") when paid in ZiG
    val tenderAmount: Double? = null,      // amount handed over in [tenderCurrency]
    val rate: Double? = null,              // second-per-base rate at payout time
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: Long = now()
)

/**
 * Refunded value totalled per original sale (read model). Drives the "refunded"
 * badge on the Receipts list WITHOUT editing the sale — the ledger stays immutable
 * and every money aggregate keeps reading the original completed sale (gross), while
 * Reports still nets refunds out separately. Presentation only.
 */
data class SaleRefundSum(
    val saleId: String,
    val refunded: Double = 0.0
)

/** A refund together with its returned lines (read model for history/receipts). */
data class RefundWithLines(
    @Embedded val refund: Refund,
    @Relation(parentColumn = "id", entityColumn = "refundId")
    val lines: List<RefundLine>
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
    /** Σ per-item markup charged on sales in the window — the mirror of [discount]. GROSS:
     *  what was added at the counter, before anything came back. The returned share is a
     *  separate figure (`observeRefundedMarkup`) because the two are on different dates. */
    val markup: Double = 0.0,
    val net: Double = 0.0
)

/** Sales grouped by tender (read model for the Reports payment breakdown). */
data class MethodBreakdown(
    val method: String,
    val count: Int,
    val total: Double
)

/** Per-cashier totals over a window (read model for the admin end-of-day summary). */
data class CashierDay(
    val cashierId: String?,
    val cashierName: String?,
    val count: Int,
    val total: Double
)

/** One customer's debt split into aging buckets (read model for the aging report). */
data class DebtAgingRow(
    val customerId: String,
    val customerName: String,
    val bucket0to30: Double = 0.0,
    val bucket30to60: Double = 0.0,
    val bucket60to90: Double = 0.0,
    val bucket90plus: Double = 0.0,
    val oldestAt: Long = 0L,
    /** The customer's credit ceiling (null = no limit set); carried so the notification
     *  engine can flag a balance that has gone over it. */
    val creditLimit: Double? = null
) {
    val total: Double get() = bucket0to30 + bucket30to60 + bucket60to90 + bucket90plus
}

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
    /**
     * Local-only per-customer credit ceiling (null = unset / no explicit limit). Like
     * [wholesale] it is NOT read back from the shared `customers` table on pull, so the
     * sync engine preserves it across pulls (toCustomer copies onto the existing local
     * row without touching this field). Stored and displayed only — no checkout gating.
     */
    @ColumnInfo val creditLimit: Double? = null,
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
    /**
     * HOW the money moved, for the rows where money moved at all — `cash`, `ecocash`,
     * `card`, and the rest of the shop's tenders. Null on a row that is pure bookkeeping:
     * a `credit_owed` records a debt arising, and no tender was involved.
     *
     * Mirrors the shared `credit_txns.method` column, which the web has always written and
     * this app did not, so a repayment taken on a phone reached the browser with no tender
     * against it. It also decides the drawer: only `cash` moves the till, which is what
     * stops an EcoCash payout from making a physical drawer read short.
     */
    val method: String? = null,
    val createdAt: Long = now(),
    // ──── Attribution & audit (Phase 2): which cashier created this ledger row ────
    val createdBy: String? = null,
    val createdByName: String? = null,
    val serverCreatedAt: Long? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
)

/** Lightweight (saleId → receipt reference) projection used to label a ledger row with
 *  the sale that created it, without loading whole [SaleEntity] rows. */
data class SaleRef(val id: String, val receiptNo: String?)

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
 * A business running cost (rent, salaries, fuel…) and — from B3 — the accounting
 * spine's expense record. Anyone may SUBMIT one; it stays [status] = "pending" until
 * an admin approves (posts) or rejects it. On posting, double-entry-lite splits the
 * amount across the accounts that funded it: [cashPortion] (drew cash-on-hand down),
 * [payablePortion] (the shop now owes the payee — cash ran short) and [capitalPortion]
 * (the owner covered it out of pocket — cash untouched). The three portions sum to
 * [amount] once approved; they stay 0 while pending/rejected.
 *
 * RECURRING (e.g. rent): approving a recurring submission mints a TEMPLATE row
 * ([isTemplate] = true) — the schedule, never itself counted as a posted cost — which
 * auto-posts a child charge each period ([nextRunAt]). Children carry [templateId] and
 * are ordinary approved postings. The admin can PAUSE ([recurrenceActive] = false),
 * EDIT the amount, or CANCEL (tombstone the template) at any time.
 *
 * [date] is a `yyyy-MM-dd` string (matches the web) so day-bucketed filters need no
 * timezone math. SYNCED: the cloud `expenses` table upserts on [localId].
 */
@Entity(tableName = "expenses", indices = [Index("businessId")])
data class Expense(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,             // sync-ready stable local key (defaults to id)
    val businessId: String,
    val category: String = "Other",
    val amount: Double = 0.0,
    val date: String,                     // yyyy-MM-dd
    val description: String? = null,
    // ── Approval lifecycle ──
    val status: String = "pending",       // pending | approved | rejected
    val submittedBy: String? = null,
    val submittedByName: String? = null,
    val approvedBy: String? = null,
    val approvedByName: String? = null,
    val approvedAt: Long? = null,
    val postedAt: Long? = null,           // when it hit the books (== approvedAt for one-offs)
    // ── Double-entry-lite funding split (set on posting; sums to [amount]) ──
    val cashPortion: Double = 0.0,        // reduced cash-on-hand
    val payablePortion: Double = 0.0,     // shop owes the payee (cash ran short)
    val capitalPortion: Double = 0.0,     // owner covered it (cash untouched)
    // ── Recurring schedule ──
    val recurring: Boolean = false,
    val recurrencePeriod: String? = null, // daily | weekly | monthly
    val recurrenceActive: Boolean = true, // admin can pause
    val isTemplate: Boolean = false,      // true => schedule row, not a posted cost
    val templateId: String? = null,       // set on auto-posted children
    val nextRunAt: Long? = null,          // template: when the next child is due
    val lastRunAt: Long? = null,          // template: when it last posted a child
    // ── Optional time period the cost covers (informational) ──
    val periodStart: String? = null,      // yyyy-MM-dd
    val periodEnd: String? = null,        // yyyy-MM-dd
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** true => has unsynced local edits waiting to push to the cloud. */
    val pendingSync: Boolean = true
)

/**
 * WHERE the shop's cash physically sits. There is NO bank: the money is on-site in one
 * of exactly two places, and every [CashTxn] belongs to one of them.
 *
 *  - [TILL] — the working float in the drawer. Only enough to make change. Sales cash
 *    lands here and change/refund payouts leave from here.
 *  - [SAFE] — the day's takings, moved out of the drawer at close of day. Taking money
 *    back OUT of the safe needs the owner's say-so (see the `safe_withdrawal` staff
 *    request), which is why it is a separate location and not just "more drawer".
 *
 * The two are balances over the SAME ledger — cash-on-hand stays `TILL + SAFE`, so every
 * existing caller of the combined figure keeps reading the right number. Moving money
 * between them is a TRANSFER (a matching `transfer_out`/`transfer_in` pair summing to
 * zero): it is neither income nor expense and never changes the combined total.
 */
object CashLocation {
    const val TILL = "till"
    const val SAFE = "safe"

    /** Human label for a stored location code (unknown/legacy reads as the till). */
    fun label(code: String?): String = if (code == SAFE) "Safe" else "Till"

    /** Normalise any stored/typed value to a known location (legacy rows = till). */
    fun of(code: String?): String = if (code == SAFE) SAFE else TILL
}

/**
 * One movement of physical CASH-ON-HAND. The accounting spine (B3): cash-on-hand =
 * opening float (a setting) + Σ of these signed [amount]s. Cash comes IN from sales
 * ("sale", +net cash tendered less change handed back) and goes OUT for approved
 * expenses ("expense", −cashPortion) or ad-hoc payouts. Owner-capital-funded expenses
 * create NO row here (cash untouched); the accounts-payable portion of a short-funded
 * expense also creates no cash row — only the cash actually paid drains the drawer.
 *
 * Money handed BACK also drains it: a cash refund payout ("refund", −cash paid back)
 * and change paid out on a balance the shop owed ("change_payout", −amount handed
 * over). Change given at the till is NOT one of these — checkout already books cash in
 * NET of it. Card/mobile-money reversals write no row: they never opened the drawer.
 *
 * TWO LOCATIONS ([location], local-only): the same ledger now answers "how much is in
 * the drawer" and "how much is in the safe" separately, while the SUM over both stays
 * the cash-on-hand every existing caller already reads. Types added for the till/safe
 * model:
 *  - "transfer_out" / "transfer_in" — the two halves of a move between locations. They
 *    are written as a PAIR with equal and opposite amounts, so the combined balance is
 *    provably unchanged (moving your own money is not income).
 *  - "variance" — the close-of-day true-up to the physically counted cash. Signed:
 *    negative = short, positive = over. Reported as a "cash short/over" line against
 *    profit, never folded into gross profit.
 *  - "drawing" — the owner taking money out (equity, NOT an expense: it must not reduce
 *    profit). Mirrors the existing "capital" (owner putting money in).
 *  - "loan" — outside borrowed money coming in (a liability to repay, not income).
 *
 * Append-only and immutable: a correction is a new "adjust" row, never an edit. That is
 * also why the PULL only ever inserts: there is nothing on one of these a later write may
 * legitimately change except the tombstone.
 *
 * SYNCED, both ways, as the cloud's `cash_movements` keyed on [id] — there is no
 * `cash_txns` table on the shared schema and there never was.
 *
 * [location] has NO cloud column: up there, TILL / SAFE is DERIVED from `type`, whose
 * vocabulary is a CHECK-constrained seven. So a row going out is translated by
 * [cashMovementTypeToWire] and a row coming in is translated back by
 * [cashMovementTypeFromWire], which restores the pocket AND the sign the wire dropped
 * (`amount` travels as a magnitude). The trip is lossy on [type] — a "drawing" goes up as
 * a bare `pay_out` — which is precisely why a row already held locally is never re-typed
 * from the wire: see [com.portionspot.pos.sync.wire.toCashTxn].
 */
@Entity(tableName = "cash_txns", indices = [Index("businessId")])
data class CashTxn(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,
    val businessId: String,
    // sale | expense | purchase | refund | change_payout | credit_payment | payout
    // | capital | adjust | transfer_out | transfer_in | variance | drawing | loan
    val type: String,
    val amount: Double = 0.0,             // signed: + into that location, − out of it
    /** LOCAL-ONLY: which on-site location this movement happened in ([CashLocation]). */
    @ColumnInfo(defaultValue = "till") val location: String = CashLocation.TILL,
    val source: String? = null,           // free note of the funding account, if useful
    val note: String? = null,
    val refType: String? = null,          // sale | expense | …
    val refId: String? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** true => has unsynced local edits waiting to push to the cloud. */
    val pendingSync: Boolean = true
)

/**
 * A permanent record of ONE close-of-day count (§2). Written only when the owner presses
 * "Close the day" — never on a timer, never automatically.
 *
 * The counted figure WINS: [countedCash] is what was physically in the drawer, and the
 * close writes a `variance` [CashTxn] for `counted − expected` so the ledger agrees with
 * reality from that moment on. [variance] is kept here too so the history reads without
 * recomputation, and so a repeat offender is visible per cashier ([closedByName]).
 *
 * [movedToSafe] is the excess over [floatTarget] the owner physically moved into the safe
 * as part of the same confirmation; it is recorded in the cash ledger as a matching
 * transfer PAIR, so the till and safe balances both move and the combined total does not.
 *
 * LOCAL-ONLY, and staying that way on purpose. The cloud has no `day_closes` and does not
 * need one: a close is the closing half of the day's [CashSession], so [PosRepository.closeDay]
 * writes the count, the float target and the amount moved to the safe straight onto that
 * row, which does go up. Two cloud answers to "what was the till short on the 8th?" would be
 * one too many. This stays as the device's own fuller record.
 */
/**
 * One trading SHIFT — and a shift here IS A TRADING DAY, opened by the calendar rather
 * than by anyone pressing a button. There are deliberately no open/close shift controls:
 * the period runs from local midnight to local midnight, and the row is created lazily by
 * the first sale of the day. See [planDayRollover] for why, and for the rule that closes
 * a day that has ended before it can block the next one. A day CLOSE fills in the second
 * half of the same row — [countedCash], [expectedCash], [movedToSafe], [floatTarget] —
 * which is why `day_closes` needs no cloud table of its own.
 *
 * ★ ONE OPEN SESSION PER SHOP, NOT PER TILL. Gridline has one physical drawer, so it has
 * exactly one thing to count; the shared database enforces it with a partial unique index
 * on `(business_id) where status = 'open'`. [tillCode] records which DEVICE opened the
 * shift, but it is NOT part of the shift's identity — any device may close it, and closing
 * it closes it for the whole shop.
 *
 * That constraint cannot prevent two offline tills both opening one (each correctly sees
 * no open shift), so the survivor is settled after the fact by [planSessionMerge], whose
 * rule this app and the web POS must implement identically or they will each pick a
 * different winner forever.
 *
 * Distinct from [DayClose], which is the RECORD of a completed count — a session is the
 * period, a day-close is the event that ends one.
 */
@Entity(
    tableName = "cash_sessions",
    indices = [Index("businessId"), Index(value = ["businessId", "status"])]
)
data class CashSession(
    @PrimaryKey override val id: String = newId(),
    val businessId: String,
    override val status: String = SessionStatus.OPEN,
    override val openedAt: Long = now(),
    val openedBy: String? = null,
    val openedByName: String? = null,
    val openingFloat: Double = 0.0,
    val closedAt: Long? = null,
    val closedBy: String? = null,
    val closedByName: String? = null,
    override val countedCash: Double? = null,
    val expectedCash: Double? = null,
    /** Excess takings physically moved into the safe at the close. Mirrors the cloud's
     *  `cash_sessions.moved_to_safe`, so the shared summary carries what [DayClose] does. */
    @ColumnInfo(defaultValue = "0") val movedToSafe: Double = 0.0,
    /** The float left in the till at the close. Mirrors `cash_sessions.float_target`. */
    @ColumnInfo(defaultValue = "0") val floatTarget: Double = 0.0,
    /** Free note. Also where a merged-away shift records what became of it. */
    val note: String? = null,
    /** Which device opened the shift. Recorded, never part of its identity. */
    val tillCode: String? = null,
    val updatedAt: Long = now(),
    override val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud. */
    val pendingSync: Boolean = true
) : DaySessionRow {
    val isOpen: Boolean get() = status == SessionStatus.OPEN && !deleted

    /**
     * The over/short on this shift. DERIVED, never stored — the cloud's `variance` column
     * is GENERATED, so sending one would be rejected, and keeping a second copy on the
     * device is how the two drift apart.
     */
    val variance: Double? get() = countedCash?.let { it - (expectedCash ?: 0.0) }
}

object SessionStatus {
    const val OPEN = "open"
    const val CLOSED = "closed"
}

@Entity(tableName = "day_closes", indices = [Index("businessId"), Index("closedAt")])
data class DayClose(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,             // cloud upsert key, when sync is ever wired
    val businessId: String,
    /** Start-of-day millis for the trading day being closed (grouping key for history). */
    val dayStart: Long,
    /** The till balance the ledger expected before the count. */
    val expectedCash: Double = 0.0,
    /** What was physically counted. This wins — the ledger is trued up to it. */
    val countedCash: Double = 0.0,
    /** counted − expected. Negative = short (a loss), positive = over (a gain). */
    val variance: Double = 0.0,
    /** Excess physically moved from the till into the safe at this close. */
    val movedToSafe: Double = 0.0,
    /** The float target in force for this close (what was left in the till). */
    val floatTarget: Double = 0.0,
    /** Required when |variance| exceeds the admin's threshold; free text otherwise. */
    val note: String? = null,
    val closedBy: String? = null,
    val closedByName: String? = null,
    val closedAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push (nothing pushes it yet). */
    val pendingSync: Boolean = true
)

/**
 * Money that came from OUTSIDE the shop, or went out of it to the owner (§4 + §6).
 * Neither sales nor profit — this is the equity/liability side of the till-and-safe
 * model, kept as its own append-only ledger so "put in" and "borrowed" can be totalled
 * separately and never confused with takings.
 *
 * [kind]:
 *  - "capital" — the OWNER's own money. Money in RAISES what the shop owes the owner;
 *    money out (a drawing) pays some of that back. Never an expense, so it must never
 *    reduce profit.
 *  - "loan"    — money BORROWED from outside. A liability to repay; "out" is a repayment.
 *
 * [direction] is "in" (into the shop) or "out" (back to the owner / lender).
 *
 * A row is written whether or not shop CASH moved: paying a bill straight from outside
 * funds never touches the drawer (and writes no [CashTxn]), while an injection or a
 * drawing does. [refType]/[refId] tie the row to whatever it funded.
 *
 * LOCAL-ONLY but sync-ready ([localId] / [updatedAt] / [pendingSync]); no push/pull is
 * wired — the cloud schema has no `outside_funds`.
 */
@Entity(tableName = "outside_funds", indices = [Index("businessId"), Index("createdAt")])
data class OutsideFund(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,
    val businessId: String,
    val kind: String = "capital",         // capital (owner's own) | loan (must be repaid)
    val direction: String = "in",         // in (into the shop) | out (back to owner/lender)
    val amount: Double = 0.0,             // always POSITIVE; [direction] carries the sign
    val source: String? = null,           // "Owner", a lender's name, …
    val note: String? = null,
    val refType: String? = null,          // expense | purchase_order | cash
    val refId: String? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push (nothing pushes it yet). */
    val pendingSync: Boolean = true
)

/** Running totals of outside money (read model for the owner's "put in / taken out"). */
data class OutsideFundTotals(
    val capitalIn: Double = 0.0,
    val capitalOut: Double = 0.0,
    val loanIn: Double = 0.0,
    val loanOut: Double = 0.0
) {
    /** What the shop still owes the owner: put in less taken out. */
    val ownerNet: Double get() = capitalIn - capitalOut
    /** Borrowings still outstanding: taken less repaid. */
    val loanOutstanding: Double get() = loanIn - loanOut
}

/**
 * One completed sale reduced to the RAW INGREDIENTS of its margin (read model, see
 * [SaleDao.observeSaleMargins]). Aggregated per sale in SQL; the allocation that turns
 * these into a profit lives in [computeSaleMargin], which is pure and unit-tested.
 *
 * Nothing here is a figure to show a user. Call [margin] first.
 */
data class SaleMarginRow(
    val id: String,
    val soldAt: Long,
    val total: Double,
    val taxTotal: Double,
    val amountPaid: Double,
    val customerId: String?,
    /** Σ `unitPrice*qty − lineDiscount + lineMarkup` over every live line. */
    val allLinesNet: Double,
    /** The same, over lines carrying a cost. */
    val costedLinesNet: Double,
    /** Σ `cost × unitsPerLine × qty` over those costed lines. */
    val costedLinesCost: Double
)

/** This sale's margin — the whole-sale discount shared pro-rata with the costed lines.
 *  `total − taxTotal` is the VAT-exclusive money billed; VAT is never earned. */
fun SaleMarginRow.margin(): SaleMargin = computeSaleMargin(
    allLinesNet = allLinesNet,
    costedLinesNet = costedLinesNet,
    costedLinesCost = costedLinesCost,
    saleNetTake = total - taxTotal
)

/**
 * One completed sale reduced to exactly what CASH-BASIS revenue/profit needs (read
 * model, see [CashBasis]), so the Kotlin side can do the FIFO repayment attribution
 * that SQL cannot express.
 *
 *  - [total]         = the VAT-INCLUSIVE money billed. The denominator every ratio in
 *    [CashBasis] is taken against, and the same basis a refund's `refundTotal` is on.
 *  - [taxTotal]      = the VAT inside [total]. Carried so VAT can be pro-rated on the
 *    SAME ratio as everything else instead of being left inside a "revenue" figure it
 *    was never part of — VAT is ZIMRA's money, never the shop's.
 *  - [costedRevenue] = the costed lines' take, after their pro-rata share of the
 *    whole-sale discount. VAT-EXCLUSIVE, like everything out of [computeSaleMargin].
 *  - [lineProfit]    = that take less the cost of those goods.
 *    So the cost of goods on those lines is exactly `costedRevenue − lineProfit`.
 */
data class CashBasisSaleRow(
    val id: String,
    val soldAt: Long,
    val total: Double,
    val taxTotal: Double,
    val amountPaid: Double,
    val customerId: String?,
    val costedRevenue: Double,
    val lineProfit: Double
)

/** Same sale, seen as [CashBasis] needs it — margin resolved once, by the one function. */
fun SaleMarginRow.toCashBasisRow(): CashBasisSaleRow {
    val m = margin()
    return CashBasisSaleRow(
        id = id,
        soldAt = soldAt,
        total = total,
        taxTotal = taxTotal,
        amountPaid = amountPaid,
        customerId = customerId,
        costedRevenue = m.costedRevenue,
        lineProfit = m.profit
    )
}

/**
 * One live refund reduced to exactly what CASH-BASIS recognition needs (read model, see
 * [RefundDao.observeCashBasisRefunds]) so [CashBasis] can reverse the sale it undoes
 * WITHOUT the sale row ever being edited.
 *
 *  - [at] is the refund's own creation time. The reversal is booked HERE, never back on
 *    the day the sale was rung up: that day may already carry a [DayClose] whose counted
 *    short/over was computed against the old figure, and restating it would invalidate a
 *    count a human physically performed.
 *  - [refundTotal] is on the SAME basis as [CashBasisSaleRow.total] — VAT- and
 *    discount-inclusive — because [computeRefundTotal] builds it as
 *    `returnedGoods / saleGoodsValue × saleTotal`. That is what makes
 *    `refundTotal / sale.total` the share of the GOODS that came back.
 *
 * Voided refunds are tombstoned, and the query filters them, so they never appear here.
 */
data class CashBasisRefundRow(
    val id: String,
    val saleId: String,
    val at: Long,
    val refundTotal: Double
)

/**
 * A goods supplier / vendor. Purchase orders denormalise the supplier's name onto
 * each PO, so a tombstoned supplier never breaks history. SYNCED: the cloud
 * `suppliers` table upserts on [localId] (= the Android UUID).
 */
@Entity(tableName = "suppliers", indices = [Index("businessId")])
data class Supplier(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,             // cloud upsert key (defaults to id)
    val businessId: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** true => has unsynced local edits waiting to push to the cloud `suppliers` table. */
    val pendingSync: Boolean = true
)

/**
 * A purchase order (restock request to a [Supplier]). SYNCED: the cloud
 * `purchase_orders` table upserts on [localId] (= the Android UUID).
 * Lifecycle: draft → placed → (partial) → received (or cancelled). [supplierName] is
 * denormalised so deleting a supplier never orphans PO history. [ref] is the human code
 * `PO-YYMMDD-NNNN`.
 *
 * B4 accounting — buying stock is a CASH → INVENTORY conversion, NOT a profit-reducing
 * expense: [cashPaid] drains the drawer via a `cash_txns` "purchase" row (never an
 * `expenses` row, so it never hits the derived net-profit line — the goods only affect
 * profit later through cost-of-goods-sold when they sell). [capitalPaid] is the owner
 * funding it out of pocket (cash untouched); [payableRemainder] is the unpaid balance
 * recorded as ACCOUNTS PAYABLE to the supplier. [eta] is the rough expected-arrival date
 * that drives the "has it arrived?" prompt; [arrivalPromptedAt] dedupes that prompt.
 */
@Entity(tableName = "purchase_orders", indices = [Index("businessId")])
data class PurchaseOrder(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,               // cloud upsert key (defaults to id)
    val businessId: String,
    val ref: String,
    val supplierId: String? = null,
    val supplierName: String = "",
    val status: String = "draft",          // draft | placed | partial | received | cancelled
    val notes: String? = null,
    val eta: Long? = null,                  // rough expected-arrival date (epoch ms)
    val cashPaid: Double = 0.0,             // paid now from cash-on-hand (drains the drawer)
    val capitalPaid: Double = 0.0,          // owner covered out of pocket (cash untouched)
    val payableRemainder: Double = 0.0,     // unpaid balance → accounts payable to supplier
    val arrivalPromptedAt: Long? = null,    // last time the arrival prompt was raised (dedupe)
    val createdAt: Long = now(),
    val sentAt: Long? = null,
    val receivedAt: Long? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** true => has unsynced local edits waiting to push to `purchase_orders`. */
    val pendingSync: Boolean = true
)

/**
 * One line on a [PurchaseOrder]. [itemId] links to the catalog [Item] by UUID
 * (the Android catalog keys on UUID, not the web's sku) so receiving can find the
 * product to restock; [name]/[sku] are snapshotted for display. [qty] is ordered
 * quantity in units; [receivedQty] is filled in as the PO is received.
 *
 * B4: [sellPrice] is the optional intended retail price for the incoming goods (used to
 * seed a brand-new product's price on arrival). [stockOnArrival] flags whether this line
 * should be reflected as PENDING stock and moved into sellable stock when it arrives (a
 * consumable / non-inventory line can be off). [productType] seeds a new product's type.
 */
@Entity(tableName = "purchase_order_items", indices = [Index("poId")])
data class PurchaseOrderLine(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,               // cloud upsert key (defaults to id)
    val poId: String,
    val itemId: String? = null,
    val name: String = "",
    val sku: String? = null,
    val qty: Double = 1.0,
    val unitCost: Double = 0.0,
    val sellPrice: Double? = null,
    val stockOnArrival: Boolean = true,
    val productType: String = "piece",
    val receivedQty: Double? = null,
    /** true => has unsynced local edits waiting to push to `purchase_order_items`. */
    val pendingSync: Boolean = true
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
 * The shared schema's word for a purchase order's state.
 *
 * `purchase_orders.status` is CHECK-constrained on the cloud to exactly
 *
 *     draft · sent · received · cancelled
 *
 * and this app writes two words that are not on that list: `placed` (the order has gone
 * to the supplier) and `partial` (some of it has arrived). A CHECK violation fails the
 * WHOLE upsert batch rather than the offending row, so one PO in a state the cloud has
 * never heard of would take every other PO in the push down with it.
 *
 * Both collapse to `sent`, which is what the shared schema calls "ordered, not yet fully
 * received". Anything unrecognised falls back to `draft` instead of travelling as-is: an
 * unknown value is precisely the one that would be illegal, and a PO that arrives on the
 * other client as a draft is visibly wrong, where a rejected batch is silently missing.
 */
fun purchaseOrderStatusToWire(localStatus: String): String =
    when (localStatus.trim().lowercase()) {
        "draft" -> "draft"
        "sent", "placed", "partial" -> "sent"
        "received" -> "received"
        "cancelled", "canceled" -> "cancelled"
        else -> "draft"
    }

/**
 * The local word for a purchase order's state, given what the wire says and what this
 * device already believed.
 *
 * The translation is LOSSY in one direction — `placed` and `partial` both go up as
 * `sent` — so coming back it has to be told what it is landing on. A half-received order
 * whose local status is `partial` KEEPS it when the wire says `sent`; without that, every
 * pull would quietly reset it to `placed` and the shop could receive the same goods a
 * second time, restocking stock that is already on the shelf.
 *
 * An unrecognised wire value keeps whatever the device had rather than inventing a state:
 * a PO whose status we cannot read is not evidence that it went back to draft.
 */
fun purchaseOrderStatusFromWire(wireStatus: String, localStatus: String?): String =
    when (wireStatus.trim().lowercase()) {
        "sent" -> if (localStatus?.trim()?.lowercase() == "partial") "partial" else "placed"
        "draft" -> "draft"
        "received" -> "received"
        "cancelled", "canceled" -> "cancelled"
        else -> localStatus?.ifBlank { null } ?: "draft"
    }

/** Exactly the 8-4-4-4-12 hex shape Postgres will accept for a `uuid` column. */
private val UUID_SHAPE =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * [raw] if it really is a uuid, else null.
 *
 * Several cloud columns this app fills are `uuid` while the local field beside them is a
 * free String — `purchase_orders.supplier_id` most of all, which can hold anything a
 * caller put there. Postgres rejects a value that is not a uuid outright and takes the
 * whole batch with it, so one that cannot be one is sent as NULL instead: losing a link
 * is recoverable and visible, losing every row in the push is neither.
 *
 * `java.util.UUID.fromString` is deliberately NOT used — it accepts short forms like
 * "1-1-1-1-1" that Postgres also accepts but that no row on either side ever means.
 */
fun uuidOrNull(raw: String?): String? = raw?.trim()?.takeIf { UUID_SHAPE.matches(it) }

/**
 * A parsed mobile-money confirmation SMS (prompt §6 — the flagship reconciliation
 * feature). One row per incoming payment message (EcoCash primarily; OneMoney,
 * InnBucks, Omari and bank alerts add by a parser RULE, not new columns).
 *
 * Idempotency: [txnCode] is the provider's unique transaction code and is the
 * dedupe key — a unique index on (businessId, txnCode) plus an insert-ignore means
 * the same SMS (redelivered, or read again) can never be recorded twice (§6.6).
 *
 * Lifecycle ([status]):
 *  - `unmatched`         — parsed, no customer found by phone; awaits manual assignment.
 *  - `needs_verification`— matched a customer by phone; awaits the cashier confirming
 *                          what the money is for (§6.1–6.2).
 *  - `verified`          — the cashier applied it (to a debt, a sale, or just logged it);
 *                          [appliedCreditTxnId]/[appliedSaleId] link where it went.
 *  - `ignored`           — dismissed (not a real payment / duplicate handled manually).
 *
 * Attribution: [createdBy]/[createdByName] are stamped from the last-unlocked cashier's
 * cached session AT arrival (offline included), so a passive receipt still has an owner.
 * Local-first: carries [pendingSync] but cloud push is deferred to the sync-parity phase.
 */
@Entity(
    tableName = "mobile_money_receipts",
    indices = [
        Index("businessId"),
        Index(value = ["businessId", "txnCode"], unique = true)
    ]
)
data class MobileMoneyReceipt(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val provider: String = "unknown",     // ecocash | onemoney | innbucks | omari | bank | unknown
    val rawBody: String = "",              // the original SMS text (audit / re-parse)
    val sender: String? = null,           // SMS originating address (e.g. "EcoCash")
    val senderName: String? = null,        // payer name parsed from the body, if present
    val senderPhone: String? = null,       // payer number parsed from the body, if present
    val amount: Double = 0.0,
    val currency: String = "USD",
    val txnCode: String,                   // provider's unique reference — the idempotency key
    val receivedAt: Long = now(),          // device time the SMS arrived
    val status: String = "unmatched",      // unmatched | needs_verification | verified | ignored
    val matchedCustomerId: String? = null,
    val matchedCustomerName: String? = null,
    val purpose: String? = null,           // debt | sale (what the cashier said it was for)
    val appliedCreditTxnId: String? = null, // the credit_paid / change_owed row it produced
    val appliedSaleId: String? = null,
    val note: String? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val serverCreatedAt: Long? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. Never sent to cloud yet. */
    val pendingSync: Boolean = true
)

/**
 * A persisted admin notification (prompt §8, Phase 7). The [NotificationEngine]
 * recomputes the shop's alert state on a schedule and upserts rows here keyed by a
 * stable [dedupeKey], so a recurring condition (a low-stock item, an owed refund)
 * is ONE row that updates — never a fresh duplicate each cycle.
 *
 * Read-state ([readAt]) and push-state ([pushedAt]) live on the row: the admin sees
 * unread badges; [pushedAt] stops a system notification firing twice for the same
 * escalation. [eventAt] is the UNDERLYING event time (a refund's creation, a payment's
 * arrival) so the N-hour escalation in §8 is measured from when it really happened,
 * not from when the engine noticed.
 *
 * SYNCED (multi-device): a cashier phone's alert must reach the admin's phone, so rows
 * upsert to the cloud `notifications` table on the COMPOSITE key
 * `(business_id, dedupe_key)` — never on id, because two devices computing the same
 * condition mint different local ids but the SAME dedupeKey and must converge on one
 * cloud row. [readAt] is synced (read on one device = read everywhere).
 * [pushedAt] is DEVICE-LOCAL and never sent or overwritten by a pull: it records
 * whether THIS phone already fired its own heads-up notification.
 */
@Entity(
    tableName = "notifications",
    indices = [Index("businessId"), Index(value = ["businessId", "dedupeKey"], unique = true)]
)
data class AppNotification(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val category: String,                 // inventory | sales | system | payments | refunds
    val severity: String = "info",        // info | warn | danger
    val title: String,
    val body: String,
    val dedupeKey: String,                // stable natural key: recompute updates this row
    /** Who this alert is FOR: "admin" | "cashier" | "all". Drives BOTH which device
     *  fires a system heads-up (a cashier phone must not buzz for an admin-only alert)
     *  and where its deep-link lands. SYNCED so every phone agrees on the target; a
     *  legacy/foreign row with no value reads as "admin" (the historical behaviour). */
    val audience: String = "admin",
    val refType: String? = null,          // sale | refund | item | customer | mm_receipt | device
    val refId: String? = null,
    val eventAt: Long = now(),            // underlying event time (drives escalation age)
    val createdAt: Long = now(),          // first seen
    val readAt: Long? = null,             // null => unread (SYNCED)
    /** DEVICE-LOCAL: when THIS phone last fired a system notification for the row.
     *  Never pushed, never overwritten by a pull — each device pushes for itself. */
    val pushedAt: Long? = null,
    val updatedAt: Long = now(),          // last local edit; drives last-write-wins
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. */
    val pendingSync: Boolean = true
)

/**
 * One immutable audit-trail entry (prompt §8 "full audit log"). Written whenever an
 * admin/cashier performs a sensitive action — voiding a refund, adjusting stock,
 * overriding a price, locking a payment method, writing off a debt. Append-only.
 * Attribution ([createdBy]/[createdByName]) is stamped at write time.
 *
 * SYNCED (one-way in effect): the trail is mirrored to the cloud `audit_log` so the
 * owner's admin phone sees receipt edits, till shortages/overages and voids recorded
 * on the cashier phones. Because the cloud table has SELECT/INSERT/DELETE policies but
 * deliberately NO UPDATE policy (an audit trail must not be rewritable), the push uses
 * ignore-duplicates and a pulled row that already exists locally is skipped, never
 * overwritten. [updatedAt] exists only to feed the `updated_at=gt.<cursor>` pull cursor
 * and always equals [createdAt] for a row this device wrote.
 */
@Entity(tableName = "audit_log", indices = [Index("businessId")])
data class AuditEntry(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    val action: String,                   // void_refund | stock_adjust | payment_lock | debt_writeoff | …
    val entityType: String? = null,
    val entityId: String? = null,
    val summary: String,
    val meta: String? = null,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: Long = now(),
    /** Mirrors [createdAt] — the row is immutable; this only drives the pull cursor. */
    val updatedAt: Long = createdAt,
    /** Local-only: true => still waiting to go up. */
    val pendingSync: Boolean = true
)

/**
 * A remote approval request from a cashier to the admin (Phase 3, admin⇄cashier
 * channel). A cashier who hits an admin-gated action (an over-threshold discount, a
 * void, a price override…) can, instead of entering an admin PIN on the till, RAISE a
 * request that lands in the admin's Alerts feed on the OTHER phone; the admin approves
 * or denies it and the decision rides back.
 *
 * SYNCED via the cloud `staff_requests` table, whose RLS is the whole reason the sync is
 * split into two modes (see PosSyncEngine.push):
 *   • staff may INSERT + SELECT, ONLY an admin may UPDATE/DELETE.
 *   • A cashier's freshly-created PENDING row therefore goes up INSERT-ONCE (ignore-
 *     duplicates on local_id) — a merge upsert would trip the UPDATE policy and be
 *     rejected every cycle.
 *   • Only an admin ever writes a DECIDED row ([decidedAt] != null), so decided rows go
 *     up merge-upsert (the admin passes the UPDATE policy). The engine keys off
 *     status/[decidedAt] alone, so it never has to know the device's role.
 *   • No remote cancel in v1: a cashier can't UPDATE a pushed row, so a stale pending
 *     request is superseded by creating a NEW one and letting the admin deny the old.
 *
 * [applied] = the approved action was actually consumed by the requester (e.g. the
 * cashier tapped "apply" and the discount went onto the still-open sale). It is a
 * DEVICE-LOCAL nicety on the cashier side: the cashier can't UPDATE the cloud row, so a
 * cashier-side apply is set WITHOUT [pendingSync] and never pushed (cloud `applied`
 * stays false unless an admin device writes it). Mirroring it up is a nice-to-have, not
 * a correctness field.
 */
@Entity(
    tableName = "staff_requests",
    indices = [Index("businessId"), Index("status"), Index("requestedBy")]
)
data class StaffRequest(
    @PrimaryKey val id: String = newId(),
    val localId: String = id,             // cloud upsert key (defaults to id, like Expense/Supplier)
    val businessId: String,
    val type: String,                     // discount | void | price_override | … (free-form v1)
    val targetType: String? = null,       // sale | item | customer | … (what the request is about)
    val targetId: String? = null,
    val targetName: String? = null,       // snapshot for display (brief cart summary, item name…)
    val amount: Double? = null,           // the discount / override amount, when the request carries one
    val note: String? = null,
    val requestedBy: String? = null,      // cashier auth uuid
    val requestedByName: String? = null,  // display-name snapshot
    val status: String = "pending",       // pending | approved | denied
    val decidedBy: String? = null,        // admin auth uuid
    val decidedByName: String? = null,
    val decidedAt: Long? = null,          // when the admin decided (drives the push partition)
    /** DEVICE-LOCAL on the cashier side: the approved action was consumed. Set without
     *  [pendingSync] on a cashier device (RLS blocks the cashier updating a decided row);
     *  an admin device may write it and push. */
    val applied: Boolean = false,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local-only: true => has unsynced local edits to push. */
    val pendingSync: Boolean = true
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
