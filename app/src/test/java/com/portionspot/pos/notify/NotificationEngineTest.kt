package com.portionspot.pos.notify

import com.portionspot.pos.data.Item
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.SaleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the admin notification rules (prompt §8) — escalation, stock, dedupe keys. */
class NotificationEngineTest {

    private val HOUR = 60L * 60 * 1000
    private val now = 1_000_000_000_000L
    private val th = NotifThresholds(largeSaleMin = 500.0, escalateHours = 4, unsyncedHours = 6)

    private fun snap(
        items: List<Item> = emptyList(),
        owed: List<Refund> = emptyList(),
        pending: List<MobileMoneyReceipt> = emptyList(),
        large: List<SaleEntity> = emptyList(),
        pendingSync: Int = 0,
        lastSync: Long? = now
    ) = NotifSnapshot(now, "USD", items, owed, pending, large, emptyList(), pendingSync, lastSync, th)

    private fun refund(total: Double, hoursAgo: Int) =
        Refund(businessId = "b", saleId = "s", refundTotal = total, status = "owed", createdAt = now - hoursAgo * HOUR)

    private fun payment(amount: Double, hoursAgo: Int) =
        MobileMoneyReceipt(businessId = "b", txnCode = "T$hoursAgo", amount = amount, receivedAt = now - hoursAgo * HOUR)

    @Test fun owed_refund_escalates_past_threshold() {
        val old = NotificationEngine.compute(snap(owed = listOf(refund(50.0, 5)))).single()
        assertEquals("danger", old.severity)
        assertTrue(old.pushWorthy)

        val fresh = NotificationEngine.compute(snap(owed = listOf(refund(50.0, 1)))).single()
        assertEquals("warn", fresh.severity)
        assertTrue(!fresh.pushWorthy)
    }

    @Test fun unverified_payment_escalates_past_threshold() {
        val old = NotificationEngine.compute(snap(pending = listOf(payment(20.0, 5)))).single()
        assertEquals("payments", old.category)
        assertTrue(old.pushWorthy)
        val fresh = NotificationEngine.compute(snap(pending = listOf(payment(20.0, 1)))).single()
        assertTrue(!fresh.pushWorthy)
    }

    @Test fun stock_levels_map_to_out_and_low() {
        val out = Item(businessId = "b", name = "Oil", trackStock = true, stockQty = 0.0, reorderLevel = 5.0)
        val low = Item(businessId = "b", name = "Filter", trackStock = true, stockQty = 3.0, reorderLevel = 5.0)
        val ok = Item(businessId = "b", name = "Belt", trackStock = true, stockQty = 20.0, reorderLevel = 5.0)
        val cs = NotificationEngine.compute(snap(items = listOf(out, low, ok)))
        assertNotNull(cs.firstOrNull { it.dedupeKey == "outstock:${out.id}" && it.severity == "danger" })
        assertNotNull(cs.firstOrNull { it.dedupeKey == "lowstock:${low.id}" && it.severity == "warn" })
        assertNull(cs.firstOrNull { it.refId == ok.id })
    }

    @Test fun unsynced_only_fires_when_stale_and_pending() {
        assertTrue(NotificationEngine.compute(snap(pendingSync = 2, lastSync = now - 10 * HOUR)).any { it.dedupeKey == "unsynced" })
        assertTrue(NotificationEngine.compute(snap(pendingSync = 0, lastSync = now - 10 * HOUR)).none { it.dedupeKey == "unsynced" })
        assertTrue(NotificationEngine.compute(snap(pendingSync = 2, lastSync = now - 1 * HOUR)).none { it.dedupeKey == "unsynced" })
    }

    @Test fun large_sale_is_flagged() {
        val sale = SaleEntity(businessId = "b", total = 600.0, soldAt = now)
        assertNotNull(NotificationEngine.compute(snap(large = listOf(sale))).firstOrNull { it.dedupeKey == "largesale:${sale.id}" })
    }
}
