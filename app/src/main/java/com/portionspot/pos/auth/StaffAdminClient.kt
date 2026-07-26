package com.portionspot.pos.auth

import com.portionspot.pos.sync.Connection
import com.portionspot.pos.sync.SupabaseRest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One POS staff member as the admin console lists them (from `pos_staff`). */
@Serializable
data class StaffRow(
    val id: String,
    val role: String = "cashier",
    @SerialName("display_name") val displayName: String = "",
    val active: Boolean = true,
    /** jsonb capability grants for this staff member; null for legacy rows. */
    val permissions: JsonObject? = null,
) {
    /** Parsed capability grants (empty ⇒ cashier defaults apply in the editor). */
    fun perms(): Permissions = Permissions.fromJson(permissions)
}

/** One roster entry for the lock-screen "switch by name" picker (email included). */
@Serializable
data class RosterRow(
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val role: String = "cashier",
    val active: Boolean = true,
    /** Backfilled on existing staff; may still be null for a freshly created cashier
     *  until the create-cashier email backfill lands. */
    val email: String? = null,
)

sealed interface StaffResult {
    data class Ok(val message: String = "Done") : StaffResult
    data class Err(val message: String) : StaffResult
}

/**
 * Admin-only staff management against the shop's own Supabase: lists `pos_staff`,
 * and calls the `create-cashier` Edge Function to create / (de)activate cashier
 * logins. The Edge Function holds the service-role key and verifies the CALLER is an
 * admin, so this client just sends the signed-in admin's JWT (never a service key).
 *
 * All calls block; run on [kotlinx.coroutines.Dispatchers.IO].
 */
class StaffAdminClient(
    private val connection: Connection,
    private val accessToken: () -> String?,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    /** The admin console is read/write against `pos_staff`, never a local-row sync, so an
     *  anon fallback here costs a failed request rather than silently-unsynced data. It
     *  must still never forward [SupabaseRest.SESSION_UNAVAILABLE] — that value is a
     *  signal, not a credential. */
    private fun bearer(): String {
        val token = accessToken()
        return if (token.isNullOrBlank() || token == SupabaseRest.SESSION_UNAVAILABLE) {
            connection.anonKey
        } else {
            token
        }
    }

    private fun Request.Builder.authed() = this
        .header("apikey", connection.anonKey)
        .header("Authorization", "Bearer ${bearer()}")
        .header("Accept", "application/json")

    /**
     * Lightweight shop roster for the lock-screen "switch by name" picker:
     * id, display_name, role, active, email. Filtered to active staff. Runs with
     * whatever token is available (falls back to the anon key via [authed]); returns
     * an empty list on any failure or RLS block so the picker degrades to local
     * accounts only.
     */
    fun listRoster(): List<RosterRow> {
        val url = "${connection.url}/rest/v1/pos_staff" +
            "?select=id,display_name,role,active,email&active=eq.true&order=display_name.asc"
        return try {
            client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) emptyList()
                else runCatching { json.decodeFromString<List<RosterRow>>(text) }.getOrDefault(emptyList())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** All staff (admin JWT + RLS gate this). Empty on any error. */
    fun listStaff(): List<StaffRow> {
        val url = "${connection.url}/rest/v1/pos_staff" +
            "?select=id,role,display_name,active,permissions&order=display_name.asc"
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

    fun createCashier(email: String, password: String, displayName: String, role: String = "cashier"): StaffResult {
        val (result, newId) = callFnWithId(
            "{\"action\":\"create\"," +
                "\"email\":${q(email)},\"password\":${q(password)}," +
                "\"displayName\":${q(displayName)},\"role\":${q(role)}}"
        )
        // Best-effort: the Edge Function may not populate pos_staff.email, which would
        // break lock-screen "switch by name" for this new cashier. If it returned the
        // new id, backfill the email directly (admin JWT + RLS admin-update, like
        // setPermissions). If the id isn't in the response, skip — the row's backfill
        // and a later password login via "Add another account" still cover it.
        if (result is StaffResult.Ok && !newId.isNullOrBlank()) {
            runCatching { patchEmail(newId, email) }
        }
        return result
    }

    fun setActive(staffId: String, active: Boolean): StaffResult =
        callFn("{\"action\":\"set_active\",\"staffId\":${q(staffId)},\"active\":$active}")

    fun resetPassword(staffId: String, password: String): StaffResult =
        callFn("{\"action\":\"reset_password\",\"staffId\":${q(staffId)},\"password\":${q(password)}}")

    /**
     * Update one staff member's capability grants directly via PostgREST (no Edge
     * Function needed — RLS policy `pos_staff_admin_update` = is_pos_admin() lets an
     * admin PATCH the row). [permissions] is a full key→boolean map; it overwrites the
     * whole `permissions` jsonb. Uses `Prefer: return=minimal` so no body comes back.
     */
    fun setPermissions(staffId: String, permissions: Map<String, Boolean>): StaffResult {
        val obj = permissions.entries.joinToString(",") { "${q(it.key)}:${it.value}" }
        val body = "{\"permissions\":{$obj}}"
        return try {
            val req = Request.Builder()
                .url("${connection.url}/rest/v1/pos_staff?id=eq.$staffId")
                .patch(body.toRequestBody(jsonMedia))
                .authed()
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) StaffResult.Ok("Permissions saved")
                else StaffResult.Err("Couldn't save permissions (HTTP ${resp.code})")
            }
        } catch (e: Exception) {
            StaffResult.Err(e.message ?: "Could not reach the server")
        }
    }

    private fun callFn(body: String): StaffResult = callFnWithId(body).first

    /** Like [callFn] but also surfaces any staff/user id the function returned (used to
     *  backfill pos_staff.email for a newly created cashier). id is null if absent. */
    private fun callFnWithId(body: String): Pair<StaffResult, String?> = try {
        val req = Request.Builder()
            .url("${connection.url}/functions/v1/create-cashier")
            .post(body.toRequestBody(jsonMedia))
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<FnResponse>(text) }.getOrNull()
            if (parsed?.ok == true) StaffResult.Ok() to parsed.newId()
            else StaffResult.Err(parsed?.error ?: "Failed (HTTP ${resp.code})") to null
        }
    } catch (e: Exception) {
        StaffResult.Err(e.message ?: "Could not reach the server") to null
    }

    /** Best-effort admin PATCH of pos_staff.email (RLS admin-update). Throws on network
     *  failure; the caller wraps it in runCatching so a miss is silent. */
    private fun patchEmail(staffId: String, email: String) {
        val req = Request.Builder()
            .url("${connection.url}/rest/v1/pos_staff?id=eq.$staffId")
            .patch("{\"email\":${q(email)}}".toRequestBody(jsonMedia))
            .authed()
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .build()
        client.newCall(req).execute().close()
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Serializable
    private data class FnResponse(
        val ok: Boolean = false,
        val error: String? = null,
        // The function's response shape isn't guaranteed; accept the common id carriers.
        val id: String? = null,
        @SerialName("userId") val userId: String? = null,
        @SerialName("staffId") val staffId: String? = null,
        val user: FnUser? = null,
    ) {
        fun newId(): String? = id ?: userId ?: staffId ?: user?.id
    }

    @Serializable
    private data class FnUser(val id: String? = null)
}
