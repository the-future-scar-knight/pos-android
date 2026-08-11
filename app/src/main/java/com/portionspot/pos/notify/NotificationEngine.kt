package com.portionspot.pos.notify

import com.portionspot.pos.data.AppNotification
import com.portionspot.pos.data.DebtAgingRow
import com.portionspot.pos.data.Item
import com.portionspot.pos.data.MobileMoneyReceipt
import com.portionspot.pos.data.PurchaseOrder
import com.portionspot.pos.data.Refund
import com.portionspot.pos.data.SaleEntity
import com.portionspot.pos.data.isMeasured
import com.portionspot.pos.data.onHand
import com.portionspot.pos.data.stockIsShort
import java.util.Locale

/**
 * The rule brain of the admin notifications backend (prompt §8, Phase 7). Pure and
 * unit-tested: given a snapshot of shop state it returns the notifications that SHOULD
 * exist, each with a stable [Candidate.dedupeKey] so the repository upserts one row per
 * condition. [Candidate.pushWorthy] marks the ones that warrant a system notification
 * (a new state, or a time-based condition that has crossed its N-hour escalation).
 *
 * Time-based escalation (§8): unverified mobile-money payments, refunds left owing, and
 * a device that hasn't synced are measured from the underlying event time, so they go
 * from a quiet feed entry to a pushed danger alert once they age past the threshold.
 */
data class NotifThresholds(
    val largeSaleMin: Double = 500.0,
    val escalateHours: Int = 4,      // unverified payment / owed refund → escalate
    val unsyncedHours: Int = 6,      // device hasn't synced while records pending
    val debtAgeDays: Int = 30,       // debt "newly older than N days"
    val lowStockDefault: Double = 5.0
)

data class NotifCandidate(
    val category: String,
    val severity: String,
    val title: String,
    val body: String,
    val dedupeKey: String,
    val refType: String?,
    val refId: String?,
    val eventAt: Long,
    val pushWorthy: Boolean
)

data class NotifSnapshot(
    val nowMs: Long,
    val currency: String,
    val trackedItems: List<Item>,
    val owedRefunds: List<Refund>,
    val pendingPayments: List<MobileMoneyReceipt>,
    val largeSales: List<SaleEntity>,
    val agingRows: List<DebtAgingRow>,
    val openPurchaseOrders: List<PurchaseOrder> = emptyList(),
    val pendingSyncCount: Int,
    val lastSyncAt: Long?,
    val thresholds: NotifThresholds = NotifThresholds()
)

object NotificationEngine {

    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24L * HOUR

    /**
     * Should THIS device raise a system heads-up (and own the deep-link) for an alert
     * targeted at [audience], given whether the signed-in session is an admin?
     *
     * Pure so it can be unit-tested and reused from every firing site (the sweep, the
     * cross-device pull delivery, the AdminNotificationWorker). "all" reaches everyone;
     * "cashier" reaches only non-admins; anything else (including the legacy/default
     * "admin") reaches only admins — a cashier phone must never buzz for an admin alert.
     */
    fun audienceMatches(audience: String, isAdmin: Boolean): Boolean = when (audience) {
        "all" -> true
        "cashier" -> !isAdmin
        else -> isAdmin      // "admin" and any unknown value are admin-only
    }

