package app.stopdash.domain

import app.stopdash.domain.Hubs.Membership
import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic records around the obviously-fake (51.5, -0.12); never a real stop. */
class HubsTest {
    private val index = StationIndex(
        listOf(
            IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                platforms = listOf("9100HILL1"), icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("910GHILLLL", "Hill LL", listOf("national-rail"), "HUBEXA", 51.502, -0.120,
                icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
            IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                icsCode = "1009002", station = "1009002:tube", modelHub = "HUBEXA"),
            IndexedStation("910GVALE", "Vale", listOf("national-rail"), latitude = 51.53, longitude = -0.12,
                station = "910GVALE", modelHub = "910GVALE"),
            IndexedStation("HUBEXA", "Hill", listOf("national-rail", "tube")),
        ),
        revision = "abc",
    )

    private val tsv = listOf(
        "# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc",
        "L\t1\tbus\t1",
        // Two poles of one clean area, owned by Hill's rail station.
        "S\t490000001A\tB\tHill Station\t51.500500\t-0.120000\t490G000001\t\tA\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
        "S\t490000001B\tB\tHill Station\t51.500700\t-0.120000\t490G000001\t\tB\t\t\t1\t1009001\t490G000001\t1009001:rail\tHUBEXA",
        // An area split into two stops, at a junction of their own.
        "S\t490000005A\tB\tHigh Road\t51.520000\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005A\t\t490000005A",
        "S\t490000005B\tB\tMill Lane\t51.520300\t-0.120000\t490G000005\t\t\t\t\t1\t\t490000005B\t\t490000005A",
        // A rail record the index leaves out, of Vale's station.
        "S\t910GVALELL\tR\tVale LL Rail Station\t51.531000\t-0.120000\t910GVALELL\t\t\t\t\t\t\t910GVALE\t\t910GVALE",
    ).joinToString("\n")

    private fun stops(text: String = tsv) = BundledStops.read(BufferedReader(StringReader(text)))

    private val hubs = Hubs.of(index, stops())!!

    @Test
    fun `records of one ICS code are one station, and an interchange's stations one hub`() {
        assertEquals(Membership.Of("1009001:rail"), hubs.stationOf("910GHILL"))
        assertTrue(hubs.sameStation("910GHILL", "910GHILLLL"))
        assertFalse(hubs.sameStation("910GHILL", "940GZZLUHIL"))
        assertTrue(hubs.sameHub("910GHILL", "940GZZLUHIL"))
        assertEquals(Membership.Of("HUBEXA"), hubs.hubOf("HUBEXA"))
        assertEquals(Hubs.Members(setOf("1009001:rail", "1009002:tube"), setOf("490G000001")), hubs.membersOf("HUBEXA"))
    }

    @Test
    fun `a station's code is its records' and platforms' ICS code, whatever its mode`() {
        assertEquals("1009001", hubs.stationCodeOf("910GHILL"))
        assertEquals("1009001", hubs.stationCodeOf("910GHILLLL"))
        assertEquals("1009001", hubs.stationCodeOf("9100HILL1"))
        assertEquals("1009002", hubs.stationCodeOf("940GZZLUHIL"))
        assertNull("a bus pole is no station's", hubs.stationCodeOf("490000001A"))
        assertNull(hubs.stationCodeOf("910GNOWHERE"))
    }

    @Test
    fun `a station's records are filed under one of them, a TfL id it opens by`() {
        assertEquals("910GHILL", hubs.stationPlaceOf("910GHILL"))
        assertEquals("910GHILL", hubs.stationPlaceOf("910GHILLLL"))
        assertEquals("910GHILL", hubs.stationPlaceOf("9100HILL1"))
        // Its own code's, apart from the rail station's.
        assertEquals("940GZZLUHIL", hubs.stationPlaceOf("940GZZLUHIL"))
        assertNull(hubs.stationPlaceOf("490000001A"))
    }

