package app.stopdash.watch

import app.stopdash.data.WatchEnvelopes
import app.stopdash.data.WatchPayload
import app.stopdash.domain.AlertBehind
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
    ): Outcome = attempt(snapshot, starred, hiddenModes, selected, routeLines, force, emptyIfNone).also(::report)

    /** The last link state [report] logged, so a state that holds isn't logged on every publish. */
    private var reported: String? = null

    /**
     * Logs where the watch link stands: whether a paired watch has the app, and whether the latest
     * snapshot was queued for it. Without this a link that never forms (the watch keeps saying
     * "Open StopDash on your phone") leaves no trace in a bug report. A put only queues the item for
     * the Data Layer to sync, so it says "queued", never "delivered".
     *
     * A queue is logged every time: it happens only when there's something new to send, so it costs
     * a line per change, and no earlier line can hide it. The states that hold without anything
     * happening (no watch, nothing stored, nothing new) are logged when they change. An unchanged
     * envelope still says a watch has the app, so a process that starts with nothing new to send
     * isn't silent about it. Failures go through [failed], which forgets the last state.
     */
    @Synchronized
    private fun report(outcome: Outcome) {
        val line = when (outcome) {
            Outcome.Published -> QUEUED
            // After a queue, nothing new is no news.
            Outcome.Unchanged -> if (reported == QUEUED) return else "a paired watch has the app, nothing new to queue"
            Outcome.NoWatch -> "no paired watch has the app"
            Outcome.NothingStored -> "nothing stored to send yet"
            Outcome.Failed -> return
        }
        if (line == reported && outcome != Outcome.Published) return
        reported = line
        log(line)
    }

    /**
     * Logs a failed publish ([reason]: the failure, as a type name, never user data) and forgets the
     * last state, so whatever follows (a retry that queues, or finds nothing new) is logged and the
     * log never ends on a failure that was recovered from. Every failure path goes through here, the
     * caller's own (a stored state it couldn't read) as well as this class's.
     */
    @Synchronized
    fun failed(reason: String) {
        reported = null
        log(reason)
    }

    private suspend fun attempt(
        snapshot: DeparturesSnapshot?,
        starred: Set<StarredRow>,
        hiddenModes: Set<String>,
        selected: Set<StarredRow>,
        routeLines: Map<String, List<RoutePattern>>,
        force: Boolean,
        emptyIfNone: Boolean,
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
            failed("watch publish failed: ${e::class.simpleName}")
            Outcome.Failed
        }
    }

    companion object {
        private const val QUEUED = "queued for the watch"

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
         * [hiddenModes], the [dismissed] alerts, the refreshed [routeLines] or the app's verdicts on
         * alerts behind a stop ([alertsBehind]): every write, from any
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
            // The app's verdicts that a bus alert lies behind a stop: applied to the envelope as
            // dismissals are ([DeparturesSnapshot.withAlertsBehind]), so a change is a cue too.
            alertsBehind: Flow<Set<AlertBehind>> = flowOf(emptySet()),
            window: Duration = COALESCE,
        ): Flow<Pair<DeparturesSnapshot?, Set<StarredRow>>> =
            combine(combine(snapshots, starred, ::Pair), hiddenModes, dismissed, routeLines, alertsBehind) { state, _, _, _, _ -> state }
                .debounce(window)
    }
}
