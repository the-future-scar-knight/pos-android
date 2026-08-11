package com.portionspot.pos.auth

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The till PIN hash — a **byte-for-byte port of the web POS's `src/lib/pin.js`**.
 *
 * ★ THIS FILE IS A WIRE FORMAT, NOT AN IMPLEMENTATION CHOICE. ★
 *
 * The `staff` table is shared: the shop owner creates a cashier on the web, and that
 * cashier walks up to the phone and signs in. The only thing that connects those two
 * moments is `staff.pin_hash`, so if this file and `pin.js` disagree about a single
 * parameter — the iteration count, the digest, the derived length, the salt seed, even
 * the case of the hex — then a cashier created on the web simply cannot log in on the
 * phone, and one created on the phone cannot log in on the web. There is no error
 * message for that; it presents as "wrong PIN" for a PIN that is not wrong.
 *
 * So every constant below is copied, not chosen, and [StaffPinTest] pins them to hashes
 * produced by actually running the web's `hashPin()` — including one taken straight out
 * of the live `staff` table. Change a constant and those tests fail, which is the point.
 *
 * ── THE PARAMETERS (from pin.js) ─────────────────────────────────────────────
 *
 *   stored format   `pbkdf2$<iterations>$<64 lowercase hex>`
 *   KDF             PBKDF2-HMAC-SHA256
 *   iterations      210,000 (OWASP's current recommendation)
 *   derived length  256 bits / 32 bytes
 *   salt            SHA-256("PortionSpot POS pin v1:" + businessId)
 *
 * The salt is DERIVED from the business id rather than stored per row. That is the web's
 * deliberate trade and this side has no say in it: one derivation per sign-in attempt
 * instead of one per staff member, which is what keeps a PIN pad responsive with twenty
 * accounts on file (see [checker]). The consequence is worth stating plainly — **a hash
 * is only valid inside its own shop**. The same PIN under a different `business_id`
 * derives a completely different hash, which is why [hash] and [matches] both take one.
 *
 * ── WHAT THIS BUYS AND WHAT IT DOES NOT ──────────────────────────────────────
 *
 * A 4-digit PIN is 10,000 possibilities and no hash makes that space large. The only
 * defence is making each guess expensive, which 210k iterations does (~100ms a try).
 * Treat the PIN as what it is — a "who is at the counter" check — not as a secret. The
 * plain PIN is never stored and never leaves the device it was typed on.
 *
 * ── WHY NOT `SecretKeyFactory` ───────────────────────────────────────────────
 *
 * `PBKDF2WithHmacSHA256` only exists from API 26. The shop's Sunmi V1s is API 23 (this
 * app's minSdk), where that factory throws `NoSuchAlgorithmException` — it would crash
 * sign-in on the one device that matters most. `Mac("HmacSHA256")` ships on every API
 * level and RFC 8018 over it is byte-identical, which is the same reasoning (and the
 * same loop) as [SessionVault.pbkdf2].
 *
 * ★ [SessionVault]'s PIN is a DIFFERENT MECHANISM and must not be confused with this
 * one. That one is a device-unlock PIN: random per-device salt, 120k iterations, base64,
 * never leaves the phone. This one is the shop-wide staff credential. They can hold
 * different values for the same person and that is correct.
 */
object StaffPin {

    /** OWASP's current PBKDF2-HMAC-SHA256 recommendation, and what `pin.js` writes. */
    const val ITERATIONS = 210_000

    /** 256 bits, as `KEY_BITS` in `pin.js`. */
    const val KEY_BYTES = 32

    /** The seed prefix the salt is derived over. Copied verbatim from `pin.js`. */
    const val SALT_PREFIX = "PortionSpot POS pin v1:"

    /** The only scheme this app writes or accepts. */
    const val SCHEME = "pbkdf2"

    /**
     * Hash [pin] for the shop [businessId], in the stored `pbkdf2$<iterations>$<hex>`
     * form. Null for a blank PIN — matching `pin.js`, which returns null rather than
     * hashing an empty string, so "no PIN set" stays distinguishable from "PIN of ''".
     *
     * The PIN is trimmed first, so a stray space from a keypad or a paste cannot lock
     * someone out of an account they set up correctly.
     *
     * Blocking and deliberately slow (~100ms+): call it off the main thread.
     */
    fun hash(pin: String?, businessId: String?): String? {
        val clean = pin?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return format(ITERATIONS, derive(clean, saltFor(businessId), ITERATIONS))
    }

    /**
     * Does [candidate] match [stored] for this shop?
     *
     * Recomputed against the STORED hash's own iteration count, never against
     * [ITERATIONS] — that is how `pin.js` lets the cost be raised later without
     * stranding every account hashed at the old one.
     *
     * Anything that is not a well-formed `pbkdf2$<n>$<hex>` is false rather than an
     * exception: a null, a legacy bcrypt string, a truncated column, a row where the
     * owner never set a PIN. A credential check must have a decision for every input.
     */
    fun matches(candidate: String?, stored: String?, businessId: String?): Boolean {
        val parsed = parse(stored) ?: return false
        val clean = candidate?.trim().orEmpty()
        if (clean.isEmpty()) return false
        val computed = hex(derive(clean, saltFor(businessId), parsed.first))
        return timingSafeEquals(computed, parsed.second)
    }

    /**
     * Hash a candidate ONCE and get back a matcher, so signing in costs one derivation
     * no matter how many staff are on file.
     *
     * Every account in a shop shares the salt (it is derived from the business id), so
     * the same digest can be tested against all of them. Without this, a "type your PIN
     * and we'll find you" pad on a phone with twenty cashiers would spend two full
     * seconds of PBKDF2 per attempt on a Sunmi. Rows hashed at a different cost are
     * memoised per iteration count, exactly as `pinChecker` does.
     *
     * The returned lambda is NOT thread-safe; build one per sign-in attempt.
     */
    fun checker(candidate: String?, businessId: String?): (String?) -> Boolean {
        val clean = candidate?.trim().orEmpty()
        if (clean.isEmpty()) return { false }
        val salt = saltFor(businessId)
        val cache = HashMap<Int, String>()
        return fn@{ stored ->
            val parsed = parse(stored) ?: return@fn false
            val digest = cache.getOrPut(parsed.first) { hex(derive(clean, salt, parsed.first)) }
            timingSafeEquals(digest, parsed.second)
        }
    }

    /** `pbkdf2$<iterations>$<hex>` for an already-derived key. Built by concatenation
     *  rather than a template, because `$` is the template character and a string full
     *  of them is exactly where a silent format change would hide. */
    private fun format(iterations: Int, key: ByteArray): String =
        SCHEME + SEP + iterations + SEP + hex(key)

    /** The field separator in the stored format. */
    private const val SEP = "$"

    /**
     * Split a stored hash into (iterations, hex) or null if it isn't one of ours.
     *
     * Mirrors `pin.js`'s guards one for one: the scheme must be `pbkdf2`, the hex part
     * must be present, and the iteration count must parse to a finite positive number.
     */
    private fun parse(stored: String?): Pair<Int, String>? {
        if (stored.isNullOrBlank()) return null
        val parts = stored.split("$")
        if (parts.size < 3) return null
        if (parts[0] != SCHEME) return null
        val hex = parts[2]
        if (hex.isEmpty()) return null
        val iterations = parts[1].toIntOrNull() ?: return null
        if (iterations <= 0) return null
        return iterations to hex
    }

    /**
     * Shop-specific salt: `SHA-256("PortionSpot POS pin v1:" + businessId)`.
     *
     * A null or missing business id seeds with the empty string, which is what
     * `${SALT_PREFIX}${businessId || ''}` does in JS. That case should never reach a
     * real sign-in — a till that does not yet know which shop it is has nothing to
     * check a PIN against — but the two sides must agree even on the degenerate input,
     * because agreeing "everywhere except one edge" is how a wire format rots.
     */
    private fun saltFor(businessId: String?): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT_PREFIX + (businessId ?: "")).toByteArray(Charsets.UTF_8))

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) over [Mac], for the API-23 reason in the file
     * header. [KEY_BYTES] equals the SHA-256 output length, so this is exactly one
     * block — the loop is kept general anyway so a future longer key stays correct.
     */
    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (KEY_BYTES + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            // U_1 = PRF(salt || INT_32_BE(i)). doFinal() resets the Mac to its post-init
            // state, so the same key is reused for every subsequent PRF.
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (i ushr 24).toByte(), (i ushr 16).toByte(),
                    (i ushr 8).toByte(), i.toByte()
                )
            )
            var u = mac.doFinal()
            val block = u.copyOf()
            for (c in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in block.indices) block[j] = (block[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(block, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(KEY_BYTES)
    }

    /** Lowercase, zero-padded hex — `bytesToHex` in `pin.js`. Case is part of the format. */
    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Compare without short-circuiting on the first differing character.
     *
     * It barely matters here — anyone who can time this already holds the database —
     * but a credential comparison that leaks how much matched is the kind of thing that
     * gets copied somewhere it does matter. `pin.js` does the same.
     */
    private fun timingSafeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
