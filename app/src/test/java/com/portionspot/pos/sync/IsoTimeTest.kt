package com.portionspot.pos.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync cursor stores cloud timestamps as fixed-width UTC ISO TEXT and relies
 * on lexicographic order == chronological order for the `?updated_at=gt.<cursor>`
 * pull. These tests pin that contract and the millis round-trip.
 */
class IsoTimeTest {
    @Test
    fun roundTrip_preservesMillis() {
        val millis = 1_700_000_000_123L
        val iso = IsoTime.toIso(millis)
        assertEquals(millis, IsoTime.toMillis(iso))
    }

    @Test
    fun epochConstant_isZeroMillis() {
        assertEquals(IsoTime.EPOCH, IsoTime.toIso(0L))
        assertEquals(0L, IsoTime.toMillis(IsoTime.EPOCH))
    }

    @Test
    fun lexicographicOrder_matchesChronological() {
        // The property the pull cursor depends on: later time => lexicographically greater string.
        val earlier = IsoTime.toIso(1_700_000_000_000L)
        val later = IsoTime.toIso(1_700_000_001_000L)
        assertTrue(earlier < later)
    }

    @Test
    fun parsesIsoWithoutMillis_viaFallback() {
        // A Postgres timestamp serialized without the .SSS fraction still parses.
        assertEquals(1_700_000_000_000L, IsoTime.toMillis("2023-11-14T22:13:20Z"))
    }

    @Test
    fun parsesMicrosecondPrecision_withoutInflatingTheInstant() {
        // PostgREST emits timestamptz with 6 fraction digits. Read naively, SimpleDateFormat's
        // SSS treats all six as millis (+123 seconds here); the fraction must be truncated to 3.
        assertEquals(1_700_000_000_123L, IsoTime.toMillis("2023-11-14T22:13:20.123456+00:00"))
    }

    @Test
    fun parsesMicrosecondPrecision_atTheWorstCaseFraction() {
        // ".999999" naively parses as +999999ms ≈ 16m39s into the future.
        assertEquals(1_700_000_000_999L, IsoTime.toMillis("2023-11-14T22:13:20.999999+00:00"))
    }

    @Test
    fun parsesShortFractions_byPadding() {
        // Postgres trims trailing zeros, so 1- and 2-digit fractions show up too: .1 == 100ms.
        assertEquals(1_700_000_000_100L, IsoTime.toMillis("2023-11-14T22:13:20.1+00:00"))
        assertEquals(1_700_000_000_120L, IsoTime.toMillis("2023-11-14T22:13:20.12+00:00"))
        assertEquals(1_700_000_000_100L, IsoTime.toMillis("2023-11-14T22:13:20.1Z"))
    }

    @Test
    fun parsesNonUtcOffset_withMicroseconds() {
        // +02:00 is two hours ahead of UTC, so the instant is two hours EARLIER.
        assertEquals(
            1_700_000_000_123L - 2 * 60 * 60 * 1000,
            IsoTime.toMillis("2023-11-14T22:13:20.123456+02:00"),
        )
    }

    @Test
    fun unparseableString_returnsZero() {
        assertEquals(0L, IsoTime.toMillis("not-a-date"))
    }
}
