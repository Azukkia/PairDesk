package io.github.azukkia.pairdesk.core.signaling

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Global brute-force protection for incoming password attempts
 * (AuthLimiter in src/main/signaling/signaling.js): [threshold] failures within
 * [windowMs] lock new attempts for [baseLockMs], doubling with every further
 * failure up to [maxLockMs]. Thread-safe.
 */
class AuthLimiter(
    val windowMs: Long = 30 * 60_000L,
    val threshold: Int = 5,
    val baseLockMs: Long = 30_000L,
    val maxLockMs: Long = 30 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failures = ArrayList<Long>()

    private fun prune() {
        val limit = now() - windowMs
        failures.removeAll { it <= limit }
    }

    @Synchronized
    fun recordFailure() {
        prune()
        failures.add(now())
    }

    @Synchronized
    fun recordSuccess() {
        failures.clear()
    }

    val recentFailures: Int
        @Synchronized get() {
            prune()
            return failures.size
        }

    /** Milliseconds before a new attempt is accepted (0 = not locked). */
    @Synchronized
    fun lockedFor(): Long {
        prune()
        val n = failures.size
        if (n < threshold) return 0
        val lock = min(maxLockMs.toDouble(), baseLockMs * 2.0.pow(n - threshold))
        return max(0.0, failures[n - 1] + lock - now()).toLong()
    }

    /** Seconds to announce in `denied.retryIn` (rounded up). */
    fun retryInSeconds(lockedMs: Long): Long = ceil(lockedMs / 1000.0).toLong()
}
