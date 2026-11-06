package com.selbir.authenticator.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base32Test {
    /** RFC 4648, section 10. */
    private val vectors = listOf(
        "" to "",
        "f" to "MY======",
        "fo" to "MZXQ====",
        "foo" to "MZXW6===",
        "foob" to "MZXW6YQ=",
        "fooba" to "MZXW6YTB",
        "foobar" to "MZXW6YTBOI======",
    )

    @Test
    fun encodesRfc4648Vectors() {
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base32.encode(plain.toByteArray(), padding = true))
            assertEquals(encoded.trimEnd('='), Base32.encode(plain.toByteArray()))
        }
    }

    @Test
    fun decodesRfc4648Vectors() {
        for ((plain, encoded) in vectors) {
            assertContentEquals(plain.toByteArray(), Base32.decode(encoded))
        }
    }

    @Test
    fun decodesTheWaySecretsArePrinted() {
        val expected = "foobar".toByteArray()
        assertContentEquals(expected, Base32.decode("mzxw 6ytb oi"))
        assertContentEquals(expected, Base32.decode("MZXW-6YTB-OI"))
    }

    @Test
    fun rejectsCharactersOutsideTheAlphabet() {
        assertFailsWith<IllegalArgumentException> { Base32.decode("MZXW1YTB") }
        assertFailsWith<IllegalArgumentException> { Base32.decode("MZXW6YT!") }
    }

    @Test
    fun roundTripsArbitraryBytes() {
        val data = ByteArray(257) { (it * 31 + 7).toByte() }
        assertContentEquals(data, Base32.decode(Base32.encode(data)))
    }
}
