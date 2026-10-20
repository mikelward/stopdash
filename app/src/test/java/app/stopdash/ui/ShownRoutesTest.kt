package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripFare
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A trip page's routes as drawn ([ShownRoutes]): a last ride arriving by hub drawn cut at its first call there,
 * the routes as planned never changed. Through the synthetic Hill model ([HillModel]); made-up stops and lines.
 */
class ShownRoutesTest {
    private val t = Instant.parse("2026-10-20T08:00:00Z")

    // Red from A to M, then blue from M to Hill's tube station calling at Hill East first, then a walk on in the hub.
    private val red = TripLeg(
        mode = "tube", lineId = "red", lineName = "red", fromId = "A", fromName = "A", toId = "M", toName = "M",
        departure = t, arrival = t.plusSeconds(120), path = listOf("M"), pathNames = listOf("M"),
    )
    private val blue = TripLeg(
        mode = "tube", lineId = "blue", lineName = "blue", fromId = "M", fromName = "M", toId = "940GZZLUHIL", toName = "Hill",
        departure = t.plusSeconds(300), arrival = t.plusSeconds(600),
        path = listOf("940GZZLUHLE", "B", "940GZZLUHIL"), pathNames = listOf("Hill East", "Bee", "Hill"),
    )
    private val walkOn = TripLeg(TripLeg.WALKING, "", "", "940GZZLUHIL", "Hill", "910GHILL", "Hill", t.plusSeconds(600), t.plusSeconds(780))
    private val changing = TripRoute(listOf(red, blue, walkOn), fare = TripFare(290))

    // Green from A runs on through M to Hill East and Hill: a train through the change.
    private val green = mapOf(
        "green" to LineSequence(
            routes = listOf(LineRoute("r", listOf("A", "M", "940GZZLUHLE", "940GZZLUHIL"))),
            stopNames = mapOf("A" to "A", "M" to "M", "940GZZLUHLE" to "Hill East", "940GZZLUHIL" to "Hill"),
        ),
    )
    private val planned = TripViewModel.State(
        routes = listOf(changing),
        live = mapOf("A" to TripViewModel.StopLive(listOf(Departure("green", "green", "", "Hill", null, t, mode = "tube")), t)),
    )

    @Test
    fun `a last ride arriving by hub is drawn cut, the route as planned unchanged`() {
        val shown = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val drawn = shown.state.routes.orEmpty().single()
        assertEquals(listOf("A", "M"), drawn.legs.map { it.fromId })
        assertEquals("940GZZLUHLE", drawn.legs.last().toId)
        assertEquals("Hill East", drawn.legs.last().toName)
        // The walk on, and the train's fare to its old end, aren't drawn.
        assertEquals(null, drawn.fare)
        // The state as planned, and its route, are as they were.
        assertTrue(planned.routes!!.single() === changing)
        assertEquals(listOf(red, blue, walkOn), changing.legs)
        // It is the route as planned wherever routes are told apart, and a Start starts that route.
        assertTrue(drawn.drawnFrom === changing)
        assertEquals(routeKey(changing), routeKey(drawn))
        assertTrue(shown.plannedOf(drawn) === changing)
        // Worked out for the state as placed, by identity.
        assertTrue(shown.of === planned)
        assertFalse(shown.pending)
        // A step-free trip, or no model: drawn as planned.
        assertTrue(ShownRoutes.of(planned, StepFree.STATION, HillModel.toHill).state.routes!!.single() === changing)
        assertTrue(ShownRoutes.of(planned, StepFree.ANY, null).state.routes!!.single() === changing)
    }

    @Test
    fun `a Direct row's route is drawn as planned`() {
        val direct = planned.copy(directKeys = setOf(routeKey(changing)))
        assertTrue(ShownRoutes.of(direct, StepFree.ANY, HillModel.toHill).state.routes!!.single() === changing)
    }

