package com.selbir.authenticator.security

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The QR scanner with the same screen protection as [com.selbir.authenticator.MainActivity]:
 * the camera preview shows the QR code, which carries the seed (pentest 2026-11-12, D10), so it
 * must not end up in screenshots, screen recordings, casts or the recents view.
 */
class SecureCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        super.onCreate(savedInstanceState)
    }
}
