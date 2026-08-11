package com.portionspot.pos.sync

import com.portionspot.pos.sync.wire.ItemAttributeDto
import com.portionspot.pos.sync.wire.toItemAttribute
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `item_attributes` wire shape against a payload copied from the live shop's
 * PostgREST response.
 *
 * Two things here have bitten this codebase before on other tables and are cheap to pin:
 * unknown columns must not blow the decode up (the web adds columns without telling the
 * till), and a NULLABLE normalised column must not silently drop a fitment out of search.
 */
class ItemAttributeWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String) = json.decodeFromString<List<ItemAttributeDto>>(body)

    @Test
    fun readsALiveRow() {
        val rows = decode(
            """
            [{"id":"6b1f0a2e-11d4-4a55-9d1e-6d2a1b0c3e77",
              "business_id":"7d1f3a52-9c4e-4b18-8f6a-2e5b0c9a4d31",
              "item_id":"2f9c5b6a-8e14-4c33-9a02-71b4d5e6f708",
              "key":"car","value":"TOYOTA HILUX",
              "key_norm":"car","value_norm":"toyota hilux",
              "updated_at":"2026-08-10T09:14:22.512331+00:00",
              "deleted":false,"client_updated_at":null}]
            """.trimIndent()
        )
        val tag = rows.single().toItemAttribute(BIZ)!!
        assertEquals("2f9c5b6a-8e14-4c33-9a02-71b4d5e6f708", tag.itemId)
        assertEquals("car", tag.key)
        assertEquals("TOYOTA HILUX", tag.value)      // shown to the cashier as stored
        assertEquals("toyota hilux", tag.valueNorm)  // matched on
        assertEquals(BIZ, tag.businessId)
        assertTrue("updated_at must parse to a real instant", tag.updatedAt > 0)
    }

    @Test
    fun computesTheNormalisedValueWhenTheCloudLeftItNull() {
        // Both `*_norm` columns are nullable on the shared schema. A hand-inserted row
        // with nulls would otherwise carry an empty valueNorm, match nothing, and take
        // that car's parts out of search with no error anywhere.
        val tag = decode(
            """[{"id":"a1","item_id":"i1","key":" Car ","value":"  Ford  Ranger T6 ",
                 "key_norm":null,"value_norm":null,"updated_at":"2026-08-10T09:14:22Z"}]"""
        ).single().toItemAttribute(BIZ)!!
        assertEquals("car", tag.keyNorm)
        assertEquals("ford ranger t6", tag.valueNorm)
        assertEquals("Ford  Ranger T6", tag.value)   // display keeps the shop's own text
    }

    @Test
    fun dropsARowWithNothingToMatchOn() {
        // An empty value is not a fitment; kept, its normalised form is "" and every
        // query contains the empty string, so it would attach itself to every search.
        val rows = decode(
            """[{"id":"a1","item_id":"i1","key":"car","value":"   ","updated_at":"2026-08-10T09:14:22Z"},
                {"id":"a2","item_id":"i1","value":"Vezel","updated_at":"2026-08-10T09:14:22Z"}]"""
        )
        assertNull("blank value", rows[0].toItemAttribute(BIZ))
        assertNull("missing key", rows[1].toItemAttribute(BIZ))
    }

    @Test
    fun tombstonesSurviveTheMapping() {
        val tag = decode(
            """[{"id":"a1","item_id":"i1","key":"car","value":"Vezel",
                 "updated_at":"2026-08-10T09:14:22Z","deleted":true}]"""
        ).single().toItemAttribute(BIZ)!!
        assertTrue(tag.deleted)
    }

    @Test
    fun unknownColumnsDoNotBreakTheDecode() {
        // The web owns this table and can add a column at any time. A strict decode would
        // turn that into a total pull failure on every till at once.
        val rows = decode(
            """[{"id":"a1","item_id":"i1","key":"car","value":"Vezel",
                 "updated_at":"2026-08-10T09:14:22Z","sort_order":3,"source":"import"}]"""
        )
        assertEquals("Vezel", rows.single().value)
    }

    private companion object {
        const val BIZ = "7d1f3a52-9c4e-4b18-8f6a-2e5b0c9a4d31"
    }
}
