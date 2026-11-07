package io.github.azukkia.pairdesk.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.azukkia.pairdesk.BuildConfig
import io.github.azukkia.pairdesk.R

/** Whether this edition can let a computer control the phone (see build.gradle.kts). */
val phoneControlAvailable: Boolean get() = BuildConfig.PHONE_CONTROL

/** The full edition, with the accessibility service (latest release). */
const val FULL_EDITION_URL = "https://github.com/Azukkia/PairDesk/releases/latest/download/PairDesk-full.apk"

/**
 * Standard edition: why computers can watch this phone but not control it,
 * and where to get the full edition (and how to get past Play Protect).
 */
@Composable
fun FullEditionNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.full_edition_needed), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.full_edition_play_protect),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FULL_EDITION_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    // No browser: nothing to open.
                }
            },
        ) {
            Text(stringResource(R.string.full_edition_download))
        }
    }
}
