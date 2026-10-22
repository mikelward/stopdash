package app.stopdash.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.HiddenModes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A line named alone ("Northern line") from the app's own strings, and a saved one read back through them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class LineLabelsTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val lines = listOf("Northern" to "tube", "Mildmay" to "overground", "Elizabeth line" to "elizabeth-line", "134" to "bus", "Thameslink" to "national-rail")

    @Test
    fun `a line reads from the app's strings as it always read`() {
        assertEquals(
            listOf("Northern line", "Mildmay line", "Elizabeth", "134", "Thameslink"),
            lines.map { (name, mode) -> lineLabel(name, mode, resources) },
        )
        // What the domain has always said, so nothing reads differently in English.
        assertEquals(lines.map { (name, mode) -> app.stopdash.domain.lineLabel(name, mode) }, lines.map { (name, mode) -> lineLabel(name, mode, resources) })
    }

    @Test
    fun `a hidden or avoided line saved in English is named through the app's strings`() {
        val saved = lines.map { (name, mode) -> HiddenModes.lineKey(name.lowercase(), app.stopdash.domain.lineLabel(name, mode)) }
        val named = lines.map { (name, mode) -> lineLabel(name, mode, resources) }
        assertEquals(named, saved.map { app.stopdash.domain.savedLineLabel(it.substringAfter('='), lineWord(resources)) })
        // The banner, Settings' lists and the trip's chips, each from the same entries.
        assertEquals(named, HiddenModes.hiddenLineLabels(saved.toCollection(LinkedHashSet()), lineWord(resources)))
        assertEquals(named, AvoidedLines.labeled(saved.toCollection(LinkedHashSet()), lineWord(resources)).map { it.second })
        assertEquals("Northern line", hiddenGroupsLabel(setOf(saved.first()), resources))
    }
}
