package app.stopdash.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.stopdash.data.WorkAhead
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.PlannedAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Each line's work for the coming days, TfL's date-range status ([WorkAhead]), as last asked, for a line
 * page's *Coming up* (SPEC *Line page*), one for every page that shows it: held for [reuse], or until the
 * soonest of it starts, whichever comes first, as it's under way then and asked again (Codex, #697); a line
 * whose ask failed is said so ([Answer.failed]) until an ask goes through. Read and written on the main
 * thread; the request runs on [io].
 */
internal class WorkAheadCache(
    // Throws when it couldn't be asked.
    private val workAhead: suspend (String) -> WorkAhead,
    private val io: CoroutineDispatcher,
    // A monotonic clock in milliseconds.
    private val elapsedMillis: () -> Long,
    private val reuse: Duration = REUSE,
    private val warn: (String) -> Unit = {},
) {
    /** A line's work ahead as last answered, when ([elapsedMillis]), and for how long it holds. */
    class Held(val alerts: List<PlannedAlert>, val at: Long, val holdsFor: Duration)

    /** One line's week ahead as last answered, if any, and whether its last ask failed. */
    class Answer(val held: Held?, val failed: Boolean)

    // Each line's answer a state of its own, so an answer coming in touches its line alone, never a copy of
    // every line's (Codex, #704); one a line asked for, so bounded by TfL's lines.
    private val lines = HashMap<String, MutableStateFlow<Answer>>()

    private fun stateOf(lineId: String) = lines.getOrPut(lineId) { MutableStateFlow(NONE) }

    /** [lineId]'s answer as it comes in, for its page to read through [current]. */
    fun answer(lineId: String): StateFlow<Answer> = stateOf(lineId)

    /**
     * [held] while it holds, else null: a page reads its line's week through this, so one past its hold is
     * never shown, however long until it's dropped (Codex, #704). A clock read, cheap enough for composition.
     */
    fun current(held: Held?): Held? = held?.takeIf { it.holds() }

    /** [lineId]'s work ahead while it holds, else null: none asked, or it's due to be asked again. */
    fun fresh(lineId: String): List<PlannedAlert>? = current(lines[lineId]?.value?.held)?.alerts

    /** Whether [lineId]'s last ask failed. */
    fun failed(lineId: String): Boolean = lines[lineId]?.value?.failed == true

    /** How long until [lineId]'s work ahead is due to be asked again, in milliseconds; null for now. */
    fun holdsForMillis(lineId: String): Long? {
        val held = current(lines[lineId]?.value?.held) ?: return null
        return held.holdsFor.toMillis() - (elapsedMillis() - held.at)
    }

    // The asks under way, one a line, for a second asker to wait on rather than ask again.
    private val inFlight = HashMap<String, CompletableDeferred<List<PlannedAlert>?>>()

    /**
     * [lineId]'s work ahead: as held while it holds, else asked for and kept, or said to have failed; null
     * for a failed ask. Every page shares one request: an ask while another for the line is under way waits
     * for that one's answer (Codex, #704). One past its hold is dropped first, so no page shows work that has
     * started meanwhile as still to come.
     */
    suspend fun ask(lineId: String): List<PlannedAlert>? {
        fresh(lineId)?.let { return it }
        inFlight[lineId]?.let { running ->
            return try {
                running.await()
            } catch (e: CancellationException) {
                // The asker it waited on left, not this one: ask itself.
                currentCoroutineContext().ensureActive()
                ask(lineId)
            }
        }
        val mine = CompletableDeferred<List<PlannedAlert>?>()
        inFlight[lineId] = mine
        try {
            val state = stateOf(lineId)
            state.update { now -> if (now.held?.holds() == false) Answer(null, now.failed) else now }
            // Its hold counted from the ask, before the request and anything that can delay its answer reaching
            // here: the client measures [WorkAhead.startsIn] later than this, so it can only expire early, never
            // keep work past its start (Codex, #697).
            val asked = elapsedMillis()
            val week = try {
                withContext(io) { workAhead(lineId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("work ahead failed for $lineId: ${e::class.simpleName}")
                null
            }
            state.update { now ->
                if (week != null) {
                    val holds = week.startsIn?.takeIf { it < reuse } ?: reuse
                    Answer(Held(week.alerts, asked, holds), failed = false)
                } else {
                    Answer(now.held, failed = true)
                }
            }
            mine.complete(week?.alerts)
            return week?.alerts
        } catch (e: CancellationException) {
            mine.cancel(e)
            throw e
        } finally {
            if (inFlight[lineId] === mine) inFlight.remove(lineId)
        }
    }

    private fun Held.holds(): Boolean {
        val age = elapsedMillis() - at
        return age >= 0 && age < holdsFor.toMillis()
    }

    companion object {
        // How long a line's week ahead stands before its page asks again: planned work moves slowly.
        val REUSE: Duration = Duration.ofHours(3)

        // How often an open page looks again, at the latest: a failed ask is retried this often.
        val RECHECK: Duration = Duration.ofMinutes(1)

        private val NONE = Answer(null, failed = false)
    }
}

/**
 * What a lines page needs to add a line's week ahead to its page ([TripLinesPage]): the [cache] it asks, and
 * the alerts the rider [dismissed], to leave theirs out. Null where a page shows none of its own.
 */
internal class LineWorkAhead(val cache: WorkAheadCache, val dismissed: StateFlow<Set<DismissedAlert>>)

internal val LocalLineWorkAhead = staticCompositionLocalOf<LineWorkAhead?> { null }
