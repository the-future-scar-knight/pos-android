package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the FIFO debt-aging math (prompt §8 aging report). */
class DebtAgingTest {

    private val DAY = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    private fun owed(cid: String, amount: Double, daysAgo: Int) =
        CreditTxn(businessId = "b", customerId = cid, type = "credit_owed", amount = amount, createdAt = now - daysAgo * DAY)

    private fun paid(cid: String, amount: Double, daysAgo: Int) =
        CreditTxn(businessId = "b", customerId = cid, type = "credit_paid", amount = amount, createdAt = now - daysAgo * DAY)

    @Test fun payment_clears_oldest_charge_first() {
        // 100 charged 40 days ago, 30 repaid recently → 70 remains, aged in the 30–60 bucket.
        val rows = DebtAging.compute(listOf(owed("A", 100.0, 40), paid("A", 30.0, 5)), mapOf("A" to "Ann"), now)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(70.0, r.total, 0.001)
        assertEquals(0.0, r.bucket0to30, 0.001)
        assertEquals(70.0, r.bucket30to60, 0.001)
        assertEquals("Ann", r.customerName)
    }

    @Test fun fifo_across_two_charges() {
        // 50 (70d) + 40 (10d), 60 repaid → clears the 50 fully + 10 of the 40; 30 left at 10d.
        val rows = DebtAging.compute(
            listOf(owed("B", 50.0, 70), owed("B", 40.0, 10), paid("B", 60.0, 1)),
            mapOf("B" to "Ben"), now
        )
        val r = rows[0]
        assertEquals(30.0, r.total, 0.001)
        assertEquals(30.0, r.bucket0to30, 0.001)
        assertEquals(0.0, r.bucket60to90, 0.001)
    }

    @Test fun fully_paid_customer_is_omitted() {
        val rows = DebtAging.compute(listOf(owed("C", 40.0, 20), paid("C", 40.0, 1)), mapOf("C" to "Cy"), now)
        assertEquals(0, rows.size)
    }
}
