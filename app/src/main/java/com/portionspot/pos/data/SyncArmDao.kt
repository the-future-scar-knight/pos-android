package com.portionspot.pos.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Re-arm every row this till OWNS so it uploads to a database it has not met before.
 *
 * ══ The failure this exists to stop ══
 * A dirty flag records "this row has been sent", and nothing on it says WHERE. Point a
 * till at a second project and every row it already pushed to the first is still marked
 * clean, so it is never offered to the new database — not once, not ever. The sync
 * reports success, because from the engine's point of view there was nothing to send.
 *
 * Observed on the shop's own data: customer `Ryan` was created before the till was
 * repointed, so he stayed behind, while `Tim` — created after — went up normally. Two
 * sales and a `credit_owed` of $15 then landed in the cloud referencing a customer that
 * exists in neither database. Nothing errored, and nothing enforces the reference, so the
 * back office showed a debt with no debtor. That is the shape of this bug: not a crash,
 * a quietly incomplete shop.
 *
 * The same reasoning is why [SyncConfig.clearConnection] already forgets the cursors and
 * the adopted business id. The dirty flags are the half that was missed.
 *
 * ══ Why re-sending is safe ══
 * Every push in [com.portionspot.pos.sync.PosSyncEngine] upserts on the row's own uuid,
 * which the device generated and never changes. Re-offering a row the database already
 * has is therefore an update to identical values, not a duplicate. Sending a row twice
 * costs a request; NOT sending it loses a customer, and only one of those is recoverable.
 */
@Dao
interface SyncArmDao {

    // ★ `sales` tracks its dirty state as `synced` (0 = needs pushing) while every other
    // table uses `pendingSync` (1 = needs pushing). The two are INVERTED, so writing
    // `pendingSync = 1` here would not fail to compile against the wrong column — it
    // would mean the exact opposite of what it says. Same trap [CashSessionDao] flags.
    @Query("UPDATE sales SET synced = 0")
    suspend fun armSales(): Int

    @Query("UPDATE customers SET pendingSync = 1")
    suspend fun armCustomers(): Int

    @Query("UPDATE refunds SET pendingSync = 1")
    suspend fun armRefunds(): Int

    @Query("UPDATE credit_transactions SET pendingSync = 1")
    suspend fun armCredit(): Int

    @Query("UPDATE stock_movements SET pendingSync = 1")
    suspend fun armStockMovements(): Int

    @Query("UPDATE mobile_money_receipts SET pendingSync = 1")
    suspend fun armMobileMoney(): Int

    @Query("UPDATE cash_txns SET pendingSync = 1")
    suspend fun armCashMovements(): Int

    @Query("UPDATE cash_sessions SET pendingSync = 1")
    suspend fun armCashSessions(): Int

    /**
     * Arm every table the engine actually pushes, and return how many rows were touched.
     *
     * ★ NOT `items`. The catalogue is PULL-ONLY — the web owns it and this app never
     * pushes a product — so arming it would mark the whole catalogue dirty to no effect
     * and leave a queue indicator that can never drain.
     *
     * ★ NOT `sale_items`, `sale_payments`, `refund_items`, `refund_payments`. These have
     * no dirty flag of their own by design: they travel with their header, in the same
     * push, and are only marked clean once all three parts have landed. Arming the header
     * carries its children with it.
     *
     * ★ NOT `expenses`, `suppliers`, `purchase_orders`, `purchase_order_items` or
     * `audit_log`. Each still needs a uuid-keyed DTO before it can go up at all; marking
     * them dirty would queue rows against a push that does not exist yet.
     *
     * One transaction, so a till interrupted mid-arm is either fully armed or untouched —
     * a half-armed device would silently upload part of its history and keep the rest.
     */
    @Transaction
    suspend fun armAll(): Int =
        armSales() + armCustomers() + armRefunds() + armCredit() +
            armStockMovements() + armMobileMoney() + armCashMovements() + armCashSessions()
}
