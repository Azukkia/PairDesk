package io.github.azukkia.pairdesk

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.data.DeviceNames
import io.github.azukkia.pairdesk.data.KeystoreSecretBox
import io.github.azukkia.pairdesk.data.SettingsStore
import io.github.azukkia.pairdesk.data.SharedPreferencesStore
import io.github.azukkia.pairdesk.net.AndroidLogger
import io.github.azukkia.pairdesk.net.NetworkLifecycle
import io.github.azukkia.pairdesk.net.PairDeskNetwork
import io.github.azukkia.pairdesk.rtc.RtcSessions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app-scoped objects, created once per process ([PairDeskApp.graph]).
 * Later features (viewer, host, camera) hang their session controllers here
 * so that sessions outlive the activity.
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext

    val log: Logger = AndroidLogger

    /** For work that must not be cancelled with a screen (accepting a session…). */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> log.warn("[app] ${e.stackTraceToString()}") },
    )

    val settings = SettingsStore(
        kv = SharedPreferencesStore(appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        secrets = KeystoreSecretBox(),
        defaultDisplayName = DeviceNames.pretty(Build.MANUFACTURER, Build.MODEL),
    )

    /** Signaling transport, host passwords, incoming / outgoing sessions. */
    val network = PairDeskNetwork(settings = settings, appVersion = BuildConfig.VERSION_NAME, log = log)

    /** WebRTC peer connections of the running sessions. */
    val rtc = RtcSessions(appContext, network, log)

    private companion object {
        const val PREFS = "pairdesk"
    }
}

class PairDeskApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // The network runs while the app is visible (and while a session runs).
        NetworkLifecycle(this, graph.network).install()
    }
}

/** The [AppGraph] of this process. */
val Context.appGraph: AppGraph get() = (applicationContext as PairDeskApp).graph
