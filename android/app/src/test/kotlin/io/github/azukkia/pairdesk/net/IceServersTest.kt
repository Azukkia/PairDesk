package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.data.AppSettings
import io.github.azukkia.pairdesk.data.NetworkMode
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IceServersTest {
    @Test
    fun `defaults are the desktop configuration`() {
        assertEquals(
            listOf(
                IceServerSpec(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")),
                IceServerSpec(listOf("stun:stun.cloudflare.com:3478")),
            ),
            IceServers.DEFAULT,
        )
    }

    @Test
    fun `parses the TURN servers of a private server`() {
        val json = JsonJs.parse(
            """[
              {"urls":["turn:turn.example.com:3478?transport=udp","turns:turn.example.com:5349"],"username":"1700000000:abc","credential":"secret"},
              {"urls":"stun:turn.example.com:3478"},
              {"url":"turn:legacy.example.com"},
              {"urls":["https://not-ice"]},
              {"urls":42},
              "garbage"
            ]""",
        ) as JsonArray
        val parsed = IceServers.parse(json)
        assertEquals(3, parsed.size)
        assertEquals(IceServerSpec(listOf("turn:turn.example.com:3478?transport=udp", "turns:turn.example.com:5349"), "1700000000:abc", "secret"), parsed[0])
        assertEquals(IceServerSpec(listOf("stun:turn.example.com:3478")), parsed[1])
        assertEquals(IceServerSpec(listOf("turn:legacy.example.com")), parsed[2])

        val combined = IceServers.combine(json)
        assertEquals(IceServers.DEFAULT, combined.take(2))
        assertEquals(parsed, combined.drop(2))
        assertTrue(IceServers.parse(null).isEmpty())
    }

    @Test
    fun `transport choice`() {
        assertEquals(TransportConfig.PublicRelays(), TransportConfig.from(AppSettings()))
        assertEquals(
            TransportConfig.Server("wss://pairdesk.example.com/ws"),
            TransportConfig.from(AppSettings(networkMode = NetworkMode.SERVER, serverUrl = "wss://pairdesk.example.com/ws")),
        )
        // An invalid URL falls back to the public relays.
        assertEquals(TransportConfig.PublicRelays(), TransportConfig.from(AppSettings(networkMode = NetworkMode.SERVER, serverUrl = "")))
    }
}
