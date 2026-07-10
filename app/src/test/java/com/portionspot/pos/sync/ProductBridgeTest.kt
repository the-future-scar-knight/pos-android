package com.portionspot.pos.sync

import com.portionspot.pos.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [bridgeProducts] — the cloud-product ↔ local-item merge that keeps a
 * "connect after building the catalogue locally" from duplicating every product.
 * See PosSyncEngine.pullProducts.
 */
class ProductBridgeTest {

    private fun item(id: String, name: String, sku: String? = null) =
        Item(id = id, businessId = "biz", name = name, sku = sku)

    private fun dto(sku: String, name: String, updatedAt: String = "2026-07-09T00:00:00.000Z") =
        ProductDto(sku = sku, name = name, updatedAt = updatedAt)

    @Test
    fun mergesByName_whenLocalHasDifferentSku() {
        // Shop typed "Coca Cola" locally with its own code; cloud has it under a different sku.
        val local = listOf(item("L1", "Coca Cola", sku = "LOCAL-1"))
        val bridged = bridgeProducts(local, listOf(dto("CC-500", "Coca Cola")))
        assertEquals("L1", bridged.single().second?.id)   // merged, not a new row
    }

    @Test
    fun mergesByName_whenLocalHasNoSku() {
        val local = listOf(item("L2", "Bread"))
        val bridged = bridgeProducts(local, listOf(dto("BRD", "bread ")))   // case/space folded
        assertEquals("L2", bridged.single().second?.id)
    }

    @Test
    fun exactSkuWins_overName() {
        val local = listOf(item("L3", "Milk", sku = "MILK-1"))
        val bridged = bridgeProducts(local, listOf(dto("MILK-1", "Completely Different Name")))
        assertEquals("L3", bridged.single().second?.id)
    }

    @Test
    fun distinctProducts_stayUnmatched() {
        val local = listOf(item("L4", "Sugar", sku = "SUG"))
        val bridged = bridgeProducts(local, listOf(dto("SALT", "Salt")))
        assertNull(bridged.single().second)               // genuinely new → inserts fresh
    }

    @Test
    fun claimOnce_secondCloudRowWithSameNameGetsNoLocal() {
        val local = listOf(item("L5", "Rice"))
        val cloud = listOf(dto("RICE-A", "Rice"), dto("RICE-B", "rice"))
        val bridged = bridgeProducts(local, cloud)
        val matched = bridged.mapNotNull { it.second?.id }
        assertEquals(listOf("L5"), matched)               // only one row claims the local item
    }
}
