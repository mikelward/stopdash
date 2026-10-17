package app.stopdash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.IncidentDetail
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteDisruption
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the "route disruption" alert says. The alerts' words are made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteDisruptionAlertTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun diversion(leg: Int, words: String) = RouteDisruption.Signal.Line(
        leg, "99", "99", LineStatus("99", 6, "Diversion", words, soleAlert = true), RouteDisruption.Tier.MEDIUM,
    )

    @Test
    fun `each of a line's alerts is said with its own words`() {
        // Two diversions on one line, each heard once, so each is said (Codex on #519).
        val (title, body) = RouteDisruptionAlert.content(app, listOf(diversion(0, "Bus stop 'Alpha Road' will not be served."), diversion(0, "Bus stop 'Beta Road' will not be served.")))
        assertEquals("99: Diversion", title)
        assertEquals("Bus stop 'Alpha Road' will not be served.\n\n99: Diversion\nBus stop 'Beta Road' will not be served.", body)
    }

    @Test
    fun `the same alert on two legs is said once`() {
        val words = "Bus stop 'Alpha Road' will not be served."
        assertEquals("99: Diversion" to words, RouteDisruptionAlert.content(app, listOf(diversion(0, words), diversion(2, words))))
    }

    @Test
    fun `a branch taken by itself says where to change only where that has a name`() {
        val named = RouteDisruption.Signal.NoneDirect(0, "northern", "Northern", "Morden", "Kennington")
        assertEquals("None direct to Morden · Change at Kennington" to null, RouteDisruptionAlert.content(app, listOf(named)))
        // TfL's route names no stop where the branch turns off: no "Change at" with nothing after it (Codex, #633).
        assertEquals("None direct to Morden" to null, RouteDisruptionAlert.content(app, listOf(named.copy(forkName = ""))))
    }

    @Test
    fun `National Rail's words are said only if they're still current when the alert comes down`() {
        val now = Instant.now()
        val link = "https://www.nationalrail.co.uk/service-disruptions/example-20261018/"
        val words = "Disruption at Example\n\n$link"
        val line = RouteDisruption.Signal.Line(
            0, "c2c", "c2c",
            LineStatus("c2c", 6, "Special Service", link, soleAlert = true, incidentWords = mapOf(link to IncidentDetail(link, words, until = now.plusSeconds(300)))),
            RouteDisruption.Tier.MEDIUM,
        )
        assertEquals(words, RouteDisruptionAlert.content(app, listOf(line), through = now.plusSeconds(60)).second)
        assertEquals(link, RouteDisruptionAlert.content(app, listOf(line), through = now.plus(Duration.ofMinutes(10))).second)
    }
}
