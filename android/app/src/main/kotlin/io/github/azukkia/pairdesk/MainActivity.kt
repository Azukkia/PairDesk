package io.github.azukkia.pairdesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.azukkia.pairdesk.ui.PairDeskRoot
import io.github.azukkia.pairdesk.ui.theme.PairDeskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = appGraph
        setContent {
            PairDeskTheme {
                PairDeskRoot(graph)
            }
        }
    }
}
