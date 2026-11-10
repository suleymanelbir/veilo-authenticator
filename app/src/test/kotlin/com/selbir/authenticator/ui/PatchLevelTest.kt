package com.selbir.authenticator.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatchLevelTest {
    @Test
    fun androidSecurityPatchBecomesYearMonth() {
        assertEquals(202607, patchLevel("2026-07-05"))
        assertEquals(202512, patchLevel("2025-12"))
        assertNull(patchLevel(null))
        assertNull(patchLevel(""))
        assertNull(patchLevel("unknown"))
        assertNull(patchLevel("26-07-05"))
    }
}
