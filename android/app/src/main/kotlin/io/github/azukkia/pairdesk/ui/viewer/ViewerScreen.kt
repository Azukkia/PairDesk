package io.github.azukkia.pairdesk.ui.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.net.ConnectResult
import io.github.azukkia.pairdesk.session.ViewerSession
import io.github.azukkia.pairdesk.ui.ConnectErrors
import io.github.azukkia.pairdesk.ui.UiText
import io.github.azukkia.pairdesk.ui.text
import io.github.azukkia.pairdesk.viewer.EndReason
import io.github.azukkia.pairdesk.viewer.InputMode
import io.github.azukkia.pairdesk.viewer.ViewerNotice
import io.github.azukkia.pairdesk.viewer.ViewerPhase
import io.github.azukkia.pairdesk.viewer.ViewerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Colors of the viewer chrome (always dark, over the remote picture). */
internal object ViewerColors {
    val bar = Color(0xEB121826)
    val barSolid = Color(0xFF121826)
    val key = Color(0xFF242C3F)
    val keyActive = Color(0xFF3B6CF6)
    val keyLocked = Color(0xFF6D4CF6)
    val text = Color(0xFFF1F4FA)
    val dim = Color(0xFFA9B1C6)
    val good = Color(0xFF34C77B)
    val warn = Color(0xFFF2B33D)
    val bad = Color(0xFFEF5350)
    val notice = Color(0xE6352A12)
}

