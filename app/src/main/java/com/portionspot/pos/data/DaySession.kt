package com.portionspot.pos.data

import java.util.Calendar
import java.util.TimeZone

/**
 * A SHIFT IS A TRADING DAY.
 *
 * ── WHY THERE ARE NO OPEN/CLOSE SHIFT BUTTONS ─────────────────────────────────
 *
 * The owner does not want them, and the reason holds up: a shift button is a thing a
 * cashier must remember to press, and the one thing a busy till reliably forgets is the
 * bookkeeping step that does not stand between them and the next customer. A shift left
 * open across three days silently buckets three days of takings into one, and a shift
 * nobody opened leaves every sale of that day pointing at nothing — which, on the shared
 * schema, means the cash-up cannot resolve those sales and quietly leaves them out.
 *
 * So the period is decided by the calendar instead of by a button. One [CashSession] per
 * SHOP per day, rolling over at LOCAL MIDNIGHT — the same boundary [startOfDay] gives the
 * dashboard, the end-of-day screen and the web client's daily chart buckets, so "today"
 * means one thing everywhere and a day's takings add up the same on every surface.
 *
 * ── WHY PER SHOP AND NOT PER TILL ─────────────────────────────────────────────
 *
 * Not a style choice — the live database decides it:
 *
 *     create unique index uq_cash_sessions_one_open
 *         on public.cash_sessions (business_id)
 *         where status = 'open' and deleted = false;
 *
 * ONE OPEN SESSION PER BUSINESS. A second open row is REJECTED, and a rejected row does
 * not fail politely: the push that carried it fails, and the till's sales keep pointing at
 * a session id the cloud has never heard of. (The web repo carries a migration that would
 * relax this to one-per-till. It has never been applied, so it does not exist.)
 *
 * Everything here is therefore built so the app can only ever ask for ONE open session per
 * shop, and so that the state where two exist anyway — two phones offline from each other,
 * both correctly seeing none — resolves itself the same way on every device. The resolving
 * is [planSessionMerge]'s job and is deliberately left alone; this file's job is to make
 * sure a STALE day never reaches it (see [planDayRollover]).
 *
 * ── WHY PURE ──────────────────────────────────────────────────────────────────
 *
 * No Room, no Android. Two phones reach these decisions independently, seconds apart, in
 * whatever order their pulls happened to arrive, and they must land on the same answer
 * without asking each other. That property is only testable if the deciding is separable
 * from the storing, so it is.
 */

// ─────────────────────────────── the day boundary ───────────────────────────────

/**
 * Start-of-day millis for [at] in [zone] — the ONE definition of a day boundary.
 *
 * Promoted out of `PosRepository` so the repository, the sync engine and the end-of-day
 * screen all measure a day from the same edge. Three private copies of this is how a
 * day's sales end up in one bucket on the dashboard and another in the shift record.
 */
fun startOfDay(at: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val c = Calendar.getInstance(zone).apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return c.timeInMillis
}

/**
 * Midnight that ENDS the day containing [at] (i.e. the next day's start, exclusive).
 *
 * Computed by adding a CALENDAR day rather than 86,400,000 millis. A day is not always
 * 24 hours: on a daylight-saving change it is 23 or 25, and fixed arithmetic there lands
 * an hour either side of midnight — which silently files an evening sale under the wrong
 * trading day exactly twice a year, in the one place nobody looks for it.
 */
fun startOfNextDay(at: Long, zone: TimeZone = TimeZone.getDefault()): Long {
    val c = Calendar.getInstance(zone).apply {
        timeInMillis = startOfDay(at, zone)
        add(Calendar.DAY_OF_MONTH, 1)
    }
    return c.timeInMillis
}

/** The LAST millisecond of the day containing [at]. What a day session is closed at. */
fun endOfDay(at: Long, zone: TimeZone = TimeZone.getDefault()): Long =
    startOfNextDay(at, zone) - 1

