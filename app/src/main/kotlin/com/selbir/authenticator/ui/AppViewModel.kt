package com.selbir.authenticator.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.UserNotAuthenticatedException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selbir.authenticator.AuthenticatorApp
import com.selbir.authenticator.R
import com.selbir.authenticator.core.AegisMigration
import com.selbir.authenticator.core.BackupCodec
import com.selbir.authenticator.core.BackupException
import com.selbir.authenticator.core.BackupPayload
import com.selbir.authenticator.core.Base32
import com.selbir.authenticator.core.GoogleAuthMigration
import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.Otp
import com.selbir.authenticator.core.OtpSpec
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.Transfer
import com.selbir.authenticator.core.TransferException
import com.selbir.authenticator.data.Account
import com.selbir.authenticator.data.DuplicateAccountException
import com.selbir.authenticator.data.GatewayBinding
import com.selbir.authenticator.export.PdfExport
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloKeyLevel
import com.selbir.authenticator.edition.DeviceKeyFactory
import com.selbir.authenticator.edition.Edition
import com.selbir.authenticator.gateway.AnswerResult
import com.selbir.authenticator.gateway.ChallengeFetch
import com.selbir.authenticator.gateway.ConfirmResult
import com.selbir.authenticator.policy.Policy
import com.selbir.authenticator.data.Protection
import com.selbir.authenticator.security.DeviceIntegrity
import com.selbir.authenticator.security.DeviceRisk
import com.selbir.authenticator.security.KeyLevel
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
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

enum class Screen { List, Add, Export, Import, About, Migrate, PdfExport, Transfer }

data class AccountRow(
    val id: String,
    val issuer: String,
    val name: String,
    val type: OtpType,
    val period: Int,
    val hardwareBound: Boolean,
    /** Where the key that protects this account is held. */
    val keyLevel: KeyLevel,
    /** Null for an HOTP account that has not generated a code since the last unlock. */
    val code: String?,
    /** The key was destroyed (e.g. the screen lock was removed); the account must be re-enrolled. */
    val unusable: Boolean,
    val group: String = "",
    val pinned: Boolean = false,
)

data class UiState(
    val locked: Boolean = true,
    /** Increments on every lock, so the lock screen can prompt once per lock. */
    val lockEpoch: Int = 0,
    val screen: Screen = Screen.List,
    /** Null until the first load after an unlock. */
    val rows: List<AccountRow>? = null,
    val epochSeconds: Long = System.currentTimeMillis() / 1000,
    /** What [DeviceIntegrity] found at the last unlock. */
    val risks: Set<DeviceRisk> = emptySet(),
    /** Distinct non-empty groups found in accounts. */
    val groups: List<String> = emptyList(),
    /** Null = show all, "" = ungrouped, otherwise the group name. */
    val selectedGroup: String? = null,
    /** Under a hardware-only policy: accounts still stored as exportable keys. */
    val exportableUnderPolicy: Int = 0,
    /** Enterprise flavour: a login waiting for number matching on this phone (PROTOCOL.md §5). */
    val challenge: ChallengePrompt? = null,
)

/** One Gateway challenge shown to the user, with the account it arrived for. */
data class ChallengePrompt(
    val accountId: String,
    val issuer: String,
    val name: String,
    val challenge: PendingChallenge,
    /** True while the answer is being signed and sent. */
    val busy: Boolean = false,
)

