package com.androidharness.app.ui.cloud

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.cloud.CloudEvent
import com.androidharness.app.cloud.CloudModelOption
import com.androidharness.app.cloud.CloudPermissionMode
import com.androidharness.app.cloud.CloudPrefs
import com.androidharness.app.cloud.CloudRunState
import com.androidharness.app.cloud.CloudSetupProgress
import com.androidharness.app.cloud.CloudSetupStep
import com.androidharness.app.cloud.CloudStepStatus
import com.androidharness.app.cloud.CodespacePhase
import com.androidharness.app.cloud.RelayConnection
import com.androidharness.app.ui.chat.MarkdownText
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.OutlinedButton
import com.androidharness.app.ui.common.TextButton
import kotlinx.coroutines.launch

/**
 * Claude Code on the user's own Codespace. The app authenticates, provisions
 * and renders; the model work happens remotely with the user's own providers.
 */
@Composable
fun CloudClaudeScreen(container: AppContainer, onBack: () -> Unit) {
    val cloud = container.cloudClaude
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by cloud.status.collectAsStateWithLifecycle()
    val auth by cloud.auth.state.collectAsStateWithLifecycle()
    val prefs by cloud.prefs.collectAsStateWithLifecycle(initialValue = CloudPrefs())
    val progress by cloud.progress.collectAsStateWithLifecycle()
    val events by cloud.events.collectAsStateWithLifecycle()
    val relay by cloud.relayStatus.collectAsStateWithLifecycle()
    val runState by cloud.runState.collectAsStateWithLifecycle()
    val options by cloud.modelOptions.collectAsStateWithLifecycle()
    val notice by cloud.notice.collectAsStateWithLifecycle()

    var showConsent by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBypassWarning by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(status.error, notice) {
        (status.error ?: notice)?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppHeader(
                title = stringResource(R.string.cloud_title),
                subtitle = stringResource(R.string.cloud_subtitle),
                onBack = onBack,
                actions = {
                    if (status.signedIn) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.cloud_settings))
                        }
                        IconButton(onClick = { scope.launch { cloud.signOut() } }) {
                            Icon(Icons.Outlined.Logout, stringResource(R.string.cloud_sign_out))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !status.signedIn -> LoginPane(
                    auth = auth,
                    onSignIn = { cloud.signIn() },
                    onCancel = { cloud.cancelSignIn() },
                    onOpenGithub = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device"))) },
                    onPat = { token -> scope.launch { cloud.connectPat(token) } },
                    busy = status.busy,
                )

                status.codespace == null -> SetupPane(
                    prefs = prefs,
                    progress = progress,
                    busy = status.busy,
                    onPrefs = { transform -> scope.launch { cloud.settings.update(transform) } },
                    onSetup = { showConsent = true },
                    onRetry = { step -> scope.launch { cloud.provision(from = step) } },
                    onRefresh = { scope.launch { cloud.refreshStatus() } },
                )

                else -> {
                    CloudStatusBar(
                        status = status,
                        relay = relay,
                        runState = runState,
                        onStart = { scope.launch { cloud.startCloud() } },
                        onStop = { scope.launch { cloud.stopCloud() } },
                        onDelete = { showDelete = true },
                        onRefresh = { scope.launch { cloud.refreshStatus() } },
                    )
                    if (events.isEmpty()) {
                        EmptyChatHint(modifier = Modifier.weight(1f))
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(events.size) {
                            if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(events) { event ->
                                CloudEventCard(event, onPermission = { id, allow ->
                                    cloud.answerPermission(id, allow)
                                })
                            }
                        }
                    }
                    Composer(
                        draft = draft,
                        onDraft = { draft = it },
                        running = runState == CloudRunState.RUNNING,
                        connected = relay == RelayConnection.OPEN,
                        onSend = {
                            val text = draft
                            draft = ""
                            scope.launch { cloud.send(text) }
                        },
                        onInterrupt = { cloud.interrupt() },
                        onNewChat = { cloud.clearTranscript() },
                    )
                }
            }
        }
    }

    if (showConsent) ConsentDialog(
        repository = "${status.login}/${prefs.repositoryName}",
        onDismiss = { showConsent = false },
        onConfirm = {
            showConsent = false
            scope.launch { cloud.provision() }
        },
    )
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text(stringResource(R.string.cloud_delete)) },
        text = { Text(stringResource(R.string.cloud_delete_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                showDelete = false
                scope.launch { cloud.deleteCloud() }
            }) { Text(stringResource(R.string.cloud_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cloud_cancel)) } },
    )
    if (showSettings) CloudSettingsDialog(
        prefs = prefs,
        options = options,
        onPrefs = { transform -> scope.launch { cloud.settings.update(transform) } },
        onBypass = { showBypassWarning = true },
        onTestModel = { scope.launch { snackbar.showSnackbar(cloud.probeModel()) } },
        onDismiss = { showSettings = false },
    )
    if (showBypassWarning) AlertDialog(
        onDismissRequest = { showBypassWarning = false },
        title = { Text(stringResource(R.string.cloud_permission_bypass)) },
        text = { Text(stringResource(R.string.cloud_bypass_warning)) },
        confirmButton = {
            TextButton(onClick = {
                showBypassWarning = false
                scope.launch { cloud.settings.update { it.copy(permission = CloudPermissionMode.BYPASS) } }
            }) { Text(stringResource(R.string.cloud_allow)) }
        },
        dismissButton = { TextButton(onClick = { showBypassWarning = false }) { Text(stringResource(R.string.cloud_cancel)) } },
    )
}

