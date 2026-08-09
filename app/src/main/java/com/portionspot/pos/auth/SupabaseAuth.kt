package com.portionspot.pos.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class AuthUserDto(val id: String, val email: String? = null)

@Serializable
data class SessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: AuthUserDto? = null,
) {
    /** Epoch seconds this access token dies, from the server or derived. */
    fun expiryEpochSeconds(now: Long = System.currentTimeMillis() / 1000): Long =
        expiresAt ?: (now + expiresIn)
}

@Serializable
data class StaffProfileDto(
    val role: String,
    @SerialName("display_name") val displayName: String = "",
    val active: Boolean = true,
    /** jsonb capability grants; null/absent for legacy rows ⇒ cashier defaults apply. */
    val permissions: JsonObject? = null,
)

/** GoTrue error bodies come in two shapes; capture both loosely. */
@Serializable
private data class AuthErrorDto(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
)

sealed class AuthResult {
    data class Success(val session: SessionDto) : AuthResult()

    /** The server actively rejected us (bad credentials / dead refresh token). */
    data class Rejected(val message: String) : AuthResult()

    /** Couldn't reach the server — not a verdict, try again when online. */
    data class Offline(val message: String) : AuthResult()
}

/**
 * Minimal Supabase Auth (GoTrue) + PostgREST client over OkHttp, in the same
 * spirit as [com.portionspot.pos.sync.SupabaseRest]: this app deliberately
 * avoids the heavyweight supabase-kt/Ktor stack — the auth surface we need is
 * just password sign-in, token refresh, sign-out, and one profile read.
 *
 * All calls are blocking; callers run them on Dispatchers.IO.
 */
class SupabaseAuth(
    private val baseUrl: String = SupabaseDefaults.URL,
    private val anonKey: String = SupabaseDefaults.ANON_KEY,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    fun signIn(email: String, password: String): AuthResult =
        tokenGrant("password", buildJsonObject {
            put("email", email)
            put("password", password)
        }.toString())

    fun refresh(refreshToken: String): AuthResult =
        tokenGrant("refresh_token", buildJsonObject {
            put("refresh_token", refreshToken)
        }.toString())

    /** Best-effort server-side sign-out; local state is what actually matters. */
    fun signOut(accessToken: String) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/logout")
                .post(ByteArray(0).toRequestBody(jsonMedia))
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
            // Offline sign-out is fine; the refresh token will expire server-side.
        }
    }

    /**
     * Reads the caller's own `pos_staff` row with THEIR token. RLS only returns
     * a row for active staff, so an empty result means "not authorized for POS"
     * (never signed up as staff, or deactivated by the admin).
     *
     * @throws IOException when the server can't be reached — offline, not a verdict.
     */
    fun fetchStaffProfile(accessToken: String, userId: String): StaffProfileDto? {
        val url = ("$baseUrl/rest/v1/pos_staff".toHttpUrlOrNull()
            ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("id", "eq.$userId")
            .addQueryParameter("select", "role,display_name,active,permissions")
            .build()
        val req = Request.Builder().url(url).get()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("profile: HTTP ${resp.code}")
            val rows = json.decodeFromString<List<StaffProfileDto>>(body)
            return rows.firstOrNull()
        }
    }

    /**
     * The three-valued form of [fetchStaffProfile], for the periodic permission refresh.
     *
     * That caller cannot use the nullable form: `null` there means "200 OK, no row" while
     * an exception means "couldn't ask", and folding both into null is exactly how a
     * staff member deleted in the console kept their capability grants forever — the
     * device could not tell the deletion from a dropped signal. Here a reached-server
     * verdict is [StaffProfileFetch.Missing] and everything else — no network, a 401 on a
     * stale token, a 5xx, an unparseable body — is [StaffProfileFetch.Unreachable], which
     * the caller must treat as "keep what you have".
     */
    fun fetchStaffProfileResult(accessToken: String, userId: String): StaffProfileFetch =
        try {
            val profile = fetchStaffProfile(accessToken, userId)
            if (profile == null) StaffProfileFetch.Missing else StaffProfileFetch.Found(profile)
        } catch (_: Exception) {
            StaffProfileFetch.Unreachable
        }

    private fun tokenGrant(grantType: String, body: String): AuthResult {
        val url = ("$baseUrl/auth/v1/token".toHttpUrlOrNull()
            ?: return AuthResult.Offline("Bad server URL"))
            .newBuilder()
            .addQueryParameter("grant_type", grantType)
            .build()
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(jsonMedia))
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> AuthResult.Success(json.decodeFromString<SessionDto>(text))
                    // A server-side blip or a rate limit is NOT a verdict on the session.
                    // Treating it as one used to be the difference between "retry in a
                    // minute" and telling a working shop to sign in again mid-trade.
                    resp.code == 429 || resp.code >= 500 -> AuthResult.Offline(parseError(text, resp.code))
                    else -> AuthResult.Rejected(parseError(text, resp.code))
                }
            }
        } catch (e: Exception) {
            AuthResult.Offline(e.message ?: "Could not reach the server")
        }
    }

    private fun parseError(body: String, code: Int): String = try {
        val err = json.decodeFromString<AuthErrorDto>(body)
        when {
            err.errorCode == "invalid_credentials" -> "Wrong email or password"
            err.error == "invalid_grant" ->
                err.errorDescription ?: "Session expired — sign in again"
            else -> err.msg ?: err.errorDescription ?: err.error ?: "HTTP $code"
        }
    } catch (_: Exception) {
        "HTTP $code"
    }
}
