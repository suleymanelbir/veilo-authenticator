package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OtpAuthUriTest {
    @Test
    fun parsesMinimalTotpUriWithDefaults() {
        val spec = OtpAuthUri.parse("otpauth://totp/Example:alice@example.com?secret=MZXW6YTBOI")
        assertEquals(OtpType.TOTP, spec.type)
        assertEquals("Example", spec.issuer)
        assertEquals("alice@example.com", spec.accountName)
        assertContentEquals("foobar".toByteArray(), spec.secret)
        assertEquals(OtpAlgorithm.SHA1, spec.algorithm)
        assertEquals(6, spec.digits)
        assertEquals(30, spec.period)
    }

    @Test
    fun parsesAllParametersAndPrefersIssuerParameter() {
        val spec = OtpAuthUri.parse(
            "otpauth://totp/Old%20Name:bob?secret=mzxw6ytboi&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60"
        )
        assertEquals("ACME Co", spec.issuer)
        assertEquals("bob", spec.accountName)
        assertEquals(OtpAlgorithm.SHA256, spec.algorithm)
        assertEquals(8, spec.digits)
        assertEquals(60, spec.period)
    }

    @Test
    fun parsesHotpWithCounter() {
        val spec = OtpAuthUri.parse("otpauth://hotp/carol?secret=MZXW6YTBOI&counter=42")
        assertEquals(OtpType.HOTP, spec.type)
        assertEquals("", spec.issuer)
        assertEquals("carol", spec.accountName)
        assertEquals(42, spec.counter)
    }

    @Test
    fun keepsLiteralPlusInLabelAndDecodesUtf8() {
        val spec = OtpAuthUri.parse("otpauth://totp/%C3%9Cniversite:ali+test@example.com?secret=MZXW6YTBOI")
        assertEquals("Üniversite", spec.issuer)
        assertEquals("ali+test@example.com", spec.accountName)
    }

    @Test
    fun rejectsUnusableUris() {
        val bad = listOf(
            "https://example.com",
            "otpauth://totp/x",
            "otpauth://totp/x?secret=",
            "otpauth://totp/x?secret=MZXW1",
            "otpauth://steam/x?secret=MZXW6YTBOI",
            "otpauth://totp/x?secret=MZXW6YTBOI&digits=10",
            "otpauth://totp/x?secret=MZXW6YTBOI&period=0",
            "otpauth://totp/x?secret=MZXW6YTBOI&algorithm=MD5",
            "otpauth://hotp/x?secret=MZXW6YTBOI&counter=abc",
        )
        for (uri in bad) {
            assertFailsWith<IllegalArgumentException>(uri) { OtpAuthUri.parse(uri) }
        }
    }
}
