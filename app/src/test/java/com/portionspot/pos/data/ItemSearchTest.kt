package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins catalogue search against the shape of the LIVE shop's data.
 *
 * The fixtures are real rows out of `public.item_attributes` — 671 tags over 80 items,
 * every one `key = 'car'`. The case that matters most is **"Oil Filter 164"**: 72
 * fitments, from a BMW 3 Series to a Mahindra Pikup, and a product name that does not
 * contain a single car. Before these tags were pulled, a customer asking for an oil filter
 * for a Nissan Navara got "No products found" from a shop that had one on the shelf, and
 * the only way through was a cashier who happened to know the part number.
 *
 * Three properties, in the order a cashier would notice them breaking: the part is FOUND,
 * the obvious answer is still FIRST, and the card says WHY it matched.
 */
class ItemSearchTest {

    private fun item(id: String, name: String, sku: String? = null, category: String? = null) =
        Item(id = id, businessId = BIZ, name = name, sku = sku, category = category)

    private fun tag(itemId: String, value: String, key: String = "car", deleted: Boolean = false) =
        ItemAttribute(
            id = "$itemId-$value", businessId = BIZ, itemId = itemId,
            key = key, value = value, deleted = deleted,
        )

    // The catalogue, as it really reads: part numbers for names, fitments in the tags.
    private val filter164 = item("i1", "Oil Filter 164", sku = "164")
    private val filterZ217 = item("i2", "Oil Filter Z217", sku = "Z217")
    private val hiaceFilter = item("i3", "Caravan / Hiace Air Filter", sku = "17801-54100")
    private val gearOil = item("i4", "Gear Oil 80W-90", category = "Oils")

    private val catalogue = listOf(filter164, filterZ217, hiaceFilter, gearOil)

    // Cars are stored UPPERCASE on these rows and title-case on others — the shop's data
    // is not consistent, which is exactly why matching goes through the normalised value.
    private val tags = tagIndex(
        listOf(
            tag("i1", "NISSAN NAVARA"), tag("i1", "BMW 3 SERIES"), tag("i1", "MAHINDRA PIKUP"),
            tag("i1", "NISSAN CARAVAN"),
            tag("i2", "TOYOTA HILUX"), tag("i2", "TOYOTA HI-ACE"), tag("i2", "MAZDA CX5"),
            tag("i3", "Hiace"), tag("i3", "Caravan"),
        )
    )

    private fun idsFor(query: String) = searchCatalog(catalogue, query, tags).map { it.item.id }

    // ── the thing that was broken ───────────────────────────────────────────────

    @Test
    fun findsAPartByACarItsNameNeverMentions() {
        // "Oil Filter 164" contains no car, no "Nissan", no "Navara". This query used to
        // return nothing at all.
        assertEquals(listOf("i1"), idsFor("Navara"))
        assertEquals(listOf("i2"), idsFor("Hilux"))
    }

    @Test
    fun matchIsCaseAndSpacingInsensitive() {
        // Typed one-handed at a counter, against data stored in another case entirely.
        assertEquals(listOf("i1"), idsFor("nissan navara"))
        assertEquals(listOf("i1"), idsFor("  NISSAN   NAVARA  "))
    }

    @Test
    fun aPartialCarNameStillMatches() {
        // The customer says "Mahindra"; nobody says "MAHINDRA PIKUP" out loud.
        assertEquals(listOf("i1"), idsFor("mahindra"))
    }

    // ── ranking: a tag hit must never push the obvious answer down the screen ───

    @Test
    fun nameMatchesComeBeforeTagMatches() {
        // "Caravan" is in one product's NAME and is a tag on another. The product the
        // cashier can see the word in comes first — a tag is a weaker reason to be on
        // screen than the name being right — but the tagged one is still there.
        assertEquals(listOf("i3", "i1"), idsFor("Caravan"))
    }

