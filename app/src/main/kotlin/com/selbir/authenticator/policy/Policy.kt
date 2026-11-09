package com.selbir.authenticator.policy

/**
 * Rules an organisation's MDM pushes through Android managed configuration. Every rule can
 * only take a capability away; with no configuration present ([NONE]) the app behaves exactly
 * as the public edition. Keys and defaults mirror `res/xml/app_restrictions.xml`.
 */
data class Policy(
    /** True when any restriction key is present, whatever its value. */
    val managed: Boolean = false,
    val organizationName: String? = null,
    val supportContact: String? = null,
    /** New accounts, and accounts arriving by import or transfer, must be hardware-bound. */
    val hardwareBoundOnly: Boolean = false,
    val allowBackupExport: Boolean = true,
    /** Covers backup import and migration from other authenticator apps. */
    val allowBackupImport: Boolean = true,
    val allowPdfExport: Boolean = true,
    val allowTransferSend: Boolean = true,
    val allowTransferReceive: Boolean = true,
    /** Refuse to show codes on a rooted device, instead of only warning. */
    val blockOnRootedDevice: Boolean = false,
    val blockOnDebugger: Boolean = false,
) {
    /** The transfer screen is reachable if either direction is allowed. */
    val allowTransfer: Boolean get() = allowTransferSend || allowTransferReceive

    /** Rules currently taking something away, for the "organisation policy" list in About. */
    val activeRestrictions: List<Restriction>
        get() = buildList {
            if (hardwareBoundOnly) add(Restriction.HARDWARE_BOUND_ONLY)
            if (!allowBackupExport) add(Restriction.NO_BACKUP_EXPORT)
            if (!allowBackupImport) add(Restriction.NO_BACKUP_IMPORT)
            if (!allowPdfExport) add(Restriction.NO_PDF_EXPORT)
            if (!allowTransferSend) add(Restriction.NO_TRANSFER_SEND)
            if (!allowTransferReceive) add(Restriction.NO_TRANSFER_RECEIVE)
            if (blockOnRootedDevice) add(Restriction.BLOCK_ROOTED)
            if (blockOnDebugger) add(Restriction.BLOCK_DEBUGGER)
        }

    /**
     * The policy as the phone sees it, for the Enterprise flavour's enrolment confirm (Gateway
     * compares it with the desired policy). Null on an unmanaged device.
     */
    fun toSnapshot(): Map<String, Any?>? = if (!managed) null else mapOf(
        KEY_ORGANIZATION_NAME to organizationName.orEmpty(),
        KEY_SUPPORT_CONTACT to supportContact.orEmpty(),
        KEY_HARDWARE_BOUND_ONLY to hardwareBoundOnly,
        KEY_ALLOW_BACKUP_EXPORT to allowBackupExport,
        KEY_ALLOW_BACKUP_IMPORT to allowBackupImport,
        KEY_ALLOW_PDF_EXPORT to allowPdfExport,
        KEY_ALLOW_TRANSFER_SEND to allowTransferSend,
        KEY_ALLOW_TRANSFER_RECEIVE to allowTransferReceive,
        KEY_BLOCK_ON_ROOTED_DEVICE to blockOnRootedDevice,
        KEY_BLOCK_ON_DEBUGGER to blockOnDebugger,
    )

    enum class Restriction {
        HARDWARE_BOUND_ONLY, NO_BACKUP_EXPORT, NO_BACKUP_IMPORT, NO_PDF_EXPORT,
        NO_TRANSFER_SEND, NO_TRANSFER_RECEIVE, BLOCK_ROOTED, BLOCK_DEBUGGER,
    }

    companion object {
        val NONE = Policy()

        const val KEY_ORGANIZATION_NAME = "organization_name"
        const val KEY_SUPPORT_CONTACT = "support_contact"
        const val KEY_HARDWARE_BOUND_ONLY = "hardware_bound_only"
        const val KEY_ALLOW_BACKUP_EXPORT = "allow_backup_export"
        const val KEY_ALLOW_BACKUP_IMPORT = "allow_backup_import"
        const val KEY_ALLOW_PDF_EXPORT = "allow_pdf_export"
        const val KEY_ALLOW_TRANSFER_SEND = "allow_transfer_send"
        const val KEY_ALLOW_TRANSFER_RECEIVE = "allow_transfer_receive"
        const val KEY_BLOCK_ON_ROOTED_DEVICE = "block_on_rooted_device"
        const val KEY_BLOCK_ON_DEBUGGER = "block_on_debugger"

        /**
         * Builds a policy from the flat key/value map an MDM delivers. Unknown keys are
         * ignored; a missing or malformed value keeps the permissive default, so a typo in
         * the console can never grant more than the public edition has.
         */
        fun from(restrictions: Map<String, Any?>): Policy {
            if (restrictions.isEmpty()) return NONE
            fun bool(key: String, default: Boolean): Boolean = when (val v = restrictions[key]) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true) || (v.equals("false", ignoreCase = true).not() && default)
                else -> default
            }
            fun text(key: String): String? = (restrictions[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            return Policy(
                managed = true,
                organizationName = text(KEY_ORGANIZATION_NAME),
                supportContact = text(KEY_SUPPORT_CONTACT),
                hardwareBoundOnly = bool(KEY_HARDWARE_BOUND_ONLY, false),
                allowBackupExport = bool(KEY_ALLOW_BACKUP_EXPORT, true),
                allowBackupImport = bool(KEY_ALLOW_BACKUP_IMPORT, true),
                allowPdfExport = bool(KEY_ALLOW_PDF_EXPORT, true),
                allowTransferSend = bool(KEY_ALLOW_TRANSFER_SEND, true),
                allowTransferReceive = bool(KEY_ALLOW_TRANSFER_RECEIVE, true),
                blockOnRootedDevice = bool(KEY_BLOCK_ON_ROOTED_DEVICE, false),
                blockOnDebugger = bool(KEY_BLOCK_ON_DEBUGGER, false),
            )
        }
    }
}
