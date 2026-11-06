package com.selbir.authenticator.data

import android.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/** Where the account list is persisted. */
interface AccountStore {
    fun load(): List<Account>

    fun save(accounts: List<Account>)
}

/**
 * Persists the account list as one small JSON file in app-private storage.
 * Writes are atomic, so a crash mid-save leaves the previous list intact.
 */
class FileAccountStore(file: File) : AccountStore {
    private val file = AtomicFile(file)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): List<Account> {
        val bytes = try {
            file.readFully()
        } catch (e: FileNotFoundException) {
            return emptyList()
        }
        return json.decodeFromString<Vault>(bytes.decodeToString()).accounts
    }

    override fun save(accounts: List<Account>) {
        val bytes = json.encodeToString(Vault(accounts = accounts)).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}
