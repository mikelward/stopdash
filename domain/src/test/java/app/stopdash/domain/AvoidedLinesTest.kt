package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AvoidedLinesTest {
    private val northern = AvoidedLines.key("Northern", "Northern line")

    @Test
    fun `a line is avoided by its id, whatever its case`() {
        assertEquals("line:northern=Northern line", northern)
        assertTrue(AvoidedLines.avoids("northern", setOf(northern)))
        assertTrue(AvoidedLines.avoids("NORTHERN", setOf(northern)))
        assertFalse(AvoidedLines.avoids("central", setOf(northern)))
        assertFalse(AvoidedLines.avoids("", setOf(northern)))
    }

    @Test
    fun `each avoided line is named by its label, in the order avoided`() {
        val central = AvoidedLines.key("central", "Central line")
        assertEquals(listOf(central to "Central line", northern to "Northern line"), AvoidedLines.labeled(linkedSetOf(central, northern, "bus")))
        assertEquals(emptyList<Pair<String, String>>(), AvoidedLines.labeled(emptySet()))
    }

    @Test
    fun `a stored entry that isn't a line's is dropped`() {
        assertEquals(setOf(northern), AvoidedLines.fromStored(setOf(northern, "bus")))
    }

    @Test
    fun `a trip leaves out what's hidden and what's avoided`() {
        val hidden = setOf("bus")
        assertSame(hidden, AvoidedLines.excluded(hidden, emptySet()))
        val excluded = AvoidedLines.excluded(hidden, setOf(northern))
        assertEquals(setOf("bus", northern), excluded)
        // A route on the avoided line is left out as a hidden line's is.
        assertTrue(HiddenModes.isHidden("tube", "northern", excluded))
        assertFalse(HiddenModes.isHidden("tube", "central", excluded))
    }

    @Test
    fun `a tap adds a line at the end or takes it out, leaving the rest in order`() {
        val northern = AvoidedLines.key("northern", "Northern line")
        val central = AvoidedLines.key("central", "Central line")
        val avoided = AvoidedLines.changed(setOf(northern), central, avoided = true)
        assertEquals(listOf(northern, central), avoided.toList())
        assertEquals(setOf(northern), AvoidedLines.changed(avoided, central, avoided = false))
        // Avoiding one already avoided changes nothing.
        assertEquals(avoided, AvoidedLines.changed(avoided, northern, avoided = true))
    }

    private fun leg(mode: String, line: String, from: String, to: String, fromArea: String = "", toArea: String = "") = TripLeg(
        mode, line, line, from, from, to, to, java.time.Instant.EPOCH, java.time.Instant.EPOCH, fromArea = fromArea, toArea = toArea,
    )

    private val bank = AvoidedLines.stopKey("940GZZLUBNK", "Bank")

    @Test
    fun `a stop is avoided by its id and named by its name, beside the lines`() {
        assertEquals("stop:940GZZLUBNK=Bank", bank)
        assertTrue(AvoidedLines.isStopKey(bank))
        assertFalse(AvoidedLines.isStopKey(northern))
        assertEquals(setOf("940GZZLUBNK"), AvoidedLines.stopIds(setOf(bank, northern, "bus")))
        assertEquals(listOf(northern to "Northern line", bank to "Bank"), AvoidedLines.labeled(linkedSetOf(northern, bank)))
        // Kept from storage with the lines.
        assertEquals(setOf(northern, bank), AvoidedLines.fromStored(setOf(northern, bank, "bus")))
    }

    @Test
    fun `a route boarding, getting off or changing at an avoided stop is dropped, one riding through it isn't`() {
        val excluded = AvoidedLines.excluded(setOf("bus"), setOf(bank))
        val boards = TripRoute(listOf(leg("tube", "northern", "940GZZLUBNK", "940GZZLUKGX")))
        val getsOff = TripRoute(listOf(leg("tube", "central", "940GZZLUOXC", "940GZZLUBNK")))
        val changesOnFoot = TripRoute(
            listOf(leg("tube", "central", "940GZZLUOXC", "940GZZLUBNK"), leg(TripLeg.WALKING, "", "940GZZLUBNK", "940GZZLUMMT")),
        )
        // Through Bank on the Northern line without getting off: trains run through a closed station.
        val through = TripRoute(listOf(leg("tube", "northern", "940GZZLULBN", "940GZZLUKGX")))
        assertTrue(AvoidedLines.drops(boards, excluded))
        assertTrue(AvoidedLines.drops(getsOff, excluded))
        assertTrue(AvoidedLines.drops(changesOnFoot, excluded))
        assertFalse(AvoidedLines.drops(through, excluded))
        // The lines left out still drop a route, as they did.
        assertTrue(AvoidedLines.drops(TripRoute(listOf(leg("bus", "73", "A", "B"))), excluded))
        assertTrue(AvoidedLines.drops(through, AvoidedLines.excluded(emptySet(), setOf(northern))))
        assertFalse(AvoidedLines.drops(through, emptySet()))
    }

    @Test
    fun `a bus stop avoided by its area leaves out both its poles`() {
        val stop = AvoidedLines.stopKey("490GEXAMPLE", "Example Stop")
        val route = TripRoute(listOf(leg("bus", "73", "490EXAMPLEA", "490EXAMPLEB", fromArea = "490GEXAMPLE")))
        assertTrue(AvoidedLines.drops(route, setOf(stop)))
        // A blank id avoids nothing.
        assertFalse(AvoidedLines.drops(TripRoute(listOf(leg("bus", "73", "A", "B"))), setOf(AvoidedLines.stopKey("", "Nowhere"))))
    }
}
