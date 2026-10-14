package io.github.azukkia.pairdesk.viewer

import io.github.azukkia.pairdesk.core.json.JsonJs
import kotlinx.serialization.json.JsonArray

/** A [Scheduler] driven by the test: [advance] runs what is due, in time order. */
class ManualScheduler : Scheduler {
    var now = 0L
        private set

    private class Task(val at: Long, val seq: Long, val run: () -> Unit) {
        var cancelled = false
    }

    private val tasks = ArrayList<Task>()
    private var seq = 0L

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(now + delayMs.coerceAtLeast(0), seq++, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    /** Moves the clock forward by [ms], running due tasks (including those they schedule). */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.run()
        }
        now = end
        tasks.removeAll { it.cancelled }
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}

/** Records what an [InputSender] sends, as the JSON text of each message. */
class RecordingSink : InputSink {
    val reliable = ArrayList<String>()
    val pointer = ArrayList<String>()

    /** Every message, in order, prefixed with its channel (`R ` or `P `). */
    val all = ArrayList<String>()

    override fun sendReliable(events: JsonArray) {
        val text = JsonJs.stringify(events)
        reliable += text
        all += "R $text"
    }

    override fun sendPointer(events: JsonArray) {
        val text = JsonJs.stringify(events)
        pointer += text
        all += "P $text"
    }

    fun clear() {
        reliable.clear()
        pointer.clear()
        all.clear()
    }
}
