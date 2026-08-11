package com.portionspot.pos.auth

import com.portionspot.pos.sync.Connection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One staff member as the admin console lists them, straight off the shared `staff` row. */
@Serializable
data class StaffRow(
    val id: String,
    val name: String = "",
    val username: String = "",
    val role: String = "cashier",
    val active: Boolean = true,
    /** jsonb capability grants for this staff member; null for a row never edited. */
    val permissions: JsonObject? = null,
    /**
     * `pbkdf2$…`, or null/blank where the owner never set one. Read so the console can SAY
     * "no PIN set" — a cashier who cannot sign in should learn that from the staff list,
     * not from typing four digits and being told the PIN is wrong.
     */
    @SerialName("pin_hash") val pinHash: String? = null,
) {
    /** Parsed capability grants (empty ⇒ cashier defaults apply in the editor). */
    fun perms(): Permissions = Permissions.fromJson(permissions)

    val hasPin: Boolean get() = !pinHash.isNullOrBlank()

    /** What to show when the row has no name yet. */
    val label: String get() = name.ifBlank { username }.ifBlank { "(no name)" }
}

sealed interface StaffResult {
    data class Ok(val message: String = "Done") : StaffResult
    data class Err(val message: String) : StaffResult
}

/**
 * Admin staff management against the shop's own Supabase — plain PostgREST reads and
 * writes on the shared `staff` table.
 *
 * ── WHAT THIS STOPPED DOING ──────────────────────────────────────────────────
 *
 * It used to POST to a `create-cashier` Edge Function that was never deployed, so every
 * create, deactivate and password reset answered 404 and the console silently did
 * nothing. It also read and wrote `pos_staff`, a table the shared schema does not have.
 *
 * There is nothing an Edge Function was needed for any more. A cashier is not a GoTrue
 * user: creating one is an INSERT of a `staff` row whose `pin_hash` this device computes
 * with [StaffPin] — the same function, constant for constant, that the web's `pin.js`
 * uses — so a cashier created here signs in on the web and vice versa. No service-role
 * key is involved and none is needed.
 *
 * ── EVERY WRITE IS A PATCH OF NAMED COLUMNS ──────────────────────────────────
 *
 * Never an upsert of a whole row. `pin_hash` is the credential and `permissions` is the
 * revocation record; a full-row write that happened to carry a stale or absent value for
 * either would blank a cashier's PIN, or hand back a capability the owner had taken away,
 * as a side effect of renaming them.
 *
 * All calls block; run them on [kotlinx.coroutines.Dispatchers.IO].
 */
