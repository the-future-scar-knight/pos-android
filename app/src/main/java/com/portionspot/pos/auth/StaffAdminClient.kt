package com.portionspot.pos.auth

import com.portionspot.pos.sync.Connection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    private fun bearer(): String = accessToken() ?: connection.anonKey

    private fun Request.Builder.authed() = this
        .header("apikey", connection.anonKey)
        .header("Authorization", "Bearer ${bearer()}")
        .header("Accept", "application/json")

    /** All staff (admin JWT + RLS gate this). Empty on any error. */
    fun listStaff(): List<StaffRow> {
        val url = "${connection.url}/rest/v1/pos_staff" +
            "?select=id,role,display_name,active&order=display_name.asc"
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

    fun createCashier(email: String, password: String, displayName: String, role: String = "cashier"): StaffResult =
        callFn(
            "{\"action\":\"create\"," +
                "\"email\":${q(email)},\"password\":${q(password)}," +
                "\"displayName\":${q(displayName)},\"role\":${q(role)}}"
        )

    fun setActive(staffId: String, active: Boolean): StaffResult =
        callFn("{\"action\":\"set_active\",\"staffId\":${q(staffId)},\"active\":$active}")

    fun resetPassword(staffId: String, password: String): StaffResult =
        callFn("{\"action\":\"reset_password\",\"staffId\":${q(staffId)},\"password\":${q(password)}}")

    private fun callFn(body: String): StaffResult = try {
        val req = Request.Builder()
            .url("${connection.url}/functions/v1/create-cashier")
            .post(body.toRequestBody(jsonMedia))
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { json.decodeFromString<FnResponse>(text) }.getOrNull()
            if (parsed?.ok == true) StaffResult.Ok()
            else StaffResult.Err(parsed?.error ?: "Failed (HTTP ${resp.code})")
        }
    } catch (e: Exception) {
        StaffResult.Err(e.message ?: "Could not reach the server")
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Serializable
    private data class FnResponse(val ok: Boolean = false, val error: String? = null)
}
