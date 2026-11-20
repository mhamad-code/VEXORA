package com.agent.mhamed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agent.mhamed.R
import com.agent.mhamed.data.AppSettings
import com.agent.mhamed.data.ActivityDisplay
import com.agent.mhamed.data.CodeReviewLevel

/** Master switch for the Android device-control tools (Feature A). */
@Composable
internal fun DeviceControlSettingsCard(
    settings: AppSettings,
    onEnabled: (Boolean) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        SettingsAnchor(R.string.settings_device_control_enabled) {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(settings.deviceControlEnabled, role = Role.Switch, onValueChange = onEnabled)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_device_control_enabled), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_device_control_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.deviceControlEnabled, onCheckedChange = null)
            }
        }
    }
    if (!settings.deviceControlEnabled) {
        Text(
            stringResource(R.string.settings_device_control_off_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Review-before-commit level: Off, Secrets only, Full (default). */
@Composable
internal fun CodeReviewSettingsCard(
    settings: AppSettings,
    onLevel: (CodeReviewLevel) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsAnchor(R.string.settings_code_review_before_commit) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_code_review_before_commit), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_code_review_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CodeReviewLevel.entries.forEach { level ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(settings.codeReviewBeforeCommit == level, role = Role.RadioButton) { onLevel(level) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = settings.codeReviewBeforeCommit == level, onClick = { onLevel(level) })
                    Text(levelLabel(level), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun levelLabel(level: CodeReviewLevel): String = when (level) {
    CodeReviewLevel.OFF -> stringResource(R.string.settings_code_review_off)
    CodeReviewLevel.SECRETS_ONLY -> stringResource(R.string.settings_code_review_secrets_only)
    CodeReviewLevel.FULL -> stringResource(R.string.settings_code_review_full)
}

/** Activity display: grouped lines (default) or the classic cards. */
@Composable
internal fun ActivityDisplaySettingsCard(
    settings: AppSettings,
    onSelect: (ActivityDisplay) -> Unit,
) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_activity_display), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_activity_display_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActivityDisplay.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(settings.activityDisplay == option, role = Role.RadioButton) { onSelect(option) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = settings.activityDisplay == option, onClick = { onSelect(option) })
                    Text(
                        stringResource(if (option == ActivityDisplay.NEW) R.string.activity_display_new else R.string.activity_display_classic),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
