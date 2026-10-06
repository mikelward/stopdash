package app.stopdash.wear

import app.stopdash.data.WatchTrip
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the watch shows a trip the phone sent, as it ages. */
class WatchTripStateTest {
    private val now = Instant.parse("2026-10-03T08:00:00Z")
    private fun sentAgo(seconds: Long) = WatchTrip(
        title = "Board Victoria",
        steps = listOf(WatchTrip.Step("King's Cross St. Pancras → Victoria", "victoria", "Victoria", "tube")),
        current = 0,
        departures = listOf(
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", now.plusSeconds(120).toEpochMilli()),
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", now.plusSeconds(300).toEpochMilli()),
            WatchTrip.Train("victoria", "Victoria", "tube", "Brixton", now.minusSeconds(60).toEpochMilli()),
        ),
        departuresAt = 0,
        sentAt = now.minusSeconds(seconds).toEpochMilli(),
    )

    // The trip as the watch holds it, having arrived [arrivedAgo] seconds before now.
    private val elapsedNow = 10_000_000L
    private fun held(seconds: Long, arrivedAgo: Long = maxOf(seconds, 0)) = HeldTrip(sentAgo(seconds), elapsedNow - arrivedAgo * 1000)

    @Test
    fun `a trip just sent shows as current`() {
        assertFalse(WatchTripState.shown(held(10), now, elapsedNow)!!.stale)
    }

    @Test
    fun `one the phone stopped updating reads out of date, then goes`() {
        assertTrue(WatchTripState.shown(held(3 * 60), now, elapsedNow)!!.stale)
        assertNull(WatchTripState.shown(held(16 * 60), now, elapsedNow))
    }

    @Test
    fun `one stamped well ahead of the watch's clock isn't trusted as current`() {
        assertTrue(WatchTripState.shown(held(-5 * 60), now, elapsedNow)!!.stale)
        assertFalse(WatchTripState.shown(held(-20), now, elapsedNow)!!.stale)
    }

    @Test
    fun `one stamped ahead still goes once the phone stops updating it`() {
        assertTrue(WatchTripState.shown(held(-5 * 60, arrivedAgo = 10 * 60), now, elapsedNow)!!.stale)
        assertNull(WatchTripState.shown(held(-5 * 60, arrivedAgo = 16 * 60), now, elapsedNow))
    }

    @Test
    fun `one held without an update goes, whatever the wall clock says`() {
        // Stamped just now by a clock since set back, but held for sixteen minutes untouched.
        assertTrue(WatchTripState.shown(held(10, arrivedAgo = 3 * 60), now, elapsedNow)!!.stale)
        assertNull(WatchTripState.shown(held(10, arrivedAgo = 16 * 60), now, elapsedNow))
    }

    @Test
    fun `one read back after a restart is dated by its stamp, and stays gone once gone`() {
        assertNull(WatchTripState.lookedUpHeld(sentAgo(16 * 60), now, elapsedNow))
        val back = WatchTripState.lookedUpHeld(sentAgo(3 * 60), now, elapsedNow)!!
        assertEquals(elapsedNow - 3 * 60 * 1000, back.arrivedElapsed)
        assertTrue(WatchTripState.shown(back, now, elapsedNow)!!.stale)
        // Stamped ahead of the watch's clock: held from now, and still goes fifteen minutes on.
        assertEquals(elapsedNow, WatchTripState.lookedUpHeld(sentAgo(-5 * 60), now, elapsedNow)!!.arrivedElapsed)
    }

    @Test
    fun `reopening the app keeps a trip's time held, even one stamped ahead`() {
        // Held ten minutes, stamped five minutes ahead of the watch: read back again, it isn't held anew.
        val ahead = sentAgo(-5 * 60)
        val held = HeldTrip(ahead, elapsedNow - 10 * 60 * 1000)
        assertEquals(held, WatchTripState.lookedUpHeld(ahead, now, elapsedNow, held))
        assertNull(WatchTripState.shown(WatchTripState.lookedUpHeld(ahead, now, elapsedNow + 6 * 60 * 1000, held), now, elapsedNow + 6 * 60 * 1000))
        // Another trip read back is dated afresh.
        val other = sentAgo(60)
        assertEquals(elapsedNow - 60 * 1000, WatchTripState.lookedUpHeld(other, now, elapsedNow, held)!!.arrivedElapsed)
    }

