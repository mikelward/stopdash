package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import app.stopdash.domain.alertFingerprint
import java.io.InputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * The DataStore-backed [SnapshotStore]. DataStore serializes reads and writes to one file
 * and survives process death, which is exactly what the cross-session restore and the
 * widget need: the app writes the last-good snapshot on every refresh, and a later launch —
 * or the widget, in the same app process — reads it back.
 *
 * The store adds no off-device channel of its own: it is a private app file, so it carries
 * the real stop and departure data the restore needs, unredacted, the same as any on-device
 * cache. It is not, however, strictly device-local — the app allows Android backup, so this
 * file can travel via the platform's backup and device-to-device transfer like the rest of
 * the app's data (SPEC §12 / *Privacy*). That is a platform path the user controls, not data
 * this code sends anywhere.
 */
class DataStoreSnapshotStore internal constructor(
    private val dataStore: DataStore<PersistedSnapshot?>,
    // For merging line checks: one dated after it predates a clock rollback and loses.
    private val clock: () -> Instant = Instant::now,
) : SnapshotStore {
    // Recent dismissals not yet in the stored snapshot: one made before anything is stored (first
    // launch, the first save still in flight) has no snapshot to be recorded in, and must still
    // reach that save. A write reads them inside its transform, under DataStore's write lock, so a
    // dismissal recorded before its own write either lands in this write or runs after it; folds
    // them in ([recentWith]) and, once stored, drops them here ([stored]), so the stored copy alone
    // decides when one stops being replayed.
    private val pendingDismissals = AtomicReference<List<PersistedRecentDismissal>>(emptyList())

    private fun recentWith(
        current: PersistedSnapshot?,
        pending: List<PersistedRecentDismissal>,
    ): List<PersistedRecentDismissal> = (current?.recentDismissals.orEmpty() + pending).distinct()

    private fun stored(pending: List<PersistedRecentDismissal>) {
        if (pending.isNotEmpty()) pendingDismissals.updateAndGet { it - pending.toSet() }
    }

    override suspend fun load(): DeparturesSnapshot? = dataStore.data.first()?.toDomain()

    /** Every stored snapshot as it's written, from any writer (the app, the widget's worker). */
    fun snapshots(): Flow<DeparturesSnapshot?> = dataStore.data.map { it?.toDomain() }

    override suspend fun save(snapshot: DeparturesSnapshot) {
        val now = clock()
        var pending = emptyList<PersistedRecentDismissal>()
        // A replacement still keeps the recent dismissals, so a check of a just-dismissed alert this
        // writer fetched before the dismissal comes out dismissed.
        dataStore.updateData { current ->
            pending = pendingDismissals.get()
            withRecentDismissals(snapshot.toPersisted(), recentWith(current, pending), now)
        }
        stored(pending)
    }

    override suspend fun saveIfStopsMatch(
        snapshot: DeparturesSnapshot,
        expectedStopIds: List<String>,
    ): Boolean {
        val desired = snapshot.toPersisted()
        // Captured once, so the transform stays a pure function of `current` if DataStore re-runs it.
        val now = clock()
        var pending = emptyList<PersistedRecentDismissal>()
        // The transform runs under DataStore's write lock, so the compare and the write are one
        // atomic step — no reload→save window a concurrent writer could slip through. Keep the
        // stored snapshot untouched when its stop set no longer matches what the caller worked
        // from (a newer write changed it), discarding the caller's now-stale result. The block
        // stays a pure function of `current` and the pending dismissals it reads (it only records
        // which it read), since DataStore may re-run it on a write conflict.
        // The widget's journeys are the app's to change, never this caller's: a match keeps the
        // stored ones, so a slow worker can't restore a pin the app has since dropped or changed.
        // So are each stop's nearer places ([app.stopdash.domain.Terminating]): they follow the
        // rider's location, which only the app knows, so a worker that loaded an older location's
        // snapshot can't restore its places over the app's newer ones.
        fun keepingAppsOwn(current: PersistedSnapshot, pending: List<PersistedRecentDismissal>): PersistedSnapshot {
            val nearerById = current.stops.associate { it.stopId to (it.nearerIds to it.nearerNames) }
            return desired.copy(
                journeys = current.journeys,
                journeyOnlyStopIds = current.journeyOnlyStopIds,
                // Which requested stops are missing is the app's too: the worker only refetches the
                // stops it holds, so an older worker result can't clear a newer missing set.
                missingStopIds = current.missingStopIds,
                stops = desired.stops.map { stop ->
                    nearerById[stop.stopId]?.let { (ids, names) -> stop.copy(nearerIds = ids, nearerNames = names) } ?: stop
                },
                // Line checks per line, newest wins: the app may have checked a line since this
                // caller loaded, and an older verdict mustn't replace it.
                lineStatuses = newestStatuses(current.lineStatuses, desired.lineStatuses, desired.stops, now),
            ).let { withRecentDismissals(it, recentWith(current, pending), now) }
        }
        val written = dataStore.updateData { current ->
            pending = pendingDismissals.get()
            if (current != null && current.matchesStops(expectedStopIds)) keepingAppsOwn(current, pending) else current
        }
        val matched = written != null && written == keepingAppsOwn(written, pending)
        if (matched) stored(pending)
        return matched
    }

    override suspend fun updateNearer(nearer: Map<String, Terminating.Nearer>) {
        if (nearer.isEmpty()) return
        // A pure function of `current` and the immutable map, atomic under the write lock like
        // [pruneStops].
        dataStore.updateData { current ->
            if (current == null) return@updateData null
            val stops = current.stops.map { stop ->
                val n = nearer[stop.stopId] ?: return@map stop
                stop.copy(nearerIds = n.ids.sorted(), nearerNames = n.names.sorted())
            }
            if (stops == current.stops) current else current.copy(stops = stops)
        }
    }

    override suspend fun dismissLineStatus(alert: DismissedAlert) {
        // Pure function of `current` (DataStore may re-run it), atomic with the read, so a check a
        // concurrent refresh just wrote is judged as it now stands.
        // The dismissal is also remembered for a few minutes ([withRecentDismissals]), so a writer
        // holding a check fetched before it (the widget's worker, mid-refresh) can't save that check
        // back undismissed.
        val now = clock()
        val recent = PersistedRecentDismissal(alert.alertKey, alertFingerprint(alert), now.toEpochMilli())
        val window = RECENT_DISMISSAL_WINDOW.toMillis()
        pendingDismissals.updateAndGet { list -> list.filter { now.toEpochMilli() - it.atMillis < window } + recent }
        var pending = emptyList<PersistedRecentDismissal>()
        val written = dataStore.updateData { current ->
            pending = pendingDismissals.get()
            // Nothing stored yet: the pending copy carries it to the first save.
            if (current == null || current.version !in PersistedSnapshot.READABLE_VERSIONS) return@updateData current
            withRecentDismissals(current, recentWith(current, pending), now)
        }
        if (written != null && written.version in PersistedSnapshot.READABLE_VERSIONS) stored(pending)
    }

    override suspend fun pruneStops(departedStopIds: Collection<String>) {
        if (departedStopIds.isEmpty()) return
        val departed = departedStopIds.toSet()
        // Pure function of `current` (DataStore may re-run it on a write conflict, so it captures
        // only the immutable `departed`): drop the departed stops and re-derive the whole-snapshot
        // stamp from what remains, leaving the kept stops at their own ages. Atomic with the read
        // under the write lock, so a concurrent save can't be lost through a reload→save window.
        dataStore.updateData { current ->
            if (current == null) return@updateData null
            // A departed stop a pinned journey starts from stays, as a journey-only stop: the widget
            // shows (and refreshes) just its journey there.
            val origins = current.journeys.mapTo(HashSet()) { it.originId }
            val kept = current.stops.filterNot { it.stopId in departed && it.stopId !in origins }
            val demoted = current.stops.map { it.stopId }.filter { it in departed && it in origins }
            val unchanged = kept.size == current.stops.size && current.missingStopIds.none { it in departed }
            if (unchanged && demoted.all { it in current.journeyOnlyStopIds }) {
                current
            } else {
                current.copy(
                    // Journey-only stops are a version-2 field: an older reader mustn't take them.
                    version = PersistedSnapshot.CURRENT_VERSION,
                    stops = kept,
                    // A departed stop is no longer one the widget should show, missing or not.
                    missingStopIds = current.missingStopIds.filterNot { it in departed },
                    fetchedAtMillis = kept.maxOfOrNull { it.fetchedAtMillis } ?: current.fetchedAtMillis,
                    journeyOnlyStopIds = (current.journeyOnlyStopIds + demoted).distinct(),
                    // A departed stop's lines go with it, unless a kept stop shows them too.
                    lineStatuses = linesOfPersisted(kept).let { lines -> current.lineStatuses.filter { it.lineId in lines } },
                )
            }
        }
    }

    override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
        val desired = snapshot.toPersisted()
        val now = clock()
        var pending = emptyList<PersistedRecentDismissal>()
        // Pure function of `current` and the pending dismissals, atomic with the read under the
        // write lock (see pruneStops).
        dataStore.updateData { current ->
            pending = pendingDismissals.get()
            // A newer build's file isn't this one's to rewrite piecemeal: replace it outright.
            val journeys = current?.takeIf { it.version in PersistedSnapshot.READABLE_VERSIONS }?.journeys.orEmpty()
            val origins = journeys.mapTo(HashSet()) { it.originId }
            // A journey-only stop is kept only while a stored pin starts from it.
            val own = keepingFresher(current, desired).let { merged ->
                merged.copy(stops = merged.stops.filter { it.stopId !in desired.journeyOnlyStopIds || it.stopId in origins })
            }
            val ids = own.stops.mapTo(HashSet()) { it.stopId }
            // Every stored pin's origin stays: one the caller doesn't hold now is journey-only; one
            // it holds keeps the caller's class.
            // A carried origin the caller asked for as nearby and couldn't get stands in for a failed
            // refresh, so it's marked unrefreshed, as a stop kept from a failed fetch is.
            val carried = current?.stops.orEmpty().filter { it.stopId in origins && it.stopId !in ids }
                .map { if (it.stopId in desired.missingStopIds) it.copy(arrivalsFresh = false) else it }
            val stops = own.stops + carried
            own.copy(
                stops = stops,
                // Stamped from the stops kept — carried origins too (the worker may have refreshed one
                // since), never a dropped one the widget won't show.
                fetchedAtMillis = stops.maxOfOrNull { it.fetchedAtMillis } ?: desired.fetchedAtMillis,
                journeys = journeys,
                // A carried origin the caller asked for as nearby (and couldn't get) is a nearby stop
                // recovered from its stored copy, not a journey-only one.
                journeyOnlyStopIds = (
                    desired.journeyOnlyStopIds.filter { it in origins } +
                        carried.map { it.stopId }.filterNot { it in desired.missingStopIds }
                    ).distinct(),
                // A stop the stored copy still holds, carried or not, isn't missing, whoever fetched it.
                missingStopIds = desired.missingStopIds.filter { id -> stops.none { it.stopId == id } },
                // Newest check per line of the two writers', for the lines the kept stops show (a
                // carried origin's too).
                lineStatuses = newestStatuses(
                    current?.takeIf { it.version in PersistedSnapshot.READABLE_VERSIONS }?.lineStatuses.orEmpty(),
                    desired.lineStatuses,
                    stops,
                    now,
                ),
            ).let { merged ->
                withRecentDismissals(
                    merged,
                    recentWith(current?.takeIf { it.version in PersistedSnapshot.READABLE_VERSIONS }, pending),
                    now,
                )
            }
        }
        stored(pending)
    }

    private fun keepingFresher(current: PersistedSnapshot?, desired: PersistedSnapshot): PersistedSnapshot {
        val stored = current?.stops.orEmpty().associateBy { it.stopId }
        val stops = desired.stops.map { stop ->
            // Newer wins; at the same age the stored copy wins if it marks a failed refresh, so
            // aged arrivals are never re-marked live.
            stored[stop.stopId]?.takeIf {
                it.fetchedAtMillis > stop.fetchedAtMillis ||
                    (it.fetchedAtMillis == stop.fetchedAtMillis && !it.arrivalsFresh)
            } ?: stop
        }
        return desired.copy(
            stops = stops,
            fetchedAtMillis = maxOf(desired.fetchedAtMillis, stops.maxOfOrNull { it.fetchedAtMillis } ?: 0L),
        )
    }

    override suspend fun updateWidgetJourneys(report: WidgetJourneysReport, origins: List<StopArrivals>) {
        // Pure function of `current`, atomic with the read under the write lock (see pruneStops),
        // so every report builds on the pins as stored, whichever writer came before.
        dataStore.updateData { current ->
            // A newer build's file: leave it rather than rewrite it in this build's format.
            if (current != null && current.version !in PersistedSnapshot.READABLE_VERSIONS) return@updateData current
            // Written as the current version: journeys and journey-only stops are version-2 fields.
            // The domain form has no recent dismissals, so they're carried over from the stored copy.
            WidgetJourneys.apply(current?.toDomain(), report, origins)?.toPersisted()
                ?.copy(recentDismissals = current?.recentDismissals.orEmpty())
        }
    }

    companion object {
        /** The file name DataStore owns under the app's files dir. */
        private const val FILE_NAME = "departures-snapshot.json"

        @Volatile
        private var instance: DataStoreSnapshotStore? = null

        /**
         * The process-wide store. DataStore permits only **one** active instance per file per
         * process (a second throws), and both the app and the widget read this file in the
         * same app process, so the [DataStore] is created once here and shared. Built from the
         * application context so it outlives any one Activity or widget update.
         *
         * [warn] is the sanitized log seam (no-op until the shared on-device logger lands, the
         * same as the ViewModel's): a corrupt or truncated file is logged and then discarded
         * by the corruption handler rather than swallowed, so the failure leaves a trace and
         * the next read starts clean. Only the first caller's [warn] is used, since the store
         * is a process singleton.
         */
        fun from(context: Context, warn: (String) -> Unit = {}): DataStoreSnapshotStore =
            instance ?: synchronized(this) {
                instance ?: DataStoreSnapshotStore(
                    DataStoreFactory.create(
                        serializer = SnapshotSerializer,
                        corruptionHandler = ReplaceFileCorruptionHandler {
                            // Sanitized: the exception can quote the malformed JSON, which
                            // holds stop/departure data, so only the fact is logged, never its
                            // message (SPEC *Privacy*). Returning the default (null) replaces
                            // the bad file so the next launch reads clean.
                            warn("persisted snapshot was unreadable and has been discarded")
                            null
                        },
                    ) {
                        context.applicationContext.dataStoreFile(FILE_NAME)
                    },
                ).also { instance = it }
            }
    }
}

