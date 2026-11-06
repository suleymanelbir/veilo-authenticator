package com.selbir.authenticator

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.selbir.authenticator.security.BiometricGate
import com.selbir.authenticator.ui.AppRoot
import com.selbir.authenticator.ui.AppViewModel
import com.selbir.authenticator.ui.AuthenticatorTheme

// FragmentActivity (not ComponentActivity) because androidx.biometric requires it.
class MainActivity : FragmentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Codes must not appear in screenshots, screen recordings, casts or the recents view.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)

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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onStop()
    }
}
