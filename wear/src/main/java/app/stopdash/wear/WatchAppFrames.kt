package app.stopdash.wear

import app.stopdash.data.WatchEnvelope
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
        // The envelope as the clock reads it now ([WatchEnvelopeStore.current]), given the one the
        // ticker last judged.
        current: (WatchReceived.Received) -> WatchEnvelope = { it.envelope },
        emit: (TileFrame?) -> Unit,
    ) {
        var received = stored
        while (true) {
            val now = clock()
            // Judged again at each wake, as the tile's schedule is when it's built: the envelope as
            // the clock reads it now, however it's been set since it arrived, the ticker's own run
            // included ([WatchReceived.Received.current]), so a stop fetched before the clock was set
            // back mid-run keeps its real age rather than turning fresh again once the clock catches
            // up with its stamp (Codex, PR #371). A check or a stop dated after now (the clock moved
            // back) stays untrusted from then on, since each wake judges what the last one kept, so a
            // later redraw can't start showing it once its instant passes.
            received = (received as? WatchReceived.Received)
                ?.let { it.copy(envelope = current(it).distrustingFuture(now), arrivedIn = null) }
                ?: received
            emit(at(received, now, topology))
            val next = TileTimeline.nextChange((received as? WatchReceived.Received)?.envelope, now) ?: return
            delay(Duration.between(clock(), next).toMillis().coerceAtLeast(0))
        }
    }
}
