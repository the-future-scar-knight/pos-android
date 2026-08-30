package com.portionspot.pos.payments

import com.portionspot.pos.sync.Connection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Outcome of asking Paynow (via the shop's Edge Function) to start a payment. */
sealed interface PaynowInit {
    data class Ok(val reference: String, val browserUrl: String) : PaynowInit
    data class Err(val message: String) : PaynowInit
}

/** Outcome of one poll for a started payment's status. */
sealed interface PaynowPoll {
    data class Ok(val paid: Boolean, val status: String, val paynowReference: String?) : PaynowPoll
    data class Err(val message: String) : PaynowPoll
}

/**
 * Calls the Paynow Edge Functions that live in the SHOP'S OWN Supabase project
 * (same URL + anon key as Cloud sync). The secret Integration Key stays in that
 * project's Function secrets — this client only ever sees URLs and references.
 *
 * All calls block; run them on [kotlinx.coroutines.Dispatchers.IO].
 */
class PaynowClient(private val connection: Connection) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)   // Paynow initiate can be slow
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private fun fn(name: String) = "${connection.url}/functions/v1/$name"

    /**
     * Start a payment of [amount]; returns the reference + URL to show as a QR.
     *
     * No `authemail` is sent. Paynow requires that field to be the MERCHANT's own
     * account email — in test mode only that account may complete the payment —
     * so it is a property of the integration, not of the shop's profile. It lives
     * in the PAYNOW_AUTH_EMAIL Function secret next to the Integration Key, where
     * a cashier cannot edit it into something unpayable.
     */
    fun initiate(amount: Double, businessId: String? = null): PaynowInit {
        val body = buildString {
            append("{")
            append("\"amount\":").append(amount)
            if (!businessId.isNullOrBlank()) append(",\"businessId\":").append(quote(businessId))
            append("}")
        }
        return try {
            val resp = post("paynow-initiate", body)
            val parsed = json.decodeFromString(InitiateResponse.serializer(), resp)
            if (parsed.ok && !parsed.reference.isNullOrBlank() && !parsed.browserUrl.isNullOrBlank()) {
                PaynowInit.Ok(parsed.reference, parsed.browserUrl)
            } else {
                PaynowInit.Err(parsed.error ?: "Paynow could not start the payment")
            }
        } catch (e: Exception) {
            PaynowInit.Err(e.message ?: "Could not reach Paynow")
        }
    }

    /** Poll a started payment once. */
    fun status(reference: String): PaynowPoll {
        val body = "{\"reference\":${quote(reference)}}"
        return try {
            val resp = post("paynow-status", body)
            val parsed = json.decodeFromString(StatusResponse.serializer(), resp)
            if (parsed.ok) {
                PaynowPoll.Ok(parsed.paid, parsed.status ?: "sent", parsed.paynowReference)
            } else {
                PaynowPoll.Err(parsed.error ?: "Could not check the payment")
            }
        } catch (e: Exception) {
            PaynowPoll.Err(e.message ?: "Could not reach Paynow")
        }
    }

    private fun post(fnName: String, body: String): String {
        val req = Request.Builder()
            .url(fn(fnName))
            .post(body.toRequestBody(jsonMedia))
            .header("apikey", connection.anonKey)
            .header("Authorization", "Bearer ${connection.anonKey}")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // The functions return JSON (with ok/error) even on 4xx, so pass the
            // body through; only throw when there's nothing usable to parse.
            if (text.isBlank()) throw java.io.IOException("Empty response (HTTP ${resp.code})")
            return text
        }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Serializable
    private data class InitiateResponse(
        val ok: Boolean = false,
        val reference: String? = null,
        @SerialName("browserUrl") val browserUrl: String? = null,
        val error: String? = null
    )

    @Serializable
    private data class StatusResponse(
        val ok: Boolean = false,
        val status: String? = null,
        val paid: Boolean = false,
        @SerialName("paynowReference") val paynowReference: String? = null,
        val error: String? = null
    )
}
