package com.awd.teledrive.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.awd.teledrive.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            title = { Text(stringResource(R.string.whats_new)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val changelogs = listOf(
                        R.string.changelog_v120,
                        R.string.changelog_v110,
                        R.string.changelog_v100
                    )
                    
                    changelogs.forEach { resId ->
                        Text(
                            text = stringResource(resId),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (resId != changelogs.last()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChangelogDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(R.string.license)) },
            text = {
                Text(
                    text = stringResource(R.string.mit_license_text),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.about_app)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsCategory(title = stringResource(R.string.version))
            SettingsClickableRow(
                icon = Icons.Default.Info,
                title = "Awd TeleDrive",
                subtitle = "v${viewModel.versionName} (${viewModel.versionCode}) • ${stringResource(R.string.version_history)}",
                onClick = { showChangelogDialog = true }
            )
            
            SettingsClickableRow(
                icon = Icons.Default.Refresh,
                title = stringResource(R.string.check_for_updates),
                onClick = viewModel::checkForUpdates
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.links))
            
            SettingsClickableRow(
                icon = Icons.Default.Public,
                title = stringResource(R.string.official_website),
                subtitle = "teledrive.biz.id",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://teledrive.biz.id".toUri())
                    context.startActivity(intent)
                }
            )

            SettingsClickableRow(
                icon = Icons.Default.Code,
                title = stringResource(R.string.github_and_issues),
                subtitle = "putuwahyu29/awd-teledrive-android",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/putuwahyu29/awd-teledrive-android".toUri())
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SettingsCategory(title = stringResource(R.string.legal))

            SettingsClickableRow(
                icon = Icons.Default.Person,
                title = stringResource(R.string.creator),
                subtitle = "I Putu Agus Wahyu Dupayana",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.linkedin.com/in/aguswahyu/".toUri())
                    context.startActivity(intent)
                }
            )

            SettingsClickableRow(
                icon = Icons.Default.Gavel,
                title = stringResource(R.string.license),
                subtitle = stringResource(R.string.mit_license),
                onClick = { showLicenseDialog = true }
            )
        }
    }
}
