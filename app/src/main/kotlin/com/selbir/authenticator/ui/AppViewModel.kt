package com.selbir.authenticator.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.UserNotAuthenticatedException
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selbir.authenticator.AuthenticatorApp
import com.selbir.authenticator.R
import com.selbir.authenticator.core.BackupCodec
import com.selbir.authenticator.core.BackupException
import com.selbir.authenticator.core.BackupPayload
import com.selbir.authenticator.core.Base32
import com.selbir.authenticator.core.Otp
import com.selbir.authenticator.core.OtpSpec
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.data.Account
import com.selbir.authenticator.data.Protection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class Screen { List, Add, Export, Import }

data class AccountRow(
    val id: String,
    val issuer: String,
    val name: String,
    val type: OtpType,
    val period: Int,
    val hardwareBound: Boolean,
    /** Null for an HOTP account that has not generated a code since the last unlock. */
    val code: String?,
    /** The key was destroyed (e.g. the screen lock was removed); the account must be re-enrolled. */
    val unusable: Boolean,
)

data class UiState(
    val locked: Boolean = true,
    /** Increments on every lock, so the lock screen can prompt once per lock. */
    val lockEpoch: Int = 0,
    val screen: Screen = Screen.List,
    /** Null until the first load after an unlock. */
    val rows: List<AccountRow>? = null,
    val epochSeconds: Long = System.currentTimeMillis() / 1000,
)

/** Outcome of an operation that needs a Keystore key. */
enum class OpResult {
    Success,

    /** The Keystore's authentication window lapsed; authenticate and call again. */
    NeedsAuth,
    Failed,
}

/** The add-account form. Held by the ViewModel so it survives rotation without ever being written to saved state. */
class AddFormState {
    var issuer by mutableStateOf("")
    var account by mutableStateOf("")
    var secretText by mutableStateOf("")
    var scanned by mutableStateOf<OtpSpec?>(null)
    var protection by mutableStateOf(Protection.EXPORTABLE)
    var errorRes by mutableStateOf<Int?>(null)
    var saving by mutableStateOf(false)

    val canSave: Boolean
        get() = !saving && (scanned != null || secretText.isNotBlank()) && (issuer.isNotBlank() || account.isNotBlank())

