package io.github.azukkia.pairdesk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.azukkia.pairdesk.session.Notifications
import io.github.azukkia.pairdesk.ui.PairDeskRoot
import io.github.azukkia.pairdesk.ui.theme.PairDeskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = appGraph
        if (savedInstanceState == null) handle(intent)
        setContent {
            PairDeskTheme {
                PairDeskRoot(graph)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Accept button of a connection request notification: the request dialog asks for the screen capture. */
    private fun handle(intent: Intent?) {
        if (intent?.action != Notifications.ACTION_ACCEPT_INCOMING) return
        intent.getStringExtra(Notifications.EXTRA_SID)?.let(appGraph.hosts::requestAccept)
    }
}