    @Test
    fun `a lookup read before an update doesn't undo it`() {
        val sent = held(10)
        var since = WatchTripState.events()
        WatchTripState.take(sent)
        // The lookup found no item before the trip arrived: the trip stays.
        assertFalse(WatchTripState.take(null, ifNoneSince = since))
        assertEquals(sent, WatchTripState.trip.value)
        since = WatchTripState.events()
        WatchTripState.clear()
        // The lookup read the trip before the phone took it off: it stays off.
        assertFalse(WatchTripState.take(sent, ifNoneSince = since))
        assertNull(WatchTripState.trip.value)
        // Nothing landed while it read: the lookup's answer stands.
        assertTrue(WatchTripState.take(sent, ifNoneSince = WatchTripState.events()))
        assertEquals(sent, WatchTripState.trip.value)
        WatchTripState.clear()
    }

    @Test
    fun `a failed lookup is tried again until one reads`() = runBlocking {
        var tries = 0
        WatchTripState.retrying(List(3) { kotlin.time.Duration.ZERO }) { ++tries == 3 }
        assertEquals(3, tries)
        tries = 0
        WatchTripState.retrying(List(2) { kotlin.time.Duration.ZERO }) { tries++; false }
        assertEquals(3, tries)
    }

    @Test
    fun `no trip, nothing shown`() {
        assertNull(WatchTripState.shown(null, now, elapsedNow))
    }

    @Test
    fun `the next ride's trains are one row per line and destination, gone trains left out`() {
        val rows = trainRows(sentAgo(10), now, stale = false)
        assertEquals(1, rows.size)
        assertEquals("Brixton", rows.single().row.label)
        assertEquals("2 · 5 min", rows.single().row.countdown)
        assertEquals("", rows.single().stop)
        assertFalse(rows.single().row.muted)
    }

    @Test
    fun `out of date, the trains read as the soonest's time, marked as a guess`() {
        val countdown = trainRows(sentAgo(10), now, stale = true).single().row.countdown
        assertTrue(countdown, Regex("\\d\\d:\\d\\d\\?").matches(countdown))
    }

    @Test
    fun `an old board's trains from the phone read as marked guesses, though the trip is current`() {
        // The phone holds the board as too old to stand behind, and sends its trains apart (D4).
        val fresh = sentAgo(10)
        val row = trainRows(fresh.copy(departures = emptyList(), oldDepartures = fresh.departures), now, stale = false).single().row
        assertTrue(row.countdown, Regex("\\d\\d:\\d\\d\\?").matches(row.countdown))
        assertTrue(row.stale)
    }

    @Test
    fun `a missed train gets its own muted row, and each pole its own rows`() {
        val trip = sentAgo(10).copy(
            departures = listOf(
                WatchTrip.Train("73", "73", "bus", "Oxford Circus", now.plusSeconds(60).toEpochMilli(), stop = "Stop A", missed = true),
                WatchTrip.Train("73", "73", "bus", "Oxford Circus", now.plusSeconds(240).toEpochMilli(), stop = "Stop A"),
                WatchTrip.Train("38", "38", "bus", "Liverpool Street", now.plusSeconds(180).toEpochMilli(), stop = "Stop B"),
                WatchTrip.Train("73", "73", "bus", "Oxford Circus", now.plusSeconds(420).toEpochMilli(), stop = "Stop A"),
            ),
        )
        val rows = trainRows(trip, now, stale = false)
        assertEquals(listOf("Stop A", "Stop A", "Stop B"), rows.map { it.stop })
        assertEquals(listOf(true, false, false), rows.map { it.row.muted })
        assertEquals("1 min", rows[0].row.countdown)
        assertEquals("4 · 7 min", rows[1].row.countdown)
    }

