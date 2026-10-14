package io.github.azukkia.pairdesk.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.session.ViewerSession
import io.github.azukkia.pairdesk.viewer.InputMode
import io.github.azukkia.pairdesk.viewer.KeyCombo
import io.github.azukkia.pairdesk.viewer.Quality
import io.github.azukkia.pairdesk.viewer.RemoteLayout
import io.github.azukkia.pairdesk.viewer.ViewerPhase
import io.github.azukkia.pairdesk.viewer.ViewerState

/** Labels of the key combinations (same order as the desktop's "Actions" menu). */
internal val KeyCombo.label: Int
    get() = when (this) {
        KeyCombo.CTRL_ALT_DEL -> R.string.keys_ctrl_alt_del
        KeyCombo.CTRL_SHIFT_ESC -> R.string.keys_ctrl_shift_esc
        KeyCombo.ALT_TAB -> R.string.keys_alt_tab
        KeyCombo.ALT_F4 -> R.string.keys_alt_f4
        KeyCombo.WIN -> R.string.keys_win
        KeyCombo.WIN_R -> R.string.keys_win_r
        KeyCombo.WIN_D -> R.string.keys_win_d
        KeyCombo.WIN_E -> R.string.keys_win_e
        KeyCombo.WIN_L -> R.string.keys_win_l
        KeyCombo.PRINT_SCREEN -> R.string.keys_print_screen
    }

internal val RemoteLayout.label: Int
    get() = when (this) {
        RemoteLayout.QWERTY -> R.string.layout_qwerty
        RemoteLayout.AZERTY -> R.string.layout_azerty
        RemoteLayout.QWERTZ -> R.string.layout_qwertz
    }

private val Quality.label: Int
    get() = when (this) {
        Quality.SPEED -> R.string.quality_speed
        Quality.BALANCED -> R.string.quality_balanced
        Quality.QUALITY -> R.string.quality_quality
    }

private val Quality.hint: Int
    get() = when (this) {
        Quality.SPEED -> R.string.quality_speed_hint
        Quality.BALANCED -> R.string.quality_balanced_hint
        Quality.QUALITY -> R.string.quality_quality_hint
    }

/**
 * The viewer toolbar: connection state, then keyboard, input mode, screens,
 * quality, key combinations (or the phone buttons of an Android host),
 * chat, clipboard, sound, more, disconnect. Scrolls horizontally when the
 * screen is narrow.
 */