    @Test
    fun `a train through a change is made from the routes as planned and drawn cut too, and starts as planned`() {
        val shown = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val drawnChanging = shown.state.routes.orEmpty().single()
        // Made from the routes as planned and drawn cut as they are ([withThroughRoutes]): green to Hill East, no walk on.
        val through = withThroughRoutes(shown.state, StepFree.ANY, green, ends = shown.ends::ends, planned = shown.of)
            .routes.orEmpty().single { routeKey(it) != routeKey(drawnChanging) }
        assertEquals("green", through.legs.single().lineId)
        assertEquals("940GZZLUHLE", through.legs.single().toId)
        assertEquals("Hill East", through.legs.single().toName)
        // Drawn from the train through as planned: green to the Planner's end, Hill, and the walk on from there.
        val asPlanned = checkNotNull(through.drawnFrom)
        assertEquals(listOf("green", ""), asPlanned.legs.map { it.lineId })
        assertEquals("940GZZLUHIL", asPlanned.legs.first().toId)
        assertEquals(walkOn, asPlanned.legs.last())
        assertEquals(legsKey(asPlanned), routeKey(through))
        // Opened, it's that train, made from the plan: found again among the routes as planned ([OpenRoute.routeIn]).
        val open = openRouteOf(through, shown.of, StepFree.ANY, green, ends = shown.ends::ends)
        assertEquals(routeKey(through), open.key)
        assertEquals(routeKey(changing), open.plan)
        assertEquals("940GZZLUHIL", open.ride?.toId)
        assertEquals(asPlanned.legs, open.routeIn(shown.of.routes.orEmpty())?.legs)
        assertEquals(drawnChanging, OpenRoute(routeKey(drawnChanging)).routeIn(shown.state.routes.orEmpty()))
        // Its Start starts it as planned: the walk on kept.
        assertTrue(shown.plannedOf(through) === asPlanned)
        // A route not cut starts as itself.
        val other = TripRoute(listOf(red))
        assertTrue(shown.plannedOf(other) === other)
    }

    @Test
    fun `a train through a change matching a route as drawn is left to the Planner's copy`() {
        // Green from A to Hill with the walk on, planned as a route of its own, drawn cut at Hill East: the train
        // through the change at M is that route as planned, so it isn't offered again.
        val greenRide = red.copy(
            lineId = "green", lineName = "green", toId = "940GZZLUHIL", toName = "Hill", arrival = t.plusSeconds(600),
            path = listOf("M", "940GZZLUHLE", "940GZZLUHIL"), pathNames = listOf("M", "Hill East", "Hill"),
        )
        val direct = TripRoute(listOf(greenRide, walkOn))
        val both = planned.copy(routes = listOf(changing, direct))
        val shown = ShownRoutes.of(both, StepFree.ANY, HillModel.toHill)
        val routes = withThroughRoutes(shown.state, StepFree.ANY, green, ends = shown.ends::ends, planned = shown.of).routes.orEmpty()
        assertEquals(shown.state.routes, routes)
    }

    @Test
    fun `routes standing in while they're worked out are as planned, with the last answer's ends`() {
        val shown = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val placed = planned.copy(plannedAt = t)
        val pending = ShownRoutes.pending(placed, StepFree.ANY, shown)
        assertTrue(pending.pending)
        assertTrue(pending.state === placed)
        assertEquals(shown.ends, pending.ends)
        // No routes: nothing to wait for.
        assertFalse(ShownRoutes.pending(TripViewModel.State(), StepFree.ANY, null).pending)
    }

    @Test
    fun `an open route drawn cut stays open drawn as planned at another step-free level, and back`() {
        val cut = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val drawnCut = cut.state.routes.orEmpty().single()
        // Opened from its card, by its key: the route as planned's.
        val opened = OpenRoute(routeKey(drawnCut))
        assertEquals(OpenRoute(routeKey(changing)), opened)
        assertEquals(opened, OpenRoute.parse(opened.encode()))
        assertEquals(drawnCut, opened.routeIn(cut.state.routes.orEmpty()))
        // Step-free: drawn as planned, and still the route open, under the same key.
        val stepFree = ShownRoutes.of(planned, StepFree.STATION, HillModel.toHill)
        assertEquals(changing, opened.routeIn(stepFree.state.routes.orEmpty()))
        // A plan without it: none, to be closed ([openRouteGone]).
        assertEquals(null, opened.routeIn(listOf(TripRoute(listOf(red)))))
    }

