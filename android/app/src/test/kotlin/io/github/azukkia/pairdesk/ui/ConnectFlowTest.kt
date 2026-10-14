package io.github.azukkia.pairdesk.ui

import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.core.signaling.Credential
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.SignalingEvent
import io.github.azukkia.pairdesk.core.transport.MemoryBus
import io.github.azukkia.pairdesk.net.ConnectRequest
import io.github.azukkia.pairdesk.net.ConnectResult
import io.github.azukkia.pairdesk.net.ConnectStep
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.net.SessionKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectFlowTest {
    private val request = ConnectRequest("123 456 789", "abc234", remember = false, kind = SessionKind.CONTROL)

    @Test
    fun `progress then failure, retry`() {
        val scope = TestScope(StandardTestDispatcher())
        val results = ArrayDeque<CompletableDeferred<ConnectResult>>()
        var calls = 0
        var reportStep: ((ConnectStep) -> Unit)? = null
        val flow = ConnectFlow(scope, { _, onStep ->
            calls++
            reportStep = onStep
            CompletableDeferred<ConnectResult>().also(results::addLast).await()
        })

        assertTrue(flow.start(request))
        assertEquals(ConnectUiState.Running("123456789", SessionKind.CONTROL, null), flow.state.value)
        assertFalse(flow.start(request), "one attempt at a time")
        scope.runCurrent()
        reportStep!!(ConnectStep.WAITING_APPROVAL)
        assertEquals(ConnectStep.WAITING_APPROVAL, (flow.state.value as ConnectUiState.Running).step)

        results.removeFirst().complete(ConnectResult.Failure("locked", 30))
        scope.advanceUntilIdle()
        val failed = flow.state.value as ConnectUiState.Failed
        assertEquals("locked", failed.code)
        assertEquals(30L, failed.retryIn)
        assertTrue(failed.canRetry)
        assertFalse(failed.isPasswordError)

        assertTrue(flow.retry())
        scope.runCurrent()
        assertEquals(2, calls)
        results.removeFirst().complete(ConnectResult.Failure("auth"))
        scope.advanceUntilIdle()
        assertTrue((flow.state.value as ConnectUiState.Failed).isPasswordError)
        flow.dismiss()
        assertEquals(ConnectUiState.Idle, flow.state.value)
    }

    @Test
    fun `cancel abandons the attempt`() {
        val scope = TestScope(StandardTestDispatcher())
        val never = CompletableDeferred<ConnectResult>()
        val flow = ConnectFlow(scope, { _, _ -> never.await() })
        flow.start(request)
        scope.runCurrent()
        flow.cancel()
        assertEquals(ConnectUiState.Idle, flow.state.value)
        scope.advanceUntilIdle()
        assertEquals(ConnectUiState.Idle, flow.state.value)
        assertTrue(flow.start(request), "a new attempt can start")
    }

    @Test
    fun `success is consumed once, a late success is ended`() = runBlocking {
        val bus = MemoryBus()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prs = ByteArray(32) { 1 }
        val host = Signaling(bus.transport().apply { start("123456789") }, "123456789", { listOf(Credential("temp", prs)) })
        val ctrl = Signaling(bus.transport().apply { start("987654321") }, "987654321")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.events.collect { e ->
                if (e is SignalingEvent.Incoming) host.accept(e.session.sid, SessionMessages.accepted("PC", Caps(), "1.2.0", platform = "linux"))
            }
        }
        try {
            suspend fun session() = OutgoingSession(ctrl.connect("123456789", prs), SessionKind.CONTROL, prs)

            val test = TestScope(StandardTestDispatcher())
            val established = session()
            val flow = ConnectFlow(test, { _, _ -> ConnectResult.Success(established) })
            flow.start(request)
            test.advanceUntilIdle()
            assertEquals(ConnectUiState.Connected(established), flow.state.value)
            assertSame(established, flow.consume())
            assertEquals(null, flow.consume())
            assertEquals(ConnectUiState.Idle, flow.state.value)

            // The user cancels while the host accepts: the session must not stay open.
            val late = session()
            val gate = CompletableDeferred<Unit>()
            val abandoned = CompletableDeferred<OutgoingSession>()
            val lateFlow = ConnectFlow(
                test,
                { _, _ ->
                    withContext(NonCancellable) { gate.await() }
                    ConnectResult.Success(late)
                },
                abandon = { abandoned.complete(it) },
            )
            lateFlow.start(request)
            test.runCurrent()
            lateFlow.cancel()
            gate.complete(Unit)
            test.advanceUntilIdle()
            assertEquals(ConnectUiState.Idle, lateFlow.state.value)
            assertSame(late, abandoned.await())
        } finally {
            host.dispose()
            ctrl.dispose()
            scope.cancel()
            bus.shutdown()
        }
    }
}
