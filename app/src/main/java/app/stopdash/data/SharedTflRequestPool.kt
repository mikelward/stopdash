package app.stopdash.data

import app.stopdash.domain.TflRequestPool

/**
 * The process-wide cap on in-flight TfL requests, shared by every client in the process — the
 * app's departures and discovery clients and the widget worker's — the same sharing shape as
 * [SharedTflRateLimiter].
 */
object SharedTflRequestPool {
    /**
     * At most this many TfL requests in flight at once (maintainer, 2026-09-23: 10, up from 8).
     * Enough to take a normal refresh's per-stop requests in one or two round trips rather than one
     * per request, while staying a polite load on TfL and on a phone's radio. Kept below the keyless
     * limiter's burst, so the pool, not the limiter, shapes a normal refresh.
     */
    const val MAX_CONCURRENT = 10

    val pool: TflRequestPool by lazy {
        TflRequestPool(MAX_CONCURRENT, onSlowWait = { ms -> logNetworkWarning("waited $ms ms for a free request slot") })
    }
}
