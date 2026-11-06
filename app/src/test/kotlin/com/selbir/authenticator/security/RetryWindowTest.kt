package com.selbir.authenticator.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryWindowTest {
    @Test
    fun isClosedUntilMarked() {
        val window = RetryWindow(60_000)
        assertFalse(window.isOpen(0))
        assertFalse(window.isOpen(5))
        assertFalse(window.isOpen(Long.MAX_VALUE))
    }

    @Test
    fun staysOpenForTheWindowAfterAMark() {
        val window = RetryWindow(60_000)
        window.mark(1_000)
        assertTrue(window.isOpen(1_000))
        assertTrue(window.isOpen(60_999))
        assertFalse(window.isOpen(61_000))
    }
}
