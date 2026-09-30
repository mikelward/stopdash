package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-tier near-me cluster selection (SPEC *Finding stops → Near me now*): stops group into
 * clusters (a station's platforms, a junction's poles), the nearest [NearbySelection.CLUSTERS_PER_MODE]
 * clusters of each mode are eager (fetched at once), the rest are the *more* tier.
 * Coordinates are obviously-synthetic offsets from the origin (SPEC *Privacy*).
 */
class NearbyClustersTest {
    // A stop [meters] due north of the origin (~111,320 m per degree of latitude) serving one
    // line of [mode], optionally in [cluster] (blank = its own cluster).
    private fun stop(id: String, meters: Double, mode: String, cluster: String = "") = StopLocation(
        id = id,
        name = id,
        latitude = meters / 111_320.0,
        longitude = 0.0,
        lines = listOf(LineRef("$mode-$id", id, mode)),
        clusterId = cluster,
    )

    private fun select(stops: List<StopLocation>, clustersPerMode: Int = NearbySelection.CLUSTERS_PER_MODE) =
        NearbySelection.selectClusters(stops, latitude = 0.0, longitude = 0.0, clustersPerMode = clustersPerMode)

    // A tier as lists of the stop ids in each cluster, in cluster order.
    private fun List<NearbySelection.NearbyCluster>.ids() = map { c -> c.stops.map { it.id } }

    @Test
    fun `no stops yields empty tiers`() {
        val result = select(emptyList())
        assertEquals(emptyList<List<String>>(), result.eager.ids())
        assertEquals(emptyList<List<String>>(), result.more.ids())
    }

