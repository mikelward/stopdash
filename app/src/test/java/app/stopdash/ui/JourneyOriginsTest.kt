package app.stopdash.ui

import app.stopdash.domain.LineRef
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyOriginsTest {
    @Test
    fun `an origin shared by two journeys keeps the interchange either knows`() {
        val unknown = StopRef("910GEXAMPLE", "Example", lines = listOf(LineRef("thameslink", "Thameslink", "national-rail")))
        val known = unknown.copy(lines = listOf(LineRef("great-northern", "Great Northern", "national-rail")), interchangeId = "HUBEXA")
        val merged = mergeJourneyOrigins(listOf(unknown, known)).single()
        assertEquals("HUBEXA", merged.interchangeId)
        assertEquals(listOf("thameslink", "great-northern"), merged.lines.map { it.id })
    }
}
