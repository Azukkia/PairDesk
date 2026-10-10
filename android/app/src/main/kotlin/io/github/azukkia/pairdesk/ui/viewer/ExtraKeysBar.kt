package io.github.azukkia.pairdesk.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.session.ViewerSession
import io.github.azukkia.pairdesk.viewer.KeyCombo
import io.github.azukkia.pairdesk.viewer.StickyModifier
import io.github.azukkia.pairdesk.viewer.StickyState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The keys a phone keyboard lacks, above the soft keyboard: Esc, Tab, sticky
 * Ctrl / Alt / Shift (tap: next key, long press: locked), Win (tap: Start
 * menu, long press: held for the next key), arrows (repeat while held), Del,
 * Home, End, F1–F12 and the key combinations.
 */
@Composable
internal fun ExtraKeysBar(viewer: ViewerSession, onHideKeyboard: () -> Unit) {
    val mods by viewer.modifiers.states.collectAsStateWithLifecycle()
    val keyboard = viewer.keyboard
    Surface(color = ViewerColors.barSolid, contentColor = ViewerColors.text) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton(stringResource(R.string.key_esc), onClick = { keyboard.key("Escape") })
            KeyButton(stringResource(R.string.key_tab), onClick = { keyboard.key("Tab") })
            ModifierKey(viewer, StickyModifier.CTRL, stringResource(R.string.key_ctrl), mods.getValue(StickyModifier.CTRL))
            ModifierKey(viewer, StickyModifier.ALT, stringResource(R.string.key_alt), mods.getValue(StickyModifier.ALT))
            ModifierKey(viewer, StickyModifier.SHIFT, stringResource(R.string.key_shift), mods.getValue(StickyModifier.SHIFT))
            KeyButton(
                stringResource(R.string.key_win),
                state = mods.getValue(StickyModifier.META),
                onClick = {
                    if (viewer.modifiers.state(StickyModifier.META) != StickyState.OFF) {
                        viewer.modifiers.tap(StickyModifier.META)
                    } else {
                        keyboard.windowsKey()
                    }
                },
                onLongClick = { viewer.modifiers.tap(StickyModifier.META) },
            )
            RepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.key_left)) { keyboard.key("ArrowLeft") }
            RepeatKey(Icons.Filled.KeyboardArrowUp, stringResource(R.string.key_up)) { keyboard.key("ArrowUp") }
            RepeatKey(Icons.Filled.KeyboardArrowDown, stringResource(R.string.key_down)) { keyboard.key("ArrowDown") }
            RepeatKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.key_right)) { keyboard.key("ArrowRight") }
            KeyButton(stringResource(R.string.key_delete), onClick = { keyboard.key("Delete") })
            KeyButton(stringResource(R.string.key_home), onClick = { keyboard.key("Home") })
            KeyButton(stringResource(R.string.key_end), onClick = { keyboard.key("End") })
            FunctionKeysMenu(onKey = { keyboard.key(it) })
            if (!viewer.peer.isAndroid) CombosMenu(onCombo = { keyboard.combo(it) })
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickableCompat(onClick = onHideKeyboard)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_keyboard_hide),
                    contentDescription = stringResource(R.string.viewer_hide_keyboard),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ModifierKey(viewer: ViewerSession, modifier: StickyModifier, label: String, state: StickyState) {
    KeyButton(
        label,
        state = state,
        onClick = { viewer.modifiers.tap(modifier) },
        onLongClick = { viewer.modifiers.lock(modifier) },
    )
}

@Composable
private fun KeyButton(
    label: String,
    state: StickyState = StickyState.OFF,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val color = when (state) {
        StickyState.OFF -> ViewerColors.key
        StickyState.ONCE -> ViewerColors.keyActive
        StickyState.LOCKED -> ViewerColors.keyLocked
    }
    Box(
        Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .combinedClickableCompat(
                onClick = onClick,
                onLongClick = onLongClick?.let { long ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        long()
                    }
                },
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (state == StickyState.LOCKED) FontWeight.Bold else FontWeight.Medium,
            color = Color.White,
        )
    }
}

/** A key repeated while it is held (arrows). */
@Composable
private fun RepeatKey(icon: ImageVector, description: String, onKey: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onKey)
    Box(
        Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ViewerColors.key)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        current()
                        var job: Job? = null
                        job = scope.launch {
                            delay(REPEAT_DELAY_MS)
                            while (true) {
                                current()
                                delay(REPEAT_INTERVAL_MS)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FunctionKeysMenu(onKey: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        KeyButton("F1–F12", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuTitle(stringResource(R.string.viewer_function_keys))
            for (i in 1..12) {
                DropdownMenuItem(
                    text = { Text("F$i") },
                    onClick = {
                        onKey("F$i")
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CombosMenu(onCombo: (KeyCombo) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(40.dp)
                .defaultMinSize(minWidth = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ViewerColors.key)
                .combinedClickableCompat(onClick = { expanded = true })
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_command), contentDescription = stringResource(R.string.viewer_keys), tint = Color.White, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuTitle(stringResource(R.string.viewer_keys))
            for (combo in KeyCombo.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(combo.label)) },
                    onClick = {
                        onCombo(combo)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L
