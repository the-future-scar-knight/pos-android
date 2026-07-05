package com.portionspot.pos.sync

import com.portionspot.pos.data.Business
import com.portionspot.pos.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins two sync invariants:
 *  1. Item ⇄ DTO round-trips the wholesale catalog fields losslessly.
 *  2. A pull NEVER wipes device-local secrets (Paynow KEY, printer MAC, logo) —
 *     they are excluded from the DTO and must be carried over from the local row.
 *     This is the secret-hygiene guarantee the audit called out.
 */
class DtoMappingTest {
    private val eps = 1e-9

    @Test
    fun item_roundTrip_preservesWholesaleFields() {
        val item = Item(
            id = "it1", businessId = "b1", name = "Delo Gold 5L", sku = "OIL-DELO-GOLD-5L",
            category = "Oils & Lubricants", price = 23.0, wholesalePrice = 20.0,
            boxPrice = 80.0, boxSize = 4, trackStock = true, stockQty = 24.0, unit = "pc",
            updatedAt = 1_700_000_000_000L
        )
        val back = item.toDto().toEntity()
        assertEquals(item.price, back.price, eps)
        assertEquals(item.wholesalePrice, back.wholesalePrice, eps)
        assertEquals(item.boxPrice, back.boxPrice, eps)
        assertEquals(item.boxSize, back.boxSize)
        assertEquals(item.category, back.category)
        assertEquals(item.trackStock, back.trackStock)
        assertEquals(item.stockQty, back.stockQty, eps)
        assertEquals(item.updatedAt, back.updatedAt)
    }

    @Test
    fun businessPull_keepsLocalOnlySecrets() {
        val local = Business(
            id = "b1", name = "Old Name",
            btPrinterMac = "AA:BB:CC:DD:EE:FF", btPrinterName = "Receipt-58",
            logoUri = "content://logo/1",
            paynowIntegrationKey = "super-secret-key",
            updatedAt = 1L
        )
        // Simulate the cloud row (DTO carries neither the secret nor the printer/logo).
        val cloud = local.copy(name = "New Name", updatedAt = 2_000L).toDto()
        val merged = cloud.toEntity(local)

        assertEquals("New Name", merged.name)                        // synced field updated
        assertEquals("AA:BB:CC:DD:EE:FF", merged.btPrinterMac)       // local-only preserved
        assertEquals("Receipt-58", merged.btPrinterName)
        assertEquals("content://logo/1", merged.logoUri)
        assertEquals("super-secret-key", merged.paynowIntegrationKey) // secret never clobbered
    }

    @Test
    fun businessPull_withNoLocalRow_secretsAreNull() {
        val cloud = Business(id = "b2", name = "Fresh", updatedAt = 5L).toDto()
        val merged = cloud.toEntity(null)
        assertEquals("Fresh", merged.name)
        assertNull(merged.paynowIntegrationKey)
        assertNull(merged.btPrinterMac)
    }
}
