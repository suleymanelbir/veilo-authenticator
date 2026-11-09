package com.selbir.authenticator.gateway

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceKeyEncodingTest {
    @Test
    fun coordinatesAreFixedTo32Bytes() {
        // leading sign byte from BigInteger.toByteArray() is dropped
        assertContentEquals(ByteArray(31) + byteArrayOf(1), DeviceKey.fixedLength(byteArrayOf(0, 1), 32))
        // short values are left-padded
        assertEquals(32, DeviceKey.fixedLength(byteArrayOf(0x7f), 32).size)
        val full = ByteArray(32) { 0x42 }
        assertContentEquals(full, DeviceKey.fixedLength(byteArrayOf(0) + full, 32))
        assertFailsWith<IllegalArgumentException> { DeviceKey.fixedLength(ByteArray(33) { 1 }, 32) }
    }

    @Test
    fun base64UrlHasNoPaddingOrUnsafeChars() {
        val out = DeviceKey.base64Url(byteArrayOf(-1, -2, -3, -4))
        assertEquals("__79_A", out)
    }
}
