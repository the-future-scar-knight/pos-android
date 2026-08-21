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

    /**
     * ★ THE REGRESSION THIS FILE COULD NOT OTHERWISE CATCH.
     *
     * The parser used to offer `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`. The `X` pattern letter only
     * exists in SimpleDateFormat from **API 24**; below that it throws. This app's minSdk
     * is 23 and the shop runs a Sunmi handheld on Android 6, where every pattern threw,
     * every cloud timestamp became 0L, and a freshly-stocked product read "Out of stock"
     * because a `stockBaseAt` of 0 means "no baseline" and the ledger was never applied.
     *
     * Every other test in this file passed throughout — they run on desktop Java, where
     * `X` has worked since Java 7. A behavioural test literally cannot see this bug. So
     * the assertion is structural: the letter must not appear.
     */
    @Test
    fun parsePatterns_avoidTheApi24OnlyZoneLetter() {
        val offenders = IsoTime.PARSE_PATTERNS.filter { it.contains('X') }
        assertTrue(
            "SimpleDateFormat 'X' needs API 24; minSdk is 23. Offending patterns: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun parsesOffsetWithoutMinutes() {
        // Postgres can render a whole-hour zone as "+00" with no minutes at all.
        assertEquals(1_700_000_000_123L, IsoTime.toMillis("2023-11-14T22:13:20.123+00"))
    }

    @Test
    fun parsesSpaceSeparatedTimestamp() {
        // The shape psql prints, as opposed to PostgREST's 'T'.
        assertEquals(1_700_000_000_123L, IsoTime.toMillis("2023-11-14 22:13:20.123+00:00"))
    }

    @Test
    fun bareDate_isNotMangledByTheZoneRewrite() {
        // "2026-08-20" ends in "-20", which a careless offset regex reads as a zone.
        // It must parse as midnight UTC, not as nonsense or zero.
        assertEquals(1_699_920_000_000L, IsoTime.toMillis("2023-11-14"))
    }

    @Test
    fun unparseableString_returnsZero() {
        assertEquals(0L, IsoTime.toMillis("not-a-date"))
    }
}
