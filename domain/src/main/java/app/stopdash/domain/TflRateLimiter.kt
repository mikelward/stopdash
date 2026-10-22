package app.stopdash.domain

import kotlin.math.ceil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounds the app's outbound TfL request rate toward the active budget so a dense corner or a new
 * caller can't fire a 429 storm (SPEC *Cost and reliability*). Gated inside every [TflClient]
 * request, below the render path — the network never renders, so a wait only defers a background
 * refresh.
 *
 * [acquire] must be called once per outbound request; it consumes a token, waiting for one when
 * the bucket is empty, up to a bounded wait, then throwing [TflException.RateLimited] rather than
 * hang or fire over-budget.
 */
interface TflRateLimiter {
    /**
     * Consume one request token, suspending (deferring a background refresh, never the render
     * path) until one is available — up to the limiter's bounded wait. Throws
     * [TflException.RateLimited] when a token won't free within that wait, so the caller surfaces
     * the honest rate-limited state (SPEC principle 2) instead of firing a doomed request or
     * hanging. Cancellable: a canceled refresh cancels the wait, and — because a token is consumed
     * only once actually granted — a canceled wait leaves the budget untouched.
     */
    suspend fun acquire()

    /**
     * TfL refused a request for its rate limit (HTTP 429), saying to ask again in [retryAfterMillis]
     * when it said. TfL's count is then past this limiter's (which starts over on a restart while
     * TfL's doesn't), so nothing more is sent before that: a request meanwhile waits for it in
     * [acquire], or, past the bounded wait, is turned away as [TflException.RateLimited] unsent,
     * rather than fired at a limit TfL is still refusing. A limiter with no budget ignores it.
     */
    suspend fun refused(retryAfterMillis: Long?) {}

    companion object {
        /**
         * A no-op limiter — every [acquire] returns at once. The default where no shared bucket is
         * wired (tests, and any client built without one); production passes the shared bucket.
         */
        val UNLIMITED: TflRateLimiter = object : TflRateLimiter {
            override suspend fun acquire() {}
        }
    }
}

/**
 * A shared, best-effort token bucket bounding the app's TfL request rate toward the budget.
 * **One instance is shared across every TfL client in the process** — MainActivity's departures
 * and discovery clients and WidgetRefreshWorker's — because a per-client bucket would each consume
 * the full allowance (Codex, PR #101).
 *
 * The bucket holds up to [capacity] tokens (the burst) and refills at [refillPerMinute]. Size
 * them so **[capacity] + [refillPerMinute] does not exceed the per-minute budget**: a full bucket
 * drained at once plus a minute's refill is the most any 60-second window admits, so an equal
 * capacity and refill (50 + 50) would allow ~2x the budget in the first minute (Codex, PR #108).
 * The burst covers a normal refresh at once; beyond it, [acquire] paces at the refill rate, which
 * **staggers a cold-start fan-out** — a burst larger than the bucket is spread out rather than
 * fired at once, the case that otherwise hits the limit right after an app update (maintainer,
 * 2026-09-22). A request that would wait longer than [maxWaitMillis] throws
 * [TflException.RateLimited] instead of deferring a background refresh indefinitely.
 *
 * TfL's own refusal is taken as the truer count ([refused]): the bucket is emptied, and no token is
 * granted before the wait TfL named (at most [MAX_HOLD_MILLIS]), so a burst that ran past the limit
 * (a cold start right after an update) isn't followed by more requests TfL refuses too.
 *
 * A token is consumed **only when granted**, never reserved ahead: so a wait canceled by a
 * superseded refresh (which MainViewModel does routinely) reclaims nothing and can't accumulate
 * phantom debt against the budget (Codex, PR #108).
 *
 * **In-memory, so best-effort, not a hard guarantee** (Codex, PR #101): it resets on process death
 * (incl. a WorkManager restart) while TfL still counts the prior calls, and does not span a
 * separate widget process. Persisting the accounting across restarts to close that window is a
 * recorded follow-up (`TODO.md`), worth doing only if it stays cheap.
 *
 * Pure of Android — [nowMillis] (a **monotonic** source in production, so a device clock
 * correction can't wedge the limiter) and [sleep] are injected, so it's JVM-testable on a virtual
 * clock. Concurrency-safe: the token accounting runs under a [Mutex]; the wait happens **outside**
 * the lock, so a paced request doesn't block others, and each waiter re-checks after sleeping
 * rather than holding a reservation.
 */
class TokenBucketRateLimiter(
    private val capacity: Int,
    private val refillPerMinute: Int,
    private val maxWaitMillis: Long,
    private val nowMillis: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) : TflRateLimiter {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerMinute > 0) { "refillPerMinute must be positive" }
    }

    private val tokensPerMillis: Double = refillPerMinute / 60_000.0

    private val mutex = Mutex()

    // Tokens available now; starts full so a cold start draws on a burst. Fractional so a token
    // refills smoothly rather than in one-per-interval jumps.
    private var availableTokens: Double = capacity.toDouble()

    // The last time [availableTokens] was brought up to date; unset until the first acquire so the
    // clock's absolute origin doesn't matter (only elapsed time does).
    private var lastRefillMillis: Long = Long.MIN_VALUE

    // No token is granted before this, when TfL last said to wait ([refused]); unset otherwise.
    private var heldUntilMillis: Long = Long.MIN_VALUE

    override suspend fun acquire() {
        val deadline = nowMillis() + maxWaitMillis
        while (true) {
            val waitMillis =
                mutex.withLock {
                    val now = nowMillis()
                    refill(now)
                    // Time left of the wait TfL named, if any.
                    val held = if (heldUntilMillis > now) heldUntilMillis - now else 0L
                    if (held == 0L && availableTokens >= 1.0) {
                        // Consume only on grant — a wait canceled before here reclaims nothing.
                        availableTokens -= 1.0
                        return
                    }
                    // Time until the bucket next holds a whole token, and TfL's wait is over.
                    val refillWait = if (availableTokens >= 1.0) 0L else ceil((1.0 - availableTokens) / tokensPerMillis).toLong()
                    val wait = maxOf(held, refillWait)
                    if (now + wait > deadline) {
                        throw TflException.RateLimited(null)
                    }
                    wait
                }
            if (waitMillis > 0) sleep(waitMillis)
        }
    }

    override suspend fun refused(retryAfterMillis: Long?) {
        mutex.withLock {
            val now = nowMillis()
            refill(now)
            availableTokens = 0.0
            if (retryAfterMillis != null && retryAfterMillis > 0) {
                heldUntilMillis = maxOf(heldUntilMillis, now + minOf(retryAfterMillis, MAX_HOLD_MILLIS))
            }
        }
    }

    private fun refill(now: Long) {
        if (lastRefillMillis == Long.MIN_VALUE) {
            lastRefillMillis = now
            return
        }
        // Guard against a non-monotonic reading (belt-and-braces; production injects a monotonic
        // clock): never refill on a backward step, just carry the timestamp forward.
        if (now <= lastRefillMillis) {
            lastRefillMillis = now
            return
        }
        val elapsed = now - lastRefillMillis
        availableTokens = minOf(capacity.toDouble(), availableTokens + elapsed * tokensPerMillis)
        lastRefillMillis = now
    }

    companion object {
        /** The longest wait TfL can name that's held: its budgets are per minute, so none runs past one. */
        const val MAX_HOLD_MILLIS = 60_000L
    }
}