// ------------------------------------------------------------------- login

@Composable
private fun LoginPane(
    auth: com.androidharness.app.github.GitHubConnectionState,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenGithub: () -> Unit,
    onPat: (String) -> Unit,
    busy: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var patOpen by remember { mutableStateOf(false) }
    var pat by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Cloud, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.cloud_sign_in_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.cloud_sign_in_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.cloud_data_notice), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        auth.code?.let { code ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.cloud_device_code_instruction), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(code.userCode, style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            clipboard.setText(AnnotatedString(code.userCode))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cloud_copy_code))
                        }
                        FilledTonalButton(onClick = onOpenGithub) { Text(stringResource(R.string.cloud_open_github)) }
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cloud_cancel)) }
                }
            }
        }

        if (auth.code == null) {
            Button(onClick = onSignIn, enabled = !busy && !auth.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cloud_sign_in_button))
            }
        }
        auth.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        TextButton(onClick = { patOpen = !patOpen }) { Text(stringResource(R.string.cloud_pat_toggle)) }
        if (patOpen) {
            OutlinedTextField(value = pat, onValueChange = { pat = it },
                label = { Text(stringResource(R.string.cloud_pat_label)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { onPat(pat); pat = "" }, enabled = pat.isNotBlank() && !busy) {
                Text(stringResource(R.string.cloud_pat_connect))
            }
        }
    }
}

// ------------------------------------------------------------------- setup

