package com.selbir.authenticator.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.selbir.authenticator.R
import com.selbir.authenticator.core.OtpAuthUri
import com.selbir.authenticator.data.Protection

/**
 * @param onScannerVisible told true just before the QR scanner activity opens and false
 * when it returns, so the app does not treat that trip as "went to background" and lock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    form: AddFormState,
    onBack: () -> Unit,
    onScannerVisible: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val scanPrompt = stringResource(R.string.scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        onScannerVisible(false)
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        try {
            val spec = OtpAuthUri.parse(contents)
            form.scanned = spec
            form.issuer = spec.issuer
            form.account = spec.accountName
            form.secretText = ""
            form.errorRes = null
        } catch (e: IllegalArgumentException) {
            form.errorRes = R.string.scan_invalid
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
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
        ) {
            FilledTonalButton(
                onClick = {
                    onScannerVisible(true)
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_scan_qr))
            }

            if (form.scanned != null) {
                Text(
                    text = stringResource(R.string.scan_loaded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                OutlinedTextField(
                    value = form.secretText,
                    onValueChange = {
                        form.secretText = it
                        form.errorRes = null
                    },
                    label = { Text(stringResource(R.string.field_secret)) },
                    singleLine = true,
                    // Password type keeps keyboards from learning or suggesting the secret.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = form.issuer,
                onValueChange = { form.issuer = it },
                label = { Text(stringResource(R.string.field_issuer)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.account,
                onValueChange = { form.account = it },
                label = { Text(stringResource(R.string.field_account)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.protection_title), style = MaterialTheme.typography.titleSmall)
            Column(modifier = Modifier.selectableGroup()) {
                ProtectionOption(
                    selected = form.protection == Protection.EXPORTABLE,
                    title = stringResource(R.string.protection_exportable_title),
                    body = stringResource(R.string.protection_exportable_body),
                    onSelect = { form.protection = Protection.EXPORTABLE },
                )
                ProtectionOption(
                    selected = form.protection == Protection.HARDWARE_BOUND,
                    title = stringResource(R.string.protection_hardware_title),
                    body = stringResource(R.string.protection_hardware_body),
                    onSelect = { form.protection = Protection.HARDWARE_BOUND },
                )
            }

            form.errorRes?.let { error ->
                Text(
                    text = stringResource(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onSave,
                enabled = form.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun ProtectionOption(selected: Boolean, title: String, body: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
