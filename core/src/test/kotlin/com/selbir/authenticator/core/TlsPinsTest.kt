package com.selbir.authenticator.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Gateway D-18: `veilo_pins` in the enrolment QR, checked against the shared vectors. */
class TlsPinsTest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/protocol-vectors.json")).readText()
    ).jsonObject["pins"]!!.jsonObject
    private val pins = vectors["pins"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun isrgRootX1Spki(): ByteArray {
        val pem = checkNotNull(javaClass.getResourceAsStream("/isrg-root-x1.pem"))
        return CertificateFactory.getInstance("X.509").generateCertificate(pem).publicKey.encoded
    }

    @Test
    fun pinOfTheRealIsrgRootMatchesTheVector() {
        assertEquals(pins[0], TlsPins.pinOf(isrgRootX1Spki()))
    }

    @Test
    fun pinnedVectorUriCarriesThePins() {
        val e = checkNotNull(VeiloEnrolment.parse(vectors["uri"]!!.jsonPrimitive.content))
        assertEquals(pins, e.pins)
    }

    @Test
    fun uriWithoutPinsHasNone() {
        val plain = vectors["uri"]!!.jsonPrimitive.content.substringBefore("&veilo_pins=")
        assertEquals(emptyList(), checkNotNull(VeiloEnrolment.parse(plain)).pins)
    }

    @Test
    fun malformedPinsBreakTheQr() {
        for (bad in vectors["bad"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertFailsWith<IllegalArgumentException>(bad) { TlsPins.parse(bad) }
            val uri = vectors["uri"]!!.jsonPrimitive.content.substringBefore("&veilo_pins=") +
                "&veilo_pins=" + java.net.URLEncoder.encode(bad, "UTF-8")
            assertFailsWith<IllegalArgumentException>(bad) { VeiloEnrolment.parse(uri) }
        }
    }

    @Test
    fun chainMatchesWhenAnyCertificateIsPinned() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded
        assertTrue(TlsPins.matches(pins, listOf(other, isrgRootX1Spki())))
        assertFalse(TlsPins.matches(pins, listOf(other)))
        assertFalse(TlsPins.matches(pins, emptyList()))
        assertTrue(TlsPins.matches(emptyList(), listOf(other)), "nothing pinned = system trust only")
    }

    @Test
    fun parseTrimsAndDeduplicates() {
        assertEquals(pins, TlsPins.parse(" ${pins[0]} ,${pins[1]},,${pins[0]}"))
    }
}
