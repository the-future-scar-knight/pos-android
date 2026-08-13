package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Attribute IDENTITY — the load-bearing part of the tags feature, and the one place where a
 * disagreement between this client and the web produces no error at all.
 *
 * A tag's id is derived from what it MEANS (business, item, canonical key, canonical value)
 * rather than minted at random, so two phones tagging the same part offline compute the same
 * id and the sync merges them onto one row. Get the derivation even slightly wrong and
 * nothing throws: the shop simply grows two rows for every tag, and the live partial unique
 * index `uq_item_attributes_live` starts refusing pushes with a 23505 that takes the whole
 * batch down with it.
 *
 * These tests pin the exact rules the web client (`src/lib/attributes.js`), this client and
 * the Postgres generated columns all have to agree on.
 */
class AttributeIdTest {

    private val biz = "b1"
    private val item = "9f0e4a2c-1111-4222-8333-444455556666"

    // ── the v5 implementation itself ──────────────────────────────────────

    /**
     * The canonical RFC 4122 §4.3 example: v5 of "python.org" in the DNS namespace. Nothing
     * to do with this app — it proves [uuidV5] is a correct version-5 implementation before
     * any attribute-specific vector is trusted, because a wrong one would still be
     * self-consistent and every test below would pass while disagreeing with the web.
     */
    @Test
    fun `uuidV5 matches the published RFC 4122 vector`() {
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(
            "886313e1-3b8a-5372-9b90-0c9aee199e5d",
            uuidV5(dns, "python.org").toString()
        )
    }

    /**
     * THE cross-client vector. Same inputs on the web produce this id; if this assertion
     * ever fails, do not "fix" it here — the two clients have drifted and every tag written
     * since is a duplicate waiting to happen.
     */
    @Test
    fun `the shared attribute vector matches the web`() {
        assertEquals(
            "057a8191-98d2-5c97-a0f5-8240e8901633",
            attributeId(biz, item, "car", "Honda Fit")
        )
    }

    /**
     * ★ The trap this whole file exists to guard.
     *
     * [UUID.nameUUIDFromBytes] is version **3** (MD5) and is the obvious-looking JDK
     * shortcut. Fed the very same namespace and name it returns the id asserted against
     * below — a perfectly well-formed uuid that disagrees with the web for every input,
     * forever, with nothing anywhere to say so.
     */
    @Test
    fun `is version 5, not the JDK's version 3`() {
        val id = attributeId(biz, item, "car", "Honda Fit")
        assertNotEquals("fc2aace5-fa14-34f0-b261-b8b2d2ce1335", id)
        assertEquals(5, UUID.fromString(id).version())
        assertEquals(2, UUID.fromString(id).variant())  // 2 == the RFC 4122 variant
    }

    @Test
    fun `emits a well-formed uuid`() {
        val id = attributeId(biz, item, "car", "Honda Fit")
        assertTrue(
            id,
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(id)
        )
    }

    // ── canonicalisation, and its equivalence with the web ────────────────

    /**
     * The web folds with `replace(/\s+/g,' ').trim().toLowerCase()`; [attrNorm] trims first
     * and then collapses. The order differs and the RESULT must not: collapsing first turns
     * a leading run into one space that the trim then removes, and trimming first leaves
     * nothing at the ends for the collapse to find. The database's generated columns fold
     * the same way — `lower(btrim(regexp_replace(v, '\s+', ' ', 'g')))` — so all three
     * agree on what "the same tag" is.
     */
    @Test
    fun `attrNorm folds exactly like the web's canonical`() {
        assertEquals("honda fit", attrNorm("  Honda   Fit "))
        assertEquals("honda fit", attrNorm("HONDA FIT"))
        assertEquals("honda fit", attrNorm("honda fit"))
        assertEquals("", attrNorm("   "))
        assertEquals("", attrNorm(""))
    }

    /** Tabs and newlines are whitespace to all three sides, so they collapse too. */
    @Test
    fun `attrNorm treats tabs and newlines as whitespace, like the database does`() {
        assertEquals("honda fit", attrNorm("Honda\tFit"))
        assertEquals("honda fit", attrNorm("Honda\nFit"))
        assertEquals("honda fit", attrNorm("Honda \r\n\tFit"))
    }

    /**
     * A KNOWN, DELIBERATE divergence, recorded so nobody "fixes" it into a bug.
     *
     * A non-breaking space (U+00A0) is not whitespace to Java's `\s` or to Kotlin's `trim`,
     * and it is not whitespace to Postgres's `[[:space:]]` either — so this client and the
     * database agree, and the web (whose JS `\s` DOES match it) is the odd one out. Making
     * Kotlin match the web here would break the agreement with the generated columns, which
     * is the one that decides whether a push is accepted. Nobody types U+00A0 on a phone;
     * if it ever matters, the fix belongs in the database and the web together.
     */
    @Test
    fun `a non-breaking space is not whitespace here, matching Postgres`() {
        val nbsp = Char(0x00A0)
        assertEquals("honda${nbsp}fit", attrNorm("Honda${nbsp}Fit"))
    }

