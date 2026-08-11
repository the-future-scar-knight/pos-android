package com.portionspot.pos.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fixed set of gated capabilities a staff member can be granted. Each maps to a
 * boolean key inside `staff.permissions` (a jsonb column on the shop's Supabase).
 *
 * An ADMIN implicitly has ALL of these and is NEVER gated (see [PosUser.can]). For a
 * CASHIER a capability is granted only when the permissions map says true; a missing
 * key falls back to [Permissions.CASHIER_DEFAULTS].
 *
 * ── TWO VOCABULARIES, ONE COLUMN ──────────────────────────────────────────────
 *
 * The web POS gates SEVEN capabilities, named in camelCase: `refunds`, `discounts`,
 * `credit`, `priceOverride`, `parking`, `quotes`, `stockAdjust`. This app gates sixteen,
 * named in snake_case, and the seven overlap. [wireKey] carries the web's spelling for
 * the ones that are the same capability wearing a different name; the nine this app
 * gates alone have no [wireKey] and the web simply never asks about them.
 *
 * ★ ABSENCE MEANS DIFFERENT THINGS ON THE TWO SIDES, and that is settled, not pending.
 * The web treats a missing key as ALLOWED (so that adding a capability to the shared
 * vocabulary can't silently lock out every existing cashier). This app treats a missing
 * key as [Permissions.CASHIER_DEFAULTS], and most of those are DENIED, because a
 * cashier who has never been granted refunds should not have them.
 *
 * Both readings are defensible and they disagree on the same row, so we do not rely on
 * either: [Permissions.toWireMap] writes every key EXPLICITLY, true or false. A reader
 * on either side then gets the same answer without having to infer anything from what
 * isn't there. Inference is what the disagreement was about.
 */
enum class Capability(val key: String, val wireKey: String? = null) {
    VOID_SALES("void_sales"),
    PROCESS_REFUNDS("process_refunds", wireKey = "refunds"),
    EDIT_RECEIPTS("edit_receipts"),
    GIVE_DISCOUNTS("give_discounts", wireKey = "discounts"),
    MANAGE_INVENTORY("manage_inventory", wireKey = "stockAdjust"),

    /**
     * Ring a sale up for MORE of a tracked item than the shop's figure says is on the shelf.
     *
     * Deliberately NOT folded into [MANAGE_INVENTORY]: that grant is about correcting the
     * catalogue's numbers, this one is about selling past them, and an owner can reasonably
     * want either without the other — the cashier who may count a shelf is not automatically
     * the cashier who may sell a shelf into deficit, and the cashier who must be able to
     * serve a customer from an un-booked-in delivery does not thereby need the stock editor.
     * One switch for both would make those four positions two.
     *
     * A grant rather than a block because the shop really does sell stock the system has not
     * caught up with — a delivery not yet booked in, a count that drifted. Refusing the sale
     * protects a number at the cost of a customer standing at the counter, so the default is
     * to WARN with the figures and let the cashier proceed on purpose. The movement is
     * written in full either way; what the owner gets back is a visible negative and a stock
     * take, not a quietly-lost sale.
     *
     * No [wireKey]: the web gates seven capabilities and this is not one of them, so there is
     * nothing on that side to round-trip with. If the web ever names it, its spelling goes
     * here and the shared vocabulary joins up — same as the four adopted below.
     */
    SELL_BELOW_STOCK("sell_below_stock"),
    MANAGE_EXPENSES_ORDERS("manage_expenses_orders"),
    VIEW_REPORTS("view_reports"),
    MANAGE_STAFF("manage_staff"),

    // ── The four the web gates and this app did not ──
    // Added so the shared vocabulary round-trips whole. Without them, an owner who
    // revoked `credit` or `priceOverride` on the web would see the switch flip back on
    // next push, because this device would have written the column without those keys.
    /** Ring a sale up on account. The customer's credit LIMIT is enforced separately and
     *  is the real control; this grant is about whether the cashier may extend terms at all. */
    SELL_ON_CREDIT("sell_on_credit", wireKey = "credit"),
    /** Change a line's price at the counter — the discretion an owner most often wants held back. */
    PRICE_OVERRIDE("price_override", wireKey = "priceOverride"),
    /** Park a cart and come back to it. No money moves. */
    PARK_SALES("park_sales", wireKey = "parking"),
    /** Write a quote. No money moves and no stock leaves. */
    MAKE_QUOTES("make_quotes", wireKey = "quotes"),

    // ── Cash (§ till & safe). Split three ways rather than one "manage cash" grant,
    // because these carry very different risk: counting the drawer at closing is the
    // routine end of a shift, while money LEAVING the business is not a cashier's call
    // at all — TAKE MONEY OUT has no capability on purpose and stays admin-only.
    /** Count the till, record the variance and move the day's excess to the safe. */
    CLOSE_DAY("close_day"),
    /** Refill the till float. Drawing from the SAFE still needs the admin's per-withdrawal
     *  approval through the existing staff_requests channel, so this grant lets a cashier
     *  ASK and top up from what is already to hand — it does not open the safe. */
    TOP_UP_FLOAT("top_up_float"),
    /** Record cash put INTO the business (owner funds, a loan). Only ever increases what
     *  the shop holds and what it owes; the matching way out is admin-only. */
    RECORD_MONEY_IN("record_money_in");

    /** Short human label for the admin permission editor. */
    val label: String
        get() = when (this) {
            VOID_SALES -> "Void sales & refunds"
            PROCESS_REFUNDS -> "Process refunds"
            EDIT_RECEIPTS -> "Edit receipts"
            GIVE_DISCOUNTS -> "Give discounts"
            MANAGE_INVENTORY -> "Manage inventory"
            SELL_BELOW_STOCK -> "Sell past on-hand stock"
            MANAGE_EXPENSES_ORDERS -> "Expenses & purchase orders"
            VIEW_REPORTS -> "View reports & dashboard"
            MANAGE_STAFF -> "Manage staff"
            SELL_ON_CREDIT -> "Sell on credit"
            PRICE_OVERRIDE -> "Override prices"
            PARK_SALES -> "Park sales"
            MAKE_QUOTES -> "Write quotes"
            CLOSE_DAY -> "Close the day"
            TOP_UP_FLOAT -> "Top up the till float"
            RECORD_MONEY_IN -> "Put money in"
        }

    /**
     * The shop-wide lock that can veto this capability for EVERYONE but the admin, named
     * as the `businesses` column that holds it. Null where the web has no shop-wide lock
     * for it — those capabilities are per-staff only.
     */
    val shopLockColumn: String?
        get() = when (this) {
            PROCESS_REFUNDS -> "lock_refunds"
            GIVE_DISCOUNTS -> "lock_discounts"
            SELL_ON_CREDIT -> "lock_credit"
            PRICE_OVERRIDE -> "lock_price_override"
            PARK_SALES -> "lock_parking"
            MAKE_QUOTES -> "lock_quotes"
            MANAGE_INVENTORY -> "lock_stock_adjust"
            else -> null
        }

    companion object {
        /**
         * Resolve a key written by EITHER client. The web writes `refunds`, this app
         * writes `process_refunds`, and both name the same capability — a reader that
         * understood only its own spelling would silently ignore the other side's
         * revocation, which is the worst possible failure for a permission.
         */
        fun fromKey(key: String): Capability? {
            val k = key.trim()
            return entries.firstOrNull { it.key == k }
                ?: entries.firstOrNull { it.wireKey == k }
        }
    }
}

/**
 * A staff member's granted capabilities, parsed from `staff.permissions`.
 *
 * Stored/round-tripped as a plain `Map<String, Boolean>` keyed by [Capability.key], so
 * it serialises cleanly into the encrypted session vault ([CachedAuth]) and survives
 * offline until the next online refresh.
 */
data class Permissions(val granted: Map<Capability, Boolean> = emptyMap()) {

    /** Is [cap] granted? Falls back to the cashier default when the map is silent.
     *  `getOrElse` rather than `getValue`: a capability added to the enum but forgotten
     *  in [CASHIER_DEFAULTS] must fail CLOSED, not throw in the middle of a sale. */
    fun allows(cap: Capability): Boolean =
        granted[cap] ?: CASHIER_DEFAULTS[cap] ?: false

    /** The full effective map (every capability resolved), for persistence / the editor. */
    fun asKeyMap(): Map<String, Boolean> =
        Capability.entries.associate { it.key to allows(it) }

    /**
     * What goes in `staff.permissions` — EVERY capability stated explicitly, under BOTH
     * spellings where the web has one of its own.
     *
     * Nothing is left to be inferred from absence, because the two clients infer opposite
     * things from it (see [Capability]'s header). Writing `{"refunds": false}` says the
     * same thing to both; writing nothing says "denied" here and "allowed" there, about
     * the same cashier, on the same row.
     *
     * The duplication is deliberate and cheap: a handful of extra jsonb keys buys a column
     * neither side can misread. The web ignores the snake_case keys it doesn't know, and
     * [fromJson] reads either spelling back.
     */
    fun toWireMap(): Map<String, Boolean> = buildMap {
        for (cap in Capability.entries) {
            val allowed = allows(cap)
            put(cap.key, allowed)
            cap.wireKey?.let { put(it, allowed) }
        }
    }

    /**
     * [toWireMap] as the jsonb TEXT the local `staff.permissions` column holds, so a row
     * mirrored onto this device after an admin write reads back exactly as the row pulled
     * from the cloud would. Kept next to [toWireMap] because the two must never disagree
     * about what a written grant looks like.
     */
    fun toWireJson(): String =
        toWireMap().entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    companion object {
        /**
         * Cashier defaults applied when `staff.permissions` is empty/missing (a
         * freshly-created cashier). Money-sensitive capabilities are OFF by default;
         * only day-to-day inventory work is ON. The admin can flip any of these per
         * staff member from the staff console.
         */
        val CASHIER_DEFAULTS: Map<Capability, Boolean> = mapOf(
            Capability.VOID_SALES to false,
            Capability.PROCESS_REFUNDS to false,
            Capability.EDIT_RECEIPTS to false,
            Capability.GIVE_DISCOUNTS to false,
            Capability.MANAGE_INVENTORY to true,
            // ★ ON by default. This is a WARNING, not a permission the shop has to hand out
            // before it can trade: the cashier is shown the on-hand and what they are about
            // to sell and has to confirm. Off by default would mean every shop that never
            // opens the staff console cannot serve a customer from a delivery it hasn't
            // booked in yet — stopping real trade to protect a number that is already wrong.
            // The owner who wants the counter held to the recorded figure turns it off, per
            // cashier, and then it is a hard refusal.
            Capability.SELL_BELOW_STOCK to true,
            Capability.MANAGE_EXPENSES_ORDERS to false,
            Capability.VIEW_REPORTS to false,
            Capability.MANAGE_STAFF to false,
            // The four adopted from the web's vocabulary. Same test as the rest: does it
            // move money or exercise discretion the owner wants to keep?
            //  - selling on credit does create debt, but it is how this shop trades with
            //    its account customers, and the customer's credit LIMIT is the real control
            //    (going over it already needs approval). Off by default would stop ordinary
            //    trade every day to prevent something the limit already prevents.
            //  - overriding a price is exactly the discretion an owner holds back.
            //  - parking and quoting move no money and take no stock.
            Capability.SELL_ON_CREDIT to true,
            Capability.PRICE_OVERRIDE to false,
            Capability.PARK_SALES to true,
            Capability.MAKE_QUOTES to true,
            // ★ ON by default, unlike the other money-touching grants. The shop's whole
            // reason for wanting these is that a cashier could not shut up shop without
            // phoning the owner to come and do it — a default of false would leave every
            // existing cashier exactly as stuck until the owner remembered to go and flip
            // three switches. None of the three can move money OUT of the business: the
            // close records a count, the float top-up shuffles cash the shop already
            // holds (and still needs approval to open the safe), and money in only ever
            // adds. Each stays revocable per cashier from the staff console.
            Capability.CLOSE_DAY to true,
            Capability.TOP_UP_FLOAT to true,
            Capability.RECORD_MONEY_IN to true,
        )

        val EMPTY = Permissions(emptyMap())

        private val json = Json { ignoreUnknownKeys = true }

        /** Build from a stored/keyed map (session vault, or the editor). Unknown keys ignored. */
        fun fromKeyMap(map: Map<String, Boolean>): Permissions =
            Permissions(map.mapNotNull { (k, v) -> Capability.fromKey(k)?.let { it to v } }.toMap())

        /**
         * Parse a `permissions` jsonb object as returned by PostgREST. Null/absent ⇒ empty.
         *
         * One row can legitimately carry a capability under BOTH spellings (this app writes
         * both; the web writes only its own). If the two ever disagree — an older web write
         * saying `refunds: true` next to a newer `process_refunds: false`, or the reverse —
         * the RESTRICTIVE value wins. Guessing wrong in the permissive direction hands a
         * cashier a capability the owner revoked; guessing wrong the other way makes them
         * ask. Only one of those is recoverable in a shop.
         */
        fun fromJson(obj: JsonObject?): Permissions {
            if (obj == null) return EMPTY
            val parsed = mutableMapOf<Capability, Boolean>()
            for ((k, v) in obj) {
                val cap = Capability.fromKey(k) ?: continue
                val b = v.jsonPrimitive.booleanOrNull ?: continue
                parsed[cap] = parsed[cap]?.and(b) ?: b
            }
            return Permissions(parsed)
        }

        /** Parse a raw jsonb string (defensive; used if a caller only has the text). */
        fun fromJsonString(raw: String?): Permissions {
            if (raw.isNullOrBlank()) return EMPTY
            return runCatching { fromJson(json.decodeFromString(JsonObject.serializer(), raw)) }
                .getOrDefault(EMPTY)
        }

        /** Convert a jsonb object straight to the stored key-map (for caching in the vault). */
        fun jsonToKeyMap(obj: JsonObject?): Map<String, Boolean> = fromJson(obj).let { p ->
            p.granted.entries.associate { it.key.key to it.value }
        }
    }
}

/**
 * The single capability check the whole app funnels through.
 *
 *     allowed = isAdmin OR (NOT shopLocked AND staffPermitted)
 *
 * Two gates, and a per-staff permission can only ever SUBTRACT. It cannot grant past a
 * shop-wide lock: `{"refunds": true}` against `lock_refunds = true` is still denied. The
 * admin bypasses both — the owner's own rule, and what the web already did for the locks.
 *
 * [shopLocks] is the set of capabilities the shop has locked for everyone (read from the
 * `businesses.lock_*` columns). It defaults to empty so every existing call site keeps
 * its current meaning until the locks are wired through.
 */
/**
 * The capabilities this shop has locked for everyone but the admin — the coarse gate,
 * read off the business profile's `lock_*` switches (mirroring `businesses.lock_*`).
 *
 * Lives here rather than on [com.portionspot.pos.data.Business] so the mapping from a
 * lock column to a [Capability] sits next to [Capability.shopLockColumn], which is the
 * other half of the same table. Adding a lock means touching one file.
 */
fun com.portionspot.pos.data.Business.lockedCapabilities(): Set<Capability> = buildSet {
    if (lockRefunds) add(Capability.PROCESS_REFUNDS)
    if (lockDiscounts) add(Capability.GIVE_DISCOUNTS)
    if (lockCredit) add(Capability.SELL_ON_CREDIT)
    if (lockPriceOverride) add(Capability.PRICE_OVERRIDE)
    if (lockParking) add(Capability.PARK_SALES)
    if (lockQuotes) add(Capability.MAKE_QUOTES)
    if (lockStockAdjust) add(Capability.MANAGE_INVENTORY)
}

fun isCapabilityAllowed(
    isAdmin: Boolean,
    permissions: Permissions,
    cap: Capability,
    shopLocks: Set<Capability> = emptySet()
): Boolean = isAdmin || (cap !in shopLocks && permissions.allows(cap))

/** [isCapabilityAllowed] for a signed-in user — the form most call sites hold. */
fun PosUser.can(cap: Capability, shopLocks: Set<Capability> = emptySet()): Boolean =
    isCapabilityAllowed(isAdmin, permissions, cap, shopLocks)
