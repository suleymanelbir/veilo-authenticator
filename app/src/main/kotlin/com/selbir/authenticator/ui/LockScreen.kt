package com.selbir.authenticator.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.selbir.authenticator.R
import com.selbir.authenticator.security.BiometricGate

@Composable
fun LockScreen(lockEpoch: Int, onUnlock: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUnlock by rememberUpdatedState(onUnlock)

    // Prompt automatically once per lock, as soon as the activity is in front.
    // If the user dismisses it, the button below is the way back in.
    LaunchedEffect(lockEpoch) {
        lifecycleOwner.lifecycle.withResumed { }
        currentOnUnlock()
    }

    CenteredMessage(
        title = stringResource(R.string.app_name),
        body = stringResource(R.string.lock_screen_message),
        action = stringResource(R.string.action_unlock),
        onAction = onUnlock,
    )
}

/** Shown when the device has no screen lock, without which hardware-backed keys cannot be protected. */
@Composable
fun SetupRequiredScreen() {
    val context = LocalContext.current
    CenteredMessage(
        title = stringResource(R.string.setup_required_title),
        body = stringResource(R.string.setup_required_body),
        action = stringResource(R.string.action_open_settings),
        onAction = { openSecuritySettings(context) },
    )
}

@Composable
private fun CenteredMessage(title: String, body: String, action: String, onAction: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun openSecuritySettings(context: Context) {
    val enroll = Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricGate.AUTHENTICATORS)
    try {
        context.startActivity(enroll)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}
