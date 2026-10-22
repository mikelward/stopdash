package app.stopdash.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.StopQualifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stop header's words come from the shared string resources and read as the English always has. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class HeaderWordsTest {
    private val words = headerWords(ApplicationProvider.getApplicationContext<Context>().resources)

    @Test
    fun `the resources' words head a stop as the English always has`() {
        val qualifiers = listOf(
            StopQualifier.Platform("2", "Eastbound"),
            StopQualifier.BusStop("e", "Farringdon Or Holborn Circus"),
            StopQualifier.Towards("Archway"),
            StopQualifier.Compass("Westbound"),
        ) + listOf("N", "E", "S", "W", "NE", "NW", "SE", "SW").map { StopQualifier.BusBearing(it) }
        for (qualifier in qualifiers) {
            assertEquals(groupHeaderLabel(qualifier), groupHeaderLabel(qualifier, words))
            assertEquals(groupHeaderSpoken(qualifier), groupHeaderSpoken(qualifier, words))
        }
        assertEquals("Platform 2", groupHeaderLabel(StopQualifier.Platform("2", null), words))
        assertEquals(listOf("Platform 2", "Plat 2"), groupHeaderForms(StopQualifier.Platform("2", null), words))
        assertEquals("Stop E, towards Farringdon", groupHeaderSpoken(StopQualifier.BusStop("e", "Farringdon"), words))
        assertEquals("Southwest-bound", bearingDirection("sw", words))
        assertNull(bearingDirection("X", words))
    }
}
