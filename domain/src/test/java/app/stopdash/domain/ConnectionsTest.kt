package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    @Test
    fun `the ridden line and buses are left out`() {
        val lines = listOf(
            LineRef("northern", "Northern", "tube"),
            LineRef("victoria", "Victoria", "tube"),
            LineRef("24", "24", "bus"),
        )
        assertEquals(listOf("victoria"), Connections.of(lines, "northern").map { it.id })
    }

    @Test
    fun `a mixed-mode interchange keeps only lines known by id`() {
        // Blank modes: an interchange lists buses, national rail and rail-type lines together.
        val lines = listOf(
            LineRef("lioness", "Lioness", ""),
            LineRef("elizabeth", "Elizabeth line", ""),
            LineRef("avanti-west-coast", "Avanti West Coast", ""),
            LineRef("n5", "N5", ""),
            // An operator TfL has no line id for here, so it isn't guessed at.
            LineRef("example-railway", "Example Railway", ""),
        )
        val connections = Connections.of(lines, "northern")
        assertEquals(listOf("lioness", "elizabeth", "avanti-west-coast"), connections.map { it.id })
        assertEquals(listOf("overground", "elizabeth-line", "national-rail"), connections.map { it.mode })
    }

    @Test
    fun `every National Rail operator TfL names is known by id, and none is a tube line`() {
        // King's Cross St. Pancras, say: the operators at the interchange, each a national-rail pill.
        val lines = listOf("thameslink", "great-northern", "london-north-eastern-railway", "east-midlands-railway", "southeastern")
            .map { LineRef(it, it, "") }
        assertEquals(List(lines.size) { "national-rail" }, Connections.of(lines, "victoria").map { it.mode })
        assertTrue(NATIONAL_RAIL_LINE_IDS.isNotEmpty())
        for (id in NATIONAL_RAIL_LINE_IDS) assertEquals(id, "national-rail", Connections.knownMode(id))
        // Northern (the operator) has its own id, so the tube line keeps its mode.
        assertEquals("tube", Connections.knownMode("northern"))
        assertEquals("national-rail", Connections.knownMode("northern-rail"))
    }

    @Test
    fun `a single-mode rail station keeps its operators, once each`() {
        val lines = listOf(
            LineRef("southern", "Southern", "national-rail"),
            LineRef("southern", "Southern", ""),
        )
        assertEquals(listOf(LineRef("southern", "Southern", "national-rail")), Connections.of(lines, "thameslink"))
    }

    @Test
    fun `only rail-type modes count, so a pier's river buses and a coach stop's coaches don't`() {
        val lines = listOf(
            LineRef("rb1", "RB1", "river-bus"),
            LineRef("rb6", "RB6", "river-bus"),
            LineRef("a1", "A1", "coach"),
        )
        assertEquals(emptyList<LineRef>(), Connections.of(lines, "rb2"))
    }

    @Test
    fun `rail is the same allowlist, known by line id when TfL gave no mode`() {
        assertEquals(true, Connections.isRail("tube", "northern"))
        assertEquals(true, Connections.isRail("", "victoria"))
        assertEquals(false, Connections.isRail("bus", "24"))
        assertEquals(false, Connections.isRail("coach", "a1"))
        assertEquals(false, Connections.isRail("river-bus", "rb1"))
        assertEquals(false, Connections.isRail("", "unknown-line"))
    }

    @Test
    fun `a rail line's mode is known by id when TfL gives none`() {
        assertEquals("tube", Connections.knownMode("northern"))
        assertEquals(null, Connections.knownMode("24"))
    }
}