class StaffAdminClient(private val connection: Connection) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /** The anon key, exactly as every other PostgREST call in this app sends it. There is
     *  no JWT to prefer over it any more — staff sign-in mints no tokens. */
    private fun Request.Builder.authed() = this
        .header("apikey", connection.anonKey)
        .header("Authorization", "Bearer ${connection.anonKey}")
        .header("Accept", "application/json")

    private fun staffUrl(query: String) = "${connection.url}/rest/v1/staff?$query"

    private val columns = "id,name,username,role,active,permissions,pin_hash"

    /**
     * The shop's whole roster, INCLUDING deactivated members — the console is where a
     * deactivation is reversed, so hiding the switched-off rows would hide the only
     * control that brings them back.
     */
    fun list(businessId: String): List<StaffRow> {
        val url = staffUrl("select=$columns&business_id=eq.$businessId&order=name.asc")
        return try {
            client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) emptyList()
                else runCatching { json.decodeFromString<List<StaffRow>>(text) }.getOrDefault(emptyList())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * ONE member's own row, in the three-valued form the revocation path needs.
     *
     * That caller cannot use a nullable row: `null` would have to mean both "200 OK, no
     * such row" and "couldn't ask", and folding those together is exactly how a staff
     * member deleted in the console kept their capability grants forever — the device
     * could not tell the deletion from a dropped signal. Here a reached-server verdict is
     * [StaffProfileFetch.Missing] and everything else — no network, a 4xx, a 5xx, an
     * unparseable body — is [StaffProfileFetch.Unreachable], which the caller must treat
     * as "keep what you have". See [PermissionRefresh].
     *
     * `display_name:name` is a PostgREST column alias: the shared table's column is `name`
     * and [StaffProfileDto] reads `display_name`, so the rename happens in the query rather
     * than in a second DTO that could drift from the first.
     */
    fun fetchProfile(businessId: String, staffId: String): StaffProfileFetch = try {
        val url = staffUrl(
            "select=role,display_name:name,active,permissions" +
                "&business_id=eq.$businessId&id=eq.$staffId"
        )
        client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                StaffProfileFetch.Unreachable
            } else {
                val rows = json.decodeFromString<List<StaffProfileDto>>(text)
                rows.firstOrNull()?.let { StaffProfileFetch.Found(it) } ?: StaffProfileFetch.Missing
            }
        }
    } catch (_: Exception) {
        StaffProfileFetch.Unreachable
    }

    /**
     * Create a staff member. [pinHash] must already be derived for THIS shop — see
     * [StaffPin.hash], which takes the business id because the salt comes from it.
     *
     * `permissions` is written in full on creation rather than left null: the two clients
     * read an absent key oppositely (this app denies, the web allows), so a new cashier
     * with no map would hold different powers depending on which till they walked up to.
     */
    fun create(
        businessId: String,
        id: String,
        name: String,
        username: String,
        role: String,
        pinHash: String?,
        permissions: Map<String, Boolean>,
    ): StaffResult {
        val body = buildString {
            append("{")
            append("\"id\":${q(id)},")
            append("\"business_id\":${q(businessId)},")
            append("\"name\":${q(name)},")
            append("\"username\":${q(username)},")
            append("\"role\":${q(role)},")
            append("\"active\":true,")
            append("\"pin_hash\":${pinHash?.let { q(it) } ?: "null"},")
            append("\"permissions\":${jsonMap(permissions)}")
            append("}")
        }
        return try {
            val req = Request.Builder()
                .url(staffUrl("select=id"))
                .post(body.toRequestBody(jsonMedia))
                .authed()
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) StaffResult.Ok("Cashier added")
                else StaffResult.Err(explain(resp.code, resp.body?.string().orEmpty()))
            }
        } catch (e: Exception) {
            StaffResult.Err(e.message ?: "Could not reach the server")
        }
    }

    /** Rename / re-role a member. Touches those columns and nothing else. */
    fun updateDetails(staffId: String, name: String, username: String, role: String): StaffResult =
        patch(staffId, "{\"name\":${q(name)},\"username\":${q(username)},\"role\":${q(role)}}", "Saved")

    /**
     * Deactivate (the console's "Remove") or reactivate.
     *
     * A soft switch, never a DELETE: the row is referenced by attribution on every sale
     * that person rang up, and removing it would orphan the history the owner keeps them
     * for. `active = false` is also what [PermissionRefresh] revokes on immediately, so
     * this is the control that ends a shift on a phone that is still in someone's hand.
     */
    fun setActive(staffId: String, active: Boolean): StaffResult =
        patch(staffId, "{\"active\":$active}", if (active) "Reactivated" else "Removed")

    /** Set or replace a member's PIN. [pinHash] is derived for this shop by [StaffPin]. */
    fun setPinHash(staffId: String, pinHash: String): StaffResult =
        patch(staffId, "{\"pin_hash\":${q(pinHash)}}", "PIN set")

    /**
     * Overwrite one member's capability grants. [permissions] is a full key→boolean map
     * (see [Permissions.toWireMap], which states every capability under both this app's
     * and the web's spelling so neither side has to infer anything from absence).
     */
    fun setPermissions(staffId: String, permissions: Map<String, Boolean>): StaffResult =
        patch(staffId, "{\"permissions\":${jsonMap(permissions)}}", "Permissions saved")

    private fun patch(staffId: String, body: String, okMessage: String): StaffResult = try {
        val req = Request.Builder()
            .url(staffUrl("id=eq.$staffId"))
            .patch(body.toRequestBody(jsonMedia))
            .authed()
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) StaffResult.Ok(okMessage)
            else StaffResult.Err(explain(resp.code, resp.body?.string().orEmpty()))
        }
    } catch (e: Exception) {
        StaffResult.Err(e.message ?: "Could not reach the server")
    }

    /**
     * Turn a PostgREST failure into something an owner can act on.
     *
     * The two that actually happen here get named. A 42501 is row-level security refusing
     * an anon write, which is a database setup answer and not a "try again" one; a 23514 is
     * the `staff_role_check` constraint, which can only mean a role outside the three the
     * shared schema allows.
     */
    private fun explain(code: Int, body: String): String = when {
        body.contains("42501") || body.contains("row-level security") ->
            "The database refused this change — its staff table doesn't allow writes from the till"
        body.contains("23514") -> "That role isn't one the shared database accepts"
        body.contains("23505") -> "A staff member with that id already exists"
        else -> "Couldn't save (HTTP $code)"
    }

    private fun jsonMap(map: Map<String, Boolean>): String =
        map.entries.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
