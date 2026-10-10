package io.github.azukkia.pairdesk.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.net.IncomingState
import io.github.azukkia.pairdesk.net.SessionEnd
import io.github.azukkia.pairdesk.rtc.RtcEvent
import io.github.azukkia.pairdesk.rtc.RtcRole
import io.github.azukkia.pairdesk.rtc.RtcSession
import io.github.azukkia.pairdesk.rtc.RtcState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Ends of a session as the session screens show them. */
private val SessionEnd.byPeerMessage: Int
    get() = if (byPeer) R.string.session_ended_by_peer else R.string.session_ended

/** The end of session [sid] (null while it runs); already ended when the screen opens late. */
@Composable
private fun rememberSessionEnd(graph: AppGraph, sid: String): State<SessionEnd?> =
    produceState<SessionEnd?>(initialValue = null, sid) {
        if (!graph.network.isSessionActive(sid)) {
            value = SessionEnd(sid, "closed", byPeer = false)
            return@produceState
        }
        value = graph.network.sessionEnded.filter { it.sid == sid }.first()
    }

/**
 * Controlling a computer. For now: the remote screen and the connection
 * state; input and the toolbar come with the full viewer.
 */
@Composable
fun ViewerScreen(graph: AppGraph, sid: String, onExit: () -> Unit) {
    val outgoing = remember(sid) { graph.network.outgoingSession(sid) }
    val peerName = outgoing?.peerName?.ifBlank { null } ?: outgoing?.peerId?.let(Protocol::formatId) ?: ""
    val ended by rememberSessionEnd(graph, sid)
    val rtc = remember(sid) { graph.rtc.open(sid, RtcRole.CONTROLLER) }

    if (rtc != null) {
        LaunchedEffect(rtc) {
            rtc.events.collect { event ->
                // The desktop host waits for `hello` to pick the quality (viewer.js).
                if (event is RtcEvent.ControlOpen) rtc.sendControl(obj("type" to "hello", "quality" to "balanced", "clipboard" to false))
            }
        }
    }

    SessionScaffold(
        title = stringResource(R.string.session_viewer_title, peerName),
        peerName = peerName,
        ended = ended,
        onEnd = {
            rtc?.sendControl(obj("type" to "bye"))
            graph.network.endOutgoing(sid, "closed")
            onExit()
        },
        onExit = onExit,
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (rtc != null && ended == null) {
                RemoteVideo(graph, rtc, Modifier.fillMaxSize())
                val state by rtc.state.collectAsStateWithLifecycle()
                val tracks by rtc.remoteTracks.collectAsStateWithLifecycle()
                val overlay = when {
                    state == RtcState.FAILED -> R.string.session_failed
                    state == RtcState.DISCONNECTED -> R.string.session_reconnecting
                    state != RtcState.CONNECTED -> R.string.session_negotiating
                    tracks.none { it.kind == "video" } -> R.string.session_placeholder_viewer
                    else -> null
                }
                if (overlay != null) StatusOverlay(stringResource(overlay), progress = state != RtcState.FAILED)
            }
        }
    }
}

/** The remote video of [rtc] in a SurfaceViewRenderer sharing the app's EGL context. */
@Composable
fun RemoteVideo(graph: AppGraph, rtc: RtcSession, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(graph.rtc.environment.eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                rtc.addVideoSink(this)
            }
        },
        onRelease = { renderer ->
            rtc.removeVideoSink(renderer)
            renderer.release()
        },
    )
}

/** The phone's camera streamed to a computer (placeholder: the camera comes with the camera feature). */
@Composable
fun CameraScreen(graph: AppGraph, sid: String, onExit: () -> Unit) {
    val outgoing = remember(sid) { graph.network.outgoingSession(sid) }
    val peerName = outgoing?.peerName?.ifBlank { null } ?: outgoing?.peerId?.let(Protocol::formatId) ?: ""
    val ended by rememberSessionEnd(graph, sid)
    SessionScaffold(
        title = stringResource(R.string.session_camera_title, peerName),
        peerName = peerName,
        ended = ended,
        onEnd = {
            graph.network.endOutgoing(sid, "closed")
            onExit()
        },
        onExit = onExit,
    ) {
        Placeholder(R.drawable.ic_videocam, stringResource(R.string.session_placeholder_camera))
    }
}

/** A partner connected to this phone (placeholder: screen sharing comes with the host feature). */
@Composable
fun HostSessionScreen(graph: AppGraph, sid: String, onExit: () -> Unit) {
    val incoming by graph.network.incoming.collectAsStateWithLifecycle()
    val session = incoming?.takeIf { it.sid == sid }
    var peerName by rememberSaveable(sid) { mutableStateOf("") }
    if (session != null) {
        peerName = session.peerName.ifBlank { null } ?: Protocol.formatId(session.peerId)
    }
    val ended by rememberSessionEnd(graph, sid)
    SessionScaffold(
        title = stringResource(R.string.incoming_title_active),
        peerName = peerName,
        ended = ended,
        onEnd = {
            graph.network.endIncoming(sid, "host-closed")
            onExit()
        },
        onExit = onExit,
    ) {
        Placeholder(
            R.drawable.ic_shield,
            stringResource(R.string.incoming_active, peerName) + "\n\n" + stringResource(R.string.session_placeholder_host),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScaffold(
    title: String,
    peerName: String,
    ended: SessionEnd?,
    onEnd: () -> Unit,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val back = { if (ended != null) onExit() else confirmEnd = true }
    BackHandler(onBack = back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (ended == null) {
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (ended != null) {
                Column(
                    Modifier.widthIn(max = 420.dp).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(ended.byPeerMessage), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Button(onClick = onExit) { Text(stringResource(R.string.session_back_home)) }
                }
            } else {
                content()
            }
        }
    }
    if (confirmEnd && ended == null) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.session_end_confirm_title)) },
            text = { Text(stringResource(R.string.session_end_confirm_body, peerName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnd = false
                        onEnd()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.session_end)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun StatusOverlay(text: String, progress: Boolean) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC111827))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Placeholder(icon: Int, text: String) {
    Column(
        Modifier.widthIn(max = 420.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

/** Shown over any screen while a partner asks to connect to this phone. */
@Composable
fun IncomingRequestDialog(graph: AppGraph, onAccepted: (String) -> Unit) {
    val incoming by graph.network.incoming.collectAsStateWithLifecycle()
    val request = incoming?.takeIf { it.state == IncomingState.PENDING } ?: return
    val name = request.peerName.ifBlank { stringResource(R.string.incoming_unknown_name) }
    val remaining by produceState(initialValue = secondsLeft(request.deadline), request.sid) {
        while (true) {
            value = secondsLeft(request.deadline)
            delay(1_000)
        }
    }
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
            Button(onClick = {
                val sid = request.sid
                graph.appScope.launch {
                    // Screen sharing and remote control come with the host feature.
                    graph.network.acceptIncoming(sid, graph.network.hostCaps(controlAvailable = false))
                }
                onAccepted(sid)
            }) { Text(stringResource(R.string.incoming_accept)) }
        },
        dismissButton = {
            OutlinedButton(onClick = { graph.network.declineIncoming(request.sid) }) { Text(stringResource(R.string.incoming_decline)) }
        },
    )
}

private fun secondsLeft(deadline: Long?): Int? =
    deadline?.let { ((it - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0) }
