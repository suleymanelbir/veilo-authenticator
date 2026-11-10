package com.selbir.authenticator.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecondsLeftTest {
    private val expires = "2026-11-10T11:20:00+00:00"
    private val at = java.time.Instant.parse("2026-11-10T11:18:00Z").toEpochMilli()

    @Test
    fun countsDownInWholeSecondsAndStopsAtZero() {
        assertEquals(120L, secondsLeft(expires, at))
        assertEquals(1L, secondsLeft(expires, at + 119_500))    // part of a second still counts
        assertEquals(0L, secondsLeft(expires, at + 120_000))
        assertEquals(0L, secondsLeft(expires, at + 600_000))     // never negative
        // the Gateway writes microseconds and an offset (Python isoformat)
        assertEquals(31L, secondsLeft("2026-11-10T11:18:30.123456+00:00", at))   // 30.12 s rounds up
    }

    @Test
    fun unreadableExpiryHasNoCountdown() {
        assertNull(secondsLeft("", at))
        assertNull(secondsLeft("yesterday", at))
        assertNull(secondsLeft("2026-11-10T11:20:00", at))     // no offset: do not guess
    }
}
