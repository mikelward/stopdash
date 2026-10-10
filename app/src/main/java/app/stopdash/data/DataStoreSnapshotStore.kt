package app.stopdash.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.FoldChoice
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import app.stopdash.domain.WidgetJourneys
import app.stopdash.domain.WidgetJourneysReport
import java.io.IOException
import java.io.InputStream
import java.time.Instant
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
    // For merging line checks, and for every write: what's dated after it predates a clock
    // rollback, and is distrusted ([update]).
    private val clock: () -> Instant = Instant::now,
    // The sanitized log seam ([from]).
    private val warn: (String) -> Unit = {},
) : SnapshotStore {

    /**
     * [DataStore.updateData], with the stored snapshot [transform] starts from and what it writes
     * both in this process's steady-clock frame ([here]) and distrusting anything stamped from
     * before the clock was set back ([distrustingFuture], as of [now]): so no merge keeps an old
     * stop over a fresh one because its stamp looks later, and what's written says which frame its
     * stamps are in. A newer build's file is left as it is, as every write here leaves it. Pure over
     * `current` and [now], since DataStore may re-run the transform.
     */
    private suspend fun update(
        now: Instant = clock(),
        transform: (current: PersistedSnapshot?) -> PersistedSnapshot?,
    ): PersistedSnapshot? {
        fun trusted(s: PersistedSnapshot?) = here(s)?.distrustingFuture(now) ?: s
        return dataStore.updateData { stored -> trusted(transform(trusted(stored))) }
    }

    /**
     * [stored] with its fetch stamps moved into this process's steady-clock frame
     * ([PersistedSnapshot.inFrame]), so one written before the clock was set is read at its real
     * age, from the first read on (Codex, PR #371); null for a newer build's file, which is left
     * as it is.
     */
    private fun here(stored: PersistedSnapshot?): PersistedSnapshot? =
        stored?.takeIf { it.version in PersistedSnapshot.READABLE_VERSIONS }?.inFrame(SteadyClock.source?.frame)

    /**
     * [stored] as this process reads it, and kept that way: moved into its steady-clock frame
     * ([here]), and distrusting anything stamped ahead of now ([PersistedSnapshot.distrustingFuture]),
     * written back when that changed anything ([update]). Every judgment a read makes of what the
     * clock has done is then made once and kept, not made again by each later read against a clock
     * that has moved on (Codex, PR #371): a stop found stale across a reboot stays stale however the
     * clock is set after, a line check found ahead of the clock stays dropped once it catches up, and
     * a snapshot with no frame (an older build's, or one saved where the boot couldn't be told) takes
     * this process's. DataStore writes only a change, so a read with nothing to move or distrust
     * writes nothing. A failed write is logged, and what it would have written is read in its place,
     * and kept ([unwritten]) while the file is still that one; a later process tries the write again.
     */
    private suspend fun readStored(stored: PersistedSnapshot?): PersistedSnapshot? {
        if (stored == null || stored.version !in PersistedSnapshot.READABLE_VERSIONS) return stored
        val now = clock()
        unwritten?.let { (from, read) ->
            if (from == stored) return read.distrustingFuture(now).also { unwritten = stored to it }
        }
        return try {
            update(now) { it }
        } catch (e: IOException) {
            warn("snapshot rewrite failed: ${e::class.simpleName}")
            (here(stored)?.distrustingFuture(now) ?: stored).also { unwritten = stored to it }
        }
    }

    // A snapshot this process couldn't write back, and what it reads as ([readStored]).
    @Volatile
    private var unwritten: Pair<PersistedSnapshot, PersistedSnapshot>? = null

    override suspend fun load(): DeparturesSnapshot? = readStored(dataStore.data.first())?.let(::here)?.toDomain()

    /** Every stored snapshot as it's written, from any writer (the app, the widget's worker). */
    fun snapshots(): Flow<DeparturesSnapshot?> = dataStore.data.map { readStored(it)?.let(::here)?.toDomain() }

    override suspend fun save(snapshot: DeparturesSnapshot) {
        update { snapshot.toPersisted() }
    }

    override suspend fun saveIfStopsMatch(
        snapshot: DeparturesSnapshot,
        expectedStopIds: List<String>,
    ): Boolean {
        // Captured once, so the transform stays a pure function of `current` if DataStore re-runs it.
        val now = clock()
        // Framed and distrusted as the write will be ([update]), so a written match compares equal to
        // it below.
        val desired = snapshot.toPersisted().inFrame(SteadyClock.source?.frame).distrustingFuture(now)
        // The transform runs under DataStore's write lock, so the compare and the write are one
        // atomic step — no reload→save window a concurrent writer could slip through. Keep the
        // stored snapshot untouched when its stop set no longer matches what the caller worked
        // from (a newer write changed it), discarding the caller's now-stale result. The block
        // stays a pure function of `current` (no captured mutable state), since DataStore may
        // re-run it on a write conflict.
        // The widget's journeys are the app's to change, never this caller's: a match keeps the
        // stored ones, so a slow worker can't restore a pin the app has since dropped or changed.
        // So are each stop's nearer places ([app.stopdash.domain.Terminating]): they follow the
        // rider's location, which only the app knows, so a worker that loaded an older location's
        // snapshot can't restore its places over the app's newer ones.
        fun keepingAppsOwn(current: PersistedSnapshot): PersistedSnapshot {
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
                // So is the stops' nearest-first order, for the same reason as the nearer places,
                // and the stop the app shows each line from.
                nearestFirst = current.nearestFirst,
                nearbyChoices = current.nearbyChoices,
            )
        }
        val written = update(now) { current ->
            if (current != null && current.matchesStops(expectedStopIds)) keepingAppsOwn(current) else current
        }
        return written != null && written == keepingAppsOwn(written)
    }

    override suspend fun updateNearer(nearer: Map<String, Terminating.Nearer>) {
        if (nearer.isEmpty()) return
        // A pure function of `current` and the immutable map, atomic under the write lock like
        // [pruneStops].
        update { current ->
            if (current == null) return@update null
            val stops = current.stops.map { stop ->
                val n = nearer[stop.stopId] ?: return@map stop
                stop.copy(nearerIds = n.ids.sorted(), nearerNames = n.names.sorted())
            }
            if (stops == current.stops) current else current.copy(stops = stops)
        }
    }

    override suspend fun updateNearestFirst(order: List<String>, choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?) {
        // A pure function of `current` and the immutable list, atomic under the write lock like
        // [updateNearer]. Only stops the snapshot holds take a place in it.
        update { current ->
            if (current == null) return@update null
            // A newer build's file isn't this one's to rewrite piecemeal (as [updateLineStatuses]).
            if (current.version !in PersistedSnapshot.READABLE_VERSIONS) return@update current
            val nearby = current.stops.map { it.stopId }.filterTo(HashSet()) { it !in current.journeyOnlyStopIds }
            // The stops [order] ranks take its order; the rest keep their stored order after them.
            // After a move whose first save hasn't landed, the snapshot is shown scoped to the new set
            // ([app.stopdash.domain.DeparturesSnapshot.scopedTo]), so a stop both sets share is
            // folded by where the rider is now, and one only the old set held isn't shown at all
            // (Codex on #473: neither dropping the old ranking nor keeping it whole was right).
            // A held stop [order] names is ranked even if stored as journey-only: it's a journey's
            // origin the rider is now near, and [scopedTo] shows it as nearby again (Codex on #473).
            val held = current.stops.mapTo(HashSet()) { it.stopId }
            val ranked = order.filter { it in held }
            val rankedIds = ranked.toHashSet()
            val next = ranked + current.nearestFirst.filter { it in nearby && it !in rankedIds }
            // Worked out afresh from the stored rows, for the stops it ranks; none given clears the
            // stored ones, worked out for where the rider was.
            val choices = choicesFor?.let { work -> current.toDomain()?.let(work) }.orEmpty()
            val nextChoices = choices.filter { it.stopId in rankedIds }.map(PersistedFoldChoice::of)
            if (next == current.nearestFirst && nextChoices == current.nearbyChoices) {
                current
            } else {
                current.copy(nearestFirst = next, nearbyChoices = nextChoices)
            }
        }
    }

    override suspend fun updateLineStatuses(checks: Map<String, LineStatusCheck>) = mergeLineStatuses(checks, null, null)

    override suspend fun updateLineStatuses(
        checks: Map<String, LineStatusCheck>,
        laidOut: DeparturesSnapshot,
        choicesFor: (DeparturesSnapshot) -> List<FoldChoice>,
    ) = mergeLineStatuses(checks, laidOut.toPersisted(), choicesFor)

    private suspend fun mergeLineStatuses(
        checks: Map<String, LineStatusCheck>,
        laidOut: PersistedSnapshot?,
        choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?,
    ) {
        if (checks.isEmpty()) return
        val desired = checks.toPersistedStatuses()
        val now = clock()
        // Pure function of `current`, atomic with the read under the write lock (see pruneStops).
        // A newer build's file isn't this one's to rewrite piecemeal, so it's left alone; a later
        // full save replaces it.
        update(now) { current ->
            if (current == null || current.version !in PersistedSnapshot.READABLE_VERSIONS) return@update current
            val merged = newestStatuses(current.lineStatuses, desired, current.stops, now)
            val withStatuses = if (merged.toSet() == current.lineStatuses.toSet()) current else current.copy(lineStatuses = merged)
            // Worked out from the merged statuses, so a status row they add folds as the app folds it; only
            // over the layout the caller saw, not one laid out from a newer position meanwhile (Codex on #748).
            val choices = choicesFor?.takeIf { laidOut != null && current.laidOutAs(laidOut) }
                ?.let { work -> withStatuses.toDomain()?.let(work) }?.map(PersistedFoldChoice::of)
            if (choices == null || choices == withStatuses.nearbyChoices) withStatuses else withStatuses.copy(nearbyChoices = choices)
        }
    }

    override suspend fun pruneStops(departedStopIds: Collection<String>) {
        if (departedStopIds.isEmpty()) return
        val departed = departedStopIds.toSet()
        // Pure function of `current` (DataStore may re-run it on a write conflict, so it captures
        // only the immutable `departed`): drop the departed stops and re-derive the whole-snapshot
        // stamp from what remains, leaving the kept stops at their own ages. Atomic with the read
        // under the write lock, so a concurrent save can't be lost through a reload→save window.
        update { current ->
            if (current == null) return@update null
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
                    // A departed stop is no longer nearby, so it leaves the order, a demoted origin too.
                    nearestFirst = current.nearestFirst.filterNot { it in departed },
                    nearbyChoices = current.nearbyChoices.filterNot { it.stopId in departed },
                )
            }
        }
    }

    override suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) {
        val now = clock()
        // Distrusted before the merge ([update]), so a stop of the caller's stamped from before the
        // clock was set back can't win over a fresher one stored.
        val desired = snapshot.toPersisted().distrustingFuture(now)
        // Pure function of `current`, atomic with the read under the write lock (see pruneStops).
        update(now) { current -> keepingJourneys(current, desired, now) }
    }

    override suspend fun saveFollowedIfUnchanged(snapshot: DeparturesSnapshot, loaded: DeparturesSnapshot): Boolean =
        saveFollowed(snapshot, loaded, null)

    override suspend fun saveFollowedIfUnchanged(
        snapshot: DeparturesSnapshot,
        loaded: DeparturesSnapshot,
        choicesFor: (DeparturesSnapshot) -> List<FoldChoice>,
    ): Boolean = saveFollowed(snapshot, loaded, choicesFor)

    private suspend fun saveFollowed(
        snapshot: DeparturesSnapshot,
        loaded: DeparturesSnapshot,
        choicesFor: ((DeparturesSnapshot) -> List<FoldChoice>)?,
    ): Boolean {
        val now = clock()
        val desired = snapshot.toPersisted().distrustingFuture(now)
        val expected = loaded.toPersisted()
        val expectedStopIds = expected.stops.map { it.stopId }
        // Set on every run, since DataStore may re-run the transform.
        var applied = false
        update(now) { current ->
            // The same stops aren't enough: the app may have laid them out from a newer position of its
            // own meanwhile, whose order and nearer places this older follow mustn't undo (Codex on #711).
            applied = current != null && current.matchesStops(expectedStopIds) && current.laidOutAs(expected)
            if (!applied) return@update current
            val written = withLayoutOf(keepingJourneys(current, desired, now), desired)
            // Worked out from the rows the write keeps: a fresher stored stop's rows over the caller's
            // fold by choices made from them (Codex on #748).
            val choices = choicesFor?.let { work -> written.toDomain()?.let(work) }?.map(PersistedFoldChoice::of)
            if (choices == null || choices == written.nearbyChoices) written else written.copy(nearbyChoices = choices)
        }
        return applied
    }

    // [merged] with each stop's nearer places from [layout]: a stop whose fresher stored arrivals the merge kept
    // still takes the follow's layout, which is from where the rider is now (Codex on #711).
    private fun withLayoutOf(merged: PersistedSnapshot, layout: PersistedSnapshot): PersistedSnapshot {
        val nearer = layout.stops.associate { it.stopId to (it.nearerIds to it.nearerNames) }
        return merged.copy(
            stops = merged.stops.map { stop ->
                nearer[stop.stopId]?.let { (ids, names) -> stop.copy(nearerIds = ids, nearerNames = names) } ?: stop
            },
        )
    }

    // [desired] stored as [saveKeepingJourneys] stores it over [current]: a pure function of both.
    private fun keepingJourneys(current: PersistedSnapshot?, desired: PersistedSnapshot, now: Instant): PersistedSnapshot =
        run {
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
            )
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
        val now = clock()
        // An origin stamped from before the clock was set back is distrusted before the merge
        // ([update]), so it can't replace a fresher one stored for its later-looking stamp.
        val trusted = origins.map { origin ->
            val stop = origin.toPersisted()
            stop.distrustingFuture(now).takeIf { it != stop }?.toDomain() ?: origin
        }
        // Pure function of `current`, atomic with the read under the write lock (see pruneStops),
        // so every report builds on the pins as stored, whichever writer came before.
        update(now) { current ->
            // A newer build's file: leave it rather than rewrite it in this build's format.
            if (current != null && current.version !in PersistedSnapshot.READABLE_VERSIONS) return@update current
            // Written as the current version: journeys and journey-only stops are version-2 fields.
            WidgetJourneys.apply(current?.toDomain(), report, trusted)?.toPersisted()
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
         * the next read starts clean; so is a failed write-back of what a read found. Only the
         * first caller's [warn] is used, since the store is a process singleton.
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
                    warn = warn,
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
 * True when this and [other] order their stops alike, give each the same nearer places and keep each
 * line at the same stop: everything the app works out from where the rider is.
 */
private fun PersistedSnapshot.laidOutAs(other: PersistedSnapshot): Boolean {
    fun layout(s: PersistedSnapshot) = s.stops.associate { it.stopId to (it.nearerIds.toSet() to it.nearerNames.toSet()) }
    return nearestFirst == other.nearestFirst && nearbyChoices.toSet() == other.nearbyChoices.toSet() && layout(this) == layout(other)
}

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
