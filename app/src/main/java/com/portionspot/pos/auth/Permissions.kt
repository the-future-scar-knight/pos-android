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
    MANAGE_STAFF("manage_staff");

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
