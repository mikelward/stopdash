package app.stopdash

import app.stopdash.domain.LineRef
import app.stopdash.domain.RouteStop
import app.stopdash.ui.RouteGo
import app.stopdash.ui.RouteStopOpen
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

/** A tapped route stop's Go, kept across a rotation for the tap it came with, never another's. */
class RouteGoHolderTest {
    private val go = RouteGo(
        listOf(RouteStop("940GZZLUVIC", "Victoria"), RouteStop("940GZZLUWRR", "Warren Street")),
        emptyMap(),
        emptyMap(),
        Instant.parse("2026-10-09T08:00:00Z"),
    )
    private val tapped = RouteStopOpen(
        "940GZZLUWRR",
        "Warren Street",
        null,
        line = LineRef("victoria", "Victoria", "tube"),
        fromName = "Victoria",
        towards = "Walthamstow Central",
        go = go,
    )

    @Test
    fun `the tap brought back by a rotation gets its Go back`() {
        val holder = RouteGoHolder()
        holder.keep(tapped)
        // Restored by its saver, which drops Go.
        assertSame(go, holder.goFor(tapped.copy(go = null)))
    }

    @Test
    fun `another tap's details get none`() {
        val holder = RouteGoHolder()
        holder.keep(tapped)
        assertNull(holder.goFor(tapped.copy(go = null, stationId = "940GZZLUOXC", stopId = "940GZZLUOXC", name = "Oxford Circus")))
        assertNull(holder.goFor(tapped.copy(go = null, towards = "Brixton")))
    }

    @Test
    fun `nothing is kept before a tap, nor after one with no Go`() {
        val holder = RouteGoHolder()
        assertNull(holder.goFor(tapped.copy(go = null)))
        holder.keep(tapped)
        holder.keep(tapped.copy(go = null))
        assertNull(holder.goFor(tapped.copy(go = null)))
    }
}