/** Which screens the organisation's rules leave reachable. */
fun Policy.allows(screen: Screen): Boolean = when (screen) {
    Screen.Export -> allowBackupExport
    Screen.Import, Screen.Migrate -> allowBackupImport
    Screen.PdfExport -> allowPdfExport
    Screen.Transfer -> allowTransfer
    Screen.List, Screen.Add, Screen.About -> true
}

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

    /** Veilo Gateway extension of the scanned QR; acted on by the Enterprise flavour only. */
    var veilo by mutableStateOf<VeiloEnrolment?>(null)
    var protection by mutableStateOf(Protection.EXPORTABLE)
    var errorRes by mutableStateOf<Int?>(null)
    var saving by mutableStateOf(false)

    var group by mutableStateOf("")

    // Advanced manual entry options (ignored when scanned != null).
    var algorithm by mutableStateOf(OtpAlgorithm.SHA1)
    var digits by mutableStateOf(6)
    var period by mutableStateOf(30)

    val canSave: Boolean
        get() = !saving && (scanned != null || secretText.isNotBlank()) && (issuer.isNotBlank() || account.isNotBlank())

    /** True when saving will also register this phone with the organisation's Gateway. */
    val enterpriseEnrolment: Boolean get() = Edition.CONNECTED && veilo != null

    /** Null when the typed secret is not valid Base32. */
    fun toSpec(): OtpSpec? {
        val base = scanned
        return try {
            if (base != null) {
                OtpSpec(base.type, issuer.trim(), account.trim(), base.secret, base.algorithm, base.digits, base.period, base.counter)
            } else {
                OtpSpec(OtpType.TOTP, issuer.trim(), account.trim(), Base32.decode(secretText), algorithm, digits, period)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun clear() {
        scanned?.secret?.fill(0)
        scanned = null
        veilo = null
        issuer = ""
        account = ""
        secretText = ""
        protection = Protection.EXPORTABLE
        errorRes = null
        saving = false
        group = ""
        algorithm = OtpAlgorithm.SHA1
        digits = 6
        period = 30
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

enum class TransferRole { Send, Receive }

/**
 * The device-to-device transfer screen. Held by the ViewModel like [BackupFormState]; the
 * receiver's private key and the sender's frames live only here and go with [clear].
 */
class TransferFormState {
    var role by mutableStateOf<TransferRole?>(null)
    var busy by mutableStateOf(false)
    var errorRes by mutableStateOf<Int?>(null)

    /** Sender: the QR texts to cycle through, once the receiver's code has been scanned. */
    var frames by mutableStateOf<List<String>>(emptyList())
    var sentCount by mutableStateOf(0)

    /** Receiver: the QR text that identifies this device to the sender. */
    var receiverCode by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false)
    var framesReceived by mutableStateOf(0)
    var framesTotal by mutableStateOf(0)
    var result by mutableStateOf<MigrationResult?>(null)

    internal var keyPair: KeyPair? = null
    internal val collector = Transfer.FrameCollector()
    internal var assembled by mutableStateOf<ByteArray?>(null)

    fun clear() {
        role = null
        busy = false
        errorRes = null
        frames = emptyList()
        sentCount = 0
        receiverCode = null
        scanning = false
        framesReceived = 0
        framesTotal = 0
        result = null
        keyPair = null
        collector.reset()
        assembled?.fill(0)
        assembled = null
    }
}

/** How often an unlocked Enterprise phone asks its Gateways for pending challenges. */
private const val CHALLENGE_POLL_MS = 4_000L

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AuthenticatorApp
    private val repository = app.repository
    private val preferences = app.preferences

    /** Organisation rules; [Policy.NONE] on an unmanaged device. */
    val policy: StateFlow<Policy> = app.policies.policy

    /** Shown once per unlock while a hardware-only policy finds exportable accounts. */
    var showMoveToChipPrompt by mutableStateOf(false)
    private var moveToChipPromptEpoch = -1

    var showBackupPrompt by mutableStateOf(false)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val addForm = AddFormState()
    val backupForm = BackupFormState()
    val transferForm = TransferFormState()
    var migrationResult by mutableStateOf<MigrationResult?>(null)

    /**
     * True while an activity we opened ourselves (the QR scanner, the system file picker)
     * covers the app, so that stop does not lock.
     */
    @Volatile
    var childActivityInFront = false

    private val refreshMutex = Mutex()
    private val totpCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val hotpCodes = ConcurrentHashMap<String, String>()

    /** Key metadata, not key material: it does not change and need not be cleared on lock. */
    private val keyLevels = ConcurrentHashMap<String, KeyLevel>()

    /** Challenges the user sent away with "not me", left to expire on the Gateway. */
    private val dismissedChallenges = HashSet<String>()

    init {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1000 - System.currentTimeMillis() % 1000)
            }
        }
        if (Edition.CONNECTED) {
            viewModelScope.launch {
                while (true) {
                    pollChallenges()
                    delay(CHALLENGE_POLL_MS)
                }
            }
        }
    }

    fun onAuthenticated() {
        app.policies.refresh()
        _state.update { it.copy(locked = false) }
        viewModelScope.launch { refresh() }
        // Checked again at every unlock: a debugger can attach long after the app started.
        viewModelScope.launch {
            val risks = withContext(Dispatchers.IO) { DeviceIntegrity.check() }
            _state.update { it.copy(risks = risks) }
        }
    }

    fun lock() {
        _state.update {
            if (it.locked) it else it.copy(locked = true, lockEpoch = it.lockEpoch + 1, screen = Screen.List, rows = null)
        }
        addForm.clear()
        backupForm.clear()
        transferForm.clear()
        dismissedChallenges.clear()
        // Taking the mutex waits out any refresh still in flight, so it cannot repopulate the caches.
        viewModelScope.launch { refreshMutex.withLock { clearCodes() } }
    }

    fun onStop() {
        if (!childActivityInFront) lock()
    }

    fun navigate(screen: Screen) {
        // Defence in depth: a stale menu must not reach a screen the organisation disabled.
        if (!policy.value.allows(screen)) return
        addForm.clear()
        backupForm.clear()
        transferForm.clear()
        migrationResult = null
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.List) viewModelScope.launch { refresh() }
    }

    fun selectGroup(group: String?) {
        _state.update { it.copy(selectedGroup = group) }
    }

    fun setGroup(id: String, group: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setGroup(id, group) }
            refresh()
        }
    }

    suspend fun addAccount(spec: OtpSpec, protection: Protection, group: String = ""): OpResult {
        val enrolment = addForm.veilo?.takeIf { Edition.CONNECTED }
        val result = withContext(Dispatchers.IO) {
            try {
                val account = repository.add(spec, protection, group)
                if (enrolment != null) confirmWithGateway(account, enrolment) else OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: DuplicateAccountException) {
                addForm.errorRes = R.string.add_duplicate
                OpResult.Failed
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) {
            spec.secret.fill(0)
            if (!preferences.hasShownBackupPrompt()) showBackupPrompt = true
            navigate(Screen.List)
        }
        return result
    }

    /**
     * Enterprise flavour: registers the freshly stored account with the Gateway named in the QR.
     * Enrolment is all-or-nothing: on refusal (the Gateway has disabled the token) or when the
     * Gateway cannot be reached (the ticket is still valid, the user can rescan online) the
     * account is removed again, so the phone never holds a token the organisation does not know.
     */
    private fun confirmWithGateway(account: Account, enrolment: VeiloEnrolment): OpResult {
        val gateway = Edition.gateway(app) ?: return OpResult.Success
        val alias = DeviceKeyFactory.alias(account.id)
        val result = try {
            val strongBox = app.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            val deviceKey = DeviceKeyFactory.generate(alias, enrolment.attestationChallenge(), strongBox)
            gateway.confirm(
                enrolment = enrolment,
                keyLevel = repository.keyLevel(account).toVeilo(),
                app = DeviceKeyFactory.appIdentity(app),
                deviceKey = deviceKey,
                policySnapshot = policy.value.toSnapshot(),
            )
        } catch (e: Exception) {
            ConfirmResult.Unreachable(e.javaClass.simpleName)
        }
        return when (result) {
            is ConfirmResult.Recorded -> {
                // From now on this phone answers the Gateway's challenges for this account (§5).
                repository.bindGateway(account.id, GatewayBinding(enrolment.gatewayUrl, result.deviceId))
                toast(R.string.enrol_gateway_recorded)
                OpResult.Success
            }
            is ConfirmResult.Refused -> {
                repository.delete(account.id)
                DeviceKeyFactory.delete(alias)
                addForm.errorRes = GatewayRefusal.messageRes(result.code)
                OpResult.Failed
            }
            is ConfirmResult.Unreachable -> {
                repository.delete(account.id)
                DeviceKeyFactory.delete(alias)
                addForm.errorRes = R.string.enrol_gateway_unreachable
                OpResult.Failed
            }
        }
    }

    // --- 0.4: signed challenge-response (Enterprise flavour) ---------------------------------

    /**
     * Asks every Gateway-bound account's Gateway what it is waiting on and shows the first
     * pending challenge. Runs only while unlocked: a locked phone must not reveal that a
     * login is in progress, and the user has to be there to read the number.
     */
    private suspend fun pollChallenges() {
        val state = _state.value
        if (state.locked || state.challenge != null || policy.value.blocks(state.risks)) return
        val gateway = Edition.gateway(app) ?: return
        val bound = try {
            repository.all().filter { it.gateway != null }
        } catch (e: Exception) {
            return
        }
        if (bound.isEmpty()) return
        val prompt = withContext(Dispatchers.IO) {
            for (account in bound) {
                val binding = account.gateway ?: continue
                val fetch = try {
                    gateway.pendingChallenges(binding.url, binding.deviceId, DeviceKeyFactory.signer(DeviceKeyFactory.alias(account.id)))
                } catch (e: Exception) {
                    continue
                }
                val first = (fetch as? ChallengeFetch.Pending)?.challenges?.firstOrNull { it.id !in dismissedChallenges } ?: continue
                return@withContext ChallengePrompt(account.id, account.issuer, account.name, first)
            }
            null
        } ?: return
        _state.update { if (it.locked || it.challenge != null) it else it.copy(challenge = prompt) }
    }

    /** The user tapped [number]: sign and send it. One attempt; the Gateway decides. */
    fun answerChallenge(number: String) {
        val prompt = _state.value.challenge ?: return
        if (prompt.busy) return
        _state.update { it.copy(challenge = prompt.copy(busy = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val gateway = Edition.gateway(app) ?: return@withContext AnswerResult.Unreachable("no gateway")
                val binding = repository.all().find { it.id == prompt.accountId }?.gateway
                    ?: return@withContext AnswerResult.Gone(0, "unbound")
                try {
                    gateway.answer(binding.url, binding.deviceId, prompt.challenge, number, DeviceKeyFactory.signer(DeviceKeyFactory.alias(prompt.accountId)))
                } catch (e: Exception) {
                    AnswerResult.Unreachable(e.javaClass.simpleName)
                }
            }
            when (result) {
                AnswerResult.Answered -> toast(R.string.challenge_answered)
                is AnswerResult.Refused -> toast(if (result.reason == "wrong_number") R.string.challenge_wrong_number else R.string.challenge_refused)
                is AnswerResult.Gone -> toast(R.string.challenge_gone)
                is AnswerResult.Unreachable -> {
                    toast(R.string.challenge_unreachable)
                    // keep it off the screen until the next poll shows it is still pending
                    dismissedChallenges.add(prompt.challenge.id)
                }
            }
            if (result !is AnswerResult.Unreachable) dismissedChallenges.add(prompt.challenge.id)
            _state.update { if (it.challenge?.challenge?.id == prompt.challenge.id) it.copy(challenge = null) else it }
            if (result is AnswerResult.Unreachable) {
                delay(CHALLENGE_POLL_MS)
                dismissedChallenges.remove(prompt.challenge.id)
            }
        }
    }

    /** "Not me": nothing is sent; the challenge expires on the Gateway and the login fails. */
    fun dismissChallenge() {
        val prompt = _state.value.challenge ?: return
        dismissedChallenges.add(prompt.challenge.id)
        _state.update { if (it.challenge?.challenge?.id == prompt.challenge.id) it.copy(challenge = null) else it }
    }

    private fun KeyLevel.toVeilo(): VeiloKeyLevel = when (this) {
        KeyLevel.STRONGBOX -> VeiloKeyLevel.STRONGBOX
        KeyLevel.SECURE_HARDWARE -> VeiloKeyLevel.TEE
        KeyLevel.SOFTWARE, KeyLevel.UNKNOWN -> VeiloKeyLevel.SOFTWARE
    }

    fun dismissBackupPrompt() {
        showBackupPrompt = false
        preferences.markBackupPromptShown()
    }

    fun acceptBackupPrompt() {
        dismissBackupPrompt()
        navigate(Screen.Export)
    }

    /** The storage new and imported accounts must use. */
    private fun requiredProtection(): Protection =
        if (policy.value.hardwareBoundOnly) Protection.HARDWARE_BOUND else Protection.EXPORTABLE

    fun dismissMoveToChipPrompt() {
        showMoveToChipPrompt = false
    }

    /** Moves every exportable account into the security chip; reports what the Keystore refused. */
    suspend fun moveAllToHardware(): OpResult {
        showMoveToChipPrompt = false
        var moved = 0
        var refused = 0
        val result = withContext(Dispatchers.IO) {
            try {
                for (account in repository.all().filter { it.protection == Protection.EXPORTABLE }) {
                    if (repository.moveToHardware(account.id)) moved++ else refused++
                    keyLevels.remove(account.id)
                }
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) {
            toast(R.string.move_to_chip_done, moved, refused)
            refresh()
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
        var rejected = 0
        val result = withContext(Dispatchers.IO) {
            try {
                val imported = repository.importAll(specs, requiredProtection())
                added = imported.added
                alreadyPresent = imported.alreadyPresent
                rejected = imported.rejected
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            }
        }
        if (result == OpResult.Success) {
            toast(R.string.import_done, added, alreadyPresent)
            if (rejected > 0) toast(R.string.import_rejected_by_policy, rejected)
            navigate(Screen.List)
        }
        return result
    }

    private fun importErrorFor(reason: BackupException.Reason): Int = when (reason) {
        BackupException.Reason.NOT_A_BACKUP -> R.string.import_file_invalid
        BackupException.Reason.UNSUPPORTED -> R.string.import_file_unsupported
        BackupException.Reason.WRONG_PASSPHRASE_OR_CORRUPT -> R.string.import_wrong_passphrase
    }

    /**
     * Safe from any thread: Toast needs a Looper, and a call from Dispatchers.IO (the Gateway
     * confirm runs there) would otherwise throw and turn a successful enrolment into "failed".
     */
    private fun toast(messageRes: Int, vararg args: Any) {
        val app = getApplication<Application>()
        val text = app.getString(messageRes, *args)
        val show = { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
        if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post { show() }
    }

    fun rename(id: String, issuer: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.rename(id, issuer, name) }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val bound = repository.all().find { it.id == id }?.gateway != null
                repository.delete(id)
                // the Gateway device key has no owner any more; the Gateway still lists the device
                if (bound) DeviceKeyFactory.delete(DeviceKeyFactory.alias(id))
            }
            totpCache.remove(id)
            hotpCodes.remove(id)
            keyLevels.remove(id)
            refresh()
        }
    }

    /** Recomputes the visible codes. Locks the app if the Keystore no longer accepts the last authentication. */
    private suspend fun refresh() = refreshMutex.withLock {
        val before = _state.value
        if (before.locked || before.screen != Screen.List) return@withLock
        // The organisation forbids codes under a detected risk: compute nothing, show nothing.
        if (policy.value.blocks(before.risks)) {
            clearCodes()
            _state.update { if (it.lockEpoch == before.lockEpoch) it.copy(rows = emptyList()) else it }
            return@withLock
        }

        val now = System.currentTimeMillis() / 1000
        val rows = try {
            withContext(Dispatchers.Default) { repository.all().map { rowFor(it, now) } }
        } catch (e: UserNotAuthenticatedException) {
            lock()
            return@withLock
        }
        val sorted = rows.sortedByDescending { it.pinned }
        val groups = sorted.map { it.group }.filter { it.isNotEmpty() }.distinct().sorted()
        val exportableUnderPolicy = if (policy.value.hardwareBoundOnly) sorted.count { !it.hardwareBound } else 0
        _state.update {
            if (it.locked || it.lockEpoch != before.lockEpoch) {
                it
            } else {
                it.copy(rows = sorted, epochSeconds = now, groups = groups, exportableUnderPolicy = exportableUnderPolicy)
            }
        }
        if (exportableUnderPolicy > 0 && moveToChipPromptEpoch != before.lockEpoch) {
            moveToChipPromptEpoch = before.lockEpoch
            showMoveToChipPrompt = true
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
            keyLevel = keyLevels.getOrPut(account.id) { repository.keyLevel(account) },
            code = code,
            unusable = unusable,
            group = account.group,
            pinned = account.pinned,
        )
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.togglePin(id) }
            refresh()
        }
    }

    private fun clearCodes() {
        totpCache.clear()
        hotpCodes.clear()
    }

    // --- Migration ---

    fun handleGoogleAuthScan(data: String) {
        viewModelScope.launch {
            val specs = try {
                withContext(Dispatchers.Default) { GoogleAuthMigration.parse(data) }
            } catch (e: Exception) {
                toast(R.string.migration_scan_invalid)
                return@launch
            }
            importMigrationSpecs(specs)
        }
    }

    fun handleAegisFile(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val specs = withContext(Dispatchers.IO) {
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
                    AegisMigration.parse(bytes)
                } catch (e: Exception) {
                    null
                }
            }
            if (specs == null || specs.isEmpty()) {
                toast(R.string.migration_file_invalid)
                return@launch
            }
            importMigrationSpecs(specs)
        }
    }

    private suspend fun importMigrationSpecs(specs: List<OtpSpec>) {
        val result = withContext(Dispatchers.IO) {
            try {
                repository.importAll(specs, requiredProtection())
            } catch (e: UserNotAuthenticatedException) {
                null
            } catch (e: Exception) {
                null
            } finally {
                specs.forEach { it.secret.fill(0) }
            }
        }
        if (result != null) {
            migrationResult = MigrationResult(result.added, result.alreadyPresent)
            toast(R.string.migration_done, result.added, result.alreadyPresent)
            if (result.rejected > 0) toast(R.string.import_rejected_by_policy, result.rejected)
            refresh()
        } else {
            toast(R.string.migration_failed)
        }
    }

    // --- PDF Export ---

    suspend fun writePdfExport(uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val specs = withContext(Dispatchers.IO) {
            try {
                repository.exportableSpecs()
            } catch (e: UserNotAuthenticatedException) {
                null
            } catch (e: Exception) {
                null
            }
        }
        if (specs == null || specs.isEmpty()) return false
        val written = withContext(Dispatchers.IO) {
            try {
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("No stream")).use { out ->
                    PdfExport.generate(getApplication(), specs, out)
                }
                true
            } catch (e: Exception) {
                try {
                    DocumentsContract.deleteDocument(resolver, uri)
                } catch (_: Exception) {
                }
                false
            } finally {
                specs.forEach { it.secret.fill(0) }
            }
        }
        if (written) {
            toast(R.string.pdf_export_done)
            navigate(Screen.List)
        }
        return written
    }

    // --- Device-to-device transfer ---

    fun chooseTransferRole(role: TransferRole) {
        val allowed = if (role == TransferRole.Send) policy.value.allowTransferSend else policy.value.allowTransferReceive
        if (!allowed) return
        val form = transferForm
        form.clear()
        form.role = role
        if (role == TransferRole.Receive) {
            form.busy = true
            viewModelScope.launch {
                val keyPair = withContext(Dispatchers.Default) { Transfer.newKeyPair() }
                form.keyPair = keyPair
                form.receiverCode = Transfer.receiverCode(keyPair.public)
                form.busy = false
            }
        }
    }

    /**
     * Sender: encrypts every backup-capable account for the device whose code was just scanned
     * and prepares the QR frames. Failure reasons land in [transferForm].
     */
    suspend fun prepareTransfer(receiverCodeText: String): OpResult {
        val form = transferForm
        val receiver = try {
            Transfer.parseReceiverCode(receiverCodeText)
        } catch (e: TransferException) {
            form.errorRes = R.string.transfer_code_invalid
            return OpResult.Failed
        }
        var frames: List<String> = emptyList()
        var count = 0
        val result = withContext(Dispatchers.Default) {
            var specs = emptyList<OtpSpec>()
            try {
                specs = repository.exportableSpecs()
                if (specs.isEmpty()) return@withContext OpResult.Failed
                val payload = BackupPayload.encode(specs)
                try {
                    frames = Transfer.frames(Transfer.encrypt(payload, receiver))
                } finally {
                    payload.fill(0)
                }
                count = specs.size
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: Exception) {
                OpResult.Failed
            } finally {
                specs.forEach { it.secret.fill(0) }
            }
        }
        when (result) {
            OpResult.Success -> {
                form.frames = frames
                form.sentCount = count
            }
            OpResult.Failed -> form.errorRes = R.string.transfer_failed
            OpResult.NeedsAuth -> Unit
        }
        return result
    }

    fun startTransferScanning() {
        transferForm.scanning = true
    }

    /**
     * Receiver: takes one scanned QR text. Returns true when the last frame has just arrived
     * and [importTransfer] should be called.
     */
    fun acceptTransferFrame(text: String): Boolean {
        val form = transferForm
        if (!form.scanning || form.assembled != null) return false
        if (!form.collector.accept(text)) return false
        form.framesReceived = form.collector.received
        form.framesTotal = form.collector.total
        val blob = try {
            form.collector.assembled()
        } catch (e: TransferException) {
            form.collector.reset()
            form.framesReceived = 0
            form.framesTotal = 0
            form.errorRes = R.string.transfer_data_invalid
            return false
        } ?: return false
        form.assembled = blob
        form.scanning = false
        return true
    }

    /** Receiver: decrypts the assembled frames with this device's key and adds the accounts. */
    suspend fun importTransfer(): OpResult {
        val form = transferForm
        val blob = form.assembled ?: return OpResult.Failed
        val keyPair = form.keyPair ?: return OpResult.Failed
        var added = 0
        var alreadyPresent = 0
        var rejected = 0
        var errorRes: Int? = null
        val result = withContext(Dispatchers.Default) {
            var specs: List<OtpSpec> = emptyList()
            try {
                val payload = Transfer.decrypt(blob, keyPair)
                try {
                    specs = BackupPayload.decode(payload)
                } finally {
                    payload.fill(0)
                }
                val imported = repository.importAll(specs, requiredProtection())
                added = imported.added
                alreadyPresent = imported.alreadyPresent
                rejected = imported.rejected
                OpResult.Success
            } catch (e: UserNotAuthenticatedException) {
                OpResult.NeedsAuth
            } catch (e: TransferException) {
                errorRes = R.string.transfer_data_invalid
                OpResult.Failed
            } catch (e: BackupException) {
                errorRes = R.string.transfer_data_invalid
                OpResult.Failed
            } catch (e: Exception) {
                errorRes = R.string.transfer_failed
                OpResult.Failed
            } finally {
                specs.forEach { it.secret.fill(0) }
            }
        }
        when (result) {
            OpResult.Success -> {
                blob.fill(0)
                form.result = MigrationResult(added, alreadyPresent)
                toast(R.string.transfer_done, added, alreadyPresent)
                if (rejected > 0) toast(R.string.import_rejected_by_policy, rejected)
                refresh()
            }
            OpResult.Failed -> {
                form.errorRes = errorRes
                // Let the user try again without starting over on the sender.
                form.assembled = null
                form.collector.reset()
                form.framesReceived = 0
                form.framesTotal = 0
                form.scanning = true
            }
            OpResult.NeedsAuth -> Unit
        }
        return result
    }

    private companion object {
        /** Far above any real backup (a few hundred bytes per account); bounds what a hostile file can make us read. */
        const val MAX_BACKUP_BYTES = 1 shl 20
    }
}
