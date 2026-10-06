package app.stopdash.widget

import android.content.Context
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Redraws a placed widget where its countdowns change, and at least once a minute, from what's stored, while something holds it: the app on
 * screen (maintainer, 2026-10-06). A trip On the way follows needs none: each of its updates, every
 * 30 seconds, redraws the widget ([app.stopdash.watch.WatchTripSync]). The widget's countdowns ("3 min")
 * are text drawn at its last render, and nothing else redraws it between its data's boundaries, so
 * they ran minutes behind. A render only: no fetch, no location.
 */
internal object WidgetMinuteTicks {
    private val holders = MutableStateFlow(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    /** Holds the ticks until canceled: call from a coroutine that lives as long as the reason does. */
    suspend fun hold(context: Context): Nothing {
        start(context.applicationContext)
        holders.update { it + 1 }
        try {
            awaitCancellation()
        } finally {
            holders.update { it - 1 }
        }
    }

    private fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            minuteTicks(holders, Instant::now, { WidgetRedraws.nextChangeAt }) {
                try {
                    if (WidgetTrips.placed(context)) redrawWidgets(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logWidgetSnapshotWarning("widget minute redraw failed: ${e::class.simpleName}")
                }
            }
        }
    }
}

/**
 * Runs [redraw] while [held] is above zero, and not at all otherwise (a hold that ends stops the
 * next one): where the countdowns drawn next change ([nextChange]: a train departing, or a count
 * going down), and in any case within a minute (Codex on #612).
 */
internal suspend fun minuteTicks(
    held: StateFlow<Int>,
    now: () -> Instant,
    nextChange: () -> Instant? = { null },
    redraw: suspend () -> Unit,
) {
    held.map { it > 0 }.distinctUntilChanged().collectLatest { on ->
        while (on) {
            val at = now()
            val change = nextChange()?.let { java.time.Duration.between(at, it).toMillis() }?.takeIf { it > 0 }
            delay(minOf(untilNextMinute(at), change ?: Long.MAX_VALUE))
            redraw()
        }
    }
}

/** Milliseconds from [now] to just past the next whole minute, when a countdown's minute turns. */
internal fun untilNextMinute(now: Instant): Long = 60_000L - Math.floorMod(now.toEpochMilli(), 60_000L) + 50L
