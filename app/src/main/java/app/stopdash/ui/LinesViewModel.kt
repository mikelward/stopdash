package app.stopdash.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.DismissedAlertsStore
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSearch
import app.stopdash.domain.LineStatus
import app.stopdash.domain.Workers
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StopLinks
import app.stopdash.domain.linksOf
import app.stopdash.domain.PlannedAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Lines…* (SPEC *Finding a line*): TfL's lines, searched on the device as the rider types, the lines
 * they opened lately listed before they type, and the status of the line they open. Every read, search
 * and request runs off the main thread; the screen only reads [state] and [check].
 */
internal class LinesViewModel(
    // TfL's lines ([app.stopdash.data.LineCatalog]): the kept list at once, whatever its age, else a first
    // one fetched; throws when none can be had.
    private val loadLines: suspend () -> List<LineRef>,
    // A renewed list when the kept one is a day old, else null (fresh, or TfL unreachable): taken when
    // it comes, the kept one up meanwhile, so the search never waits on it.
    private val refreshLines: suspend () -> List<LineRef>? = { null },
    // The recently opened lines, newest first, and remembering one; both blocking, run on [io].
    private val loadRecent: () -> List<LineRef>,
    private val recordOpen: (LineRef) -> List<LineRef>,
    // One line's status from TfL, or null when TfL gave none for it. Throws when it couldn't be asked.
    private val lineStatus: suspend (String) -> LineStatus?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Workers.compute,
    private val warn: (String) -> Unit = {},
    // A monotonic clock in milliseconds, and how long an answer stands before a reopened page asks
    // again ([LINE_STATUS_REUSE], as the departures' own line checks).
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reuse: java.time.Duration = LINE_STATUS_REUSE,
    // The service alerts the user dismissed, shared with every screen (SPEC *Disruptions*): the page
    // says "Service alert dismissed" for one dismissed elsewhere, and its menu dismisses one here.
    private val dismissedStore: DismissedAlertsStore = DismissedAlertsStore.NONE,
    // The typed query, kept over process death so the search comes back as it was, searched again once
    // the list is in (as the station search's is).
    private val saved: SavedStateHandle,
    // The bundled station index ([app.stopdash.data.StationIndexStore]), for a stop's lines and the
    // stations beside it (SPEC *Finding a line*); blocking, run on [io].
    private val loadIndex: () -> StationIndex = { StationIndex.EMPTY },
) : ViewModel() {
    /**
     * What the search shows. Compared by identity, never as a data class: the state flow compares each
     * new value with the last on the main thread, which mustn't walk the lists (AGENTS.md *Main thread*).
     */
    class State(
        val query: String = "",
        // The recently opened lines; null until read, so nothing flashes up before they're in.
        val recent: List<LineRef>? = null,
        val catalog: Catalog = Catalog.Loading,
        // The last lines matched, and the query they were matched for: they stand while a newer
        // query is searched, so typing doesn't blank the list on every letter.
        val matches: List<LineRef> = emptyList(),
        val matchesFor: String = "",
    ) {
        /** Whether a newer query than [matches]' is still being searched. */
        val searching: Boolean get() = catalog is Catalog.Ready && query.isNotBlank() && matchesFor != query
    }

    sealed interface Catalog {
        data object Loading : Catalog
        class Ready(val lines: List<LineRef>) : Catalog
        data object Failed : Catalog
    }

    /**
     * The opened line's status check: under way, its answer, or that it couldn't be made; [checkedAt]
     * is when an answer came in ([elapsedMillis]), null while none has.
     */
    class Check(val lineId: String, val checking: Boolean, val status: LineStatus?, val unknown: Boolean, val checkedAt: Long? = null)

    private val _state = MutableStateFlow(State(query = saved.get<String>(QUERY_KEY).orEmpty().take(MAX_QUERY)))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _check = MutableStateFlow<Check?>(null)
    val check: StateFlow<Check?> = _check.asStateFlow()

    private val _dismissed = MutableStateFlow<Set<DismissedAlert>>(emptySet())

    /** The service alerts the user dismissed, for the page's work to come to leave theirs out. */
    val dismissed: StateFlow<Set<DismissedAlert>> = _dismissed.asStateFlow()

    /** A stop's links ([linksOf]) and the stop they're for; null until the first is worked out. */
    class Links(val stopId: String, val links: StopLinks)

    private val _links = MutableStateFlow<Links?>(null)
    val links: StateFlow<Links?> = _links.asStateFlow()
    private var linksJob: Job? = null
    private val _dismissWriteFailed = MutableStateFlow(false)

    /** Whether a dismiss here couldn't be written, for the page to say so. */
    val dismissWriteFailed: StateFlow<Boolean> = _dismissWriteFailed.asStateFlow()

    /**
     * Whether the opened line's alert is one the user dismissed: worked out on [compute] (the alert's
     * key is built from its text), never on the main thread. False with no alert, or none checked.
     */
    val lineDismissed: StateFlow<Boolean> = combine(_check, _dismissed) { check, dismissed ->
        val status = check?.status
        status != null && status.disrupted && (
            DismissedAlert.ofLineStatus(status) in dismissed ||
                // Dismissed every way it's disrupted, as a row's own way's alert is on the list (HomeLines).
                status.byDirection.values.filter { it.disrupted }
                    .let { ways -> ways.isNotEmpty() && ways.all { DismissedAlert.ofLineStatus(it) in dismissed } }
            )
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var searchJob: Job? = null
    private var catalogJob: Job? = null
    private var checkJob: Job? = null
    private var expiryJob: Job? = null

    init {
        viewModelScope.launch {
            val recent = withContext(io) { loadRecent() }
            _state.update { State(it.query, recent, it.catalog, it.matches, it.matchesFor) }
        }
        fetchCatalog()
        viewModelScope.launch(io) { followDismissed(dismissedStore, _dismissed, warn) }
    }

    /** Dismisses [status]'s alert, as a line's page does elsewhere (SPEC *Disruptions*). */
    fun dismiss(status: LineStatus) {
        viewModelScope.launch { dismissAlertOf(dismissedStore, io, _dismissWriteFailed, warn, _dismissed) { DismissedAlert.ofLineStatus(status) } }
    }

    /** Dismisses one of [lineId]'s alerts still to come, as a line's page shows it. */
    fun dismissPlanned(lineId: String, alert: PlannedAlert) {
        viewModelScope.launch { dismissAlertOf(dismissedStore, io, _dismissWriteFailed, warn, _dismissed) { DismissedAlert.ofPlanned(lineId, alert) } }
    }

    /** The page has said a dismiss failed. */
    fun dismissWriteFailureShown() {
        _dismissWriteFailed.value = false
    }

    /** The typed query: searched again on the worker; the last matches stand until the new ones are in. */
    fun setQuery(query: String) {
        saved[QUERY_KEY] = query
        _state.update { State(query, it.recent, it.catalog, it.matches, it.matchesFor) }
        search()
    }

    /** Asks for TfL's lines again, after they couldn't be loaded. */
    fun retry() {
        if (_state.value.catalog is Catalog.Failed) fetchCatalog()
    }

    /**
     * The details of the stop [stopId] came up: its lines and the stations beside it are worked out from
     * the bundled index, off the main thread. Already up for it, they're left be. A stop the index doesn't
     * hold, or an index that couldn't be read, links nowhere ([StopLinks.NONE]): the page shows none.
     */
    fun stopLinks(stopId: String) {
        if (linksJob?.isActive == true && linksFor == stopId) return
        // Any lookup for another stop is dropped first, so it can't land over this one's (Codex on #664).
        linksJob?.cancel()
        if (_links.value?.stopId == stopId) {
            linksFor = stopId
            return
        }
        linksFor = stopId
        linksJob = viewModelScope.launch {
            val links = try {
                val index = withContext(io) { loadIndex() }
                withContext(compute) { index.linksOf(stopId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("stop links failed for $stopId: ${e::class.simpleName}")
                StopLinks.NONE
            }
            _links.value = Links(stopId, links)
        }
    }

    private var linksFor: String? = null

    /** [line] was opened: it's remembered as the newest recent line. Its status is asked by [check]. */
    fun open(line: LineRef) {
        viewModelScope.launch {
            val recent = withContext(io) { recordOpen(line) }
            _state.update { State(it.query, recent, it.catalog, it.matches, it.matchesFor) }
        }
    }

    /**
     * Asks TfL for [line]'s status for its page, which calls this as it comes up and every [checkEvery]
     * while it's on screen (SPEC *Finding a line*). A check under way, or an answer under half
     * [checkEvery] old (coming straight back to the page), is left be. An answer stays up while the next
     * is asked, but never past [reuse]: it's taken down then, whatever is under way ([expire]), and an
     * older one isn't shown again as though current (SPEC D4): the pill alone until the answer is in.
     */
    fun check(line: LineRef) {
        val held = _check.value?.takeIf { it.lineId == line.id }
        if (held != null && held.checking) return
        // Half the interval: the page's next tick, a little under [checkEvery] after this answer, asks again.
        if (held != null && !held.unknown && held.younger(checkEvery.dividedBy(2))) return
        checkJob?.cancel()
        val kept = held?.takeIf { !it.unknown && it.younger(reuse) }
        _check.value = Check(line.id, checking = true, status = kept?.status, unknown = false, checkedAt = kept?.checkedAt)
        checkJob = viewModelScope.launch {
            val answer = try {
                // No status at all is no answer: the page says it couldn't check, and asks again.
                val status = withContext(io) { lineStatus(line.id) }
                if (status == null) warn("line status for ${line.id}: none given")
                Check(line.id, checking = false, status = status, unknown = status == null, checkedAt = elapsedMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("line status failed for ${line.id}: ${e::class.simpleName}")
                // Couldn't check: its page says so rather than claiming a good service (SPEC D4).
                Check(line.id, checking = false, status = null, unknown = true)
            }
            _check.value = answer
            if (answer.status != null) expire(answer)
        }
    }

    /**
     * Takes [answer]'s status down once it's [reuse] old, if it's still the one up: by its own clock, not
     * the page's ticks, so a slow or queued request asked after it can't leave it up past its age
     * (Codex on #652). The check under way, if any, carries on; the page shows the pill alone meanwhile.
     */
    private fun expire(answer: Check) {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            delay(reuse.toMillis())
            _check.update { now ->
                if (now != null && now.lineId == answer.lineId && now.checkedAt == answer.checkedAt && now.status != null) {
                    Check(now.lineId, now.checking, status = null, unknown = now.unknown)
                } else now
            }
        }
    }

    /** How often a line's page asks again while it's on screen: well inside [reuse], so its answer never ages out shown. */
    val checkEvery: java.time.Duration get() = reuse.dividedBy(2)

    // Whether this answer came in less than [limit] ago, and isn't dated ahead of now.
    private fun Check.younger(limit: java.time.Duration): Boolean {
        val at = checkedAt ?: return false
        val age = elapsedMillis() - at
        return age >= 0 && age < limit.toMillis()
    }

    /**
     * The overlay came up again: a list already in is renewed if its day is up ([refreshLines]), staying
     * up meanwhile and if that fails; one that failed is tried again.
     */
    fun reopened() {
        if (catalogJob?.isActive == true) return
        if (_state.value.catalog !is Catalog.Ready) return fetchCatalog()
        catalogJob = viewModelScope.launch { refresh() }
    }

    // The kept list (or a first one) up at once, then renewed behind it if its day is up.
    private fun fetchCatalog() {
        catalogJob?.cancel()
        _state.update { State(it.query, it.recent, Catalog.Loading, it.matches, it.matchesFor) }
        catalogJob = viewModelScope.launch {
            val catalog = try {
                Catalog.Ready(withContext(io) { loadLines() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("line list failed: ${e::class.simpleName}")
                Catalog.Failed
            }
            _state.update { State(it.query, it.recent, catalog, it.matches, it.matchesFor) }
            search()
            if (catalog is Catalog.Ready) refresh()
        }
    }

    // A renewed list, if one comes, replaces the one up and the query is searched over it again.
    private suspend fun refresh() {
        val lines = try {
            withContext(io) { refreshLines() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("line list refresh failed: ${e::class.simpleName}")
            null
        } ?: return
        _state.update { State(it.query, it.recent, Catalog.Ready(lines), it.matches, it.matchesFor) }
        search()
    }

    private fun search() {
        val lines = (_state.value.catalog as? Catalog.Ready)?.lines ?: return
        val query = _state.value.query
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { State(it.query, it.recent, it.catalog, emptyList(), it.query) }
            return
        }
        searchJob = viewModelScope.launch {
            val matches = withContext(compute) { LineSearch.search(query, lines) }
            // A newer query typed meanwhile is searched by its own job; this answer is for its own query only.
            _state.update { if (it.query == query) State(it.query, it.recent, it.catalog, matches, query) else it }
        }
    }

    companion object {
        private const val QUERY_KEY = "query"

        /** The longest query taken: past any line's name or number, and short enough that the main thread's own reads of it stay trivial. */
        const val MAX_QUERY = 40

        /** The key the search's saved state is kept under in its holder ([LinesOverlay]). */
        const val SEARCH_STATE_KEY = "search"
    }
}
