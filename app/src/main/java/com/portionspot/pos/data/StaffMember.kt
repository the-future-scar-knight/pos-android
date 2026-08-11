package com.portionspot.pos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One member of the shop's staff — the local mirror of the SHARED cloud `staff` table.
 *
 * ★ WHY THIS TABLE HAS TO EXIST LOCALLY ★
 *
 * Sign-in is `username` + PIN checked against `pin_hash`, and this is a shop that trades
 * through power cuts and dead 4G cells. If the check needed the network, a cashier could
 * not open the till on the very days it matters most. Because the hash is a hash, holding
 * it on the device costs nothing that holding it in the cloud didn't already cost — the
 * plain PIN is not here and never was. So the rule is: **synced once, signs in forever**.
 *
 * The columns are the cloud's, one for one (`staff` = id, business_id, name, username,
 * role, active, pin_hash, permissions, updated_at, deleted, client_updated_at), because
 * a local shape that "improves" on the shared one is a mapping bug waiting to happen.
 *
 * ── ROLE ─────────────────────────────────────────────────────────────────────
 * The cloud CHECK constraint is `role IN ('admin','manager','cashier')`. Android's
 * capability model only knows admin-or-not ([com.portionspot.pos.auth.PosUser.isAdmin]
 * tests `role == "admin"`), so a `manager` lands on the cashier side of that line and is
 * shaped by their `permissions` grants instead. That is the safe direction: an unknown
 * or unexpected role can only ever mean *fewer* powers, never more.
 *
 * ── PERMISSIONS ──────────────────────────────────────────────────────────────
 * Kept as the RAW jsonb text rather than parsed columns. The vocabulary is shared with
 * the web and has already grown once (see [com.portionspot.pos.auth.Capability]); storing
 * the text means a key this build has never heard of survives a round trip instead of
 * being silently dropped by a device that happens to be older than the grant.
 *
 * ── INDICES ──────────────────────────────────────────────────────────────────
 * `(businessId, username)` is deliberately NOT unique. The cloud has no unique constraint
 * on it (verified: `staff` carries only `staff_pkey` and `staff_role_check`), so two rows
 * really can share a username — and a unique index here would turn that merely-confusing
 * cloud state into a pull that throws, or worse, a REPLACE that deletes one of them. The
 * lookup returns the active row and lets the shop sort out its own duplicate.
 */
@Entity(
    tableName = "staff",
    indices = [
        Index("businessId"),
        Index(value = ["businessId", "username"]),
    ]
)
data class StaffMember(
    @PrimaryKey val id: String = newId(),
    val businessId: String,
    /** Display name — cloud `name`. Stamped on receipts and attribution. */
    val name: String = "",
    /** The sign-in handle — cloud `username`. Compared case-insensitively (see the DAO). */
    val username: String = "",
    /** 'admin' | 'manager' | 'cashier'. */
    val role: String = "cashier",
    val active: Boolean = true,
    /**
     * `pbkdf2$<iterations>$<hex>` as written by [com.portionspot.pos.auth.StaffPin] or by
     * the web's `pin.js` — the two produce identical output for the same PIN and shop.
     * Null where the owner has never set one; such a row cannot sign in.
     */
    val pinHash: String? = null,
    /**
     * ★ The business id [pinHash] was SALTED WITH — which is not always [businessId].
     *
     * This device has two ids for one shop: the local `businesses` row id it generated on
     * first run, and the CLOUD `business_id` it adopts on first connect (see
     * `SyncConfig.cloudBusinessId`). Every other table papers over the difference by
     * stamping pulled rows with the local id, and that is fine for them because nothing
     * about their data depends on which id it is.
     *
     * A PIN hash does. The salt is `SHA-256("PortionSpot POS pin v1:" + business_id)`, so
     * a hash written by the web under the CLOUD id can only ever be verified under the
     * CLOUD id. Re-stamp it with the local one and every correct PIN in the shop reads as
     * wrong, with nothing in the UI to suggest why. So the id the hash belongs to is
     * carried on the row itself instead of being inferred.
     *
     * Blank means "same as [businessId]" — a row created on a till that has no cloud
     * shop yet. If such a till later adopts a cloud business, those PINs must be re-set,
     * because the plain PIN needed to re-derive them was never stored anywhere.
     */
    val pinShopId: String = "",
    /** Raw `permissions` jsonb text, or null. Parsed by `Permissions.fromJsonString`. */
    val permissions: String? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Local edit awaiting push. Rows arriving from a pull land with this false. */
    val pendingSync: Boolean = true,
)

@Dao
interface StaffDao {

    /** The shop roster for the sign-in picker: live staff, alphabetical. */
    @Query(
        "SELECT * FROM staff WHERE businessId = :businessId AND deleted = 0 AND active = 1 " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeRoster(businessId: String): Flow<List<StaffMember>>

    /** The same list for a one-shot read (sign-in runs off the UI thread). */
    @Query(
        "SELECT * FROM staff WHERE businessId = :businessId AND deleted = 0 AND active = 1 " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    suspend fun roster(businessId: String): List<StaffMember>

    /**
     * EVERY row for the shop including deactivated and tombstoned ones — the admin
     * console's list, and the sign-in lookup that has to be able to say "that account is
     * deactivated" rather than "no such user". Telling the two apart is the difference
     * between a cashier who phones the owner and one who thinks they typed it wrong.
     */
    @Query("SELECT * FROM staff WHERE businessId = :businessId ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(businessId: String): List<StaffMember>

    @Query("SELECT * FROM staff WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StaffMember?

    /**
     * Look a person up by what they typed.
     *
     * Case- and space-insensitive on purpose: `LOWER(TRIM(username))`. A cashier typing
     * "Ryan " on a phone keyboard that auto-capitalises is not a different person from
     * `ryan`, and a sign-in that refuses over a capital letter is indistinguishable, to
     * them, from a broken app. Deactivated and tombstoned rows are INCLUDED so the caller
     * can report the real reason; the ordering puts live rows first so a duplicate
     * username resolves to the usable one.
     */
    @Query(
        "SELECT * FROM staff WHERE businessId = :businessId " +
            "AND LOWER(TRIM(username)) = LOWER(TRIM(:username)) " +
            "ORDER BY deleted ASC, active DESC LIMIT 1"
    )
    suspend fun byUsername(businessId: String, username: String): StaffMember?

    @Upsert
    suspend fun upsert(member: StaffMember)

    @Upsert
    suspend fun upsertAll(members: List<StaffMember>)

    // ---- sync ----

    /** Admin-authored rows awaiting push. */
    @Query("SELECT * FROM staff WHERE pendingSync = 1")
    suspend fun pending(): List<StaffMember>

    @Query("UPDATE staff SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM staff WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}