    @Test
    fun `an open train through a change made where the plan is cut starts whole, and is drawn as planned step-free`() {
        val cut = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val through = withThroughRoutes(cut.state, StepFree.ANY, green, ends = cut.ends::ends, planned = cut.of)
            .routes.orEmpty().single { it.legs.first().lineId == "green" }
        val opened = openRouteOf(through, cut.of, StepFree.ANY, green, ends = cut.ends::ends)
        // Made from the plan, by the plan's key, and saved as it is.
        assertEquals(routeKey(changing), opened.plan)
        assertEquals(opened, OpenRoute.parse(opened.encode()))
        // As the page finds it again, with no green train predicted now: among the routes as planned, drawn cut.
        val found = checkNotNull(opened.routeIn(cut.of.routes.orEmpty()))
        val drawn = withThroughRoutes(cut.state, StepFree.ANY, emptyMap(), open = found, ends = cut.ends::ends, planned = cut.of, openRef = opened)
            .routes.orEmpty().single { routeKey(it) == opened.key }
        assertEquals("940GZZLUHLE", drawn.legs.single().toId)
        // Its Start hands on a route connected end to end: green to the Planner's end, Hill, and the walk on from there.
        val starts = cut.plannedOf(drawn)
        assertEquals(listOf("green", ""), starts.legs.map { it.lineId })
        assertEquals("940GZZLUHIL", starts.legs.first().toId)
        assertEquals(starts.legs.first().toId, starts.legs.last().fromId)
        assertEquals(walkOn, starts.legs.last())
        // Step-free, the same route open is drawn as planned, and starts as itself.
        val stepFree = ShownRoutes.of(planned, StepFree.STATION, HillModel.toHill)
        val atStepFree = checkNotNull(opened.routeIn(stepFree.of.routes.orEmpty()))
        val drawnStepFree = withThroughRoutes(stepFree.state, StepFree.STATION, emptyMap(), open = atStepFree, ends = stepFree.ends::ends, planned = stepFree.of, openRef = opened)
            .routes.orEmpty().single { routeKey(it) == opened.key }
        assertEquals(listOf("green", ""), drawnStepFree.legs.map { it.lineId })
        assertEquals("940GZZLUHIL", drawnStepFree.legs.first().toId)
        assertTrue(stepFree.plannedOf(drawnStepFree) === drawnStepFree)
        // It reaches the Planner's end, so it isn't one only a hub offers.
        assertFalse(hubOnlyUnchecked(opened, stepFree.of.routes.orEmpty(), stepFree.ends::ends))
    }

    @Test
    fun `an open train through getting off at the Planner's station under another of its ids is kept step-free`() {
        val cut = ShownRoutes.of(planned, StepFree.ANY, HillModel.toHill)
        val through = withThroughRoutes(cut.state, StepFree.ANY, green, ends = cut.ends::ends, planned = cut.of)
            .routes.orEmpty().single { it.legs.first().lineId == "green" }
        val opened = openRouteOf(through, cut.of, StepFree.ANY, green, ends = cut.ends::ends)
        // The same train getting off at Hill under a sibling id, as `runTo` accepts ([RouteStops.sameStationId]),
        // opened with the walk on kept, as one made now would be.
        val sibling = opened.copy(ride = checkNotNull(opened.ride).copy(toId = "HUB_SIBLING"))
        val rebuilt = checkNotNull(sibling.routeIn(cut.of.routes.orEmpty()))
        assertEquals(listOf("green", ""), rebuilt.legs.map { it.lineId })
        val open = sibling.copy(key = legsKey(rebuilt))
        val stepFree = ShownRoutes.of(planned, StepFree.STATION, HillModel.toHill)
        assertFalse(open.endsShort(stepFree.of.routes.orEmpty()))
        assertFalse(hubOnlyUnchecked(open, stepFree.of.routes.orEmpty(), stepFree.ends::ends))
    }

