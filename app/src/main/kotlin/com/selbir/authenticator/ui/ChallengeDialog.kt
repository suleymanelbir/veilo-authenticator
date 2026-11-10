package com.selbir.authenticator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.selbir.authenticator.R
import kotlinx.coroutines.delay

/**
 * Number matching (Gateway PROTOCOL.md §5): the login screen shows one number, the phone
 * offers three. Tapping one signs it with the device key and sends it; "not me" (or the back
 * gesture) sends nothing and lets the challenge expire on the Gateway. A touch outside the
 * dialog does nothing, and the screen stays on while it is open.
 */
@Composable
fun ChallengeDialog(
    prompt: ChallengePrompt,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onExpire: () -> Unit = onDismiss,
) {
    val account = listOf(prompt.issuer, prompt.name).filter { it.isNotBlank() }.joinToString(" · ")
    // the Gateway gives each sign-in two minutes; show what is left and close when it is gone,
    // so a stale request never sits on the screen waiting for a tap that can only fail
    var left by remember(prompt.challenge.id) {
        mutableStateOf(secondsLeft(prompt.challenge.expiresAt, System.currentTimeMillis()))
    }
    LaunchedEffect(prompt.challenge.id) {
        while (true) {
            val now = secondsLeft(prompt.challenge.expiresAt, System.currentTimeMillis()) ?: break
            left = now
            if (now <= 0L) {
                onExpire()
                break
            }
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    // the user looks at the login screen on another device while this is open: keep the
    // phone awake, and do not treat a stray touch outside the dialog as "not me"
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    AlertDialog(
        onDismissRequest = { if (!prompt.busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.challenge_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.challenge_body, prompt.challenge.rpId, account))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    prompt.challenge.choices.forEach { number ->
                        FilledTonalButton(onClick = { onChoose(number) }, enabled = !prompt.busy) {
                            Text(number, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                left?.let {
                    Text(
                        stringResource(R.string.challenge_seconds_left, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (prompt.busy) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.busy) {
                Text(stringResource(R.string.challenge_not_me))
            }
        },
    )
}
