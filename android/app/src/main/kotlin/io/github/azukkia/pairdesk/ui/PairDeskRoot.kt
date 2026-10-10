package io.github.azukkia.pairdesk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.net.IncomingState
import io.github.azukkia.pairdesk.net.SessionKind
import io.github.azukkia.pairdesk.ui.home.HomeScreen
import io.github.azukkia.pairdesk.ui.home.HomeViewModel
import io.github.azukkia.pairdesk.ui.session.CameraScreen
import io.github.azukkia.pairdesk.ui.session.HostSessionScreen
import io.github.azukkia.pairdesk.ui.session.IncomingRequestDialog
import io.github.azukkia.pairdesk.ui.session.ViewerScreen
import io.github.azukkia.pairdesk.ui.settings.SettingsScreen
import io.github.azukkia.pairdesk.ui.settings.SettingsViewModel

/** The whole UI: the current screen of the back stack, and the incoming request dialog above it. */
@Composable
fun PairDeskRoot(graph: AppGraph) {
    val nav: NavigatorViewModel = viewModel()
    val backStack = nav.backStack
    val home: HomeViewModel = viewModel { HomeViewModel(graph) }
    val settings: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val screens by backStack.screens.collectAsStateWithLifecycle()
    val current = screens.last()
    val saveable = rememberSaveableStateHolder()

    // Session screens handle Back themselves (they ask before ending the session).
    BackHandler(enabled = current == Screen.Settings) { backStack.pop() }

    // An accepted incoming session always has its screen (e.g. after the activity was recreated).
    val incoming by graph.network.incoming.collectAsStateWithLifecycle()
    LaunchedEffect(incoming?.sid, incoming?.state) {
        val session = incoming ?: return@LaunchedEffect
        if (session.state == IncomingState.ACTIVE) {
            val screen = Screen.HostSession(session.sid)
            if (screen !in backStack.screens.value) backStack.push(screen)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it.key },
            label = "screen",
        ) { screen ->
            saveable.SaveableStateProvider(screen.key) {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        vm = home,
                        onOpenSettings = { backStack.push(Screen.Settings) },
                        onSessionStarted = { session ->
                            backStack.push(if (session.kind == SessionKind.CAMERA) Screen.Camera(session.sid) else Screen.Viewer(session.sid))
                        },
                    )
                    Screen.Settings -> SettingsScreen(settings, onBack = { backStack.pop() })
                    is Screen.Viewer -> ViewerScreen(graph, screen.sid, onExit = { backStack.remove(screen) })
                    is Screen.Camera -> CameraScreen(graph, screen.sid, onExit = { backStack.remove(screen) })
                    is Screen.HostSession -> HostSessionScreen(graph, screen.sid, onExit = { backStack.remove(screen) })
                }
            }
        }
    }

    IncomingRequestDialog(graph, onAccepted = { sid -> backStack.push(Screen.HostSession(sid)) })
}
