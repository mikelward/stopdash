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
     * Where a follow put the rider: the [nearby] stops, their [distances] (stop id to meters from the
     * followed position), and the [hidden] modes, the user's [dismissals] and the app's alert [verdicts]
     * the line choices are worked out
     * with ([withChoices]). Held in memory for one refresh, never stored (SPEC *Privacy*).
     */
    data class Position(
        val nearby: Set<String>,
        val distances: Map<String, Double>,
        val hidden: Set<String>,
        val dismissals: Dismissals,
        val verdicts: Set<AlertBehind>,
    )

    /**
     * A move to follow: [snapshot], the stored one re-laid for the new stops (the ones not fetched yet
     * as [placeholders], to be fetched by the refresh), and [nearby], the new nearby set for the widget
     * and the watch, from [position].
     */
    data class Moved(
        val snapshot: DeparturesSnapshot,
        val nearby: Set<String>,
        val placeholders: Set<String>,
        val position: Position,
    )

    /**
     * A followed position: [position], and the [moved] layout it calls for, or null when the stored
     * one already stands there. Either way a refresh's rows get their line choices from [position]
     * ([withChoices]), since a line can come into its prediction window between refreshes.
     */
    data class Followed(val position: Position, val moved: Moved?)

    /**
     * The move from [prior], stored with [storedNearby] as its nearby set, to the stops [found] around
     * [at] with [hidden] modes hidden, picked as the app picks them ([NearbySelection.selectClusters]):
     * null when the set is the one stored, the snapshot holds all of it, and its line choices are the
     * ones [GlanceRows.choices] works out from [at] at [now] with [dismissals] and [verdicts] applied, so nothing changes. Kept are the stops still nearby and every
     * pinned journey's origin (journey-only where it's no longer nearby); each new stop is a placeholder
     * with no arrivals, stamped [Instant.EPOCH], for the refresh to fetch. The order nearest first, and
     * each stop's nearer places ([Terminating.nearer]) and the stop each line is shown from
     * ([DeparturesSnapshot.nearbyChoices]), are from [at]; [settled] works the choices out again once
     * the new stops' arrivals are in.
     */
    @WorkerThread
    fun moved(
        prior: DeparturesSnapshot,
        storedNearby: Set<String>?,
        found: List<StopLocation>,
        at: Coordinates,
        hidden: Set<String>,
        now: Instant,
        dismissals: Dismissals = Dismissals.NONE,
        verdicts: Set<AlertBehind> = emptySet(),
        radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
    ): Moved? = followed(prior, storedNearby, found, at, hidden, now, dismissals, verdicts, radiusMeters).moved

    /** [moved], with the [Position] it was worked out from, which stands whether or not it moved. */
    @WorkerThread
    fun followed(
        prior: DeparturesSnapshot,
        storedNearby: Set<String>?,
        found: List<StopLocation>,
        at: Coordinates,
        hidden: Set<String>,
        now: Instant,
        dismissals: Dismissals = Dismissals.NONE,
        verdicts: Set<AlertBehind> = emptySet(),
        radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
    ): Followed {
        // Picked, measured and ordered as the app's list does it ([NearbyLayout]).
        val result = NearbyLayout.pick(found, at, hidden, radiusMeters)
        val eager = result.eager.flatMap { it.stops }
        val all = eager + result.more.flatMap { it.stops }
        val meters = result.distances
        val nearby = eager.mapTo(LinkedHashSet()) { it.id }
        val held = prior.stops.associateBy { it.stopId }
        // The set stored and every stop of it held: nothing to change. A set stored whose stops the snapshot
        // doesn't hold yet (a follow whose fetch failed, or was stopped, after the set was stored) is laid
        // out again, so the next refresh fetches them rather than leaving them missing (Codex on #711).
        val origins = prior.journeys.mapTo(HashSet()) { it.originId }
        // Nor one still holding a stop that's neither nearby nor a journey's origin (an empty set's layout
        // that wasn't stored), whose arrivals would be fetched for rows the widget no longer shows.
        val places = NearbyLayout.places(all, meters)
        val nearestFirst = NearbyLayout.nearestFirst(eager.map { it.id }, meters)
        // The same stops can sit in a different order, or with other stops nearer, from a new spot: the
        // layout is position-derived, so it's compared too (Codex on #711).
        // The stop each line is shown from, worked out from here as the app works it out (keeping a
        // route's two directions at one place): the app's own, saved from where it last was, stand only
        // while they are the ones this position gives. Without them the widget folds by order alone,
        // and a line's two directions split across two places the app's list shows as one.
        val position = Position(nearby, meters.filterKeys { it in nearby }, hidden, dismissals, verdicts)
        val choicesHere = GlanceRows.choices(prior, nearby, meters, now, hidden, dismissals, verdicts)
        val laidOut = nearestFirst == prior.nearestFirst && prior.nearbyChoices.toSet() == choicesHere.toSet() &&
            nearby.all { id -> held[id]?.nearer == Terminating.nearer(id, places) }
        if (laidOut && nearby == storedNearby && nearby.all { it in held } && held.keys.all { it in nearby || it in origins }) {
            return Followed(position, null)
        }
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
        // Classed and ordered as the app's refresh lays them out ([SnapshotPlan]): every nearby stop is
        // kept or a placeholder, so none is missing until [settled] finds a placeholder unfetched.
        val laid = SnapshotPlan.laidOut(kept + added, nearby, origins, meters)
        return Followed(position, Moved(
            snapshot = prior.copy(
                stops = laid.stops,
                journeyOnlyStopIds = laid.journeyOnlyStopIds,
                missingStopIds = laid.missingStopIds,
                nearestFirst = laid.nearestFirst,
                // With new stops to fetch, a choice made without them could fold a line onto a farther
                // stop: none until [settled] works them out again from the fetched rows.
                nearbyChoices = if (added.isEmpty()) choicesHere else emptyList(),
            ),
            nearby = nearby,
            placeholders = added.mapTo(HashSet()) { it.stopId },
            position = position,
        ))
    }

    /**
     * [refreshed], [moved]'s snapshot after its refresh, ready to store: a placeholder stop the refresh
     * couldn't fetch (still stamped [Instant.EPOCH]) comes out, and is listed missing instead, so the
     * widget says its stops are partly out of date rather than showing a stop with no trains; and the
     * stop each line is shown from is worked out from the fetched rows ([GlanceRows.choices]) at [now].
     */
    @WorkerThread
    fun settled(refreshed: DeparturesSnapshot, moved: Moved, now: Instant): DeparturesSnapshot {
        val unfetched = refreshed.stops.filter { it.stopId in moved.placeholders && it.fetchedAt == Instant.EPOCH }.mapTo(HashSet()) { it.stopId }
        val fetched = if (unfetched.isEmpty()) {
            refreshed
        } else {
            refreshed.copy(
                stops = refreshed.stops.filterNot { it.stopId in unfetched },
                missingStopIds = refreshed.missingStopIds + unfetched,
            )
        }
        return withChoices(fetched, moved.position, now)
    }

    /**
     * [refreshed] with the stop each line is shown from worked out from [position] ([GlanceRows.choices]) at
     * [now]: after every refresh a follow ran for, moved or not, so a line that came into its
     * prediction window since is folded as the app folds it, not by the order alone.
     */
    @WorkerThread
    fun withChoices(refreshed: DeparturesSnapshot, position: Position, now: Instant): DeparturesSnapshot {
        val choices = GlanceRows.choices(refreshed, position.nearby, position.distances, now, position.hidden, position.dismissals, position.verdicts)
        return if (choices == refreshed.nearbyChoices) refreshed else refreshed.copy(nearbyChoices = choices)
    }
}
