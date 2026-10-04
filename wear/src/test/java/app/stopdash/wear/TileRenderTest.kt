package app.stopdash.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.wear.tiles.timeline.TilesTimelineCache
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.toPersisted
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tile's timeline as the Tiles renderer reads it ([TilesTimelineCache], the renderer's own
 * lookup): some entry is shown at every instant, the open-ended one included. Synthetic stops only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class TileRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun envelope() = WatchEnvelope(
        stops = listOf(
            StopArrivals(
                stopId = "940GA",
                stopName = "Stop A",
                departures = listOf(
                    Departure("victoria", "Victoria", "inbound", "Brixton", null, fetched.plusSeconds(150), "tube"),
                ),
                fetchedAt = fetched,
                lines = listOf(LineRef("victoria", "Victoria", "tube")),
            ).toPersisted(),
        ),
    )

    /** The index in [entries] of the entry the renderer shows at [at], from their timeline. */
    private fun shownAt(entries: List<TileEntry>, at: Instant): Int {
        val shown = TilesTimelineCache(tileTimeline(context, entries)).findTileTimelineEntryForTime(at.toEpochMilli())
        assertNotNull("the renderer has an entry to show at $at", shown)
        val validity = shown!!.validity
        return entries.indexOfFirst { if (it.end == null) validity == null else validity?.startMillis == it.start.toEpochMilli() }
    }

    @Test
    fun `a setup frame shows from the moment it's rendered on`() {
        val entries = TileTimeline.entries(null, fetched)
        assertEquals(0, shownAt(entries, fetched))
        assertEquals(0, shownAt(entries, fetched.plusSeconds(86_400)))
    }

    @Test
    fun `an envelope already stale shows its stale frame`() {
        val entries = TileTimeline.entries(envelope(), fetched.plusSeconds(3_600))
        assertEquals(1, entries.size)
        assertEquals(0, shownAt(entries, fetched.plusSeconds(3_600)))
    }

    @Test
    fun `each entry shows in its own interval, and the open-ended one after the last`() {
        val entries = TileTimeline.entries(envelope(), fetched)
        for ((index, entry) in entries.withIndex()) {
            assertEquals(index, shownAt(entries, entry.start))
            entry.end?.let { assertEquals(index, shownAt(entries, it.minusMillis(1))) }
        }
        assertEquals(entries.lastIndex, shownAt(entries, entries.last().start.plusSeconds(86_400)))
    }
}
