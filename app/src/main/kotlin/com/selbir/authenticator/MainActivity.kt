package com.selbir.authenticator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.selbir.authenticator.edition.Edition
import com.selbir.authenticator.security.BiometricGate
import com.selbir.authenticator.security.SensitiveClipboard
import com.selbir.authenticator.ui.AppRoot
import com.selbir.authenticator.ui.AppViewModel
import com.selbir.authenticator.ui.AuthenticatorTheme

// FragmentActivity (not ComponentActivity) because androidx.biometric requires it.
class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    // 1.1 push (Enterprise): "a sign-in is waiting" needs the notification permission (Android 13+)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askForNotificationsOnce() {
        if (!Edition.PUSH_CAPABLE || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("push", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Codes must not appear in screenshots, screen recordings, casts or the recents view.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)

        askForNotificationsOnce()
        enableEdgeToEdge()
        setContent {
            AuthenticatorTheme {
                AppRoot(
                    viewModel = viewModel,
                    authenticate = {
                        BiometricGate.authenticate(
                            activity = this,
                            title = getString(R.string.unlock_prompt_title),
                            subtitle = getString(R.string.unlock_prompt_subtitle),
                        )
                    },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SensitiveClipboard.onFocus(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onStop()
    }
}
