package com.selbir.authenticator.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import com.selbir.authenticator.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    var selectedProject by remember { mutableStateOf<VeiloProject?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // App info
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Security features
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_security_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.about_security_body),
                style = MaterialTheme.typography.bodySmall,
            )

            // Other projects
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_other_projects),
                style = MaterialTheme.typography.titleMedium,
            )
            for (project in veiloProjects) {
                ProjectRow(
                    name = stringResource(project.nameRes),
                    tagline = stringResource(project.taglineRes),
                    onClick = { selectedProject = project },
                )
            }

            // Developer
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_developer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    // Project info dialog (Tecvid Okulu pattern)
    selectedProject?.let { project ->
        ProjectInfoDialog(
            name = stringResource(project.nameRes),
            info = stringResource(project.infoRes),
            url = project.url,
            onDismiss = { selectedProject = null },
            onOpenUrl = { url ->
                selectedProject = null
                openUrl(context, url)
            },
        )
    }
}

@Composable
private fun ProjectRow(name: String, tagline: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectInfoDialog(
    name: String,
    info: String,
    url: String,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = { Text(info) },
        confirmButton = {
            TextButton(onClick = { onOpenUrl(url) }) {
                Text(stringResource(R.string.about_open_website))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (_: PackageManager.NameNotFoundException) {
    ""
}

private class VeiloProject(
    val nameRes: Int,
    val taglineRes: Int,
    val infoRes: Int,
    val url: String,
)

private val veiloProjects = listOf(
    VeiloProject(
        nameRes = R.string.project_veilo_name,
        taglineRes = R.string.project_veilo_tagline,
        infoRes = R.string.project_veilo_info,
        url = "https://veilo.tr/",
    ),
    VeiloProject(
        nameRes = R.string.project_axon_name,
        taglineRes = R.string.project_axon_tagline,
        infoRes = R.string.project_axon_info,
        url = "https://axon.veilo.tr/",
    ),
    VeiloProject(
        nameRes = R.string.project_radar_name,
        taglineRes = R.string.project_radar_tagline,
        infoRes = R.string.project_radar_info,
        url = "https://radar.veilo.tr/",
    ),
    VeiloProject(
        nameRes = R.string.project_voltbox_name,
        taglineRes = R.string.project_voltbox_tagline,
        infoRes = R.string.project_voltbox_info,
        url = "https://volt.veilo.tr/",
    ),
)
