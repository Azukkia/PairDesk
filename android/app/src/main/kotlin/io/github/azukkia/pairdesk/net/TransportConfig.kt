package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.core.PairDeskDefaults
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.core.transport.MqttTransport
import io.github.azukkia.pairdesk.core.transport.SignalingTransport
import io.github.azukkia.pairdesk.core.transport.WsTransport
import io.github.azukkia.pairdesk.data.AppSettings
import io.github.azukkia.pairdesk.data.NetworkMode
import io.github.azukkia.pairdesk.data.SettingsStore

/** Which transport to use (Network.describeTransport of the desktop). */
sealed interface TransportConfig {
    data class PublicRelays(val brokers: List<String> = PairDeskDefaults.PUBLIC_BROKERS) : TransportConfig

    data class Server(val url: String) : TransportConfig

    companion object {
        fun from(settings: AppSettings): TransportConfig =
            if (settings.networkMode == NetworkMode.SERVER && SettingsStore.isValidServerUrl(settings.serverUrl)) {
                Server(settings.serverUrl)
            } else {
                PublicRelays()
            }
    }
}

/** Creates the transport of a [TransportConfig] (replaced by a memory bus in tests). */
fun interface TransportFactory {
    /** [onIdTaken] is called when a private server refuses this device's ID. */
    fun create(config: TransportConfig, log: Logger, onIdTaken: () -> Unit): SignalingTransport

    companion object {
        val DEFAULT = TransportFactory { config, log, onIdTaken ->
            when (config) {
                is TransportConfig.PublicRelays -> MqttTransport(brokers = config.brokers, log = log)
                is TransportConfig.Server -> WsTransport(url = config.url, log = log, onIdTaken = onIdTaken)
            }
        }

        /** Stops [transport] for good (threads included). */
        fun dispose(transport: SignalingTransport) {
            when (transport) {
                is MqttTransport -> transport.dispose()
                is WsTransport -> transport.dispose()
                else -> transport.stop()
            }
        }
    }
}