@Composable
internal fun ViewerToolbar(
    viewer: ViewerSession,
    state: ViewerState,
    mode: InputMode,
    landscape: Boolean,
    zoomed: Boolean,
    keyboardOpen: Boolean,
    onKeyboard: () -> Unit,
    onMode: (InputMode) -> Unit,
    onChat: () -> Unit,
    onDisconnect: () -> Unit,
    onLandscape: (Boolean) -> Unit,
    onGestures: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onInteraction: () -> Unit,
    onHide: () -> Unit,
) {
    val control = state.caps.control && !state.ended
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = ViewerColors.bar,
        contentColor = ViewerColors.text,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(viewer, state)
            Divider()
            if (control) {
                ToolButton(
                    painterResource(if (keyboardOpen) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard),
                    stringResource(if (keyboardOpen) R.string.viewer_hide_keyboard else R.string.viewer_keyboard),
                    active = keyboardOpen,
                    onClick = onKeyboard,
                )
                val next = if (mode == InputMode.TOUCHPAD) InputMode.DIRECT else InputMode.TOUCHPAD
                ToolButton(
                    painterResource(if (mode == InputMode.TOUCHPAD) R.drawable.ic_mouse else R.drawable.ic_touch),
                    // Says what the button switches to.
                    stringResource(if (next == InputMode.TOUCHPAD) R.string.viewer_mode_touchpad else R.string.viewer_mode_direct),
                    onClick = { onMode(next) },
                )
            }
            if (state.displays.size >= 2) {
                ScreensMenu(viewer, state, onMenuOpenChange)
                ToolButton(
                    painterResource(R.drawable.ic_swap),
                    stringResource(R.string.viewer_next_screen, state.nextIndex + 1),
                    onClick = {
                        viewer.core.nextDisplay()
                        onInteraction()
                    },
                )
            }
            MenuButton(painterResource(R.drawable.ic_gauge), stringResource(R.string.viewer_quality), onMenuOpenChange) { close ->
                MenuTitle(stringResource(R.string.viewer_quality))
                for (q in Quality.entries) {
                    CheckItem(
                        title = stringResource(q.label),
                        subtitle = stringResource(q.hint),
                        checked = state.quality == q,
                        onClick = {
                            viewer.core.setQuality(q)
                            close()
                        },
                    )
                }
            }
            if (control && viewer.peer.isAndroid) {
                ToolButton(painterResource(R.drawable.ic_android_back), stringResource(R.string.viewer_android_back), onClick = { viewer.keyboard.androidAction("back") })
                ToolButton(painterResource(R.drawable.ic_android_home), stringResource(R.string.viewer_android_home), onClick = { viewer.keyboard.androidAction("home") })
                ToolButton(painterResource(R.drawable.ic_square), stringResource(R.string.viewer_android_recents), onClick = { viewer.keyboard.androidAction("recents") })
            } else if (control) {
                MenuButton(painterResource(R.drawable.ic_command), stringResource(R.string.viewer_keys), onMenuOpenChange) { close ->
                    MenuTitle(stringResource(R.string.viewer_keys))
                    for (combo in KeyCombo.entries) {
                        DropdownMenuItem(
                            text = { Text(stringResource(combo.label)) },
                            onClick = {
                                viewer.keyboard.combo(combo)
                                close()
                            },
                        )
                    }
                }
            }
            Divider()
            BadgedBox(badge = { if (state.unread > 0) Badge { Text(state.unread.coerceAtMost(99).toString()) } }) {
                ToolButton(painterResource(R.drawable.ic_chat), stringResource(R.string.viewer_chat), onClick = onChat)
            }
            if (state.caps.clipboard) {
                MenuButton(painterResource(R.drawable.ic_clipboard), stringResource(R.string.viewer_clipboard), onMenuOpenChange) { close ->
                    MenuTitle(stringResource(R.string.viewer_clipboard))
                    CheckItem(
                        title = stringResource(R.string.viewer_clipboard_sync),
                        subtitle = stringResource(R.string.viewer_clipboard_sync_desc),
                        checked = state.clipboardSync,
                        onClick = { viewer.core.setClipboardSync(!state.clipboardSync) },
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.viewer_clipboard_send))
                                Text(stringResource(R.string.viewer_clipboard_send_desc), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                        onClick = {
                            viewer.sendPhoneClipboard()
                            close()
                        },
                    )
                }
            }
            if (state.hasAudio) {
                ToolButton(
                    painterResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                    stringResource(if (state.muted) R.string.viewer_unmute else R.string.viewer_mute),
                    onClick = { viewer.setMuted(!state.muted) },
                )
            }
            MoreMenu(viewer, landscape, zoomed, onLandscape, onGestures, onMenuOpenChange)
            ToolButton(
                painterResource(R.drawable.ic_power),
                stringResource(R.string.viewer_disconnect),
                tint = ViewerColors.bad,
                onClick = onDisconnect,
            )
            ToolButton(rememberVectorPainter(Icons.Filled.KeyboardArrowUp), stringResource(R.string.viewer_hide_toolbar), onClick = onHide)
        }
    }
}