    @Test
    fun `a station's place opens every record of its code, each mode family's`() {
        // Hill's Underground under the rail station's code, as Farringdon's is: filed under the rail record.
        val shared = Hubs.of(
            StationIndex(
                listOf(
                    IndexedStation("910GHILL", "Hill", listOf("national-rail"), "HUBEXA", 51.500, -0.120,
                        icsCode = "1009001", station = "1009001:rail", modelHub = "HUBEXA"),
                    IndexedStation("940GZZLUHIL", "Hill", listOf("tube"), "HUBEXA", 51.501, -0.121,
                        icsCode = "1009001", station = "1009001:tube", modelHub = "HUBEXA"),
                    IndexedStation("HUBEXA", "Hill", listOf("national-rail", "tube")),
                ),
                revision = "abc",
            ),
            stops(),
        )!!
        assertEquals("910GHILL", shared.stationPlaceOf("940GZZLUHIL"))
        assertEquals(setOf("910GHILL", "940GZZLUHIL"), shared.lookupIdsOf("910GHILL").toSet())
        assertEquals(setOf("910GHILL", "940GZZLUHIL"), shared.lookupIdsOf("940GZZLUHIL").toSet())
        // A code of its own stays its own.
        assertEquals(setOf("940GZZLUHIL"), hubs.lookupIdsOf("940GZZLUHIL").toSet())
    }

    @Test
    fun `a platform is its record's station`() {
        assertEquals(Membership.Of("1009001:rail"), hubs.stationOf("9100HILL1"))
        // Named by its record's code alone, as TfL names an access area.
        assertEquals(Membership.Of("1009001:rail"), hubs.stationOf("9100HILLLL"))
    }

    @Test
    fun `a pole is its stop's and its owner's hub's, and so is its whole area`() {
        assertEquals(Membership.Of("490G000001"), hubs.stopOf("490000001A"))
        assertEquals(Membership.Of("490G000001"), hubs.stopOf("490G000001"))
        assertTrue(hubs.sameStop("490000001A", "490000001B"))
        assertEquals(Membership.Of("HUBEXA"), hubs.hubOf("490000001B"))
        assertTrue(hubs.sameHub("490000001A", "910GHILLLL"))
    }

    @Test
    fun `one stop to get off at is a station's records or a bus stop's poles, never a hub's two members`() {
        assertTrue(hubs.oneStop("910GVALE", "910GVALELL"))
        assertTrue(hubs.oneStop("490000001A", "490000001B"))
        assertFalse(hubs.oneStop("490000001A", "910GHILLLL"))
        assertFalse(hubs.oneStop("490000005A", "490000005B"))
        assertFalse(hubs.oneStop("UNHELD1", "UNHELD2"))
    }

    @Test
    fun `a split area's id names its stops, never one of them`() {
        assertEquals(Membership.Split(setOf("490000005A", "490000005B")), hubs.stopOf("490G000005"))
        assertFalse(hubs.sameStop("490000005A", "490000005B"))
        assertEquals("both stops are one junction's", Membership.Of("490000005A"), hubs.hubOf("490G000005"))
    }

    @Test
    fun `an id resolves to its station and hub, the same for every record of one station`() {
        assertEquals(Resolved("910GHILL", "1009001:rail", "HUBEXA"), hubs.resolve("910GHILL"))
        assertEquals("1009001:rail", hubs.resolve("910GHILLLL").station)
        assertEquals("1009001:rail", hubs.resolve("9100HILL1").station)
        // Another station of the hub: one hub, not one station.
        assertEquals(Resolved("940GZZLUHIL", "1009002:tube", "HUBEXA"), hubs.resolve("940GZZLUHIL"))
        // A pole is its bus stop, both poles alike.
        assertEquals(Resolved("490000001B", "490G000001", "HUBEXA"), hubs.resolve("490000001B"))
        // A whole stop area is its one stop, naming every pole of it; a stop's own id names itself.
        assertEquals(Resolved("490G000001", "490G000001", "HUBEXA", poles = setOf("490000001A", "490000001B")), hubs.resolve("490G000001"))
        // A hub's own id names no one station.
        assertEquals(Resolved("HUBEXA", null, "HUBEXA"), hubs.resolve("HUBEXA"))
    }

    @Test
    fun `a destination the model places matches its one hub, whatever level it was picked at`() {
        assertEquals(setOf("HUBEXA"), hubs.destinationHubs("910GHILL", "Hill", Pick.Level.STATION))
        assertEquals(setOf("HUBEXA"), hubs.destinationHubs("940GZZLUHIL", "Hill", Pick.Level.STATION))
        assertEquals(setOf("HUBEXA"), hubs.destinationHubs("490000001A", "Hill Station", Pick.Level.STOP))
        assertEquals(setOf("HUBEXA"), hubs.destinationHubs("HUBEXA", "Hill", Pick.Level.HUB))
        assertEquals(setOf("910GVALE"), hubs.destinationHubs("910GVALE", "Vale", Pick.Level.STATION))
        // Saved before phase 2, with no level: read as the pick it was.
        assertEquals(setOf("HUBEXA"), hubs.destinationHubs("910GHILL", "Hill", null))
    }

