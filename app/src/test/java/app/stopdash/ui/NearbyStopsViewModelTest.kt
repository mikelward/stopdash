package app.stopdash.ui

import app.stopdash.domain.Coordinates
import app.stopdash.domain.LineRef
import app.stopdash.domain.LocationFix
import app.stopdash.domain.LocationProvider
import app.stopdash.domain.StopFinder
import app.stopdash.domain.StopLocation
import app.stopdash.domain.TflException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyStopsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // Obviously-synthetic coordinates around the origin — never a real position (SPEC Privacy).
    private val origin = Coordinates(0.0, 0.0)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A location provider that hands back a fixed fix (or null for "no fix"); [isFallback] marks
     *  it a low-confidence last-known fix (a fresh fix failed). */
    private class FakeLocation(
        private val fix: Coordinates?,
        private val isFallback: Boolean = false,
    ) : LocationProvider {
        override suspend fun current(forceFresh: Boolean): LocationFix? =
            fix?.let { LocationFix(it, isFallback = isFallback) }
    }

    /** A location provider whose fix (and its confidence) can change between calls — for re-locate
     *  on refresh. It records the last [forceFresh] it was asked for, so a test can assert relocate
     *  forces a fresh fix (a cached one would re-resolve for the previous position). */
    private class MutableLocation(
        var fix: Coordinates?,
        var isFallback: Boolean = false,
        var accuracyMeters: Float? = null,
    ) : LocationProvider {
        var lastForceFresh: Boolean? = null
        override suspend fun current(forceFresh: Boolean): LocationFix? {
            lastForceFresh = forceFresh
            return fix?.let { LocationFix(it, isFallback = isFallback, accuracyMeters = accuracyMeters) }
        }
    }

    /** A finder that returns a fixed list, or throws, and records the query it was given. */
    private class FakeFinder(
        private val supply: () -> List<StopLocation>,
    ) : StopFinder {
        var lastLatitude: Double? = null
        var lastLongitude: Double? = null
        var lastRadius: Int? = null

        override suspend fun nearbyStops(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            stopTypes: List<String>,
        ): List<StopLocation> {
            lastLatitude = latitude
            lastLongitude = longitude
            lastRadius = radiusMeters
            return supply()
        }
    }

    // With the nearby surface on screen, as it is when the list is shown.
    private fun vm(location: LocationProvider, finder: StopFinder) =
        NearbyStopsViewModel(location = location, finder = finder, io = dispatcher)
            .also { it.resumeRefining() }

    // A stop [meters] due north of the origin (lon 0), so distance is controllable in meters.
    private fun stop(id: String, meters: Double, mode: String) = StopLocation(
        id = id,
        name = id,
        latitude = meters / 111_320.0, // ~meters per degree of latitude
        longitude = 0.0,
        lines = listOf(LineRef("$mode-$id", id, mode)),
    )

    @Test
    fun `a station's own stops are where the rider stands, 0 m away`() = runTest {
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { listOf(stop("own", 150.0, "tube"), stop("b1", 80.0, "bus")) },
            io = dispatcher,
            anchorStopIds = setOf("own"),
        )
        model.locate()
        advanceUntilIdle()
        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(0.0, ready.distanceMeters.getValue("own"), 0.0)
        assertEquals(80.0, ready.distanceMeters.getValue("b1"), 1.0)
    }

    @Test
    fun `a station's own stop keeps its real place but not a hidden mode's lines`() = runTest {
        val mixed = stop("own", 300.0, "tube").let { it.copy(lines = it.lines + LineRef("bus-own", "own", "bus")) }
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { listOf(mixed) },
            io = dispatcher,
            hiddenModes = { setOf("bus") },
            anchorStopIds = setOf("own"),
        )
        model.locate()
        advanceUntilIdle()
        val own = (model.state.value as NearbyStopsViewModel.State.Ready).eager.single().stops.single()
        assertEquals(listOf("tube"), own.lines.map { it.mode })
        assertEquals(300.0 / 111_320.0, own.latitude, 1e-9)
    }

    @Test
    fun `a station's own stops are picked first, ahead of nearer ones around it`() = runTest {
        // Without the anchor, two nearer tube stations would take the tube's places and push the
        // station's own (700 m from its middle) behind "More".
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder {
                listOf(stop("t1", 100.0, "tube"), stop("t2", 200.0, "tube"), stop("own", 700.0, "tube"))
            },
            io = dispatcher,
            anchorStopIds = setOf("own"),
        )
        model.locate()
        advanceUntilIdle()
        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals("own", ready.eagerStops.first().id)
        // Picked as if at the station's middle, but its real position is kept (its map opens there).
        val own = (ready.eager + ready.more).flatMap { it.stops }.single { it.id == "own" }
        assertEquals(700.0 / 111_320.0, own.latitude, 1e-9)
        assertEquals(0.0, ready.distanceMeters.getValue("own"), 0.0)
    }

    @Test
    fun `a hidden mode's stops aren't picked, and showing it again brings them back`() = runTest {
        val stops = listOf(stop("b1", 50.0, "bus"), stop("t1", 300.0, "tube"))
        var hidden = setOf("bus")
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin), finder = FakeFinder { stops }, io = dispatcher, hiddenModes = { hidden },
        )
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("t1"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id })

        hidden = emptySet()
        model.refilter()
        advanceUntilIdle()
        assertEquals(listOf("b1", "t1"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id })
    }

    @Test
    fun `showing a mode again at the same places reconciles the retained departures`() = runTest {
        // One place serving bus and tube: hiding the bus keeps the place, so "Show all" re-picks the
        // same places with the bus lines back, and the departures must be reconciled to fetch them.
        val mixed = stop("m1", 50.0, "tube").let { it.copy(lines = it.lines + LineRef("bus-m1", "m1", "bus")) }
        var hidden = setOf("bus")
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin), finder = FakeFinder { listOf(mixed) }, io = dispatcher, hiddenModes = { hidden },
        )
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("tube"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.single().lines.map { it.mode })

        hidden = emptySet()
        var reconciled: NearbyStopsViewModel.State.Ready? = null
        model.refilter { reconciled = it }
        advanceUntilIdle()
        assertEquals(listOf("tube", "bus"), reconciled?.eagerStops?.single()?.lines?.map { it.mode })
    }

    @Test
    fun `show all during a relocation doesn't leave the refresh indicator on`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var waitForFix = false
        val location = object : LocationProvider {
            override suspend fun current(forceFresh: Boolean): LocationFix {
                if (waitForFix) gate.await()
                return LocationFix(origin, isFallback = false)
            }
        }
        val model = vm(location, FakeFinder { listOf(stop("b1", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        waitForFix = true
        model.relocate()
        advanceUntilIdle()
        assertTrue(model.relocating.value)
        model.refilter()
        advanceUntilIdle()
        assertEquals(false, model.relocating.value)
    }

    @Test
    fun `hiding every mode nearby still picks the stops, so the list can say they're hidden`() = runTest {
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { listOf(stop("b1", 50.0, "bus")) },
            io = dispatcher,
            hiddenModes = { setOf("bus") },
        )
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("b1"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id })
    }

    @Test
    fun `shows the nearby stops within the rings, nearest-first with lines carried through`() = runTest {
        val stops = listOf(
            // Out of order; a bus stop beyond the ~1 mi outer ring is excluded.
            stop("far", 2000.0, "bus"),
            stop("b2", 200.0, "bus"),
            stop("b1", 50.0, "bus"),
        )
        val finder = FakeFinder { stops }
        val model = vm(FakeLocation(origin), finder)

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        // The two nearest bus clusters are eager, nearest-first; the 2 km one is out of range.
        assertEquals(listOf("b1", "b2"), ready.eagerStops.map { it.id })
        assertEquals(listOf(LineRef("bus-b1", "b1", "bus")), ready.eagerStops.first().lines)
        // Distance from the fix is carried (in memory, from #36) so the departures list can
        // collapse a line served by adjacent stops down to its nearest.
        assertEquals(setOf("b1", "b2"), ready.distanceMeters.keys)
        assertTrue(ready.distanceMeters.getValue("b1") < ready.distanceMeters.getValue("b2"))
        assertEquals(0.0, finder.lastLatitude!!, 0.0)
        // The exact fix is retained alongside the distances (in memory) so the consent-gated bug
        // report files the coordinate and the distances from one and the same fix.
        assertEquals(origin, ready.location)
    }

    @Test
    fun `a farther mode is not crowded out by nearer stops of another mode`() = runTest {
        // The reported bug: many near bus stops and one Tube station a little farther. The Tube
        // must still be eager — per-mode selection keeps its nearest cluster (SPEC Finding stops).
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"), // the lone tube, within the outer radius
        )
        val model = vm(FakeLocation(origin), FakeFinder { stops })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertTrue("the Tube stop survives the near buses", ready.eagerStops.any { it.id == "tube" })
        assertTrue("the near buses are still shown", ready.eagerStops.any { it.id == "bus1" })
    }

    @Test
    fun `the eager set is capped to the nearest clusters per mode`() = runTest {
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"),
        )
        val model = vm(FakeLocation(origin), FakeFinder { stops })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        // Eager is the two nearest bus clusters plus the lone tube — the third bus is beyond the
        // per-mode cap, so it sits in the `more` tier (paged in by a "More" tap), not eager. (The
        // eager/`more` split itself is covered by NearbyClustersTest.)
        assertEquals(listOf("bus1", "bus2", "tube"), ready.eagerStops.map { it.id })
        assertTrue("bus3" !in ready.eagerStops.map { it.id })
        assertEquals(listOf("bus3"), ready.more.flatMap { c -> c.stops.map { it.id } })
        // Distances span both tiers, so a revealed `more` stop is collapsed and ordered like an eager one.
        assertTrue("bus3" in ready.distanceMeters)
    }

    @Test
    fun `a second locate cancels the first before it reaches TfL`() = runTest {
        // A quick double-tap: two locate() calls before the first resolves. The superseded
        // lookup must be canceled before it hits the finder, so only the live one runs and
        // a stale first result can't finish last and overwrite the newer state.
        var lookups = 0
        val finder = FakeFinder { lookups++; listOf(stop("s", 0.0, "bus")) }
        val model = vm(FakeLocation(origin), finder)

        model.locate()
        model.locate()
        advanceUntilIdle()

        assertEquals(1, lookups)
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
    }

    @Test
    fun `no fix maps to NoLocation, not an empty list`() = runTest {
        val finder = FakeFinder { error("finder must not run without a fix") }
        val model = vm(FakeLocation(null), finder)

        model.locate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.NoLocation, model.state.value)
        // The lookup — the location-sending call — is never made without a position.
        assertEquals(null, finder.lastLatitude)
    }

    @Test
    fun `located but no stops nearby maps to Empty`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { emptyList() })

        model.locate()
        advanceUntilIdle()

        // Empty carries the fix it found nothing near, so a bug report from that gate can say where.
        assertEquals(NearbyStopsViewModel.State.Empty(origin), model.state.value)
    }

    @Test
    fun `a rate-limited lookup fails by kind, not silently`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.RateLimited(null) })

        model.locate()
        advanceUntilIdle()

        // Failed carries the fix the lookup was made with (for the bug report), alongside the kind.
        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED, origin),
            model.state.value,
        )
    }

    @Test
    fun `an offline lookup maps to the offline kind`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.Offline(null) })

        model.locate()
        advanceUntilIdle()

        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE, origin),
            model.state.value,
        )
    }

    @Test
    fun `relocate swaps in the new nearby set (walked to the next stop)`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val model = vm(MutableLocation(origin), FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("a"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id })

        // Refresh after walking on: a different nearby set resolves and replaces the old one.
        stops = listOf(stop("b", 60.0, "tube"))
        model.relocate()
        advanceUntilIdle()

        assertEquals(
            listOf("b"),
            (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id },
        )
    }

    @Test
    fun `a finished re-pick reports the set before and after, each with its own id`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertEquals(null, model.repicked.value)

        stops = listOf(stop("b", 60.0, "tube"))
        model.relocate()
        advanceUntilIdle()
        val moved = model.repicked.value!!
        assertEquals(listOf("a"), moved.before.eagerStops.map { it.id })
        assertEquals(listOf("b"), moved.after.eagerStops.map { it.id })

        model.refilter()
        advanceUntilIdle()
        val refiltered = model.repicked.value!!
        assertTrue(refiltered.id != moved.id)
        assertEquals(listOf("b"), refiltered.before.eagerStops.map { it.id })

        // A failed fix ends in no set at all: nothing to compare, so nothing is reported.
        location.fix = null
        model.relocate()
        advanceUntilIdle()
        assertEquals(refiltered, model.repicked.value)
    }

    @Test
    fun `relocate surfaces a failed fix honestly, not the stale set`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)

        // A refresh whose fix can't be obtained must not leave the previous location's stops on
        // screen as if current (SPEC principles 1–2) — it reports NoLocation.
        location.fix = null
        model.relocate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.NoLocation, model.state.value)
    }

    @Test
    fun `relocate surfaces an out-of-range result as Empty, not the stale set`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val model = vm(MutableLocation(origin), FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)

        // Walked out of range: the lookup succeeds but finds nothing, so the honest "no stops
        // nearby" replaces the old set rather than showing a previous location's stops.
        stops = emptyList()
        model.relocate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.Empty(origin), model.state.value)
    }

    /** A provider whose fresh fix is a coarse network one; [precise] is what GPS then says (or null). */
    private class CoarseThenPrecise(
        var coarse: Coordinates,
        var precise: Coordinates?,
    ) : LocationProvider {
        var preciseAsked = 0
        override suspend fun current(forceFresh: Boolean): LocationFix =
            LocationFix(coarse, isFallback = false, isCoarse = true)

        override suspend fun precise(): Coordinates? {
            preciseAsked++
            return precise
        }

        /** The GPS answer's reported accuracy: sharp by default, [preciseAccuracy] to make it vague. */
        var preciseAccuracy: Float? = 10f

        override suspend fun preciseWithAccuracy(): LocationFix? =
            precise()?.let { LocationFix(it, isFallback = false, accuracyMeters = preciseAccuracy) }
    }

    // [meters] due north of the origin.
    private fun north(meters: Double) = Coordinates(meters / 111_195.0, 0.0)

    @Test
    fun `a set shown from a coarse fix is flagged approximate and a precise fix is asked for`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = null)
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
        assertEquals(1, location.preciseAsked)
        // GPS never answered: the set stays, flagged, with nothing to move to.
        assertEquals(LocationBanner.COARSE, model.locationBanner.value)
        assertEquals(null, model.refinement.value)
        assertEquals(NearbyStopsViewModel.RiderFix(from = origin, at = origin, accurate = false), model.riderFix.value)
    }

    @Test
    fun `a precise fix that agrees with the coarse one just clears the flag`() = runTest {
        val model = vm(CoarseThenPrecise(coarse = origin, precise = north(40.0)), FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(null, model.locationBanner.value)
        assertEquals(null, model.refinement.value)
        assertEquals(origin, (model.state.value as NearbyStopsViewModel.State.Ready).location)
        // The set stays at the coarse coordinate, so where the rider actually is rides alongside it,
        // tied to the set it confirmed (the favorite chips measure from it).
        assertEquals(NearbyStopsViewModel.RiderFix(from = origin, at = north(40.0), accurate = true), model.riderFix.value)
    }

    @Test
    fun `a new location attempt drops the precise fix that confirmed the old set`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = north(40.0))
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(north(40.0), model.riderFix.value?.at)
        // The re-locate lands on the very same coarse coordinate, and GPS doesn't answer this time:
        // the old precise fix must not stand in for where the rider is now (Codex).
        location.precise = null
        model.relocate()
        // While the new fix is in flight the old set is still on screen, and still stands on the old
        // confirmation: it isn't dropped until the new outcome is applied (Codex).
        assertEquals(north(40.0), model.riderFix.value?.at)
        advanceUntilIdle()
        assertEquals(origin, (model.state.value as NearbyStopsViewModel.State.Ready).location)
        assertEquals(NearbyStopsViewModel.RiderFix(from = origin, at = origin, accurate = false), model.riderFix.value)
    }

    @Test
    fun `a vague precise answer confirms the set but isn't accurate`() = runTest {
        // GPS answered near the coarse fix, but reports 300 m: the set is confirmed, the chips don't
        // act on it (Codex).
        val location = CoarseThenPrecise(coarse = origin, precise = north(40.0)).apply { preciseAccuracy = 300f }
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(null, model.locationBanner.value)
        assertEquals(NearbyStopsViewModel.RiderFix(from = origin, at = north(40.0), accurate = false), model.riderFix.value)
    }

    @Test
    fun `the rider fix carries the fix's own accuracy, not the banner's`() = runTest {
        // An approximate-only grant: not flagged coarse (no banner), but kilometer-scale (Codex).
        val location = MutableLocation(origin, accuracyMeters = 1_500f)
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(null, model.locationBanner.value)
        assertEquals(NearbyStopsViewModel.RiderFix(from = origin, at = origin, accurate = false), model.riderFix.value)
        // A GPS-grade fix is accurate.
        location.accuracyMeters = 10f
        model.relocate()
        advanceUntilIdle()
        assertEquals(true, model.riderFix.value?.accurate)
    }

    @Test
    fun `a precise fix far from the coarse one moves the set there`() = runTest {
        val precise = north(400.0)
        val finder = FakeFinder { listOf(stop("b1", 80.0, "bus")) }
        val model = vm(CoarseThenPrecise(coarse = origin, precise = precise), finder)
        model.locate()
        advanceUntilIdle()
        val refinement = model.refinement.value!!
        assertEquals(origin, refinement.from)
        assertEquals(precise, refinement.precise)

        model.applyRefinement(refinement)
        advanceUntilIdle()
        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(precise, ready.location)
        assertEquals(precise.latitude, finder.lastLatitude!!, 1e-9)
        // The set now stands on a precise fix, so nothing is flagged or pending.
        assertEquals(null, model.locationBanner.value)
        assertEquals(null, model.refinement.value)
    }

    @Test
    fun `a refinement for a set no longer shown moves nothing but restarts its fetch`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = north(400.0))
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val stale = model.refinement.value!!
        // A refresh re-located somewhere else first (and its own precise fix is still out).
        location.coarse = north(1_000.0)
        location.precise = null
        model.relocate()
        advanceUntilIdle()
        var restarted: NearbyStopsViewModel.State.Ready? = null
        model.applyRefinement(stale) { restarted = it }
        advanceUntilIdle()
        assertEquals(north(1_000.0), (model.state.value as NearbyStopsViewModel.State.Ready).location)
        assertEquals(north(1_000.0), restarted?.location)
    }

    @Test
    fun `no stops found from a coarse fix looks again from the precise one`() = runTest {
        val precise = north(400.0)
        // Nothing within reach of the coarse fix; a stop by the precise one.
        val finder = object : StopFinder {
            override suspend fun nearbyStops(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                stopTypes: List<String>,
            ) = if (latitude > 0.0) listOf(stop("b1", 480.0, "bus")) else emptyList()
        }
        val model = vm(CoarseThenPrecise(coarse = origin, precise = precise), finder)
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Empty)
        val refinement = model.refinement.value!!
        model.applyRefinement(refinement)
        advanceUntilIdle()
        assertEquals(precise, (model.state.value as NearbyStopsViewModel.State.Ready).location)
    }

    @Test
    fun `leaving the nearby surface stops the precise follow-up, and returning asks again`() = runTest {
        val gate = CompletableDeferred<Coordinates?>()
        val location = object : LocationProvider {
            var asked = 0
            var canceled = 0
            override suspend fun current(forceFresh: Boolean) =
                LocationFix(origin, isFallback = false, isCoarse = true)
            override suspend fun precise(): Coordinates? {
                asked++
                return try {
                    gate.await()
                } catch (e: CancellationException) {
                    canceled++
                    throw e
                }
            }
        }
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(1, location.asked)
        model.pauseRefining()
        advanceUntilIdle()
        assertEquals(1, location.canceled)
        assertEquals(LocationBanner.COARSE, model.locationBanner.value)
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(2, location.asked)
        // Already asking: a second resume doesn't start another request.
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(2, location.asked)
    }

    @Test
    fun `an empty coarse outcome is still refined after the surface comes back`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = null)
        val model = vm(location, FakeFinder { emptyList() })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Empty)
        // No precise fix came: "no stops nearby" stays flagged as from a coarse fix.
        assertEquals(LocationBanner.COARSE, model.locationBanner.value)
        assertEquals(1, location.preciseAsked)
        model.pauseRefining()
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(2, location.preciseAsked)
    }

    @Test
    fun `a locate that lands while the surface is away asks for a precise fix only on return`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = null)
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        model.pauseRefining()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
        assertEquals(LocationBanner.COARSE, model.locationBanner.value)
        assertEquals(0, location.preciseAsked)
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(1, location.preciseAsked)
    }

    @Test
    fun `no precise fix is asked for before the nearby surface first shows`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = null)
        // An overlay restored on top: the surface hasn't composed, so it never resumed.
        val model = NearbyStopsViewModel(
            location = location,
            finder = FakeFinder { listOf(stop("b1", 80.0, "bus")) },
            io = dispatcher,
        )
        model.locate()
        advanceUntilIdle()
        assertEquals(0, location.preciseAsked)
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(1, location.preciseAsked)
    }

    @Test
    fun `leaving drops an unapplied refinement and returning asks for a fresh precise fix`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = north(1_000.0))
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.refinement.value != null)
        model.pauseRefining()
        assertEquals(null, model.refinement.value)
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(2, location.preciseAsked)
        assertTrue(model.refinement.value != null)
    }

    @Test
    fun `returning to a confirmed set doesn't ask for a precise fix again`() = runTest {
        val location = CoarseThenPrecise(coarse = origin, precise = origin)
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(null, model.locationBanner.value)
        model.pauseRefining()
        model.resumeRefining()
        advanceUntilIdle()
        assertEquals(1, location.preciseAsked)
    }

    @Test
    fun `an empty coarse outcome looks again even from a precise fix under 100 m away`() = runTest {
        val precise = north(50.0)
        val finder = object : StopFinder {
            override suspend fun nearbyStops(
                latitude: Double,
                longitude: Double,
                radiusMeters: Int,
                stopTypes: List<String>,
            ) = if (latitude > 0.0) listOf(stop("b1", 80.0, "bus")) else emptyList()
        }
        val model = vm(CoarseThenPrecise(coarse = origin, precise = precise), finder)
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Empty)
        model.applyRefinement(model.refinement.value!!)
        advanceUntilIdle()
        assertEquals(precise, (model.state.value as NearbyStopsViewModel.State.Ready).location)
    }

    @Test
    fun `a refinement withdrawn by a relocation in flight doesn't cancel it`() = runTest {
        val model = vm(CoarseThenPrecise(coarse = origin, precise = north(1_000.0)), FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val captured = model.refinement.value!!
        // A refresh starts before the UI applies what it captured; the old set is still shown.
        model.relocate()
        model.applyRefinement(captured)
        advanceUntilIdle()
        assertEquals(origin, (model.state.value as NearbyStopsViewModel.State.Ready).location)
    }

    @Test
    fun `each lookup logs counts only, and its stops, distances and position go to recent positions`() = runTest {
        val logged = mutableListOf<String>()
        val positions = mutableListOf<Pair<String, Coordinates>>()
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { listOf(stop("b1", 80.0, "bus")) },
            io = dispatcher,
            warn = { logged += it },
            position = { what, at -> positions += what to at },
        )
        model.locate()
        advanceUntilIdle()
        assertTrue(logged.toString(), "nearby: 1 stops (+0 more)" in logged)
        assertEquals(listOf("nearby lookup: b1 80 m," to origin), positions)
        // Neither a position nor a stop distance in the persisted log.
        assertTrue(logged.toString(), logged.none { "0.00000" in it || "80 m" in it })

        val empty = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { emptyList() },
            io = dispatcher,
            warn = { logged += it },
        )
        empty.locate()
        advanceUntilIdle()
        assertTrue(logged.toString(), logged.any { it == "nearby: no stops in range (0 found)" })
    }

    @Test
    fun `a lookup's recorded stops include the farther tier`() = runTest {
        val positions = mutableListOf<String>()
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { (1..12).map { stop("b$it", 60.0 * it, "bus") } },
            io = dispatcher,
            position = { what, _ -> positions += what },
        )
        model.locate()
        advanceUntilIdle()
        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertTrue("fixture should produce a farther tier", ready.more.isNotEmpty())
        val farther = ready.more.first().stops.first().id
        assertTrue(positions.toString(), positions.single().contains("; more: ") && farther in positions.single())
    }

    @Test
    fun `a lookup that found only route-less stops records them, not no stops`() = runTest {
        val positions = mutableListOf<String>()
        val model = NearbyStopsViewModel(
            location = FakeLocation(origin),
            finder = FakeFinder { listOf(stop("r1", 80.0, "bus").copy(lines = emptyList())) },
            io = dispatcher,
            position = { what, _ -> positions += what },
        )
        model.locate()
        advanceUntilIdle()
        assertEquals(NearbyStopsViewModel.State.Empty(origin), model.state.value)
        assertEquals(listOf("nearby lookup (no stops with routes): r1 80 m,"), positions)
    }

    @Test
    fun `a fallback fix is not refined, since it is the last-known one`() = runTest {
        val location = object : LocationProvider {
            var asked = 0
            override suspend fun current(forceFresh: Boolean) = LocationFix(origin, isFallback = true, isCoarse = true)
            override suspend fun precise(): Coordinates? = null.also { asked++ }
        }
        val model = vm(location, FakeFinder { listOf(stop("b1", 80.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(LocationBanner.APPROXIMATE, model.locationBanner.value)
        assertEquals(0, location.asked)
    }

    @Test
    fun `locate from a fallback fix shows the stops but flags the location approximate`() = runTest {
        // Cold start with only a stale last-known fix (a fresh fix failed — the Underground case):
        // the stops resolve, but the banner says the position is approximate (SPEC principle 2).
        val model = vm(FakeLocation(origin, isFallback = true), FakeFinder { listOf(stop("a", 50.0, "bus")) })

        model.locate()
        advanceUntilIdle()

        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
        assertEquals(LocationBanner.APPROXIMATE, model.locationBanner.value)
    }

    @Test
    fun `relocate keeps the current set and flags update-failed on a fallback fix`() = runTest {
        // A good set is shown, then a refresh can only get a low-confidence fix: don't jump to it —
        // keep the set already shown and say the location couldn't update (SPEC *Finding stops*).
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val shown = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(null, model.locationBanner.value)

        location.isFallback = true
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        // The set is unchanged (not re-resolved to the stale fix), and the banner explains why.
        assertEquals(shown.clusterSetKey, (model.state.value as NearbyStopsViewModel.State.Ready).clusterSetKey)
        assertEquals(LocationBanner.UPDATE_FAILED, model.locationBanner.value)
        // The retained set's departures fetch still restarts in place: the caller canceled it before
        // re-locating, so keeping the set without re-fetching would hang a mid-load screen forever.
        assertTrue("keeping the set still re-fetches its departures", refreshed)
    }

    @Test
    fun `a fresh relocate clears the location banner`() = runTest {
        val location = MutableLocation(origin, isFallback = true)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(LocationBanner.APPROXIMATE, model.locationBanner.value)

        // A later refresh gets a fresh fix — the banner clears.
        location.isFallback = false
        model.relocate()
        advanceUntilIdle()

        assertEquals(null, model.locationBanner.value)
    }

    @Test
    fun `relocating is true while the fresh fix is in flight and false once it resolves`() = runTest {
        val model = vm(MutableLocation(origin), FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        // locate() uses the Locating gate, not the in-place indicator.
        assertEquals(false, model.relocating.value)

        // relocate flips the indicator on synchronously (before the fix runs) so the departures
        // screen's refresh spinner stays up for the whole re-locate, and clears once it resolves.
        model.relocate()
        assertEquals(true, model.relocating.value)
        advanceUntilIdle()
        assertEquals(false, model.relocating.value)
    }

    @Test
    fun `a locate that supersedes a relocate clears the in-flight indicator`() = runTest {
        val model = vm(MutableLocation(origin), FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()

        model.relocate()
        assertEquals(true, model.relocating.value)
        // A locate (e.g. a permission retry) cancels the relocate and shows its own gate, so the
        // in-place indicator must not stay stuck on.
        model.locate()
        assertEquals(false, model.relocating.value)
        advanceUntilIdle()
        assertEquals(false, model.relocating.value)
    }

    @Test
    fun `relocate applies fresh distances when the stop set is unchanged`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val before = model.state.value as NearbyStopsViewModel.State.Ready
        val beforeDistance = before.distanceMeters.getValue("a")

        // The rider moves ~10 m toward the stop; the stop ID is unchanged (same set), but the
        // distance changed — and the list picks each line's nearest stop and orders rows by
        // distanceMeters, so the fresh distance must be applied, not the previous position's.
        location.fix = Coordinates(10.0 / 111_320.0, 0.0)
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        val after = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(listOf("a"), after.eagerStops.map { it.id })
        assertTrue("same set still refreshes departures in place", refreshed)
        assertTrue("the fresh, nearer distance replaces the old one", after.distanceMeters.getValue("a") < beforeDistance)
    }

    @Test
    fun `relocate refreshes the same set in place and forces a fresh fix`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        // A first open may use the recent-cache fast path.
        assertEquals(false, location.lastForceFresh)
        val before = model.state.value as NearbyStopsViewModel.State.Ready

        // The fix confirms the same stops, so the callback (the departures refresh) fires and
        // the state is not re-emitted — the refresh is sequenced after the fix, not parallel.
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        // A re-locate forces a fresh fix (a cached one could re-resolve for the old position).
        assertEquals(true, location.lastForceFresh)
        assertTrue("same set → departures refreshed in place", refreshed)
        assertEquals(before, model.state.value)
    }
}
