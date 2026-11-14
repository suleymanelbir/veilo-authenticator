package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VeiloQrLoginTest {
    private val id = "0b6c7f1e-2a7d-4c55-9d3e-6f1a2b3c4d5e"
    private val secret = "A".repeat(40) + "_-9"

    @Test
    fun parsesAVersionOneQr() {
        val q = VeiloQrLogin.parse("veilo-login:1:$id:$secret:https://gw.kurum.example/")
        assertEquals(VeiloQrLogin("https://gw.kurum.example", id, secret), q)
        assertTrue(VeiloQrLogin.looksLikeOne("  VEILO-LOGIN:1:x"))
        assertFalse(VeiloQrLogin.looksLikeOne("otpauth://totp/x?secret=AAAA"))
    }

    @Test
    fun keepsAPortInTheUrl() {
        assertEquals("https://gw.example:8443/veilo", VeiloQrLogin.parse("veilo-login:1:$id:$secret:https://gw.example:8443/veilo").gatewayUrl)
    }

    @Test
    fun refusesMalformedOnes() {
        val bad = listOf(
            "veilo-login:2:$id:$secret:https://gw.example",              // unknown version
            "veilo-login:1:not-a-uuid:$secret:https://gw.example",
            "veilo-login:1:$id:short:https://gw.example",
            "veilo-login:1:$id:$secret:http://gw.example",                // plain http outside lab builds
            "veilo-login:1:$id:$secret:https://gw.example/?x=1",
            "veilo-login:1:$id:$secret:https://user@gw.example",
            "veilo-login:1:$id:$secret",
            "veilo-login:1:$id:$secret:https://" + "a".repeat(600),
        )
        bad.forEach { assertFailsWith<IllegalArgumentException>(it) { VeiloQrLogin.parse(it) } }
        assertEquals("http://10.0.2.2:8282", VeiloQrLogin.parse("veilo-login:1:$id:$secret:http://10.0.2.2:8282", allowPlainHttp = true).gatewayUrl)
    }

    @Test
    fun matchesOnlyTheSameGateway() {
        assertTrue(VeiloQrLogin.sameGateway("https://GW.example/", "https://gw.example"))
        assertTrue(VeiloQrLogin.sameGateway("https://gw.example:443", "https://gw.example"))
        assertFalse(VeiloQrLogin.sameGateway("https://gw.example", "https://gw.example.evil.test"))
        assertFalse(VeiloQrLogin.sameGateway("https://gw.example", "http://gw.example"))
        assertFalse(VeiloQrLogin.sameGateway("https://gw.example", "https://gw.example:8443"))
        assertFalse(VeiloQrLogin.sameGateway("https://gw.example/a", "https://gw.example/b"))
    }
}
