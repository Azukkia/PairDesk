package io.github.azukkia.pairdesk.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.data.RecentPartner
import io.github.azukkia.pairdesk.net.ConnectStep
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.net.SessionKind
import io.github.azukkia.pairdesk.ui.ConnectErrors
import io.github.azukkia.pairdesk.ui.ConnectUiState
import io.github.azukkia.pairdesk.ui.IdFormat
import io.github.azukkia.pairdesk.ui.components.BrandMark
import io.github.azukkia.pairdesk.ui.components.NetworkBadge
import io.github.azukkia.pairdesk.ui.components.NetworkTexts
import io.github.azukkia.pairdesk.ui.components.SectionCard
import io.github.azukkia.pairdesk.ui.components.StatusDot
import io.github.azukkia.pairdesk.ui.text
import io.github.azukkia.pairdesk.ui.theme.CredentialTextStyle
import io.github.azukkia.pairdesk.ui.theme.LocalPairDeskColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenSettings: () -> Unit,
    onSessionStarted: (OutgoingSession) -> Unit,
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val connectState by vm.connectState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(connectState) {
        if (connectState is ConnectUiState.Connected) vm.onConnected()?.let(onSessionStarted)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    NetworkBadge(status)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val recents by vm.recents.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "device") { ThisDeviceCard(vm, snackbar) }
                item(key = "connect") { ConnectCard(vm, recents) }
                item(key = "recents-title") {
                    Text(
                        stringResource(R.string.home_recents),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                if (recents.isEmpty()) {
                    item(key = "recents-empty") {
                        Text(
                            stringResource(R.string.home_no_recents),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                items(recents, key = { "recent:" + it.id }) { recent ->
                    RecentRow(recent, onClick = { vm.pickRecent(recent) }, onForget = { vm.forgetRecent(recent.id) })
                }
            }
        }
    }

    ConnectDialog(
        state = connectState,
        onCancel = vm.connectFlow::cancel,
        onRetry = { vm.connectFlow.retry() },
        onDismiss = vm.connectFlow::dismiss,
    )
}

@Composable
private fun ThisDeviceCard(vm: HomeViewModel, snackbar: SnackbarHostState) {
    val password by vm.password.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.home_id_copied)

    SectionCard(
        title = stringResource(R.string.home_this_device),
        icon = R.drawable.ic_shield,
        subtitle = stringResource(R.string.home_incoming_desc),
    ) {
        CredentialRow(label = stringResource(R.string.home_your_id), value = Protocol.formatId(vm.myId)) {
            IconButton(onClick = {
                copyToClipboard(context, "PairDesk ID", vm.myId)
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copiedText) }
            }) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.home_copy_id))
            }
        }
        CredentialRow(
            label = stringResource(R.string.home_password),
            value = if (shown) password else "•".repeat(password.length.coerceAtLeast(6)),
        ) {
            IconButton(onClick = { shown = !shown }) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (shown) R.string.home_hide_password else R.string.home_show_password),
                )
            }
            IconButton(onClick = vm::regeneratePassword) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.home_new_password))
            }
        }
        if (!settings.allowIncoming) {
            Banner(stringResource(R.string.home_incoming_disabled))
        } else if (settings.hasPermanentPassword) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = LocalPairDeskColors.current.success)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_unattended_on), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.state)
            Spacer(Modifier.width(8.dp))
            Text(
                NetworkTexts.detail(status).text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, actions: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = CredentialTextStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

@Composable
private fun Banner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ConnectCard(vm: HomeViewModel, recents: List<RecentPartner>) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val hasSaved = vm.hasSavedPassword(recents)

    SectionCard(
        title = stringResource(R.string.home_outgoing_title),
        icon = R.drawable.ic_desktop,
        subtitle = stringResource(R.string.home_outgoing_desc),
    ) {
        OutlinedTextField(
            value = vm.partnerId,
            onValueChange = vm::onPartnerIdChange,
            label = { Text(stringResource(R.string.home_partner_id)) },
            placeholder = { Text("123 456 789", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            visualTransformation = IdFormat.visualTransformation,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.partnerPassword,
            onValueChange = vm::onPartnerPasswordChange,
            label = { Text(stringResource(R.string.home_partner_password)) },
            singleLine = true,
            isError = vm.passwordError != null,
            supportingText = when {
                vm.passwordError != null -> ({ Text(vm.passwordError!!.text()) })
                hasSaved -> ({ Text(stringResource(R.string.home_password_saved_hint)) })
                else -> null
            },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = stringResource(if (showPassword) R.string.home_hide_password else R.string.home_show_password),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                if (vm.canConnect) {
                    focus.clearFocus()
                    vm.connect(SessionKind.CONTROL)
                }
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { vm.remember = !vm.remember },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = vm.remember, onCheckedChange = { vm.remember = it })
            Text(stringResource(R.string.home_remember), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    focus.clearFocus()
                    vm.connect(SessionKind.CONTROL)
                },
                enabled = vm.canConnect,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_desktop), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_control))
            }
            FilledTonalButton(
                onClick = {
                    focus.clearFocus()
                    vm.connect(SessionKind.CAMERA)
                },
                enabled = vm.canConnect,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_videocam), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_camera))
            }
        }
        Text(
            stringResource(R.string.home_camera_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentRow(recent: RecentPartner, onClick: () -> Unit, onForget: () -> Unit) {
    val formattedId = Protocol.formatId(recent.id)
    val title = recent.name.ifBlank { formattedId }
    val now = System.currentTimeMillis()
    val lastUsed = remember(recent.lastAt) {
        if (recent.lastAt > 0) DateUtils.getRelativeTimeSpanString(recent.lastAt, now, DateUtils.MINUTE_IN_MILLIS).toString() else ""
    }
    ListItem(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_desktop),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                if (recent.name.isNotBlank()) add(formattedId)
                if (lastUsed.isNotEmpty()) add(lastUsed)
                if (recent.hasPassword) add(stringResource(R.string.home_recent_saved))
            }
            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.home_forget_recent))
            }
        },
    )
}

/** Progress and errors of an outgoing connection (the desktop's connect dialog). */
@Composable
private fun ConnectDialog(
    state: ConnectUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is ConnectUiState.Running -> {
            val formatted = Protocol.formatId(state.peerId)
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        painterResource(if (state.kind == SessionKind.CAMERA) R.drawable.ic_videocam else R.drawable.ic_desktop),
                        contentDescription = null,
                    )
                },
                title = { Text(stringResource(R.string.connect_title, formatted)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(ConnectErrors.step(state.step ?: ConnectStep.SEARCHING, formatted).text())
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
        is ConnectUiState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(stringResource(R.string.connect_failed_title)) },
                text = { Text(state.message.text()) },
                confirmButton = {
                    if (state.canRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
            )
        }
        else -> Unit
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
