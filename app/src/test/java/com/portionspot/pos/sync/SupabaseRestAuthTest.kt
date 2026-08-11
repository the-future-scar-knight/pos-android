package com.portionspot.pos.sync

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the identity contract of [SupabaseRest.authed].
 *
 * The silent-data-loss bug this guards against: a device that HAS a cloud session but
 * momentarily has no token for it used to fall through to `Bearer <anonKey>`. Postgres
 * then refused every write with 42501 ("new row violates row-level security policy"),
 * which reads like a data problem, while the UI still showed a signed-in user and the
 * unsynced rows quietly piled up. That fallback must exist ONLY when the token provider
 * says there is genuinely no session (null).
 *
 * Every assertion below is offline: [SupabaseRest.authed] runs while the request is being
 * BUILT, so the refusal happens before any socket is opened.
 */
class SupabaseRestAuthTest {

    private fun refusing() = SupabaseRest(
        baseUrl = "https://example.invalid",
        anonKey = "anon-key",
        accessToken = { SupabaseRest.SESSION_UNAVAILABLE },
    )

    @Test
    fun upsert_refusesRatherThanSigningWithAnonKey() {
        try {
            refusing().upsert("sales", "[]", "id")
            fail("expected SessionExpiredException instead of an anon-signed write")
        } catch (e: SessionExpiredException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun reads_alsoRefuse() {
        try {
            refusing().selectSince("sales", "biz-1", "1970-01-01T00:00:00.000Z", 10)
            fail("expected SessionExpiredException")
        } catch (_: SessionExpiredException) {
            // expected
        }
        try {
            refusing().selectAll("products", "sku")
            fail("expected SessionExpiredException")
        } catch (_: SessionExpiredException) {
            // expected
        }
    }

    @Test
    fun storageUpload_alsoRefuses() {
        try {
            refusing().uploadObject("product-images", "b/i.jpg", ByteArray(1), "image/jpeg")
            fail("expected SessionExpiredException")
        } catch (_: SessionExpiredException) {
            // expected
        }
    }

    /** The message is surfaced verbatim as the sync status, and must not collide with the
     *  engine's RLS-violation classifier (which keys off "42501" / "row-level security"). */
    @Test
    fun refusal_carriesAnActionableMessage() {
        val message = SessionExpiredException().message.orEmpty()
        assertTrue(message.contains("sign in again", ignoreCase = true))
        assertTrue(!message.contains("42501"))
        assertTrue(!message.contains("row-level security", ignoreCase = true))
    }

    /** The sentinel must be impossible to mistake for — or send as — a credential. */
    @Test
    fun sentinel_isNotAPlausibleBearerValue() {
        val sentinel = SupabaseRest.SESSION_UNAVAILABLE
        assertTrue(sentinel.any { it.code <= 0x1f })
        assertTrue(!sentinel.startsWith("ey"))
    }
}