/** Do [a] and [b] fall on the same trading day? */
fun sameTradingDay(a: Long, b: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
    startOfDay(a, zone) == startOfDay(b, zone)

// ─────────────────────────────── the rollover rule ───────────────────────────────

/**
 * The least a row needs for the day rules to reason about it. Extends [MergeableSession]
 * on purpose: rollover and merge must RANK the same rows the same way, so they share the
 * ordering contract rather than each inventing one.
 */
interface DaySessionRow : MergeableSession {
    val status: String
    val deleted: Boolean
    /**
     * What was physically counted out of the drawer for this day, or null if nobody has
     * counted it yet. This — not [status] — is what says a day has been SETTLED: the
     * rollover closes a day at midnight without anyone counting anything, so a closed
     * session and a counted one are genuinely different states. See [planDayClose].
     */
    val countedCash: Double?
}

/** Written on a session the rollover closed, so a reader can tell it from a counted one. */
const val DAY_ROLLOVER_NOTE = "Closed automatically at end of trading day"

/** Written on a past day rebuilt from its own sales by [planDayBackfill]. */
const val DAY_BACKFILL_NOTE = "Trading day reconstructed from its sales"

/** A session the rollover wants closed, and the instant it should be closed AT. */
data class DaySessionClose(
    val id: String,
    /** End of the session's OWN day, not now — a Tuesday shift did not end on Thursday. */
    val closedAt: Long,
    val note: String,
)

/** A session the rollover wants opened. */
data class DaySessionOpen(
    /** The trading day this session represents (start-of-day millis). */
    val dayStart: Long,
    /**
     * When to stamp it as opened. Deliberately the day's own MIDNIGHT, not `now`:
     *  - the session IS the day, so its opened_at is the day's edge, and every reporting
     *    query that buckets by `openedAt` then lands it in the day it labels;
     *  - two phones that both open today's session produce rows differing only by id,
     *    which makes [pickSurvivingSession]'s tie-break — and therefore the survivor —
     *    identical on both, instead of depending on which phone was switched on first.
     */
    val openedAt: Long,
)

/**
 * What has to happen for [now] to be trading under today's session.
 *
 * [current] is today's session when one already exists; [open] is filled instead when one
 * has to be created. Exactly one of the two is ever non-null.
 */
data class DayRolloverPlan<out T : DaySessionRow>(
    val closes: List<DaySessionClose>,
    val current: T?,
    val open: DaySessionOpen?,
)

/**
 * Close out any day that has ended, and say whether today still needs a session.
 *
 * ★ CLOSING IS NOT OPTIONAL AND MUST HAPPEN FIRST. The unique index only allows one open
 * session per shop, so yesterday's forgotten session BLOCKS today's. Skipping the close
 * because "there is already one open" is how a shop ends up trading for a week against a
 * session dated last Monday, with every day's takings piled into it.
 *
 * ★ IT ALSO HAS TO RUN BEFORE [planSessionMerge]. That rule keeps the OLDEST open session
 * and repoints the losers' sales onto it — correct among sessions of the same day, and
 * badly wrong across days: with yesterday's session still open it would keep YESTERDAY
 * and drag today's sales back into it. Rollover first means merge only ever sees a single
 * day's worth of candidates, which is the state its rule was written for.
 *
 * Idempotent and order-independent by construction:
 *  - it decides from each row's own `openedAt`, never from the order rows arrived in;
 *  - a second run sees the closed rows filtered out and plans nothing;
 *  - when several of today's sessions exist it keeps the one [pickSurvivingSession] would,
 *    so this and the merge rule cannot pick different survivors.
 *
 * Same-day duplicates are NOT closed here. Closing one would orphan the sales pointing at
 * it — moving those sales is [planSessionMerge]'s job and it does it in the same
 * transaction as the close. Rollover only ever closes days that are over.
 *
 * A session dated in the FUTURE (a phone with a wrong clock) is left alone rather than
 * closed: it is not stale, and there is no honest end-of-day to close it at. Today's
 * session is opened alongside it, and because today's midnight is the older `openedAt`,
 * the merge rule then keeps today's and repoints the skewed one's sales onto it.
 */
fun <T : DaySessionRow> planDayRollover(
    openSessions: List<T>,
    now: Long,
    zone: TimeZone = TimeZone.getDefault(),
): DayRolloverPlan<T> {
    val today = startOfDay(now, zone)
    val live = openSessions
        .distinctBy { it.id }
        .filter { !it.deleted && it.status == SessionStatus.OPEN }
    val closes = live
        .filter { startOfDay(it.openedAt, zone) < today }
        .sortedWith(compareBy<T>({ it.openedAt }, { it.id }))
        .map { DaySessionClose(it.id, endOfDay(it.openedAt, zone), DAY_ROLLOVER_NOTE) }
    val current = pickSurvivingSession(live.filter { startOfDay(it.openedAt, zone) == today })
    return DayRolloverPlan(
        closes = closes,
        current = current,
        open = if (current == null) DaySessionOpen(today, today) else null,
    )
}

/**
 * The one session that stands for each trading day, keyed by that day's start.
 *
 * Chosen with [pickSurvivingSession] rather than "the first one found", because a day that
 * has not been merged yet can legitimately hold more than one session and two devices must
 * still stamp their rows with the SAME id — otherwise the backfill below writes a split
 * that the next merge has to undo.
 */
fun <T : DaySessionRow> indexSessionsByDay(
    sessions: List<T>,
    zone: TimeZone = TimeZone.getDefault(),
): Map<Long, String> =
    sessions.filter { !it.deleted }
        .groupBy { startOfDay(it.openedAt, zone) }
        .mapNotNull { (day, rows) -> pickSurvivingSession(rows)?.let { day to it.id } }
        .toMap()

// ─────────────────────────────── the backfill rule ───────────────────────────────

/** A sale or refund carrying no session, and the instant it happened. */
data class UnstampedRow(
    val id: String,
    /** `soldAt` for a sale, `createdAt` for a refund. Null/zero => date unknown. */
    val at: Long?,
)

/**
 * How to attach rows written before shifts existed to the day they actually happened on.
 *
 * [daysToCreate] can only ever contain days STRICTLY BEFORE today, and the sessions built
 * from it are born CLOSED. That is the invariant that makes the backfill safe to run
 * against the live database at all: it is structurally incapable of adding a second OPEN
 * session and tripping the unique index. Rows dated today land in [deferred] until a real
 * sale has opened today's session (see [planDayRollover]), and the next pass picks them up.
 */
data class DayBackfillPlan(
    /** Trading days (start-of-day millis) needing a CLOSED session, ascending. */
    val daysToCreate: List<Long>,
    /** Trading day -> the ids belonging to it, ascending. */
    val assignments: Map<Long, List<String>>,
    /** Rows whose day cannot be determined. Left alone — a guessed day is worse than none. */
    val unresolved: List<String>,
    /** Rows dated today or later with no session yet. Left for the next pass. */
    val deferred: List<String>,
)

/**
 * Bucket unstamped [rows] onto trading days, reusing the sessions in [sessionByDay] and
 * naming the past days that still need one.
 *
 * [today] is the caller's start-of-day, passed in rather than read from the clock so the
 * plan is a function of its inputs and can be tested at a fixed instant.
 *
 * A row with no usable timestamp is NEVER invented a day for. Filing it under "probably
 * today" would put someone else's takings in this day's count, and a cash-up that is
 * wrong is worse than one that is visibly incomplete.
 */
fun planDayBackfill(
    rows: List<UnstampedRow>,
    sessionByDay: Map<Long, String>,
    today: Long,
    zone: TimeZone = TimeZone.getDefault(),
): DayBackfillPlan {
    val unresolved = mutableListOf<String>()
    val deferred = mutableListOf<String>()
    // Sorted so the plan reads chronologically and two devices emit identical plans.
    val buckets = sortedMapOf<Long, MutableList<String>>()
    for (row in rows.distinctBy { it.id }) {
        val at = row.at
        if (at == null || at <= 0L) {
            unresolved += row.id
            continue
        }
        val day = startOfDay(at, zone)
        if (day >= today && !sessionByDay.containsKey(day)) {
            deferred += row.id
            continue
        }
        buckets.getOrPut(day) { mutableListOf() } += row.id
    }
    return DayBackfillPlan(
        daysToCreate = buckets.keys.filter { !sessionByDay.containsKey(it) },
        assignments = buckets.mapValues { (_, ids) -> ids.sorted() },
        unresolved = unresolved.sorted(),
        deferred = deferred.sorted(),
    )
}

// ─────────────────────────── the close, mapped onto the day ───────────────────────────

/**
 * The figures a day close writes onto its day's [CashSession] — the closing half of the
 * shift, in the exact shape `public.cash_sessions` is waiting for.
 *
 * `variance` is NOT here and must never be: it is GENERATED on the cloud
 * (`counted_cash − expected_cash`) and derived on this side, and a third copy is how a
 * shop ends up with two different answers to "how short were we".
 */
data class DaySessionClosure(
    val id: String,
    val closedAt: Long,
    val closedBy: String?,
    val closedByName: String?,
    val countedCash: Double,
    val expectedCash: Double,
    val movedToSafe: Double,
    val floatTarget: Double,
    val note: String?,
)

/**
 * Map a day close onto the session for that day, or return null when there is nothing to do.
 *
 * ★ NULL IS THE IDEMPOTENCY GUARD, and it guards real money. [PosRepository.closeDay] does
 * more than record a figure: it writes a `variance` ledger row and TRANSFERS the excess
 * takings from the till into the safe. Running that twice — a double tap, or the owner's
 * phone and the cashier's both closing the same shop-day — moves the cash twice, and the
 * second move comes out of a drawer that no longer holds it.
 *
 * ★ THE GUARD IS `countedCash`, NOT `status`. Status would be the obvious choice and it is
 * the wrong one: [planDayRollover] closes a day at midnight WITHOUT anyone counting it, so
 * by the time an owner closes yesterday over breakfast that session is already `closed`.
 * Refusing there would silently drop the count on the floor and leave the shared row saying
 * a day was never counted when it was. A day that has a counted figure has been settled;
 * a day that merely ended has not.
 *
 * A null [session] also returns null. A day with no session was never traded through this
 * app's till — there is nothing to close, and minting one here would be minting a session
 * for a past day, which is exactly what the unique index exists to prevent.
 */
fun planDayClose(
    session: DaySessionRow?,
    closedAt: Long,
    countedCash: Double,
    expectedCash: Double,
    movedToSafe: Double,
    floatTarget: Double,
    closedBy: String? = null,
    closedByName: String? = null,
    note: String? = null,
): DaySessionClosure? {
    if (session == null) return null
    if (session.deleted) return null
    if (session.countedCash != null) return null
    return DaySessionClosure(
        id = session.id,
        closedAt = closedAt,
        closedBy = closedBy,
        closedByName = closedByName,
        countedCash = countedCash,
        expectedCash = expectedCash,
        movedToSafe = movedToSafe,
        floatTarget = floatTarget,
        note = note,
    )
}
