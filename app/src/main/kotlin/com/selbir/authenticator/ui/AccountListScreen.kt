package com.selbir.authenticator.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.selbir.authenticator.security.SensitiveClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(
    state: UiState,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onLock: () -> Unit,
    onDelete: (String) -> Unit,
    onNextHotp: (String) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<AccountRow?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onLock) {
                        Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.action_lock))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_title)) },
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_title)) },
                                onClick = {
                                    menuOpen = false
                                    onImport()
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
        when {
            rows == null -> Unit // First load after unlock: draw nothing rather than flash the empty message.

            rows.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.accounts_empty), textAlign = TextAlign.Center)
            }

            else -> LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                items(rows, key = { it.id }) { row ->
                    AccountItem(
                        row = row,
                        epochSeconds = state.epochSeconds,
                        onCopy = { code -> copyCode(context, code) },
                        onLongPress = { pendingDelete = row },
                        onNextHotp = { onNextHotp(row.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

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
}

private val AccountRow.displayTitle: String
    get() = issuer.ifBlank { name }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountItem(
    row: AccountRow,
    epochSeconds: Long,
    onCopy: (String) -> Unit,
    onLongPress: () -> Unit,
    onNextHotp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { row.code?.let(onCopy) },
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.action_delete),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

                row.code != null -> Text(
                    text = formatCode(row.code),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )

                else -> Text(
                    text = stringResource(R.string.hotp_hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.hardwareBound) {
                Text(
                    text = stringResource(R.string.badge_hardware_bound),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { remaining / period.toFloat() },
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = remaining.toString(),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** "123456" -> "123 456", "12345678" -> "1234 5678". */
private fun formatCode(code: String): String = code.chunked((code.length + 1) / 2).joinToString(" ")

private fun copyCode(context: Context, code: String) {
    SensitiveClipboard.copy(context, code)
    // Android 13+ shows its own "copied" confirmation as well, but only this message says
    // that the code will be cleared again.
    val seconds = SensitiveClipboard.CLEAR_AFTER_SECONDS
    val message = context.resources.getQuantityString(R.plurals.code_copied, seconds, seconds)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
