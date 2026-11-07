package io.github.azukkia.pairdesk.ui.session

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.ui.components.FullEditionNotice
import io.github.azukkia.pairdesk.ui.components.phoneControlAvailable
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.host.HostPhase
import io.github.azukkia.pairdesk.host.HostSession
import io.github.azukkia.pairdesk.host.HostSessions
import io.github.azukkia.pairdesk.host.HostState
import io.github.azukkia.pairdesk.host.PairDeskAccessibilityService
import io.github.azukkia.pairdesk.net.IncomingState
import io.github.azukkia.pairdesk.ui.theme.LocalPairDeskColors
import io.github.azukkia.pairdesk.ui.viewer.AskNotificationPermissionOnce
import io.github.azukkia.pairdesk.viewer.ChatMessage
import io.github.azukkia.pairdesk.viewer.EndReason
import kotlinx.coroutines.delay

/**
 * A computer viewing (and maybe controlling) this phone: who, the connection,
 * why control is not possible (accessibility service, setting) with the way
 * to enable it, the chat, and the End button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSessionScreen(graph: AppGraph, sid: String, onExit: () -> Unit) {
    val current by graph.hosts.current.collectAsStateWithLifecycle()
    val session = current?.takeIf { it.sid == sid }
    val exit = {
        graph.hosts.close(sid)
        onExit()
    }
    if (session == null) {
        MissingSession(onExit)
        return
    }
    val state by session.core.state.collectAsStateWithLifecycle()
    var confirmEnd by rememberSaveable(sid) { mutableStateOf(false) }
    AskNotificationPermissionOnce(graph)
    BackHandler { if (state.ended) exit() else confirmEnd = true }
    DisposableEffect(session) {
        session.setChatOpen(true)
        onDispose { session.setChatOpen(false) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.host_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { if (state.ended) exit() else confirmEnd = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (!state.ended) {
                        IconButton(onClick = { confirmEnd = true }) {
                            Icon(
                                painterResource(R.drawable.ic_call_end),
                                contentDescription = stringResource(R.string.session_end),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            if (state.ended) {
                HostEnded(state, session, onClose = exit)
            } else {
                HostLive(graph, session, state)
            }
        }
    }

    if (confirmEnd && !state.ended) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.session_end_confirm_title)) },
            text = { Text(stringResource(R.string.session_end_confirm_body, session.peer.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnd = false
                        session.stop()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.session_end)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun HostLive(graph: AppGraph, session: HostSession, state: HostState) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val service by PairDeskAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val name = session.peer.label
    var draft by rememberSaveable(session.sid) { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "who") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (state.control) R.string.host_controlling else R.string.host_viewing, name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val status = when (state.phase) {
                    HostPhase.LIVE -> stringResource(R.string.host_live) + when (state.relayed) {
                        true -> " · " + stringResource(R.string.viewer_relayed)
                        false -> " · " + stringResource(R.string.viewer_direct)
                        null -> ""
                    }
                    HostPhase.RECONNECTING -> stringResource(R.string.session_reconnecting)
                    else -> stringResource(R.string.session_connecting)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.phase != HostPhase.LIVE) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.phase == HostPhase.LIVE) LocalPairDeskColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.host_keep_on), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!state.control) {
            item(key = "control") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.host_view_only), style = MaterialTheme.typography.titleSmall)
                    }
                    if (!phoneControlAvailable) {
                        Text(stringResource(R.string.host_control_needs_full, name), style = MaterialTheme.typography.bodySmall)
                        FullEditionNotice()
                    } else if (!settings.allowControl) {
                        Text(stringResource(R.string.host_control_disabled, name), style = MaterialTheme.typography.bodySmall)
                    } else if (service == null) {
                        Text(stringResource(R.string.host_control_needs_service, name), style = MaterialTheme.typography.bodySmall)
                        // One under the other: side by side, the second one had no width
                        // left on a phone and its text stretched the card down the screen.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { open(context, PairDeskAccessibilityService.settingsIntent()) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(painterResource(R.drawable.ic_accessibility), contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_open_accessibility))
                            }
                            OutlinedButton(onClick = { open(context, PairDeskAccessibilityService.appDetailsIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.host_app_info))
                            }
                        }
                    }
                }
            }
        }
        item(key = "chat-title") {
            Text(stringResource(R.string.viewer_chat), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.chat.isEmpty()) {
            item(key = "chat-empty") {
                Text(stringResource(R.string.host_chat_hint, name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.chat, key = { "chat:" + it.id }) { message -> ChatLine(message, name) }
        item(key = "chat-input") {
            val send = {
                if (session.sendChat(draft)) draft = ""
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(2_000) },
                placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                enabled = state.phase == HostPhase.LIVE,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    IconButton(onClick = send, enabled = draft.isNotBlank() && state.phase == HostPhase.LIVE) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChatLine(message: ChatMessage, peer: String) {
    val mine = message.mine
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (mine) stringResource(R.string.chat_you) else peer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HostEnded(state: HostState, session: HostSession, onClose: () -> Unit) {
    val message = when {
        session.core.failure == "capture" -> R.string.host_capture_failed
        state.endReason == EndReason.PEER -> R.string.session_ended_by_peer
        state.endReason == EndReason.FAILED -> R.string.host_failed
        else -> R.string.session_ended
    }
    Column(
        Modifier.widthIn(max = 420.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_shield), contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(session.peer.label, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onClose) { Text(stringResource(R.string.session_back_home)) }
    }
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Shown over any screen while a computer asks to connect to this phone.
 * Accept asks Android for the screen capture (system dialog), then starts
 * the session; refusing the capture declines the request.
 */
