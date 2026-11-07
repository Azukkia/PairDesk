package io.github.azukkia.pairdesk.ui.session

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.camera.CameraFacing
import io.github.azukkia.pairdesk.camera.CameraPhase
import io.github.azukkia.pairdesk.camera.CameraSession
import io.github.azukkia.pairdesk.camera.CameraState
import io.github.azukkia.pairdesk.ui.viewer.AskNotificationPermissionOnce
import io.github.azukkia.pairdesk.ui.viewer.ViewerColors
import io.github.azukkia.pairdesk.viewer.EndReason
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * The phone's camera streamed to a computer (camera session): the local
 * preview, the camera switch and the end buttons, the connection status.
 * Asks for the CAMERA permission first; the screen stays on, and the camera
 * keeps streaming in the background (CameraService).
 */
@Composable
fun CameraScreen(graph: AppGraph, sid: String, onExit: () -> Unit) {
    val camera = remember(sid) { graph.cameras.obtain(sid) }
    if (camera == null) {
        MissingSession(onExit)
        return
    }
    val state by camera.core.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmEnd by rememberSaveable(sid) { mutableStateOf(false) }
    var denied by rememberSaveable(sid) { mutableStateOf(false) }
    val exit = {
        graph.cameras.close(sid)
        onExit()
    }

    AskNotificationPermissionOnce(graph)
    KeepScreenOn()
    BackHandler { if (state.ended) exit() else confirmEnd = true }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.startCamera() else denied = true
    }
    LaunchedEffect(camera) {
        if (camera.core.isEnded) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!state.ended && state.phase != CameraPhase.STARTING) {
            CameraPreview(graph, camera, mirror = state.facing == CameraFacing.FRONT)
        }
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(12.dp),
        ) {
            TopBar(camera, state)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.ended -> Unit
                    denied && state.phase == CameraPhase.STARTING -> PermissionDenied(
                        peer = camera.peer.label,
                        onRetry = {
                            denied = false
                            permission.launch(Manifest.permission.CAMERA)
                        },
                        onSettings = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } catch (_: ActivityNotFoundException) {
                                // No settings app.
                            }
                        },
                    )
                    state.phase != CameraPhase.LIVE -> Progress(
                        stringResource(
                            when (state.phase) {
                                CameraPhase.STARTING -> R.string.camera_starting
                                CameraPhase.RECONNECTING -> R.string.session_reconnecting
                                else -> R.string.session_connecting
                            },
                        ),
                    )
                    else -> Unit
                }
            }
            if (!state.ended) BottomBar(state, onSwitch = { camera.core.switchCamera() }, onEnd = { confirmEnd = true })
        }
        if (state.ended) Ended(state, camera.peer.label, onClose = exit)
    }

    if (confirmEnd && !state.ended) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.session_end_confirm_title)) },
            text = { Text(stringResource(R.string.session_end_confirm_body, camera.peer.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnd = false
                        camera.end()
                        exit()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.session_end)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** The camera, as the computer receives it (mirrored for the front camera, like a mirror). */
@Composable
private fun CameraPreview(graph: AppGraph, camera: CameraSession, mirror: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(graph.rtc.environment.eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                camera.addPreview(this)
            }
        },
        update = { view -> view.setMirror(mirror) },
        onRelease = { view ->
            camera.removePreview(view)
            view.release()
        },
    )
}

@Composable
private fun TopBar(camera: CameraSession, state: CameraState) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ViewerColors.bar)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_videocam), contentDescription = null, tint = ViewerColors.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.session_camera_title, camera.peer.label), color = ViewerColors.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val status = when (state.phase) {
                CameraPhase.LIVE -> stringResource(R.string.camera_live) +
                    (state.facing?.let { " · " + stringResource(if (it == CameraFacing.FRONT) R.string.camera_front else R.string.camera_back) } ?: "")
                CameraPhase.RECONNECTING -> stringResource(R.string.viewer_status_reconnecting)
                CameraPhase.ENDED -> stringResource(R.string.viewer_status_ended)
                else -> stringResource(R.string.viewer_status_connecting)
            }
            Text(
                status,
                color = if (state.phase == CameraPhase.LIVE) ViewerColors.good else ViewerColors.dim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BottomBar(state: CameraState, onSwitch: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.phase == CameraPhase.LIVE) {
            Text(
                stringResource(R.string.camera_background_hint),
                color = ViewerColors.dim,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.canSwitch) {
                FilledIconButton(
                    onClick = onSwitch,
                    enabled = !state.switching,
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViewerColors.key, contentColor = ViewerColors.text),
                ) {
                    Icon(painterResource(R.drawable.ic_swap), contentDescription = stringResource(R.string.camera_switch), modifier = Modifier.size(28.dp))
                }
            }
            FilledIconButton(
                onClick = onEnd,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ViewerColors.bad, contentColor = Color.White),
            ) {
                Icon(painterResource(R.drawable.ic_call_end), contentDescription = stringResource(R.string.session_end), modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(
        Modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ViewerColors.bar)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        Text(text, color = ViewerColors.text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionDenied(peer: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ViewerColors.barSolid)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.camera_permission_title), color = ViewerColors.text, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.camera_permission_body, peer), color = ViewerColors.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.camera_permission_denied), color = ViewerColors.dim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRetry) { Text(stringResource(R.string.camera_permission_grant)) }
        OutlinedButton(onClick = onSettings, colors = ButtonDefaults.outlinedButtonColors(contentColor = ViewerColors.text)) {
            Text(stringResource(R.string.open_app_settings))
        }
    }
}

@Composable
private fun Ended(state: CameraState, peer: String, onClose: () -> Unit) {
    val message = when {
        state.error == "no-camera" -> R.string.camera_error_no_camera
        state.error != null -> R.string.camera_error
        state.endReason == EndReason.PEER -> R.string.session_ended_by_peer
        state.endReason == EndReason.FAILED -> R.string.viewer_failed
        else -> R.string.session_ended
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ViewerColors.barSolid)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(R.drawable.ic_videocam), contentDescription = null, tint = ViewerColors.dim, modifier = Modifier.size(40.dp))
            Text(peer, color = ViewerColors.text, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(message), color = ViewerColors.text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClose) { Text(stringResource(R.string.session_back_home)) }
        }
    }
}

/** The screen stays on while the session screen is shown. */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun MissingSession(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.viewer_missing))
            Button(onClick = onExit) { Text(stringResource(R.string.session_back_home)) }
        }
    }
}
