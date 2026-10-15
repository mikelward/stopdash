package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.BundledStops
import app.stopdash.domain.FinalStop
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.RefusedStops
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StepFree
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripModes
import app.stopdash.domain.TripOrigin
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTime
import app.stopdash.domain.ViaStops
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.Workers
import app.stopdash.domain.mergedRoutes
import app.stopdash.domain.withoutDetours
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A later trip (SPEC *Trips with a change → A later trip*): the Planner's routes for the time the
 * rider picked, from its timetable alone. Nothing live is that far ahead, so there's nothing to
 * refresh or fetch: a time, with the rider's walk, step-free level and modes, is planned once
 * ([show]), and again only on [retry] or when one of those changes. Each of [destinations] (a station
 * complex's stops) is planned to at once from [origin], as a trip for now plans them.
 *
 * Everything the screen draws is worked out here, on [compute] (AGENTS.md *Main thread*): the routes
 * riding a hidden mode or an avoided line, and the detours another route beats, are left out; the rest
 * are listed as [TripTime.ordered] lists them, each a [Card] with its keys and rows. [show] does
 * nothing on its caller's thread but hand the request on. Neither end nor the time is logged.
 */
class LaterTripViewModel(
    private val planner: JourneyPlanner,
    origin: () -> TripOrigin,
    private val destinations: List<TripDestination>,
    // Every id of the destination besides its own stops, each to its stop ([TripViewModel.State.destinationStops]),
    // so a route riding through it and back is left out where another gets off there no later.
    private val destinationIds: Map<String, String> = emptyMap(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
    // As the trip for now's, for the requests via bus stops towards the destination ([TowardRequests]): the
    // station index and the bundled stops, read on [io], and the stops the Planner found no route through.
    // None by default (a test), so a plan asks nothing more.
    stations: () -> StationIndex = { StationIndex.EMPTY },
    bundledStops: suspend () -> BundledStops = { BundledStops.EMPTY },
    refused: RefusedStops = RefusedStops(),
    // The stops a trip the rider sends through a station goes via, as the trip for now's ([TripViewModel.via]):
    // each asked for every destination. Null for a trip that goes any way.
    private val via: ViaStops? = null,
) : ViewModel() {
    // A plan from a position also asks the Planner via the bus stops towards the destination, as the trip for
    // now does, at the picked time.
    private val toward = TowardRequests(planner, stations, bundledStops, refused, io, compute, warn, label = "later trip plan")

    /**
     * What the rider asked for: the time, and their own choices as the trip for now reads them. Equal only to
     * itself, so the screen tells its own request from the one [State] answers by reference, never by walking
     * the sets in composition; the model compares the parts that decide a plan, on its worker.
     */
    data class Request(
        val time: TripTime,
        val walkingSpeed: WalkingSpeed = WalkingSpeed.AVERAGE,
        val maxWalk: MaxWalk = MaxWalk.DEFAULT,
        val stepFree: StepFree = StepFree.DEFAULT,
        val tripModes: TripModes = TripModes.DEFAULT,
        val hiddenModes: Set<String> = emptySet(),
        val avoidedLines: Set<String> = emptySet(),
    ) {
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * One route as listed: [route] itself, [routeKey] to open it by in the trip for now, [listKey] for
     * the list (one route at two departures is two cards), and what its card draws.
     */
    data class Card(
        val route: TripRoute,
        val routeKey: String,
        val listKey: String,
        val departs: String,
        val arrives: String,
        val minutes: Int,
        val rows: List<Row>,
    )

    /** A ride (its departure, line, and where it gets off) or a walk (where to, and its minutes). */
    data class Row(
        val walk: Boolean,
        val time: String,
        val lineId: String,
        val lineName: String,
        val mode: String,
        val toName: String,
        val minutes: Int,
    )

    data class State(
        // The request [cards], [error], [incomplete] and [routesMissed] answer, every choice in it: a screen asking for another
        // shows planning rather than any of them, so a plan under other choices never shows under these.
        val request: Request? = null,
        // The routes as listed; null until the plan for [time] has answered.
        val cards: List<Card>? = null,
        val planning: Boolean = false,
        // No stop answered: why, with a Retry.
        val error: DeparturesUiState.Error.Kind? = null,
        // Some of a complex's stops didn't answer: the routes stand, and the screen says so.
        val incomplete: Boolean = false,
        // Every stop answered, but a request for more routes didn't (via the last stop, or via a bus stop towards
        // the destination): the routes stand, and the screen says some may be missing, not that a station was.
        val routesMissed: Boolean = false,
    )

    /**
     * Where the next plan starts, read when it starts: "Here" is the rider's latest fix, as a trip for now
     * reads it. Set by the screen on every composition, since this model outlives a rotation and the
     * origin it was made with reads that composition's fix.
     */
    @Volatile var origin: () -> TripOrigin = origin

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Only the latest matters: a request handed on while another is handled replaces it.
    private val requests = Channel<Request>(Channel.CONFLATED)

    // Read and written on [compute] only, under [publishing]: the plan's inputs, the latest request handed on
    // (the same plan, perhaps with other modes hidden or lines avoided), its routes before filtering, and
    // what's left out of them.
    private val publishing = Mutex()
    private var planned: Request? = null
    private var latest: Request? = null
    private var raw: List<TripRoute>? = null
    private var excluded: Set<String>? = null
    private var job: Job? = null

    init {
        viewModelScope.launch(compute) { for (request in requests) handle(request) }
    }

    /**
     * The request for [time] and these choices: the one the state answers when every part is the same, the sets
     * the very same objects (they come by identity), else a new one. A recreated screen (a rotation) asks again
     * with what it held, so it shows the cards in hand at once rather than "Planning…" until the model takes a
     * new request that answers the same. Compares no set's contents, so it's a read the caller's thread can make.
     */
    fun request(
        time: TripTime,
        walkingSpeed: WalkingSpeed,
        maxWalk: MaxWalk,
        stepFree: StepFree,
        tripModes: TripModes,
        hiddenModes: Set<String>,
        avoidedLines: Set<String>,
    ): Request = state.value.request?.takeIf {
        it.time == time && it.walkingSpeed == walkingSpeed && it.maxWalk == maxWalk && it.stepFree == stepFree &&
            it.tripModes === tripModes && it.hiddenModes === hiddenModes && it.avoidedLines === avoidedLines
    } ?: Request(time, walkingSpeed, maxWalk, stepFree, tripModes, hiddenModes, avoidedLines)

    /** The plan for [request]: planned when its time or choices are new, filtered again when only what's left out is. */
    fun show(request: Request) {
        requests.trySend(request)
    }

    /** Plans again, after a failure or an incomplete plan. */
    fun retry() {
        viewModelScope.launch(compute) {
            val request = publishing.withLock { planned?.takeIf { job?.isActive != true } } ?: return@launch
            // The same request planned again: its cards, under the same choices, stay up meanwhile.
            plan(request, keepCards = true)
        }
    }

    @WorkerThread
    private suspend fun handle(request: Request) {
        val leftOut = AvoidedLines.excluded(request.hiddenModes, request.avoidedLines)
        val replan = publishing.withLock {
            val last = planned
            val samePlan = last != null && last.time == request.time && last.walkingSpeed == request.walkingSpeed &&
                last.maxWalk == request.maxWalk && last.stepFree == request.stepFree && last.tripModes == request.tripModes
            val changed = leftOut != excluded
            excluded = leftOut
            latest = request
            if (samePlan) {
                // The same plan: what it answers is now this request, and with what's left out changed, the routes in
                // hand are listed again. A plan still running publishes for this request too.
                val held = _state.value
                val routes = raw
                _state.value = held.copy(request = request, cards = if (changed && routes != null) listed(routes, request.time, leftOut) else held.cards)
                false
            } else {
                planned = request
                raw = null
                true
            }
        }
        // A new time or new choices: nothing planned under the old ones stays up, as it may break the new ones
        // (a stair-free trip shown a route with stairs).
        if (replan) plan(request, keepCards = false)
    }

    // A plan for [request], in place of any still running. Only on a retry of the same request ([keepCards]) does
    // what's in hand stay up meanwhile, its cards and any notice over them, so nothing on screen moves.
    private suspend fun plan(request: Request, keepCards: Boolean) = publishing.withLock {
        job?.cancel()
        val held = _state.value
        val shown = latest ?: request
        _state.value = if (keepCards && held.request == shown) held.copy(planning = true) else State(request = shown, planning = true)
        job = viewModelScope.launch(compute) {
            val from = origin()
            // Each destination's answer, or the failure it ended in, off the main thread whole; alongside them, the
            // requests via bus stops towards the first destination, as the trip for now makes them.
            val (answers, viaStops) = coroutineScope {
                // Via each station of a stop the rider picked to go through, as the trip for now asks.
                val throughs: List<String?> = via?.ids ?: listOf(null)
                val each = destinations.flatMap { destination -> throughs.map { through -> async(io) { planTo(from, destination, through, request) } } }
                // Not for a trip via a stop the rider picked: the Planner routes via one stop, and theirs is the one asked.
                val toward = async { if (via == null) planToward(from, request) else Answer(emptyList()) }
                each.awaitAll() to toward.await()
            }
            val failures = answers.mapNotNull { it.exceptionOrNull() }
            val answered = answers.mapNotNull { it.getOrNull() }
            // A stop that didn't answer; or one that answered without its request via the last stop, or a request
            // via a stop towards the destination that failed: the routes in hand stand, said apart (Codex, #875).
            val stationsMissed = failures.isNotEmpty()
            val routesMissed = answered.any { it.viaFailed } || viaStops.viaFailed
            publishing.withLock {
                if (planned !== request) return@withLock
                if (answered.isEmpty()) {
                    _state.value = _state.value.copy(planning = false, error = errorKindOf(failures.firstOrNull()))
                    return@withLock
                }
                // The plan's own requests decide whether there's a plan; the stops towards the destination add to it.
                val routes = mergedRoutes(answered.map { it.routes }.reduce(::mergedRoutes), viaStops.routes)
                raw = routes
                _state.value = State(
                    request = latest ?: request,
                    cards = listed(routes, request.time, excluded.orEmpty()),
                    incomplete = stationsMissed,
                    routesMissed = routesMissed,
                )
            }
        }
    }

    // On [compute]: the routes keeping to the time and not riding what's [leftOut], less the detours the rest
    // beat, in order, as cards.
    @WorkerThread
    private fun listed(routes: List<TripRoute>, time: TripTime, leftOut: Set<String>): List<Card> {
        val stops = destinations.filterIsInstance<TripDestination.Stop>().associate { it.id to it.id } + destinationIds
        val kept = withoutDetours(routes.filter { it.legs.isNotEmpty() && time.keeps(it) && !AvoidedLines.drops(it, leftOut) }, stops.keys, stops)
        // A route the same as another in every leg's times is one card: the list's keys stay unique.
        return TripTime.ordered(kept, time).map(::cardOf).distinctBy { it.listKey }
    }

    // One destination's routes, and whether its request via the last stop failed (they stand, in part).
    private class Answer(val routes: List<TripRoute>, val viaFailed: Boolean = false)

    // The routes riding a bus from the stops towards the first destination ([TowardRequests]), and whether a
    // request for them failed.
    private suspend fun planToward(from: TripOrigin, request: Request): Answer {
        val destination = destinations.firstOrNull() ?: return Answer(emptyList())
        val found = mutableListOf<TripRoute>()
        // Each stop's requests land on their own coroutine: gathered under one lock.
        val gathering = Mutex()
        // Every bus line picks its stop, hidden or not: what's left out only lists the plan again here, so a stop
        // skipped for a line hidden now would stay unasked once the line is shown, with nothing to say so.
        val stood = toward.plan(
            from, destination, request.walkingSpeed, request.maxWalk, request.stepFree, request.tripModes, hidden = emptySet(), at = request.time,
        ) { routes -> gathering.withLock { found += routes } }
        return Answer(gathering.withLock { found.toList() }, viaFailed = !stood)
    }

    // On [io]. One destination's routes, via [through] where the rider picked a stop to go through, and to a place
    // the fewest changes via its fastest route's last stop as a trip for now asks ([FinalStop]); a failure of that
    // alone leaves the routes standing, as a partial answer.
    private suspend fun planTo(from: TripOrigin, destination: TripDestination, through: String?, request: Request): Result<Answer> {
        val routes = try {
            planner.journeys(from, destination, request.walkingSpeed, request.maxWalk, request.stepFree, request.tripModes, request.time, via = through)
        } catch (e: TflException) {
            // No route through the stop asked via at that time (the Planner's 404): an answer with none, as the trip
            // for now takes it, so a later trip with none through any of its stations says it found none.
            if (through != null && e is TflException.NotFound) {
                warn("later trip plan via a stop: no route through it")
                return Result.success(Answer(emptyList()))
            }
            warn("later trip plan failed: ${e::class.simpleName}")
            return Result.failure(e)
        }
        // Not for a trip via a stop the rider picked: the Planner routes via one stop, and theirs is the one asked.
        val finalStop = (if (destination is TripDestination.Place && through == null) FinalStop.of(routes) else null) ?: return Result.success(Answer(routes))
        val fewer = try {
            finalStop.fewerRides(
                planner.fewestChangesVia(from, destination, finalStop.stopId, request.walkingSpeed, request.maxWalk, request.stepFree, request.tripModes, request.time),
            )
        } catch (e: TflException) {
            // The stop isn't logged: it's a way to the rider's place.
            warn("later trip plan via the fastest route's last stop failed: ${e::class.simpleName}")
            return Result.success(Answer(routes, viaFailed = true))
        }
        return Result.success(Answer(mergedRoutes(routes, fewer)))
    }

    companion object {
        /** [route] as its card draws it: its times in London, its keys and a row per leg. */
        @WorkerThread
        internal fun cardOf(route: TripRoute): Card {
            val first = route.legs.first()
            val last = route.legs.last()
            val key = routeKey(route)
            return Card(
                route = route,
                routeKey = key,
                // Every leg's times: two routes riding the same lines through the same stops, leaving together
                // but changing onto different trains, are two cards.
                listKey = route.legs.joinToString(",", prefix = "$key@") { "${it.departure}-${it.arrival}" },
                departs = clockOf(first.departure),
                arrives = clockOf(last.arrival),
                minutes = Duration.between(first.departure, last.arrival).toMinutes().toInt(),
                rows = route.legs.map { leg ->
                    Row(
                        walk = leg.isWalk,
                        time = clockOf(leg.departure),
                        lineId = leg.lineId,
                        lineName = leg.lineName,
                        mode = leg.mode,
                        toName = leg.toName,
                        minutes = leg.run.toMinutes().toInt().coerceAtLeast(1),
                    )
                },
            )
        }

        private fun clockOf(at: Instant): String = at.atZone(LONDON).format(CLOCK)
    }
}
