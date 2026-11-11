package com.selbir.authenticator.store

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.selbir.authenticator.core.OtpAuthUri
import com.selbir.authenticator.core.OtpType
import com.selbir.authenticator.core.PendingChallenge
import com.selbir.authenticator.core.VeiloEnrolment
import com.selbir.authenticator.policy.Policy
import com.selbir.authenticator.security.KeyLevel
import com.selbir.authenticator.ui.AboutScreen
import com.selbir.authenticator.ui.AccountListScreen
import com.selbir.authenticator.ui.AccountRow
import com.selbir.authenticator.ui.AddAccountScreen
import com.selbir.authenticator.ui.AddFormState
import com.selbir.authenticator.ui.AuthenticatorTheme
import com.selbir.authenticator.ui.ChallengeDialog
import com.selbir.authenticator.ui.ChallengePrompt
import com.selbir.authenticator.ui.UiState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.OffsetDateTime

/**
 * Play store screenshots of Veilo Enterprise, drawn by the app's own composables with sample
 * data. The app sets FLAG_SECURE, so the phone cannot take them. Runs only when asked:
 *
 *     STORE_SHOTS_DIR=/path ./gradlew :app:testEnterpriseDebugUnitTest --tests '*StoreScreenshots*'
 *
 * 411×800 dp at xxhdpi = 1233×2400 px (Play wants an aspect ratio of at most 2:1).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "tr-rTR-w411dp-h800dp-port-xxhdpi")
open class StoreScreenshots {
    /** File name prefix: phone shots have none; the tablet subclasses below set one. */
    protected open val prefix = ""

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val out: File? = System.getenv("STORE_SHOTS_DIR")?.let(::File)

    private val now = System.currentTimeMillis() / 1000
    private val policy = Policy(managed = true, organizationName = "Deneme AŞ",
        supportContact = "bt@deneme.com.tr", hardwareBoundOnly = true)

    private fun row(id: String, issuer: String, name: String, code: String, group: String = "",
                    pinned: Boolean = false) = AccountRow(
        id = id, issuer = issuer, name = name, type = OtpType.TOTP, period = 30,
        hardwareBound = true, keyLevel = KeyLevel.SECURE_HARDWARE, code = code,
        unusable = false, group = group, pinned = pinned)

    private val rows = listOf(
        row("1", "Deneme AŞ", "mehmet", "418 207", group = "İş", pinned = true),
        row("2", "Deneme AŞ VPN", "mehmet.yilmaz", "903 516", group = "İş"),
        row("3", "GitHub", "mehmet-yilmaz", "275 940", group = "İş"),
        row("4", "Microsoft 365", "mehmet@deneme.com.tr", "661 382", group = "İş"),
        row("5", "AWS", "deneme-prod", "130 774", group = "İş"),
    )

    private fun state() = UiState(locked = false, rows = rows, epochSeconds = now - now % 30 + 9,
        groups = listOf("İş"))

    /** Draws a window's view tree directly: captureToImage waits for a frame callback that
     *  Robolectric never sends. */
    private fun draw(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun screen(): Bitmap = draw(compose.activity.window.decorView)

    private fun save(name: String, bitmap: Bitmap) {
        val dir = out ?: return
        dir.mkdirs()
        File(dir, "$prefix$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun list(onRendered: () -> Unit = {}) {
        compose.setContent {
            AuthenticatorTheme {
                AccountListScreen(
                    state = state(), policy = policy,
                    onAdd = {}, onExport = {}, onImport = {}, onAbout = {}, onMigrate = {},
                    onPdfExport = {}, onTransfer = {}, onLock = {}, onDelete = {},
                    onRename = { _, _, _ -> }, onSelectGroup = {}, onSetGroup = { _, _ -> },
                    onTogglePin = {}, onNextHotp = {},
                )
                onRendered()
            }
        }
    }

    private fun reveal(vararg titles: String) {
        for (t in titles) compose.onAllNodesWithText(t)[0].performClick()
        compose.waitForIdle()
    }

    @Test
    fun accounts() {
        assumeTrue(out != null)
        list()
        reveal("Deneme AŞ", "GitHub", "Microsoft 365")
        save("01-hesaplar", screen())
    }

    @Test
    fun signInApproval() {
        assumeTrue(out != null)
        val expires = OffsetDateTime.now().plusSeconds(94).toString()
        val prompt = ChallengePrompt(
            accountId = "1", issuer = "Deneme AŞ", name = "mehmet",
            challenge = PendingChallenge(id = "c1", rpId = "vpn.deneme.com.tr", nonce = "n",
                choices = listOf("17", "83", "46"), expiresAt = expires),
        )
        compose.setContent {
            AuthenticatorTheme {
                AccountListScreen(
                    state = state(), policy = policy,
                    onAdd = {}, onExport = {}, onImport = {}, onAbout = {}, onMigrate = {},
                    onPdfExport = {}, onTransfer = {}, onLock = {}, onDelete = {},
                    onRename = { _, _, _ -> }, onSelectGroup = {}, onSetGroup = { _, _ -> },
                    onTogglePin = {}, onNextHotp = {},
                )
                ChallengeDialog(prompt = prompt, onChoose = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        save("02-onay-arka", screen())
        save("02-onay-dialog", draw(ShadowDialog.getLatestDialog().window!!.decorView))
    }

    @Test
    fun enrolment() {
        assumeTrue(out != null)
        val ticket = "A".repeat(20) + "b7Kq2Xz9_Lm4Np8Rt1Vw5Yc"
        val uri = "otpauth://totp/Deneme%20A%C5%9E:mehmet?secret=JBSWY3DPEHPK3PXP&issuer=Deneme%20A%C5%9E" +
            "&veilo_gw=https%3A%2F%2Fmfa.deneme.com.tr&veilo_ticket=$ticket&veilo_keylevel=tee"
        val form = AddFormState().apply {
            val spec = OtpAuthUri.parse(uri)
            veilo = VeiloEnrolment.parse(uri)
            scanned = spec
            issuer = spec.issuer
            account = spec.accountName
        }
        compose.setContent {
            AuthenticatorTheme {
                AddAccountScreen(form = form, hardwareBoundOnly = true, onBack = {},
                    onScannerVisible = {}, onSave = {})
            }
        }
        compose.waitForIdle()
        save("03-kurumsal-kayit", screen())
    }

    @Test
    fun about() {
        assumeTrue(out != null)
        compose.setContent { AuthenticatorTheme { AboutScreen(policy = policy, onBack = {}) } }
        compose.waitForIdle()
        save("04-kurum-politikasi", screen())
    }
}

/** 7-inch tablet: 600×960 dp at hdpi = 900×1440 px. */
@Config(sdk = [35], qualifiers = "tr-rTR-w600dp-h960dp-port-hdpi")
class StoreScreenshotsTablet7 : StoreScreenshots() {
    override val prefix = "tablet7-"
}

/** 10-inch tablet: 800×1280 dp at xhdpi = 1600×2560 px. */
@Config(sdk = [35], qualifiers = "tr-rTR-w800dp-h1280dp-port-xhdpi")
class StoreScreenshotsTablet10 : StoreScreenshots() {
    override val prefix = "tablet10-"
}
