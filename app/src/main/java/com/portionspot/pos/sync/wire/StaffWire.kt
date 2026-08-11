package com.portionspot.pos.sync.wire

import com.portionspot.pos.data.StaffMember
import com.portionspot.pos.sync.IsoTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire shape for the shared cloud `staff` table — the roster AND the credential.
 *
 * Verified against the live project rather than recalled:
 *
 *     staff(id uuid pk, business_id uuid, name text, username text,
 *           role text CHECK IN ('admin','manager','cashier'), active boolean,
 *           pin_hash text, permissions jsonb, updated_at timestamptz,
 *           deleted boolean, client_updated_at timestamptz)
 *
 * It lives in its own file rather than in WireDtos.kt because `staff` is not ordinary
 * shop data: it is the login table, and the rules that apply to it are its own.
 *
 * ── THE THREE RULES OF THIS TABLE ────────────────────────────────────────────
 *
 * 1. **`pin_hash` travels; the PIN never does.** The column holds
 *    `pbkdf2$210000$<hex>` and nothing else ever goes near the wire. That is what lets a
 *    cashier created on the web sign in on the phone, and it is why
 *    [com.portionspot.pos.auth.StaffPin] is a port of the web's file rather than an
 *    implementation of the same idea.
 *
 * 2. **A hash belongs to ONE shop.** The salt is derived from `business_id`, so a row
 *    copied between businesses carries a hash that can never match. Nothing here may
 *    ever rewrite `business_id` on a row that already has a `pin_hash`.
 *
 * 3. **Push `client_updated_at`, never `updated_at`** — the same rule as every other
 *    table here. The server's `updated_at` is what every pull cursor reads; a phone with
 *    a skewed clock writing it directly would stamp a row in the future and make every
 *    other device skip everything behind it, silently and permanently.
 */
@Serializable
data class StaffDto(
    val id: String,
    val name: String? = null,
    val username: String? = null,
    val role: String = "cashier",
    val active: Boolean = true,
    @SerialName("pin_hash") val pinHash: String? = null,
    /** Capability grants. Kept as a [JsonObject] so an unknown key survives the trip. */
    val permissions: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val deleted: Boolean = false,
)

/**
 * Fold a pulled row into the local mirror.
 *
 * [local] is the row already on the device, if any. Unlike most tables there is nothing
 * here to preserve from it — every column of `staff` is cloud-owned, including the
 * credential — so this is a straight overwrite. It is still passed and used for the id,
 * because pretending a merge exists when it doesn't is how a "just add one local-only
 * field" change later silently blanks the wrong column.
 *
 * `pendingSync = false`: a row that arrived FROM the cloud has nothing to send back.
 *
 * ★★ [cloudBusinessId] MUST BE THE CLOUD ID, NOT THIS DEVICE'S OWN ★★
 *
 * Every other pull mapper in this package is handed the LOCAL business uuid — the one the
 * device invented on first run — because local rows are keyed by it. This one is the
 * exception, and getting it wrong is silent and total.
 *
 * [StaffPin] derives its salt as `SHA-256("PortionSpot POS pin v1:" + businessId)`, copied
 * from the web so that one `staff.pin_hash` works on both clients. The web salts with the
 * shop's id — the cloud one. Stamp these rows with the device's local uuid instead and
 * every hash this app computes disagrees with every hash the web wrote: no error, no log
 * line, just a correct PIN refused forever on one side, looking exactly like the cashier
 * mistyping it.
 *
 * Named for what it must be rather than documented as a caveat, because the call site is
 * surrounded by `bid` variables that are the other thing.
 */
fun StaffDto.toStaffMember(cloudBusinessId: String, local: StaffMember? = null): StaffMember {
    val base = local ?: StaffMember(id = id, businessId = cloudBusinessId)
    return base.copy(
        id = id,
        businessId = cloudBusinessId,
        name = name.orEmpty(),
        username = username.orEmpty(),
        role = role.trim().ifBlank { "cashier" },
        active = active,
        pinHash = pinHash?.ifBlank { null },
        permissions = permissions?.toString(),
        updatedAt = IsoTime.toMillis(updatedAt),
        deleted = deleted,
        pendingSync = false,
    )
}

/**
 * What an admin device sends UP when it creates or edits a staff member.
 *
 * `pin_hash` is nullable so that editing a person's NAME or PERMISSIONS never has to
 * touch their credential — but note that a null here would blank the column on a merge
 * upsert, so the caller must send the hash it pulled when it isn't changing it. See
 * [StaffMember.toStaffPush], which does exactly that by construction.
 */
@Serializable
data class StaffPushDto(
    val id: String,
    @SerialName("business_id") val businessId: String,
    val name: String,
    val username: String,
    val role: String,
    val active: Boolean,
    @SerialName("pin_hash") val pinHash: String? = null,
    val permissions: JsonObject? = null,
    val deleted: Boolean = false,
    @SerialName("client_updated_at") val clientUpdatedAt: String,
)

/**
 * Local row → push row.
 *
 * The role is CLAMPED to the three the cloud CHECK constraint accepts. A row with any
 * other role would fail the INSERT — and a failed batch takes every other staff member in
 * it down too, so one malformed row would block the whole roster from ever syncing.
 * Anything unrecognised narrows to `cashier`, which is the least powerful thing it could
 * be; a mistake here must never hand someone the admin's till.
 */
fun StaffMember.toStaffPush(cloudBusinessId: String, permissions: JsonObject? = null) = StaffPushDto(
    id = id,
    businessId = cloudBusinessId,
    name = name,
    username = username.trim(),
    role = clampStaffRole(role),
    active = active,
    pinHash = pinHash,
    permissions = permissions,
    deleted = deleted,
    clientUpdatedAt = IsoTime.toIso(updatedAt),
)

/** The roles the cloud `staff_role_check` constraint permits. Anything else is a row the
 *  database will refuse, so it is narrowed here rather than at the server. */
val STAFF_ROLES = listOf("admin", "manager", "cashier")

/** Narrow any role to one the shared schema accepts, defaulting to the least powerful. */
fun clampStaffRole(role: String?): String {
    val r = role?.trim()?.lowercase().orEmpty()
    return if (r in STAFF_ROLES) r else "cashier"
}