    @Test
    fun `a destination the bundle can't place matches the hubs of what it was planned to`() {
        assertEquals(setOf("HUBEXA", "910GVALE"), hubs.destinationHubs("HUBNOWHERE", "Somewhere", Pick.Level.HUB, listOf("910GHILL", "910GVALELL")))
        // Planned to nothing the model holds either: its own id, as it resolves.
        assertEquals(setOf("910GNOWHERE"), hubs.destinationHubs("910gnowhere", "Nowhere", Pick.Level.STATION))
    }

    @Test
    fun `a split area with no pole, a place, and no destination match no hub`() {
        assertEquals(emptySet<String>(), hubs.destinationHubs("490G000005", "", Pick.Level.STOP, listOf("490000005A")))
        assertEquals(emptySet<String>(), hubs.destinationHubs("51.5,-0.12", "A place", Pick.Level.PLACE))
        assertEquals(emptySet<String>(), hubs.destinationHubs("", "A place", null))
    }

    @Test
    fun `an id the bundle doesn't hold resolves to itself at both levels`() {
        assertEquals(Resolved("910GNOWHERE", "910GNOWHERE", "910GNOWHERE"), hubs.resolve("910GNOWHERE"))
        assertEquals(Resolved.exact("490NOWHERE1"), hubs.resolve("490NOWHERE1"))
    }

    @Test
    fun `an unbundled id compares the same whatever its case`() {
        val lower = hubs.resolve("490nowhere1")
        assertEquals("490nowhere1", lower.tflId)
        assertEquals(hubs.resolve("490NOWHERE1").station, lower.station)
        assertEquals(hubs.resolve("490NOWHERE1").hub, lower.hub)
        assertEquals(setOf("490nowhere1"), lower.poles)
    }

    @Test
    fun `a bundled id resolves to its station whatever its case`() {
        val upper = hubs.resolve("910GHILL")
        val lower = hubs.resolve("910ghill")
        assertEquals("910ghill", lower.tflId)
        assertEquals(upper.station, lower.station)
        assertEquals(upper.hub, lower.hub)
        assertEquals("1009001:rail", hubs.resolve("9100hill1").station)
    }

    @Test
    fun `a split area resolves through the pole or point given with it, else to none of its stops`() {
        val both = setOf("490000005A", "490000005B")
        val areaPoles = setOf("490000005A", "490000005B")
        val bare = hubs.resolve("490G000005")
        assertNull(bare.station)
        assertNull(bare.hub)
        assertEquals(both, bare.stops)
        assertEquals(setOf("490000005A"), bare.hubs)
        assertEquals(areaPoles, bare.poles)
        assertEquals("490000005B", hubs.resolve("490G000005", pole = "490000005B").station)
        assertEquals("490000005A", hubs.resolve("490G000005", pole = "490000005B").hub)
        // A pole outside the area is ignored, so a point decides; nothing decides with neither.
        assertNull(hubs.resolve("490G000005", pole = "490000001A").station)
        assertEquals("490000005B", hubs.resolve("490G000005", near = Coordinates(51.5202, -0.12)).station)
        assertEquals("490000005A", hubs.resolve("490G000005", pole = "490000001A", near = Coordinates(51.5199, -0.12)).station)
        // Only the area's own poles place it: another area's pole filed in Mill Lane's stop, south of
        // both, is no pole of it, given or nearest, though it stands nearer the point than the area's own.
        val foreign = Hubs.of(index, stops(tsv +
            "\nS\t490000006C\tB\tMill Lane\t51.519000\t-0.120000\t490G000006\t\t\t\t\t1\t\t490000005B\t\t490000005A"))!!
        assertEquals("490000005B", foreign.stopOf("490000006C").let { (it as Membership.Of).id })
        assertNull(foreign.resolve("490G000005", pole = "490000006C").station)
        assertEquals("490000005A", foreign.resolve("490G000005", near = Coordinates(51.5191, -0.12)).station)
    }

