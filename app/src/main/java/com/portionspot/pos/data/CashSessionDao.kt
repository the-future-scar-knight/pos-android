package com.portionspot.pos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The trading shift.
 *
 * Reads are deliberately plural where you might expect singular: [openSessions] returns a
 * LIST because two tills that were offline from each other can each have opened one, and
 * the whole point of the merge rule is that the app has to be able to SEE that state.
 * A `LIMIT 1` here would assume the conflict away and leave one shift's takings pointing
 * at a session nobody ever counts.
 */
@Dao
interface CashSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CashSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<CashSession>)

    @Query("SELECT * FROM cash_sessions WHERE id = :id")
    suspend fun getById(id: String): CashSession?

    /** Every OPEN shift for the shop, oldest first. More than one row is the conflict
     *  [planSessionMerge] settles — not an error state, and not to be treated as one. */
    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId " +
            "AND status = 'open' AND deleted = 0 ORDER BY openedAt ASC, id ASC"
    )
    suspend fun openSessions(businessId: String): List<CashSession>

    /** The shift the UI should treat as current. Ordered the same way the merge rule
     *  ranks survivors, so what the cashier sees matches what the merge will keep. */
    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId " +
            "AND status = 'open' AND deleted = 0 ORDER BY openedAt ASC, id ASC LIMIT 1"
    )
    fun observeOpenSession(businessId: String): Flow<CashSession?>

    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY openedAt DESC LIMIT :limit"
    )
    suspend fun recent(businessId: String, limit: Int): List<CashSession>

    @Query("SELECT * FROM cash_sessions WHERE pendingSync = 1 AND deleted = 0")
    suspend fun pending(): List<CashSession>

    @Query("UPDATE cash_sessions SET pendingSync = 0 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /**
     * Move every sale on the losing shifts onto the survivor, and mark them dirty so the
     * correction goes UP. Run inside the same transaction as [closeMerged] so there is no
     * moment where a sale points at a shift that has already been closed.
     */
    // ★ `sales` tracks its dirty state as `synced` (0 = needs pushing), NOT as the
    // `pendingSync` flag every other table uses — and the two are INVERTED, so writing
    // `pendingSync = 1` here would not merely fail to compile, it would mean the opposite
    // of what it says. `refunds` below really does use `pendingSync`.
    @Query("UPDATE sales SET sessionId = :winner, synced = 0 WHERE sessionId IN (:losers)")
    suspend fun repointSales(winner: String, losers: List<String>)

    @Query("UPDATE refunds SET sessionId = :winner, pendingSync = 1 WHERE sessionId IN (:losers)")
    suspend fun repointRefunds(winner: String, losers: List<String>)

    /**
     * Close a merged-away shift. CLOSED, never deleted: someone really did open a drawer
     * and take real money, and a cash-up that is missing a shift is harder to trust than
     * one that shows a merged one. The note is what tells the owner which it was.
     */
    @Query(
        "UPDATE cash_sessions SET status = 'closed', closedAt = :at, note = :note, " +
            "updatedAt = :at, pendingSync = 1 WHERE id = :id"
    )
    suspend fun closeMerged(id: String, note: String, at: Long)

    /**
     * Apply a whole merge atomically: repoint the losers' sales and refunds onto the
     * survivor, then close each loser with the note that says what became of it.
     *
     * One transaction, not three calls, because the intermediate states are all wrong in
     * ways that cost money: between the repoint and the close there is a moment where a
     * shift is open with no takings, and between the two repoints a moment where a sale
     * and its own refund belong to different shifts. A cash-up run in either window
     * balances to the wrong number and gives no sign that it did.
     */
    @Transaction
    suspend fun applyMerge(winnerId: String, losers: List<Pair<String, String>>, at: Long) {
        if (losers.isEmpty()) return
        val loserIds = losers.map { it.first }
        repointSales(winnerId, loserIds)
        repointRefunds(winnerId, loserIds)
        for ((loserId, note) in losers) closeMerged(loserId, note, at)
    }

    @Query("DELETE FROM cash_sessions WHERE businessId = :businessId")
    suspend fun wipe(businessId: String)
}
