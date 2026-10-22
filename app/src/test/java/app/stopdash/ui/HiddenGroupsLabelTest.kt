package app.stopdash.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.ModeGroups
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The hidden-modes banner's naming of what's hidden: groups, then one line by name or several by count. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class HiddenGroupsLabelTest {
    private val northern = HiddenModes.lineKey("northern", "Northern line")
    private val central = HiddenModes.lineKey("central", "Central line")
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `one hidden line is named, after any hidden groups`() {
        assertEquals("Northern line", hiddenGroupsLabel(setOf(northern), resources))
        assertEquals("Bus, Northern line", hiddenGroupsLabel(setOf(northern, "bus"), resources))
    }

    @Test
    fun `several hidden lines are counted`() {
        assertEquals("2 lines", hiddenGroupsLabel(setOf(northern, central), resources))
        assertEquals("Underground, 2 lines", hiddenGroupsLabel(setOf("tube", "dlr", northern, central), resources))
    }

    @Test
    fun `the menu's groups are named, with what the name doesn't say under the ones that need it`() {
        val names = ModeGroups.ALL.map { groupName(it, resources) to groupSubtitle(it, resources) }
        assertEquals(
            listOf(
                "Underground" to "Tube, DLR, Elizabeth",
                "Overground" to null,
                "National Rail" to "Thameslink, Southern, …",
                "Bus" to null,
                "Tram" to null,
                "Boat" to null,
            ),
            names,
        )
    }
}