@Composable
private fun SetupPane(
    prefs: CloudPrefs,
    progress: CloudSetupProgress?,
    busy: Boolean,
    onPrefs: ((CloudPrefs) -> CloudPrefs) -> Unit,
    onSetup: () -> Unit,
    onRetry: (CloudSetupStep) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.cloud_setup_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.cloud_setup_body, prefs.repositoryName), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = prefs.repositoryName,
            onValueChange = { name -> onPrefs { it.copy(repositoryName = name.filter { c -> c.isLetterOrDigit() || c in "._-" }) } },
            label = { Text(stringResource(R.string.cloud_repo_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = prefs.idleTimeoutMinutes.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { minutes -> onPrefs { it.copy(idleTimeoutMinutes = minutes.coerceIn(5, 240)) } } },
                label = { Text(stringResource(R.string.cloud_idle_timeout)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = prefs.machine,
                onValueChange = { machine -> onPrefs { it.copy(machine = machine) } },
                label = { Text(stringResource(R.string.cloud_machine)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (progress != null) Stepper(progress, onRetry)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSetup, enabled = !busy, modifier = Modifier.weight(1f)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.cloud_setup_button))
            }
            OutlinedButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Stepper(progress: CloudSetupProgress, onRetry: (CloudSetupStep) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudSetupStep.entries.filter { it != CloudSetupStep.READY }.forEach { step ->
                val status = progress.statuses[step] ?: CloudStepStatus.PENDING
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (status) {
                        CloudStepStatus.DONE -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        CloudStepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        CloudStepStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                        else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stepLabel(step), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (status == CloudStepStatus.FAILED) {
                        TextButton(onClick = { onRetry(step) }) { Text(stringResource(R.string.cloud_retry_step)) }
                    }
                }
            }
            progress.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            progress.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun stepLabel(step: CloudSetupStep): String = stringResource(
    when (step) {
        CloudSetupStep.REPOSITORY -> R.string.cloud_step_repository
        CloudSetupStep.FILES -> R.string.cloud_step_files
        CloudSetupStep.CODESPACE -> R.string.cloud_step_codespace
        CloudSetupStep.STARTING -> R.string.cloud_step_starting
        CloudSetupStep.RELAY -> R.string.cloud_step_relay
        CloudSetupStep.READY -> R.string.cloud_step_ready
    },
)

@Composable
private fun ConsentDialog(repository: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.cloud_setup_body, repository))
                Text(stringResource(R.string.cloud_data_notice), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.cloud_setup_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cloud_cancel)) } },
    )
}

// ------------------------------------------------------------------ status

@Composable
private fun CloudStatusBar(
    status: com.androidharness.app.cloud.CloudStatus,
    relay: RelayConnection,
    runState: CloudRunState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val phase = status.codespace?.phase ?: CodespacePhase.NONE
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(status.codespace?.name.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(
                            when {
                                relay == RelayConnection.OPEN -> R.string.cloud_relay_connected
                                relay == RelayConnection.CONNECTING || relay == RelayConnection.WAITING -> R.string.cloud_relay_connecting
                                status.phase == CodespacePhase.STOPPED -> R.string.cloud_status_stopped
                                else -> R.string.cloud_relay_offline
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (phase == CodespacePhase.READY) {
                    IconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, stringResource(R.string.cloud_stop)) }
                } else {
                    IconButton(onClick = onStart) { Icon(Icons.Outlined.PlayArrow, stringResource(R.string.cloud_start)) }
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.cloud_refresh)) }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.cloud_delete),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            if (runState == CloudRunState.RUNNING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

// ------------------------------------------------------------------- cards

@Composable
private fun CloudEventCard(event: CloudEvent, onPermission: (String, Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    when (event) {
        is CloudEvent.UserMessage -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHighest,
                modifier = Modifier.widthIn(max = 520.dp)) {
                Text(event.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        is CloudEvent.Text -> MarkdownText(event.text)
        is CloudEvent.Thinking -> CollapsibleCard(
            label = stringResource(R.string.cloud_thinking), body = event.text, mono = false,
        )
        is CloudEvent.ToolCall -> CollapsibleCard(
            label = "${stringResource(R.string.cloud_tool)} · ${event.name}", body = event.input, mono = true,
            icon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp)) },
        )
        is CloudEvent.ToolResult -> CollapsibleCard(
            label = stringResource(R.string.cloud_tool_result), body = event.content, mono = true,
            error = event.error,
        )
        is CloudEvent.FileChange -> Row(
            Modifier.fillMaxWidth().background(scheme.surfaceContainerLow, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = scheme.primary)
            Text(event.path, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        is CloudEvent.Usage -> Text(
            buildString {
                append("${event.inputTokens} → ${event.outputTokens}")
                event.costUsd?.let { append(" · $${"%.4f".format(it)}") }
            },
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
        )
        is CloudEvent.Error -> Surface(shape = RoundedCornerShape(14.dp), color = scheme.errorContainer,
            modifier = Modifier.fillMaxWidth()) {
            Text(event.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer)
        }
        is CloudEvent.Permission -> PermissionCard(event, onPermission)
        is CloudEvent.Done -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is CloudEvent.Raw, is CloudEvent.State, is CloudEvent.Session -> Unit
    }
}

@Composable
private fun PermissionCard(event: CloudEvent.Permission, onPermission: (String, Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cloud_permission_request, event.tool),
                style = MaterialTheme.typography.titleSmall)
            Text(event.input, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                maxLines = 8, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPermission(event.requestId, true) }) { Text(stringResource(R.string.cloud_allow)) }
                OutlinedButton(onClick = { onPermission(event.requestId, false) }) { Text(stringResource(R.string.cloud_deny)) }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(
    label: String,
    body: String,
    mono: Boolean,
    error: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (error) scheme.errorContainer else scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                icon?.invoke()
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (mono) FontFamily.Monospace else null,
                maxLines = if (open) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// --------------------------------------------------------------- composer

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Cloud, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.cloud_empty_hint), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    connected: Boolean,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onNewChat: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cloud_data_notice), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text(stringResource(R.string.cloud_composer_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                )
                if (running) {
                    FilledTonalButton(onClick = onInterrupt) {
                        Icon(Icons.Outlined.Stop, stringResource(R.string.cloud_interrupt), Modifier.size(18.dp))
                    }
                } else {
                    Button(onClick = onSend, enabled = draft.isNotBlank() && connected) {
                        Text(stringResource(R.string.cloud_send))
                    }
                }
            }
            TextButton(onClick = onNewChat) { Text(stringResource(R.string.cloud_new_chat)) }
        }
    }
}

