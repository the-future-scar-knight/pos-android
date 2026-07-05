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
    fun unparseableString_returnsZero() {
        assertEquals(0L, IsoTime.toMillis("not-a-date"))
    }
}
