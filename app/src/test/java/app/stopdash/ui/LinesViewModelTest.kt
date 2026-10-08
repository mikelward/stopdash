package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * *Lines…* (SPEC *Finding a line*): the recent lines before anything is typed, the search, which runs
 * on the compute dispatcher and never the main thread (AGENTS.md *Main thread*), a list TfL can't give,
 * and the opened line's status. Compute and I/O each run on a scheduler of their own the tests hold
 * back, so work the screen hands off is seen not to run on its (the main) thread. Public lines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinesViewModelTest {
    private val mainScheduler = TestCoroutineScheduler()
    private val main = StandardTestDispatcher(mainScheduler)
    private val computeScheduler = TestCoroutineScheduler()
    private val compute = StandardTestDispatcher(computeScheduler)
    private val ioScheduler = TestCoroutineScheduler()
    private val io = StandardTestDispatcher(ioScheduler)

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    // What's due now, never the delayed (an answer's expiry, [LinesViewModel.check]): tests move time on themselves.
    private fun mainIdle() = mainScheduler.runCurrent()

    // The main thread and I/O, each handing the other what it starts, until both are idle; compute
    // stays held for [releaseCompute].
    private fun idle() = repeat(4) {
        mainIdle()
        ioScheduler.runCurrent()
    }

    // Main and I/O first, to hand compute what they start; then compute, then what follows.
    private fun releaseCompute() {
        idle()
        computeScheduler.runCurrent()
        idle()
    }

    private val victoria = LineRef("victoria", "Victoria", "tube")
    private val n299 = LineRef("299", "299", "bus")
    private val n29 = LineRef("29", "29", "bus")
    private var recent = listOf<LineRef>(victoria)
    private var lines: () -> List<LineRef> = { listOf(victoria, n29, n299) }
    private var asked = 0
    private var listed = 0
    private var refreshes = 0
    private var renewed: () -> List<LineRef>? = { null }
    private var renewalGate: CompletableDeferred<Unit>? = null
    private var clock = 0L
    private var status: () -> LineStatus? = { LineStatus(lineId = "299", severity = LineStatus.GOOD_SERVICE, description = "Good Service") }

    // The page as it comes up: the line remembered, its status asked.
    private fun LinesViewModel.page(line: LineRef) {
        open(line)
        check(line)
    }

    private fun vm(store: DismissedAlertsStore = DismissedAlertsStore.NONE, saved: SavedStateHandle = SavedStateHandle()) = LinesViewModel(
        loadLines = { listed++; lines() },
        refreshLines = { refreshes++; renewalGate?.await(); renewed() },
        loadRecent = { recent },
        recordOpen = { line -> listOf(line) + recent.filter { it.id != line.id }.also { recent = it } },
        lineStatus = { asked++; status() },
        io = io,
        compute = compute,
        elapsedMillis = { clock },
        reuse = java.time.Duration.ofSeconds(90),
        dismissedStore = store,
        saved = saved,
        loadIndex = { indexLoads++; index() },
        workAhead = { aheadAsked++; app.stopdash.data.WorkAhead(ahead(), aheadStartsIn) },
        aheadReuse = java.time.Duration.ofHours(3),
    )

    private var aheadAsked = 0
    private var aheadStartsIn: java.time.Duration? = null
    private val weekend = app.stopdash.domain.PlannedAlert(
        "Part Closure", "Saturday 10 and Sunday 11 October, no service between Edgware Road and Aldgate.", java.time.LocalDate.of(2026, 10, 10),
    )
    private var ahead: () -> List<app.stopdash.domain.PlannedAlert> = { emptyList() }

    private var indexLoads = 0
    private var index: () -> app.stopdash.domain.StationIndex = {
        app.stopdash.domain.StationIndex(
            listOf(
                app.stopdash.domain.IndexedStation(
                    "940GZZLUOXC", "Oxford Circus Underground Station", listOf("tube"), "", 51.51522, -0.1419,
                    mapOf("tube" to listOf("bakerloo", "central", "victoria")),
                ),
            ),
            lineNames = mapOf("victoria" to "Victoria"),
        )
    }

    @Test
    fun a_stop_s_links_are_worked_out_off_the_main_thread_once() {
        val model = vm()
        idle()
        model.stopLinks("940GZZLUOXC")
        idle()
        // The index read on I/O; the links held on compute, so none are up yet.
        assertEquals(1, indexLoads)
        assertNull(model.links.value)
        releaseCompute()
        val links = model.links.value!!
        assertEquals("940GZZLUOXC", links.stopId)
        assertEquals(listOf("bakerloo", "central", "victoria"), links.links.lines.map { it.id })
        // The same stop again: left be.
        model.stopLinks("940GZZLUOXC")
        releaseCompute()
        assertEquals(1, indexLoads)
    }

    @Test
    fun reopening_a_stop_with_links_up_drops_a_lookup_for_another() {
        // Links for B up, a lookup for A under way, B opened again: A's mustn't land over B's (Codex on #664).
        val model = vm()
        idle()
        model.stopLinks("940GZZLUOXC")
        releaseCompute()
        model.stopLinks("940GZZLUKSX")
        idle()
        model.stopLinks("940GZZLUOXC")
        releaseCompute()
        assertEquals("940GZZLUOXC", model.links.value!!.stopId)
    }

    @Test
    fun an_index_that_cannot_be_read_links_nowhere() {
        index = { throw IOException("unreadable") }
        val model = vm()
        idle()
        model.stopLinks("940GZZLUOXC")
        releaseCompute()
        assertTrue(model.links.value!!.links.isEmpty)
    }

    @Test
    fun the_recent_lines_are_read_before_anything_is_typed() {
        val model = vm()
        assertNull(model.state.value.recent)
        idle()
        assertEquals(listOf(victoria), model.state.value.recent)
    }

    @Test
    fun the_search_runs_off_the_main_thread() {
        val model = vm()
        idle()
        model.setQuery("29")
        idle()
        // Held on compute: no matches yet, and the screen says a search is under way.
        assertTrue(model.state.value.searching)
        assertEquals(emptyList<LineRef>(), model.state.value.matches)
        releaseCompute()
        assertEquals(listOf(n29, n299), model.state.value.matches)
        assertFalse(model.state.value.searching)
    }

    @Test
    fun the_last_matches_stand_while_the_next_query_is_searched() {
        val model = vm()
        idle()
        model.setQuery("29")
        releaseCompute()
        model.setQuery("299")
        idle()
        assertEquals(listOf(n29, n299), model.state.value.matches)
        releaseCompute()
        assertEquals(listOf(n299), model.state.value.matches)
    }

    @Test
    fun a_list_that_cannot_be_loaded_says_so_and_retries() {
        lines = { throw IOException("offline") }
        val model = vm()
        idle()
        assertTrue(model.state.value.catalog is LinesViewModel.Catalog.Failed)
        lines = { listOf(n299) }
        model.setQuery("299")
        model.retry()
        idle()
        releaseCompute()
        assertEquals(listOf(n299), model.state.value.matches)
    }

    @Test
    fun an_opened_line_is_remembered_and_its_status_asked_once() {
        val model = vm()
        idle()
        model.page(n299)
        idle()
        assertEquals(listOf(n299, victoria), model.state.value.recent)
        val check = model.check.value!!
        assertEquals("299", check.lineId)
        assertFalse(check.checking)
        assertFalse(check.unknown)
        // Back on the page: nothing asked again.
        model.page(n299)
        idle()
        assertEquals(1, asked)
    }

    @Test
    fun a_status_that_cannot_be_asked_reads_as_unknown_and_is_asked_again_next_time() {
        status = { throw IOException("offline") }
        val model = vm()
        idle()
        model.page(n299)
        idle()
        assertTrue(model.check.value!!.unknown)
        model.page(n299)
        idle()
        assertEquals(2, asked)
    }

    @Test
    fun no_status_from_tfl_reads_as_unknown_and_is_asked_again_next_time() {
        status = { null }
        val model = vm()
        idle()
        model.page(n299)
        idle()
        val check = model.check.value!!
        assertNull(check.status)
        assertTrue(check.unknown)
        model.page(n299)
        idle()
        assertEquals(2, asked)
    }

    @Test
    fun a_line_on_screen_is_asked_again_and_an_answer_past_its_age_is_never_shown() {
        val model = vm()
        idle()
        model.page(n299)
        idle()
        assertEquals(1, asked)
        // Straight back to the page: the answer stands, nothing asked.
        clock += 20_000
        model.check(n299)
        idle()
        assertEquals(1, asked)
        // The page's next tick: asked again, the last answer (still within its age) up meanwhile.
        clock += 30_000
        model.check(n299)
        assertTrue(model.check.value!!.checking)
        assertNotNull(model.check.value!!.status)
        idle()
        assertEquals(2, asked)
        // Back after a while away (the app in the background): past its age, it isn't shown while the
        // new one is asked, the pill alone until it's in.
        clock += 100_000
        model.check(n299)
        assertTrue(model.check.value!!.checking)
        assertNull(model.check.value!!.status)
        idle()
        assertEquals(3, asked)
        assertFalse(model.check.value!!.unknown)
    }

    @Test
    fun the_page_asks_well_inside_the_answers_age() {
        assertEquals(45_000, vm().checkEvery.toMillis())
    }

    @Test
    fun the_kept_list_is_up_at_once_and_a_renewed_one_taken_when_it_comes() {
        val n2 = LineRef("2", "2", "bus")
        renewalGate = CompletableDeferred()
        renewed = { listOf(victoria, n29, n299, n2) }
        val model = vm()
        model.setQuery("2")
        releaseCompute()
        // The kept list is up and searched while the renewal is still out.
        assertTrue(model.state.value.catalog is LinesViewModel.Catalog.Ready)
        assertEquals(1, refreshes)
        assertFalse(model.state.value.matches.contains(n2))
        renewalGate!!.complete(Unit)
        releaseCompute()
        assertTrue(model.state.value.matches.contains(n2))
    }

    @Test
    fun a_reopened_search_renews_its_list_and_keeps_the_held_one_if_that_fails() {
        val model = vm()
        idle()
        assertEquals(1, listed)
        assertEquals(1, refreshes)
        // Reopened: only the renewal is asked (the kept list answers within its day: nothing comes).
        model.reopened()
        idle()
        assertEquals(1, listed)
        assertEquals(2, refreshes)
        // A renewal that fails keeps the list up.
        renewed = { throw IOException("offline") }
        model.reopened()
        idle()
        assertEquals(3, refreshes)
        assertTrue(model.state.value.catalog is LinesViewModel.Catalog.Ready)
    }

    @Test
    fun a_failed_list_is_asked_for_again_when_the_search_reopens() {
        lines = { throw IOException("offline") }
        val model = vm()
        idle()
        assertTrue(model.state.value.catalog is LinesViewModel.Catalog.Failed)
        lines = { listOf(n299) }
        model.reopened()
        idle()
        assertTrue(model.state.value.catalog is LinesViewModel.Catalog.Ready)
    }

    @Test
    fun reads_and_requests_run_on_io_never_on_the_main_thread() {
        val model = vm()
        model.page(n299)
        mainIdle()
        // Main alone has run: nothing read, listed, recorded or asked.
        assertNull(model.state.value.recent)
        assertEquals(0, listed)
        assertEquals(0, asked)
        assertEquals(listOf(victoria), recent)
        assertTrue(model.check.value!!.checking)
        idle()
        assertEquals(1, listed)
        assertEquals(1, asked)
        assertEquals(listOf(n299, victoria), model.state.value.recent)
    }

    @Test
    fun a_dismissed_alert_reads_as_dismissed_and_is_dismissed_from_the_page() {
        val disrupted = LineStatus(lineId = "299", severity = 9, description = "Minor Delays", fullText = "Minor delays.")
        status = { disrupted }
        val store = object : DismissedAlertsStore {
            val held = MutableStateFlow<Set<DismissedAlert>>(emptySet())
            override fun dismissed(): Flow<Set<DismissedAlert>> = held
            override suspend fun dismiss(alert: DismissedAlert) { held.value = held.value + alert }
            override suspend fun reconcile(live: Set<DismissedAlert>, checkedPlaces: Set<String>) {}
        }
        val model = vm(store)
        model.page(n299)
        releaseCompute()
        assertFalse(model.lineDismissed.value)
        model.dismiss(disrupted)
        releaseCompute()
        assertTrue(model.lineDismissed.value)
        assertEquals(setOf(DismissedAlert.ofLineStatus(disrupted)), store.held.value)
    }

    @Test
    fun the_query_comes_back_after_process_death_and_is_searched_again() {
        val saved = SavedStateHandle()
        val first = vm(saved = saved)
        idle()
        first.setQuery("299")
        releaseCompute()
        // A new process: a new model over the same saved state.
        val restored = vm(saved = SavedStateHandle(mapOf("query" to saved.get<String>("query"))))
        assertEquals("299", restored.state.value.query)
        releaseCompute()
        assertEquals(listOf(n299), restored.state.value.matches)
    }

    @Test
    fun an_answer_is_taken_down_at_its_age_even_while_the_next_is_still_being_asked() {
        val model = vm()
        idle()
        model.page(n299)
        idle()
        assertNotNull(model.check.value!!.status)
        // The next ask is slow: started, and still out when the answer reaches its age.
        clock += 50_000
        mainScheduler.advanceTimeBy(50_000)
        model.check(n299)
        mainIdle()
        assertTrue(model.check.value!!.checking)
        assertNotNull(model.check.value!!.status)
        mainScheduler.advanceTimeBy(40_001)
        mainScheduler.runCurrent()
        assertTrue(model.check.value!!.checking)
        assertNull(model.check.value!!.status)
        // The slow answer lands, and is up.
        idle()
        assertNotNull(model.check.value!!.status)
        assertFalse(model.check.value!!.checking)
    }

    @Test
    fun a_restored_query_is_held_to_its_length() {
        val model = vm(saved = SavedStateHandle(mapOf("query" to "9".repeat(LinesViewModel.MAX_QUERY + 10))))
        assertEquals(LinesViewModel.MAX_QUERY, model.state.value.query.length)
    }

    @Test
    fun a_lines_alert_is_dismissed_line_wide_or_every_way_it_is_disrupted() {
        val delays = LineStatus("jubilee", 9, "Minor Delays")
        assertFalse(lineAlertDismissed(null, emptySet()))
        assertFalse(lineAlertDismissed(delays, emptySet()))
        assertTrue(lineAlertDismissed(delays, setOf(DismissedAlert.ofLineStatus(delays))))
        // A good service has no alert to dismiss.
        val good = LineStatus("jubilee", LineStatus.GOOD_SERVICE, "Good Service")
        assertFalse(lineAlertDismissed(good, setOf(DismissedAlert.ofLineStatus(good))))
    }

    @Test
    fun `the week ahead is added to the line's work to come, asked once and kept`() {
        ahead = { listOf(weekend) }
        val vm = vm()
        vm.page(n299)
        // The status shows first; the week ahead joins it on the worker.
        repeat(3) { releaseCompute() }
        assertEquals(listOf(weekend), vm.check.value?.status?.planned)
        assertEquals(false, vm.check.value?.aheadUnknown)
        assertEquals(1, aheadAsked)
        // Asked again within its hours: the status is, the week ahead isn't, and stays on the page.
        clock += java.time.Duration.ofMinutes(5).toMillis()
        vm.check(n299)
        repeat(3) { releaseCompute() }
        assertEquals(2, asked)
        assertEquals(1, aheadAsked)
        assertEquals(listOf(weekend), vm.check.value?.status?.planned)
    }

    @Test
    fun `the week ahead is asked again once its soonest work starts`() {
        // Kept no longer than until it starts: under way then, not to come, so the page asks again rather than
        // leave it under Coming up for the rest of its hours (Codex, #697).
        ahead = { listOf(weekend) }
        aheadStartsIn = java.time.Duration.ofMinutes(20)
        val vm = vm()
        vm.page(n299)
        repeat(3) { releaseCompute() }
        assertEquals(1, aheadAsked)
        clock += java.time.Duration.ofMinutes(10).toMillis()
        vm.check(n299)
        repeat(3) { releaseCompute() }
        assertEquals(1, aheadAsked)
        clock += java.time.Duration.ofMinutes(11).toMillis()
        ahead = { emptyList() }
        vm.check(n299)
        repeat(3) { releaseCompute() }
        assertEquals(2, aheadAsked)
        assertEquals(emptyList<Any>(), vm.check.value?.status?.planned)
    }

    @Test
    fun `the week ahead's hold counts from its ask, however late its answer comes`() {
        // Answered 10 minutes after it was asked, its work 15 minutes off by then: asked again 15 minutes after
        // the ask, never 15 after the answer (Codex, #697).
        ahead = { clock += java.time.Duration.ofMinutes(10).toMillis(); listOf(weekend) }
        aheadStartsIn = java.time.Duration.ofMinutes(15)
        val vm = vm()
        vm.page(n299)
        repeat(3) { releaseCompute() }
        assertEquals(1, aheadAsked)
        ahead = { listOf(weekend) }
        clock += java.time.Duration.ofMinutes(6).toMillis()
        vm.check(n299)
        repeat(3) { releaseCompute() }
        assertEquals(2, aheadAsked)
    }

    @Test
    fun `a week ahead that runs out while the status is asked isn't shown, and is asked again`() {
        // Read once the status is in: work that started while it was asked never reaches Coming up (Codex, #704).
        ahead = { listOf(weekend) }
        aheadStartsIn = java.time.Duration.ofMinutes(20)
        val vm = vm()
        vm.page(n299)
        repeat(3) { releaseCompute() }
        assertEquals(1, aheadAsked)
        clock += java.time.Duration.ofMinutes(10).toMillis()
        // Its status takes long enough that the week's work starts meanwhile.
        status = { clock += java.time.Duration.ofMinutes(11).toMillis(); LineStatus(lineId = "299", severity = LineStatus.GOOD_SERVICE, description = "Good Service") }
        ahead = { emptyList() }
        vm.check(n299)
        repeat(3) { releaseCompute() }
        assertEquals(2, aheadAsked)
        assertEquals(emptyList<Any>(), vm.check.value?.status?.planned)
    }

    @Test
    fun `a week ahead that couldn't be asked is said so, and asked again`() {
        ahead = { throw java.io.IOException("down") }
        val vm = vm()
        vm.page(n299)
        repeat(3) { releaseCompute() }
        assertEquals(true, vm.check.value?.aheadUnknown)
        assertEquals("Good Service", vm.check.value?.status?.description)
        // The next tick asks again, the note standing until it's answered.
        ahead = { listOf(weekend) }
        clock += java.time.Duration.ofMinutes(5).toMillis()
        vm.check(n299)
        idle()
        assertEquals(true, vm.check.value?.aheadUnknown)
        repeat(3) { releaseCompute() }
        assertEquals(2, aheadAsked)
        assertEquals(false, vm.check.value?.aheadUnknown)
        assertEquals(listOf(weekend), vm.check.value?.status?.planned)
    }
}
