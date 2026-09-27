package app.stopdash.ui

import app.stopdash.domain.HiddenModes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hidden-modes banner's naming of what's hidden: groups, then one line by name or several by count. */
class HiddenGroupsLabelTest {
    private val northern = HiddenModes.lineKey("northern", "Northern line")
    private val central = HiddenModes.lineKey("central", "Central line")

    @Test
    fun `one hidden line is named, after any hidden groups`() {
        assertEquals("Northern line", hiddenGroupsLabel(setOf(northern)))
        assertEquals("Bus, Northern line", hiddenGroupsLabel(setOf(northern, "bus")))
    }

    @Test
    fun `several hidden lines are counted`() {
        assertEquals("2 lines", hiddenGroupsLabel(setOf(northern, central)))
        assertEquals("Tube & DLR, 2 lines", hiddenGroupsLabel(setOf("tube", "dlr", northern, central)))
    }
}
