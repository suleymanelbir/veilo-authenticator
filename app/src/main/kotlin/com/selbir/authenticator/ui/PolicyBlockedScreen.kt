package com.selbir.authenticator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.selbir.authenticator.R
import com.selbir.authenticator.policy.Policy
import com.selbir.authenticator.security.DeviceRisk

/** Replaces the account list when the organisation forbids codes under a detected [DeviceRisk]. */
@Composable
fun PolicyBlockedScreen(policy: Policy, risks: Set<DeviceRisk>, onLock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.blocked_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (DeviceRisk.ROOTED in risks && policy.blockOnRootedDevice) {
            Text(stringResource(R.string.blocked_rooted), textAlign = TextAlign.Center)
        }
        if (DeviceRisk.DEBUGGER in risks && policy.blockOnDebugger) {
            Text(stringResource(R.string.blocked_debugger), textAlign = TextAlign.Center)
        }
        policy.supportContact?.let {
            Text(
                text = stringResource(R.string.blocked_contact, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(onClick = onLock) { Text(stringResource(R.string.action_lock)) }
    }
}

/** True when the organisation's rules forbid codes under the risks found at unlock. */
fun Policy.blocks(risks: Set<DeviceRisk>): Boolean =
    (blockOnRootedDevice && DeviceRisk.ROOTED in risks) || (blockOnDebugger && DeviceRisk.DEBUGGER in risks)