/**
 * Controlling a computer: its screen full screen (immersive, kept awake,
 * any orientation), the input of the phone sent to it, and the toolbar
 * (keyboard, input mode, screens, quality, key combinations, chat,
 * clipboard, disconnect). [onExit] leaves the screen; [onReplace] opens the
 * reconnected session in its place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewerScreen(graph: AppGraph, sid: String, onExit: () -> Unit, onReplace: (String) -> Unit) {
    val viewer = remember(sid) { graph.viewers.obtain(sid) }
    if (viewer == null) {
        MissingSession(onExit)
        return
    }
    val state by viewer.core.state.collectAsStateWithLifecycle()
    val prefs by graph.settings.viewer.collectAsStateWithLifecycle()
    val mode by viewer.inputMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var toolbarVisible by rememberSaveable(sid) { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var keyboardOpen by rememberSaveable(sid) { mutableStateOf(false) }
    var chatOpen by rememberSaveable(sid) { mutableStateOf(false) }
    var confirmDisconnect by rememberSaveable(sid) { mutableStateOf(false) }
    var showGestures by rememberSaveable(sid) { mutableStateOf(false) }
    var screenView by remember { mutableStateOf<RemoteScreenView?>(null) }
    var zoomed by remember { mutableStateOf(viewer.viewport.zoom.isZoomed) }

    val exit = {
        graph.viewers.close(sid)
        onExit()
    }

    ViewerWindowEffects(landscape = prefs.landscape)
    AskNotificationPermissionOnce(graph)

    BackHandler {
        when {
            chatOpen -> chatOpen = false
            keyboardOpen -> keyboardOpen = false
            state.ended -> exit()
            else -> confirmDisconnect = true
        }
    }

    // Zoom state for the "fit" button.
    DisposableEffect(viewer) {
        val listener: (Boolean) -> Unit = { zoomed = viewer.viewport.zoom.isZoomed }
        viewer.viewport.addListener(listener)
        onDispose { viewer.viewport.removeListener(listener) }
    }

    // The toolbar hides when the remote screen is touched, and by itself after a while.
    LaunchedEffect(viewer) {
        viewer.touches.collect { if (!menuOpen) toolbarVisible = false }
    }
    LaunchedEffect(toolbarVisible, interaction, menuOpen, state.phase) {
        if (toolbarVisible && !menuOpen && state.phase == ViewerPhase.LIVE) {
            delay(TOOLBAR_HIDE_MS)
            toolbarVisible = false
        }
    }
    LaunchedEffect(state.ended) {
        if (state.ended) {
            keyboardOpen = false
            chatOpen = false
            confirmDisconnect = false
        }
    }

    // Soft keyboard: shown on the remote screen view; closed by the system → closed here.
    LaunchedEffect(keyboardOpen, screenView) { screenView?.setKeyboardVisible(keyboardOpen) }
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible && keyboardOpen && !chatOpen) keyboardOpen = false
        imeWasVisible = imeVisible
    }
    LaunchedEffect(chatOpen) {
        viewer.core.setChatOpen(chatOpen)
        if (chatOpen) keyboardOpen = false
    }

    // One-shot notices → snackbars.
    LaunchedEffect(viewer) {
        viewer.core.notices.collect { notice ->
            val text = when (notice) {
                is ViewerNotice.Chat -> context.getString(R.string.viewer_chat_from, notice.from, notice.text.take(120))
                ViewerNotice.ClipboardReceived -> context.getString(R.string.viewer_clipboard_received).takeIf {
                    // Android 13+ shows its own confirmation when an app copies something.
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                }
                ViewerNotice.ClipboardFilesIgnored -> context.getString(R.string.viewer_clipboard_files)
                ViewerNotice.ClipboardSent -> context.getString(R.string.viewer_clipboard_sent)
                ViewerNotice.ClipboardEmpty -> context.getString(R.string.viewer_clipboard_empty)
                ViewerNotice.ClipboardTooLarge -> context.getString(R.string.viewer_clipboard_too_large)
                ViewerNotice.ClipboardDisabled -> context.getString(R.string.viewer_clipboard_disabled)
            } ?: return@collect
            scope.launch {
                val action = if (notice is ViewerNotice.Chat) context.getString(R.string.chat_reply) else null
                val result = snackbar.showSnackbar(text, actionLabel = action, withDismissAction = action == null, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) chatOpen = true
            }
        }
    }

    fun showHelp(mode: InputMode) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val res = if (mode == InputMode.TOUCHPAD) R.string.viewer_mode_touchpad_help else R.string.viewer_mode_direct_help
            snackbar.showSnackbar(context.getString(res), withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        RemoteScreenView(ctx, viewer, graph.rtc.environment.eglContext).also { screenView = it }
                    },
                    update = { view ->
                        // Redraws the touchpad cursor when control or the mode changes.
                        if (state.canControl || mode == InputMode.TOUCHPAD) view.invalidate()
                    },
                    onRelease = { view ->
                        view.release()
                        if (screenView === view) screenView = null
                    },
                )

                StatusOverlay(state, viewer, onDisconnect = { confirmDisconnect = true })

                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(top = 6.dp, start = 8.dp, end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = toolbarVisible && !state.ended,
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        ViewerToolbar(
                            viewer = viewer,
                            state = state,
                            mode = mode,
                            landscape = prefs.landscape,
                            zoomed = zoomed,
                            keyboardOpen = keyboardOpen,
                            onKeyboard = {
                                keyboardOpen = !keyboardOpen
                                interaction++
                            },
                            onMode = { next ->
                                viewer.setInputMode(next)
                                showHelp(next)
                                interaction++
                            },
                            onChat = {
                                chatOpen = true
                                interaction++
                            },
                            onDisconnect = { confirmDisconnect = true },
                            onLandscape = { on -> graph.settings.updateViewer { it.copy(landscape = on) } },
                            onGestures = { showGestures = true },
                            onMenuOpenChange = { open ->
                                menuOpen = open
                                interaction++
                            },
                            onInteraction = { interaction++ },
                            onHide = { toolbarVisible = false },
                        )
                    }
                    if (!toolbarVisible && !state.ended) ToolbarHandle(onShow = { toolbarVisible = true })
                    Notices(state)
                }

                if (zoomed && !state.ended) {
                    FitButton(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(12.dp),
                        onClick = { viewer.viewport.resetZoom() },
                    )
                }
            }
            AnimatedVisibility(visible = keyboardOpen && !state.ended) {
                ExtraKeysBar(viewer, onHideKeyboard = { keyboardOpen = false })
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (keyboardOpen) 56.dp else 8.dp),
        ) { data ->
            Snackbar(data, containerColor = ViewerColors.barSolid, contentColor = ViewerColors.text, actionColor = Color(0xFF8FB0FF))
        }

        if (state.ended) {
            EndedOverlay(
                state = state,
                viewer = viewer,
                onClose = exit,
                onReconnected = { newSid ->
                    graph.viewers.close(sid)
                    onReplace(newSid)
                },
            )
        }
    }

    if (chatOpen) {
        ChatSheet(viewer, state, onDismiss = { chatOpen = false })
    }

    if (confirmDisconnect && !state.ended) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.viewer_disconnect_title, viewer.peer.label)) },
            text = { Text(stringResource(R.string.viewer_disconnect_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        viewer.disconnect()
                        exit()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.viewer_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showGestures) {
        AlertDialog(
            onDismissRequest = { showGestures = false },
            title = { Text(stringResource(R.string.viewer_gestures)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.viewer_mode_touchpad_help))
                    Text(stringResource(R.string.viewer_mode_direct_help))
                    Text(stringResource(R.string.viewer_modifier_help), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showGestures = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

private const val TOOLBAR_HIDE_MS = 4_000L

/** The small tab left at the top when the toolbar is hidden: tap or pull it down. */
@Composable
private fun ToolbarHandle(onShow: () -> Unit) {
    val description = stringResource(R.string.viewer_show_toolbar)
    Box(
        Modifier
            .padding(top = 2.dp)
            .size(width = 72.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onShow)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 2f) onShow() }
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x99FFFFFF)),
        )
    }
}

