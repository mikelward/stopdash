package app.stopdash.wear

import app.stopdash.domain.RouteTopology
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * What the watch app shows, and when it changes (dev-docs/wear-os.md *Staleness on the watch*).
 * The app lists the tile's lines, every one of them since it scrolls, from the same pure
 * (envelope, now) builder; while it's in the foreground, [tick] re-renders it at each instant a
 * frame can change, with no polling and no network.
 */
object WatchAppFrames {
    /** The app's frame for [received] at [now]; null until the stored envelope has been read. */
    fun at(received: WatchReceived, now: Instant, topology: RouteTopology = RouteTopology.EMPTY): TileFrame? =
        when (received) {
            WatchReceived.Loading -> null
            WatchReceived.NeverSynced -> TileFrame.NeverSynced
            is WatchReceived.Received -> TileTimeline.frame(received.envelope, now, topology, budget = TileTimeline.UNBOUNDED)
        }

    /**
     * Emits [received]'s frame now, then again at each instant it can change
     * ([TileTimeline.nextChange]): each countdown minute, each departure, each stop's staleness
     * boundary. It ends once every stop is stale (nothing changes after that) and runs until
     * cancelled otherwise, so the caller runs it only while the app is in the foreground. [clock]
     * is injected so a test can drive it past a departure and a boundary.
     */
    suspend fun tick(
        stored: WatchReceived,
        topology: RouteTopology,
        clock: () -> Instant,
        emit: (TileFrame?) -> Unit,
    ) {
        // As the tile's schedule does: a check dated after the start (the clock moved back) stays
        // untrusted for the whole run, so a later redraw can't start showing it once its instant
        // passes, with nothing scheduled at its expiry to take it away again.
        val received = (stored as? WatchReceived.Received)
            ?.let { it.copy(envelope = it.envelope.withoutFutureChecks(clock())) }
            ?: stored
        val envelope = (received as? WatchReceived.Received)?.envelope
        while (true) {
            val now = clock()
            emit(at(received, now, topology))
            val next = TileTimeline.nextChange(envelope, now) ?: return
            delay(Duration.between(clock(), next).toMillis().coerceAtLeast(0))
        }
    }
}