// --------------------------------------------------------------- settings

@Composable
private fun CloudSettingsDialog(
    prefs: CloudPrefs,
    options: List<CloudModelOption>,
    onPrefs: ((CloudPrefs) -> CloudPrefs) -> Unit,
    onBypass: () -> Unit,
    onTestModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picker by remember { mutableStateOf<Boolean?>(null) }
    val main = options.firstOrNull { it.key == prefs.mainModel }
    val fast = options.firstOrNull { it.key == prefs.fastModel }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(stringResource(R.string.cloud_model_main), main?.label ?: stringResource(R.string.cloud_no_models)) {
                    picker = true
                }
                SettingRow(stringResource(R.string.cloud_model_fast), fast?.label ?: stringResource(R.string.cloud_no_models)) {
                    picker = false
                }
                Text(stringResource(R.string.cloud_permission), style = MaterialTheme.typography.labelLarge)
                CloudPermissionMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (mode == CloudPermissionMode.BYPASS) onBypass()
                            else onPrefs { it.copy(permission = mode) }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = prefs.permission == mode, onClick = {
                            if (mode == CloudPermissionMode.BYPASS) onBypass()
                            else onPrefs { it.copy(permission = mode) }
                        })
                        Text(
                            stringResource(
                                when (mode) {
                                    CloudPermissionMode.ASK -> R.string.cloud_permission_ask
                                    CloudPermissionMode.ACCEPT_EDITS -> R.string.cloud_permission_accept
                                    CloudPermissionMode.BYPASS -> R.string.cloud_permission_bypass
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cloud_auto_stop), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.autoStop, onCheckedChange = { value ->
                        onPrefs { it.copy(autoStop = value) }
                    })
                }
                FilledTonalButton(onClick = onTestModel) { Text(stringResource(R.string.cloud_test_model)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cloud_cancel)) } },
    )
    picker?.let { chooseMain ->
        AlertDialog(
            onDismissRequest = { picker = null },
            title = {
                Text(stringResource(if (chooseMain) R.string.cloud_model_main else R.string.cloud_model_fast))
            },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options) { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onPrefs {
                                    if (chooseMain) it.copy(mainModel = option.key) else it.copy(fastModel = option.key)
                                }
                                picker = null
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (if (chooseMain) prefs.mainModel else prefs.fastModel) == option.key,
                                onClick = {
                                    onPrefs {
                                        if (chooseMain) it.copy(mainModel = option.key) else it.copy(fastModel = option.key)
                                    }
                                    picker = null
                                },
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.cloud_cancel)) } },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
