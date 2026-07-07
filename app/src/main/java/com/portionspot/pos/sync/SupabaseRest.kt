package com.portionspot.pos.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of a one-off connection check, so the UI can give a useful message. */
sealed class ConnectionTest {
    object Ok : ConnectionTest()                       // reachable + tables present
    object TablesMissing : ConnectionTest()            // reachable but setup script not run
    object Unauthorized : ConnectionTest()             // bad/expired anon key
    data class Failed(val message: String) : ConnectionTest()
}

/**
 * Thin PostgREST client over OkHttp. We deliberately avoid the heavyweight
 * supabase-kt SDK: with no login (anon key only) the REST surface we need is
 * tiny — test, pull-since, and upsert.
 *
 * All calls are blocking; the engine runs them on [kotlinx.coroutines.Dispatchers.IO].
 */
class SupabaseRest(
    private val baseUrl: String,
    private val anonKey: String,
    /** Signed-in user's JWT; RLS needs it — the anon key alone can do nothing. */
    private val accessToken: () -> String? = { null },
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun rest(table: String) = "$baseUrl/rest/v1/$table"

    fun test(): ConnectionTest {
        val url = (rest("products").toHttpUrlOrNull()
            ?: return ConnectionTest.Failed("That doesn't look like a valid URL"))
            .newBuilder()
            .addQueryParameter("select", "sku")
            .addQueryParameter("limit", "1")
            .build()
        return try {
            client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> ConnectionTest.Ok
                    resp.code == 401 || resp.code == 403 -> ConnectionTest.Unauthorized
                    resp.code == 404 -> ConnectionTest.TablesMissing
                    else -> {
                        val body = resp.body?.string().orEmpty()
                        if (body.contains("does not exist", true) || body.contains("PGRST205"))
                            ConnectionTest.TablesMissing
                        else ConnectionTest.Failed("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            ConnectionTest.Failed(e.message ?: "Could not reach the database")
        }
    }

    /** GET rows where `updated_at > cursor`, oldest-first, capped at [limit]. */
    fun selectSince(table: String, cursor: String, limit: Int): String {
        val url = (rest(table).toHttpUrlOrNull() ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("updated_at", "gt.$cursor")
            .addQueryParameter("order", "updated_at.asc")
            .addQueryParameter("limit", limit.toString())
            .build()
        client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("pull $table: HTTP ${resp.code} $body")
            return body
        }
    }

    /**
     * Upsert a JSON array of rows, conflict-resolving on [onConflict] (the PK).
     * [ignoreDuplicates] = true skips existing rows entirely (matches the web's
     * `ignoreDuplicates` on the append-only `sales` table) instead of overwriting.
     */
    fun upsert(table: String, jsonArray: String, onConflict: String, ignoreDuplicates: Boolean = false) {
        val url = (rest(table).toHttpUrlOrNull() ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("on_conflict", onConflict)
            .build()
        val resolution = if (ignoreDuplicates) "ignore-duplicates" else "merge-duplicates"
        val req = Request.Builder().url(url)
            .post(jsonArray.toRequestBody(jsonMedia))
            .authed()
            .header("Prefer", "resolution=$resolution,return=minimal")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw IOException("push $table: HTTP ${resp.code} $body")
            }
        }
    }

    private fun Request.Builder.authed() = this
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${accessToken() ?: anonKey}")
        .header("Accept", "application/json")
}
