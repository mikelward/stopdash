package app.stopdash.watch

import app.stopdash.data.WatchEnvelopes
import app.stopdash.data.WatchPayload
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.Dismissals
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.StarredRow
import app.stopdash.domain.SteadyClock
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf

/** The phone's side of the Data Layer, a seam so [WatchPublisher] is testable without Play services. */
interface WatchChannel {
    /** Whether a paired watch, connected or not, has the watch app installed. */
    suspend fun watchInstalled(): Boolean

    /** Writes [payload] as the latest snapshot item; the Data Layer syncs it when the watch is in reach. */
    suspend fun put(payload: WatchPayload)
}

/** The durable record of what was last published, so a lost publish is noticed on the next start. */
interface PublishMarker {
    fun get(): String?

    fun set(hash: String)
}

/**
 * Publishes the widget's snapshot to the watch (dev-docs/wear-os.md *Sync*), only when a paired
 * watch has the app, and only when the envelope differs from the last one published. The marker
 * is written only after a successful write, so a publish lost to a failure or to process death is
 * retried by the next attempt rather than suppressed.
 */
class WatchPublisher(
    private val channel: WatchChannel,
    private val marker: PublishMarker,
    private val log: (String) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    sealed interface Outcome {
        data object Published : Outcome

        /** The same envelope was already published. */
        data object Unchanged : Outcome

        /** No snapshot has been stored yet: the watch keeps saying "Open StopDash on your phone". */
        data object NothingStored : Outcome

        /** No paired watch has the app: nothing leaves the phone. */
        data object NoWatch : Outcome

        /** The Data Layer failed; the caller schedules a retry. */
        data object Failed : Outcome
    }

    /**
     * Publishes [snapshot]. With none stored, nothing is sent, unless [emptyIfNone]: then an empty
     * one is, so a watch still holding stops the phone no longer has (its data was cleared) shows
     * none rather than keep them.
     */
    suspend fun publish(
        snapshot: DeparturesSnapshot?,
        starred: Set<StarredRow>,
        hiddenModes: Set<String> = emptySet(),
        selected: Set<StarredRow> = emptySet(),
        // The route lines a refresh took over the asset, for the watch to group by as the widget does.
        routeLines: Map<String, List<RoutePattern>> = emptyMap(),
        force: Boolean = false,
        emptyIfNone: Boolean = false,
    ): Outcome {
        val snapshot = snapshot ?: if (emptyIfNone) DeparturesSnapshot(emptyList(), SteadyClock.stamp(now())) else return Outcome.NothingStored
        return try {
            // Asked first, so a phone with no watch app never builds or hashes an envelope.
            if (!channel.watchInstalled()) return Outcome.NoWatch
            val payload = WatchEnvelopes.build(snapshot, starred, selected = selected, hiddenModes = hiddenModes, routeLines = routeLines, now = now())
            val hash = sha256(payload.bytes)
            if (!force && hash == marker.get()) return Outcome.Unchanged
            channel.put(payload)
            marker.set(hash)
            Outcome.Published
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The failure type only: the payload is the user's stops, never logged.
            log("watch publish failed: ${e::class.simpleName}")
            Outcome.Failed
        }
    }

    companion object {
        /** A burst of writes (one refresh saves several times) publishes once, at most this often. */
        val COALESCE: Duration = 2.seconds

        /** The waits before each restart of a collection that failed. */
        val RESTARTS: List<Duration> = listOf(30.seconds, 2.minutes, 10.minutes)

        /**
         * Runs [collect], restarting it after each failure (logged, the failure type only) once per
         * wait in [waits], then giving up. Cancellation passes straight through; a [collect] that
         * returns normally ends it.
         */
        suspend fun keepCollecting(waits: List<Duration> = RESTARTS, log: (String) -> Unit, collect: suspend () -> Unit) {
            for (attempt in 0..waits.size) {
                try {
                    collect()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = waits.getOrNull(attempt)
                    log("sync stopped: ${e::class.simpleName}" + if (wait == null) ", giving up" else ", restarting in $wait")
                    wait?.let { delay(it) }
                }
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * One publish request per settled change to the stored [snapshots], the [starred] rows, the
         * [hiddenModes], the [dismissed] alerts or the refreshed [routeLines]: every write, from any
         * writer, with bursts coalesced to the latest inside [window]. The first value is the state
         * at start, which the durable marker compares against.
         */
        @OptIn(FlowPreview::class)
        fun requests(
            snapshots: Flow<DeparturesSnapshot?>,
            starred: Flow<Set<StarredRow>>,
            hiddenModes: Flow<Set<String>> = flowOf(emptySet()),
            // The alerts the user dismissed: a change is a cue too, since the envelope is built with
            // them applied ([DeparturesSnapshot.withDismissals]).
            dismissed: Flow<Dismissals> = flowOf(Dismissals.NONE),
            // The route lines a refresh took over the asset: the envelope carries them, so a refresh
            // that changes them is a cue even when nothing else moves.
            routeLines: Flow<Map<String, List<RoutePattern>>> = flowOf(emptyMap()),
            window: Duration = COALESCE,
        ): Flow<Pair<DeparturesSnapshot?, Set<StarredRow>>> =
            combine(snapshots, starred, hiddenModes, dismissed, routeLines) { snapshot, stars, _, _, _ -> snapshot to stars }.debounce(window)
    }
}