    // ── the identity properties the sync depends on ───────────────────────

    @Test
    fun `the same tag always derives the same id`() {
        assertEquals(
            attributeId(biz, item, "car", "Honda Fit"),
            attributeId(biz, item, "car", "Honda Fit")
        )
    }

    /** Casing and spacing are display, not identity — this is what makes two tills converge. */
    @Test
    fun `casing and spacing do not change the id`() {
        val base = attributeId(biz, item, "car", "Honda Fit")
        assertEquals(base, attributeId(biz, item, "Car", "  honda   FIT "))
        assertEquals(base, attributeId(biz, item, " CAR ", "Honda\tFit"))
    }

    /** Everything that means a different tag must derive a different row. */
    @Test
    fun `business, item, key and value each separate`() {
        val base = attributeId(biz, item, "car", "Honda Fit")
        assertNotEquals(base, attributeId("b2", item, "car", "Honda Fit"))
        assertNotEquals(base, attributeId(biz, "other-item", "car", "Honda Fit"))
        assertNotEquals(base, attributeId(biz, item, "brand", "Honda Fit"))
        assertNotEquals(base, attributeId(biz, item, "car", "Honda Jazz"))
    }

    /**
     * The separator is U+001F precisely because nobody can type it. With a printable one,
     * the key `car|Honda` with value `x` and the key `car` with value `|Honda` would join
     * into the same string and collide into one row.
     */
    @Test
    fun `the separator cannot be typed into a collision`() {
        assertNotEquals(
            attributeId(biz, item, "car|Honda", "x"),
            attributeId(biz, item, "car", "|Honda")
        )
        assertEquals(1, ATTR_SEPARATOR.length)
        assertEquals(0x1F, ATTR_SEPARATOR[0].code)
    }

    /** Changing either constant re-ids every tag ever written, on both clients. */
    @Test
    fun `the namespace is pinned`() {
        assertEquals("6f1c0b3e-8a2d-5f47-9c31-2b7a4d6e8f10", ATTR_NAMESPACE)
    }

    // ── the paths the repository builds on the identity ───────────────────

    /**
     * Re-adding a REMOVED tag must land back on the same row.
     *
     * A removal is a tombstone (`deleted = true`), not a disappearance, because the removal
     * itself has to travel to the other phones. So adding "Honda Fit" back derives the same
     * id and the upsert flips `deleted` off — one row, resurrected. With a random id the
     * re-add would be a SECOND live row for the same fitment, which is exactly what the
     * cloud's live unique index refuses, failing the entire push batch.
     *
     * Modelled as an upsert-by-id over a map, which is what both Room and PostgREST do with
     * these rows.
     */
    @Test
    fun `re-adding a removed tag reuses the row instead of duplicating it`() {
        val table = HashMap<String, ItemAttribute>()
        fun upsert(row: ItemAttribute) { table[row.id] = row }

        val id = attributeId(biz, item, "car", "Honda Fit")
        upsert(ItemAttribute(id = id, businessId = biz, itemId = item, key = "car", value = "Honda Fit"))
        // ... removed at the counter ...
        upsert(table.getValue(id).copy(deleted = true))
        assertEquals(1, table.size)

        // ... and typed in again a week later, with different spacing, as people do.
        val again = attributeId(biz, item, " Car ", "honda   fit")
        assertEquals(id, again)
        upsert(
            ItemAttribute(
                id = again, businessId = biz, itemId = item,
                key = "Car", value = "honda   fit", deleted = false,
            )
        )
        assertEquals(1, table.size)
        assertFalse("the tombstone was resurrected, not duplicated", table.getValue(id).deleted)
    }

    /**
     * Why an edit is a tombstone PLUS an insert rather than a mutation.
     *
     * The key and the value ARE the identity. Change either and the row's id changes with
     * it, so writing the new text onto the old row would leave a row whose id no longer
     * describes its contents — and the next device to add that same tag would derive the
     * correct id and create a duplicate beside it. Re-spelling the SAME tag is the one case
     * that stays a single upsert, because the id does not move.
     */
    @Test
    fun `an edit that changes the text changes the identity`() {
        val before = attributeId(biz, item, "car", "Honda Fit")
        assertNotEquals(before, attributeId(biz, item, "car", "Honda Jazz"))
        assertNotEquals(before, attributeId(biz, item, "fits", "Honda Fit"))
        assertEquals(before, attributeId(biz, item, "CAR", "Honda  Fit"))
    }

    /**
     * Two offline tills, same part, same fitment, typed slightly differently — one row.
     * This is the entire reason the id is derived rather than random.
     */
    @Test
    fun `two offline tills converge on one row`() {
        val till1 = attributeId(biz, item, "car", "Ford Ranger T6")
        val till2 = attributeId(biz, item, "Car", " ford ranger  t6 ")
        assertEquals(till1, till2)
    }
}
