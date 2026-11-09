package com.selbir.authenticator.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    @Test
    fun noRestrictionsMeansThePublicEdition() {
        val policy = Policy.from(emptyMap())
        assertEquals(Policy.NONE, policy)
        assertFalse(policy.managed)
        assertTrue(policy.allowBackupExport && policy.allowBackupImport && policy.allowPdfExport)
        assertTrue(policy.allowTransferSend && policy.allowTransferReceive && policy.allowTransfer)
        assertFalse(policy.hardwareBoundOnly || policy.blockOnRootedDevice || policy.blockOnDebugger)
        assertTrue(policy.activeRestrictions.isEmpty())
    }

    @Test
    fun anyKeyMarksTheDeviceAsManagedEvenWhenPermissive() {
        val policy = Policy.from(mapOf("organization_name" to "Acme A.Ş."))
        assertTrue(policy.managed)
        assertEquals("Acme A.Ş.", policy.organizationName)
        assertTrue(policy.activeRestrictions.isEmpty())
    }

    @Test
    fun restrictionsAreReadAndListed() {
        val policy = Policy.from(
            mapOf(
                "organization_name" to "  Acme  ",
                "support_contact" to "it@acme.example",
                "hardware_bound_only" to true,
                "allow_backup_export" to false,
                "allow_backup_import" to false,
                "allow_pdf_export" to false,
                "allow_transfer_send" to false,
                "allow_transfer_receive" to true,
                "block_on_rooted_device" to true,
                "block_on_debugger" to false,
            ),
        )
        assertEquals("Acme", policy.organizationName)
        assertEquals("it@acme.example", policy.supportContact)
        assertTrue(policy.hardwareBoundOnly)
        assertFalse(policy.allowBackupExport)
        assertFalse(policy.allowBackupImport)
        assertFalse(policy.allowPdfExport)
        assertFalse(policy.allowTransferSend)
        assertTrue(policy.allowTransferReceive)
        assertTrue(policy.allowTransfer)
        assertTrue(policy.blockOnRootedDevice)
        assertEquals(
            listOf(
                Policy.Restriction.HARDWARE_BOUND_ONLY, Policy.Restriction.NO_BACKUP_EXPORT,
                Policy.Restriction.NO_BACKUP_IMPORT, Policy.Restriction.NO_PDF_EXPORT,
                Policy.Restriction.NO_TRANSFER_SEND, Policy.Restriction.BLOCK_ROOTED,
            ),
            policy.activeRestrictions,
        )
    }

    @Test
    fun unknownKeysAndBadValuesFallBackToTheDefaults() {
        val policy = Policy.from(
            mapOf(
                "allow_everything" to true,
                "hardware_bound_only" to "yes please",
                "allow_backup_export" to 0,
                "allow_pdf_export" to "false",
                "block_on_rooted_device" to "TRUE",
                "organization_name" to "   ",
                "support_contact" to 42,
            ),
        )
        assertTrue(policy.managed)
        assertFalse(policy.hardwareBoundOnly)
        assertTrue(policy.allowBackupExport)
        assertFalse(policy.allowPdfExport)
        assertTrue(policy.blockOnRootedDevice)
        assertNull(policy.organizationName)
        assertNull(policy.supportContact)
    }

    @Test
    fun bothTransferDirectionsOffHidesTheScreen() {
        val policy = Policy.from(mapOf("allow_transfer_send" to false, "allow_transfer_receive" to false))
        assertFalse(policy.allowTransfer)
    }
}
