package app.stopdash.data

import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.LineRef
import app.stopdash.domain.RailFeed
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.Terminating
import app.stopdash.domain.WidgetJourney
import app.stopdash.domain.normalizeBranch
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of a [DeparturesSnapshot], kept in the `data` layer so the domain types
 * stay free of serialization annotations and `java.time` never has to round-trip through a
 * serializer. `Instant`s are stored as epoch milliseconds (a plain `Long`), which is stable
 * across app versions and time zones. `version` lets a future format change be detected and
 * discarded rather than mis-read; an unknown version reads as "no snapshot".
 *
 * [PersistedStop] is also the Wear OS envelope's stop model ([WatchEnvelope]), so the watch
 * renders from exactly the widget's inputs: a field added here reaches the user's watch too, so
 * check it against the watch-sync disclosure (docs/PRIVACY.md). Otherwise the field set can
 * change with a `version` bump, and only the fields the restore actually needs are carried (the
 * transient refresh-cycle flags are not persisted; see [DeparturesSnapshot]). The file itself is
 * not strictly device-local: it rides Android backup and device-to-device transfer like the rest
 * of the app's data (SPEC §12), a platform path the user controls — see the app's
 * `DataStoreSnapshotStore`.
 */
@Serializable
data class PersistedSnapshot(
    val version: Int = CURRENT_VERSION,
    val stops: List<PersistedStop> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    // The widget's starred journeys and journey-only stops ([DeparturesSnapshot]), added in version
    // 2. Defaulted, so a version-1 snapshot reads back with none.
    val journeys: List<PersistedWidgetJourney> = emptyList(),
    val journeyOnlyStopIds: List<String> = emptyList(),
    // Stops the last refresh asked for but couldn't get ([DeparturesSnapshot.missingStopIds]).
    // Defaulted, so an older snapshot reads back with none, as before; an older build reading
    // this one ignores it and shows what it did before.
    val missingStopIds: List<String> = emptyList(),
) {
    companion object {
        /**
         * The current on-disk format. Bump when a field's meaning changes incompatibly. Version 2
         * added journey-only stops: a version-1 reader would show them as nearby stops, so it
         * must discard a version-2 file, while this build still reads version 1.
         */
        const val CURRENT_VERSION = 2

        /** The formats this build reads. */
        val READABLE_VERSIONS = setOf(1, CURRENT_VERSION)
    }
}

@Serializable
data class PersistedStop(
    val stopId: String,
    val stopName: String,
    val departures: List<PersistedDeparture> = emptyList(),
    val fetchedAtMillis: Long = 0L,
    val lines: List<PersistedLine> = emptyList(),
    val arrivalsFresh: Boolean = true,
    // The stop's cluster for per-place grouping (SPEC D8). Defaulted, so a snapshot written by an
    // older build reads back blank — that stop groups on its own until the next refresh restamps
    // it — no version bump needed.
    val clusterId: String = "",
    // The bus pole's letter/bearing/towards for the per-pole split (SPEC D8) — persisted like
    // [clusterId] (grouping inputs the screen needs before the refresh), so a restored snapshot keeps
    // its per-pole headers rather than collapsing to bare/terminus until the fetch lands. Defaulted,
    // so an older build's snapshot reads back blank — no version bump needed.
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
    // The places no farther from the rider than this stop ([Terminating.Nearer]), so the widget's
    // refresh hides the same terminating services the app does. Defaulted: an older snapshot reads
    // back empty and hides nothing until the app next fetches the stop.
    val nearerIds: List<String> = emptyList(),
    val nearerNames: List<String> = emptyList(),
    // Why the stop's arrivals carry no National Rail times ([RailFeed]: live-but-empty, no key, or
    // board unavailable), by name, so a restored rail row still says "No key" or "No data"
    // rather than guessing. Defaulted: an older snapshot reads back null (the pre-persistence
    // behavior), and a name this build doesn't know reads back null too.
    val railFeed: String? = null,
)
// Stop disruptions (closures) are deliberately NOT persisted: a closure is a point-in-time
// claim the screen renders unconditionally, with no stale-safe rendering (unlike a countdown,
// which is withheld once stale), so a saved one would assert a possibly-reopened station on
// the next launch. This is the same reason Snapshot.mergeStop never ages a disruption. On
// restore a stop carries no disruptions; the immediate refresh re-establishes them.

