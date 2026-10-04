package app.stopdash

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteDisruption
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
}