    fun compute(s: NotifSnapshot): List<NotifCandidate> {
        val t = s.thresholds
        val out = ArrayList<NotifCandidate>()

        // ---- Inventory: out of stock, then low stock ----
        // ★ On-hand is read through [onHand], NEVER the raw stockQty. A measured product
        // (sold by a decimal quantity of kg/L/m) parks its real on-hand in stockMeasured
        // and deliberately leaves stockQty at 0 — so testing stockQty announced every
        // full drum of oil as "Out of stock", the false alarms the owner was getting.
        // [onHand] is the same accessor the dashboard's out/low counters use, so the feed
        // and the dashboard can no longer disagree about the same shelf.
        // Wording is product-type aware (§ Box/Set/Piece): a set reads "2 sets left", a
        // piece "4 pieces left", a measured item its own unit "1.5 kg left", and a box
        // item just "5 left" (the count carries it).
        // ★ BELOW ZERO IS ITS OWN ALERT, not the bottom end of "Out of stock". It used to
        // fall into the `qty <= 0.0` branch and reach the owner as an ordinary sold-out
        // notice, which is how a -2 sat on a real phone unnoticed: the two conditions look
        // identical in a feed but need opposite responses. Out of stock means reorder;
        // below zero means the record is wrong and only a count will fix it — no delivery
        // corrects it, and it will keep mis-costing every sale until someone does. Its own
        // dedupeKey (`negstock:`) so the two can coexist on the same item's history rather
        // than one overwriting the other.
        for (it in s.trackedItems) {
            val qty = it.onHand
            if (it.stockIsShort()) {
                val noun = qtyNoun(it, qty)
                out += NotifCandidate(
                    "inventory", "danger", "Stock take needed",
                    "${it.name}: recorded at ${trimQty(qty)}${noun?.let { n -> " $n" } ?: ""} — " +
                        "more was sold than the system had. Count the shelf and correct it.",
                    "negstock:${it.id}", "item", it.id, s.nowMs, pushWorthy = true
                )
            } else if (qty <= 0.0) {
                val noun = stockNoun(it, 0.0)
                out += NotifCandidate(
                    "inventory", "danger", "Out of stock",
                    if (noun != null) "${it.name}: no $noun in stock." else "${it.name} has run out.",
                    "outstock:${it.id}", "item", it.id, s.nowMs, pushWorthy = true
                )
            } else {
                // ★ [NotifThresholds.lowStockDefault] is a bare COUNT, which only carries
                // meaning for counted types (boxes, sets, pieces). On a measured item the
                // same 5 would mean 5 kg of cooking oil and 5 m of hose alike — two
                // thresholds that share a number and nothing else, one of them absurd. So
                // a measured item alerts only against the reorder level its owner typed in
                // that item's own unit ("Reorder at (kg)" in the product form); with none
                // set, a level of 0 keeps it quiet instead of guessing. Running out is
                // still running out, so the zero branch above applies to every type.
                val level = when {
                    it.reorderLevel > 0.0 -> it.reorderLevel
                    it.isMeasured -> 0.0
                    else -> t.lowStockDefault
                }
                if (qty <= level) {
                    val noun = qtyNoun(it, qty)
                    out += NotifCandidate(
                        "inventory", "warn", "Low stock",
                        "${it.name}: ${trimQty(qty)}${noun?.let { n -> " $n" } ?: ""} left.",
                        "lowstock:${it.id}", "item", it.id, s.nowMs, pushWorthy = true
                    )
                }
            }
        }

        // ---- Refunds left owing — escalate past the threshold (§8) ----
        for (r in s.owedRefunds) {
            val escalated = s.nowMs - r.createdAt > t.escalateHours * HOUR
            out += NotifCandidate(
                "refunds", if (escalated) "danger" else "warn",
                if (escalated) "Refund unpaid too long" else "Refund still owed",
                "${money(r.refundTotal, s.currency)} to ${r.customerName ?: "customer"}" +
                    (r.createdByName?.let { " · by $it" } ?: ""),
                "refundowed:${r.id}", "refund", r.id, r.createdAt, pushWorthy = escalated
            )
        }

        // ---- Mobile-money payments left unverified — escalate past the threshold ----
        for (p in s.pendingPayments) {
            val escalated = s.nowMs - p.receivedAt > t.escalateHours * HOUR
            out += NotifCandidate(
                "payments", if (escalated) "danger" else "warn",
                if (escalated) "Payment unverified too long" else "Payment to verify",
                "${money(p.amount, p.currency)} from ${p.matchedCustomerName ?: p.senderName ?: p.senderPhone ?: "unknown"}",
                "mmpending:${p.id}", "mm_receipt", p.id, p.receivedAt, pushWorthy = escalated
            )
        }

        // ---- Large sales (configurable amount) — notify once ----
        for (sale in s.largeSales) {
            out += NotifCandidate(
                "sales", "warn", "Large sale",
                "${money(sale.total, s.currency)} · ${sale.customerName ?: "Walk-in"}" +
                    (sale.createdByName?.let { " · by $it" } ?: ""),
                "largesale:${sale.id}", "sale", sale.id, sale.soldAt, pushWorthy = true
            )
        }

        // ---- Debts crossing the aging threshold ----
        for (row in s.agingRows) {
            if (row.oldestAt <= 0L) continue
            val ageDays = (s.nowMs - row.oldestAt) / DAY
            if (ageDays >= t.debtAgeDays) {
                val severe = ageDays >= 60
                out += NotifCandidate(
                    "system", if (severe) "danger" else "warn", "Aging debt",
                    "${row.customerName} owes ${money(row.total, s.currency)} · oldest $ageDays days.",
                    "debtage:${row.customerId}", "customer", row.customerId, row.oldestAt, pushWorthy = true
                )
            }
        }

        // ---- Customer over their credit limit (admin is informed; sale still went
        //      through — the cashier only gets an advisory warning) ----
        for (row in s.agingRows) {
            val limit = row.creditLimit ?: continue
            if (row.total > limit + 0.005) {
                out += NotifCandidate(
                    "sales", "danger", "Over credit limit",
                    "${row.customerName} owes ${money(row.total, s.currency)} — over their ${money(limit, s.currency)} limit.",
                    "overlimit:${row.customerId}", "customer", row.customerId, s.nowMs, pushWorthy = true
                )
            }
        }

        // ---- Purchase orders near / past their ETA — "has it arrived?" (§10.5) ----
        // Prompt from one day before the ETA; escalate to danger once it is a day overdue.
        for (po in s.openPurchaseOrders) {
            val eta = po.eta ?: continue
            if (s.nowMs < eta - DAY) continue          // still more than a day out — quiet
            val overdue = s.nowMs > eta + DAY
            out += NotifCandidate(
                "inventory", if (overdue) "danger" else "warn",
                if (overdue) "Order overdue — arrived?" else "Order arriving — confirm",
                "${po.ref} from ${po.supplierName.ifBlank { "supplier" }} — confirm arrival to stock it.",
                "poarrival:${po.id}", "purchase_order", po.id, eta, pushWorthy = true
            )
        }

        // ---- Device hasn't synced while records are pending ----
        if (s.pendingSyncCount > 0) {
            val since = s.lastSyncAt ?: 0L
            val stale = since == 0L || s.nowMs - since > t.unsyncedHours * HOUR
            if (stale) {
                out += NotifCandidate(
                    "system", "danger", "Not synced",
                    "${s.pendingSyncCount} record(s) pending — sync to back them up.",
                    "unsynced", "device", null, s.lastSyncAt ?: s.nowMs, pushWorthy = true
                )
            }
        }

        return out
    }

    private fun money(n: Double, currency: String): String {
        val amt = String.format(Locale.US, "%.2f", n)
        return if (currency.equals("USD", ignoreCase = true)) "\$$amt" else "${currency.uppercase()} $amt"
    }

    private fun trimQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

    /** Stock noun for the item's product type, singular/plural by [qty]; null for box/
     *  unit items where the bare count already reads naturally ("5 left"). Measured items
     *  are counted nowhere here on purpose — they read in their unit, see [qtyNoun]. */
    private fun stockNoun(item: Item, qty: Double): String? = when (item.productType) {
        "set" -> if (qty == 1.0) "set" else "sets"
        "piece" -> if (qty == 1.0) "piece" else "pieces"
        else -> null
    }

    /** Noun for a REMAINING quantity: a measured item reads in its own unit ("1.5 kg
     *  left"), every other type falls back to [stockNoun]. Deliberately NOT used on the
     *  out-of-stock line, where "no kg in stock" would read wrong — a measured item that
     *  hits zero simply "has run out". */
    private fun qtyNoun(item: Item, qty: Double): String? =
        if (item.isMeasured) item.unit.trim().ifBlank { null } else stockNoun(item, qty)
}
