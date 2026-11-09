package com.selbir.authenticator.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.selbir.authenticator.R
import com.selbir.authenticator.export.QrCodes
import kotlinx.coroutines.delay

/** Pixels per QR image. Scaled by Compose to the available width; more pixels only cost memory. */
private const val QR_PIXELS = 720

/** How long the sender shows each frame. A camera at 30 fps gets many chances at each. */
private const val FRAME_INTERVAL_MS = 1500L

/**
 * Device-to-device transfer. One screen, three stages per role; see `core/Transfer`.
 *
 * @param onChildVisible told true while the scanner activity or the camera permission
 * dialog covers the app, so that trip is not mistaken for going to the background.
 * @param onReceiverCodeScanned sender: the text of the receiver's QR code.
 * @param onFrameScanned receiver: the text of one of the sender's QR codes.
 */
@Composable
fun TransferScreen(
    form: TransferFormState,
    exportableCount: Int,
    hardwareBoundCount: Int,
    onBack: () -> Unit,
    onChooseRole: (TransferRole) -> Unit,
    onChildVisible: (Boolean) -> Unit,
    onReceiverCodeScanned: (String) -> Unit,
    onStartScanning: () -> Unit,
    onFrameScanned: (String) -> Unit,
) {
    TransferScaffold(title = stringResource(R.string.transfer_title), onBack = onBack) {
        when (form.role) {
            null -> RoleChoice(exportableCount, hardwareBoundCount, onChooseRole)
            TransferRole.Send -> SendStage(form, exportableCount, hardwareBoundCount, onChildVisible, onReceiverCodeScanned, onBack)
            TransferRole.Receive -> ReceiveStage(form, onChildVisible, onStartScanning, onFrameScanned, onBack)
        }
    }
}

@Composable
private fun ColumnScope.RoleChoice(exportableCount: Int, hardwareBoundCount: Int, onChooseRole: (TransferRole) -> Unit) {
    Text(stringResource(R.string.transfer_body))

    Text(stringResource(R.string.transfer_send_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.transfer_send_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.export_included, exportableCount), style = MaterialTheme.typography.titleSmall)
    if (hardwareBoundCount > 0) {
        Text(
            text = stringResource(R.string.export_excluded, hardwareBoundCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = { onChooseRole(TransferRole.Send) },
        enabled = exportableCount > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.transfer_send_action))
    }

    Text(stringResource(R.string.transfer_receive_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.transfer_receive_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FilledTonalButton(onClick = { onChooseRole(TransferRole.Receive) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.transfer_receive_action))
    }
}

@Composable
private fun ColumnScope.SendStage(
    form: TransferFormState,
    exportableCount: Int,
    hardwareBoundCount: Int,
    onChildVisible: (Boolean) -> Unit,
    onReceiverCodeScanned: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scanPrompt = stringResource(R.string.transfer_scan_receiver_prompt)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        onChildVisible(false)
        result.contents?.let(onReceiverCodeScanned)
    }

    if (form.frames.isEmpty()) {
        Text(stringResource(R.string.transfer_send_step1))
        Text(stringResource(R.string.export_included, exportableCount), style = MaterialTheme.typography.titleSmall)
        if (hardwareBoundCount > 0) {
            Text(
                text = stringResource(R.string.export_excluded, hardwareBoundCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TransferStatus(form)
        Button(
            onClick = {
                form.errorRes = null
                onChildVisible(true)
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(scanPrompt)
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            enabled = !form.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.transfer_scan_receiver))
        }
    } else {
        var index by remember { mutableIntStateOf(0) }
        LaunchedEffect(form.frames) {
            index = 0
            while (true) {
                delay(FRAME_INTERVAL_MS)
                index = (index + 1) % form.frames.size
            }
        }
        Text(stringResource(R.string.transfer_send_step2, form.sentCount))
        QrImage(form.frames[index])
        Text(
            text = stringResource(R.string.transfer_frame_counter, index + 1, form.frames.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            text = stringResource(R.string.transfer_send_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transfer_finish))
        }
    }
}

@Composable
private fun ColumnScope.ReceiveStage(
    form: TransferFormState,
    onChildVisible: (Boolean) -> Unit,
    onStartScanning: () -> Unit,
    onFrameScanned: (String) -> Unit,
    onDone: () -> Unit,
) {
    val result = form.result
    when {
        result != null -> {
            Text(
                text = stringResource(R.string.transfer_done, result.added, result.alreadyPresent),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.transfer_finish))
            }
        }

        !form.scanning && form.assembled == null -> {
            Text(stringResource(R.string.transfer_receive_step1))
            val code = form.receiverCode
            if (code == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                QrImage(code)
            }
            TransferStatus(form)
            Button(onClick = onStartScanning, enabled = code != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.transfer_receive_next))
            }
        }

        form.scanning -> {
            Text(stringResource(R.string.transfer_receive_step2))
            FrameScanner(onChildVisible = onChildVisible, onFrameScanned = onFrameScanned)
            if (form.framesTotal > 0) {
                LinearProgressIndicator(
                    progress = { form.framesReceived.toFloat() / form.framesTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(R.string.transfer_progress, form.framesReceived, form.framesTotal),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            TransferStatus(form)
        }

        else -> {
            // All frames are in; the import (and possibly an authentication prompt) is running.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall)
            TransferStatus(form)
        }
    }
}

/** Live camera preview that reports every QR code it sees, for as long as it is on screen. */
@Composable
private fun FrameScanner(onChildVisible: (Boolean) -> Unit, onFrameScanned: (String) -> Unit) {
    val context = LocalContext.current
    val latestOnFrame by rememberUpdatedState(onFrameScanned)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onChildVisible(false)
        granted = ok
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) {
            onChildVisible(true)
            request.launch(Manifest.permission.CAMERA)
        }
    }

    if (!granted) {
        if (asked) {
            Text(
                text = stringResource(R.string.transfer_camera_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    val view = remember {
        DecoratedBarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            setStatusText("")
            decodeContinuous(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) {
                    result.text?.let { latestOnFrame(it) }
                }
            })
        }
    }
    DisposableEffect(view) {
        view.resume()
        onDispose { view.pause() }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
    )
}

@Composable
private fun ColumnScope.QrImage(text: String) {
    val bitmap = remember(text) { QrCodes.bitmap(text, QR_PIXELS).asImageBitmap() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(Color.White)
            .padding(12.dp),
    ) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TransferStatus(form: TransferFormState) {
    form.errorRes?.let { error ->
        Text(
            text = stringResource(error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (form.busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
