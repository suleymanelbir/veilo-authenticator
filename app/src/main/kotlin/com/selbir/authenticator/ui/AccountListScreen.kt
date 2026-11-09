package com.selbir.authenticator.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.selbir.authenticator.R
import com.selbir.authenticator.core.Otp
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.security.DeviceRisk
import com.selbir.authenticator.policy.Policy
import com.selbir.authenticator.security.KeyLevel
import com.selbir.authenticator.security.SensitiveClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    state: UiState,
    policy: Policy,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onAbout: () -> Unit,
    onMigrate: () -> Unit,
    onPdfExport: () -> Unit,
    onTransfer: () -> Unit,
    onLock: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String, String) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onSetGroup: (String, String) -> Unit,
    onTogglePin: (String) -> Unit,
    onNextHotp: (String) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<AccountRow?>(null) }
    var pendingEdit by remember { mutableStateOf<AccountRow?>(null) }
    var editingRow by remember { mutableStateOf<AccountRow?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }

    // Tracks which account codes are currently revealed (tap-to-reveal).
    val revealedIds = remember { mutableStateMapOf<String, Long>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text(stringResource(R.string.app_name))
                            if (policy.managed) {
                                Text(
                                    text = policy.organizationName?.let { stringResource(R.string.managed_by, it) }
                                        ?: stringResource(R.string.managed_by_unknown),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    val rows = state.rows
                    if (rows != null && rows.isNotEmpty()) {
                        IconButton(onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible) searchQuery = ""
                        }) {
                            Icon(
                                painterResource(if (searchVisible) R.drawable.ic_close else R.drawable.ic_search),
                                stringResource(R.string.search_hint),
                            )
                        }
                    }
                    IconButton(onClick = onLock) {
                        Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.action_lock))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (policy.allowBackupExport) DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_title)) },
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                            if (policy.allowBackupImport) DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_title)) },
                                onClick = {
                                    menuOpen = false
                                    onImport()
                                },
                            )
                            if (policy.allowPdfExport) DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_export_title)) },
                                onClick = {
                                    menuOpen = false
                                    onPdfExport()
                                },
                            )
                            if (policy.allowBackupImport) DropdownMenuItem(
                                text = { Text(stringResource(R.string.migration_title)) },
                                onClick = {
                                    menuOpen = false
                                    onMigrate()
                                },
                            )
                            if (policy.allowTransfer) DropdownMenuItem(
                                text = { Text(stringResource(R.string.transfer_title)) },
                                onClick = {
                                    menuOpen = false
                                    onTransfer()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_title)) },
                                onClick = {
                                    menuOpen = false
                                    onAbout()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_account))
            }
        },
    ) { padding ->
        val rows = state.rows
        val groupFiltered = when (state.selectedGroup) {
            null -> rows
            else -> rows?.filter { it.group == state.selectedGroup }
        }
        val filteredRows = if (searchQuery.isBlank()) groupFiltered else groupFiltered?.filter { row ->
            row.issuer.contains(searchQuery, ignoreCase = true) ||
                row.name.contains(searchQuery, ignoreCase = true)
        }

        // Clear revealed state when the TOTP time step changes.
        revealedIds.entries.removeAll { (_, step) -> step != state.epochSeconds / 30 }

        when {
            filteredRows == null -> Unit

            filteredRows.isEmpty() -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                RiskBanner(state.risks)
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.accounts_empty), textAlign = TextAlign.Center)
                }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                if (state.risks.isNotEmpty()) item(key = RISK_BANNER_KEY) { RiskBanner(state.risks) }
                if (state.groups.isNotEmpty()) item(key = GROUP_CHIPS_KEY) {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = state.selectedGroup == null,
                                onClick = { onSelectGroup(null) },
                                label = { Text(stringResource(R.string.group_all)) },
                            )
                        }
                        items(state.groups) { group ->
                            FilterChip(
                                selected = state.selectedGroup == group,
                                onClick = { onSelectGroup(group) },
                                label = { Text(group) },
                            )
                        }
                    }
                }
                items(filteredRows, key = { it.id }) { row ->
                    val revealed = row.id in revealedIds
                    AccountItem(
                        row = row,
                        revealed = revealed,
                        epochSeconds = state.epochSeconds,
                        onTap = {
                            if (row.code != null) {
                                if (revealed) {
                                    copyCode(context, row.code, row.displayTitle)
                                } else {
                                    revealedIds[row.id] = state.epochSeconds / 30
                                    copyCode(context, row.code, row.displayTitle)
                                }
                            }
                        },
                        onLongPress = { pendingEdit = row },
                        onNextHotp = { onNextHotp(row.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Long-press action sheet: Pin / Edit / Delete
    pendingEdit?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text(row.displayTitle) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            pendingEdit = null
                            onTogglePin(row.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(if (row.pinned) R.string.action_unpin else R.string.action_pin))
                    }
                    TextButton(
                        onClick = {
                            pendingEdit = null
                            editingRow = row
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_edit)) }
                    TextButton(
                        onClick = {
                            val target = row
                            pendingEdit = null
                            pendingDelete = target
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    // Delete confirmation
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, row.displayTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Edit dialog
    editingRow?.let { row ->
        EditAccountDialog(
            row = row,
            groups = state.groups,
            onDismiss = { editingRow = null },
            onSave = { issuer, name, group ->
                onRename(row.id, issuer, name)
                if (group != row.group) onSetGroup(row.id, group)
                editingRow = null
            },
        )
    }
}

@Composable
private fun EditAccountDialog(
    row: AccountRow,
    groups: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (issuer: String, name: String, group: String) -> Unit,
) {
    var issuer by remember(row.id) { mutableStateOf(row.issuer) }
    var name by remember(row.id) { mutableStateOf(row.name) }
    var group by remember(row.id) { mutableStateOf(row.group) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text(stringResource(R.string.field_issuer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_account)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text(stringResource(R.string.field_group)) },
                    placeholder = { Text(stringResource(R.string.group_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (groups.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(groups) { g ->
                            FilterChip(
                                selected = group == g,
                                onClick = { group = g },
                                label = { Text(g, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(issuer.trim(), name.trim(), group.trim()) },
                enabled = issuer.isNotBlank() || name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val AccountRow.displayTitle: String
    get() = issuer.ifBlank { name }

/** Account ids are UUIDs, so this cannot collide with a row key. */
private const val RISK_BANNER_KEY = "risk-banner"
private const val GROUP_CHIPS_KEY = "group-chips"

/** Warns, without blocking: the checks behind it can be wrong in both directions. */
@Composable
private fun RiskBanner(risks: Set<DeviceRisk>) {
    if (risks.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (DeviceRisk.ROOTED in risks) {
                Text(stringResource(R.string.risk_rooted), style = MaterialTheme.typography.bodyMedium)
            }
            if (DeviceRisk.DEBUGGER in risks) {
                Text(stringResource(R.string.risk_debugger), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountItem(
    row: AccountRow,
    revealed: Boolean,
    epochSeconds: Long,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onNextHotp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.action_edit),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.pinned) {
                    Text(
                        text = stringResource(R.string.pin_indicator),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(
                    text = row.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.issuer.isNotBlank() && row.name.isNotBlank()) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                row.unusable -> Text(
                    text = stringResource(R.string.key_unusable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                row.code != null && (revealed || row.type == OtpType.HOTP) -> Text(
                    text = formatCode(row.code),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )

                row.code != null -> Text(
                    text = stringResource(R.string.code_tap_to_reveal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Text(
                    text = stringResource(R.string.hotp_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.hardwareBound) {
                Text(
                    text = when (row.keyLevel) {
                        KeyLevel.STRONGBOX -> stringResource(R.string.badge_hardware_bound_strongbox)
                        KeyLevel.SECURE_HARDWARE -> stringResource(R.string.badge_hardware_bound_tee)
                        KeyLevel.SOFTWARE -> stringResource(R.string.badge_hardware_bound_software)
                        KeyLevel.UNKNOWN -> stringResource(R.string.badge_hardware_bound)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.keyLevel == KeyLevel.SOFTWARE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            } else if (row.keyLevel == KeyLevel.SOFTWARE) {
                Text(
                    text = stringResource(R.string.badge_wrapped_software),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        when {
            row.unusable -> Unit
            row.type == OtpType.TOTP -> Countdown(Otp.secondsRemaining(epochSeconds, row.period), row.period)
            else -> IconButton(onClick = onNextHotp) {
                Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.action_next_code))
            }
        }
    }
}

@Composable
private fun Countdown(remaining: Int, period: Int) {
    val color = when {
        remaining <= 5 -> MaterialTheme.colorScheme.error
        remaining <= 10 -> WarningColor
        else -> MaterialTheme.colorScheme.primary
    }
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { remaining / period.toFloat() },
            modifier = Modifier.size(40.dp),
            color = color,
        )
        Text(
            text = remaining.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private val WarningColor = androidx.compose.ui.graphics.Color(0xFFE6A700)

/** "123456" -> "123 456", "12345678" -> "1234 5678". */
private fun formatCode(code: String): String = code.chunked((code.length + 1) / 2).joinToString(" ")

private fun copyCode(context: Context, code: String, label: String) {
    SensitiveClipboard.copy(context, code, label)
    val seconds = SensitiveClipboard.CLEAR_AFTER_SECONDS
    val message = context.resources.getQuantityString(R.plurals.code_copied, seconds, seconds)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