@Composable
fun IncomingRequestDialog(graph: AppGraph, onAccepted: (String) -> Unit) {
    val incoming by graph.network.incoming.collectAsStateWithLifecycle()
    val acceptRequest by graph.hosts.acceptRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The request whose capture consent is being asked (survives the system dialog).
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sid = asking ?: return@rememberLauncherForActivityResult
        asking = null
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            if (graph.hosts.begin(sid, data)) onAccepted(sid)
        } else {
            graph.network.declineIncoming(sid, "declined")
        }
    }
    val request = incoming?.takeIf { it.state == IncomingState.PENDING }
    fun accept(sid: String) {
        if (asking != null) return
        graph.network.holdIncoming(sid, HostSessions.CONSENT_HOLD_MS)
        asking = sid
        try {
            consent.launch(HostSessions.captureIntent(context))
        } catch (_: ActivityNotFoundException) {
            asking = null
            graph.network.declineIncoming(sid, "unsupported")
        }
    }
    // Accept button of the notification.
    LaunchedEffect(acceptRequest, request?.sid) {
        val sid = acceptRequest ?: return@LaunchedEffect
        graph.hosts.consumeAcceptRequest()
        if (request?.sid == sid) accept(sid)
    }
    if (request == null) return
    val name = request.peerName.ifBlank { stringResource(R.string.incoming_unknown_name) }
    val remaining by produceState(initialValue = secondsLeft(request.deadline), request.sid, request.deadline) {
        while (true) {
            value = secondsLeft(request.deadline)
            delay(1_000)
        }
    }
    val waiting = asking == request.sid
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(painterResource(R.drawable.ic_shield), contentDescription = null) },
        title = { Text(stringResource(R.string.incoming_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.incoming_body, name, Protocol.formatId(request.peerId)))
                Text(
                    stringResource(if (request.credential == "perm") R.string.incoming_credential_perm else R.string.incoming_credential_temp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(if (waiting) R.string.incoming_waiting_consent else R.string.incoming_consent_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remaining != null) {
                    Text(
                        stringResource(R.string.incoming_expires, remaining!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { accept(request.sid) }, enabled = !waiting) { Text(stringResource(R.string.incoming_accept)) }
        },
        dismissButton = {
            OutlinedButton(onClick = { graph.network.declineIncoming(request.sid) }, enabled = !waiting) {
                Text(stringResource(R.string.incoming_decline))
            }
        },
    )
}

private fun secondsLeft(deadline: Long?): Int? =
    deadline?.let { ((it - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0) }