    @Test
    fun `a stop beyond the outer ring is dropped from both tiers`() {
        val result = select(listOf(stop("near", 100.0, "bus"), stop("far", 2000.0, "bus")))
        assertEquals(listOf(listOf("near")), result.eager.ids())
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun `poles sharing a cluster form one cluster, nearest member first`() {
        // A junction's two poles are distinct stop ids sharing a stationNaptan — one cluster,
        // its stops ordered nearest-first, ranked by the nearer pole.
        val result = select(
            listOf(
                stop("A", 100.0, "bus", "490G0TPL"),
                stop("B", 60.0, "bus", "490G0TPL"),
            ),
        )
        assertEquals(listOf(listOf("B", "A")), result.eager.ids())
        assertEquals(60.0, result.eager.single().distanceMeters, 0.5)
    }

    @Test
    fun `two same-named stops in different clusters stay separate clusters`() {
        // The whole point of keying on the cluster, not the name: adjacent stations TfL spells
        // alike but gives different stationNaptans are two places, not one.
        val result = select(
            listOf(
                stop("k1", 100.0, "tube", "940GZZLUKSX"),
                stop("s1", 120.0, "tube", "490G000760"),
            ),
        )
        assertEquals(listOf(listOf("k1"), listOf("s1")), result.eager.ids())
    }

    @Test
    fun `a blank cluster id keeps each stop its own cluster`() {
        val result = select(listOf(stop("x", 100.0, "bus"), stop("y", 120.0, "bus")))
        assertEquals(listOf(listOf("x"), listOf("y")), result.eager.ids())
    }

    @Test
    fun `only the nearest two clusters of a mode are eager, the rest go to more`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0C1"),
                stop("b2", 120.0, "bus", "490G0C2"),
                stop("b3", 300.0, "bus", "490G0C3"),
                stop("t1", 700.0, "tube", "940GZZLUKSX"),
            ),
        )
        // Nearest two bus clusters are eager; the far lone tube is eager as its mode's nearest.
        assertEquals(listOf(listOf("b1"), listOf("b2"), listOf("t1")), result.eager.ids())
        // The third bus cluster waits behind "More".
        assertEquals(listOf(listOf("b3")), result.more.ids())
    }

    @Test
    fun `the nearest station of a sparse mode is always eager, however far`() {
        // Highgate at 0.8 mi: the only tube, so rank 1 of its mode — never crowded out by nearer
        // buses (maintainer's worked example).
        val result = select(
            listOf(
                stop("bus1", 40.0, "bus", "490G0C1"),
                stop("bus2", 90.0, "bus", "490G0C2"),
                stop("bus3", 150.0, "bus", "490G0C3"),
                stop("highgate", 1287.0, "tube", "940GZZLUHGT"), // ~0.8 mi
            ),
        )
        assertTrue("the far lone tube is eager", result.eager.ids().contains(listOf("highgate")))
        assertEquals(listOf(listOf("bus3")), result.more.ids())
    }

    @Test
    fun `an interchange cluster counts toward every mode it serves`() {
        // A hub serving tube + bus is eager via tube (its only one) and also counts as a top-2
        // bus cluster, so it isn't double-listed and the tube is never pushed to "More".
        val hub = StopLocation(
            id = "hub", name = "hub", latitude = 40.0 / 111_320.0, longitude = 0.0,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("55", "55", "bus")),
            clusterId = "940GZZLUOXC",
        )
        val result = select(
            listOf(
                hub,
                stop("busA", 50.0, "bus", "490G0A"),
                stop("busB", 120.0, "bus", "490G0B"),
                stop("busC", 300.0, "bus", "490G0C"),
            ),
        )
        assertEquals(listOf(listOf("hub"), listOf("busA")), result.eager.ids())
        assertEquals(listOf(listOf("busB"), listOf("busC")), result.more.ids())
        // No tube left in "more" — the hub covered it.
        assertTrue(result.more.none { "tube" in it.modes })
    }

    @Test
    fun `more is globally ordered and pages per mode`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0B1"),
                stop("b2", 120.0, "bus", "490G0B2"),
                stop("b3", 400.0, "bus", "490G0B3"),
                stop("u1", 300.0, "tube", "940GZZLU1"),
                stop("u2", 600.0, "tube", "940GZZLU2"),
                stop("u3", 900.0, "tube", "940GZZLU3"),
            ),
        )
        // Eager: nearest two of each mode within walking reach, kept in global distance order — the
        // tube at 600 m is past it, since a nearer tube is in reach.
        assertEquals(listOf(listOf("b1"), listOf("b2"), listOf("u1")), result.eager.ids())
        // More stays globally ordered; the "More bus"/"More tube" buttons filter it by mode.
        assertEquals(listOf(listOf("b3"), listOf("u2"), listOf("u3")), result.more.ids())
        assertEquals(listOf(listOf("b3")), result.more.filter { "bus" in it.modes }.ids())
        assertEquals(listOf(listOf("u2"), listOf("u3")), result.more.filter { "tube" in it.modes }.ids())
    }

    @Test
    fun `a stop with no routes is never fetched eagerly, but stays reachable under More`() {
        // A stop TfL lists no lines for (a disused or unserved stop) has nothing to show, so it isn't
        // auto-fetched — however near — and doesn't spend the rate budget. It isn't dropped either: it
        // waits in "more" with an empty modes set, which the UI surfaces under the generic "More stops".
        val noLines = StopLocation("s", "S", latitude = 100.0 / 111_320.0, longitude = 0.0)
        val result = select(listOf(noLines))
        assertTrue(result.eager.isEmpty())
        assertEquals(listOf(listOf("s")), result.more.ids())
        assertTrue("the overflow cluster has no declared mode", result.more.single().modes.isEmpty())
    }

    @Test
    fun `stops with no routes don't take eager places from a mode that runs`() {
        // Nearer route-less stops leave the bus cap to the poles that actually have buses.
        val noLines = { id: String, meters: Double -> StopLocation(id, id, meters / 111_320.0, 0.0) }
        val result = select(
            listOf(noLines("a", 50.0), noLines("b", 120.0), stop("b1", 300.0, "bus", "490G0B1")),
        )
        assertEquals(listOf(listOf("b1")), result.eager.ids())
        assertEquals(listOf(listOf("a"), listOf("b")), result.more.ids())
    }

    @Test
    fun `a route-less pole in a served junction isn't fetched with the junction`() {
        // One junction (shared clusterId): a served bus pole and a disused one TfL lists no routes for.
        // The junction is eager for its bus; the disused pole waits alone behind "More stops".
        val served = stop("b1", 50.0, "bus", "490G0J")
        val disused = StopLocation("b2", "b2", 60.0 / 111_320.0, 0.0, clusterId = "490G0J")
        val result = select(listOf(served, disused))
        assertEquals(listOf(listOf("b1")), result.eager.ids())
        assertEquals(listOf(listOf("b2")), result.more.ids())
        assertTrue(result.more.single().modes.isEmpty())
    }

    @Test
    fun `a served stop whose routes carry no mode is still selected eagerly`() {
        // TfL listed routes but no mode for them: the stop runs, so it isn't treated as route-less.
        val thin = StopLocation(
            "t", "t", 100.0 / 111_320.0, 0.0,
            lines = listOf(LineRef("73", "73", "")),
        )
        val result = select(listOf(thin))
        assertEquals(listOf(listOf("t")), result.eager.ids())
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun `only stops within walking reach are fetched up front, however many a mode has farther`() {
        // Two overground stations, both past 500 m: only the nearest is eager (its mode's
        // representative); the second waits behind More instead of being fetched a mile out.
        val result = select(
            listOf(
                stop("bus", 80.0, "bus", "490G0B"),
                stop("og1", 790.0, "overground", "910G1"),
                stop("og2", 1270.0, "overground", "910G2"),
            ),
        )
        assertEquals(listOf(listOf("bus"), listOf("og1")), result.eager.ids())
        assertEquals(listOf(listOf("og2")), result.more.ids())
    }

    @Test
    fun `a mode with a stop in reach doesn't also fetch its farther ones`() {
        val result = select(
            listOf(
                stop("near", 300.0, "tube", "940G1"),
                stop("far", 700.0, "tube", "940G2"),
            ),
        )
        assertEquals(listOf(listOf("near")), result.eager.ids())
        assertEquals(listOf(listOf("far")), result.more.ids())
    }

    @Test
    fun `a metro station in reach stands for every metro mode`() {
        // The Tube within walking reach covers the Overground too: the nearest Overground, a mile
        // off, isn't fetched just to represent its mode (the farther cards offer its line instead).
        val result = select(
            listOf(
                stop("tube", 360.0, "tube", "940G1"),
                stop("og", 810.0, "overground", "910G1"),
                stop("dlr", 1200.0, "dlr", "940G2"),
                stop("el", 1300.0, "elizabeth-line", "910G2"),
            ),
        )
        assertEquals(listOf(listOf("tube")), result.eager.ids())
        assertEquals(listOf(listOf("og"), listOf("dlr"), listOf("el")), result.more.ids())
    }

    @Test
    fun `the metro modes share one cap of two in reach`() {
        // Two Tube stations and an Overground one all in reach: the nearest two of them, whatever
        // their mode, not two of each.
        val result = select(
            listOf(
                stop("og", 150.0, "overground", "910G1"),
                stop("t1", 250.0, "tube", "940G1"),
                stop("t2", 400.0, "tube", "940G2"),
            ),
        )
        assertEquals(listOf(listOf("og"), listOf("t1")), result.eager.ids())
        assertEquals(listOf(listOf("t2")), result.more.ids())
    }

    @Test
    fun `with no metro in reach only the nearest metro station of any kind is fetched`() {
        val result = select(
            listOf(
                stop("bus", 80.0, "bus", "490G0B"),
                stop("dlr", 700.0, "dlr", "940G1"),
                stop("tube", 900.0, "tube", "940G2"),
            ),
        )
        assertEquals(listOf(listOf("bus"), listOf("dlr")), result.eager.ids())
        assertEquals(listOf(listOf("tube")), result.more.ids())
    }

    @Test
    fun `an interchange's metro stations count as one place and load together`() {
        // Canary Wharf's shape: Tube, DLR and Elizabeth line stations in one hub but separate
        // clusters. Counted apart, the shared cap of two would drop one, and its farther card is
        // withheld because the interchange is already shown — so the hub takes one place and brings
        // all three, leaving the cap's second place for the next metro station.
        fun hubStop(id: String, meters: Double, mode: String) =
            stop(id, meters, mode, "940G$id").copy(hubId = "HUBCAW")
        val result = select(
            listOf(
                hubStop("tube", 150.0, "tube"),
                hubStop("dlr", 200.0, "dlr"),
                hubStop("el", 250.0, "elizabeth-line"),
                stop("other", 300.0, "dlr", "940GOTHER"),
                stop("third", 400.0, "dlr", "940GTHIRD"),
            ),
        )
        assertEquals(
            listOf(listOf("tube"), listOf("dlr"), listOf("el"), listOf("other")),
            result.eager.ids(),
        )
        assertEquals(listOf(listOf("third")), result.more.ids())
    }

    @Test
    fun `an interchange's National Rail stations load together`() {
        // King's Cross's shape: two St Pancras stations nearest and King's Cross mainline a little
        // farther, all in one hub. Counted apart, the cap of two left King's Cross mainline out —
        // and with its interchange already shown it got no farther card, so its trains (LNER, Great
        // Northern) silently vanished while St Pancras's showed. As one place they all load, and
        // the cap's second place still goes to the next station outside the hub.
        fun hubRail(id: String, meters: Double) =
            stop(id, meters, "national-rail", "910G$id").copy(hubId = "HUB1")
        val result = select(
            listOf(
                hubRail("a", 100.0),
                hubRail("b", 150.0),
                hubRail("c", 350.0),
                stop("other", 400.0, "national-rail", "910GOTHER"),
                stop("third", 450.0, "national-rail", "910GTHIRD"),
            ),
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("other")), result.eager.ids())
        assertEquals(listOf(listOf("third")), result.more.ids())
    }

    @Test
    fun `an interchange doesn't group its bus stops`() {
        // Bus poles around an interchange are stops of their own, each a request: two of them, not all.
        fun hubBus(id: String, meters: Double) = stop(id, meters, "bus", "490G$id").copy(hubId = "HUB1")
        val result = select(listOf(hubBus("a", 100.0), hubBus("b", 150.0), hubBus("c", 300.0)))
        assertEquals(listOf(listOf("a"), listOf("b")), result.eager.ids())
        assertEquals(listOf(listOf("c")), result.more.ids())
    }

    @Test
    fun `national rail and trams still pick apart from the metro`() {
        // A Tube in reach doesn't stand for a train or a tram: each keeps its own nearest station.
        val result = select(
            listOf(
                stop("tube", 200.0, "tube", "940G1"),
                stop("rail", 800.0, "national-rail", "910G1"),
                stop("tram", 1000.0, "tram", "940G2"),
            ),
        )
        assertEquals(listOf(listOf("tube"), listOf("rail"), listOf("tram")), result.eager.ids())
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun `a far station is fetched for a mode it serves beside the metro`() {
        // A station serving National Rail and the Overground is still its train mode's nearest:
        // folding the metro modes drops only the Overground's own claim on it.
        val station = StopLocation(
            id = "far", name = "far", latitude = 800.0 / 111_320.0, longitude = 0.0,
            lines = listOf(LineRef("lioness", "Lioness", "overground"), LineRef("avanti", "Avanti", "national-rail")),
            clusterId = "910G1",
        )
        val result = select(listOf(stop("tube", 300.0, "tube", "940G1"), station))
        assertEquals(listOf(listOf("tube"), listOf("far")), result.eager.ids())
        // The cluster still reports TfL's own modes.
        assertEquals(setOf("overground", "national-rail"), result.eager.last().modes)
    }

    @Test
    fun `a tighter per-mode cap pushes more clusters behind More`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0B1"),
                stop("b2", 120.0, "bus", "490G0B2"),
            ),
            clustersPerMode = 1,
        )
        assertEquals(listOf(listOf("b1")), result.eager.ids())
        assertEquals(listOf(listOf("b2")), result.more.ids())
    }
}
