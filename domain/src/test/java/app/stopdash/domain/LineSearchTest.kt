package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LineSearchTest {
    private fun bus(n: String) = LineRef(n.lowercase(), n, "bus")
    private val victoria = LineRef("victoria", "Victoria", "tube")
    private val elizabeth = LineRef("elizabeth", "Elizabeth line", "elizabeth-line")
    private val lines = listOf("29", "290", "299", "129", "209", "249", "N29", "2").map(::bus) + victoria + elizabeth

    private fun names(query: String) = LineSearch.search(query, lines).map { it.name }

    @Test
    fun a_number_finds_itself_first_then_longer_numbers_then_numbers_with_it_inside() {
        assertEquals(listOf("29", "290", "299", "129", "N29"), names("29"))
        assertEquals(listOf("299"), names("299"))
    }

    @Test
    fun a_number_is_never_matched_loosely() {
        // 209 and 249 hold a 2 and a 9 in order, which a fuzzy match would take.
        assertEquals(false, names("29").contains("209"))
        assertEquals(false, names("29").contains("249"))
    }

    @Test
    fun a_route_number_with_a_letter_is_never_matched_loosely_either() {
        val night = listOf("N25", "N205", "N250").map(::bus)
        // N205 holds N, 2 and 5 in order, which a fuzzy match would take.
        assertEquals(listOf("N25", "N250"), LineSearch.search("N25", night).map { it.name })
        assertEquals(listOf("N25", "N250"), LineSearch.search("n25", night).map { it.name })
    }

    @Test
    fun a_line_is_found_by_name_whatever_the_case() {
        assertEquals(listOf("Victoria"), names("vic"))
        assertEquals(listOf("Elizabeth line"), names("elizabeth"))
    }

    @Test
    fun a_blank_query_matches_nothing() {
        assertEquals(emptyList<String>(), names(" "))
    }

    @Test
    fun the_list_is_capped() {
        assertEquals(3, LineSearch.search("2", lines, limit = 3).size)
    }

    @Test
    fun a_reopened_line_moves_to_the_front_and_the_list_is_capped() {
        val a = bus("1")
        val b = bus("2")
        val c = bus("3")
        assertEquals(listOf(b, a), RecentLines.add(listOf(a), b))
        assertEquals(listOf(a, b), RecentLines.add(listOf(b, a), a))
        assertEquals(listOf(c, a), RecentLines.add(listOf(a, b), c, max = 2))
    }
}
