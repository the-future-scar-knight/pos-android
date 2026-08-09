package com.portionspot.pos.notify

import com.portionspot.pos.data.DebtAgingRow
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
        aging: List<DebtAgingRow> = emptyList(),
        pendingSync: Int = 0,
        lastSync: Long? = now
    ) = NotifSnapshot(
        nowMs = now, currency = "USD", trackedItems = items, owedRefunds = owed,
        pendingPayments = pending, largeSales = large, agingRows = aging,
        pendingSyncCount = pendingSync, lastSyncAt = lastSync, thresholds = th
    )

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

    /** ★ A measured product (kg/L/m) keeps its on-hand in [Item.stockMeasured] and leaves
     *  stockQty at 0 by design, so the engine reading stockQty called a full drum of oil
     *  "Out of stock". These four pin the [com.portionspot.pos.data.onHand] reading and
     *  the deliberate refusal to invent a default threshold for a measured unit. */
    private fun measured(name: String, unit: String, onHand: Double, reorder: Double) = Item(
        businessId = "b", name = name, productType = "measured", unit = unit,
        trackStock = true, pricePerUnit = 2.5,
        stockQty = 0.0, stockMeasured = onHand, reorderLevel = reorder
    )

    @Test fun measured_item_with_stock_raises_no_inventory_alert() {
        val oil = measured("Cooking oil", "L", onHand = 12.5, reorder = 2.0)
        assertTrue(NotificationEngine.compute(snap(items = listOf(oil))).none { it.refId == oil.id })
    }

    @Test fun measured_item_below_its_reorder_level_still_warns() {
        val oil = measured("Cooking oil", "L", onHand = 1.5, reorder = 2.0)
        val c = NotificationEngine.compute(snap(items = listOf(oil))).single()
        assertEquals("lowstock:${oil.id}", c.dedupeKey)
        assertEquals("warn", c.severity)
        // Reads in the item's own unit, not a bare count.
        assertTrue(c.body, c.body.contains("1.5 L"))
    }

    @Test fun measured_item_at_zero_is_out_of_stock() {
        val oil = measured("Cooking oil", "L", onHand = 0.0, reorder = 2.0)
        val c = NotificationEngine.compute(snap(items = listOf(oil))).single()
        assertEquals("outstock:${oil.id}", c.dedupeKey)
        assertEquals("danger", c.severity)
        assertTrue(c.pushWorthy)
    }

    @Test fun measured_item_without_a_reorder_level_never_goes_low() {
        // 5 m of hose and 5 kg of oil are not the same threshold, so the count-based
        // default must not apply — only an owner-set level in the item's unit does.
        val hose = measured("Garden hose", "m", onHand = 2.0, reorder = 0.0)
        assertTrue(NotificationEngine.compute(snap(items = listOf(hose))).none { it.refId == hose.id })
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

    @Test fun audience_match_targets_the_right_role() {
        // "admin" alerts buzz only the admin phone.
        assertTrue(NotificationEngine.audienceMatches("admin", isAdmin = true))
        assertTrue(!NotificationEngine.audienceMatches("admin", isAdmin = false))

        // "cashier" alerts buzz only the non-admin (cashier) phones.
        assertTrue(NotificationEngine.audienceMatches("cashier", isAdmin = false))
        assertTrue(!NotificationEngine.audienceMatches("cashier", isAdmin = true))

        // "all" reaches everyone.
        assertTrue(NotificationEngine.audienceMatches("all", isAdmin = true))
        assertTrue(NotificationEngine.audienceMatches("all", isAdmin = false))

        // An unknown/legacy value is treated as admin-only (the historical default), so a
        // cashier phone never buzzes for it.
        assertTrue(NotificationEngine.audienceMatches("", isAdmin = true))
        assertTrue(!NotificationEngine.audienceMatches("", isAdmin = false))
        assertTrue(NotificationEngine.audienceMatches("owner", isAdmin = true))
    }

    @Test fun over_credit_limit_flagged_only_when_balance_exceeds_limit() {
        fun aging(owed: Double, limit: Double?) =
            DebtAgingRow(customerId = "c1", customerName = "Pachedu", bucket0to30 = owed, creditLimit = limit)

        // Over the limit → a pushable danger alert.
        val over = NotificationEngine.compute(snap(aging = listOf(aging(120.0, 100.0))))
            .firstOrNull { it.dedupeKey == "overlimit:c1" }
        assertNotNull(over)
        assertEquals("danger", over!!.severity)
        assertTrue(over.pushWorthy)

        // Within the limit → nothing.
        assertNull(NotificationEngine.compute(snap(aging = listOf(aging(80.0, 100.0))))
            .firstOrNull { it.dedupeKey == "overlimit:c1" })
        // No limit set → nothing.
        assertNull(NotificationEngine.compute(snap(aging = listOf(aging(120.0, null))))
            .firstOrNull { it.dedupeKey == "overlimit:c1" })
    }
}