    @Test
    fun `a call naming several records keeps them all and agrees only where they do`() {
        val one = hubs.resolveAll(listOf("910GHILL", "910GHILLLL"))
        assertNull("no one id to get off at", one.tflId)
        assertEquals("1009001:rail", one.station)
        assertEquals("HUBEXA", one.hub)
        assertEquals(setOf("910GHILL", "910GHILLLL"), one.poles)
        val two = hubs.resolveAll(listOf("910GHILL", "940GZZLUHIL"))
        assertNull(two.station)
        assertEquals("HUBEXA", two.hub)
        assertEquals(setOf("1009001:rail", "1009002:tube"), two.stops)
        assertEquals(hubs.resolve("910GVALE"), hubs.resolveAll(listOf("910GVALE")))
    }

    @Test
    fun `a station record the index leaves out reads its station from the stops`() {
        assertTrue(hubs.sameStation("910GVALE", "910GVALELL"))
        assertEquals(Membership.Of("910GVALE"), hubs.hubOf("910GVALELL"))
    }

    @Test
    fun `a platform of a record only stops tsv holds is that record's station`() {
        assertEquals(Membership.Of("910GVALE"), hubs.stationOf("9100VALELL"))
    }

    @Test
    fun `an id the bundle doesn't hold is unbundled and matches only itself`() {
        assertEquals(Membership.Unbundled, hubs.stationOf("910GNOWHERE"))
        assertEquals(Membership.Unbundled, hubs.stopOf("490NOWHERE1"))
        assertEquals(Membership.Unbundled, hubs.hubOf("HUBNOWHERE"))
        assertTrue(hubs.sameHub("HUBNOWHERE", "hubnowhere"))
        assertFalse(hubs.sameHub("HUBNOWHERE", "910GHILL"))
    }

    @Test
    fun `each level's place is the middle of its records or poles`() {
        assertEquals(51.501, hubs.placeOf("1009001:rail", Hubs.Level.STATION)!!.latitude, 1e-9)
        assertEquals(51.5006, hubs.placeOf("490G000001", Hubs.Level.STOP)!!.latitude, 1e-9)
        // A hub counts each of its stations' records once, not its stations' middles.
        assertEquals((51.500 + 51.502 + 51.501) / 3, hubs.placeOf("HUBEXA", Hubs.Level.HUB)!!.latitude, 1e-9)
        assertNull(hubs.placeOf("910GNOWHERE", Hubs.Level.STATION))
        assertNull("an id is placed only at its own level", hubs.placeOf("HUBEXA", Hubs.Level.STOP))
    }

    @Test
    fun `a junction and the anchor stop it's named after are placed apart`() {
        // The junction is named after its anchor stop, 490000005A: one id, two levels.
        assertEquals(51.52015, hubs.placeOf("490000005A", Hubs.Level.HUB)!!.latitude, 1e-9)
        assertEquals(51.52, hubs.placeOf("490000005A", Hubs.Level.STOP)!!.latitude, 1e-9)
    }

    @Test
    fun `a bundle built before the model, or from two builds, is unavailable and says why`() {
        val reasons = mutableListOf<String>()
        assertNull(Hubs.of(index, stops(tsv.replace("\trevision=abc", "").replace("stops v2", "stops v1"))) { reasons += it })
        assertNull(Hubs.of(index, stops(tsv + "\nS\tbroken\tB")) { reasons += it })
        assertNull(Hubs.of(StationIndex(index.stations, revision = "abc", skippedEntries = 1), stops()) { reasons += it })
        assertNull(Hubs.of(index, stops(tsv.replace("revision=abc", "revision=def"))) { reasons += it })
        assertNull(Hubs.of(StationIndex(index.stations.map { it.copy(station = "") }, revision = "abc"), stops()) { reasons += it })
        assertNull(Hubs.of(index, stops(tsv.replace("\t490000005A\t\t490000005A\n", "\t\t\t\n"))) { reasons += it })
        assertEquals(
            listOf(
                "bundle built before the station model",
                "bundle has unreadable records",
                "bundle has unreadable records",
                "station index and stops are from different builds",
                "a station record has no membership",
                "a bus stop has no membership",
            ),
            reasons,
        )
    }
}
