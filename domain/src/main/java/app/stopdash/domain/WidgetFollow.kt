package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant

/**
 * The widget following the rider while the app is closed (maintainer, 2026-10-09; SPEC D1): at each
 * of its refreshes, with location allowed all the time, the phone's last known position re-picks the
 * nearby stops the app would pick there, so the widget and the watch show the stops where the rider
 * is now rather than where they last opened the app. Pure, so it's tested off a device.
 */
object WidgetFollow {
    /**
     * The oldest last known position followed. Android keeps one fresh while anything asks for
     * location (maps, a fitness app, the app's own fixes); an older one may be from before the rider
     * moved, so the stops stay as they are.
     */
    val MAX_FIX_AGE: Duration = Duration.ofMinutes(10)

    /** The widest a followed fix may be: a fix vaguer than a short walk could pick another stop. */
    const val MAX_ACCURACY_METERS = 200f

    /**
     * Whether a last known fix is good enough to follow: recent ([LocationFix.ageMillis]), and accurate
     * enough to pick stops by. One with no age or no accuracy can't show it is either, so isn't.
     */
    fun followable(fix: LocationFix?): Boolean {
        val ageMillis = fix?.ageMillis ?: return false
        if (fix.isFallback || ageMillis < 0 || ageMillis > MAX_FIX_AGE.toMillis()) return false
        val accuracy = fix.accuracyMeters ?: return false
        return accuracy >= 0f && accuracy <= MAX_ACCURACY_METERS
    }

    /** The newest of [fixes] good enough to follow ([followable]), or null when none is. */
    fun best(fixes: List<LocationFix>): LocationFix? = fixes.filter(::followable).minByOrNull { it.ageMillis!! }

    /**
     * A move to follow: [snapshot], the stored one re-laid for the new stops (the ones not fetched yet
     * as [placeholders], to be fetched by the refresh), and [nearby], the new nearby set for the widget
     * and the watch.
     */
    data class Moved(val snapshot: DeparturesSnapshot, val nearby: Set<String>, val placeholders: Set<String>)

    /**
     * The move from [prior], stored with [storedNearby] as its nearby set, to the stops [found] around
     * [at] with [hidden] modes hidden, picked as the app picks them ([NearbySelection.selectClusters]):
     * null when the set is the one stored and the snapshot holds all of it, so nothing changes. Kept are the stops still nearby and every
     * pinned journey's origin (journey-only where it's no longer nearby); each new stop is a placeholder
     * with no arrivals, stamped [Instant.EPOCH], for the refresh to fetch. The order nearest first, and
     * each stop's nearer places ([Terminating.nearer]), are from [at]; the stop each line is shown from
     * is left to the order ([DeparturesSnapshot.nearbyChoices] empty) until the app works it out again.
     */
    @WorkerThread
    fun moved(
        prior: DeparturesSnapshot,
        storedNearby: Set<String>?,
        found: List<StopLocation>,
        at: Coordinates,
        hidden: Set<String>,
        radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
    ): Moved? {
        val shown = HiddenModes.stops(found, hidden)
        val result = NearbySelection.selectClusters(shown, at.latitude, at.longitude, outerRadiusMeters = radiusMeters)
            .takeIf { it.eager.isNotEmpty() }
            ?: NearbySelection.selectClusters(found, at.latitude, at.longitude, outerRadiusMeters = radiusMeters)
        val eager = result.eager.flatMap { it.stops }
        val all = eager + result.more.flatMap { it.stops }
        val meters = all.associate { it.id to NearestStops.distanceMeters(at.latitude, at.longitude, it.latitude, it.longitude) }
        val nearby = eager.mapTo(LinkedHashSet()) { it.id }
        val held = prior.stops.associateBy { it.stopId }
        // The set stored and every stop of it held: nothing to change. A set stored whose stops the snapshot
        // doesn't hold yet (a follow whose fetch failed, or was stopped, after the set was stored) is laid
        // out again, so the next refresh fetches them rather than leaving them missing (Codex on #711).
        val origins = prior.journeys.mapTo(HashSet()) { it.originId }
        // Nor one still holding a stop that's neither nearby nor a journey's origin (an empty set's layout
        // that wasn't stored), whose arrivals would be fetched for rows the widget no longer shows.
        val places = all.distinctBy { it.id }.map { Terminating.Place(it.id, it.clusterId, it.name, meters.getValue(it.id)) }
        val nearestFirst = eager.sortedBy { meters.getValue(it.id) }.map { it.id }.distinct()
        // The same stops can sit in a different order, or with other stops nearer, from a new spot: the
        // layout is position-derived, so it's compared too (Codex on #711).
        // Nor can the app's per-line stop choices stay: they were worked out from distances (a slack
        // in meters, not just an order) that the widget never sees, so a follow drops them and the
        // widget folds by its order alone (Codex on #711).
        val laidOut = nearestFirst == prior.nearestFirst && prior.nearbyChoices.isEmpty() &&
            nearby.all { id -> held[id]?.nearer == Terminating.nearer(id, places) }
        if (laidOut && nearby == storedNearby && nearby.all { it in held } && held.keys.all { it in nearby || it in origins }) return null
        val kept = prior.stops
            .filter { it.stopId in nearby || it.stopId in origins }
            .map { if (it.stopId in nearby) it.copy(nearer = Terminating.nearer(it.stopId, places)) else it.copy(nearer = Terminating.Nearer()) }
        val added = eager.filter { it.id !in held }.distinctBy { it.id }.map { stop ->
            StopArrivals(
                stopId = stop.id,
                stopName = stop.name,
                departures = emptyList(),
                fetchedAt = Instant.EPOCH,
                lines = stop.lines,
                arrivalsFresh = false,
                clusterId = stop.clusterId,
                hubId = stop.hubId,
                stopLetter = stop.stopLetter,
                bearing = stop.bearing,
                towards = stop.towards,
                nearer = Terminating.nearer(stop.id, places),
            )
        }
        val stops = kept + added
        return Moved(
            snapshot = prior.copy(
                stops = stops,
                journeyOnlyStopIds = origins.filterTo(HashSet()) { id -> id !in nearby && stops.any { it.stopId == id } },
                missingStopIds = prior.missingStopIds.filterTo(HashSet()) { it in nearby },
                nearestFirst = nearestFirst,
                nearbyChoices = emptyList(),
            ),
            nearby = nearby,
            placeholders = added.mapTo(HashSet()) { it.stopId },
        )
    }

    /**
     * [refreshed], the moved snapshot after its refresh, ready to store: a [placeholders] stop the
     * refresh couldn't fetch (still stamped [Instant.EPOCH]) comes out, and is listed missing instead,
     * so the widget says its stops are partly out of date rather than showing a stop with no trains.
     */
    fun settled(refreshed: DeparturesSnapshot, placeholders: Set<String>): DeparturesSnapshot {
        val unfetched = refreshed.stops.filter { it.stopId in placeholders && it.fetchedAt == Instant.EPOCH }.mapTo(HashSet()) { it.stopId }
        if (unfetched.isEmpty()) return refreshed
        return refreshed.copy(
            stops = refreshed.stops.filterNot { it.stopId in unfetched },
            missingStopIds = refreshed.missingStopIds + unfetched,
        )
    }
}
