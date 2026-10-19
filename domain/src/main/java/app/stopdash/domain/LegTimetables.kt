package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Trip rides' timetables ([LegTimetableSource]), kept in memory for the rest of the London service
 * day, as [TimetableRepository] keeps a board's: a timetable changes from one day to the next, not
 * within one. Asked for only a ride reached past its live predictions ([TripTiming.Estimate.scheduleAsked])
 * on a line running a good service, so a trip whose trains are all predicted asks for none.
 *
 * **Cost**: free (TfL's Unified API), one request per line and pair of stops a day, about 300 KB and
 * a second or two for a Tube station; at most [MAX_CONCURRENT_FETCHES] at once, sharing TfL's
 * request budget with the live refresh. A failed fetch is asked again after [retryAfter]; until one
 * lands the ride is timed as it would be without it.
 */
class LegTimetables(
    private val source: LegTimetableSource,
    private val clock: () -> Instant = Instant::now,
    private val warn: (String) -> Unit = {},
    val retryAfter: Duration = TimetableRepository.RETRY_AFTER,
) {
    /** A ride's line and the stops it boards and gets off at. */
    data class Key(val lineId: String, val fromId: String, val toId: String)

    // A found timetable (null when TfL has none), or a failure (absent [timetable], [failed]).
    private data class Entry(val timetable: StopTimetable?, val failed: Boolean, val day: LocalDate, val at: Instant)

    private val entries = ConcurrentHashMap<Key, Entry>()
    // Each ride being fetched, completed once it's in (or the fetch gave up).
    private val inFlight = ConcurrentHashMap<Key, CompletableDeferred<Unit>>()
    private val fetchSlots = Semaphore(MAX_CONCURRENT_FETCHES)

    /**
     * Today's service day's answers so far, by ride: its timetable, or null where TfL has none (a 404,
     * not asked again that day). A ride not yet answered, or whose last fetch failed, is absent. With
     * the day they're for, so a caller never pairs one day's answers with the next day's date.
     */
    @WorkerThread
    fun known(): Known {
        val today = serviceDay(clock())
        return Known(today, entries.entries.filter { (_, entry) -> entry.day == today && !entry.failed }.associate { (key, entry) -> key to entry.timetable })
    }

    /** [known]'s answers ([timetables]) with the service [day] they're for, read at one instant. */
    data class Known(val day: LocalDate, val timetables: Map<Key, StopTimetable?>)

    /**
     * Fetches each of [keys] not yet in for today's service day (or failed over [retryAfter] ago).
     * One another caller is already fetching is waited for rather than asked twice, and fetched here
     * if that caller gave up. Suspends until each is in.
     */
    @WorkerThread
    suspend fun fetch(keys: Collection<Key>) {
        val today = serviceDay(clock())
        entries.entries.removeIf { it.value.day != today }
        coroutineScope { keys.distinct().forEach { key -> launch { settle(key, today) } } }
    }

    // Brings [key] in for [today]: answered already, waited for while another caller fetches it, or fetched.
    private suspend fun settle(key: Key, today: LocalDate) {
        while (true) {
            // Only today's answer counts: one asked before 04:00 and answered after is the day before's.
            entries[key]?.let { if (it.day == today && !needed(it, clock())) return }
            val mine = CompletableDeferred<Unit>()
            val other = inFlight.putIfAbsent(key, mine)
            if (other != null) {
                other.await()
                // Answered, failed included (its retry isn't due yet), unless that caller was canceled first.
                if (entries[key]?.day == today) return
                continue
            }
            try {
                entries[key] = fetchOne(key, today)
                return
            } finally {
                inFlight.remove(key, mine)
                mine.complete(Unit)
            }
        }
    }

    private fun needed(entry: Entry?, now: Instant): Boolean =
        entry == null || (entry.failed && Duration.between(entry.at, now) >= retryAfter)

    private suspend fun fetchOne(key: Key, today: LocalDate): Entry = fetchSlots.withPermit {
        try {
            Entry(source.legTimetable(key.lineId, key.fromId, key.toId), failed = false, today, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException.NotFound) {
            // TfL has no timetable for this ride, and asking again won't give one: kept as none for the day.
            // The log names the boarding stop only, never where the rider gets off (AGENTS.md Privacy).
            warn("no timetable for line ${key.lineId} from stop ${key.fromId}")
            Entry(null, failed = false, today, clock())
        } catch (e: TflException) {
            // The ride is timed without it until a later request gets it.
            // TfL's exceptions carry a sanitized reason ("HTTP 500", "offline"), never the request URL.
            warn("timetable failed for line ${key.lineId} from stop ${key.fromId}: ${e::class.simpleName} (${e.message})")
            Entry(null, failed = true, today, clock())
        }
    }

    companion object {
        private const val MAX_CONCURRENT_FETCHES = 2

        /** The London service day [at] falls in, which [known]'s timetables are for: it ends at 04:00. */
        fun serviceDay(at: Instant): LocalDate = TimetableRepository.serviceDay(at)
    }
}
