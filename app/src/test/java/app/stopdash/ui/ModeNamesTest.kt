package app.stopdash.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A station's modes as its row says them, from the app's own strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class ModeNamesTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `every mode the app names reads from its strings as it always read`() {
        val modes = listOf("tube", "bus", "dlr", "overground", "elizabeth-line", "national-rail", "tram", "river-bus", "cable-car")
        assertEquals(
            listOf("Tube", "Bus", "DLR", "Overground", "Elizabeth", "National Rail", "Tram", "River Bus", "Cable car"),
            modes.map { modeName(it, resources) },
        )
        // The same names the domain has always said, so nothing reads differently in English.
        assertEquals(modes.map { app.stopdash.domain.modeName(it) }, modes.map { modeName(it, resources) })
        // TfL's ids in any case.
        assertEquals("National Rail", modeName("National-Rail", resources))
    }

    @Test
    fun `modes with no icon, which a search row names in words, read from the strings too`() {
        assertEquals(
            "Coach · International rail · Replacement bus",
            modesSpoken(listOf("coach", "international-rail", "replacement-bus"), resources),
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `modes with no icon are named in the app's language`() {
        val german = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("Fernbus · Internationaler Zug", modesSpoken(listOf("coach", "international-rail"), german))
    }

    @Test
    fun `a station's modes are said in TfL's order, each once, an unknown one by its tidied id`() {
        assertEquals("Tube · Elizabeth · National Rail", modesSpoken(listOf("tube", "elizabeth-line", "national-rail"), resources))
        assertEquals("Bus · Some mode", modesSpoken(listOf("bus", "", "some-mode", "bus"), resources))
        assertEquals("", modesSpoken(emptyList(), resources))
    }
}
