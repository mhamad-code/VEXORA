package com.agent.mhamed.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.mhamed.R
import com.agent.mhamed.core.ChatActivityPresentation
import com.agent.mhamed.core.ActivityGrouping
import com.agent.mhamed.ui.common.DotLoading

/**
 * One compact line for a run of consecutive activity entries, matching the
 * Freebuff layout: a small tinted glyph, a single gray summary, a chevron and
 * no card chrome. Tapping toggles the individual tool cards underneath.
 */
@Composable
internal fun ActivityGroupLine(
    group: ActivityGrouping.Group,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val runningKind = remember(group) {
        group.entries.lastOrNull { it is ChatActivityPresentation.Entry.Tool && it.result == null }?.let { (it as ChatActivityPresentation.Entry.Tool).call.name }?.let { com.agent.mhamed.core.ActivityGrouping.kindOfTool(it) }
            ?: if (group.kinds.contains(com.agent.mhamed.core.ActivityGrouping.Kind.THINKING)) com.agent.mhamed.core.ActivityGrouping.Kind.THINKING else group.kinds.firstOrNull()
    }
    val runningLabel = when (runningKind) {
        com.agent.mhamed.core.ActivityGrouping.Kind.THINKING -> stringResource(R.string.activity_running_thinking)
        com.agent.mhamed.core.ActivityGrouping.Kind.READ_FILES -> stringResource(R.string.activity_running_read)
        com.agent.mhamed.core.ActivityGrouping.Kind.EDIT_FILES -> stringResource(R.string.activity_running_edit)
        com.agent.mhamed.core.ActivityGrouping.Kind.BROWSER, com.agent.mhamed.core.ActivityGrouping.Kind.MCP -> stringResource(R.string.activity_running_browser)
        else -> stringResource(R.string.activity_running_command)
    }
    val summary = GroupSummary(group.kinds).replaceFirstChar { it.uppercase() }
    val a11y = buildString {
        append(summary)
        if (group.hasFailure) append(". ").append(stringResource(R.string.activity_group_failed))
        if (group.awaitingApproval) append(". ").append(stringResource(R.string.activity_group_waiting))
        append(". ").append(stringResource(if (expanded) R.string.activity_group_collapse else R.string.activity_group_expand))
    }
    val tint = when {
        group.hasFailure -> scheme.error
        group.awaitingApproval -> scheme.tertiary
        else -> GroupGlyphTint(group.kinds.firstOrNull() ?: ActivityGrouping.Kind.OTHER)
    }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 29.dp)
            .toggleable(expanded, role = Role.Button, onValueChange = { onToggle() })
            .semantics { contentDescription = a11y }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (group.running) {
            Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF5AA9F0),
                )
            }
        } else if (group.hasFailure) {
            Icon(Icons.Outlined.Warning, null, Modifier.size(19.dp), tint = Color(0xFFD28C4C))
        } else {
            val ic = when (group.kinds.firstOrNull() ?: ActivityGrouping.Kind.OTHER) {
                ActivityGrouping.Kind.THINKING -> Icons.Outlined.AutoAwesome
                ActivityGrouping.Kind.BROWSER, ActivityGrouping.Kind.MCP -> Icons.Outlined.Public
                ActivityGrouping.Kind.READ_FILES -> Icons.Outlined.Description
                ActivityGrouping.Kind.EDIT_FILES -> Icons.Outlined.Edit
                ActivityGrouping.Kind.COMMAND -> Icons.Outlined.Code
                else -> Icons.Outlined.Build
            }
            val color = when (group.kinds.firstOrNull() ?: ActivityGrouping.Kind.OTHER) {
                ActivityGrouping.Kind.THINKING, ActivityGrouping.Kind.BROWSER, ActivityGrouping.Kind.MCP -> Color(0xFF5AA9F0)
                ActivityGrouping.Kind.READ_FILES, ActivityGrouping.Kind.EDIT_FILES -> Color(0xFF4FC38A)
                ActivityGrouping.Kind.COMMAND -> Color(0xFFD28C4C)
                else -> scheme.onSurfaceVariant
            }
            Icon(ic, null, Modifier.size(19.dp), tint = color)
        }
        if (group.running) {
            Box(Modifier.weight(1f)) {
                ChatShimmerText(
                    text = runningLabel,
                    running = true,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.5.sp),
                )
            }
        } else {
            Text(
                summary,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.5.sp),
                color = Color(0xFF8E8E93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Localized summary built from the kinds present, in first-appearance order. */
@Composable
internal fun GroupSummary(kinds: List<ActivityGrouping.Kind>): String {
    val parts = ActivityGrouping.summaryParts(kinds)
    val pieces = mutableListOf<String>()
    for ((kind, count) in parts) pieces += GroupPhrase(kind, count)
    return pieces.joinToString(", ").replaceFirstChar { it.lowercase() }
}

@Composable
private fun GroupPhrase(kind: ActivityGrouping.Kind, count: Int): String = pluralStringResource(
    when (kind) {
        ActivityGrouping.Kind.THINKING -> R.plurals.activity_thinking
        ActivityGrouping.Kind.READ_FILES -> R.plurals.activity_read_files
        ActivityGrouping.Kind.EDIT_FILES -> R.plurals.activity_edit_files
        ActivityGrouping.Kind.COMMAND -> R.plurals.activity_commands
        ActivityGrouping.Kind.BROWSER -> R.plurals.activity_browser
        ActivityGrouping.Kind.GIT -> R.plurals.activity_git
        ActivityGrouping.Kind.SEARCH -> R.plurals.activity_search
        ActivityGrouping.Kind.MCP -> R.plurals.activity_mcp
        ActivityGrouping.Kind.SUBAGENT -> R.plurals.activity_subagent
        ActivityGrouping.Kind.OTHER -> R.plurals.activity_other
    },
    count,
)

@Composable
private fun GroupGlyphTint(kind: ActivityGrouping.Kind) = when (kind) {
    ActivityGrouping.Kind.EDIT_FILES -> MaterialTheme.colorScheme.primary
    ActivityGrouping.Kind.COMMAND -> MaterialTheme.colorScheme.secondary
    ActivityGrouping.Kind.BROWSER, ActivityGrouping.Kind.MCP -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun GroupGlyph(kind: ActivityGrouping.Kind): ImageVector = when (kind) {
    ActivityGrouping.Kind.READ_FILES -> Icons.Outlined.Description
    ActivityGrouping.Kind.EDIT_FILES -> Icons.Outlined.Edit
    ActivityGrouping.Kind.COMMAND -> Icons.Outlined.Code
    ActivityGrouping.Kind.BROWSER -> Icons.Outlined.Public
    ActivityGrouping.Kind.GIT -> Icons.Outlined.History
    ActivityGrouping.Kind.SEARCH -> Icons.Outlined.Search
    ActivityGrouping.Kind.MCP -> Icons.Outlined.Extension
    ActivityGrouping.Kind.SUBAGENT, ActivityGrouping.Kind.THINKING -> Icons.Outlined.AutoAwesome
    ActivityGrouping.Kind.OTHER -> Icons.Outlined.Build
}