/**
 * True when the stored snapshot exists and holds exactly [ids], in order — the identity a
 * conditional save compares against. Order is part of it: the nearby set is distance-sorted, so a
 * reordering is a different set, and a null (nothing stored) never matches.
 */
private fun PersistedSnapshot?.matchesStops(ids: List<String>): Boolean =
    this != null && stops.map { it.stopId } == ids

/**
 * Reads and writes [PersistedSnapshot] as JSON. An empty file is "nothing saved yet" and
 * reads back as null; a **corrupt** one throws [CorruptionException] rather than being
 * silently taken for empty, so the failure is surfaced — the store's corruption handler
 * logs it (sanitized) and replaces the file, instead of the loss going untraced and the next
 * save quietly overwriting the evidence. `ignoreUnknownKeys` lets a snapshot written by a
 * newer build (extra fields) still parse on an older one; a `version` mismatch is caught in
 * [PersistedSnapshot.toDomain] and discarded as "no last-good" without being corruption.
 */
internal object SnapshotSerializer : Serializer<PersistedSnapshot?> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: PersistedSnapshot? = null

    override suspend fun readFrom(input: InputStream): PersistedSnapshot? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        return try {
            json.decodeFromString(PersistedSnapshot.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            // Signal corruption rather than returning null: the handler logs and replaces the
            // file. The message is generic — the exception can quote the malformed bytes,
            // which hold stop/departure data (SPEC *Privacy*), so the cause is not attached.
            throw CorruptionException("persisted snapshot could not be decoded")
        }
    }

    override suspend fun writeTo(t: PersistedSnapshot?, output: OutputStream) {
        if (t == null) return
        output.write(json.encodeToString(PersistedSnapshot.serializer(), t).encodeToByteArray())
    }
}