    /** Null when the typed secret is not valid Base32. */
    fun toSpec(): OtpSpec? {
        val base = scanned
        return try {
            if (base != null) {
                OtpSpec(base.type, issuer.trim(), account.trim(), base.secret, base.algorithm, base.digits, base.period, base.counter)
            } else {
                OtpSpec(OtpType.TOTP, issuer.trim(), account.trim(), Base32.decode(secretText))
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun clear() {
        scanned?.secret?.fill(0)
        scanned = null
        issuer = ""
        account = ""
        secretText = ""
        protection = Protection.EXPORTABLE
        errorRes = null
        saving = false
    }
}

/** The export and import screens. Like [AddFormState], held by the ViewModel and never written to saved state. */
class BackupFormState {
    var passphrase by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var errorRes by mutableStateOf<Int?>(null)
    var busy by mutableStateOf(false)

    /** Import: the chosen file, still encrypted. Export: the encrypted backup waiting for a destination. */
    var blob by mutableStateOf<ByteArray?>(null)
    var exportedCount = 0

    /** Null when the passphrase typed for a new backup is acceptable. */
    fun newPassphraseError(): Int? = when {
        passphrase.length < MIN_PASSPHRASE_LENGTH -> R.string.passphrase_too_short
        passphrase != confirmation -> R.string.passphrase_mismatch
        else -> null
    }

    fun clear() {
        passphrase = ""
        confirmation = ""
        errorRes = null
        busy = false
        blob = null
        exportedCount = 0
    }

    companion object {
        /** Kept in step with the number in the `passphrase_too_short` string. */
        const val MIN_PASSPHRASE_LENGTH = 8
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AuthenticatorApp).repository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val addForm = AddFormState()
    val backupForm = BackupFormState()

    /**
     * True while an activity we opened ourselves (the QR scanner, the system file picker)
     * covers the app, so that stop does not lock.
     */
    @Volatile
    var childActivityInFront = false

    private val refreshMutex = Mutex()
    private val totpCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val hotpCodes = ConcurrentHashMap<String, String>()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1000 - System.currentTimeMillis() % 1000)
            }
        }
    }

    fun onAuthenticated() {
        _state.update { it.copy(locked = false) }
        viewModelScope.launch { refresh() }
    }

    fun lock() {
        _state.update {
            if (it.locked) it else it.copy(locked = true, lockEpoch = it.lockEpoch + 1, screen = Screen.List, rows = null)
        }
        addForm.clear()
        backupForm.clear()
        // Taking the mutex waits out any refresh still in flight, so it cannot repopulate the caches.
        viewModelScope.launch { refreshMutex.withLock { clearCodes() } }
    }

    fun onStop() {
        if (!childActivityInFront) lock()
    }

    fun navigate(screen: Screen) {
        addForm.clear()
        backupForm.clear()
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.List) viewModelScope.launch { refresh() }
    }

    suspend fun addAccount(spec: OtpSpec, protection: Protection): OpResult {
        val result = withContext(Dispatchers.IO) {
            try {
                repository.add(spec, protection)
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) {
            spec.secret.fill(0)
            navigate(Screen.List)
        }
        return result
    }

    suspend fun generateHotp(id: String): OpResult {
        val result = withContext(Dispatchers.IO) {
            try {
                hotpCodes[id] = repository.nextHotpCode(id)
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) refresh()
        return result
    }

    /**
     * Encrypts every backup-capable account under the passphrase in [backupForm] and holds
     * the result there until [writeExport] is given a destination. Encrypting before the
     * destination is chosen means a failure here never leaves an empty file behind.
     */
    suspend fun encryptExport(): OpResult {
        val form = backupForm
        val passphrase = BackupCodec.passphraseChars(form.passphrase)
        var blob: ByteArray? = null
        var count = 0
        val result = withContext(Dispatchers.Default) {
            var specs = emptyList<OtpSpec>()
            try {
                specs = repository.exportableSpecs()
                if (specs.isEmpty()) return@withContext OpResult.Failed
                val payload = BackupPayload.encode(specs)
                try {
                    blob = BackupCodec.encrypt(payload, passphrase)
                } finally {
                    payload.fill(0)
                }
                count = specs.size
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            } catch (e: OutOfMemoryError) {
                // The key derivation needs 64 MiB at once.
                OpResult.Failed
            } finally {
                specs.forEach { it.secret.fill(0) }
                passphrase.fill('\u0000')
            }
        }
        if (result == OpResult.Success) {
            form.blob = blob
            form.exportedCount = count
        }
        return result
    }

    /** Writes the backup prepared by [encryptExport] to [uri]. Returns false if it could not be written. */
    suspend fun writeExport(uri: Uri): Boolean {
        val blob = backupForm.blob ?: return false
        val count = backupForm.exportedCount
        val resolver = getApplication<Application>().contentResolver
        val written = withContext(Dispatchers.IO) {
            try {
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("No stream")).use { it.write(blob) }
                true
            } catch (e: Exception) {
                // Do not leave an empty or truncated file behind that looks like a backup.
                try {
                    DocumentsContract.deleteDocument(resolver, uri)
                } catch (ignored: Exception) {
                }
                false
            }
        }
        if (written) {
            toast(R.string.export_done, count)
            navigate(Screen.List)
        } else {
            backupForm.blob = null
        }
        return written
    }

    fun discardExport() {
        backupForm.blob = null
    }

    /** Reads the file chosen for import into [backupForm], or reports there why it cannot be one of ours. */
    suspend fun loadImportFile(uri: Uri) {
        val form = backupForm
        val resolver = getApplication<Application>().contentResolver
        var errorRes: Int? = null
        val blob = withContext(Dispatchers.IO) {
            try {
                val bytes = (resolver.openInputStream(uri) ?: throw IOException("No stream")).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= MAX_BACKUP_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.size > MAX_BACKUP_BYTES) {
                    throw BackupException(BackupException.Reason.NOT_A_BACKUP, "Too large to be a backup")
                }
                BackupCodec.checkHeader(bytes)
                bytes
            } catch (e: BackupException) {
                errorRes = importErrorFor(e.reason)
                null
            } catch (e: Exception) {
                errorRes = R.string.import_file_unreadable
                null
            }
        }
        form.blob = blob
        form.errorRes = errorRes
    }

    /**
     * Decrypts the file loaded by [loadImportFile] with the passphrase in [backupForm].
     * Returns null on failure, with the reason in the form. The caller must wipe the
     * returned secrets once they have been imported.
     */
    suspend fun decryptImport(): List<OtpSpec>? {
        val form = backupForm
        val blob = form.blob ?: return null
        val passphrase = BackupCodec.passphraseChars(form.passphrase)
        var errorRes: Int? = null
        val specs = withContext(Dispatchers.Default) {
            try {
                val payload = BackupCodec.decrypt(blob, passphrase)
                try {
                    BackupPayload.decode(payload)
                } finally {
                    payload.fill(0)
                }
            } catch (e: BackupException) {
                errorRes = importErrorFor(e.reason)
                null
            } catch (e: OutOfMemoryError) {
                // The file asks for more key-derivation memory than this device can give.
                errorRes = R.string.import_file_unsupported
                null
            } finally {
                passphrase.fill('\u0000')
            }
        }
        form.errorRes = errorRes
        return specs
    }

    suspend fun importAccounts(specs: List<OtpSpec>): OpResult {
        var added = 0
        var alreadyPresent = 0
        val result = withContext(Dispatchers.IO) {
            try {
                val imported = repository.importAll(specs)
                added = imported.added
                alreadyPresent = imported.alreadyPresent
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) {
            toast(R.string.import_done, added, alreadyPresent)
            navigate(Screen.List)
        }
        return result
    }

    private fun importErrorFor(reason: BackupException.Reason): Int = when (reason) {
        BackupException.Reason.NOT_A_BACKUP -> R.string.import_file_invalid
        BackupException.Reason.UNSUPPORTED -> R.string.import_file_unsupported
        BackupException.Reason.WRONG_PASSPHRASE_OR_CORRUPT -> R.string.import_wrong_passphrase
    }

    private fun toast(messageRes: Int, vararg args: Any) {
        val app = getApplication<Application>()
        Toast.makeText(app, app.getString(messageRes, *args), Toast.LENGTH_LONG).show()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(id) }
            totpCache.remove(id)
            hotpCodes.remove(id)
            refresh()
        }
    }

    /** Recomputes the visible codes. Locks the app if the Keystore no longer accepts the last authentication. */
    private suspend fun refresh() = refreshMutex.withLock {
        val before = _state.value
        if (before.locked || before.screen != Screen.List) return@withLock

        val now = System.currentTimeMillis() / 1000
        val rows = try {
            withContext(Dispatchers.Default) { repository.all().map { rowFor(it, now) } }
        } catch (e: UserNotAuthenticatedException) {
            lock()
            return@withLock
        }
        _state.update {
            if (it.locked || it.lockEpoch != before.lockEpoch) it else it.copy(rows = rows, epochSeconds = now)
        }
    }

    private fun rowFor(account: Account, now: Long): AccountRow {
        var unusable = false
        val code = when (account.type) {
            OtpType.HOTP -> hotpCodes[account.id]
            OtpType.TOTP -> {
                val step = Otp.timeCounter(now, account.period)
                val cached = totpCache[account.id]
                if (cached != null && cached.first == step) {
                    cached.second
                } else {
                    try {
                        repository.code(account, step).also { totpCache[account.id] = step to it }
                    } catch (e: UserNotAuthenticatedException) {
                        throw e
                    } catch (e: Exception) {
                        unusable = true
                        null
                    }
                }
            }
        }
        return AccountRow(
            id = account.id,
            issuer = account.issuer,
            name = account.name,
            type = account.type,
            period = account.period,
            hardwareBound = account.protection == Protection.HARDWARE_BOUND,
            code = code,
            unusable = unusable,
        )
    }

    private fun clearCodes() {
        totpCache.clear()
        hotpCodes.clear()
    }

    private companion object {
        /** Far above any real backup (a few hundred bytes per account); bounds what a hostile file can make us read. */
        const val MAX_BACKUP_BYTES = 1 shl 20
    }
}