/** Dot, partner name, and "Direct (P2P) · 30 fps · ≈ 40 ms". */
@Composable
private fun StatusChip(viewer: ViewerSession, state: ViewerState) {
    val dot = when (state.phase) {
        ViewerPhase.LIVE -> ViewerColors.good
        ViewerPhase.ENDED -> ViewerColors.bad
        else -> ViewerColors.warn
    }
    val line = when (state.phase) {
        ViewerPhase.NEGOTIATING, ViewerPhase.WAITING_VIDEO -> stringResource(R.string.viewer_status_connecting)
        ViewerPhase.RECONNECTING -> stringResource(R.string.viewer_status_reconnecting)
        ViewerPhase.ENDED -> stringResource(R.string.viewer_status_ended)
        ViewerPhase.LIVE -> listOfNotNull(
            state.stats.relayed?.let { stringResource(if (it) R.string.viewer_relayed else R.string.viewer_direct) },
            state.stats.fps?.let { stringResource(R.string.viewer_fps, it) },
            state.stats.delayMs?.let { stringResource(R.string.viewer_delay, it) },
        ).joinToString(" · ")
    }
    Row(Modifier.padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 200.dp)) {
            Text(
                viewer.peer.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.labelSmall, color = ViewerColors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ScreensMenu(viewer: ViewerSession, state: ViewerState, onMenuOpenChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val set = { open: Boolean ->
        expanded = open
        onMenuOpenChange(open)
    }
    Box {
        Surface(
            onClick = { set(true) },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            contentColor = ViewerColors.text,
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_desktop), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.viewer_monitor_n, (state.currentIndex.takeIf { it >= 0 } ?: 0) + 1),
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { set(false) }) {
            MenuTitle(stringResource(R.string.viewer_screens))
            state.displays.forEachIndexed { i, d ->
                val name = stringResource(R.string.viewer_monitor_n, i + 1) +
                    (if (d.primary) " (" + stringResource(R.string.viewer_primary) + ")" else "")
                CheckItem(
                    title = name,
                    subtitle = if (d.width > 0 && d.height > 0) "${d.width} × ${d.height}" else null,
                    checked = d.id == state.current,
                    onClick = {
                        viewer.core.selectDisplay(d.id)
                        set(false)
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(
    viewer: ViewerSession,
    landscape: Boolean,
    zoomed: Boolean,
    onLandscape: (Boolean) -> Unit,
    onGestures: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val layout by viewer.remoteLayout.collectAsStateWithLifecycle()
    MenuButton(rememberVectorPainter(Icons.Filled.MoreVert), stringResource(R.string.viewer_more), onMenuOpenChange) { close ->
        CheckItem(
            title = stringResource(R.string.viewer_landscape),
            checked = landscape,
            leading = painterResource(R.drawable.ic_screen_rotation),
            onClick = {
                onLandscape(!landscape)
                close()
            },
        )
        if (zoomed) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_fit)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_fit), contentDescription = null) },
                onClick = {
                    viewer.viewport.resetZoom()
                    close()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.viewer_gestures)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = {
                onGestures()
                close()
            },
        )
        if (!viewer.peer.isAndroid) {
            HorizontalDivider()
            MenuTitle(stringResource(R.string.viewer_layout_title))
            for (l in RemoteLayout.entries) {
                CheckItem(
                    title = stringResource(l.label),
                    checked = layout == l,
                    onClick = {
                        viewer.setRemoteLayout(l)
                        close()
                    },
                )
            }
            Text(
                stringResource(R.string.viewer_layout_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

// ───────────────────────────── building blocks ─────────────────────────────

@Composable
internal fun ToolButton(
    painter: Painter,
    description: String,
    active: Boolean = false,
    tint: Color = ViewerColors.text,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) ViewerColors.keyActive else Color.Transparent,
            contentColor = tint,
        ),
    ) {
        Icon(painter, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/** An icon button opening a menu; [content] gets a `close` function. */
@Composable
internal fun MenuButton(
    painter: Painter,
    description: String,
    onMenuOpenChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val set = { open: Boolean ->
        expanded = open
        onMenuOpenChange(open)
    }
    Box {
        ToolButton(painter, description, active = expanded, onClick = { set(true) })
        DropdownMenu(expanded = expanded, onDismissRequest = { set(false) }) {
            content { set(false) }
        }
    }
}

@Composable
internal fun MenuTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CheckItem(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    leading: Painter? = null,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = leading?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(width = 1.dp, height = 24.dp)
            .background(Color(0x33FFFFFF)),
    )
}
