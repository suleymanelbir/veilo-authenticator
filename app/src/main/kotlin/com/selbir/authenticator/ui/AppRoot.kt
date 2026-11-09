package com.selbir.authenticator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.selbir.authenticator.R
import com.selbir.authenticator.data.Protection
import com.selbir.authenticator.security.BiometricGate
import kotlinx.coroutines.launch

/**
 * Chooses the screen and owns the authentication flow.
 *
 * @param authenticate shows the system biometric/credential prompt; true on success.
 */
@Composable
fun AppRoot(viewModel: AppViewModel, authenticate: suspend () -> Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Re-checked on every resume: the user may have just come back from system settings.
    var authAvailable by remember { mutableStateOf(BiometricGate.isAvailable(context)) }
    LifecycleResumeEffect(Unit) {
        authAvailable = BiometricGate.isAvailable(context)
        onPauseOrDispose { }
    }

    var prompting by remember { mutableStateOf(false) }
    val promptOnce: suspend () -> Boolean = {
        if (prompting) {
            false
        } else {
            prompting = true
            try {
                authenticate()
            } finally {
                prompting = false
            }
        }
    }

    when {
        !authAvailable -> SetupRequiredScreen()

        state.locked -> LockScreen(
            lockEpoch = state.lockEpoch,
            onUnlock = { scope.launch { if (promptOnce()) viewModel.onAuthenticated() } },
        )

        state.screen == Screen.Add -> {
            val form = viewModel.addForm
            BackHandler { viewModel.navigate(Screen.List) }
            AddAccountScreen(
                form = form,
                groups = state.groups,
                onBack = { viewModel.navigate(Screen.List) },
                onScannerVisible = { viewModel.childActivityInFront = it },
                onSave = {
                    val spec = form.toSpec()
                    if (spec == null) {
                        form.errorRes = R.string.secret_invalid
                    } else {
                        scope.launch {
                            form.saving = true
                            form.errorRes = null
                            val protection = form.protection
                            val group = form.group
                            val result = withAuthRetry(viewModel, promptOnce) { viewModel.addAccount(spec, protection, group) }
                            form.saving = false
                            if (result == OpResult.Failed) {
                                form.errorRes = if (protection == Protection.HARDWARE_BOUND) {
                                    R.string.add_failed_hardware_hint
                                } else {
                                    R.string.add_failed
                                }
                            }
                        }
                    }
                },
            )
        }

        state.screen == Screen.Export -> {
            val form = viewModel.backupForm
            val rows = state.rows.orEmpty()
            BackHandler { viewModel.navigate(Screen.List) }
            ExportScreen(
                form = form,
                exportableCount = rows.count { !it.hardwareBound },
                hardwareBoundCount = rows.count { it.hardwareBound },
                onBack = { viewModel.navigate(Screen.List) },
                onPickerVisible = { viewModel.childActivityInFront = it },
                onEncrypt = {
                    form.errorRes = form.newPassphraseError()
                    if (form.errorRes != null) {
                        false
                    } else {
                        form.busy = true
                        val result = try {
                            withAuthRetry(viewModel, promptOnce) { viewModel.encryptExport() }
                        } finally {
                            form.busy = false
                        }
                        if (result == OpResult.Failed) form.errorRes = R.string.export_failed
                        result == OpResult.Success
                    }
                },
                onDestinationChosen = { uri ->
                    if (uri == null) {
                        viewModel.discardExport()
                    } else {
                        scope.launch {
                            form.busy = true
                            val written = viewModel.writeExport(uri)
                            form.busy = false
                            if (!written) form.errorRes = R.string.export_failed
                        }
                    }
                },
            )
        }

        state.screen == Screen.Import -> {
            val form = viewModel.backupForm
            BackHandler { viewModel.navigate(Screen.List) }
            ImportScreen(
                form = form,
                onBack = { viewModel.navigate(Screen.List) },
                onPickerVisible = { viewModel.childActivityInFront = it },
                onFileChosen = { uri -> scope.launch { viewModel.loadImportFile(uri) } },
                onImport = {
                    scope.launch {
                        form.busy = true
                        form.errorRes = null
                        val specs = viewModel.decryptImport()
                        if (specs != null) {
                            val result = try {
                                withAuthRetry(viewModel, promptOnce) { viewModel.importAccounts(specs) }
                            } finally {
                                specs.forEach { it.secret.fill(0) }
                            }
                            if (result == OpResult.Failed) form.errorRes = R.string.import_failed
                        }
                        form.busy = false
                    }
                },
            )
        }

        state.screen == Screen.About -> {
            BackHandler { viewModel.navigate(Screen.List) }
            AboutScreen(onBack = { viewModel.navigate(Screen.List) })
        }

        state.screen == Screen.Migrate -> {
            BackHandler { viewModel.navigate(Screen.List) }
            MigrationScreen(
                onBack = { viewModel.navigate(Screen.List) },
                onScannerVisible = { viewModel.childActivityInFront = it },
                onGoogleAuthScanned = viewModel::handleGoogleAuthScan,
                onAegisFileChosen = viewModel::handleAegisFile,
                onPickerVisible = { viewModel.childActivityInFront = it },
                migrationResult = viewModel.migrationResult,
            )
        }

        state.screen == Screen.PdfExport -> {
            val rows = state.rows.orEmpty()
            BackHandler { viewModel.navigate(Screen.List) }
            PdfExportScreen(
                exportableCount = rows.count { !it.hardwareBound },
                hardwareBoundCount = rows.count { it.hardwareBound },
                onBack = { viewModel.navigate(Screen.List) },
                onPickerVisible = { viewModel.childActivityInFront = it },
                onDestinationChosen = { uri ->
                    if (uri != null) {
                        scope.launch { viewModel.writePdfExport(uri) }
                    }
                },
            )
        }

        state.screen == Screen.Transfer -> {
            val rows = state.rows.orEmpty()
            BackHandler { viewModel.navigate(Screen.List) }
            TransferScreen(
                form = viewModel.transferForm,
                exportableCount = rows.count { !it.hardwareBound },
                hardwareBoundCount = rows.count { it.hardwareBound },
                onBack = { viewModel.navigate(Screen.List) },
                onChooseRole = viewModel::chooseTransferRole,
                onChildVisible = { viewModel.childActivityInFront = it },
                onReceiverCodeScanned = { code ->
                    scope.launch {
                        val form = viewModel.transferForm
                        form.busy = true
                        try {
                            withAuthRetry(viewModel, promptOnce) { viewModel.prepareTransfer(code) }
                        } finally {
                            form.busy = false
                        }
                    }
                },
                onStartScanning = viewModel::startTransferScanning,
                onFrameScanned = { text ->
                    if (viewModel.acceptTransferFrame(text)) {
                        scope.launch { withAuthRetry(viewModel, promptOnce) { viewModel.importTransfer() } }
                    }
                },
            )
        }

        else -> AccountListScreen(
            state = state,
            onAdd = { viewModel.navigate(Screen.Add) },
            onExport = { viewModel.navigate(Screen.Export) },
            onImport = { viewModel.navigate(Screen.Import) },
            onAbout = { viewModel.navigate(Screen.About) },
            onMigrate = { viewModel.navigate(Screen.Migrate) },
            onPdfExport = { viewModel.navigate(Screen.PdfExport) },
            onTransfer = { viewModel.navigate(Screen.Transfer) },
            onLock = viewModel::lock,
            onDelete = viewModel::delete,
            onRename = viewModel::rename,
            onSelectGroup = viewModel::selectGroup,
            onSetGroup = viewModel::setGroup,
            onTogglePin = viewModel::togglePin,
            onNextHotp = { id -> scope.launch { withAuthRetry(viewModel, promptOnce) { viewModel.generateHotp(id) } } },
        )
    }

    if (viewModel.showBackupPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupPrompt,
            title = { Text(stringResource(R.string.backup_prompt_title)) },
            text = { Text(stringResource(R.string.backup_prompt_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::acceptBackupPrompt) {
                    Text(stringResource(R.string.backup_prompt_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissBackupPrompt) {
                    Text(stringResource(R.string.backup_prompt_dismiss))
                }
            },
        )
    }
}

/**
 * Runs [operation]; if the Keystore reports that the authentication window has lapsed,
 * prompts once and retries. If the user declines, the app locks.
 */
private suspend fun withAuthRetry(
    viewModel: AppViewModel,
    authenticate: suspend () -> Boolean,
    operation: suspend () -> OpResult,
): OpResult {
    var result = operation()
    if (result == OpResult.NeedsAuth && authenticate()) result = operation()
    if (result == OpResult.NeedsAuth) viewModel.lock()
    return result
}
