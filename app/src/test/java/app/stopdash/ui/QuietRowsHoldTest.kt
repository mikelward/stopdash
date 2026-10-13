package app.stopdash.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A refresh never pulls a line's "?" row out from under the rider ([withQuietRows], principle 4):
 * while a new snapshot's "?" rows are worked out the answer is "not yet", and the list's own pass
 * works them out meanwhile ([QuietPending]), rather than drawing the new list without them, which
 * pulled the rows below up into its place and back on every refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class QuietRowsHoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val fetched = Instant.parse("2026-10-06T11:01:00Z")
    private val stopId = "940GZZLUKSX"
    private val distances = mapOf(stopId to 100.0)

    private fun inputs(at: Instant) = QuietInputs(
        stops = listOf(
            StopArrivals(
                stopId, "King's Cross St. Pancras", departures = emptyList(), fetchedAt = at,
                lines = listOf(LineRef("northern", "Northern", "tube")),
            ),
        ),
        lineStatuses = emptyMap(),
        determinedLineIds = setOf("northern"),
        now = fetched,
        stopDistanceMeters = distances,
        hiddenModes = emptySet(),
    )

    @Test
    fun a_new_snapshot_reads_not_yet_until_its_quiet_rows_are_in_never_the_list_without_them() {
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        val quiet = "quiet:$stopId|northern"
        val marks = MutableStateFlow(mapOf(quiet to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, fetched)))
        val state = EmptyTimesState(marks, fetched, worker) { _, _ -> }
        var revision by mutableStateOf(RowsRevision(emptyList(), fetched))
        var quietInputs by mutableStateOf(inputs(fetched))
        var rows: List<DepartureRow>? = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalEmptyTimes provides state) { rows = withQuietRows(revision, quietInputs) }
        }
        composeRule.waitForIdle()
        assertNull("not yet worked out", rows)
        settle(scheduler)
        assertEquals(listOf("northern"), rows?.map { it.lineId })

        // A refresh: a new snapshot, new candidates. "Not yet" until they're in, never the bare list.
        revision = RowsRevision(emptyList(), fetched.plusSeconds(30))
        quietInputs = inputs(fetched.plusSeconds(30))
        composeRule.waitForIdle()
        assertNull("the refresh's quiet rows are still being worked out", rows)
        settle(scheduler)
        assertEquals(listOf("northern"), rows?.map { it.lineId })

        // A new list from the same data (a starred row, say): "not yet" too, until it's merged in.
        revision = RowsRevision(emptyList(), fetched.plusSeconds(30))
        composeRule.waitForIdle()
        assertNull(rows)
        settle(scheduler)
        assertEquals(listOf("northern"), rows?.map { it.lineId })
    }

    // Both passes on the worker: the candidates, then each list's merge, which starts once they're in.
    private fun settle(scheduler: TestCoroutineScheduler) {
        repeat(2) {
            scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun a_pending_list_works_out_the_same_quiet_rows_itself() {
        val quiet = "quiet:$stopId|northern"
        val marks = MutableStateFlow(mapOf(quiet to EmptyTimes.Marked(EmptyTimes.Mark.UNKNOWN, fetched)))
        val revision = RowsRevision(emptyList(), fetched)
        assertEquals(listOf("northern"), QuietPending(revision, inputs(fetched), marks).rows().map { it.lineId })
        // The marks as they stand when it runs: one its timetable now rules out adds nothing.
        marks.value = mapOf(quiet to EmptyTimes.Marked(EmptyTimes.Mark.NONE, fetched))
        assertEquals(emptyList<String>(), QuietPending(revision, inputs(fetched), marks).rows().map { it.lineId })
    }

    @Test
    fun no_inputs_are_the_rows_as_they_are_at_once() {
        val state = EmptyTimesState(MutableStateFlow(emptyMap()), fetched, StandardTestDispatcher()) { _, _ -> }
        val revision = RowsRevision(emptyList(), fetched)
        var rows: List<DepartureRow>? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalEmptyTimes provides state) { rows = withQuietRows(revision, null) }
        }
        composeRule.waitForIdle()
        assertSame(revision.rows, rows)
    }
}
