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
    /** GET selected [columns] for every row of [table] (no cursor). Small lookups only. */
    fun selectAll(table: String, columns: String): String {
        val url = (rest(table).toHttpUrlOrNull() ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("select", columns)
            .build()
        client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("select $table: HTTP ${resp.code} $body")
            return body
        }
    }

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

    // ── Storage (product images) ──────────────────────────────────────────────
    private fun storage(bucket: String, objectPath: String) =
        "$baseUrl/storage/v1/object/$bucket/$objectPath"

    /**
     * Upload [bytes] to `<bucket>/<objectPath>`, overwriting any existing object
     * (`x-upsert: true`). Returns true on success. RLS on storage.objects must allow
     * the signed-in staff user to write the bucket. Throws on transport failure so the
     * caller can decide whether to retry (the sync engine catches + leaves it pending).
     */
    fun uploadObject(bucket: String, objectPath: String, bytes: ByteArray, contentType: String): Boolean {
        val url = storage(bucket, objectPath).toHttpUrlOrNull() ?: throw IOException("Bad URL")
        val req = Request.Builder().url(url)
            .post(bytes.toRequestBody(contentType.toMediaType()))
            .authed()
            .header("x-upsert", "true")
            .header("Content-Type", contentType)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                throw IOException("upload $bucket/$objectPath: HTTP ${resp.code} $body")
            }
            return true
        }
    }

    /** Best-effort delete of a storage object; false on any non-success (never throws). */
    fun deleteObject(bucket: String, objectPath: String): Boolean {
        val url = storage(bucket, objectPath).toHttpUrlOrNull() ?: return false
        return try {
            client.newCall(Request.Builder().url(url).delete().authed().build()).execute()
                .use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** Public URL for an object in a PUBLIC bucket (no auth needed to read). */
    fun publicUrl(bucket: String, objectPath: String): String =
        "$baseUrl/storage/v1/object/public/$bucket/$objectPath"

    private fun Request.Builder.authed() = this
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${accessToken() ?: anonKey}")
        .header("Accept", "application/json")
}
