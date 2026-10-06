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
            is LineMap.Item.Fold -> if (item.section) "[${item.ends.joinToString(" · ")}]" else "[${item.first} to ${item.last}]"
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
        assertTrue(kennington.kept)
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
            assertTrue(name, map.row(name).kept)
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
        val reasonless = suspended.copy(fullText = null, closures = listOf(PartClosure(3, "Part Suspended", null, closure)))
        assertNotEquals(LineMap.alertKey(reasonless), LineMap.alertKey(reasonless.copy(severity = 6, description = "Severe Delays")))
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
    fun `with the Battersea closure the rest of the line folds to stretches, each naming its ends`() {
        val map = LineMap.of(northern(), closures = closure)!!
        val items = map.folded(emptySet())
        assertEquals(
            listOf("[Edgware · High Barnet · Mill Hill East]", "Kennington", "Nine Elms", "Battersea Power Station", "[Morden]"),
            items.labels(),
        )
        assertEquals("Euston counted once", 38, (items.first() as LineMap.Item.Fold).count)
    }

    @Test
    fun `an opened stretch shows its ends and junctions, its runs still folded`() {
        val map = LineMap.of(northern(), closures = closure)!!
        val north = map.folded(emptySet()).first()
        assertEquals(
            listOf(
                "Edgware", "[Burnt Oak to Chalk Farm]", "High Barnet", "[Totteridge & Whetstone to West Finchley]",
                "Mill Hill East", "Finchley Central", "[East Finchley to Kentish Town]", "Camden Town",
                "[Mornington Crescent to Waterloo]", "[Euston to Elephant & Castle]",
                "Kennington", "Nine Elms", "Battersea Power Station", "[Morden]",
            ),
            map.folded(setOf(north.key)).labels(),
        )
    }

    @Test
    fun `a starred station splits the stretch it's in, and the stretch leading nowhere shows its runs`() {
        val map = LineMap.of(northern(), closures = closure, starred = setOf(ids.getValue("King's Cross St. Pancras")))!!
        val labels = map.folded(emptySet()).labels()
        val kingsCross = labels.indexOf("King's Cross St. Pancras")
        assertEquals("[Edgware · High Barnet · Mill Hill East]", labels.first())
        // Between King's Cross and Kennington, each trunk's run on its own, never one fold jumping between them.
        assertEquals(listOf("[Angel to Elephant & Castle]", "Kennington"), labels.subList(kingsCross + 1, kingsCross + 3))
        assertTrue(labels.subList(0, kingsCross).contains("[Edgware · High Barnet · Mill Hill East]"))
    }
}
