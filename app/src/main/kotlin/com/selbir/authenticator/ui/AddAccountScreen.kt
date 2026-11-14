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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpAuthUri
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.core.VeiloQrLogin
import com.selbir.authenticator.data.Protection
import com.selbir.authenticator.edition.Edition
import com.selbir.authenticator.security.SecureCaptureActivity

/**
 * @param onScannerVisible told true just before the QR scanner activity opens and false
 * when it returns, so the app does not treat that trip as "went to background" and lock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    form: AddFormState,
    /** Organisation policy: storage is fixed to the security chip and the choice is not shown. */
    hardwareBoundOnly: Boolean,
    groups: List<String> = emptyList(),
    onBack: () -> Unit,
    onScannerVisible: (Boolean) -> Unit,
    /** A "Veilo ile giriş" QR scanned here by habit: handed over instead of "not a valid QR". */
    onQrLogin: (String) -> Unit = {},
    onSave: () -> Unit,
) {
    val scanPrompt = stringResource(R.string.scan_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        onScannerVisible(false)
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        if (Edition.CONNECTED && VeiloQrLogin.looksLikeOne(contents)) {
            onQrLogin(contents)
            return@rememberLauncherForActivityResult
        }
        try {
            val spec = OtpAuthUri.parse(contents)
            // Public edition: parsed for validity only, never acted on. Enterprise: confirmed on save.
            form.veilo = VeiloEnrolment.parse(contents, allowPlainHttp = Edition.ALLOW_PLAIN_HTTP_GATEWAY)
            form.scanned = spec
            if (form.enterpriseEnrolment) form.protection = Protection.HARDWARE_BOUND
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
                            .setCaptureActivity(SecureCaptureActivity::class.java)
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
                form.veilo?.takeIf { form.enterpriseEnrolment }?.let { veilo ->
                    Text(
                        text = stringResource(
                            R.string.enrol_gateway_notice,
                            java.net.URI(veilo.gatewayUrl).host.orEmpty(),
                            veilo.requiredLevel.wire,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

            OutlinedTextField(
                value = form.group,
                onValueChange = { form.group = it },
                label = { Text(stringResource(R.string.field_group)) },
                placeholder = { Text(stringResource(R.string.group_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (groups.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups) { g ->
                        FilterChip(
                            selected = form.group == g,
                            onClick = { form.group = g },
                            label = { Text(g) },
                        )
                    }
                }
            }

            // Advanced options — only for manual entry.
            if (form.scanned == null) {
                var advancedOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { advancedOpen = !advancedOpen }) {
                    Text(stringResource(if (advancedOpen) R.string.advanced_hide else R.string.advanced_show))
                }
                if (advancedOpen) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AlgorithmDropdown(
                            selected = form.algorithm,
                            onSelect = { form.algorithm = it },
                            modifier = Modifier.weight(1f),
                        )
                        DigitsDropdown(
                            selected = form.digits,
                            onSelect = { form.digits = it },
                            modifier = Modifier.weight(1f),
                        )
                        PeriodDropdown(
                            selected = form.period,
                            onSelect = { form.period = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Text(stringResource(R.string.protection_title), style = MaterialTheme.typography.titleSmall)
            if (hardwareBoundOnly || form.enterpriseEnrolment) {
                Text(
                    text = stringResource(if (hardwareBoundOnly) R.string.restriction_hardware_bound_only else R.string.enrol_gateway_hardware_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else Column(modifier = Modifier.selectableGroup()) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmDropdown(selected: OtpAlgorithm, onSelect: (OtpAlgorithm) -> Unit, modifier: Modifier) {
    val options = OtpAlgorithm.entries
    val labels = mapOf(OtpAlgorithm.SHA1 to "SHA-1", OtpAlgorithm.SHA256 to "SHA-256", OtpAlgorithm.SHA512 to "SHA-512")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = labels[selected] ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_algorithm)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { algo ->
                DropdownMenuItem(text = { Text(labels[algo] ?: "") }, onClick = { onSelect(algo); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigitsDropdown(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val options = listOf(6, 7, 8)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_digits)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { d ->
                DropdownMenuItem(text = { Text(d.toString()) }, onClick = { onSelect(d); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodDropdown(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val options = listOf(30, 60)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = "${selected}s",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.field_period)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { p ->
                DropdownMenuItem(text = { Text("${p}s") }, onClick = { onSelect(p); expanded = false })
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
