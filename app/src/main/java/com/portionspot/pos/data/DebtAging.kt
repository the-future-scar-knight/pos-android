package com.portionspot.pos.data

/**
 * FIFO debt aging (prompt §8 — "aging report 30/60/90"). Pure and unit-tested.
 *
 * A customer's balance is the derived credit ledger: `credit_owed` charges minus
 * `credit_paid` payments. To age it, payments are applied to the OLDEST outstanding
 * charge first (FIFO), then whatever remains of each charge is bucketed by its own
 * age. This matches how a shop actually reasons about "how old is the money still
 * owed" — a recent payment clears the oldest debt, not the newest.
 */
object DebtAging {
    private const val DAY = 24L * 60 * 60 * 1000

    fun compute(txns: List<CreditTxn>, nameById: Map<String, String>, nowMs: Long): List<DebtAgingRow> {
        val byCustomer = txns
            .filter { it.type == "credit_owed" || it.type == "credit_paid" }
            .groupBy { it.customerId }

        val rows = ArrayList<DebtAgingRow>()
        for ((cid, list) in byCustomer) {
            val charges = list.filter { it.type == "credit_owed" }
                .sortedBy { it.createdAt }
                .map { RemainingCharge(it.createdAt, it.amount) }
                .toMutableList()
            var payment = list.filter { it.type == "credit_paid" }.sumOf { it.amount }

            // Apply payments to the oldest charges first.
            var i = 0
            while (payment > 0.005 && i < charges.size) {
                val c = charges[i]
                val applied = minOf(payment, c.remaining)
                c.remaining -= applied
                payment -= applied
                if (c.remaining <= 0.005) i++
            }

            var b0 = 0.0; var b30 = 0.0; var b60 = 0.0; var b90 = 0.0
            var oldest = Long.MAX_VALUE
            for (c in charges) {
                if (c.remaining <= 0.005) continue
                val ageDays = (nowMs - c.createdAt) / DAY
                when {
                    ageDays < 30 -> b0 += c.remaining
                    ageDays < 60 -> b30 += c.remaining
                    ageDays < 90 -> b60 += c.remaining
                    else -> b90 += c.remaining
                }
                if (c.createdAt < oldest) oldest = c.createdAt
            }
            val total = b0 + b30 + b60 + b90
            if (total > 0.005) {
                rows.add(
                    DebtAgingRow(
                        customerId = cid,
                        customerName = nameById[cid] ?: "Unknown",
                        bucket0to30 = b0,
                        bucket30to60 = b30,
                        bucket60to90 = b60,
                        bucket90plus = b90,
                        oldestAt = if (oldest == Long.MAX_VALUE) 0L else oldest
                    )
                )
            }
        }
        return rows.sortedByDescending { it.total }
    }

    private class RemainingCharge(val createdAt: Long, var remaining: Double)
}
