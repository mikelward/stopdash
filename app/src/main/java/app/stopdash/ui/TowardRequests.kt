package app.stopdash.ui

import app.stopdash.domain.BundledStops
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.RefusedStops
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TowardStops
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripModes
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTime
import app.stopdash.domain.WalkingSpeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A plan's requests via bus stops near the rider towards its destination ([TowardStops]; maintainer,
 * 2026-10-15), shared by the trip for now and a later trip, so both ask the same stops the same way.
 * [label] starts each log line ("trip plan", "later trip plan"); no stop, end, position or time is logged,
 * only how many were asked and what came back.
 */
internal class TowardRequests(
    private val planner: JourneyPlanner,
    // The bundled station index, for where a station destination is. Read on [io], as it may read the asset.
    private val stations: () -> StationIndex,
    // The bundled stops ([BundledStops]), searched on the device, so the rider's position goes nowhere new.
    // Read on [io], as it may read the asset.
    private val bundledStops: suspend () -> BundledStops,
    // The stops the Planner found no route through ([RefusedStops]), so a later plan doesn't ask one again.
    private val refused: RefusedStops,
    private val io: CoroutineDispatcher,
    private val compute: CoroutineDispatcher,
    private val warn: (String) -> Unit,
    private val label: String,
) {
    /**
     * The requests from [from] to [destination], each in parallel: the routes riding a bus from there, the
     * soonest of each marked to be listed whatever ([TripRoute.towardStop]), handed to [add] as each lands.
     * Asked from a position only, where there's a walk to choose; a stop the Planner finds no route through
     * gives way, once, to the next for its lines. A bus line in [hidden] picks no stop. [at] is a later
     * trip's time, none for now. False when a request failed: the plan stands without it.
     */
    suspend fun plan(
        from: TripOrigin,
        destination: TripDestination,
        speed: WalkingSpeed,
        limit: MaxWalk,
        access: StepFree,
        modes: TripModes,
        hidden: Set<String>,
        at: TripTime? = null,
        add: suspend (List<TripRoute>) -> Unit,
    ): Boolean {
        val here = (from as? TripOrigin.Here)?.coordinate ?: return true
        val stops = try {
            // Buses turned off for the trip: the Planner would offer none from any stop. Worked out with the
            // read, off the main thread (AGENTS.md *Main thread*; Codex, #870).
            withContext(io) { if (BUS_MODE in modes.plannerModes.split(',')) bundledStops() else null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Static public data: naming the failure carries nothing about the rider. The plan stands as asked.
            warn("$label via stops towards the destination: bundled stops unavailable: ${e::class.simpleName}")
            return true
        } ?: return true
        if (stops.size == 0) {
            // A build without its bundled stops (unreadable, and so for the life of the process, as they're read
            // once): the plan stands as asked, said here rather than as an incomplete plan whose Retry can't finish
            // it (Codex, #875).
            warn("$label via stops towards the destination: no bundled stops")
            return true
        }
        val to = when (destination) {
            is TripDestination.Place -> destination.coordinate
            // A station from the station index (a hub's place worked out over its stations, so off the main thread),
            // else a bus stop from the bundled stops.
            is TripDestination.Stop -> withContext(io) { stations().placeOf(destination.id) } ?: withContext(compute) { stops.positionOf(destination.id) }
        } ?: return true
        val asks = withContext(compute) {
            val near = stops.nearby(here.latitude, here.longitude, TowardStops.REACH_METERS, listOf(BUS_STOP_TYPE))
            TowardStops.of(here, to, HiddenModes.stops(near, hidden))
        }
        if (asks.isEmpty()) return true
        warn("$label via stops towards the destination: ${asks.size} asked")
        // A refusal for a later time is that time's: a stop with no route through it at night may have one by day.
        val choices = RefusedStops.Asked(speed, limit, access, modes, at)
        var stood = true
        coroutineScope {
            for (ask in asks) {
                launch {
                    suspend fun via(stop: String, sent: () -> Unit = {}) =
                        withContext(io) { planner.quickestVia(from, destination, stop, speed, limit, access, modes, at, sent) }
                    // A stop refused before, for this destination under these choices: its next straight away,
                    // unless refused too.
                    val next = ask.fallback?.takeIf { !refused.has(it, destination, choices) }
                    val first = if (refused.has(ask.via, destination, choices)) {
                        next ?: run {
                            warn("$label via a stop towards the destination: no route through it before, nor the next stop")
                            return@launch
                        }
                    } else {
                        ask.via
                    }
                    // The next stop for its lines, should [first] be refused. Asked alongside once [first] is slow to
                    // answer, not only after its refusal: the Planner has taken 7 s to refuse a stop it finds no route
                    // through, the next stop then asked from scratch. Its answer counts only after that refusal.
                    val then = next?.takeIf { it != first }
                    // When [first]'s request went to the Planner: slow is timed from then, never from a wait for the
                    // app's own request budget, which another request would only lengthen.
                    val sent = CompletableDeferred<Unit>()
                    // The stop the answer came via, and the answer or the request's failure.
                    val (viaStop, answer) = coroutineScope {
                        val spare = then?.let { stop -> async(start = CoroutineStart.LAZY) { attempt { via(stop) } } }
                        val early = spare?.let {
                            launch {
                                sent.await()
                                delay(SPARE_AFTER_MILLIS)
                                warn("$label via a stop towards the destination: slow to answer, so the next stop asked alongside")
                                it.start()
                            }
                        }
                        val own = attempt { via(first) { sent.complete(Unit) } }
                        early?.cancel()
                        if (own.exceptionOrNull() is TflException.NotFound && spare != null) {
                            // The Planner found no route through it, though it may plan from it readily: remembered, and
                            // the next stop for its lines asked (or, already asked, awaited), once.
                            refused.add(first, destination, choices)
                            warn("$label via a stop towards the destination: no route through it; asking the next stop")
                            then to spare.await()
                        } else {
                            spare?.cancel()
                            first to own
                        }
                    }
                    val routes = answer.getOrElse { e ->
                        when {
                            e !is TflException.NotFound -> {
                                warn("$label via a stop towards the destination failed: ${e::class.simpleName}")
                                stood = false
                            }
                            viaStop == first -> {
                                refused.add(first, destination, choices)
                                warn("$label via a stop towards the destination: no route through it")
                            }
                            else -> {
                                refused.add(viaStop, destination, choices)
                                warn("$label via a stop towards the destination: no route through the next stop either")
                            }
                        }
                        return@launch
                    }
                    val found = withContext(compute) {
                        val rides = TowardStops.ridesFrom(routes, viaStop)
                        // The soonest by the Planner's times, its every departure: the card it gets is the one asked for.
                        val soonest = rides.minByOrNull { it.legs.last().arrival }?.let(::routeKey)
                        rides.map { if (routeKey(it) == soonest) it.copy(towardStop = true) else it }
                    }
                    warn("$label via a stop towards the destination: ${found.size} of ${routes.size} routes ride a bus from there")
                    if (found.isNotEmpty()) add(found)
                }
            }
        }
        return stood
    }

    // The Planner's answer, or its failure ([TflException]): a request alongside another that fails never cancels it.
    private suspend fun attempt(ask: suspend () -> List<TripRoute>): Result<List<TripRoute>> =
        try {
            Result.success(ask())
        } catch (e: TflException) {
            Result.failure(e)
        }

    private companion object {
        // How long a request via a stop runs at the Planner before the next stop for its lines is asked alongside, in
        // case it's refused: past most answers the Planner gives (about 1 to 2.5 s), well short of a slow refusal.
        const val SPARE_AFTER_MILLIS = 3_000L

        // The NaPTAN stop type of a bus stop, as the bundled stops are searched by.
        const val BUS_STOP_TYPE = "NaptanPublicBusCoachTram"

        // The Planner's mode for a bus, in [TripModes.plannerModes].
        const val BUS_MODE = "bus"
    }
}