    @Test
    fun `a trip item is read, decoded and compared off the caller's thread`() {
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }.asCoroutineDispatcher()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "worker") }.asCoroutineDispatcher()
        val item = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.google.android.gms.wearable.DataItem::class.java)) { _, _, _ -> null }
            as com.google.android.gms.wearable.DataItem
        try {
            var readOn = ""
            val held = HeldTrip(sentAgo(10), elapsedNow - 10_000)
            val bytes = kotlinx.coroutines.runBlocking { app.stopdash.data.WatchTrip.encode(held.trip) }
            val prepared = kotlinx.coroutines.runBlocking(caller) {
                WatchTripState.prepared(listOf(item), lookedUp = true, current = held, dispatcher = worker, elapsedNow = { elapsedNow }, clock = { now }, nodeOf = { "" }, read = { readOn = Thread.currentThread().name; bytes })
            }
            assertTrue(readOn.startsWith("worker"))
            // The same trip read back keeps the held one, compared on the worker with the rest.
            assertTrue(prepared!!.held === held)
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `a held trip is equal only to itself, so the screen compares it in constant time`() {
        val trip = sentAgo(10)
        assertFalse(HeldTrip(trip, elapsedNow) == HeldTrip(trip, elapsedNow))
        assertEquals(HeldTrip(trip, elapsedNow).stepsKey, HeldTrip(trip, elapsedNow + 1).stepsKey)
    }

    @Test
    fun `a removed item has what's left read back, and a failed read clears the trip`() = kotlinx.coroutines.runBlocking {
        WatchTripState.take(held(10))
        var looked = 0
        WatchTripState.removed { looked++; true }
        assertEquals(1, looked)
        // The read stood: what it found is what's held (here, left as it was).
        org.junit.Assert.assertNotNull(WatchTripState.trip.value)
        WatchTripState.removed { false }
        assertNull(WatchTripState.trip.value)
    }

    @Test
    fun `the latest trip received wins, from whichever phone, and a read-back keeps the held phone's`() {
        fun item() = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.google.android.gms.wearable.DataItem::class.java)) { _, _, _ -> null }
            as com.google.android.gms.wearable.DataItem
        val held = HeldTrip(sentAgo(30), elapsedNow, node = "phoneA")
        // Each item: its trip's bytes and the phone (node) it came from.
        fun prepare(vararg sent: Pair<app.stopdash.data.WatchTrip, String>, lookedUp: Boolean = false) = kotlinx.coroutines.runBlocking {
            val items = sent.map { item() to it }
            WatchTripState.prepared(
                items.map { it.first }, lookedUp, held, kotlinx.coroutines.Dispatchers.Unconfined,
                read = { i -> kotlinx.coroutines.runBlocking { app.stopdash.data.WatchTrip.encode(items.first { it.first === i }.second.first) } },
                nodeOf = { i -> items.first { it.first === i }.second.second },
                elapsedNow = { elapsedNow },
                clock = { now },
            )
        }
        // Sent, whatever its stamp says (another phone's clock can't be compared): the latest received wins.
        assertEquals("phoneB", prepare(sentAgo(300) to "phoneB")!!.held!!.node)
        assertEquals("phoneA", prepare(sentAgo(5) to "phoneB", sentAgo(300) to "phoneA")!!.held!!.node)
        // Read back with both phones' items there: the held phone's stands, whichever is stamped newer.
        assertEquals("phoneA", prepare(sentAgo(60) to "phoneA", sentAgo(5) to "phoneB", lookedUp = true)!!.held!!.node)
        // The held phone's gone: another's stands.
        assertEquals("phoneB", prepare(sentAgo(5) to "phoneB", lookedUp = true)!!.held!!.node)
    }
}
