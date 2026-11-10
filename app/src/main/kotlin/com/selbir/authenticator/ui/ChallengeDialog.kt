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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.selbir.authenticator.R

/**
 * Number matching (Gateway PROTOCOL.md §5): the login screen shows one number, the phone
 * offers three. Tapping one signs it with the device key and sends it; "not me" sends
 * nothing and lets the challenge expire on the Gateway.
 */
@Composable
fun ChallengeDialog(prompt: ChallengePrompt, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val account = listOf(prompt.issuer, prompt.name).filter { it.isNotBlank() }.joinToString(" · ")
    AlertDialog(
        onDismissRequest = { if (!prompt.busy) onDismiss() },
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
