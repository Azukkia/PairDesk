package io.github.azukkia.pairdesk.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The screens of the app. Session screens are keyed by the signaling session id. */
sealed interface Screen {
    /** Stable key (saveable state of the screen). */
    val key: String

    data object Home : Screen {
        override val key = "home"
    }

    data object Settings : Screen {
        override val key = "settings"
    }

    /** Controlling a computer (outgoing control session). */
    data class Viewer(val sid: String) : Screen {
        override val key = "viewer:$sid"
    }

    /** The phone's camera streamed to a computer (outgoing camera session). */
    data class Camera(val sid: String) : Screen {
        override val key = "camera:$sid"
    }

    /** A partner connected to this phone (incoming session). */
    data class HostSession(val sid: String) : Screen {
        override val key = "host:$sid"
    }
}

/**
 * Back stack of [Screen]s, never empty ([Screen.Home] at the bottom).
 * Pure logic, unit tested; [NavigatorViewModel] keeps it across configuration changes.
 */
class BackStack(initial: List<Screen> = listOf(Screen.Home)) {
    private val _screens = MutableStateFlow(initial.ifEmpty { listOf(Screen.Home) })
    val screens: StateFlow<List<Screen>> = _screens.asStateFlow()

    val current: Screen get() = _screens.value.last()

    val canPop: Boolean get() = _screens.value.size > 1

    /** Shows [screen] (brought to the top if it is already in the stack). */
    fun push(screen: Screen) = _screens.update { stack -> stack.filter { it != screen } + screen }

    /** Goes back; false when already at the bottom. */
    fun pop(): Boolean {
        var popped = false
        _screens.update { stack ->
            if (stack.size > 1) {
                popped = true
                stack.dropLast(1)
            } else {
                stack
            }
        }
        return popped
    }

    /** Removes [screen] wherever it is (e.g. a session that ended in the background). */
    fun remove(screen: Screen) = _screens.update { stack -> stack.filter { it != screen }.ifEmpty { listOf(Screen.Home) } }

    /** Back to the home screen. */
    fun popToHome() = _screens.update { listOf(Screen.Home) }
}

class NavigatorViewModel : ViewModel() {
    val backStack = BackStack()
}
