package com.portionspot.pos.sync

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SHARED web-POS sync contract (see pos-web-sync-contract): products bridge
 * on sku with box+loose stock, PostgREST numeric columns arrive as JSON STRINGS, and
 * a sale's line items live in the JSONB `items` column with the web's exact key shape.
 * Getting any of these wrong would mis-read the live shared database.
 */
class DtoMappingTest {
    private val eps = 1e-9

    @Test
    fun product_mapsToItem_bridgingSkuAndSplittingStock() {
        val dto = ProductDto(
            sku = "OIL-DELO-5L", name = "Delo 5L", category = "Oils",
            boxPrice = "80", boxSize = 4, wholesalePrice = "20", retailPrice = "23",
            costPrice = "15", stockBoxes = 3, stockUnits = 2, lowStockThreshold = 5,
            active = true, updatedAt = "2026-07-06T09:00:00.000Z",
        )
        val item = dto.toItem("biz1", null)
        assertEquals("biz1", item.businessId)
        assertEquals("OIL-DELO-5L", item.sku)
        assertEquals(23.0, item.price, eps)             // retail_price (numeric string)
        assertEquals(20.0, item.wholesalePrice, eps)
        assertEquals(80.0, item.boxPrice, eps)
        assertEquals(4, item.boxSize)
        assertEquals(15.0, item.cost!!, eps)
        assertEquals(14.0, item.stockQty, eps)          // 3 boxes × 4 + 2 loose
        assertEquals(5.0, item.reorderLevel, eps)
        assertTrue(item.trackStock)
    }

    @Test
    fun product_zeroCost_meansUnknown() {
        val dto = ProductDto(sku = "X", name = "X", costPrice = "0", retailPrice = "10")
        assertNull(dto.toItem("biz1", null).cost)       // 0 = unknown ⇒ excluded from profit
    }

    @Test
    fun product_pullPreservesLocalItemId() {
        val existing = com.portionspot.pos.data.Item(
            id = "local-uuid-1", businessId = "biz1", name = "old", sku = "OIL-DELO-5L",
        )
        val dto = ProductDto(sku = "OIL-DELO-5L", name = "new", retailPrice = "9")
        val merged = dto.toItem("biz1", existing)
        assertEquals("local-uuid-1", merged.id)         // keeps the local id (bridge = sku)
        assertEquals("new", merged.name)
        assertEquals(9.0, merged.price, eps)
    }

    @Test
    fun sale_parsesJsonbItemsAndStringTotals() {
        val json = """
          {"id":"PSM-1","ref":"PSM-1","type":"sale","status":"completed",
           "grand_total":"160","total_discount":"0","vat_amount":"0",
           "pay_method":"cash","cashier":"Admin","cashier_id":"admin",
           "items":[{"qty":2,"sku":"OIL-DELO-5L","mode":"box","name":"Delo 5L",
                     "unitPrice":80,"lineDiscount":0,"unitsPerLine":4,"boxSize":4}],
           "payments":[{"amount":160,"method":"cash"}],
           "created_at":"2026-07-06T09:00:00.000Z","updated_at":"2026-07-06T09:00:00.000Z"}
        """.trimIndent()
        val dto = syncJson.decodeFromString<SaleDto>(json)

        val sale = dto.toSaleEntity("biz1")
        assertEquals("PSM-1", sale.id)                  // id = ref
        assertEquals("PSM-1", sale.receiptNo)
        assertEquals(160.0, sale.total, eps)            // numeric-as-string parsed
        assertEquals("cash", sale.paymentMethod)
        assertEquals("admin", sale.createdBy)
        assertEquals("Admin", sale.createdByName)

        val lines = dto.toSaleLines("biz1") { sku -> if (sku == "OIL-DELO-5L") "item-1" else null }
        assertEquals(1, lines.size)
        assertEquals("item-1", lines[0].itemId)         // resolved from sku
        assertEquals(2.0, lines[0].qty, eps)
        assertEquals(80.0, lines[0].unitPrice, eps)
        assertEquals("box", lines[0].mode)
        assertEquals(4, lines[0].unitsPerLine)
        assertEquals(160.0, lines[0].lineTotal, eps)    // unitPrice*qty − lineDiscount
    }
}
