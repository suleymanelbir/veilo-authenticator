package com.selbir.authenticator.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.selbir.authenticator.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(
    onBack: () -> Unit,
    onScannerVisible: (Boolean) -> Unit,
    onGoogleAuthScanned: (String) -> Unit,
    onAegisFileChosen: (Uri) -> Unit,
    onPickerVisible: (Boolean) -> Unit,
    migrationResult: MigrationResult?,
) {
    val scanPrompt = stringResource(R.string.migration_scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        onScannerVisible(false)
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        onGoogleAuthScanned(contents)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onPickerVisible(false)
        if (uri != null) onAegisFileChosen(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.migration_title)) },
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
            Text(
                text = stringResource(R.string.migration_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            migrationResult?.let { result ->
                Text(
                    text = stringResource(R.string.migration_done, result.added, result.alreadyPresent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.added > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            // Google Authenticator
            Text(
                text = stringResource(R.string.migration_google_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.migration_google_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    onScannerVisible(true)
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.migration_scan_qr))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Microsoft Authenticator
            Text(
                text = stringResource(R.string.migration_microsoft_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.migration_microsoft_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    onScannerVisible(true)
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.migration_scan_qr))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Aegis
            Text(
                text = stringResource(R.string.migration_aegis_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.migration_aegis_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = {
                    onPickerVisible(true)
                    filePicker.launch(arrayOf("*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.migration_choose_file))
            }
        }
    }
}

data class MigrationResult(val added: Int, val alreadyPresent: Int)
