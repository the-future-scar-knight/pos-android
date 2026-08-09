package com.portionspot.pos.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fixed set of gated capabilities a staff member can be granted. Each maps to a
 * boolean key inside `pos_staff.permissions` (a jsonb column on the shop's Supabase).
 *
 * An ADMIN implicitly has ALL of these and is NEVER gated (see [PosUser.can]). For a
 * CASHIER a capability is granted only when the permissions map says true; a missing
 * key falls back to [Permissions.CASHIER_DEFAULTS].
 */
enum class Capability(val key: String) {
    VOID_SALES("void_sales"),
    PROCESS_REFUNDS("process_refunds"),
    EDIT_RECEIPTS("edit_receipts"),
    GIVE_DISCOUNTS("give_discounts"),
    MANAGE_INVENTORY("manage_inventory"),
    MANAGE_EXPENSES_ORDERS("manage_expenses_orders"),
    VIEW_REPORTS("view_reports"),
    MANAGE_STAFF("manage_staff"),

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
            MANAGE_EXPENSES_ORDERS -> "Expenses & purchase orders"
            VIEW_REPORTS -> "View reports & dashboard"
            MANAGE_STAFF -> "Manage staff"
            CLOSE_DAY -> "Close the day"
            TOP_UP_FLOAT -> "Top up the till float"
            RECORD_MONEY_IN -> "Put money in"
        }

    companion object {
        fun fromKey(key: String): Capability? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A staff member's granted capabilities, parsed from `pos_staff.permissions`.
 *
 * Stored/round-tripped as a plain `Map<String, Boolean>` keyed by [Capability.key], so
 * it serialises cleanly into the encrypted session vault ([CachedAuth]) and survives
 * offline until the next online refresh.
 */
data class Permissions(val granted: Map<Capability, Boolean> = emptyMap()) {

    /** Is [cap] granted? Falls back to the cashier default when the map is silent. */
    fun allows(cap: Capability): Boolean = granted[cap] ?: CASHIER_DEFAULTS.getValue(cap)

    /** The full effective map (every capability resolved), for persistence / the editor. */
    fun asKeyMap(): Map<String, Boolean> =
        Capability.entries.associate { it.key to allows(it) }

    companion object {
        /**
         * Cashier defaults applied when `pos_staff.permissions` is empty/missing (a
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
            Capability.MANAGE_EXPENSES_ORDERS to false,
            Capability.VIEW_REPORTS to false,
            Capability.MANAGE_STAFF to false,
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

        /** Parse a `permissions` jsonb object as returned by PostgREST. Null/absent ⇒ empty. */
        fun fromJson(obj: JsonObject?): Permissions {
            if (obj == null) return EMPTY
            val parsed = buildMap {
                for ((k, v) in obj) {
                    val cap = Capability.fromKey(k) ?: continue
                    val b = v.jsonPrimitive.booleanOrNull ?: continue
                    put(cap, b)
                }
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
 * The single capability check the whole app funnels through. An admin is never gated;
 * a cashier is allowed only when their cached [PosUser.permissions] grants the cap.
 */
fun PosUser.can(cap: Capability): Boolean = isAdmin || permissions.allows(cap)
