package com.portionspot.pos.notify

import com.portionspot.pos.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tracked item recorded BELOW ZERO must not reach the owner as an ordinary "Out of stock".
 *
 * It used to: the engine tested `qty <= 0.0`, so a shelf at -2 and a shelf at 0 produced
 * byte-identical notifications. They need opposite responses — one is reorder, the other is
 * "the record is wrong, go and count it, and no delivery will fix it" — and a real -2 sat
 * on a real phone unnoticed precisely because it wore the sold-out badge.
 */
class NegativeStockNotificationTest {

    private val now = 1_000_000_000_000L

    private fun snap(vararg items: Item) = NotifSnapshot(
        nowMs = now, currency = "USD", trackedItems = items.toList(),
        owedRefunds = emptyList(), pendingPayments = emptyList(), largeSales = emptyList(),
        agingRows = emptyList(), pendingSyncCount = 0, lastSyncAt = now
    )

    @Test fun `a negative on-hand is its own alert, not out-of-stock`() {
        val short = Item(businessId = "b", name = "Brake Pad", trackStock = true, stockQty = -2.0)
        val c = NotificationEngine.compute(snap(short)).single()

        assertEquals("negstock:${short.id}", c.dedupeKey)
        assertEquals("Stock take needed", c.title)
        assertEquals("inventory", c.category)
        assertEquals("danger", c.severity)
        assertTrue("the owner has to be told about this one", c.pushWorthy)
        assertTrue("the alert must carry the actual figure", c.body.contains("-2"))
    }

    /** The two conditions must stay separable in the feed — one item cannot be both, and a
     *  negative must never also raise the out-of-stock row it replaced. */
    @Test fun `a negative item raises exactly one inventory alert`() {
        val short = Item(businessId = "b", name = "Brake Pad", trackStock = true, stockQty = -2.0)
        val cs = NotificationEngine.compute(snap(short))
        assertEquals(1, cs.size)
        assertNull(cs.firstOrNull { it.dedupeKey == "outstock:${short.id}" })
        assertNull(cs.firstOrNull { it.dedupeKey == "lowstock:${short.id}" })
    }

    /** Zero is still out of stock. The new branch must not swallow the ordinary case. */
    @Test fun `zero is still out of stock`() {
        val empty = Item(businessId = "b", name = "Oil", trackStock = true, stockQty = 0.0)
        val c = NotificationEngine.compute(snap(empty)).single()
        assertEquals("outstock:${empty.id}", c.dedupeKey)
        assertEquals("Out of stock", c.title)
    }

    /** A measured product's on-hand lives in stockMeasured, so a negative there has to be
     *  found there — and reads in the item's own unit, not as a bare count. */
    @Test fun `a negative measured product is read from stockMeasured and reads in its unit`() {
        val oil = Item(
            businessId = "b", name = "Cooking Oil", trackStock = true,
            productType = "measured", stockQty = 0.0, stockMeasured = -1.5, unit = "L"
        )
        val c = NotificationEngine.compute(snap(oil)).single()
        assertEquals("negstock:${oil.id}", c.dedupeKey)
        assertTrue("expected the unit in the body, got: ${c.body}", c.body.contains("-1.5 L"))
    }

    /** A measured on-hand that has summed its way to -1.11e-16 (2 L sold as 1.1 then 0.9) is
     *  an empty shelf, and must not push the owner a stock take. This is the same
     *  false-alarm shape the low-stock rule produced before it was fixed. */
    @Test fun `a float residue does not raise a stock take`() {
        val residue = 2.0 - 1.1 - 0.9
        assertTrue("precondition: this needs a NEGATIVE residue", residue < 0.0)
        val oil = Item(
            businessId = "b", name = "Cooking Oil", trackStock = true,
            productType = "measured", stockMeasured = residue, unit = "L"
        )
        val cs = NotificationEngine.compute(snap(oil))
        assertNull("a rounding residue announced a stock take", cs.firstOrNull { it.dedupeKey == "negstock:${oil.id}" })
        assertEquals("outstock:${oil.id}", cs.single().dedupeKey)
    }

    @Test fun `an untracked item is not in the snapshot's business at all`() {
        // The engine is handed tracked items only; a healthy tracked item stays quiet.
        val fine = Item(businessId = "b", name = "Filter", trackStock = true, stockQty = 20.0)
        assertNotNull(NotificationEngine.compute(snap(fine)))
        assertTrue(NotificationEngine.compute(snap(fine)).isEmpty())
    }
}
