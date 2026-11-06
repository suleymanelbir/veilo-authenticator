package com.selbir.authenticator.security

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceIntegrityTest {
    @Test
    fun findsAnSuBinaryInAnyOfTheDirectories() {
        val empty = Files.createTempDirectory("empty").toFile()
        val rooted = Files.createTempDirectory("rooted").toFile()
        try {
            assertFalse(DeviceIntegrity.suBinaryIn(listOf(empty, rooted, File(empty, "missing"))))

            File(rooted, "su").writeText("")
            assertTrue(DeviceIntegrity.suBinaryIn(listOf(empty, rooted)))
            assertFalse(DeviceIntegrity.suBinaryIn(listOf(empty)))
        } finally {
            empty.deleteRecursively()
            rooted.deleteRecursively()
        }
    }

    @Test
    fun splitsThePathVariable() {
        val separator = File.pathSeparator
        assertEquals(
            listOf(File("/system/bin"), File("/vendor/bin")),
            DeviceIntegrity.pathDirectories("/system/bin$separator$separator/vendor/bin"),
        )
        assertEquals(emptyList(), DeviceIntegrity.pathDirectories(null))
    }

    @Test
    fun recognisesTestKeys() {
        assertTrue(DeviceIntegrity.hasTestKeys("test-keys"))
        assertTrue(DeviceIntegrity.hasTestKeys("dev-keys,test-keys"))
        assertFalse(DeviceIntegrity.hasTestKeys("release-keys"))
        assertFalse(DeviceIntegrity.hasTestKeys(null))
    }

    @Test
    fun readsTheTracerFromProcStatus() {
        assertEquals(0, DeviceIntegrity.tracerPid("Name:\tapp\nTracerPid:\t0\nUid:\t10123\n"))
        assertEquals(4242, DeviceIntegrity.tracerPid("Name:\tapp\nTracerPid:\t4242\nUid:\t10123\n"))
        assertEquals(0, DeviceIntegrity.tracerPid(""))
    }
}
