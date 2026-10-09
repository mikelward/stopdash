package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The debug log's lines for journey alerts (SPEC *Journeys → Alerts*): when the next check is due, and
 * what each check found and did, so a missed alert can be explained from a bug report (SPEC principle 2).
 * Coarse by design (SPEC *Privacy*): times, counts, line ids and TfL's status words, never a stop or a
 * journey's ends, which together say where the rider lives and works.
 */
object JourneyAlertLog {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** The check armed at [now] for [due]: when, whether a window is open by then, and whether it waits for a network. */
    fun scheduled(now: Instant, due: Instant, asks: Boolean, zone: ZoneId): String {
        // One already due runs at once, so it's said as now.
        val at = maxOf(due, now)
        val minutes = Duration.between(now, at).toMinutes()
        val clock = CLOCK.format(at.atZone(zone))
        return if (asks) {
            "next check in $minutes min ($clock), window open, waits for a network"
        } else {
            "next check in $minutes min ($clock), no window open then"
        }
    }

    /** The close check armed at [now] for [closes], which only takes down what the closing window showed. */
    fun closeScheduled(now: Instant, closes: Instant, zone: ZoneId): String {
        val at = maxOf(closes, now)
        return "close check in ${Duration.between(now, at).toMinutes()} min (${CLOCK.format(at.atZone(zone))})"
    }

    /** Why no check is armed: nothing watched, or alerts can't show. */
    fun notScheduled(watched: Boolean): String =
        if (watched) "not scheduled: notifications off" else "not scheduled: no direction watched"

    /**
     * One check's line. [late] is how long after it was due it ran (null when not known: one armed before
     * this was recorded). [watched] counts the journeys with a window open; [skipped] says why nothing
     * was asked when it wasn't. [asked] are the lines asked about and [statuses] what TfL said of them
     * (null: no answer). [done] are the alerts posted or taken down, and [notDone] the decisions left for
     * a later check (the settings or pins changed meanwhile, or the rider had swiped it).
     */
    @WorkerThread
    fun check(
        late: Duration?,
        closeOnly: Boolean,
        watched: Int,
        skipped: String?,
        asked: Set<String>,
        statuses: Map<String, LineStatus>?,
        done: List<JourneyAlertAction>,
        notDone: Int,
    ): String {
        val ran = (if (closeOnly) "close check" else "check") + (late?.let { " ran ${it.coerceAtLeast(Duration.ZERO).seconds} s after due" } ?: " ran")
        val parts = mutableListOf("$watched journeys watched")
        if (skipped != null) {
            parts += skipped
        } else if (asked.isNotEmpty()) {
            parts += asked.sorted().joinToString(", ", prefix = "TfL: ") { line ->
                val status = statuses?.get(line)
                when {
                    statuses == null -> "$line no answer"
                    status == null -> "$line not answered"
                    else -> "$line ${status.description.ifBlank { if (status.disrupted) "disrupted" else "good" }}"
                }
            }
        }
        val posted = done.filterIsInstance<JourneyAlertAction.Post>()
        parts += "posted ${posted.count { !it.renew }}, renewed ${posted.count { it.renew }}, " +
            "cleared ${done.count { it is JourneyAlertAction.Clear }}" + (if (notDone > 0) ", left $notDone for later" else "")
        return "$ran: ${parts.joinToString("; ")}"
    }
}
