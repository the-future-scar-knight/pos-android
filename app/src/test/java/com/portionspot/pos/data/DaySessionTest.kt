package com.portionspot.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

/**
 * A shift is a trading day, pinned.
 *
 * These tests are about CONVERGENCE and about MONEY MOVING ONCE, not about arithmetic.
 * Two phones reach these decisions seconds apart, offline from each other, having pulled
 * rows in whatever order they happened to arrive in — so most assertions here are of the
 * form "the order of discovery must not change the answer" and "running it twice must not
 * do it twice". The one thing offline tills are guaranteed not to agree on is order, and
 * the one thing a cash-up cannot survive is the same takings being moved to the safe twice.
 *
 * The clock is fixed and the zone is explicit throughout. A test that reads the device's
 * timezone passes in Harare and fails in a CI container set to UTC, which is the worst kind
 * of failure: it looks like the code and it is not.
 */
class DaySessionTest {

    /** UTC+2, no daylight saving — the shop's own zone, and stable arithmetic. */
    private val tz: TimeZone = TimeZone.getTimeZone("Africa/Harare")

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, zone: TimeZone = tz): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private data class S(
        override val id: String,
        override val openedAt: Long,
        override val status: String = SessionStatus.OPEN,
        override val deleted: Boolean = false,
        override val countedCash: Double? = null,
    ) : DaySessionRow

    // ────────────────────────────── the day boundary ──────────────────────────────

    @Test
    fun aDayRunsFromLocalMidnightToLocalMidnight() {
        val noon = at(2026, 8, 12, 12, 30)
        assertEquals(at(2026, 8, 12), startOfDay(noon, tz))
        assertEquals(at(2026, 8, 13), startOfNextDay(noon, tz))
        assertEquals(at(2026, 8, 13) - 1, endOfDay(noon, tz))
    }

    @Test
    fun theLastMillisecondOfADayStillBelongsToIt() {
        val day = at(2026, 8, 12)
        val lastMs = endOfDay(day, tz)
        assertTrue(sameTradingDay(day, lastMs, tz))
        assertFalse(sameTradingDay(day, lastMs + 1, tz))
        assertEquals(at(2026, 8, 13), startOfDay(lastMs + 1, tz))
    }

    /**
     * A day is not always 24 hours. Adding 86,400,000 millis across a daylight-saving change
     * lands an hour either side of midnight, which files an evening sale under the wrong
     * trading day exactly twice a year — in the one place nobody thinks to look.
     */
    @Test
    fun daylightSavingDoesNotMoveTheBoundary() {
        val ny = TimeZone.getTimeZone("America/New_York")
        // 2026-03-08 is the US spring-forward: a 23-hour day.
        val duringShortDay = at(2026, 3, 8, 12, 0, ny)
        val start = startOfDay(duringShortDay, ny)
        val next = startOfNextDay(duringShortDay, ny)
        assertEquals(at(2026, 3, 8, 0, 0, ny), start)
        assertEquals(at(2026, 3, 9, 0, 0, ny), next)
        assertEquals(23 * 60 * 60 * 1000L, next - start)
        // And the naive arithmetic really would have crossed the boundary early.
        assertFalse(sameTradingDay(start, start + 24 * 60 * 60 * 1000L, ny))
    }

    // ────────────────────────────── the rollover rule ──────────────────────────────

    private val today = at(2026, 8, 12)
    private val now = at(2026, 8, 12, 9, 15)
    private val yesterday = at(2026, 8, 11)

    @Test
    fun aShopWithNoShiftOpensTodays() {
        val plan = planDayRollover(emptyList<S>(), now, tz)
        assertTrue(plan.closes.isEmpty())
        assertNull(plan.current)
        assertEquals(DaySessionOpen(today, today), plan.open)
    }

    @Test
    fun todaysShiftIsReusedAndNothingIsOpened() {
        val s = S("a", at(2026, 8, 12, 7, 0))
        val plan = planDayRollover(listOf(s), now, tz)
        assertTrue(plan.closes.isEmpty())
        assertEquals(s, plan.current)
        assertNull(plan.open)
    }

    /**
     * The case the whole file exists for: yesterday's session is still open, and the cloud
     * allows exactly ONE open session per business, so it physically blocks today's. It has
     * to be closed, and closed at the end of ITS OWN day — a Tuesday shift did not end on
     * Wednesday morning.
     */
    @Test
    fun aShiftLeftOpenFromAnEarlierDayIsClosedAtTheEndOfThatDay() {
        val stale = S("a", at(2026, 8, 11, 8, 0))
        val plan = planDayRollover(listOf(stale), now, tz)
        assertEquals(1, plan.closes.size)
        assertEquals("a", plan.closes[0].id)
        assertEquals(endOfDay(yesterday, tz), plan.closes[0].closedAt)
        assertEquals(DAY_ROLLOVER_NOTE, plan.closes[0].note)
        // And today still gets one — closing without opening leaves the till with no shift.
        assertNull(plan.current)
        assertEquals(DaySessionOpen(today, today), plan.open)
    }

    @Test
    fun everyStaleDayIsClosedNotJustTheOldest() {
        val plan = planDayRollover(
            listOf(
                S("a", at(2026, 8, 9, 8, 0)),
                S("b", at(2026, 8, 10, 8, 0)),
                S("c", at(2026, 8, 11, 8, 0)),
            ),
            now, tz
        )
        assertEquals(listOf("a", "b", "c"), plan.closes.map { it.id })
        assertEquals(
            listOf(at(2026, 8, 9), at(2026, 8, 10), at(2026, 8, 11)).map { endOfDay(it, tz) },
            plan.closes.map { it.closedAt }
        )
    }

    /**
     * Same-day duplicates are deliberately NOT closed here. Closing one would orphan the
     * sales pointing at it — moving those is [planSessionMerge]'s job, and it does it in the
     * same transaction as the close.
     */
    @Test
    fun twoShiftsOnTheSameDayAreLeftForTheMergeRule() {
        val a = S("a", at(2026, 8, 12, 7, 0))
        val b = S("b", at(2026, 8, 12, 8, 0))
        val plan = planDayRollover(listOf(a, b), now, tz)
        assertTrue(plan.closes.isEmpty())
        assertNull(plan.open)
        // And the one it keeps is the one the merge rule would keep, or the two rules would
        // stamp sales onto different sessions and undo each other forever.
        assertEquals(pickSurvivingSession(listOf(a, b)), plan.current)
    }

    @Test
    fun closedAndDeletedRowsAreNotCandidates() {
        val plan = planDayRollover(
            listOf(
                S("a", at(2026, 8, 11, 8, 0), status = SessionStatus.CLOSED),
                S("b", at(2026, 8, 11, 8, 0), deleted = true),
                S("c", at(2026, 8, 12, 8, 0), status = SessionStatus.CLOSED),
            ),
            now, tz
        )
        assertTrue(plan.closes.isEmpty())
        assertNull(plan.current)     // the closed row for today does not count as today's
        assertNotNull(plan.open)
    }

    /**
     * A phone with a wrong clock can hold a session dated tomorrow. It is not stale, and
     * there is no honest end-of-day to close it at, so it is left alone — today's is opened
     * alongside it and the merge rule (older `openedAt` wins) then keeps today's and drags
     * the skewed one's sales back where they belong.
     */
    @Test
    fun aFutureDatedShiftIsLeftAloneAndTodayStillOpens() {
        val skewed = S("z", at(2026, 8, 13, 6, 0))
        val plan = planDayRollover(listOf(skewed), now, tz)
        assertTrue(plan.closes.isEmpty())
        assertNull(plan.current)
        assertEquals(DaySessionOpen(today, today), plan.open)
        // Today's midnight is older than tomorrow's, so the merge keeps today's.
        val opened = S("new", plan.open!!.openedAt)
        assertEquals(opened, pickSurvivingSession(listOf(skewed, opened)))
    }

    @Test
    fun runningTheRolloverTwiceChangesNothingTheSecondTime() {
        val stale = S("a", at(2026, 8, 11, 8, 0))
        val first = planDayRollover(listOf(stale), now, tz)
        // Apply it the way the repository does: the closed row stops being an open session,
        // and the newly opened one takes its place.
        val afterApply = listOf(S("new", first.open!!.openedAt))
        val second = planDayRollover(afterApply, now, tz)
        assertTrue(second.closes.isEmpty())
        assertNull(second.open)
        assertEquals("new", second.current?.id)
    }

    @Test
    fun theAnswerDoesNotDependOnTheOrderTheRowsArrive() {
        val rows = listOf(
            S("d4", at(2026, 8, 9, 8, 0)),
            S("a1", at(2026, 8, 12, 7, 0)),
            S("c3", at(2026, 8, 12, 7, 0)),
            S("b2", at(2026, 8, 11, 20, 0)),
        )
        val expected = planDayRollover(rows, now, tz)
        val r = Random(1234)
        repeat(200) {
            val got = planDayRollover(rows.shuffled(r), now, tz)
            assertEquals(expected.closes, got.closes)
            assertEquals(expected.current, got.current)
            assertEquals(expected.open, got.open)
        }
    }

    /**
     * WHY THE ROLLOVER MUST RUN BEFORE THE MERGE, stated as a test so the ordering in
     * `PosSyncEngine.mergeOpenSessions` cannot be "tidied" back the other way.
     *
     * The merge rule keeps the OLDEST open session and repoints the losers' sales onto it.
     * That is right among sessions of the same day and badly wrong across days: with
     * yesterday's still open it keeps YESTERDAY and drags today's takings into it, on every
     * device at once. Close the ended days first and the merge only ever ranks one day's
     * candidates, which is the state its rule was written for.
     */
    @Test
    fun theMergeRuleWouldKeepYesterdayIfTheRolloverHadNotRunFirst() {
        val stale = S("a", at(2026, 8, 11, 8, 0))
        val todays = S("b", at(2026, 8, 12, 0, 0))
        // Unrolled: the merge keeps the stale one and today's sales would follow it back.
        assertEquals(stale, pickSurvivingSession(listOf(stale, todays)))
        // Rolled over first: the stale one is no longer open, so the merge cannot pick it.
        val plan = planDayRollover(listOf(stale, todays), now, tz)
        assertEquals(listOf("a"), plan.closes.map { it.id })
        val stillOpen = listOf(stale, todays).filterNot { s -> plan.closes.any { it.id == s.id } }
        assertEquals(todays, pickSurvivingSession(stillOpen))
        assertNull(planSessionMerge(stillOpen))   // one left: nothing to merge
    }

    @Test
    fun duplicateRowsFromTwoPullsAreCollapsed() {
        val stale = S("a", at(2026, 8, 11, 8, 0))
        val plan = planDayRollover(listOf(stale, stale, stale), now, tz)
        assertEquals(1, plan.closes.size)
    }

    // ────────────────────────────── indexing days ──────────────────────────────

    @Test
    fun eachDayResolvesToTheSessionTheMergeRuleWouldKeep() {
        val a = S("bbb", at(2026, 8, 12, 7, 0))
        val b = S("aaa", at(2026, 8, 12, 9, 0))
        val c = S("ccc", at(2026, 8, 11, 9, 0))
        val index = indexSessionsByDay(listOf(a, b, c), tz)
        assertEquals(mapOf(today to "bbb", yesterday to "ccc"), index)
        // Same answer whichever order the rows arrived in.
        assertEquals(index, indexSessionsByDay(listOf(c, b, a), tz))
    }

    @Test
    fun deletedSessionsNeverStandForADay() {
        val index = indexSessionsByDay(listOf(S("a", today, deleted = true)), tz)
        assertTrue(index.isEmpty())
    }

    // ────────────────────────────── the backfill rule ──────────────────────────────

    @Test
    fun orphanSalesLandOnTheDayTheyHappened() {
        val rows = listOf(
            UnstampedRow("s1", at(2026, 8, 10, 9, 0)),
            UnstampedRow("s2", at(2026, 8, 10, 17, 0)),
            UnstampedRow("s3", at(2026, 8, 11, 11, 0)),
        )
        val plan = planDayBackfill(rows, emptyMap(), today, tz)
        assertEquals(listOf(at(2026, 8, 10), yesterday), plan.daysToCreate)
        assertEquals(listOf("s1", "s2"), plan.assignments[at(2026, 8, 10)])
        assertEquals(listOf("s3"), plan.assignments[yesterday])
        assertTrue(plan.unresolved.isEmpty())
        assertTrue(plan.deferred.isEmpty())
    }

    @Test
    fun aDayThatAlreadyHasAShiftIsReusedNotDuplicated() {
        val plan = planDayBackfill(
            listOf(UnstampedRow("s1", at(2026, 8, 11, 9, 0))),
            mapOf(yesterday to "existing"),
            today, tz
        )
        assertTrue(plan.daysToCreate.isEmpty())
        assertEquals(listOf("s1"), plan.assignments[yesterday])
    }

    /**
     * The invariant that makes this safe to run against the live database: the backfill can
     * only ever create sessions for days STRICTLY BEFORE today, and those are born closed.
     * It is structurally incapable of adding a second OPEN session and tripping
     * `uq_cash_sessions_one_open`.
     */
    @Test
    fun theBackfillNeverCreatesADayForTodayOrLater() {
        val plan = planDayBackfill(
            listOf(
                UnstampedRow("s1", at(2026, 8, 12, 9, 0)),    // today
                UnstampedRow("s2", at(2026, 8, 13, 9, 0)),    // a skewed clock
                UnstampedRow("s3", at(2026, 8, 10, 9, 0)),    // a real past day
            ),
            emptyMap(), today, tz
        )
        assertEquals(listOf(at(2026, 8, 10)), plan.daysToCreate)
        assertTrue(plan.daysToCreate.all { it < today })
        assertEquals(listOf("s1", "s2"), plan.deferred)
    }

    @Test
    fun rowsOnTodayAttachOnceTodaysShiftExists() {
        val plan = planDayBackfill(
            listOf(UnstampedRow("s1", at(2026, 8, 12, 9, 0))),
            mapOf(today to "todays"),
            today, tz
        )
        assertTrue(plan.deferred.isEmpty())
        assertTrue(plan.daysToCreate.isEmpty())
        assertEquals(listOf("s1"), plan.assignments[today])
    }

    /** A guessed day puts someone else's takings in this day's count. Never guess. */
    @Test
    fun aRowWithNoUsableDateIsLeftAlone() {
        val plan = planDayBackfill(
            listOf(UnstampedRow("s1", null), UnstampedRow("s2", 0L), UnstampedRow("s3", -5L)),
            emptyMap(), today, tz
        )
        assertEquals(listOf("s1", "s2", "s3"), plan.unresolved)
        assertTrue(plan.daysToCreate.isEmpty())
        assertTrue(plan.assignments.isEmpty())
    }

    @Test
    fun theBackfillPlanDoesNotDependOnTheOrderTheRowsArrive() {
        val rows = listOf(
            UnstampedRow("s5", at(2026, 8, 10, 9, 0)),
            UnstampedRow("s1", at(2026, 8, 11, 9, 0)),
            UnstampedRow("s3", at(2026, 8, 10, 18, 0)),
            UnstampedRow("s2", null),
            UnstampedRow("s4", at(2026, 8, 12, 9, 0)),
        )
        val expected = planDayBackfill(rows, emptyMap(), today, tz)
        val r = Random(99)
        repeat(200) {
            assertEquals(expected, planDayBackfill(rows.shuffled(r), emptyMap(), today, tz))
        }
    }

    @Test
    fun aSecondBackfillPassOverARepairedShopPlansNothing() {
        // The repository only ever feeds it rows whose sessionId IS NULL, so a repaired
        // shop feeds it nothing at all.
        val plan = planDayBackfill(emptyList(), mapOf(yesterday to "x"), today, tz)
        assertTrue(plan.daysToCreate.isEmpty())
        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.unresolved.isEmpty())
        assertTrue(plan.deferred.isEmpty())
    }

    // ────────────────────────────── closing the day ──────────────────────────────

    private fun close(session: DaySessionRow?, closedAt: Long = now) = planDayClose(
        session = session,
        closedAt = closedAt,
        countedCash = 210.0,
        expectedCash = 200.0,
        movedToSafe = 150.0,
        floatTarget = 60.0,
        closedBy = "u1",
        closedByName = "Tendai",
        note = "counted twice",
    )

    @Test
    fun aCountLandsOnTheDaysShift() {
        val c = close(S("a", at(2026, 8, 12, 7, 0)))
        assertNotNull(c)
        assertEquals("a", c!!.id)
        assertEquals(now, c.closedAt)
        assertEquals(210.0, c.countedCash, 1e-9)
        assertEquals(200.0, c.expectedCash, 1e-9)
        assertEquals(150.0, c.movedToSafe, 1e-9)
        assertEquals(60.0, c.floatTarget, 1e-9)
        assertEquals("Tendai", c.closedByName)
        // Counted and expected both travel, so the reader derives the variance rather than
        // being handed a third copy of it. `variance` is GENERATED on the cloud.
        assertEquals(10.0, c.countedCash - c.expectedCash, 1e-9)
    }

    /**
     * THE MONEY GUARD. `closeDay` moves the excess takings from the till into the safe. A
     * retap, or the owner's phone and the cashier's both closing the same shop-day, must not
     * move it twice — the second move comes out of a drawer that no longer holds it.
     */
    @Test
    fun aDayThatHasAlreadyBeenCountedCannotBeCountedAgain() {
        assertNull(close(S("a", at(2026, 8, 12, 7, 0), countedCash = 210.0)))
        // Even a zero count is a count — someone looked in the drawer and found nothing.
        assertNull(close(S("a", at(2026, 8, 12, 7, 0), countedCash = 0.0)))
    }

    /**
     * ...and the guard is the COUNT, not the status. The rollover closes a day at midnight
     * without anyone counting it, so the owner closing yesterday over breakfast is writing a
     * count onto an already-closed session — legitimately. Refusing here would drop the
     * count on the floor and leave the shared row claiming a day was never counted.
     */
    @Test
    fun aDayTheCalendarClosedCanStillBeCounted() {
        val rolledOver = S(
            "a", at(2026, 8, 11, 7, 0),
            status = SessionStatus.CLOSED, countedCash = null
        )
        val c = close(rolledOver)
        assertNotNull(c)
        assertEquals("a", c!!.id)
    }

    @Test
    fun closingAPastDayClosesThatDaysShiftNotTodays() {
        // Resolved by the day being closed, so a late-night close of yesterday lands on
        // yesterday. The session's own openedAt proves which day the caller resolved.
        val threeDaysAgo = S("old", at(2026, 8, 9, 8, 0), status = SessionStatus.CLOSED)
        val c = close(threeDaysAgo)
        assertNotNull(c)
        assertEquals("old", c!!.id)
        assertEquals(at(2026, 8, 9), startOfDay(threeDaysAgo.openedAt, tz))
        // closedAt is when it was COUNTED, which is genuinely now, not the day's end.
        assertEquals(now, c.closedAt)
    }

    @Test
    fun thereIsNothingToCloseOnADayTheShopNeverTradedThrough() {
        assertNull(close(null))
        assertNull(close(S("a", today, deleted = true)))
    }

    // ──────────────────── which session speaks for the day (pickDaySession) ────────────────────

    @Test
    fun aCountedSessionOutranksAnOlderUncountedOne() {
        // The state the cash screen kept getting wrong. Two sessions for one day — two
        // devices offline from each other each opened one — and the count landed on the
        // YOUNGER of them. The merge cannot collapse the pair any more (the count closed
        // one of them), so the older uncounted row would go on winning forever and the day
        // would read as never counted on the phone that counted it.
        val older = S("aaa", at(2026, 8, 18, 7, 0))
        val counted = S("zzz", at(2026, 8, 18, 9, 0), status = SessionStatus.CLOSED, countedCash = 61.0)
        assertEquals(counted, pickDaySession(listOf(older, counted)))
        assertEquals(counted, pickDaySession(listOf(counted, older)))
    }

    @Test
    fun withNoCountAnywhereItPicksExactlyWhatTheMergeWould() {
        // The uncounted case must not drift from the merge's survivor, or the device writes
        // its count onto a row the merge is about to close.
        val rows = listOf(
            S("d4", at(2026, 8, 18, 11, 0)),
            S("a1", at(2026, 8, 18, 7, 0)),
            S("c3", at(2026, 8, 18, 7, 0)),
        )
        assertEquals(pickSurvivingSession(rows), pickDaySession(rows))
    }

    @Test
    fun twoDevicesReadTheSameCountOffTheSameRow() {
        // Same requirement as everywhere else here: the answer cannot depend on the order
        // the rows were pulled in, or two phones disagree about whether the day is settled.
        val rows = listOf(
            S("m", at(2026, 8, 18, 8, 0)),
            S("b", at(2026, 8, 18, 9, 0), status = SessionStatus.CLOSED, countedCash = 61.0),
            S("z", at(2026, 8, 18, 6, 0)),
        )
        val expected = pickDaySession(rows)
        assertEquals("b", expected!!.id)
        val r = Random(11)
        repeat(100) { assertEquals(expected, pickDaySession(rows.shuffled(r))) }
    }

    @Test
    fun aTombstonedCountDoesNotSettleTheDay() {
        val deletedCount = S("zzz", at(2026, 8, 18, 9, 0), deleted = true, countedCash = 61.0)
        val live = S("aaa", at(2026, 8, 18, 7, 0))
        assertEquals(live, pickDaySession(listOf(deletedCount, live)))
        assertNull(pickDaySession(listOf(deletedCount)))
    }

    @Test
    fun aDayWithNoSessionsHasNothingToSpeakForIt() {
        assertNull(pickDaySession(emptyList<S>()))
    }

    @Test
    fun theCloseGuardSeesACountHeldOnANonSurvivingSession() {
        // The two rules together, which is how they are actually used: [pickDaySession]
        // resolves the day's row and [planDayClose] refuses to count it twice. Before this
        // the pair let a settled day be counted again — moving the takings into the safe a
        // second time, out of a drawer that no longer held them.
        val rows = listOf(
            S("aaa", at(2026, 8, 18, 7, 0)),
            S("zzz", at(2026, 8, 18, 9, 0), status = SessionStatus.CLOSED, countedCash = 61.0),
        )
        assertNull(
            planDayClose(
                session = pickDaySession(rows),
                closedAt = at(2026, 8, 18, 18, 0),
                countedCash = 61.0,
                expectedCash = 58.0,
                movedToSafe = 41.0,
                floatTarget = 20.0,
            )
        )
    }
}