    @Test
    fun `an open train through reaching only another station of the hubs is found again connected, with no walk on, and dropped step-free`() {
        // Yellow from A runs on through M to Hill East and ends there: it never reaches the Planner's end, Hill.
        val yellow = mapOf(
            "yellow" to LineSequence(
                routes = listOf(LineRoute("r", listOf("A", "M", "940GZZLUHLE"))),
                stopNames = mapOf("A" to "A", "M" to "M", "940GZZLUHLE" to "Hill East"),
            ),
        )
        val board = planned.copy(
            live = mapOf("A" to TripViewModel.StopLive(listOf(Departure("yellow", "yellow", "", "Hill East", null, t, mode = "tube")), t)),
        )
        val cut = ShownRoutes.of(board, StepFree.ANY, HillModel.toHill)
        val through = withThroughRoutes(cut.state, StepFree.ANY, yellow, ends = cut.ends::ends, planned = cut.of)
            .routes.orEmpty().single { it.legs.first().lineId == "yellow" }
        assertEquals(listOf("940GZZLUHLE"), through.legs.map { it.toId })
        val opened = openRouteOf(through, cut.of, StepFree.ANY, yellow, ends = cut.ends::ends)
        // Its prediction gone, or the screen restored: found again among the routes as planned, as it was opened,
        // yellow to Hill East with no walk on from Hill.
        for (open in listOf(opened, checkNotNull(OpenRoute.parse(opened.encode())))) {
            val found = checkNotNull(open.routeIn(cut.of.routes.orEmpty()))
            assertEquals(listOf("yellow"), found.legs.map { it.lineId })
            assertEquals("940GZZLUHLE", found.legs.single().toId)
            assertEquals(routeKey(through), routeKey(found))
            val drawn = withThroughRoutes(cut.state, StepFree.ANY, emptyMap(), open = found, ends = cut.ends::ends, planned = cut.of, openRef = open)
                .routes.orEmpty().single { routeKey(it) == open.key }
            assertEquals(found.legs, drawn.legs)
            // Its Start hands on the same connected route.
            assertEquals(found.legs, cut.plannedOf(drawn).legs)
            assertFalse(hubOnlyUnchecked(open, cut.of.routes.orEmpty(), cut.ends::ends))
            // Step-free, the Planner's call is the one checked: a train reaching only Hill East isn't offered, so
            // the page neither draws it nor keeps it open ([openRouteGone]'s close applies), though the plan it's
            // made from is still there.
            val stepFree = ShownRoutes.of(board, StepFree.STATION, HillModel.toHill)
            val atStepFree = checkNotNull(open.routeIn(stepFree.of.routes.orEmpty()))
            assertTrue(hubOnlyUnchecked(open, stepFree.of.routes.orEmpty(), stepFree.ends::ends))
            val drawnStepFree = withThroughRoutes(stepFree.state, StepFree.STATION, emptyMap(), open = atStepFree, ends = stepFree.ends::ends, planned = stepFree.of, openRef = open)
                .routes.orEmpty()
            assertTrue(drawnStepFree.none { routeKey(it) == open.key })
            assertEquals(stepFree.state.routes, drawnStepFree)
            // Back at Any, it's drawn again.
            assertTrue(
                withThroughRoutes(cut.state, StepFree.ANY, emptyMap(), open = found, ends = cut.ends::ends, planned = cut.of, openRef = open)
                    .routes.orEmpty().any { routeKey(it) == open.key },
            )
        }
    }

    @Test
    fun `two plans the cut draws alike stay two, each opening and starting its own plan`() {
        // Blue to Hill with the walk on, and the same blue ride to Hill without it: two plans, one drawing once cut.
        val withWalk = TripRoute(listOf(blue, walkOn))
        val riding = TripRoute(listOf(blue))
        val state = TripViewModel.State(routes = listOf(withWalk, riding))
        val shown = ShownRoutes.of(state, StepFree.ANY, HillModel.toHill)
        val drawn = shown.state.routes.orEmpty()
        // Both drawn, in the Planner's order, alike leg for leg ...
        assertEquals(2, drawn.size)
        assertEquals(drawn[0].legs, drawn[1].legs)
        assertEquals(legsKey(drawn[0]), legsKey(drawn[1]))
        // ... yet each is its own plan, by key.
        assertTrue(drawn[0].drawnFrom === withWalk)
        assertTrue(drawn[1].drawnFrom === riding)
        assertEquals(routeKey(withWalk), routeKey(drawn[0]))
        assertEquals(routeKey(riding), routeKey(drawn[1]))
        assertTrue(routeKey(drawn[0]) != routeKey(drawn[1]))
        // Each card opened opens its own plan, drawn cut, and its Start starts that plan, whichever ranks first.
        for ((index, plan) in listOf(withWalk, riding).withIndex()) {
            val open = OpenRoute(routeKey(drawn[index]))
            val route = checkNotNull(open.routeIn(drawn))
            assertTrue(route === drawn[index])
            assertTrue(shown.plannedOf(route) === plan)
            assertTrue(open.routeIn(drawn.reversed()) === drawn[index])
            // At a step-free level that draws them as planned, still its own.
            assertTrue(open.routeIn(ShownRoutes.of(state, StepFree.STATION, HillModel.toHill).state.routes.orEmpty()) === plan)
        }
        // A later trip's list keeps both as cards, each keyed as its own plan.
        val cards = drawn.map { LaterTripViewModel.cardOf(it) }
        assertEquals(listOf(routeKey(withWalk), routeKey(riding)), cards.map { it.routeKey })
        assertEquals(2, cards.distinctBy { it.listKey }.size)
        // The page ranks both, neither standing for the other.
        val estimates = tripEstimates(shown.state, t, java.time.Duration.ZERO, emptyMap())
        assertEquals(setOf(routeKey(withWalk), routeKey(riding)), estimates.orEmpty().map { routeKey(it.route) }.toSet())
    }
}
