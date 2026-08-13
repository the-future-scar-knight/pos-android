package com.portionspot.pos.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * The identity rule for item attributes — the one part of this feature that is hard.
 *
 * A tag's id is NOT random. It is a UUID **version 5** derived from what the tag MEANS:
 *
 *     uuidV5(ATTR_NAMESPACE, businessId ‖ US ‖ itemId ‖ US ‖ canon(key) ‖ US ‖ canon(value))
 *
 * Two phones, both offline, both tagging the same part "Honda Fit" therefore compute the
 * SAME id, and the sync upserts them onto one row instead of creating two tags for someone
 * to merge by hand afterwards. It also makes a re-add free: removing a tag tombstones the
 * row rather than dropping it, and adding it back derives the same id again, so the upsert
 * simply flips `deleted` off. With random ids that second add is a duplicate — and against
 * the live partial unique index
 *
 *     uq_item_attributes_live (business_id, item_id, key_norm, value_norm) WHERE NOT deleted
 *
 * a duplicate is a 23505 that fails the WHOLE push batch, taking every table queued behind
 * it down with it. Deterministic ids are what stop that from ever being reachable.
 *
 * ★ The web client computes the same id from the same inputs (`src/lib/attributes.js`), and
 * the two MUST agree character for character. If they ever drift nothing errors — the shop
 * just quietly grows two rows for every tag, and it is miserable to diagnose after the
 * fact. `AttributeIdTest` pins the vector both sides share; do not change the namespace,
 * the separator, the field order or the canonicalisation without changing both clients and
 * re-iding every attribute ever written.
 *
 * Deliberately free of Android and Room imports so it can be tested as plain Kotlin.
 */

/** Fixed namespace. Changing it re-ids every attribute ever created — never do it. */
const val ATTR_NAMESPACE = "6f1c0b3e-8a2d-5f47-9c31-2b7a4d6e8f10"

/**
 * Field separator: U+001F, the ASCII unit separator.
 *
 * Chosen because nobody can type it. A printable separator would let a key of `car|Honda`
 * with value `x` collide with key `car` and value `|Honda` — two different tags, one id.
 *
 * Spelled as a code point rather than as an escape inside a string literal: a bare
 * control character in source survives a copy-paste or an encoding change only by luck,
 * and this one byte decides whether this client and the web agree about every tag.
 */
const val ATTR_SEPARATOR_CODE = 0x1F

val ATTR_SEPARATOR: String = Char(ATTR_SEPARATOR_CODE).toString()

private val ATTR_NAMESPACE_UUID: UUID = UUID.fromString(ATTR_NAMESPACE)

/**
 * The deterministic id for one (business, item, key, value) tag.
 *
 * [attrNorm] is applied to the key and the value for the id ONLY — the row keeps exactly
 * what the owner typed, for display. That is what makes "  Honda   Fit " and "honda fit"
 * one tag rather than three, and it is the same fold the database applies to its generated
 * `key_norm` / `value_norm` columns.
 *
 * [businessId] and [itemId] go in RAW: they are uuids both clients already agree on
 * exactly, and folding them would only add a way to be wrong.
 */
fun attributeId(businessId: String, itemId: String, key: String, value: String): String =
    uuidV5(
        ATTR_NAMESPACE_UUID,
        listOf(businessId, itemId, attrNorm(key), attrNorm(value)).joinToString(ATTR_SEPARATOR)
    ).toString()

/**
 * RFC 4122 version 5 (SHA-1) UUID over `namespace ‖ name`.
 *
 * ★ Written out by hand because the JDK does not have this. [UUID.nameUUIDFromBytes] is
 * version **3** (MD5) — it returns a perfectly valid-looking uuid that disagrees with the
 * web's v5 for every single input, forever. Reaching for it is the one mistake in this file
 * that would raise no error anywhere and duplicate every tag in the shop.
 *
 * The namespace enters as its 16 raw bytes, most-significant first (network order), which
 * is the only representation both languages' uuid libraries agree on; the name enters as
 * UTF-8. The version nibble and the variant bits are then stamped into the digest, per
 * §4.3 of the RFC.
 */
fun uuidV5(namespace: UUID, name: String): UUID {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(namespace.toBytes())
    md.update(name.toByteArray(StandardCharsets.UTF_8))
    val h = md.digest()
    h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()  // version 5
    h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()  // RFC 4122 variant
    var hi = 0L
    for (i in 0..7) hi = (hi shl 8) or (h[i].toLong() and 0xFF)
    var lo = 0L
    for (i in 8..15) lo = (lo shl 8) or (h[i].toLong() and 0xFF)
    return UUID(hi, lo)
}

/** The 16 bytes of a uuid, most-significant first — the form the digest is fed. */
private fun UUID.toBytes(): ByteArray {
    val out = ByteArray(16)
    var msb = mostSignificantBits
    var lsb = leastSignificantBits
    for (i in 7 downTo 0) { out[i] = (msb and 0xFF).toByte(); msb = msb ushr 8 }
    for (i in 15 downTo 8) { out[i] = (lsb and 0xFF).toByte(); lsb = lsb ushr 8 }
    return out
}