/** Connecting / waiting for the picture / reconnecting, over the picture. */
@Composable
private fun StatusOverlay(state: ViewerState, viewer: ViewerSession, onDisconnect: () -> Unit) {
    val text = when (state.phase) {
        ViewerPhase.NEGOTIATING -> R.string.viewer_negotiating
        ViewerPhase.WAITING_VIDEO -> R.string.viewer_waiting_video
        ViewerPhase.RECONNECTING -> R.string.viewer_reconnecting
        else -> return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .padding(24.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ViewerColors.bar)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            Text(stringResource(text), color = ViewerColors.text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text(viewer.peer.label, color = ViewerColors.dim, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDisconnect, colors = ButtonDefaults.outlinedButtonColors(contentColor = ViewerColors.bad)) {
                Text(stringResource(R.string.viewer_disconnect))
            }
        }
    }
}

/** Why the input does nothing, or why the picture does not change. */
@Composable
private fun Notices(state: ViewerState) {
    val text = when {
        state.ended -> null
        state.inputBlocked == "secure-desktop" -> R.string.viewer_blocked_secure
        state.inputBlocked != null -> R.string.viewer_blocked_elevated
        state.frozen -> R.string.viewer_frozen
        state.connected && !state.caps.control -> R.string.viewer_view_only
        else -> null
    } ?: return
    Row(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ViewerColors.notice)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = ViewerColors.warn, modifier = Modifier.size(18.dp))
        Text(stringResource(text), color = ViewerColors.text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FitButton(modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = ViewerColors.bar,
        contentColor = ViewerColors.text,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_fit), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.viewer_fit), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The session is over: why, and Reconnect / Close. */
@Composable
private fun EndedOverlay(state: ViewerState, viewer: ViewerSession, onClose: () -> Unit, onReconnected: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    val message = when (state.endReason) {
        EndReason.PEER -> R.string.viewer_ended_by_peer
        EndReason.FAILED -> R.string.viewer_failed
        else -> R.string.viewer_ended
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
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
            Icon(
                painterResource(R.drawable.ic_power),
                contentDescription = null,
                tint = if (state.endReason == EndReason.FAILED) ViewerColors.bad else ViewerColors.dim,
                modifier = Modifier.size(40.dp),
            )
            Text(viewer.peer.label, color = ViewerColors.text, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(message), color = ViewerColors.text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            error?.let { Text(it.text(), color = ViewerColors.bad, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose, colors = ButtonDefaults.outlinedButtonColors(contentColor = ViewerColors.text)) {
                    Text(stringResource(R.string.common_close))
                }
                Button(
                    enabled = !connecting,
                    onClick = {
                        connecting = true
                        error = null
                        scope.launch {
                            when (val result = viewer.reconnect()) {
                                is ConnectResult.Success -> onReconnected(result.outgoing.sid)
                                is ConnectResult.Failure -> {
                                    error = ConnectErrors.message(result.code, result.retryIn)
                                    connecting = false
                                }
                            }
                        }
                    },
                ) {
                    if (connecting) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.viewer_reconnect_running))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.viewer_reconnect))
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingSession(onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.viewer_missing), color = ViewerColors.text)
            Button(onClick = onExit) { Text(stringResource(R.string.session_back_home)) }
        }
    }
}

/**
 * Full screen while controlling: system bars hidden (a swipe shows them for
 * a moment), the screen kept on, and landscape forced when asked.
 */
@Composable
private fun ViewerWindowEffects(landscape: Boolean) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }
    DisposableEffect(activity, landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** Android 13+: the ongoing-session notification (with its Disconnect button) needs a permission, asked once. */
@Composable
private fun AskNotificationPermissionOnce(graph: AppGraph) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (graph.settings.viewer.value.notificationsAsked) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        graph.settings.updateViewer { it.copy(notificationsAsked = true) }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
