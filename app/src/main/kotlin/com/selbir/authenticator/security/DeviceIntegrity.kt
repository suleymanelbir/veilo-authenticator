package com.selbir.authenticator.security

import android.os.Build
import android.os.Debug
import java.io.File

/** A sign that this device or process is not in the state the app's protections assume. */
enum class DeviceRisk {
    /** The system looks rooted or modified: other software may run with more rights than this app. */
    ROOTED,

    /** A debugger or tracer is attached to this process and can read its memory. */
    DEBUGGER,
}

/**
 * Best-effort checks, made offline. They catch the ordinary cases and warn the user; they
 * are not a defence. Software that controls the system can hide from an app that asks it
 * questions, so nothing here is used to decide whether keys may be used: that stays with
 * the Keystore.
 */
object DeviceIntegrity {
    fun check(): Set<DeviceRisk> {
        val risks = mutableSetOf<DeviceRisk>()
        if (hasTestKeys(Build.TAGS) || suBinaryIn(SU_DIRECTORIES + pathDirectories(System.getenv("PATH")))) {
            risks += DeviceRisk.ROOTED
        }
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger() || tracerPid(readProcStatus()) != 0) {
            risks += DeviceRisk.DEBUGGER
        }
        return risks
    }

    /** Release firmware is signed with release keys; "test-keys" marks a self-built system image. */
    internal fun hasTestKeys(buildTags: String?): Boolean = buildTags?.contains("test-keys") == true

    internal fun suBinaryIn(directories: Collection<File>): Boolean = directories.any {
        try {
            File(it, "su").exists()
        } catch (e: SecurityException) {
            false
        }
    }

    internal fun pathDirectories(path: String?): List<File> =
        path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)

    /** The process tracing this one, from the text of `/proc/self/status`; 0 when there is none. */
    internal fun tracerPid(procStatus: String): Int = procStatus.lineSequence()
        .firstOrNull { it.startsWith("TracerPid:") }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    private fun readProcStatus(): String = try {
        File("/proc/self/status").readText()
    } catch (e: Exception) {
        ""
    }

    private val SU_DIRECTORIES = listOf(
        "/system/bin",
        "/system/xbin",
        "/system_ext/bin",
        "/vendor/bin",
        "/product/bin",
        "/sbin",
        "/su/bin",
        "/data/local",
        "/data/local/bin",
        "/data/local/xbin",
    ).map(::File)
}
