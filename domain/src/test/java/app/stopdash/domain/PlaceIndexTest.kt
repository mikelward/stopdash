package app.stopdash.domain

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PlaceIndexTest {
    // Synthetic names and positions near central London only (AGENTS.md *Privacy*).
    private fun index(vararg lines: String, header: String = "# stopdash place index v1\ttest"): PlaceIndex =
        PlaceIndex.read(BufferedReader(StringReader((listOf(header) + lines).joinToString("\n"))))

    private fun line(kind: String, name: String, rank: Int = 5, category: String = "") =
        "$kind\t$name\t$category\t51.50000\t-0.12000\t$rank"

    private fun PlaceIndex.names(query: String, limit: Int = PlaceIndex.DEFAULT_LIMIT) = search(query, limit).map { it.name }

    @Test
    fun `reads entries with their kind and position`() {
        val places = index(line("P", "Alpha Gallery", 9, "museum"), line("S", "Alpha Road"), line("C", "XX1 1XX"))
        assertEquals(3, places.size)
        val found = places.search("alpha gallery").single()
        assertEquals(PlaceIndex.Entry("Alpha Gallery", PlaceIndex.Kind.PLACE, Coordinates(51.5, -0.12)), found)
        assertEquals(PlaceIndex.Kind.STREET, places.search("alpha road").single().kind)
        assertEquals(PlaceIndex.Kind.POSTCODE, places.search("xx11xx").single().kind)
    }

    @Test
    fun `a checkpoint that throws stops a scan partway, and one that doesn't is called as it goes`() {
        val places = index(*Array(PlaceIndex.CHECKPOINT_EVERY * 3) { line("P", "Zeta Hall $it") })
        var calls = 0
        assertEquals(PlaceIndex.DEFAULT_LIMIT, places.search("zeta", checkpoint = { calls++ }).size)
        assertEquals(3, calls)
        var stopped = 0
        val thrown = runCatching {
            places.search("zeta", checkpoint = { if (++stopped == 2) throw IllegalStateException("superseded") })
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(2, stopped)
    }

    @Test
    fun `a prefix beats a substring, which beats letters in order`() {
        val places = index(line("P", "Lower Zeta Hall"), line("P", "Zigzag Eta Hall"), line("P", "Zeta Hall"))
        assertEquals(listOf("Zeta Hall", "Lower Zeta Hall", "Zigzag Eta Hall"), places.names("zeta"))
    }

    @Test
    fun `a leading the is passed over for a prefix`() {
        val places = index(line("P", "Gallery Alpha Hall", 9), line("P", "The Alpha Gallery", 5))
        // "The Alpha Gallery" is a prefix match once "the" is passed over, so it leads a higher-ranked substring match.
        assertEquals(listOf("The Alpha Gallery", "Gallery Alpha Hall"), places.names("alpha"))
    }

    @Test
    fun `equal matches go to the higher rank, then the shorter name`() {
        val places = index(line("P", "Zeta Long Name", 9), line("P", "Zeta Short", 9), line("P", "Zeta", 2))
        assertEquals(listOf("Zeta Short", "Zeta Long Name", "Zeta"), places.names("zeta"))
    }

    @Test
    fun `letters in order may start after a leading the`() {
        val places = index(line("P", "The Zeta Gallery"))
        assertEquals(listOf("The Zeta Gallery"), places.names("ztglry"))
    }

    @Test
    fun `letters in order must start at the name's first letter`() {
        val places = index(line("P", "Victoria Gardens"), line("P", "Avictoria"))
        assertEquals(listOf("Victoria Gardens", "Avictoria"), places.names("victoria"))
        assertEquals(listOf("Victoria Gardens"), places.names("vctra"))
    }

    @Test
    fun `case accents spaces and punctuation are ignored`() {
        val places = index(line("P", "Café Éclair & Co."), line("P", "𝑮𝒀𝑴 Hall"))
        assertEquals(listOf("Café Éclair & Co."), places.names("CAFE ECL"))
        assertEquals(listOf("𝑮𝒀𝑴 Hall"), places.names("gym hall"))
        val postcodes = index(line("C", "XX1 1XX"))
        assertEquals(listOf("XX1 1XX"), postcodes.names("xx1 1x"))
    }

    @Test
    fun `a two-letter query finds prefixes only, and one letter finds nothing`() {
        val places = index(line("P", "Zeta Hall"), line("P", "Alpha Zeta"))
        assertEquals(listOf("Zeta Hall"), places.names("ze"))
        assertEquals(emptyList<String>(), places.names("z"))
    }

    @Test
    fun `keeps only the best few`() {
        val places = index(*Array(20) { line("P", "Zeta $it", it % 10) })
        // Ranks 9 first (the shorter name ahead), then the best of rank 8.
        assertEquals(listOf("Zeta 9", "Zeta 19", "Zeta 8"), places.names("zeta", limit = 3))
    }

    @Test
    fun `an unreadable line is left out and counted`() {
        val places = index(line("P", "Zeta Hall"), "P\tBroken", "X\tUnknown Kind\t\t51.5\t-0.12\t5", line("P", "!!!"), line("P", "Zeta Rank", 12))
        assertEquals(1, places.size)
        assertEquals(4, places.skippedLines)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `another format version is refused`() {
        index(line("P", "Zeta Hall"), header = "# stopdash place index v2\ttest")
    }

    @Test
    fun `an empty file reads as an empty index`() {
        assertEquals(0, PlaceIndex.read(BufferedReader(StringReader(""))).size)
    }

    // The bundled index itself: it reads whole and finds the obvious answer first. Skipped where the
    // asset isn't beside this module (it is in the repository).
    @Test
    fun `the bundled index reads and finds a landmark first`() {
        val file = File("../app/src/main/assets/places/place_index.tsv")
        assumeTrue(file.exists())
        val started = System.nanoTime()
        val places = file.bufferedReader().use { PlaceIndex.read(it) }
        val read = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, places.skippedLines)
        assertTrue("only ${places.size} entries", places.size > 400_000)
        val searched = System.nanoTime()
        assertEquals("Tate Modern", places.search("tate modern").first().name)
        assertEquals("The British Museum", places.search("british museum").first().name)
        assertEquals(PlaceIndex.Kind.POSTCODE, places.search("sw1a 1aa").first().kind)
        assertTrue(places.search("brtmus").any { it.name == "The British Museum" })
        val search = (System.nanoTime() - searched) / 1_000_000 / 3
        println("place index: ${places.size} entries read in $read ms, $search ms a search")
    }
}