    @Test
    fun withNoTagMatches_theOrderIsExactlyTheCallersOrder() {
        // The regression guard for every search that has nothing to do with cars: these
        // must return what the old inline name/sku filter returned, in the same order.
        assertEquals(listOf("i1"), idsFor("164"))
        assertEquals(listOf("i4"), idsFor("Gear Oil"))
        assertEquals(listOf("i1", "i2"), idsFor("Oil Filter"))
    }

    // ── the guard rails ────────────────────────────────────────────────────────

    @Test
    fun oneCharacterDoesNotMatchTags() {
        // "a" is inside Navara, Mahindra, Mazda, Hiace — nearly every fitment in the shop.
        // Letting one keystroke match tags flashes the whole catalogue on the way to
        // typing a real query. Name/sku still match from a single character.
        val ids = idsFor("a")
        assertTrue("no item should be found only via a tag", ids.none { it == "i2" })
        assertTrue("name matches still work at one character", ids.contains("i3"))
    }

    @Test
    fun blankQueryReturnsEverythingUntouched() {
        assertEquals(catalogue.map { it.id }, idsFor("   "))
        assertEquals(catalogue.map { it.id }, idsFor(""))
    }

    @Test
    fun deletedTagsStopMatching() {
        // A fitment the web removed must stop selling the part for that car.
        val withTombstone = tagIndex(
            listOf(tag("i1", "NISSAN NAVARA", deleted = true), tag("i1", "BMW 3 SERIES"))
        )
        assertTrue(searchCatalog(catalogue, "Navara", withTombstone).isEmpty())
        assertEquals(listOf("i1"), searchCatalog(catalogue, "BMW", withTombstone).map { it.item.id })
    }

    @Test
    fun duplicateTagsAreCollapsed() {
        // The same car tagged twice in different casing is one fitment, not two.
        val dupes = tagIndex(
            listOf(tag("i1", "TOYOTA HILUX"), tag("i1", "toyota hilux "), tag("i1", "Toyota  Hilux"))
        )
        assertEquals(1, dupes.getValue("i1").size)
    }

    @Test
    fun categoryOnlyMatchesWhenTheScreenAsksForIt() {
        // The sell screen has category CHIPS, so folding category into its text search
        // would make the chips and the search disagree about the same word. Inventory has
        // no chips and does want it.
        assertTrue(searchCatalog(catalogue, "Oils", tags).isEmpty())
        assertEquals(
            listOf("i4"),
            searchCatalog(catalogue, "Oils", tags, includeCategory = true).map { it.item.id }
        )
    }

    // ── what the card says ─────────────────────────────────────────────────────

    @Test
    fun aTagHitCarriesTheReasonItMatched() {
        val hit = searchCatalog(catalogue, "Navara", tags).single()
        assertEquals("Fits NISSAN NAVARA", tagCaption(hit.matchedTags))
    }

    @Test
    fun everyMatchingFitmentIsNamed_notJustTheFirst() {
        // "toyota" hits two of this filter's fitments; showing one would read as the only
        // one, and a cashier would tell the customer it does not fit their Hi-Ace.
        val hit = searchCatalog(catalogue, "toyota", tags).single()
        assertEquals("Fits TOYOTA HI-ACE, TOYOTA HILUX", tagCaption(hit.matchedTags))
    }

    @Test
    fun aNameHitCarriesNoReason() {
        // Nothing to explain — the cashier can see the word in the name. A caption here
        // would print "Fits Hiace" under a product already called "… Hiace Air Filter".
        assertEquals(
            emptyList<TagValue>(),
            searchCatalog(catalogue, "Caravan / Hiace", tags).single().matchedTags
        )
    }

    @Test
    fun captionNamesAnyOtherKeyRatherThanGuessing() {
        // Only `car` reads as "Fits". A catalogue that later grows sizes or colours has to
        // stay readable without this code having been told about them in advance.
        val other = tagIndex(listOf(tag("i4", "20L", key = "Size")))
        assertEquals("Size: 20L", tagCaption(searchCatalog(catalogue, "20L", other).single().matchedTags))
    }

    private companion object {
        const val BIZ = "biz"
    }
}
