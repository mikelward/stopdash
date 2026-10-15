package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Public interchanges as examples (SPEC *Privacy*: a hub is fine to name). */
class ViaChoiceTest {
    private val finsburyPark = TripVia("HUBFPK", "Finsbury Park")
    private val highbury = TripVia("940GZZLUHAI", "Highbury & Islington")

    @Test
    fun `an ordinary stop picked is the via at once`() {
        val choice = ViaChoice().picked(highbury, isComplex = false, toId = "940GZZLUCYF")
        assertEquals(ViaChoice(via = highbury), choice)
    }

    @Test
    fun `a complex picked waits on its stations, the via in place kept meanwhile`() {
        val choice = ViaChoice(via = highbury).picked(finsburyPark, isComplex = true, toId = "940GZZLUCYF")
        assertEquals(ViaChoice(via = highbury, pending = finsburyPark), choice)
        // Its stations looked up: it is the via.
        assertEquals(ViaChoice(via = finsburyPark), choice.resolved(finsburyPark))
    }

    @Test
    fun `a complex looked up is shown before it is the via, and the via in place until then`() {
        // Codex on #887: the trip showed the via in place for a frame after the pick was looked up.
        val choice = ViaChoice(via = highbury, pending = finsburyPark)
        assertEquals(finsburyPark, choice.shown(pendingFound = true))
        assertEquals(highbury, choice.shown(pendingFound = false))
        assertEquals(finsburyPark, ViaChoice(pending = finsburyPark).shown(pendingFound = true))
        assertNull(ViaChoice(pending = finsburyPark).shown(pendingFound = false))
        assertEquals(highbury, ViaChoice(via = highbury).shown(pendingFound = true))
    }

    @Test
    fun `the via in place and the pick pending are each looked up once`() {
        assertEquals(listOf(highbury, finsburyPark), ViaChoice(via = highbury, pending = finsburyPark).looked)
        assertEquals(listOf(finsburyPark), ViaChoice(via = finsburyPark, pending = finsburyPark).looked)
        assertEquals(emptyList<TripVia>(), ViaChoice().looked)
    }

    @Test
    fun `picking the trip's own destination changes nothing`() {
        val before = ViaChoice(via = highbury)
        assertEquals(before, before.picked(finsburyPark, isComplex = true, toId = "HUBFPK"))
        assertEquals(before, before.picked(highbury, isComplex = false, toId = "940GZZLUHAI"))
    }

    @Test
    fun `one of the destination's own stations is planned via as picked`() {
        // Maintainer, 2026-10-16: only the destination itself is ignored; a station or stop of it is a via like any.
        val fpkRail = TripVia("910GFNPK", "Finsbury Park")
        assertEquals(ViaChoice(via = fpkRail), ViaChoice().picked(fpkRail, isComplex = false, toId = "HUBFPK"))
        // An interchange holding the station the trip goes to waits on its stations like any other.
        assertEquals(ViaChoice(pending = finsburyPark), ViaChoice().picked(finsburyPark, isComplex = true, toId = "940GZZLUFPK"))
    }

    @Test
    fun `a lookup for a pick no longer pending changes nothing`() {
        val choice = ViaChoice(via = highbury, pending = finsburyPark).cancelled()
        assertEquals(ViaChoice(via = highbury), choice)
        assertEquals(choice, choice.resolved(finsburyPark))
    }

    @Test
    fun `the cross goes any way`() {
        assertEquals(ViaChoice(), ViaChoice(via = highbury, pending = finsburyPark).cleared())
    }
}
