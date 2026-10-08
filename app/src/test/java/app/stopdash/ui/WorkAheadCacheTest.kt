package app.stopdash.ui

import app.stopdash.data.WorkAhead
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.LineStatus
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.TripLeg
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkAheadCacheTest {
    private val weekend = PlannedAlert("Part Closure", "Saturday 10 and Sunday 11 October, no service between Kennington and Morden.", LocalDate.of(2026, 10, 10))
    private val later = PlannedAlert("Part Closure", "Saturday 17 October, no service between Camden Town and Edgware.", LocalDate.of(2026, 10, 17))

    private var now = 1_000L
    private val asked = mutableListOf<String>()
    private var answer: () -> WorkAhead = { WorkAhead(listOf(weekend), null) }
    private val cache = WorkAheadCache({ asked += it; answer() }, Dispatchers.Unconfined, { now }, Duration.ofHours(3))

    @Test
    fun an_answer_holds_for_its_reuse_then_is_asked_again() = runTest {
        assertNull(cache.fresh("northern"))
        assertEquals(listOf(weekend), cache.ask("northern"))
        assertEquals(listOf(weekend), cache.fresh("northern"))
        assertEquals(Duration.ofHours(3).toMillis(), cache.holdsForMillis("northern"))
        now += Duration.ofHours(3).toMillis()
        assertNull(cache.fresh("northern"))
        assertNull(cache.holdsForMillis("northern"))
    }

    @Test
    fun an_answer_holds_no_longer_than_until_its_soonest_work_starts() = runTest {
        answer = { WorkAhead(listOf(weekend), Duration.ofMinutes(20)) }
        cache.ask("northern")
        now += Duration.ofMinutes(20).toMillis()
        // Under way then: the line's own status carries it, and the week is asked again.
        assertNull(cache.fresh("northern"))
    }

    @Test
    fun a_lapsed_answer_is_dropped_as_it_is_asked_again() = runTest {
        cache.ask("northern")
        now += Duration.ofHours(4).toMillis()
        var heldWhileAsking: WorkAheadCache.Held? = WorkAheadCache.Held(emptyList(), 0, Duration.ZERO)
        answer = { heldWhileAsking = cache.answer("northern").value.held; WorkAhead(listOf(later), null) }
        cache.ask("northern")
        // No page shows the lapsed week while the new one is asked.
        assertNull(heldWhileAsking)
        assertEquals(listOf(later), cache.fresh("northern"))
    }

    @Test
    fun a_page_never_reads_a_week_past_its_hold() = runTest {
        cache.ask("northern")
        val held = cache.answer("northern").value.held
        assertSame(held, cache.current(held))
        now += Duration.ofHours(3).toMillis()
        // Still held until asked again, but read as gone: a page reopened then shows none of it.
        assertSame(held, cache.answer("northern").value.held)
        assertNull(cache.current(held))
    }

    @Test
    fun a_failed_ask_is_said_until_one_goes_through() = runTest {
        val warned = mutableListOf<String>()
        val failing = WorkAheadCache({ throw IOException("offline") }, Dispatchers.Unconfined, { now }, warn = { warned += it })
        assertNull(failing.ask("northern"))
        assertTrue(failing.failed("northern"))
        assertTrue(failing.answer("northern").value.failed)
        assertEquals(listOf("work ahead failed for northern: IOException"), warned)
        cache.ask("northern")
        assertFalse(cache.failed("northern"))
    }

    @Test
    fun one_line_asked_leaves_the_others_be() = runTest {
        cache.ask("northern")
        assertNull(cache.fresh("victoria"))
        assertEquals(listOf("northern"), asked)
    }

    @Test
    fun pages_asking_at_once_share_one_request() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val shared = WorkAheadCache({ asked += it; gate.await(); WorkAhead(listOf(weekend), null) }, Dispatchers.Unconfined, { now })
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shared.ask("northern") }
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shared.ask("northern") }
        gate.complete(Unit)
        assertEquals(listOf(weekend), first.await())
        assertEquals(listOf(weekend), second.await())
        assertEquals(listOf("northern"), asked)
        // Held: a later ask is answered without a request, and a failure elsewhere can't mark it failed.
        assertEquals(listOf(weekend), shared.ask("northern"))
        assertEquals(listOf("northern"), asked)
    }

    @Test
    fun an_ask_waiting_on_one_whose_asker_left_asks_itself() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val shared = WorkAheadCache({ calls++; if (calls == 1) gate.await(); WorkAhead(listOf(later), null) }, Dispatchers.Unconfined, { now })
        val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shared.ask("northern") }
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shared.ask("northern") }
        first.cancel()
        assertEquals(listOf(later), second.await())
        assertEquals(2, calls)
    }

    @Test
    fun a_lines_page_holds_its_last_line_only_while_the_week_ahead_is_unchanged() {
        val status = good
        fun inputs(held: Any?, failed: Boolean = false, dismissed: Set<DismissedAlert> = noneDismissed) =
            Inputs(TripLine(leg, status), held, failed, dismissed, status, false, false, false)
        val held = WorkAheadCache.Held(listOf(weekend), 0, Duration.ofHours(3))
        assertTrue(sameWorkAhead(inputs(held), inputs(held)))
        // The week ran out and was dropped, it failed, or the rider dismissed something: worked out afresh.
        assertFalse(sameWorkAhead(inputs(held), inputs(null)))
        assertFalse(sameWorkAhead(inputs(held), inputs(held, failed = true)))
        assertFalse(sameWorkAhead(inputs(held), inputs(held, dismissed = setOf(DismissedAlert.ofPlanned("northern", weekend)))))
    }

    private val noneDismissed = emptySet<DismissedAlert>()

    // A line's week ahead added to its lines page's line (TripLine.withWorkAhead).

    private val leg = TripLeg(mode = "tube", lineId = "northern", lineName = "Northern", fromId = "", fromName = "", toId = "", toName = "", departure = Instant.EPOCH, arrival = Instant.EPOCH)
    private val good = LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service")

    @Test
    fun the_week_ahead_is_added_to_the_lines_work_to_come_soonest_first() {
        val line = TripLine(leg, good.copy(planned = listOf(later)))
        val shown = line.withWorkAhead(listOf(later, weekend), failed = false, dismissed = emptySet())
        assertEquals(listOf(weekend, later), shown.planned)
        assertFalse(shown.aheadUnknown)
    }

    @Test
    fun an_alert_dismissed_stays_off_whichever_answer_carries_it() {
        // Dismissed as the status gave it; the week ahead dates it from its period, so its identity differs.
        val dismissed = setOf(DismissedAlert.ofPlanned("northern", weekend))
        val status = good.copy(planned = listOf(weekend))
        val line = TripLine(leg, status, planned = emptyList())
        val ahead = weekend.copy(startsOn = LocalDate.of(2026, 10, 9))
        assertSame(line, line.withWorkAhead(listOf(ahead), failed = false, dismissed = dismissed))
        // One dismissed from the week ahead itself stays off too.
        val alone = TripLine(leg, good)
        assertEquals(emptyList<PlannedAlert>(), alone.withWorkAhead(listOf(weekend), false, dismissed).planned)
    }

    @Test
    fun work_under_way_is_not_listed_as_to_come() {
        val now = good.copy(severity = 20, description = "Part Closure", fullText = weekend.fullText)
        val line = TripLine(leg, now)
        assertEquals(emptyList<PlannedAlert>(), line.withWorkAhead(listOf(weekend), false, emptySet()).planned)
    }

    @Test
    fun a_week_not_asked_is_said_and_one_with_no_status_adds_nothing() {
        val line = TripLine(leg, good)
        assertTrue(line.withWorkAhead(null, failed = true, dismissed = emptySet()).aheadUnknown)
        val checking = TripLine(leg, status = null, checking = true)
        assertEquals(emptyList<PlannedAlert>(), checking.withWorkAhead(listOf(weekend), false, emptySet()).planned)
    }
}
