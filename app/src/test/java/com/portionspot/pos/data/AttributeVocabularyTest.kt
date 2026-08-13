package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping, labels and the editor's typeahead.
 *
 * The typeahead is not a convenience feature. Canonicalisation merges "Honda fit" with
 * "honda  Fit", but nothing can merge "HondaFit" — a tag typed slightly differently is a
 * second tag forever, and with 671 of them in this shop that is how a catalogue rots. The
 * only real defence is putting what has already been used in front of the owner before
 * they retype it, which is what these functions are for.
 */
class AttributeVocabularyTest {

    private val biz = "b1"

    private fun tag(
        itemId: String,
        value: String,
        key: String = "car",
        deleted: Boolean = false,
    ) = ItemAttribute(
        id = attributeId(biz, itemId, key, value),
        businessId = biz, itemId = itemId, key = key, value = value, deleted = deleted,
    )

    // ── grouping ──────────────────────────────────────────────────────────

    /** One heading per canonical key, so "Car" typed on one till and "car" on another do
     *  not read as two different things about the same part. */
    @Test
    fun `groups by canonical key even when the typing differs`() {
        val groups = groupAttributes(
            listOf(tag("i1", "Honda Fit", key = "Car"), tag("i1", "Toyota Vitz", key = "car"))
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("Honda Fit", "Toyota Vitz"), groups[0].rows.map { it.value })
    }

    /** A removed fitment must stop being displayed — not become an empty row. */
    @Test
    fun `leaves tombstoned tags out`() {
        val groups = groupAttributes(
            listOf(tag("i1", "Honda Fit"), tag("i1", "Gone", deleted = true))
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("Honda Fit"), groups[0].rows.map { it.value })
    }

    @Test
    fun `an untagged item groups to nothing`() {
        assertTrue(groupAttributes(emptyList()).isEmpty())
    }

    @Test
    fun `keys read as labels`() {
        assertEquals("Part number", prettyAttrKey("part_number"))
        assertEquals("Car", prettyAttrKey("car"))
        assertEquals("", prettyAttrKey("  "))
    }

    // ── vocabulary and suggestions ────────────────────────────────────────

    /**
     * Ordered by USE, not alphabetically: the value offered first should be the one the
     * shop actually reaches for. A parts shop that has tagged forty items "Toyota Hilux"
     * and one "Aston Martin" wants the Hilux first, every time.
     */
    @Test
    fun `vocabulary orders values by how often they are used`() {
        val vocab = attrVocabulary(
            listOf(
                tag("i1", "Toyota Hilux"), tag("i2", "Toyota Hilux"), tag("i3", "Toyota Hilux"),
                tag("i4", "Aston Martin"),
                tag("i5", "Honda Fit"), tag("i6", "Honda Fit"),
            )
        )
        assertEquals(
            listOf("Toyota Hilux", "Honda Fit", "Aston Martin"),
            vocab.valuesByKey["car"]
        )
    }

    /** Tombstones are not vocabulary: a fitment the shop removed should not be suggested
     *  straight back to the person who removed it. */
    @Test
    fun `a removed value is not offered again`() {
        val vocab = attrVocabulary(listOf(tag("i1", "Vezel", deleted = true), tag("i2", "Fit GK3")))
        assertEquals(listOf("Fit GK3"), vocab.valuesByKey["car"])
    }

    /** The vocabulary is indexed by CANONICAL key, since that is what the caller has after
     *  folding whatever was typed into the key field. */
    @Test
    fun `values are indexed by canonical key`() {
        val vocab = attrVocabulary(listOf(tag("i1", "Vezel", key = " CAR ")))
        assertEquals(listOf("Vezel"), vocab.valuesByKey["car"])
    }

    /** Substring, not prefix — someone typing "num" is looking for `part_number`. */
    @Test
    fun `key suggestions match on substring and include the standard keys`() {
        val vocab = attrVocabulary(listOf(tag("i1", "Vezel")))
        assertTrue("part_number" in keySuggestions(vocab, "num"))
        assertTrue("car" in keySuggestions(vocab, ""))
        // What is already fully typed is in the box, not a suggestion.
        assertFalse("car" in keySuggestions(vocab, "car"))
    }

    /** Keys the shop already uses come before the standard ones it has not adopted. */
    @Test
    fun `keys the shop uses are offered before the suggested ones`() {
        val vocab = attrVocabulary(listOf(tag("i1", "A111K", key = "part_number")))
        assertEquals("part_number", keySuggestions(vocab, "").first())
    }

    /**
     * Values already on THIS item are excluded rather than shown: the derived id means
     * tapping one could only rewrite the row it already has, and a control that visibly
     * does nothing reads as a bug.
     */
    @Test
    fun `value suggestions drop what the item already carries`() {
        val vocab = attrVocabulary(listOf(tag("i1", "Vezel"), tag("i2", "Fit GK3")))
        val offered = valueSuggestions(vocab, "car", onItemValueNorms = setOf("vezel"), typed = "")
        assertEquals(listOf("Fit GK3"), offered)
    }

    @Test
    fun `value suggestions narrow as the owner types`() {
        val vocab = attrVocabulary(
            listOf(tag("i1", "Toyota Hilux"), tag("i2", "Toyota Vitz"), tag("i3", "Honda Fit"))
        )
        assertEquals(
            listOf("Toyota Hilux", "Toyota Vitz"),
            valueSuggestions(vocab, "car", emptySet(), "toy")
        )
    }

    @Test
    fun `a key the shop has never used offers no values`() {
        val vocab = attrVocabulary(listOf(tag("i1", "Vezel")))
        assertTrue(valueSuggestions(vocab, "engine", emptySet(), "").isEmpty())
    }
}
