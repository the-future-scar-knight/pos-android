package com.portionspot.pos.sync

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Raised instead of signing a request with the anon key when this device HAS a cloud
 * session but no usable token for it.
 *
 * An anon-signed write is rejected by row-level security (Postgres 42501) on every
 * table, which reads to the owner like a data/permissions problem while the app still
 * looks signed in — and the rows just pile up unsynced. Failing the pass outright keeps
 * the cause visible ("sign in again") and, because nothing reaches its `markSynced`,
 * every queued row stays queued exactly as it does offline.
 *
 * It extends [IOException] so the sync engine treats it as an ordinary transport
 * failure: the pass reports Failed and retries later.
 */
class SessionExpiredException : IOException("Session expired — sign in again")

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
    /**
     * The identity to sign requests with. Three-valued on purpose (see [authed]):
     *  - a JWT   → the signed-in user; RLS needs it, the anon key alone can do nothing.
     *  - `null`  → this device has NO cloud session (local/phone-only mode, or the
     *              unauthenticated connection probe), so the anon key IS the intended
     *              identity and the request goes out as-is.
     *  - [SESSION_UNAVAILABLE] → a session EXISTS but no token can be produced for it.
     *              The request is refused rather than downgraded.
     */
    private val accessToken: () -> String? = { null },
) {

    companion object {
        /**
         * Sentinel the token provider returns for "a cloud session exists on this device
         * but we cannot produce a token for it right now". Deliberately not a plausible
         * JWT and not a legal HTTP header value, so it can never be sent by accident.
         */
        const val SESSION_UNAVAILABLE = "\u0000pos-session-unavailable"

        /**
         * The catalogue table — proves the project is reachable and the key is accepted.
         *
         * ★ It is `items`, NOT `products`. The web POS owns this schema and its catalogue
         * has always been `items`; `products` never existed there. Probing for a table the
         * shared database does not have is how a till ends up believing the database "needs
         * setting up" and standing up a second, parallel catalogue nobody else can see.
         */
        private const val PROBE_TABLE = "items"

        /**
         * A COLUMN probe, not a table one — the schema moves by columns now, not by whole
         * tables. `sales.profit_total` is the newest thing Android depends on (the settled
         * money model); a database that predates it answers "column does not exist", which
         * is precisely the out-of-date case the owner needs told about. Bump this whenever
         * Android starts depending on a newer column.
         */
        private const val PROBE_TABLE_NEWEST = "sales"
        private const val PROBE_COLUMN_NEWEST = "profit_total"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun rest(table: String) = "$baseUrl/rest/v1/$table"

    /**
     * Is the database ready? Two probes, because they answer different questions:
     * [PROBE_TABLE] proves the project is reachable and the key is accepted, and
     * [PROBE_COLUMN_NEWEST] proves the schema is CURRENT. Without the second, a database
     * that predates the shared money model reports Ok and then fails on every push with
     * a raw Postgres error instead of an answer the owner can act on.
     *
     * ★ Android never repairs what it finds. The web POS owns this schema; if a probe
     * comes back missing, the fix is to migrate the WEB, not to have a till create the
     * table itself. A till that stands up its own copy produces two catalogues that
     * both work and never see each other, which is worse than a refusal because a
     * refusal tells you.
     */
    fun test(): ConnectionTest {
        val core = probeTable(PROBE_TABLE, "id")
        if (core !is ConnectionTest.Ok) return core
        return probeTable(PROBE_TABLE_NEWEST, PROBE_COLUMN_NEWEST)
    }

    private fun probeTable(table: String, column: String): ConnectionTest {
        val url = (rest(table).toHttpUrlOrNull()
            ?: return ConnectionTest.Failed("That doesn't look like a valid URL"))
            .newBuilder()
            .addQueryParameter("select", column)
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
                        // A missing TABLE is 404/PGRST205; a missing COLUMN comes back 400
                        // with Postgres 42703 (or PGRST204 from the schema cache). Both mean
                        // the same thing to the owner — this database is not the one this
                        // build expects — so both land on TablesMissing.
                        val outOfDate = body.contains("does not exist", true) ||
                            body.contains("PGRST205") ||
                            body.contains("PGRST204") ||
                            body.contains("42703")
                        if (outOfDate) ConnectionTest.TablesMissing
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

    /**
     * GET just the `id` column for the rows of [table] whose id is one of [ids].
     * One round-trip for the whole batch — used to confirm an ignore-duplicates push
     * actually landed (that resolution returns 201 even when the server dropped the
     * row as a duplicate, so the HTTP status alone proves nothing).
     */
    fun selectIdsIn(table: String, ids: List<String>): String {
        val list = ids.joinToString(",") { "\"" + it.replace("\"", "") + "\"" }
        val url = (rest(table).toHttpUrlOrNull() ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("select", "id")
            .addQueryParameter("id", "in.($list)")
            .build()
        client.newCall(Request.Builder().url(url).get().authed().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("verify $table: HTTP ${resp.code} $body")
            return body
        }
    }

    /**
     * [onConflict] names the cloud UNIQUE constraint to resolve against. It may be a
     * SINGLE column ("local_id", "sku") or a COMPOSITE key given as comma-separated
     * columns ("business_id,dedupe_key" — the `notifications` table, where two devices
     * computing the same alert must converge on ONE row). Whitespace around the commas
     * is stripped and the joined value is passed through as one `on_conflict` query
     * param, which is exactly the shape PostgREST expects.
     */
    fun upsert(table: String, jsonArray: String, onConflict: String, ignoreDuplicates: Boolean = false) {
        val conflictKey = onConflict.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        val url = (rest(table).toHttpUrlOrNull() ?: throw IOException("Bad URL"))
            .newBuilder()
            .addQueryParameter("on_conflict", conflictKey)
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

    /**
     * Sign the request.
     *
     * The anon key is used ONLY when the provider says this device genuinely has no
     * cloud session (`null`) — local/phone-only mode and the connection probe. It is
     * never a silent stand-in for a session we failed to produce a token for: that used
     * to turn an auth problem into what looked like a data problem (every write refused
     * by RLS with Postgres 42501 while the UI still showed a signed-in user, and the
     * rows piling up unsynced). That case throws [SessionExpiredException] instead, which
     * fails the pass with a nameable cause and leaves every queued row queued.
     */
    private fun Request.Builder.authed(): Request.Builder {
        val token = accessToken()
        if (token == SESSION_UNAVAILABLE) throw SessionExpiredException()
        val builder = this
            .header("apikey", anonKey)
            .header("Accept", "application/json")
        // ★ `Authorization: Bearer` must carry a JWT. Supabase now issues TWO key formats
        // per project — the legacy `anon` key, which IS a JWT, and a newer
        // `sb_publishable_…` key, which is not. Sending the publishable one as a Bearer
        // token makes PostgREST answer:
        //
        //     401 PGRST301  "No suitable key was found to decode the JWT"
        //
        // which reads like a permissions or key-validity problem and sends you looking at
        // RLS and at the wrong project, when the key is perfectly valid and merely in the
        // wrong header. The `apikey` header above is where a publishable key belongs, and
        // it is already set, so the request authenticates correctly once Authorization is
        // simply left off.
        //
        // ★ A JWT is only usable against the project that ISSUED it.
        //
        // This device can hold a session from a database it used to sync with — the app
        // has pointed at more than one Supabase project over its life, and the vault
        // survives a change of connection. Handing that older token to this project makes
        // PostgREST answer 401 PGRST301 "No suitable key was found to decode the JWT",
        // which names the JWT and so reads like a broken key. It is not: the key is fine
        // and the token belongs to somewhere else.
        //
        // A mismatched token is DROPPED rather than sent, and the request falls back to
        // the anon key — which is the correct identity for a device with no valid session
        // here, and which this schema's RLS accepts. Signing in against THIS project
        // replaces it and attribution resumes.
        val projectRef = baseUrl.projectRef()
        val usableToken = token?.takeIf { projectRef == null || it.issuedFor(projectRef) }
        val bearer = usableToken ?: anonKey.takeIf { it.looksLikeJwt() }
        return if (bearer != null) builder.header("Authorization", "Bearer $bearer") else builder
    }

    /** A JWT is three dot-separated base64url segments; Supabase's start `eyJ` (`{"` encoded).
     *  Deliberately a shape check, not a parse — we only need to know which header it belongs in. */
    private fun String.looksLikeJwt(): Boolean =
        startsWith("eyJ") && count { it == '.' } == 2

    /** The project ref out of a Supabase URL — `https://<ref>.supabase.co`. Null for a
     *  self-hosted or otherwise unrecognised host, where the check cannot apply and the
     *  token is given the benefit of the doubt rather than dropped. */
    private fun String.projectRef(): String? {
        val host = toHttpUrlOrNull()?.host ?: return null
        if (!host.endsWith(".supabase.co")) return null
        return host.substringBefore('.').takeIf { it.isNotBlank() }
    }

    /**
     * Was this JWT issued by [projectRef]? Reads the `ref` claim from the payload.
     *
     * The payload is decoded, NOT verified — we are not authenticating anything here, only
     * deciding which of two projects a token belongs to. The server still verifies it.
     * An unreadable payload returns true: a token we cannot parse is sent as before and
     * left for PostgREST to judge, because silently dropping a valid token would break
     * sync for a shape we simply did not anticipate.
     */
    private fun String.issuedFor(projectRef: String): Boolean {
        val payload = split(".").getOrNull(1) ?: return true
        val json = runCatching {
            String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE))
        }.getOrNull() ?: return true
        val ref = Regex("\"ref\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return true
        return ref == projectRef
    }
}
