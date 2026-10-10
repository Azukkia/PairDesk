package io.github.azukkia.pairdesk.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.core.transport.TransportKind
import io.github.azukkia.pairdesk.core.transport.TransportState
import io.github.azukkia.pairdesk.net.NetworkStatus
import io.github.azukkia.pairdesk.ui.UiText
import io.github.azukkia.pairdesk.ui.text
import io.github.azukkia.pairdesk.ui.theme.LocalPairDeskColors

/** Texts describing the connection to the PairDesk network (status line of the desktop). */
object NetworkTexts {
    /** Short label next to the dot. */
    fun label(status: NetworkStatus): UiText = when (status.state) {
        TransportState.ONLINE -> UiText(R.string.status_online)
        TransportState.CONNECTING -> UiText(R.string.status_connecting)
        TransportState.OFFLINE -> UiText(R.string.status_offline)
        TransportState.ERROR -> UiText(R.string.status_error)
    }

    /** One line of details: what is wrong, or through what the device is connected. */
    fun detail(status: NetworkStatus): UiText = when {
        status.error == "id-taken" -> UiText(R.string.status_id_taken)
        status.state == TransportState.ONLINE && status.kind == TransportKind.PUBLIC && status.relays != null ->
            UiText(R.string.status_via_relays, listOf(status.relays))
        status.state == TransportState.ONLINE && status.kind == TransportKind.SERVER && status.serverUrl != null ->
            UiText(R.string.status_via_server, listOf(status.serverUrl))
        status.state == TransportState.ONLINE -> UiText(R.string.status_online_detail)
        status.state == TransportState.CONNECTING -> UiText(R.string.status_connecting_detail)
        status.state == TransportState.ERROR -> UiText(R.string.status_error)
        else -> UiText(R.string.status_offline_detail)
    }
}

@Composable
fun statusColor(state: TransportState): Color {
    val colors = LocalPairDeskColors.current
    return when (state) {
        TransportState.ONLINE -> colors.success
        TransportState.CONNECTING -> colors.warning
        TransportState.OFFLINE -> MaterialTheme.colorScheme.outline
        TransportState.ERROR -> colors.danger
    }
}

/** The network state dot (green online, amber connecting, grey offline, red error). */
@Composable
fun StatusDot(state: TransportState, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(statusColor(state)),
    )
}

/** Dot + label, e.g. in the top bar. */
@Composable
fun NetworkBadge(status: NetworkStatus, modifier: Modifier = Modifier) {
    val label = NetworkTexts.label(status).text()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status.state)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** The brand mark: the launcher icon's logo on the blue → violet gradient. */
@Composable
fun BrandMark(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(LocalPairDeskColors.current.brand),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size * 1.5f),
        )
    }
}

/** A titled card section. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}
