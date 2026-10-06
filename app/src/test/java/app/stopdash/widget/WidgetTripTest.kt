package app.stopdash.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopdash.data.WatchTrip
import app.stopdash.domain.SteadyClock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trip on the way on the widget: the next step and the trains at the next change, out of date
 * and then gone as the watch's are. Public TfL interchanges only (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetTripTest {
    private val now = Instant.parse("2026-10-05T12:00:00Z")

    private fun trip(sentAt: Instant = now, departures: List<WatchTrip.Train> = trains()) = WatchTrip(
        title = "Board Victoria at Oxford Circus",
        detail = "3 min",
        steps = listOf(
            WatchTrip.Step("Walk to Oxford Circus", walk = true),
            WatchTrip.Step("Victoria to King's Cross St. Pancras", "victoria", "Victoria", "tube"),
        ),
        current = 1,
        departures = departures,
        departuresAt = 1,
        sentAt = sentAt.toEpochMilli(),
    )

    private fun trains() = listOf(
        WatchTrip.Train("victoria", "Victoria", "tube", "Walthamstow Central", now.plusSeconds(180).toEpochMilli()),
        WatchTrip.Train("victoria", "Victoria", "tube", "Walthamstow Central", now.plusSeconds(420).toEpochMilli()),
        WatchTrip.Train("victoria", "Victoria", "tube", "Seven Sisters", now.plusSeconds(300).toEpochMilli()),
        WatchTrip.Train("victoria", "Victoria", "tube", "Walthamstow Central", now.minusSeconds(30).toEpochMilli()),
    )

    @Test
    fun `a fresh trip shows its step and the trains at the next change, grouped and counting down`() {
        val model = widgetTripModel(trip(), now)!!
        assertEquals("Board Victoria at Oxford Circus", model.title)
        assertFalse(model.stale)
        // The departed one is left out; a destination's trains share a row, soonest row first.
        assertEquals(listOf("Walthamstow Central", "Seven Sisters"), model.trains.map { it.destination })
        assertEquals("3 · 7 min", model.trains.first().countdown)
        // Redrawn when it would go out of date, unless a train is due sooner.
        assertEquals(now.plus(WIDGET_TRIP_STALE_AFTER), model.redrawAt)
    }

    @Test
    fun `an out-of-date trip says so and guesses the trains' times`() {
        val sent = now.minus(Duration.ofMinutes(3))
        val model = widgetTripModel(trip(sentAt = sent), now)!!
        assertTrue(model.stale)
        assertTrue(model.trains.first().countdown.endsWith("?"))
    }

    @Test
    fun `an old board's trains on a current trip read as dimmed guesses`() {
        // The phone holds the board as too old to stand behind and sends its trains apart (D4).
        val model = widgetTripModel(trip(departures = emptyList()).copy(oldDepartures = trains(), departuresNote = "Checking…"), now)!!
        assertFalse(model.stale)
        assertEquals("Checking…", model.note)
        assertEquals(listOf("Walthamstow Central", "Seven Sisters"), model.trains.map { it.destination })
        assertTrue(model.trains.all { it.guess && it.countdown.endsWith("?") })
        // Redrawn by the time the soonest goes, as it drops off.
        assertFalse(model.redrawAt.isAfter(now.plusSeconds(180)))
    }

    @Test
    fun `a trip the phone stopped updating goes, and the departures come back`() {
        assertNull(widgetTripModel(trip(sentAt = now.minus(WIDGET_TRIP_GONE_AFTER)), now))
        assertNull(widgetTripModel(null, now))
    }

    @Test
    fun `a trip stamped ahead of a clock set back isn't shown as live`() {
        assertNull(widgetTripModel(trip(sentAt = now.plus(Duration.ofMinutes(30))), now))
        // A nudge within the skew is still shown.
        assertTrue(widgetTripModel(trip(sentAt = now.plusSeconds(20)), now) != null)
    }

    @Test
    fun `the trains come first, the step only where every train fits with it`() {
        val model = widgetTripModel(trip(), now)!!
        assertTrue(widgetTripLayout(model, width = 240f, height = 180f, fontScale = 1f).showStep)
        val short = widgetTripLayout(model, width = 240f, height = 110f, fontScale = 1f)
        assertFalse(short.showStep)
        assertTrue(short.rows.isNotEmpty())
    }

    @Test
    fun `a larger font fits fewer rows`() {
        val model = widgetTripModel(trip(), now)!!
        val normal = widgetTripLayout(model, width = 240f, height = 150f, fontScale = 1f)
        val large = widgetTripLayout(model, width = 240f, height = 150f, fontScale = 2f)
        assertTrue(large.rows.size < normal.rows.size || (normal.showStep && !large.showStep))
    }

    @Test
    fun `pole headers are counted, and each pole's first row carries one`() {
        val trains = listOf(
            WatchTrip.Train("victoria", "Victoria", "tube", "Walthamstow Central", now.plusSeconds(180).toEpochMilli(), stop = "Stop A"),
            WatchTrip.Train("victoria", "Victoria", "tube", "Seven Sisters", now.plusSeconds(300).toEpochMilli(), stop = "Stop B"),
        )
        val model = widgetTripModel(trip(departures = trains), now)!!
        val layout = widgetTripLayout(model, width = 240f, height = 400f, fontScale = 1f)
        assertEquals(listOf("Stop A", "Stop B"), layout.rows.map { it.header })
        // With room for both rows but not both headers, one row is left out rather than clipped.
        val line = widgetLineHeight(1f).toFloat()
        val tight = widgetTripLayout(model, width = 240f, height = 24f + 20f + 8f + 2 * line + 1f, fontScale = 1f)
        assertEquals(1, tight.rows.size)
    }

    @Test
    fun `a narrow widget at a large font stacks each row, and counts its extra line`() {
        val model = widgetTripModel(trip(), now)!!
        val wide = widgetTripLayout(model, width = 320f, height = 400f, fontScale = 1f)
        assertTrue(wide.rows.none { it.stacked })
        val narrow = widgetTripLayout(model, width = 180f, height = 400f, fontScale = 2f)
        assertTrue(narrow.rows.all { it.stacked })
    }

    @Test
    fun `a failed write leaves no temporary copy of the trip`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = context.noBackupFilesDir
        // The target a directory, so the rename fails after the copy is written.
        java.io.File(dir, "widget-trip.json").apply { delete(); mkdirs() }
        try {
            WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) {}
            assertFalse(java.io.File(dir, "widget-trip.json.tmp").exists())
        } finally {
            java.io.File(dir, "widget-trip.json").deleteRecursively()
        }
    }

    @Test
    fun `a trip file that won't delete is emptied instead`() {
        val dir = ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir
        val stuck = object : java.io.File(dir, "widget-trip.json.tmp") {
            override fun delete() = false
        }
        stuck.writeText("{\"trip\":1}")
        try {
            WidgetTripStore.scrub(stuck)
            assertEquals(0L, stuck.length())
        } finally {
            java.io.File(dir, "widget-trip.json.tmp").delete()
        }
    }

    @Test
    fun `where neither a train nor the step fits, it says so rather than show nothing`() {
        val model = widgetTripModel(trip(), now)!!
        // The minimum height at a larger font: a stacked row and the step both overflow.
        val small = widgetTripLayout(model, width = 180f, height = 110f, fontScale = 1.3f)
        assertTrue(small.rows.isEmpty())
        assertTrue(small.tooSmall)
        assertFalse(small.showStep)
        // With the board's status to say (kept trains after a failed refresh), that shows instead.
        val failed = widgetTripModel(trip().copy(departuresNote = "Couldn't refresh"), now)!!
        val status = widgetTripLayout(failed, width = 180f, height = 110f, fontScale = 1.3f)
        assertFalse(status.tooSmall)
        assertTrue(status.rows.isEmpty())
        // At the default font the same cell fits a row, as before.
        assertFalse(widgetTripLayout(model, width = 180f, height = 110f, fontScale = 1f).tooSmall)
    }

    @Test
    fun `a cleared trip isn't read back, and the next trip is`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) {}
        WidgetTripStore.show(context, null, Dispatchers.Unconfined) {}
        // Left on disk as if its delete failed: still not read back as a trip.
        java.io.File(context.noBackupFilesDir, "widget-trip.json").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(WidgetTripStore.load(context, Dispatchers.Unconfined))
        WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) {}
        assertEquals(trip(), WidgetTripStore.load(context, Dispatchers.Unconfined)?.trip)
        WidgetTripStore.show(context, null, Dispatchers.Unconfined) {}
    }

    @Test
    fun `with no trains yet, the step shows where it fits, else it says so`() {
        val model = widgetTripModel(trip(departures = emptyList()), now)!!
        assertTrue(widgetTripLayout(model, width = 240f, height = 180f, fontScale = 1f).showStep)
        val small = widgetTripLayout(model, width = 180f, height = 110f, fontScale = 1.5f)
        assertTrue(small.tooSmall)
        assertFalse(small.showStep)
        // With the board's status to say, that shows rather than "Too small".
        val loading = widgetTripModel(trip(departures = emptyList()).copy(departuresNote = "Loading…"), now)!!
        val status = widgetTripLayout(loading, width = 180f, height = 110f, fontScale = 1.5f)
        assertFalse(status.tooSmall)
        assertFalse(status.showStep)
        assertTrue(status.rows.isEmpty())
        // Out of date at twice the text size, the status line itself no longer fits: "Too small".
        val staleLoading = widgetTripModel(
            trip(sentAt = now.minusSeconds(180), departures = emptyList()).copy(departuresNote = "Loading…"),
            now,
        )!!
        assertTrue(widgetTripLayout(staleLoading, width = 180f, height = 110f, fontScale = 2f).tooSmall)
    }

    @Test
    fun `a trip another process kept is shown only where its age can be told`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.noBackupFilesDir, "widget-trip.json")
        fun boot(id: String?, offset: Duration) = object : SteadyClock.Source {
            override val frame = id?.let { SteadyClock.Frame(it, 0) }
            override fun offset() = offset
        }
        // As if an earlier process wrote what's on disk.
        fun fromEarlierProcess() = file.writeText(file.readText().replace(WidgetTripStore.PROCESS, "earlier"))
        try {
            // Written after the clock was set back 10 minutes, so its steady stamp is ahead of the wall.
            SteadyClock.source = boot("first", Duration.ofMinutes(10))
            WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) {}
            assertEquals(trip(), WidgetTripStore.load(context, Dispatchers.Unconfined)?.trip)
            fromEarlierProcess()
            // The same boot: moved into this frame and shown.
            assertEquals(trip(), WidgetTripStore.load(context, Dispatchers.Unconfined)?.trip)
            // After a reboot: not shown.
            SteadyClock.source = boot("second", Duration.ZERO)
            assertNull(WidgetTripStore.load(context, Dispatchers.Unconfined))
            // Where the boot can't be told, neither (Codex on #600).
            SteadyClock.source = boot(null, Duration.ofMinutes(-10))
            WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) {}
            assertEquals(trip(), WidgetTripStore.load(context, Dispatchers.Unconfined)?.trip)
            fromEarlierProcess()
            assertNull(WidgetTripStore.load(context, Dispatchers.Unconfined))
        } finally {
            SteadyClock.source = null
            WidgetTripStore.show(context, null, Dispatchers.Unconfined) {}
        }
    }

    @Test
    fun `setting the clock back doesn't make an old trip fresh again`() {
        // Written at [now]; 11 minutes pass, then the clock is set back 10 minutes. By the wall clock
        // it's a minute old, but by the steady clock it's 11, so out of date.
        val setBack = Duration.ofMinutes(10)
        val wallNow = now.plus(Duration.ofMinutes(11)).minus(setBack)
        SteadyClock.source = object : SteadyClock.Source {
            override val frame: SteadyClock.Frame? = null
            override fun offset(): java.time.Duration = setBack
        }
        try {
            val model = runBlocking { widgetTripModelOn(KeptWidgetTrip(trip(), now), wallNow, Dispatchers.Unconfined) }!!
            assertTrue(model.stale)
        } finally {
            SteadyClock.source = null
        }
    }

    @Test
    fun `the model is worked out off the caller's thread`() = runBlocking {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "trip-worker") }
        try {
            var builtOn: String? = null
            val probe = object : kotlinx.coroutines.CoroutineDispatcher() {
                val inner = worker.asCoroutineDispatcher()
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) =
                    inner.dispatch(context, Runnable { builtOn = Thread.currentThread().name; block.run() })
            }
            assertTrue(widgetTripModelOn(KeptWidgetTrip(trip(), now), now, probe) != null)
            assertTrue(builtOn.orEmpty().startsWith("trip-worker"))
            assertFalse(Thread.currentThread().name.startsWith("trip-worker"))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun `the store keeps a trip, removes it, and redraws each time`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var redraws = 0
        WidgetTripStore.show(context, trip(), Dispatchers.Unconfined) { redraws++ }
        assertEquals(trip(), WidgetTripStore.load(context, Dispatchers.Unconfined)?.trip)
        WidgetTripStore.show(context, null, Dispatchers.Unconfined) { redraws++ }
        assertNull(WidgetTripStore.load(context, Dispatchers.Unconfined))
        assertEquals(2, redraws)
    }
}
