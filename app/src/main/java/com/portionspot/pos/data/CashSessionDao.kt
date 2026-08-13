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

    /** Every live shift for the shop, oldest first — the input [indexSessionsByDay] needs
     *  to answer "which session is which day's". Unbounded on purpose: a capped read would
     *  quietly stop finding the older days the backfill exists to repair. */
    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId AND deleted = 0 " +
            "ORDER BY openedAt ASC, id ASC"
    )
    suspend fun allLive(businessId: String): List<CashSession>

    /** The shifts belonging to one trading day, [from] inclusive to [to] exclusive.
     *  Plural for the same reason [openSessions] is — a day can hold two until they merge. */
    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId AND deleted = 0 " +
            "AND openedAt >= :from AND openedAt < :to ORDER BY openedAt ASC, id ASC"
    )
    fun observeForDay(businessId: String, from: Long, to: Long): Flow<List<CashSession>>

    /** Same window, read once — what a day close resolves its session by. */
    @Query(
        "SELECT * FROM cash_sessions WHERE businessId = :businessId AND deleted = 0 " +
            "AND openedAt >= :from AND openedAt < :to ORDER BY openedAt ASC, id ASC"
    )
    suspend fun forDayOnce(businessId: String, from: Long, to: Long): List<CashSession>

    /**
     * Close a shift the calendar has ended.
     *
     * `AND status = 'open'` is the idempotency guard, not decoration: without it a second
     * rollover pass would rewrite an already-closed shift's [closedAt] and stamp it dirty
     * again, which pushes a changed row up on every sync forever.
     *
     * [closedAt] is the end of the shift's OWN day and [at] is now. They are separate
     * arguments deliberately: `updatedAt` drives the last-writer-wins comparison on the
     * wire, and stamping it with yesterday's midnight would make this correction look
     * older than the row it is correcting and lose to it.
     */
    @Query(
        "UPDATE cash_sessions SET status = 'closed', closedAt = :closedAt, note = :note, " +
            "updatedAt = :at, pendingSync = 1 WHERE id = :id AND status = 'open'"
    )
    suspend fun closeForDay(id: String, note: String, closedAt: Long, at: Long)

    /**
     * Write a day's count onto its shift.
     *
     * ★ The interlock is `countedCash IS NULL`, NOT `status = 'open'`. The rollover closes
     * a day at midnight without counting it, so an owner closing yesterday in the morning
     * is writing a count onto an already-closed session — legitimately. What must never
     * happen twice is the COUNT, because [PosRepository.closeDay] moves real cash to the
     * safe alongside it. See [planDayClose], which encodes the same rule in Kotlin so it
     * can be tested without a database.
     *
     * `variance` is absent: it is GENERATED on the cloud and derived here. Storing it would
     * make three copies of one figure.
     */
    @Query(
        "UPDATE cash_sessions SET status = 'closed', closedAt = :closedAt, " +
            "closedBy = :closedBy, closedByName = :closedByName, countedCash = :countedCash, " +
            "expectedCash = :expectedCash, movedToSafe = :movedToSafe, floatTarget = :floatTarget, " +
            "note = COALESCE(:note, note), updatedAt = :at, pendingSync = 1 " +
            "WHERE id = :id AND countedCash IS NULL"
    )
    suspend fun closeWithCount(
        id: String,
        closedAt: Long,
        closedBy: String?,
        closedByName: String?,
        countedCash: Double,
        expectedCash: Double,
        movedToSafe: Double,
        floatTarget: Double,
        note: String?,
        at: Long,
    )

    // ---- backfill: rows written before a shift meant anything ----

    /**
     * Completed sales belonging to no shift, oldest first.
     *
     * Only `completed`. A quote is not a sale (it is excluded from every report and from
     * the push), and a parked sale is not one yet — it is hard-deleted and re-rung through
     * checkout when it is resumed, which is where it gets its shift. Stamping either would
     * put a session id on a row the cash-up must not count.
     *
     * Capped: attaching a session marks the sale dirty, so an unbounded first pass on a
     * long history would queue the whole history for re-upload in one go. Re-run until it
     * returns nothing — the query is self-limiting because it only ever sees NULLs.
     */
    @Query(
        "SELECT id AS id, soldAt AS at FROM sales WHERE businessId = :businessId " +
            "AND deleted = 0 AND sessionId IS NULL AND status = 'completed' " +
            "ORDER BY soldAt ASC LIMIT :limit"
    )
    suspend fun salesWithoutSession(businessId: String, limit: Int): List<UnstampedRow>

    /** Refunds belonging to no shift, oldest first. Money leaving the drawer has to be
     *  counted against the same day the sales were. */
    @Query(
        "SELECT id AS id, createdAt AS at FROM refunds WHERE businessId = :businessId " +
            "AND deleted = 0 AND sessionId IS NULL ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun refundsWithoutSession(businessId: String, limit: Int): List<UnstampedRow>

    // ★ `sales` tracks dirtiness as `synced` (0 = needs pushing), INVERTED from the
    // `pendingSync` every other table uses — see [repointSales] below. Both are correct
    // here; they simply are not the same flag.
    @Query("UPDATE sales SET sessionId = :sessionId, synced = 0 WHERE id IN (:ids) AND sessionId IS NULL")
    suspend fun attachSalesToSession(sessionId: String, ids: List<String>)

    @Query("UPDATE refunds SET sessionId = :sessionId, pendingSync = 1 WHERE id IN (:ids) AND sessionId IS NULL")
    suspend fun attachRefundsToSession(sessionId: String, ids: List<String>)

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
