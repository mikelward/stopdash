package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line laid out as a map and folded for its page, on the real Northern line: its eight routes as
 * TfL runs them (the app's bundled route data), with three northern branches, two central trunks that
 * both call at Euston without meeting there, Kennington where they meet again, and the Morden and
 * Battersea branches. The rider's stops here are big interchanges only.
 */
class LineMapTest {
    private val names = mapOf(
        "940GZZBPSUST" to "Battersea Power Station", "940GZZLUACY" to "Archway", "940GZZLUAGL" to "Angel",
        "940GZZLUBLM" to "Balham", "940GZZLUBNK" to "Bank", "940GZZLUBOR" to "Borough", "940GZZLUBTK" to "Burnt Oak",
        "940GZZLUBTX" to "Brent Cross", "940GZZLUBZP" to "Belsize Park", "940GZZLUCFM" to "Chalk Farm",
        "940GZZLUCHX" to "Charing Cross", "940GZZLUCND" to "Colindale", "940GZZLUCPC" to "Clapham Common",
        "940GZZLUCPN" to "Clapham North", "940GZZLUCPS" to "Clapham South", "940GZZLUCSD" to "Colliers Wood",
        "940GZZLUCTN" to "Camden Town", "940GZZLUEAC" to "Elephant & Castle", "940GZZLUEFY" to "East Finchley",
        "940GZZLUEGW" to "Edgware", "940GZZLUEMB" to "Embankment", "940GZZLUEUS" to "Euston",
        "940GZZLUFYC" to "Finchley Central", "940GZZLUGDG" to "Goodge Street", "940GZZLUGGN" to "Golders Green",
        "940GZZLUHBT" to "High Barnet", "940GZZLUHCL" to "Hendon Central", "940GZZLUHGT" to "Highgate",
        "940GZZLUHTD" to "Hampstead", "940GZZLUKNG" to "Kennington", "940GZZLUKSH" to "Kentish Town",
        "940GZZLUKSX" to "King's Cross St. Pancras", "940GZZLULNB" to "London Bridge", "940GZZLULSQ" to "Leicester Square",
        "940GZZLUMDN" to "Morden", "940GZZLUMGT" to "Moorgate", "940GZZLUMHL" to "Mill Hill East",
        "940GZZLUMTC" to "Mornington Crescent", "940GZZLUODS" to "Old Street", "940GZZLUOVL" to "Oval",
        "940GZZLUSKW" to "Stockwell", "940GZZLUSWN" to "South Wimbledon", "940GZZLUTAW" to "Totteridge & Whetstone",
        "940GZZLUTBC" to "Tooting Bec", "940GZZLUTBY" to "Tooting Broadway", "940GZZLUTCR" to "Tottenham Court Road",
        "940GZZLUTFP" to "Tufnell Park", "940GZZLUWFN" to "West Finchley", "940GZZLUWLO" to "Waterloo",
        "940GZZLUWOP" to "Woodside Park", "940GZZLUWRR" to "Warren Street", "940GZZNEUGST" to "Nine Elms",
    )
    private val ids = names.entries.associate { (id, name) -> name to id }
    private fun ids(vararg stations: String) = stations.map { ids.getValue(it) }

    private val edgware = ids("Edgware", "Burnt Oak", "Colindale", "Hendon Central", "Brent Cross", "Golders Green", "Hampstead", "Belsize Park", "Chalk Farm")
    private val finchley = ids("Finchley Central", "East Finchley", "Highgate", "Archway", "Tufnell Park", "Kentish Town")
    private val highBarnet = ids("High Barnet", "Totteridge & Whetstone", "Woodside Park", "West Finchley") + finchley
    private val millHillEast = ids("Mill Hill East") + finchley
    private val bank = ids("Camden Town", "Euston", "King's Cross St. Pancras", "Angel", "Old Street", "Moorgate", "Bank", "London Bridge", "Borough", "Elephant & Castle", "Kennington")
    private val charingCross = ids("Camden Town", "Mornington Crescent", "Euston", "Warren Street", "Goodge Street", "Tottenham Court Road", "Leicester Square", "Charing Cross", "Embankment", "Waterloo", "Kennington")
    private val morden = ids("Oval", "Stockwell", "Clapham North", "Clapham Common", "Clapham South", "Balham", "Tooting Bec", "Tooting Broadway", "Colliers Wood", "South Wimbledon", "Morden")
    private val battersea = ids("Nine Elms", "Battersea Power Station")

    // North to south: every northern end by either trunk to Morden, and Edgware and High Barnet by
    // Charing Cross to Battersea Power Station.
    private val southbound: List<List<String>> = buildList {
        for (north in listOf(edgware, highBarnet, millHillEast)) {
            add(north + bank + morden)
            add(north + charingCross + morden)
            if (north !== millHillEast) add(north + charingCross + battersea)
        }
    }

    private val positions = mapOf(
        "Edgware" to 51.61365, "High Barnet" to 51.65054, "Mill Hill East" to 51.60823,
        "Morden" to 51.40214, "Battersea Power Station" to 51.47993,
    ).entries.associate { (name, lat) -> ids.getValue(name) to (lat to 0.0) }

    private fun northern(routes: List<List<String>> = southbound, hubs: Map<String, String> = emptyMap()) = LineSequence(
        routes = routes.map { LineRoute("", it, "outbound") } + routes.map { LineRoute("", it.asReversed(), "inbound") },
        stopNames = names,
        stopPositions = positions,
        stopHubs = hubs,
    )

    // Shut both ways, as TfL lists such a closure: each way round, in the order trains run through it.
    private val closure = listOf(ids("Kennington", "Nine Elms", "Battersea Power Station"), ids("Battersea Power Station", "Nine Elms", "Kennington"))

    private fun LineMap.row(name: String) = rows.single { it.name == name }
    private fun List<LineMap.Item>.labels() = map { item ->
        when (item) {
            is LineMap.Item.Station -> item.row.name
            is LineMap.Item.Fold -> when {
                // An alert's own stretch: how many and how bad, never where.
                item.unnamed -> "[${item.count} ${item.level}]"
                item.section && item.ends.isNotEmpty() -> "[${item.ends.joinToString(" · ")}]"
                item.count == 1 -> "[${item.first}]"
                else -> "[${item.first} to ${item.last}]"
            }
        }
    }

    @Test
    fun `a straight line is one rail with an end at each end`() {
        val map = LineMap.of(northern(listOf(morden)))!!
        assertEquals(1, map.columns)
        assertEquals("Oval", map.rows.first().name)
        assertEquals(listOf(true, false), listOf(map.rows.first().end, map.rows[1].end))
        assertTrue(map.rows.none { it.junction })
    }

    @Test
    fun `every station once per branch, three branches wide, north at the top`() {
        val map = LineMap.of(northern())!!
        assertEquals(3, map.columns)
        assertEquals("Euston twice, every other station once", names.size + 1, map.rows.size)
        assertEquals("Edgware", map.rows.first().name)
        assertEquals(setOf("Finchley Central", "Camden Town", "Kennington"), map.rows.filter { it.junction }.mapTo(HashSet()) { it.name })
        assertEquals(setOf("Edgware", "High Barnet", "Mill Hill East", "Morden", "Battersea Power Station"), map.rows.filter { it.end }.mapTo(HashSet()) { it.name })
    }

    @Test
    fun `Euston is a row on each trunk, never a junction where trains could cross`() {
        val euston = LineMap.of(northern())!!.rows.filter { it.name == "Euston" }
        assertEquals(2, euston.size)
        assertTrue(euston.none { it.junction })
        assertEquals(2, euston.map { it.column }.distinct().size)
    }

    @Test
    fun `the trunks meet at Kennington on a curve, and part again for Battersea`() {
        val kennington = LineMap.of(northern())!!.row("Kennington")
        assertEquals(setOf(LineMap.Rail(0, 0), LineMap.Rail(1, 0)), kennington.top.toSet())
        assertEquals(setOf(LineMap.Rail(0, 0), LineMap.Rail(0, 1)), kennington.bottom.toSet())
    }

    @Test
    fun `the Battersea branch is drawn before the Morden branch carries on beside it`() {
        val map = LineMap.of(northern())!!
        val order = map.rows.map { it.name }
        assertTrue(order.indexOf("Battersea Power Station") < order.indexOf("Oval"))
        assertEquals("Morden keeps Kennington's column", map.row("Kennington").column, map.row("Oval").column)
    }

    @Test
    fun `run northbound as TfL lists it, the line is turned north up`() {
        val map = LineMap.of(northern(southbound.map { it.asReversed() }))!!
        assertTrue(map.rows.first().name in setOf("Edgware", "High Barnet", "Mill Hill East"))
        assertTrue(map.rows.last().name in setOf("Morden", "Battersea Power Station"))
    }

    @Test
    fun `routes kept without a direction still lay out, each once`() {
        val bare = LineSequence(southbound.flatMap { listOf(LineRoute("", it), LineRoute("", it.asReversed())) }, names, stopPositions = positions)
        assertEquals(names.size + 1, LineMap.of(bare)!!.rows.size)
    }

    @Test
    fun `a loop no route ends in gets no map`() {
        // The Circle as it ran before 2009, round and round: there's no top to start from.
        val circle = ids("Embankment", "Bank", "Moorgate", "King's Cross St. Pancras")
        val loop = LineSequence(listOf(LineRoute("", circle, "outbound"), LineRoute("", circle.drop(2) + circle.take(2), "outbound")), names)
        assertNull(LineMap.of(loop))
        assertNull(LineMap.of(LineSequence(emptyList(), names)))
    }

    @Test
    fun `the Battersea closure closes its track, and leaves Kennington open but on the page`() {
        val map = LineMap.of(northern(), closures = closure)!!
        val kennington = map.row("Kennington")
        assertTrue(kennington.bottom.single { it.from != it.to }.closed)
        assertFalse("Morden trains still run on from it", kennington.bottom.single { it.from == it.to }.closed)
        assertTrue(map.row("Nine Elms").unserved)
        assertTrue("and the branch's end beyond it", map.row("Battersea Power Station").unserved)
        assertFalse(kennington.unserved)
        // How bad, for a fold holding it: no service at Nine Elms; none to say at Kennington, still served.
        assertEquals(LineMap.Level.CLOSURE, map.row("Nine Elms").level)
        assertNull(kennington.level)
        assertFalse(map.row("Oval").alerted)
        // Where the closure begins, said for a screen reader too; not at a station with no service.
        assertTrue(kennington.besideClosure)
        assertFalse(map.row("Nine Elms").besideClosure)
        assertFalse(map.row("Oval").besideClosure)
    }

    @Test
    fun `shut one way only, the closure leaves trains calling going the other way`() {
        val map = LineMap.of(northern(), closures = closure.take(1))!!
        val kennington = map.row("Kennington")
        assertTrue("drawn closed", kennington.bottom.single { it.from != it.to }.closed)
        for (name in listOf("Nine Elms", "Battersea Power Station")) {
            assertFalse(name, map.row(name).unserved)
            assertTrue(name, map.row(name).servedOneWay)
            assertEquals(name, LineMap.Level.CLOSURE, map.row(name).level)
        }
        assertFalse(kennington.servedOneWay)
        assertFalse("shut both ways, nothing calls", LineMap.of(northern(), closures = closure)!!.row("Nine Elms").servedOneWay)
    }

    @Test
    fun `the stations the alert names are marked, where TfL placed no closure`() {
        val text = "No service between Kennington and Battersea Power Station while we fix a faulty train at Nine Elms."
        val marked = LineMap.of(northern(), alertText = text)!!
        assertEquals(setOf("Kennington", "Nine Elms", "Battersea Power Station"), marked.rows.filter { it.marked }.mapTo(HashSet()) { it.name })
    }

    @Test
    fun `the alert shown is placed by its closure, or by its words beside another alert's closure`() {
        val text = "No service between Kennington and Battersea Power Station. Use Oval instead."
        val suspended = PartClosure(3, "Part Suspended", text, closure)
        // The alert shown is the closure: drawn by its track, its words marking nothing, Oval included.
        val placed = LineMap.forStatus(northern(), LineStatus("northern", 3, "Part Suspended", fullText = text, closures = listOf(suspended)))!!
        assertTrue(placed.row("Nine Elms").unserved)
        assertTrue(placed.rows.none { it.marked })
        // Another alert shown beside the closure: both on the map.
        val delays = LineStatus("northern", 6, "Severe Delays", fullText = "Severe delays while we fix a signal at Hampstead.", closures = listOf(suspended))
        val both = LineMap.forStatus(northern(), delays)!!
        assertTrue(both.row("Nine Elms").unserved)
        assertEquals(setOf("Hampstead"), both.rows.filter { it.marked }.mapTo(HashSet()) { it.name })
        // A direction's own closure counts too; good service marks nothing.
        val oneWay = LineStatus("northern", 6, "Severe Delays", byDirection = mapOf("inbound" to LineStatus("northern", 3, "Part Suspended", closures = listOf(suspended.copy(sections = closure.take(1))))))
        assertTrue(LineMap.forStatus(northern(), oneWay)!!.row("Nine Elms").servedOneWay)
        assertTrue(LineMap.forStatus(northern(), LineStatus("northern", 10, "Good Service", fullText = text))!!.rows.none { it.marked })
    }

    // A made-up bus line: each stop's poles either side of the road share a stop area. Its way back
    // runs along another street from Alpha, calling at Xray instead of Beta.
    private val busNames = mapOf(
        "A1" to "Alpha", "A2" to "Alpha", "B1" to "Beta", "B2" to "Beta", "C1" to "Gamma", "C2" to "Gamma",
        "D1" to "Delta", "D2" to "Delta", "X2" to "Xray",
    )
    private val busAreas = busNames.keys.associateWith { it.take(1).lowercase() }

    private fun bus(back: List<String>, areas: Map<String, String> = busAreas) = LineSequence(
        routes = listOf(LineRoute("", listOf("A1", "B1", "C1", "D1"), "outbound"), LineRoute("", back, "inbound")),
        stopNames = busNames,
        stopAreas = areas,
    )

    @Test
    fun `a bus's way back is drawn where it runs elsewhere, its poles as the stops across the road`() {
        val map = LineMap.of(bus(listOf("D2", "C2", "X2", "A2")), closures = listOf(listOf("D2", "C2")), riding = setOf("C2", "X2"))!!
        assertEquals(listOf("Alpha", "Beta", "Delta", "Gamma", "Xray"), map.rows.map { it.name }.sorted())
        assertEquals(2, map.columns)
        assertTrue(map.row("Alpha").junction)
        assertTrue(map.row("Gamma").junction)
        // The rider's stops on the way back, by their own poles, are on the map.
        assertEquals(setOf("Gamma", "Xray"), map.rows.filter { it.riding }.mapTo(HashSet()) { it.name })
        // Shut on the way back only, by its own poles: Delta still served the other way.
        assertTrue(map.row("Delta").servedOneWay)
        assertFalse(map.row("Delta").unserved)
    }

    @Test
    fun `a way back the map can't take as well leaves the outbound way drawn alone`() {
        // Calling at Gamma before Beta on the way back would draw both twice: the outbound way alone, the
        // way back's poles still found on it.
        val crossed = LineMap.of(bus(listOf("D2", "B2", "C2", "A2")), closures = listOf(listOf("C2", "B2")), riding = setOf("B2"))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), crossed.rows.map { it.name })
        assertTrue(crossed.row("Beta").riding)
        assertTrue("shut going up the map", crossed.row("Beta").bottom.single().closedGoingUp)
        // Its closure on the way back left off the map: placed by its words instead, never an unaffected map.
        val words = "Buses are not calling at Beta."
        val unplaced = LineMap.forStatus(
            bus(listOf("D2", "B2", "C2", "A2")),
            LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("A2", "D2"))))),
        )!!
        assertTrue(unplaced.rows.none { row -> (row.top + row.bottom).any { it.closed } })
        assertEquals(setOf("Beta"), unplaced.rows.filter { it.marked }.mapTo(HashSet()) { it.name })
        // Nor when another closure's track lands: the one shown is still placed by its words (Codex, #606).
        val other = PartClosure(3, "Part Closure", "Gamma to Delta closed.", listOf(listOf("C1", "D1")))
        val beside = LineMap.forStatus(
            bus(listOf("D2", "B2", "C2", "A2")),
            LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("A2", "D2"))), other)),
        )!!
        assertTrue(beside.row("Gamma").bottom.single().closed)
        assertEquals(setOf("Beta"), beside.rows.filter { it.marked }.mapTo(HashSet()) { it.name })
        assertTrue(unplaced.closurePlaced)
        // Shut only at a stop of the way back that isn't drawn, and named only by it: nowhere on the map to put
        // it, so the map says so rather than look unaffected (Codex, #606).
        val xray = "Buses are not calling at Xray."
        val nowhere = LineMap.forStatus(
            bus(listOf("D2", "X2", "B2", "C2", "A2")),
            LineStatus("b", 3, "Part Suspended", fullText = xray, closures = listOf(PartClosure(3, "Part Suspended", xray, listOf(listOf("D2", "X2"))))),
        )!!
        assertTrue(nowhere.rows.none { it.name == "Xray" })
        assertFalse(nowhere.closurePlaced)
        assertTrue(LineMap.forStatus(northern(), LineStatus("northern", 3, "Part Suspended", fullText = xray, closures = listOf(PartClosure(3, "Part Suspended", xray, closure))))!!.closurePlaced)
        // Nor with no words at all: TfL gave it no reason, so it's known by how it's shown (Codex, #606).
        val reasonless = LineMap.forStatus(
            bus(listOf("D2", "X2", "B2", "C2", "A2")),
            LineStatus("b", 3, "Part Suspended", closures = listOf(PartClosure(3, "Part Suspended", null, listOf(listOf("D2", "X2"))))),
        )!!
        assertFalse(reasonless.closurePlaced)
        assertTrue(LineMap.forStatus(northern(), LineStatus("northern", 3, "Part Suspended", closures = listOf(PartClosure(3, "Part Suspended", null, closure))))!!.closurePlaced)
        // No stop areas to match its poles by: never the line twice, side by side.
        val apart = LineMap.of(bus(listOf("D2", "C2", "X2", "A2"), areas = emptyMap()))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), apart.rows.map { it.name })
    }

    // The made-up bus line's stops, a hundred meters or so apart down one street, and its way back's own
    // poles: Alpha's stand round the corner, named the same but in no stop area of its own.
    private val busPositions = mapOf(
        "A1" to (51.5000 to -0.12), "B1" to (51.4990 to -0.12), "C1" to (51.4980 to -0.12), "D1" to (51.4970 to -0.12),
        "A3" to (51.5002 to -0.1205), "D3" to (51.4970 to -0.1202), "Z3" to (51.4800 to -0.12),
    )

    private fun spread(back: List<String>, names: Map<String, String> = emptyMap(), hubs: Map<String, String> = emptyMap()) =
        bus(back).copy(
            stopNames = busNames + mapOf("A3" to "Alpha", "D3" to "Delta Station", "Z3" to "Delta") + names,
            stopAreas = busAreas,
            stopPositions = busPositions,
            stopHubs = hubs,
        )

    @Test
    fun `the way back's stops at the same place are one row, not a fork`() {
        // Ending at Alpha's other stand, in no stop area with the outbound one: the same name, yards away.
        val stand = LineMap.of(spread(listOf("D2", "C2", "B2", "A3")))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), stand.rows.map { it.name })
        assertEquals(1, stand.columns)
        // Starting at a stop of the same interchange, its name spelled its own way ("Delta Station").
        val hub = LineMap.of(spread(listOf("D3", "C2", "B2", "A2"), hubs = mapOf("D1" to "HUBD", "D3" to "HUBD")))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), hub.rows.map { it.name })
        assertEquals(1, hub.columns)
        // An interchange with two of its stops on the route: the one of its name; one named apart from
        // both is a place of its own, its row named as alerts name it (Codex, #663).
        val twoInHub = mapOf("C1" to "HUBD", "D1" to "HUBD", "D3" to "HUBD")
        val byName = LineMap.of(spread(listOf("D3", "C2", "B2", "A2"), names = mapOf("D3" to "Delta"), hubs = twoInHub))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), byName.rows.map { it.name })
        val unknown = spread(listOf("D3", "C2", "B2", "A2"), names = mapOf("D3" to "Concourse"), hubs = twoInHub)
        val unplaced = LineMap.of(unknown.copy(stopPositions = unknown.stopPositions - "D3"))!!
        assertEquals(1, unplaced.rows.count { it.name == "Concourse" })
        val words = "Buses are not calling at Concourse."
        val alert = LineMap.forStatus(unknown, LineStatus("b", 3, "Part Suspended", fullText = words))!!
        assertEquals(setOf("Concourse"), alert.rows.filter { it.marked }.mapTo(HashSet()) { it.name })
        // The same name far down the road is somewhere else: drawn as its own stop.
        val far = LineMap.of(spread(listOf("Z3", "C2", "B2", "A2")))!!
        assertEquals(2, far.rows.count { it.name == "Delta" })
    }

    @Test
    fun `a way back passing a stop without calling runs through it, not round it`() {
        val skipping = LineMap.of(spread(listOf("D2", "B2", "A2")))!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), skipping.rows.map { it.name })
        assertEquals(1, skipping.columns)
        assertTrue(skipping.rows.none { it.junction })
        // Shut the way back between the stops either side, it shuts the track drawn through the stop it
        // passes: placed on the map, not left to its words (Codex, #663).
        val words = "Buses are not running between Delta and Beta."
        val shut = LineMap.forStatus(
            spread(listOf("D2", "B2", "A2")),
            LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("D2", "B2"))))),
        )!!
        assertTrue(shut.closurePlaced)
        assertTrue(shut.row("Beta").bottom.single().closedGoingUp)
        assertTrue(shut.row("Gamma").bottom.single().closedGoingUp)
        assertFalse(shut.row("Alpha").bottom.single().closed)
        // Nor when another pattern of the way back leaves the outbound way drawn alone (Codex, #663).
        val alone = LineMap.forStatus(
            spread(listOf("D2", "B2", "A2")).let { it.copy(routes = it.routes + LineRoute("", listOf("D2", "B2", "C2", "A2"), "inbound")) },
            LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("D2", "B2"))))),
        )!!
        assertEquals(listOf("Alpha", "Beta", "Gamma", "Delta"), alone.rows.map { it.name })
        assertTrue(alone.closurePlaced)
        assertTrue(alone.row("Gamma").bottom.single().closedGoingUp)
        // Nor through a branch the way back may not run: two between the stops either side, neither taken.
        val branches = spread(listOf("D2", "B2", "A2")).let {
            it.copy(routes = it.routes + LineRoute("", listOf("A1", "B1", "X2", "D1"), "outbound"))
        }
        val either = LineMap.forStatus(
            branches,
            LineStatus("b", 3, "Part Suspended", fullText = words, closures = listOf(PartClosure(3, "Part Suspended", words, listOf(listOf("D2", "B2"))))),
        )!!
        // Shut on its own direct track instead, neither branch's stop.
        assertTrue(either.closurePlaced)
        assertTrue(either.rows.filter { it.name == "Gamma" || it.name == "Xray" }.none { row ->
            (row.top + row.bottom).any { it.closed && (it.from == row.column || it.to == row.column) }
        })
        // One with a stop of its own between still forks there.
        val elsewhere = LineMap.of(spread(listOf("D2", "C2", "X2", "A2")))!!
        assertEquals(2, elsewhere.columns)
    }

    @Test
    fun `a closure the rider dismissed while a milder alert stands is drawn too, or said to be off the map`() {
        val text = "No service between Kennington and Battersea Power Station."
        val suspended = LineStatus("northern", 3, "Part Suspended", fullText = text, closures = listOf(PartClosure(3, "Part Suspended", text, closure)))
        val minor = LineStatus("northern", 9, "Minor Delays")
        // The page names the dismissed closure beside the minor delays, so the map draws it (Codex, #606).
        assertTrue(LineMap.forStatus(northern(), minor)!!.rows.none { it.unserved })
        val both = LineMap.forStatus(northern(), minor, quieted = suspended)!!
        assertEquals(setOf("Nine Elms", "Battersea Power Station"), both.rows.filter { it.unserved }.mapTo(HashSet()) { it.name })
        assertTrue(both.closurePlaced)
        // One the map can't place anywhere is said to be off it, as the closure shown would be.
        val xray = "Buses are not calling at Xray."
        val off = LineStatus("b", 3, "Part Suspended", fullText = xray, closures = listOf(PartClosure(3, "Part Suspended", xray, listOf(listOf("D2", "X2")))))
        assertFalse(LineMap.forStatus(bus(listOf("D2", "X2", "B2", "C2", "A2")), LineStatus("b", 9, "Minor Delays"), quieted = off)!!.closurePlaced)
        // And it keys the map: the same status without it is another map, and with none, the key is as before.
        assertNotEquals(LineMap.alertKey(minor), LineMap.alertKey(minor, suspended))
        assertEquals(LineMap.alertKey(minor), LineMap.alertKey(minor, null))
    }

    @Test
    fun `two statuses drawing the same map share a key, and a changed alert changes it`() {
        val text = "No service between Kennington and Battersea Power Station."
        val suspended = LineStatus("northern", 3, "Part Suspended", fullText = text, closures = listOf(PartClosure(3, "Part Suspended", text, closure)))
        // Rebuilt with what the map doesn't draw changed (its planned work, say): the same key.
        assertEquals(LineMap.alertKey(suspended), LineMap.alertKey(suspended.copy(description = "Part suspended", planned = emptyList())))
        // Shut one way only, ended, or another alert's words shown: each another key.
        val oneWay = suspended.copy(closures = listOf(PartClosure(3, "Part Suspended", text, closure.take(1))))
        val good = LineStatus("northern", 10, "Good Service")
        val delays = suspended.copy(severity = 6, fullText = "Severe delays.")
        val keys = listOf(suspended, oneWay, good, delays).map { LineMap.alertKey(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(LineMap.alertKey(good), LineMap.alertKey(null))
        // With no words, how it's shown picks out the closure shown, so that keys it too.
        // Each closure counts by its own track and words, whatever the page shows it as.
        val reasonless = suspended.copy(fullText = null, closures = listOf(PartClosure(3, "Part Suspended", null, closure)))
        assertEquals(LineMap.alertKey(reasonless), LineMap.alertKey(reasonless.copy(severity = 6, description = "Severe Delays")))
    }

    @Test
    fun `the rider's stops are found by stop or interchange and never fold`() {
        val starred = LineMap.of(northern(), starred = setOf(ids.getValue("King's Cross St. Pancras")))!!
        assertTrue(starred.row("King's Cross St. Pancras").starred)
        assertTrue("King's Cross St. Pancras" in starred.folded(emptySet()).labels())
        val riding = LineMap.of(northern(hubs = mapOf(ids.getValue("Bank") to "HUBBAN")), riding = setOf("HUBBAN"))!!
        assertTrue(riding.row("Bank").riding)
        assertTrue("Bank" in riding.folded(emptySet()).labels())
    }

    @Test
    fun `the station nearest the rider never folds, and isn't starred`() {
        val kingsCross = ids.getValue("King's Cross St. Pancras")
        assertFalse("King's Cross St. Pancras" in LineMap.of(northern())!!.folded(emptySet()).labels())
        val near = LineMap.forStatus(northern(), null, nearby = setOf(kingsCross))!!
        assertTrue(near.row("King's Cross St. Pancras").nearby)
        assertFalse(near.row("King's Cross St. Pancras").starred)
        assertTrue("King's Cross St. Pancras" in near.folded(emptySet()).labels())
    }

    @Test
    fun `with good service only the plain runs fold, the ends and junctions stay`() {
        val map = LineMap.of(northern())!!
        assertEquals(
            listOf(
                "Edgware", "[Burnt Oak to Chalk Farm]", "High Barnet", "[Totteridge & Whetstone to West Finchley]",
                "Mill Hill East", "Finchley Central", "[East Finchley to Kentish Town]", "Camden Town",
                "[Mornington Crescent to Waterloo]", "[Euston to Elephant & Castle]", "Kennington",
                "Nine Elms", "Battersea Power Station", "[Oval to South Wimbledon]", "Morden",
            ),
            map.folded(emptySet()).labels(),
        )
        assertTrue(map.foldable())
    }

    @Test
    fun `an opened run shows its stations, and all shows every one`() {
        val map = LineMap.of(northern())!!
        val run = map.folded(emptySet()).filterIsInstance<LineMap.Item.Fold>().first { it.first == "Mornington Crescent" }
        assertEquals(9, run.count)
        assertTrue("the other trunk going by is drawn solid", run.rails.any { !it.folded })
        assertTrue("Goodge Street" in map.folded(setOf(run.key)).labels())
        assertEquals(map.rows.size, map.folded(emptySet(), all = true).size)
    }

    @Test
    fun `a closure off the rider's stops and rides folds with where it is, its fold saying how bad`() {
        val map = LineMap.of(northern(), closures = closure)!!
        val items = map.folded(emptySet())
        // The line as with good service, but Nine Elms folded: no station of it named on its own but the
        // line's end, which always shows (maintainer, 2026-10-06).
        assertEquals(
            listOf(
                "Edgware", "[Burnt Oak to Chalk Farm]", "High Barnet", "[Totteridge & Whetstone to West Finchley]",
                "Mill Hill East", "Finchley Central", "[East Finchley to Kentish Town]", "Camden Town",
                "[Mornington Crescent to Waterloo]", "[Euston to Elephant & Castle]", "Kennington",
                "[1 CLOSURE]", "Battersea Power Station", "[Oval to South Wimbledon]", "Morden",
            ),
            items.labels(),
        )
        assertEquals(LineMap.Level.CLOSURE, map.row("Battersea Power Station").level)
        val battersea = items.filterIsInstance<LineMap.Item.Fold>().single { it.first == "Nine Elms" }
        assertEquals(LineMap.Level.CLOSURE, battersea.level)
        assertEquals(1, battersea.count)
        assertTrue("the plain runs say nothing", items.filterIsInstance<LineMap.Item.Fold>().filter { it !== battersea }.all { it.level == null })
        // A tap shows it in full.
        assertEquals(
            listOf("Kennington", "Nine Elms", "Battersea Power Station", "[Oval to South Wimbledon]"),
            map.folded(setOf(battersea.key)).labels().let { it.subList(it.indexOf("Kennington"), it.indexOf("Morden")) },
        )
        // A station an alert's words name folds the same way, as a warning, even one on its own.
        val words = LineMap.of(northern(), alertText = "Severe delays while we fix a signal at Hampstead.")!!.folded(emptySet())
        assertTrue("Hampstead" !in words.labels())
        // Folded with the plain stations around it, up to the end and the junction either side: no run
        // named beside it to say where.
        assertTrue("[8 WARNING]" in words.labels())
        assertTrue(words.labels().none { "Burnt Oak" in it || "Golders Green" in it || "Chalk Farm" in it })
    }

    @Test
    fun `a junction an alert names still shows, the runs either side folding`() {
        // Camden Town, where the Edgware and High Barnet branches meet the two trunks, named in a delay's
        // words: it stays on the page, never folded with the stations beside it (maintainer, 2026-10-06).
        val map = LineMap.of(northern(), alertText = "Minor delays between Chalk Farm and Camden Town.")!!
        assertEquals(LineMap.Level.WARNING, map.row("Camden Town").level)
        val labels = map.folded(emptySet()).labels()
        assertTrue("Camden Town" in labels)
        assertTrue("Chalk Farm" !in labels)
        assertEquals("[8 WARNING]", labels[labels.indexOf("Edgware") + 1])
    }

    @Test
    fun `an end of the line an alert names still shows, the stations along it folding`() {
        // The Metropolitan line, trimmed at Baker Street, with a delay between Harrow-on-the-Hill and
        // Uxbridge: Uxbridge, the branch's end, stays on the page; the stations along it fold, saying
        // how bad (maintainer, 2026-10-06).
        val met = mapOf(
            "940GZZLUAMS" to "Amersham", "940GZZLUCSM" to "Chesham", "940GZZLUCAL" to "Chalfont & Latimer",
            "940GZZLUCYD" to "Chorleywood", "940GZZLURKW" to "Rickmansworth", "940GZZLUWAF" to "Watford",
            "940GZZLUCXY" to "Croxley", "940GZZLUMPK" to "Moor Park", "940GZZLUNWD" to "Northwood",
            "940GZZLUNWH" to "Northwood Hills", "940GZZLUPNR" to "Pinner", "940GZZLUNHA" to "North Harrow",
            "940GZZLUUXB" to "Uxbridge", "940GZZLUHGD" to "Hillingdon", "940GZZLUICK" to "Ickenham",
            "940GZZLURSP" to "Ruislip", "940GZZLURSM" to "Ruislip Manor", "940GZZLUEAE" to "Eastcote",
            "940GZZLURYL" to "Rayners Lane", "940GZZLUWHW" to "West Harrow", "940GZZLUHOH" to "Harrow-on-the-Hill",
            "940GZZLUNKP" to "Northwick Park", "940GZZLUPRD" to "Preston Road", "940GZZLUWYP" to "Wembley Park",
            "940GZZLUFYR" to "Finchley Road", "940GZZLUBST" to "Baker Street",
        )
        val metIds = met.entries.associate { (id, name) -> name to id }
        fun met(vararg stations: String) = stations.map { metIds.getValue(it) }
        val trunk = met("Harrow-on-the-Hill", "Northwick Park", "Preston Road", "Wembley Park", "Finchley Road", "Baker Street")
        val toMoorPark = met("Moor Park", "Northwood", "Northwood Hills", "Pinner", "North Harrow") + trunk
        val routes = listOf(
            met("Amersham", "Chalfont & Latimer", "Chorleywood", "Rickmansworth") + toMoorPark,
            met("Chesham", "Chalfont & Latimer", "Chorleywood", "Rickmansworth") + toMoorPark,
            met("Watford", "Croxley") + toMoorPark,
            met("Uxbridge", "Hillingdon", "Ickenham", "Ruislip", "Ruislip Manor", "Eastcote", "Rayners Lane", "West Harrow") + trunk,
        )
        val sequence = LineSequence(
            routes = routes.map { LineRoute("", it, "outbound") } + routes.map { LineRoute("", it.asReversed(), "inbound") },
            stopNames = met,
            stopPositions = mapOf(
                "Amersham" to 51.674, "Chesham" to 51.705, "Watford" to 51.657, "Uxbridge" to 51.546, "Baker Street" to 51.522,
            ).entries.associate { (name, lat) -> metIds.getValue(name) to (lat to 0.0) },
        )
        val text = "Metropolitan Line: Minor delays between Harrow-on-the-Hill and Uxbridge due to an earlier " +
            "track fault at Ickenham. GOOD SERVICE on the rest of the line."
        val map = LineMap.of(sequence, alertText = text)!!
        assertTrue(map.row("Uxbridge").end)
        assertEquals(LineMap.Level.WARNING, map.row("Uxbridge").level)
        val labels = map.folded(emptySet()).labels()
        assertTrue(labels.containsAll(listOf("Amersham", "Chesham", "Watford", "Uxbridge")))
        // The stations along the branch fold behind it, naming none of them.
        assertTrue(labels.none { "Ickenham" in it || "Hillingdon" in it || "West Harrow" in it })
        assertTrue(labels[labels.indexOf("Uxbridge") + 1].endsWith(" WARNING]"))
    }

    @Test
    fun `a closure off the rider's route folds with the plain stations around it, naming none`() {
        // A made-up closure between Angel and Moorgate, on no ride: Angel and Moorgate, open, fold with
        // it and the rest of the Bank branch's run, so no run's name says where it is (maintainer,
        // 2026-10-06).
        val shut = ids("Angel", "Old Street", "Moorgate")
        val map = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()))!!
        assertTrue(map.row("Angel").besideClosure)
        val items = map.folded(emptySet())
        val fold = items.filterIsInstance<LineMap.Item.Fold>().single { it.level != null }
        assertEquals(LineMap.Level.CLOSURE, fold.level)
        assertTrue(fold.unnamed)
        assertTrue("its track drawn closed", fold.rails.single { it.folded }.closed)
        assertTrue(items.labels().none { "Angel" in it || "Moorgate" in it || "Euston" in it && it.startsWith("[") })
        // The junctions either side stay on the page, as with good service.
        assertTrue(items.labels().containsAll(listOf("Camden Town", "Kennington")))
        // A tap opens it in full.
        assertTrue("Old Street" in map.folded(setOf(fold.key)).labels())
    }

    @Test
    fun `a closed track between two stations still served folds as a closure`() {
        // A made-up closure of the track between Old Street and Moorgate alone: both still served the
        // other side, so neither is a station without service, but the fold still says so (Codex, #613).
        val shut = ids("Old Street", "Moorgate")
        val map = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()))!!
        assertTrue(map.row("Old Street").besideClosure)
        val items = map.folded(emptySet())
        val fold = items.filterIsInstance<LineMap.Item.Fold>().single { it.level != null }
        assertEquals(LineMap.Level.CLOSURE, fold.level)
        assertTrue(fold.unnamed)
        assertTrue(items.labels().none { "Old Street" in it || "Moorgate" in it })
    }

    @Test
    fun `an alert on a stretch the rider rides shows in full, the rest folding around it`() {
        // A made-up closure between Angel and Moorgate, on a ride from King's Cross St. Pancras to Bank.
        val ride = ids("Angel", "Old Street", "Moorgate")
        val closures = listOf(ride, ride.asReversed()) + closure
        val rides = listOf(ids("King's Cross St. Pancras", "Bank"))
        val map = LineMap.of(northern(), closures = closures, riding = rides.flatten().toSet(), rides = rides)!!
        assertTrue(map.row("Old Street").ridden)
        assertTrue(map.row("Old Street").unserved)
        val items = map.folded(emptySet())
        assertEquals(
            listOf(
                "[Edgware · High Barnet · Mill Hill East]", "King's Cross St. Pancras", "Angel", "Old Street", "Moorgate", "Bank",
                "[Battersea Power Station · Morden]",
            ),
            items.labels(),
        )
        // The Battersea closure, off the ride, says how bad on the fold it's in.
        assertEquals(LineMap.Level.CLOSURE, (items.last() as LineMap.Item.Fold).level)
        assertFalse("named by where it leads, as it would be without the alert", (items.last() as LineMap.Item.Fold).unnamed)
        assertEquals("Euston counted once", 30, (items.first() as LineMap.Item.Fold).count)
        // Ridden the other way up the map, the same stretch.
        val back = LineMap.of(northern(), closures = closures, rides = rides.map { it.asReversed() })!!
        assertTrue(back.row("Old Street").ridden)
        assertTrue("never past where it boards", back.rows.filter { it.name == "Euston" }.none { it.ridden })
    }

    @Test
    fun `a closure one way doesn't touch a ride the other way over the same track`() {
        // A made-up closure between Angel and Moorgate for trains running from Angel to Moorgate only.
        val shut = ids("Angel", "Old Street", "Moorgate")
        // The trip's own stops passed as the app passes them, each ride's ends.
        fun ride(vararg names: String) = LineMap.of(northern(), closures = listOf(shut), riding = ids(*names).toSet(), rides = listOf(ids(*names)))!!
        assertTrue("ridden the way it's shut: the rider's", ride("King's Cross St. Pancras", "Bank").alerted)
        // From Bank to King's Cross St. Pancras, the way trains still run: off the trip, it folds
        // (Codex, #613).
        val against = ride("Bank", "King's Cross St. Pancras")
        assertTrue(against.row("Old Street").ridden)
        assertFalse(against.alerted)
        assertFalse(against.row("Old Street").kept)
        // Boarding at Old Street, shut one way, the same: the way the trip leaves it decides.
        val leaving = ride("Old Street", "Angel")
        assertTrue(leaving.row("Old Street").riding)
        assertFalse(leaving.alerted)
        assertTrue(ride("Old Street", "Moorgate").alerted)
        // Where no ride places it, a stop shut either way is still the rider's.
        val lost = LineMap.of(northern(), closures = listOf(shut), riding = ids("Old Street").toSet(), rides = listOf(listOf("unknown-stop") + ids("Old Street")))!!
        assertTrue(lost.row("Old Street").ridingUnplaced)
        assertTrue(lost.alerted)
    }

    @Test
    fun `a ride's planned calls pick its branch where two join the same stops`() {
        // From Euston to Camden Town by Mornington Crescent, on the Charing Cross branch, though the Bank
        // branch joins the two directly (Codex, #613).
        val planned = LineMap.of(northern(), rides = listOf(ids("Euston", "Mornington Crescent", "Camden Town")))!!
        assertTrue(planned.row("Mornington Crescent").ridden)
        assertEquals("only the Euston it rides from", 1, planned.rows.count { it.name == "Euston" && it.ridden })
        // A closure on it shows in full; one on the other branch folds.
        val shut = ids("Euston", "Mornington Crescent", "Camden Town")
        val closed = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()), rides = listOf(shut))!!
        assertTrue(closed.row("Mornington Crescent").kept)
        assertTrue("Mornington Crescent" in closed.folded(emptySet()).labels())
        // With only its two ends known, the nearest way between them: the Bank branch's.
        val ends = LineMap.of(northern(), rides = listOf(ids("Euston", "Camden Town")))!!
        assertFalse(ends.row("Mornington Crescent").ridden)
        assertTrue(ends.row("Camden Town").ridden)
    }

    @Test
    fun `a planned call the map can't find still counts a station between`() {
        // Mornington Crescent under an id the cached line lacks: two tracks from Euston to Camden Town, so
        // the Charing Cross branch, not the Bank branch's one direct track (Codex, #613).
        val lost = LineMap.of(northern(), rides = listOf(ids("Euston") + "unknown-stop" + ids("Camden Town")))!!
        assertTrue(lost.row("Mornington Crescent").ridden)
        assertEquals("only the Euston it rides from", 1, lost.rows.count { it.name == "Euston" && it.ridden })
        // On a longer ride, the stretches either side count too: Old Street lost between Angel and Moorgate.
        val ride = ids("King's Cross St. Pancras", "Angel") + "unknown-stop" + ids("Moorgate", "Bank")
        val map = LineMap.of(northern(), rides = listOf(ride))!!
        assertTrue(listOf("King's Cross St. Pancras", "Angel", "Old Street", "Moorgate", "Bank").all { map.row(it).ridden })
        // Two calls lost where no way three tracks long runs: left out, never a branch guessed.
        val gap = LineMap.of(northern(), rides = listOf(ids("Euston") + listOf("unknown-1", "unknown-2") + ids("Camden Town")))!!
        assertTrue(gap.rows.none { it.ridden })
    }

    @Test
    fun `a closure on another branch where the ride only gets off folds as anywhere else`() {
        // A made-up closure up the Charing Cross branch from Camden Town, on a ride from Euston to Camden
        // Town by the Bank branch: the closed track isn't one the ride takes (Codex, #613).
        val shut = ids("Camden Town", "Mornington Crescent", "Euston")
        val rides = listOf(ids("Euston", "Camden Town"))
        val map = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()), riding = rides.flatten().toSet(), rides = rides)!!
        assertTrue(map.row("Camden Town").besideClosure)
        assertFalse("nothing alerted on the ride", map.alerted)
        val labels = map.folded(emptySet()).labels()
        assertTrue("the line's ends and junctions as with good service", labels.containsAll(listOf("Edgware", "Camden Town", "Kennington")))
        val fold = map.folded(emptySet()).filterIsInstance<LineMap.Item.Fold>().single { it.level != null }
        assertEquals("Mornington Crescent folded, saying there's no service", LineMap.Level.CLOSURE, fold.level)
        assertTrue(fold.unnamed)
        assertTrue(labels.none { "Mornington Crescent" in it })
        // The same closure on the ride's own track, ridden by Mornington Crescent: shown in full.
        val through = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()), rides = listOf(shut.asReversed()))!!
        assertTrue(through.alerted)
        assertTrue("Mornington Crescent" in through.folded(emptySet()).labels())
    }

    @Test
    fun `a station on two branches is the rider's only on the one their trip rides`() {
        // A made-up closure shutting the Charing Cross branch's Euston, on a ride from Euston to King's Cross
        // St. Pancras by the Bank branch (Codex, #613).
        val shut = ids("Mornington Crescent", "Euston", "Warren Street")
        val rides = listOf(ids("Euston", "King's Cross St. Pancras"))
        val map = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()), riding = rides.flatten().toSet(), rides = rides)!!
        val euston = map.rows.filter { it.name == "Euston" }
        assertEquals(LineMap.Level.CLOSURE, euston.single { !it.riding }.level)
        assertNull("the one the trip rides is open", euston.single { it.riding }.level)
        assertFalse("nothing alerted on the ride", map.alerted)
        // With no ride to say which, both rows are the rider's.
        assertTrue(LineMap.of(northern(), riding = setOf(ids.getValue("Euston")))!!.rows.filter { it.name == "Euston" }.all { it.riding })
    }

    @Test
    fun `each ride's stop at a station on two branches is judged by its own stretch`() {
        // One ride from Euston to King's Cross St. Pancras on the Bank branch, and another ending at
        // Euston whose other end the cached line lacks: the second says nothing of which Euston, so both
        // stay the rider's, not narrowed by the first ride's branch (Codex, #613).
        val rides = listOf(ids("Euston", "King's Cross St. Pancras"), listOf("unknown-stop") + ids("Euston"))
        val map = LineMap.of(northern(), riding = rides.flatten().toSet(), rides = rides)!!
        assertEquals(2, map.rows.count { it.name == "Euston" && it.riding })
        // The first ride alone keeps only its own Euston.
        val one = LineMap.of(northern(), riding = rides.first().toSet(), rides = rides.take(1))!!
        assertEquals(1, one.rows.count { it.name == "Euston" && it.riding })
    }

    @Test
    fun `an opened stretch shows every station it holds in one tap`() {
        val ride = ids("Angel", "Old Street", "Moorgate")
        val rides = listOf(ids("King's Cross St. Pancras", "Bank"))
        val map = LineMap.of(northern(), closures = listOf(ride, ride.asReversed()), riding = rides.flatten().toSet(), rides = rides)!!
        val north = map.folded(emptySet()).first() as LineMap.Item.Fold
        val opened = map.folded(setOf(north.key)).labels().let { it.subList(0, it.indexOf("King's Cross St. Pancras")) }
        // Its ends and every station between, never its ends alone with the rest folded again.
        assertTrue(opened.none { it.startsWith("[") })
        assertEquals(listOf("Edgware", "Burnt Oak", "Colindale"), opened.take(3))
        assertEquals("Euston on each trunk", north.count + 1, opened.size)
    }

    @Test
    fun `a starred station a closure shuts shows it, and a stretch leading nowhere shows its runs`() {
        // A made-up closure through King's Cross St. Pancras, both of the rider's starred stops big interchanges.
        val shut = ids("Euston", "King's Cross St. Pancras", "Angel")
        val starred = setOf(ids.getValue("King's Cross St. Pancras"), ids.getValue("Bank"))
        val map = LineMap.of(northern(), closures = listOf(shut, shut.asReversed()), starred = starred)!!
        assertTrue(map.row("King's Cross St. Pancras").unserved)
        assertEquals(
            listOf(
                "[Edgware · High Barnet · Mill Hill East]", "King's Cross St. Pancras",
                // Between King's Cross and Bank, leading to no end of the line: its run, on one track,
                // saying the closure runs on into it.
                "[3 CLOSURE]", "Bank", "[Battersea Power Station · Morden]",
            ),
            map.folded(emptySet()).labels(),
        )
    }
}