@Serializable
data class PersistedWidgetJourney(
    val originId: String,
    val calls: List<PersistedJourneyCall> = emptyList(),
    val key: String = "",
    val shownFrom: String = "",
)

@Serializable
data class PersistedJourneyCall(
    val lineId: String,
    val destination: String,
    val branch: String? = null,
)

@Serializable
data class PersistedDeparture(
    val lineId: String,
    val lineName: String,
    val direction: String,
    val destination: String,
    val platform: String? = null,
    val expectedArrivalMillis: Long,
    val mode: String,
    // The "via" branch, when TfL gave one. Nullable with a default, so a snapshot written by
    // an older build (no branch field) reads back as null — restored departures show no branch
    // until the next refresh, no version bump needed.
    val branch: String? = null,
    // The terminus stop id (TfL `destinationNaptanId`), so a restored or widget-refreshed snapshot
    // hides the same terminating services ([Terminating]). Defaulted blank for an older snapshot.
    val destinationId: String = "",
    // TfL's id for the train making the departure, so one restored can still be followed. Defaulted
    // blank (no train) for an older snapshot.
    val vehicleId: String = "",
)

@Serializable
data class PersistedLine(
    val id: String,
    val name: String,
    val mode: String,
)

fun WidgetJourney.toPersisted(): PersistedWidgetJourney =
    PersistedWidgetJourney(originId, calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, key, shownFrom)

fun DeparturesSnapshot.toPersisted(): PersistedSnapshot =
    PersistedSnapshot(
        stops = stops.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        journeys = journeys.map { j ->
            PersistedWidgetJourney(
                j.originId, j.calls.map { PersistedJourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.sorted(),
        missingStopIds = missingStopIds.sorted(),
    )

/**
 * The domain snapshot, or null when the stored format is a version this build doesn't know
 * — discarded rather than mis-read, so a forward-incompatible change fails safe to "no
 * last-good".
 */
fun PersistedSnapshot.toDomain(): DeparturesSnapshot? {
    if (version !in PersistedSnapshot.READABLE_VERSIONS) return null
    return DeparturesSnapshot(
        stops = stops.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        journeys = journeys.map { j ->
            WidgetJourney(
                j.originId, j.calls.mapTo(HashSet()) { JourneyCall(it.lineId, it.destination, it.branch) }, j.key, j.shownFrom,
            )
        },
        journeyOnlyStopIds = journeyOnlyStopIds.toSet(),
        missingStopIds = missingStopIds.toSet(),
    )
}

fun StopArrivals.toPersisted(): PersistedStop =
    PersistedStop(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toPersisted() },
        fetchedAtMillis = fetchedAt.toEpochMilli(),
        lines = lines.map { PersistedLine(it.id, it.name, it.mode) },
        // Stop disruptions are intentionally not carried to disk (point-in-time; see above).
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
        nearerIds = nearer.ids.sorted(),
        nearerNames = nearer.names.sorted(),
        railFeed = railFeed?.name,
    )

fun PersistedStop.toDomain(): StopArrivals =
    StopArrivals(
        stopId = stopId,
        stopName = stopName,
        departures = departures.map { it.toDomain() },
        fetchedAt = Instant.ofEpochMilli(fetchedAtMillis),
        lines = lines.map { LineRef(it.id, it.name, it.mode) },
        // No disruptions restored — the immediate refresh re-establishes any current one.
        arrivalsFresh = arrivalsFresh,
        clusterId = clusterId,
        stopLetter = stopLetter,
        bearing = bearing,
        towards = towards,
        nearer = Terminating.Nearer(nearerIds.toSet(), nearerNames.toSet()),
        railFeed = railFeed?.let { name -> RailFeed.entries.firstOrNull { it.name == name } },
    )

private fun Departure.toPersisted(): PersistedDeparture =
    PersistedDeparture(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrivalMillis = expectedArrival.toEpochMilli(),
        mode = mode,
        branch = branch,
        destinationId = destinationId,
        vehicleId = vehicleId,
    )

internal fun PersistedDeparture.toDomain(): Departure =
    Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = Instant.ofEpochMilli(expectedArrivalMillis),
        mode = mode,
        // Fold an older build's raw spelling ("Charing Cross", "Bank Branch") to the canonical
        // short label on restore, so a persisted row matches a freshly-fetched one (SPEC).
        branch = normalizeBranch(branch),
        destinationId = destinationId,
        vehicleId = vehicleId,
    )
