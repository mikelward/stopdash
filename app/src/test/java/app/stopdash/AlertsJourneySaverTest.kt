package app.stopdash

import androidx.compose.runtime.saveable.SaverScope
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.JourneyEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The open Alerts screen's journey comes back over a recreation, so the screen draws at once. Public stations only. */
class AlertsJourneySaverTest {
    private val scope = SaverScope { true }

    @Test
    fun `the journey comes back with everything the screen draws from`() {
        val journey = FavoriteJourney(JourneyEnd("940GZZLUEUS", "Euston", areaId = "940GZZLUEUS"), JourneyEnd("940GZZLUWLO", "Waterloo"), "northern", "Northern", "tube")
        val saved = with(AlertsJourneySaver) { scope.save(journey) }!!
        assertEquals(journey, AlertsJourneySaver.restore(saved))
    }

    @Test
    fun `none open stays none`() {
        // Nothing is saved, so the screen comes back closed.
        assertNull(with(AlertsJourneySaver) { scope.save(null) })
    }
}
