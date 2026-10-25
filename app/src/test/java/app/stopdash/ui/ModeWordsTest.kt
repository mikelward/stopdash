package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** A ride's notes and a route page's timetable line name its vehicles by its mode's own name (maintainer, 2026-10-25). */
class ModeWordsTest {
    @Test
    fun `each mode names its own vehicles, and a rail mode or one unknown its trains`() {
        assertEquals(ModeWords.BUS, ModeWords.of("bus"))
        assertEquals(ModeWords.BUS, ModeWords.of("replacement-bus"))
        assertEquals(ModeWords.COACH, ModeWords.of("coach"))
        assertEquals(ModeWords.TRAM, ModeWords.of("tram"))
        assertEquals(ModeWords.TRAM, ModeWords.of("Tram"))
        assertEquals(ModeWords.BOAT, ModeWords.of("river-bus"))
        assertEquals(ModeWords.BOAT, ModeWords.of("river-tour"))
        assertEquals(ModeWords.CABLE_CAR, ModeWords.of("cable-car"))
        for (rail in listOf("tube", "dlr", "overground", "elizabeth-line", "national-rail", "", "something-new")) {
            assertEquals(rail, ModeWords.TRAIN, ModeWords.of(rail))
        }
    }

    @Test
    fun `no two modes share a word`() {
        val words = ModeWords.entries
        for (pick in listOf<(ModeWords) -> Int>({ it.checking }, { it.none }, { it.notChecked }, { it.notCheckedNamed }, { it.nextOne }, { it.nextMany })) {
            assertEquals(words.size, words.map(pick).distinct().size)
        }
    }
}
