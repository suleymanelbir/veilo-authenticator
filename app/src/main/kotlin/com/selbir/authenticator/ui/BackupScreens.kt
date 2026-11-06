package com.selbir.authenticator.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.selbir.authenticator.R
import kotlinx.coroutines.launch
import java.time.LocalDate

// Generic on purpose: Android's type registry has no entry for our container.
private const val BACKUP_MIME_TYPE = "application/octet-stream"

/**
 * @param onPickerVisible told true just before the system file picker opens and false when
 * it returns, so the app does not treat that trip as "went to background" and lock.
 * @param onEncrypt builds the encrypted backup; true once it is ready to be written.
 * @param onDestinationChosen receives where to write it, or null if the picker was dismissed.
 */
@Composable
fun ExportScreen(
    form: BackupFormState,
    exportableCount: Int,
    hardwareBoundCount: Int,
    onBack: () -> Unit,
    onPickerVisible: (Boolean) -> Unit,
    onEncrypt: suspend () -> Boolean,
    onDestinationChosen: (Uri?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        onPickerVisible(false)
        onDestinationChosen(uri)
    }

    BackupScaffold(title = stringResource(R.string.export_title), onBack = onBack) {
        Text(stringResource(R.string.export_body))
        Text(stringResource(R.string.export_included, exportableCount), style = MaterialTheme.typography.titleSmall)
        if (hardwareBoundCount > 0) {
            Text(
                text = stringResource(R.string.export_excluded, hardwareBoundCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PassphraseField(
            value = form.passphrase,
            onValueChange = {
                form.passphrase = it
                form.errorRes = null
            },
            label = stringResource(R.string.field_passphrase),
            enabled = !form.busy,
        )
        PassphraseField(
            value = form.confirmation,
            onValueChange = {
                form.confirmation = it
                form.errorRes = null
            },
            label = stringResource(R.string.field_passphrase_confirm),
            enabled = !form.busy,
        )
        Text(
            text = stringResource(R.string.export_passphrase_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BackupStatus(form)

        Button(
            onClick = {
                scope.launch {
                    if (onEncrypt()) {
                        onPickerVisible(true)
                        picker.launch("authenticator-backup-${LocalDate.now()}.otpb")
                    }
                }
            },
            enabled = !form.busy && exportableCount > 0 && form.passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_export))
        }
    }
}

/** @param onPickerVisible see [ExportScreen]. */
@Composable
fun ImportScreen(
    form: BackupFormState,
    onBack: () -> Unit,
    onPickerVisible: (Boolean) -> Unit,
    onFileChosen: (Uri) -> Unit,
    onImport: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPickerVisible(false)
        if (uri != null) onFileChosen(uri)
    }

    BackupScaffold(title = stringResource(R.string.import_title), onBack = onBack) {
        Text(stringResource(R.string.import_body))

        FilledTonalButton(
            onClick = {
                onPickerVisible(true)
                // Any type: file managers and cloud providers label our files inconsistently.
                picker.launch(arrayOf("*/*"))
            },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_choose_file))
        }
        if (form.blob != null) {
            Text(
                text = stringResource(R.string.import_file_loaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        PassphraseField(
            value = form.passphrase,
            onValueChange = {
                form.passphrase = it
                form.errorRes = null
            },
            label = stringResource(R.string.field_passphrase),
            enabled = !form.busy,
        )

        BackupStatus(form)

        Button(
            onClick = onImport,
            enabled = !form.busy && form.blob != null && form.passphrase.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_import))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PassphraseField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The error, or the progress bar while the key derivation runs (deliberately slow: a few seconds). */
@Composable
private fun BackupStatus(form: BackupFormState) {
    form.errorRes?.let { error ->
        Text(
            text = stringResource(error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (form.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall)
    }
}
