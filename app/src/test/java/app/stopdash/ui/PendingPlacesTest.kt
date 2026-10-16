package app.stopdash.ui

import app.stopdash.domain.LineRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a cold load's still-loading stops show ([pendingPlaces], [pendingSlots]). */
class PendingPlacesTest {
    private val circle = LineRef("circle", "Circle", "tube")
    private val metropolitan = LineRef("metropolitan", "Metropolitan", "tube")

    @Test
    fun `a station's loading platforms make one card with every line`() {
        val places = pendingPlaces(
            listOf(
                StopRef("940GZZLUESQ1", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ"),
                StopRef("940GZZLUESQ2", "Euston Square", listOf(circle, metropolitan), clusterId = "940GZZLUESQ"),
            ),
            shownPlaces = emptySet(),
            stopDistanceMeters = mapOf("940GZZLUESQ1" to 90.0, "940GZZLUESQ2" to 80.0),
        )
        assertEquals(1, places.size)
        assertEquals(80.0, places.single().place.meters, 0.0)
        assertEquals(listOf("circle", "metropolitan"), places.single().place.lines.map { it.id })
    }

    @Test
    fun `a place already on screen gets no loading card`() {
        val places = pendingPlaces(
            listOf(StopRef("940GZZLUESQ2", "Euston Square", clusterId = "940GZZLUESQ")),
            shownPlaces = setOf("940GZZLUESQ"),
            stopDistanceMeters = mapOf("940GZZLUESQ2" to 80.0),
        )
        assertEquals(emptyList<PendingPlace>(), places)
    }

    @Test
    fun `the near-me list leaves out a stop that isn't nearby`() {
        // A journey's farther origin is fetched with the list but shown on its card, not here.
        val places = pendingPlaces(
            listOf(StopRef("940GZZLUESQ", "Euston Square"), StopRef("940GZZLUWHM", "West Ham")),
            shownPlaces = emptySet(),
            stopDistanceMeters = mapOf("940GZZLUESQ" to 80.0),
        )
        assertEquals(listOf("Euston Square"), places.map { it.place.name })
    }

    @Test
    fun `on the watched list a loading card has no distance and goes to the foot`() {
        val places = pendingPlaces(listOf(StopRef("940GZZLUESQ", "Euston Square")), emptySet(), emptyMap())
        assertFalse(places.single().distanced)
        assertEquals(listOf(2), pendingSlots(listOf(null, null), listOf(false, false), places))
    }

    @Test
    fun `a loading card goes where its distance puts it, below the starred band`() {
        fun at(meters: Double) = pendingPlaces(listOf(StopRef("s$meters", "Stop")), emptySet(), mapOf("s$meters" to meters)).single()
        // A starred place far off leads; then places at 100 m and 300 m.
        val meters = listOf(900.0, 100.0, 300.0)
        val pinned = listOf(true, false, false)
        assertEquals(
            listOf(1, 2, 3),
            pendingSlots(meters, pinned, listOf(at(50.0), at(200.0), at(1_000.0))),
        )
    }

    @Test
    fun `a loading card never splits a starred station's groups`() {
        val near = pendingPlaces(listOf(StopRef("s", "Stop")), emptySet(), mapOf("s" to 50.0)).single()
        // A starred station's two platform groups, both in the starred band, then a place at 300 m.
        assertEquals(listOf(2), pendingSlots(listOf(900.0, 900.0, 300.0), listOf(true, true, false), listOf(near)))
    }

    @Test
    fun `a hidden mode leaves the loading cards too`() {
        val bus = LineRef("73", "73", "bus")
        val shown = visiblePending(
            listOf(
                StopRef("490000001A", "Example Road", listOf(bus)),
                StopRef("940GZZLUESQ", "Euston Square", listOf(circle, bus)),
            ),
            setOf("bus"),
        )
        assertEquals(listOf("Euston Square"), shown.map { it.name })
        assertEquals(listOf("circle"), shown.single().lines.map { it.id })
    }

    @Test
    fun `a stop still loading counts as reached for the farther cards`() {
        val partial = DeparturesUiState.Loaded(
            stops = listOf(app.stopdash.domain.StopArrivals("A", "A", emptyList(), java.time.Instant.EPOCH)),
            fetchedAt = java.time.Instant.EPOCH,
            pendingStops = listOf(StopRef("B", "B")),
            statusPending = true,
        )
        assertEquals(setOf("A", "B"), reachedStopIds(partial))
        // Once the batch is whole, only what came back counts: a failed stop's lines keep their cards.
        assertEquals(setOf("A"), reachedStopIds(partial.copy(pendingStops = emptyList(), statusPending = false)))
        assertEquals(null, reachedStopIds(DeparturesUiState.Loading))
    }

    private fun place(id: String, name: String) =
        pendingPlaces(listOf(StopRef(id, name)), emptySet(), emptyMap()).single()

    @Test
    fun `a place that lands on screen is held as a card, one off screen isn't`() {
        val near = place("940GZZLUESQ", "Euston Square")
        val far = place("910GEUSTON", "London Euston")
        val tracker = PendingTracker()
        tracker.update(listOf(near, far))
        // Both land; only Euston Square's card was drawn.
        tracker.onScreen = setOf(pendingItemKey(near))
        val held = tracker.update(emptyList())
        assertEquals(setOf(near.placeKey), held.keys)
    }

    @Test
    fun `a held card keeps its place among the loading ones`() {
        val a = place("A", "A")
        val b = place("B", "B")
        val tracker = PendingTracker()
        tracker.update(listOf(a, b))
        tracker.onScreen = setOf(pendingItemKey(a))
        tracker.update(listOf(b))
        assertTrue(tracker.order(a.placeKey) < tracker.order(b.placeKey))
    }

    @Test
    fun `a held card says tap to see, a dash with nothing running, and goes if it failed`() {
        val p = place("940GZZLUESQ", "Euston Square")
        assertEquals(FartherCue.TAP_TO_SEE, heldCue(p, setOf(p.placeKey), setOf("940GZZLUESQ")))
        assertEquals(FartherCue.NO_DEPARTURES, heldCue(p, emptySet(), setOf("940GZZLUESQ")))
        assertEquals(null, heldCue(p, emptySet(), emptySet()))
        // Nothing running and a notice saying the stop is closed, by stop or by place (a hub's
        // folded notice names one pole); groups to open still win.
        val closedStop = ClosedStops(setOf("940GZZLUESQ"), emptySet())
        assertEquals(FartherCue.CLOSED, heldCue(p, emptySet(), setOf("940GZZLUESQ"), closedStop))
        assertEquals(FartherCue.CLOSED, heldCue(p, emptySet(), emptySet(), ClosedStops(emptySet(), setOf(p.placeKey))))
        assertEquals(FartherCue.TAP_TO_SEE, heldCue(p, setOf(p.placeKey), setOf("940GZZLUESQ"), closedStop))
    }

    @Test
    fun `held cards survive a recreated screen`() {
        val p = pendingPlaces(
            listOf(StopRef("940GZZLUESQ", "Euston Square", listOf(circle))),
            emptySet(),
            mapOf("940GZZLUESQ" to 80.0),
        ).single()
        val tracker = PendingTracker()
        tracker.update(listOf(p))
        tracker.onScreen = setOf(pendingItemKey(p))
        tracker.update(emptyList())

        val restored = recreated(tracker)
        assertEquals(mapOf(p.placeKey to p), restored.update(emptyList()))
        assertEquals(0, restored.order(p.placeKey))
    }

    @Test
    fun `a stop landing mid-rotation still holds the card that was on screen`() {
        val p = place("940GZZLUESQ", "Euston Square")
        val tracker = PendingTracker()
        tracker.update(listOf(p))
        tracker.onScreen = setOf(pendingItemKey(p))

        // Recreated while still loading; it lands before the new list draws a frame.
        val restored = recreated(tracker)
        assertEquals(setOf(p.placeKey), restored.update(emptyList()).keys)
    }

    @Test
    fun `a held card keeps its stops' lines for its marks, across stops landing and a recreated screen`() {
        val a = StopRef("A", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ")
        val b = StopRef("B", "Euston Square", listOf(circle, metropolitan), clusterId = "940GZZLUESQ")
        val tracker = PendingTracker()
        tracker.update(pendingPlaces(listOf(a, b), emptySet(), emptyMap()))
        // A comes back first; B's lines are still known once it lands too.
        tracker.update(pendingPlaces(listOf(b), emptySet(), emptyMap()))
        tracker.onScreen = setOf(pendingItemKey(pendingPlaces(listOf(b), emptySet(), emptyMap()).single()))
        val held = tracker.update(emptyList()).values.single()
        // Its board asks for each line only at the stops that serve it, after a recreated screen too.
        val board = heldBoard(held)!!.recipe()
        assertEquals(board, heldBoard(recreated(tracker).update(emptyList()).values.single())!!.recipe())
        assertEquals(
            setOf(
                app.stopdash.domain.EmptyTimes.Key("A", "circle"),
                app.stopdash.domain.EmptyTimes.Key("B", "circle"),
                app.stopdash.domain.EmptyTimes.Key("B", "metropolitan"),
            ),
            board.keys.toSet(),
        )
    }

    @Test
    fun `a held card's board tag holds across frames and changes only when stops land`() {
        val a = StopRef("A", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ")
        val b = StopRef("B", "Euston Square", listOf(circle, metropolitan), clusterId = "940GZZLUESQ")
        val tracker = PendingTracker()
        val first = tracker.widened(pendingPlaces(listOf(a), emptySet(), emptyMap()).single())
        tracker.update(listOf(first))
        val tag = heldBoard(first)!!.tag
        // Re-measured and refiltered each frame: the same tag, so the card keeps its mark.
        assertEquals(tag, heldBoard(remeasured(first, mapOf("A" to 120.0)))!!.tag)
        assertEquals(tag, heldBoard(withoutHidden(first, setOf("bus"))!!)!!.tag)
        // Another stop landing is a new board, with no mark yet.
        val widened = tracker.widened(pendingPlaces(listOf(b), emptySet(), emptyMap()).single())
        assertNotEquals(tag, heldBoard(widened)!!.tag)
    }

    private fun recreated(tracker: PendingTracker): PendingTracker {
        val saved = with(PendingTracker.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(tracker) }!!
        return PendingTracker.Saver.restore(saved)!!
    }

    @Test
    fun `a held card remembers a stop that came back before the rest`() {
        fun station(vararg ids: String) =
            pendingPlaces(ids.map { StopRef(it, "Euston Square", clusterId = "940GZZLUESQ") }, emptySet(), emptyMap()).single()
        val tracker = PendingTracker()
        tracker.update(listOf(station("A", "B")))
        // A comes back empty; B is still out, then fails while the card is on screen.
        tracker.update(listOf(station("B")))
        tracker.onScreen = setOf(pendingItemKey(station("B")))
        val held = tracker.update(emptyList()).values.single()
        assertEquals(setOf("A", "B"), held.stopIds)
        assertEquals(FartherCue.NO_DEPARTURES, heldCue(held, emptySet(), setOf("A")))
    }

    @Test
    fun `a loading card keeps the lines and distance of a stop already back`() {
        val a = StopRef("A", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ")
        val b = StopRef("B", "Euston Square", listOf(metropolitan), clusterId = "940GZZLUESQ")
        val meters = mapOf("A" to 80.0, "B" to 90.0)
        val tracker = PendingTracker()
        tracker.update(pendingPlaces(listOf(a, b), emptySet(), meters))
        // A comes back empty; the card for B still shows both lines, at A's distance.
        val onlyB = pendingPlaces(listOf(b), emptySet(), meters)
        tracker.update(onlyB)
        val card = tracker.widened(onlyB.single())
        assertEquals(setOf("circle", "metropolitan"), card.place.lines.mapTo(HashSet()) { it.id })
        assertEquals(80.0, card.place.meters, 0.0)
    }

    @Test
    fun `a hidden mode comes off a held card, and a card of only that mode goes`() {
        val bus = LineRef("73", "73", "bus")
        val mixed = pendingPlaces(listOf(StopRef("X", "X", listOf(circle, bus))), emptySet(), emptyMap()).single()
        val busOnly = pendingPlaces(listOf(StopRef("Y", "Y", listOf(bus))), emptySet(), emptyMap()).single()
        assertEquals(listOf("circle"), withoutHidden(mixed, setOf("bus"))!!.place.lines.map { it.id })
        assertEquals(null, withoutHidden(busOnly, setOf("bus")))
    }

    @Test
    fun `a recreated screen keeps what an off-screen card knew and which cards were opened`() {
        val a = StopRef("A", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ")
        val b = StopRef("B", "Euston Square", listOf(metropolitan), clusterId = "940GZZLUESQ")
        val tracker = PendingTracker()
        tracker.update(pendingPlaces(listOf(a, b), emptySet(), emptyMap()))
        val onlyB = pendingPlaces(listOf(b), emptySet(), emptyMap())
        tracker.update(onlyB)
        tracker.opened = setOf("other")

        // Recreated with the card off screen; later it's on screen when B fails.
        val restored = recreated(tracker)
        assertEquals(setOf("other"), restored.opened)
        restored.update(onlyB)
        restored.onScreen = setOf(pendingItemKey(onlyB.single()))
        assertEquals(setOf("A", "B"), restored.update(emptyList()).values.single().stopIds)
    }

    @Test
    fun `a failed held place is let go, so a later load isn't held`() {
        val p = place("940GZZLUESQ", "Euston Square")
        val tracker = PendingTracker()
        tracker.update(listOf(p))
        tracker.onScreen = setOf(pendingItemKey(p))
        assertEquals(setOf(p.placeKey), tracker.update(emptyList()).keys)

        val before = tracker.revision
        tracker.forget(p.placeKey)
        // A cached held map is taken again, since the list's inputs haven't changed.
        assertEquals(before + 1, tracker.revision)
        assertTrue(tracker.update(emptyList()).isEmpty())
        tracker.forget(p.placeKey)
        assertEquals(before + 1, tracker.revision)
    }

    @Test
    fun `a kept card takes its distance from the current fix`() {
        val stops = listOf(StopRef("A", "Euston Square", clusterId = "940GZZLUESQ"), StopRef("B", "Euston Square", clusterId = "940GZZLUESQ"))
        val p = pendingPlaces(stops, emptySet(), mapOf("A" to 100.0, "B" to 300.0)).single()
        assertEquals(100.0, p.place.meters, 0.0)

        // Same stops, a new fix: the nearest is now B.
        val moved = remeasured(p, mapOf("A" to 800.0, "B" to 400.0))
        assertEquals(400.0, moved.place.meters, 0.0)
        assertTrue(moved.distanced)
        // The watched list has no distances: the card stays as it was.
        assertEquals(p, remeasured(p, emptyMap()))
    }

    @Test
    fun `a restored tracker waits out an unknown list, and a different list starts afresh`() {
        val p = place("940GZZLUESQ", "Euston Square")
        val holder = PendingTrackerHolder()
        val tracker = holder.trackerFor("set-a")
        tracker.update(listOf(p))
        tracker.onScreen = setOf(pendingItemKey(p))
        tracker.update(emptyList())
        val saved = with(PendingTrackerHolder.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(holder) }!!
        val restored = PendingTrackerHolder.Saver.restore(saved)!!

        // After process death the nearby set isn't known yet: the restored tracker stays.
        val kept = restored.trackerFor(null)
        assertSame(kept, restored.trackerFor("set-a"))
        assertEquals(setOf(p.placeKey), kept.update(emptyList()).keys)
        assertTrue(restored.trackerFor("set-b").update(emptyList()).isEmpty())
    }

    @Test
    fun `an opened card keeps its slot until the list refreshes`() {
        val p = place("940GZZLUESQ", "Euston Square")
        val tracker = PendingTracker()
        tracker.update(listOf(p))
        tracker.onScreen = setOf(pendingItemKey(p))
        tracker.update(emptyList())
        tracker.opened += p.placeKey

        val fetched = java.time.Instant.parse("2026-01-01T08:00:00Z")
        assertTrue(tracker.openedToRelease(fetched).isEmpty())
        assertTrue(tracker.openedToRelease(fetched).isEmpty())
        // The clock dropping a gone departure isn't a refresh (no newer fetch).
        assertTrue(tracker.openedToRelease(fetched.minusSeconds(30)).isEmpty())
        // A refresh: the card lets go, and its rows join the list.
        assertEquals(listOf(p.placeKey), tracker.openedToRelease(fetched.plusSeconds(60)))
        tracker.forget(p.placeKey)
        assertTrue(tracker.update(emptyList()).isEmpty())
        assertTrue(tracker.opened.isEmpty())
    }

    @Test
    fun `only a card with departures drawn below it would push them`() {
        // A loading card, a place's departures, then another loading card and a farther card.
        val keys = listOf("pending|pending:A", "header|B", "card|B", "pending|pending:C", "farther|D")
        assertEquals(setOf("pending|pending:A", "header|B"), aboveLoadedRows(keys))
        // Nothing loaded drawn below: every card opens where it is.
        assertTrue(aboveLoadedRows(listOf("pending|pending:A", "farther|D")).isEmpty())
        // A journey's trains count as loaded times too.
        assertEquals(setOf("pending|pending:A"), aboveLoadedRows(listOf("pending|pending:A", "journey-card|J|G")))
        assertEquals(setOf("pending|pending:A"), aboveLoadedRows(listOf("pending|pending:A", "journey-change-card|J|S|G")))
        // A journey's settled "no trains" counts too.
        assertEquals(setOf("pending|pending:A"), aboveLoadedRows(listOf("pending|pending:A", "journey-none|J")))
        // A watched-list card is held wherever it's drawn, loaded rows below it or not.
        assertEquals(
            setOf("pending|pending:A"),
            aboveLoadedRows(listOf("card|B", "pending|pending:A"), alwaysHold = setOf("pending|pending:A", "pending|pending:Z")),
        )
    }

    @Test
    fun `a stop notice slots by distance like a loading card`() {
        // Groups at 80 m and 400 m; a notice at 250 m goes between them, one with no distance at the foot.
        assertEquals(listOf(1, 2), distanceSlots(listOf(80.0, 400.0), listOf(false, false), listOf(250.0, null)))
    }
}
