package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A stretch of a day, from [start] up to (not at) [end], in the device's time zone. [end] is after
 * [start]; a window crossing midnight isn't offered.
 */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    val valid: Boolean get() = start.isBefore(end)

    fun contains(time: LocalTime): Boolean = valid && !time.isBefore(start) && time.isBefore(end)
}

/**
 * [time] on [date] in [zone], the first time it comes round after [after]: an hour repeated as the
 * clocks go back has two, and the one already past is never the answer (Codex on #700). A time in
 * the hour skipped as they go forward is the jump itself.
 */
private fun occurrence(date: java.time.LocalDate, time: LocalTime, zone: java.time.ZoneId, after: ZonedDateTime): ZonedDateTime? {
    val local = java.time.LocalDateTime.of(date, time)
    val offsets = zone.rules.getValidOffsets(local)
    // In a skipped hour the time is the moment the clocks jump, when the wall clock first reads past it,
    // so a window starting inside the gap opens exactly when [JourneyAlertSchedule.isActive] says it is.
    val candidates = if (offsets.isEmpty()) {
        listOf(zone.rules.getTransition(local).instant.atZone(zone))
    } else {
        offsets.map { ZonedDateTime.ofStrict(local, it, zone) }
    }
    return candidates.filter { it.toInstant().isAfter(after.toInstant()) }.minOrNull()
}

/**
 * When a favorite journey is watched for service alerts (SPEC *Journeys → Alerts*; maintainer,
 * 2026-10-08): on [days], during each of [windows], in the device's time zone. Inside a window a
 * background check posts a silent notification when one of the journey's lines is disrupted. A
 * journey with no schedule isn't watched: alerts are off until the rider turns them on.
 */
data class JourneyAlertSchedule(
    val days: Set<DayOfWeek> = WEEKDAYS,
    val windows: List<TimeWindow> = DEFAULT_WINDOWS,
) {
    /** Whether [at] falls in one of the windows on one of [days]. */
    @WorkerThread
    fun isActive(at: ZonedDateTime): Boolean =
        at.dayOfWeek in days && windows.any { it.contains(at.toLocalTime()) }

    /** When the window open at [at] closes, or null when none is open then. */
    @WorkerThread
    fun currentEnd(at: ZonedDateTime): ZonedDateTime? {
        if (at.dayOfWeek !in days) return null
        // The end of the whole stretch of windows open from now: overlapping or touching ones run on
        // into each other, so 08:00–10:00 and 09:00–11:00 close at 11:00 (Codex on #700).
        var end = windows.filter { it.contains(at.toLocalTime()) }.maxOfOrNull { it.end } ?: return null
        while (true) {
            val further = windows.filter { it.valid && !it.start.isAfter(end) && it.end.isAfter(end) }.maxOfOrNull { it.end } ?: break
            end = further
        }
        return occurrence(at.toLocalDate(), end, at.zone, at) ?: ZonedDateTime.of(at.toLocalDate(), end, at.zone)
    }

    /** The next time a window opens strictly after [after], or null when none ever does. */
    @WorkerThread
    fun nextStart(after: ZonedDateTime): ZonedDateTime? {
        val starts = windows.filter { it.valid }.map { it.start }.sorted()
        if (days.isEmpty() || starts.isEmpty()) return null
        // Eight days covers today's windows already passed, through the same weekday next week.
        var next: ZonedDateTime? = null
        for (offset in 0L..7L) {
            val date = after.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in days) continue
            next = starts.mapNotNull { occurrence(date, it, after.zone, after) }.minOrNull()
            if (next != null) break
        }
        // A window can also open by the clocks going back into it, its start long past (Codex on
        // #700): each fall-back in that span whose first moment is inside a window counts too.
        val reentry = clocksBack(after, next ?: after.plusDays(8)).firstOrNull { isActive(it) }
        return listOfNotNull(next, reentry).minOrNull()
    }

    // Each moment the clocks go back after [after] and up to [until], as the wall clock reads just then.
    @WorkerThread
    private fun clocksBack(after: ZonedDateTime, until: ZonedDateTime): List<ZonedDateTime> {
        val rules = after.zone.rules
        val out = mutableListOf<ZonedDateTime>()
        var transition = rules.nextTransition(after.toInstant())
        while (transition != null && !transition.instant.isAfter(until.toInstant())) {
            if (transition.isOverlap) out += transition.instant.atZone(after.zone)
            transition = rules.nextTransition(transition.instant)
        }
        return out
    }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        // The maintainer's defaults, 2026-10-08: "use 08-10", "and i guess also 16-18" — a journey is
        // saved both ways, so the way there and the way back.
        val DEFAULT_WINDOWS: List<TimeWindow> = listOf(
            TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)),
            TimeWindow(LocalTime.of(16, 0), LocalTime.of(18, 0)),
        )

        val DEFAULT = JourneyAlertSchedule()

        /** The most windows a direction may have: plenty for a commute, and keeps its screen bounded. */
        const val MAX_WINDOWS = 4
    }
}

/**
 * One disrupted line on a watched journey, as its notification names it: the line and TfL's label
 * and words for what's wrong. [fingerprint] is what it says, so the same alert isn't announced again.
 */
data class JourneyLineAlert(
    val lineId: String,
    val lineName: String,
    val description: String,
    val fullText: String?,
) {
    val fingerprint: String get() = "$lineId|$description|${fullText.orEmpty()}"
}

/**
 * What a check found for one journey: [key] ([FavoriteJourney.key]) and its [alerts], worst first,
 * from the lines TfL answered for; [unanswered] are the lines it asked about and got no status for.
 */
data class JourneyAlertResult(
    val key: String,
    val alerts: List<JourneyLineAlert>,
    val unanswered: Set<String> = emptySet(),
    // The open directions it was found for (their origins' stop ids). Part of what it says: a swipe of
    // the alert for one direction doesn't hide the same disruption for the other once the first's
    // window has closed and its alert timed out (Codex on #700).
    val directions: Set<String> = emptySet(),
    // When the window it was found in closes: which occurrence of the window it belongs to, so a swipe
    // made in one commute doesn't hide the same disruption in the next, however late the check
    // between them ran (Codex on #700).
    val until: Instant? = null,
) {
    /**
     * What the notification says, as one string: the same alerts in the same words, for the same
     * directions in the same window, read the same. The directions and window lead, on a line of their
     * own with no bar, so [linesIn] and [leavesOut] see only the alerts' lines.
     */
    @get:WorkerThread
    val fingerprint: String
        get() = (listOfNotNull(scope(directions, until)) + alerts.map { it.fingerprint }.sorted()).joinToString("\n")

    /** Whether [announced] (a [fingerprint]) names one of the [unanswered] lines. */
    @WorkerThread
    fun leavesOut(announced: String?): Boolean =
        announced != null && unanswered.any { line -> announced.startsWith("$line|") || announced.contains("\n$line|") }

    companion object {
        /**
         * The line of a [fingerprint] naming what an alert was found for: its open [directions] and the
         * close of their window, [until]. Null when it names neither.
         */
        @WorkerThread
        fun scope(directions: Set<String>, until: Instant?): String? =
            listOfNotNull(
                directions.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",", prefix = "@"),
                until?.let { "until ${it.epochSecond}" },
            ).joinToString(" ").takeIf { it.isNotEmpty() }

        /** The [scope] an alert saying [announced] (a [fingerprint]) was found for, or null for none. */
        @WorkerThread
        fun scopeIn(announced: String): String? =
            announced.substringBefore('\n').takeIf { it.startsWith("@") || it.startsWith("until ") }

        /**
         * The lines an alert saying [announced] (a [fingerprint]) is about, so a check asks about each
         * again whatever else it can or can't read. A line of TfL's words that happens to hold a bar
         * adds a name no line has, which TfL simply doesn't answer for.
         */
        @WorkerThread
        fun linesIn(announced: String?): Set<String> =
            announced?.split('\n')?.filter { '|' in it }?.mapTo(LinkedHashSet()) { it.substringBefore('|') }.orEmpty()
    }
}

/** What to do with one journey's notification after a check. */
sealed interface JourneyAlertAction {
    val key: String

    /**
     * Post (or update) the notification for [result]: it is new or says something different, or
     * ([renew]) it is still showing and is posted again, silently, so it stays up only as long as
     * checks keep finding it.
     */
    data class Post(val result: JourneyAlertResult, val renew: Boolean = false) : JourneyAlertAction {
        override val key: String get() = result.key
    }

    /** Take the notification down: the journey's lines are clear, or its window has closed. */
    data class Clear(override val key: String) : JourneyAlertAction
}

/**
 * Pure decisions for journey alerts (SPEC *Journeys → Alerts*), JVM-testable: which journeys are being
 * watched now, which lines to ask TfL about, when to check next, and what each check changes.
 */
object JourneyAlerts {
    /**
     * How often a journey is checked while its window is open: WorkManager's floor for repeating work,
     * so a disruption is heard within about a quarter of an hour, at four requests an hour.
     */
    val CHECK_INTERVAL: Duration = Duration.ofMinutes(15)

    /**
     * Where [journey]'s schedule for travel from [originStopId] is kept: alerts are set per direction
     * (maintainer, 2026-10-08: separate times for home to work and work to home).
     */
    fun directionKey(journey: FavoriteJourney, originStopId: String): String = "${journey.key}>$originStopId"

    /** The journey a [directionKey] belongs to. */
    fun journeyKey(directionKey: String): String = directionKey.substringBefore('>')

    /**
     * The schedules alerts start with when the rider turns them on for [journey]: the way it was saved,
     * mornings; the way back, evenings; both Monday to Friday (maintainer, 2026-10-08).
     */
    fun defaultsFor(journey: FavoriteJourney): Map<String, JourneyAlertSchedule> = mapOf(
        directionKey(journey, journey.from.stopId) to JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.take(1)),
        directionKey(journey, journey.to.stopId) to JourneyAlertSchedule(windows = JourneyAlertSchedule.DEFAULT_WINDOWS.drop(1)),
    )

    /** [journey]'s directions watched at [now], each oriented the way it travels, the saved way first. */
    @WorkerThread
    fun activeDirections(journey: FavoriteJourney, schedules: Map<String, JourneyAlertSchedule>, now: ZonedDateTime): List<FavoriteJourney> =
        listOf(journey, journey.reversed()).filter { schedules[directionKey(it, it.from.stopId)]?.isActive(now) == true }

    /**
     * The journeys among [journeys] being watched at [now], each oriented the way its open window
     * travels ([FavoriteJourney.reversed] for the way back), for the notification's title. Where both
     * directions are open at once the saved one titles it; [activeDirections] gives both, whose lines
     * are all checked.
     */
    @WorkerThread
    fun active(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, now: ZonedDateTime): List<FavoriteJourney> =
        journeys.mapNotNull { journey -> activeDirections(journey, schedules, now).firstOrNull() }

    /**
     * How long what a check found for [journey] at [now] holds: until the first of its open
     * directions' windows closes. What it says may be about that direction's lines alone, so it goes
     * then, even with no check to take it down (offline, or deferred by the system), and a check for
     * the direction still open puts back whatever still applies (Codex on #700). Null when none is open.
     */
    @WorkerThread
    fun watchedUntil(journey: FavoriteJourney, schedules: Map<String, JourneyAlertSchedule>, now: ZonedDateTime): ZonedDateTime? =
        activeDirections(journey, schedules, now)
            .mapNotNull { schedules[directionKey(it, it.from.stopId)]?.currentEnd(now) }
            .minOrNull()

    /**
     * When the first window open at [at] closes, among the saved journeys' directions; null when none
     * is open then. A check held for a network past that moment still has to take its alert down.
     */
    @WorkerThread
    fun closesAfter(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, at: ZonedDateTime): ZonedDateTime? =
        prune(schedules, journeys).values.mapNotNull { it.currentEnd(at) }.minOrNull()

    /**
     * When the next check is due after [now]: a [CHECK_INTERVAL] on while any window is open, but no
     * later than the next window to open or an open one to close (so its alert comes down as it
     * closes, not up to a quarter hour after); else when the next one opens. Null when no saved
     * journey is watched at all, so nothing is scheduled.
     */
    @WorkerThread
    fun nextCheck(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, now: ZonedDateTime): ZonedDateTime? {
        val watched = prune(schedules, journeys).values
        if (watched.isEmpty()) return null
        val nextOpen = watched.mapNotNull { it.nextStart(now) }.minOrNull()
        if (watched.none { it.isActive(now) }) return nextOpen
        val closes = watched.mapNotNull { it.currentEnd(now) }.minOrNull()
        return listOfNotNull(now.plus(CHECK_INTERVAL), nextOpen, closes).min()
    }

    /**
     * The journeys watched now ([now]) whose lines aren't the ones a check asked about ([asked]): every
     * one, not only those with something to post or clear, since one whose pins moved with nothing to do
     * still needs a check of its new lines at once (Codex on #700).
     */
    @WorkerThread
    fun moved(asked: Map<String, Set<String>>, now: Map<String, Set<String>>): Set<String> =
        now.keys.filterTo(HashSet()) { asked[it] != now[it] }

    /**
     * Each of [journeys]' lines to ask about at [at]: those of every direction whose window is open (both
     * ways' when their windows overlap), by [pinned] ([pinnedLines]).
     */
    @WorkerThread
    fun linesFor(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, at: ZonedDateTime, pinned: Map<String, Map<String, Set<String>>>): Map<String, Set<String>> =
        journeys.associate { journey ->
            journey.key to activeDirections(journey, schedules, at).flatMapTo(LinkedHashSet()) { lines(it, pinned[it.key].orEmpty()) }
        }

    /**
     * The lines to ask about for [journey], oriented the way it's traveled: the line it was saved
     * on, and the lines the app last found running directly between its ends ([pinnedLines], from
     * the widget's journey pins), since the background check can't look routes up itself. The pins
     * worked out for this direction ([JourneyEnd.stopId] of its origin) when there are any; else
     * those for the other way, since the widget keeps only the direction last shown and a line
     * between two stops almost always runs both ways — asking about one too many costs an alert
     * worth a glance, one too few a disruption missed.
     */
    @WorkerThread
    fun lines(journey: FavoriteJourney, pinnedByOrigin: Map<String, Set<String>>): Set<String> {
        val pinned = pinnedByOrigin[journey.from.stopId] ?: pinnedByOrigin.values.flatten()
        return (setOf(journey.lineId) + pinned).filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    /**
     * What each journey's alert says, as far as a check can tell: the record ([announced]), overruled by
     * the alert actually up ([up], its own fingerprint), since an alert is posted before it's recorded and
     * the process can die between the two (Codex on #700). Every decision reads this, not the record alone.
     */
    @WorkerThread
    fun shown(announced: Map<String, String>, up: Map<String, String?>): Map<String, String> =
        announced + up.mapNotNull { (key, said) -> said?.let { key to it } }

    /**
     * The lines each of [journeys] last showed an alert for ([shown]) that this check isn't asking about
     * ([lines]): left unanswered rather than cleared by going unasked.
     */
    @WorkerThread
    fun unasked(journeys: List<FavoriteJourney>, lines: Map<String, Set<String>>, shown: Map<String, String>): Map<String, Set<String>> =
        journeys.associate { journey -> journey.key to JourneyAlertResult.linesIn(shown[journey.key]) - lines[journey.key].orEmpty() }

    /**
     * The lines each journey key's pins in [snapshot] run, by the stop the journey was shown from
     * ([WidgetJourney.shownFrom]; blank where it was never recorded).
     */
    @WorkerThread
    fun pinnedLines(snapshot: DeparturesSnapshot?): Map<String, Map<String, Set<String>>> =
        snapshot?.journeys.orEmpty()
            .groupBy { WidgetJourneys.baseKey(it.key) }
            .mapValues { (_, pins) ->
                pins.groupBy { it.shownFrom }.mapValues { (_, own) -> own.flatMapTo(HashSet()) { pin -> pin.calls.map { it.lineId } } }
            }

    /** Each line's rider-facing name, as [snapshot]'s stops and departures carry them. */
    @WorkerThread
    fun lineNames(snapshot: DeparturesSnapshot?): Map<String, String> {
        val names = HashMap<String, String>()
        for (stop in snapshot?.stops.orEmpty()) {
            for (line in stop.lines) if (line.name.isNotBlank()) names.putIfAbsent(line.id, line.name)
            for (d in stop.departures) if (d.lineName.isNotBlank()) names.putIfAbsent(d.lineId, riderLineName(d.lineName, d.mode))
        }
        return names
    }

    /**
     * What [statuses] (the lines TfL answered for) say of each of [journeys], with each
     * journey's [lines]. A line TfL didn't answer for isn't reported either way: the check claims
     * nothing it didn't hear (SPEC principle 1), so the journey keeps whatever it last showed.
     * [names] gives a line's rider-facing name where known.
     */
    @WorkerThread
    fun results(
        journeys: List<FavoriteJourney>,
        lines: Map<String, Set<String>>,
        statuses: Map<String, LineStatus>,
        names: Map<String, String> = emptyMap(),
        // Lines, by journey key, that couldn't be asked about this time but may be the journey's: they
        // count as unanswered.
        inconclusive: Map<String, Set<String>> = emptyMap(),
        // The open directions, by journey key, each result is for ([directionsOf]), and when their
        // window closes ([watchedUntil]).
        directions: Map<String, Set<String>> = emptyMap(),
        until: Map<String, Instant> = emptyMap(),
    ): List<JourneyAlertResult> = journeys.mapNotNull { journey ->
        val asked = lines[journey.key].orEmpty()
        // A journey none of whose lines was answered says nothing new.
        if (asked.none { it in statuses }) return@mapNotNull null
        // A line TfL didn't answer for (one it doesn't know, or a chunk of the request it refused)
        // says nothing either way; [actions] leaves alone a journey whose alert names one.
        val unanswered = asked.filterTo(LinkedHashSet()) { it !in statuses } + inconclusive[journey.key].orEmpty()
        val alerts = asked.mapNotNull { statuses[it] }
            .filter { it.disrupted }
            .sortedWith(compareBy<LineStatus> { it.severity }.thenBy { it.lineId })
            .map { status ->
                val name = names[status.lineId]?.takeIf { it.isNotBlank() }
                    ?: journey.lineName.takeIf { status.lineId == journey.lineId && it.isNotBlank() }
                    ?: status.lineId
                JourneyLineAlert(status.lineId, name, status.description, status.fullText)
            }
        JourneyAlertResult(journey.key, alerts, unanswered, directions[journey.key].orEmpty(), until[journey.key])
    }

    /**
     * What to do with each journey's notification, given this check's [results], the journeys
     * [active] now, what was last [announced] (a fingerprint per journey key), which are [showing],
     * and which the rider [dismissed] (swiped away, by the fingerprint they swiped). A disruption the
     * rider swiped isn't posted again; one that says something new is. One that only timed out (a
     * late check) is posted again, since nobody asked for it to go. One still showing is renewed,
     * silently, unless the rider has since swiped it. A journey whose lines are clear, or whose window has closed, has its notification
     * taken down, so nothing shown outlives the check behind it. A journey not answered this time is
     * left alone.
     */
    @WorkerThread
    fun actions(
        results: List<JourneyAlertResult>,
        active: Set<String>,
        announced: Map<String, String>,
        showing: Set<String>,
        dismissed: Map<String, String> = emptyMap(),
    ): List<JourneyAlertAction> {
        val out = mutableListOf<JourneyAlertAction>()
        for (result in results) {
            if (result.key !in active) continue
            // Its alert names a line not answered this time: nothing is known of that line, so the
            // alert neither clears nor changes on a partial answer (Codex on #700).
            if (result.leavesOut(announced[result.key])) continue
            val fingerprint = result.fingerprint
            when {
                result.alerts.isEmpty() -> if (result.key in showing || result.key in announced || result.key in dismissed) {
                    out += JourneyAlertAction.Clear(result.key)
                }
                announced[result.key] != fingerprint -> out += JourneyAlertAction.Post(result)
                // A swipe outranks "showing", which may have been read before the swipe landed.
                dismissed[result.key] == fingerprint -> Unit
                result.key in showing -> out += JourneyAlertAction.Post(result, renew = true)
                else -> out += JourneyAlertAction.Post(result)
            }
        }
        // Windows closed, or journeys no longer watched: their notifications go.
        (showing + announced.keys + dismissed.keys).filter { it !in active }.distinct().forEach { key -> out += JourneyAlertAction.Clear(key) }
        return out
    }

    /** [announced] after [actions] were carried out: a post records what it said, a clear forgets it. */
    @WorkerThread
    fun announcedAfter(announced: Map<String, String>, actions: List<JourneyAlertAction>): Map<String, String> {
        val next = announced.toMutableMap()
        for (action in actions) when (action) {
            is JourneyAlertAction.Post -> next[action.key] = action.result.fingerprint
            is JourneyAlertAction.Clear -> next.remove(action.key)
        }
        return next
    }

    /**
     * [dismissed] after [actions]: a clear forgets the swipe, and so does a post saying something
     * other than what was swiped, so the next swipe is the one that counts. A clear of a journey
     * [heldBack] (its window still open, its alert only taken down while the rider is away) keeps the
     * swipe, so the same alert isn't brought back on their return (Codex on #712).
     */
    @WorkerThread
    fun dismissedAfter(
        dismissed: Map<String, String>,
        actions: List<JourneyAlertAction>,
        heldBack: Set<String> = emptySet(),
    ): Map<String, String> {
        val next = dismissed.toMutableMap()
        for (action in actions) when (action) {
            is JourneyAlertAction.Post -> if (next[action.key] != action.result.fingerprint) next.remove(action.key)
            is JourneyAlertAction.Clear -> if (action.key !in heldBack) next.remove(action.key)
        }
        return next
    }

    /** Which way each of [journeys] is watched at [at], by journey key: the origin of each open direction. */
    @WorkerThread
    fun directionsOf(journeys: List<FavoriteJourney>, schedules: Map<String, JourneyAlertSchedule>, at: ZonedDateTime): Map<String, Set<String>> =
        journeys.associate { journey -> journey.key to activeDirections(journey, schedules, at).mapTo(HashSet()) { it.from.stopId } }

    /** [schedules] (by [directionKey]) kept only for journeys still saved in [journeys]. */
    @WorkerThread
    fun prune(schedules: Map<String, JourneyAlertSchedule>, journeys: List<FavoriteJourney>): Map<String, JourneyAlertSchedule> {
        // Only a saved journey's two directions: a key that names neither (from a restore, another build)
        // could be timed but never found by a check, nor shown on the screen (Codex on #700).
        val keys = journeys.flatMapTo(HashSet()) { listOf(directionKey(it, it.from.stopId), directionKey(it, it.to.stopId)) }
        return schedules.filterKeys { it in keys }
    }

    /**
     * Whether a check should hold its alerts back because the phone is abroad: the mobile network it's on
     * ([networkCountry], an ISO 3166 code as Android reports it) is outside the UK (maintainer, 2026-10-09).
     * Only a network known to be elsewhere does: with none (Wi-Fi only, no SIM, airplane mode), alerts fire
     * as they would anyway, so a phone that can't say never silences them unseen. The Crown Dependencies'
     * networks count as home, since their riders commute into London as anyone's do.
     */
    fun abroad(networkCountry: String?): Boolean {
        val country = networkCountry?.trim()?.lowercase().orEmpty()
        return country.isNotEmpty() && country !in HOME_NETWORKS
    }

    /**
     * The country of the mobile network the phone is registered on, from what Android reports: its
     * [networkCountryIso], but only with a [simReady] SIM and a [networkOperator] (Android's numeric code
     * for the registered operator, blank when the phone isn't registered). Android can answer the
     * country from a nearby cell alone, with no SIM or no registration, which says nothing of where
     * the rider's network is (Codex on #712); null then, so alerts fire as before.
     */
    fun registeredCountry(networkCountryIso: String?, simReady: Boolean, networkOperator: String?): String? =
        networkCountryIso?.takeIf { simReady && !networkOperator.isNullOrBlank() && it.isNotBlank() }

    private val HOME_NETWORKS = setOf("gb", "uk", "gg", "je", "im")

    /**
     * Whether [at] is in or around London, for journey alerts, which fire only there (maintainer,
     * 2026-10-09): within [LONDON_RADIUS_KM] of Charing Cross, taking in the commuter belt (Brighton,
     * Cambridge, Oxford, Peterborough) so a rider is told at home before setting out, while staying
     * quiet in Birmingham, Bristol or anywhere further (maintainer, 2026-10-09: "people commute from
     * Brighton too").
     */
    fun inLondon(at: Coordinates): Boolean = kmFromCentralLondon(at) <= LONDON_RADIUS_KM

    /**
     * Whether a check should hold its alerts back because [fix] puts the phone away from London
     * ([inLondon]). Only a position known to be elsewhere does: with none (location not allowed all the
     * time, or no fix to be had), alerts fire as they would anyway, so a missing permission never
     * silences them unseen. A fallback fix (a last-known one handed back when a fresh one timed out, up
     * to half an hour old) may be from before the rider crossed the line either way, so it counts as
     * none (Codex on #711). And the whole of the fix's uncertainty has to lie outside: an approximate
     * fix can be kilometers out, and one near the edge (Peterborough sits about a kilometer inside)
     * mustn't silence a rider who is really in range (Codex on #711).
     */
    fun awayFromLondon(fix: LocationFix?): Boolean {
        if (fix == null || fix.isFallback) return false
        // A fix with no accuracy can't show its uncertainty lies outside, so it's unknown too (Codex on #711).
        val accuracy = fix.accuracyMeters?.takeIf { it >= 0f } ?: return false
        val uncertaintyKm = accuracy / 1000.0
        return kmFromCentralLondon(fix.coordinates) - uncertaintyKm > LONDON_RADIUS_KM
    }

    const val LONDON_RADIUS_KM = 120.0

    // Charing Cross, where distances from London are traditionally measured.
    private val CENTRAL_LONDON = Coordinates(51.5073, -0.1276)

    // The great-circle distance, on a sphere of the Earth's mean radius: within a fraction of a percent here.
    private fun kmFromCentralLondon(at: Coordinates): Double {
        val lat1 = Math.toRadians(CENTRAL_LONDON.latitude)
        val lat2 = Math.toRadians(at.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(at.longitude - CENTRAL_LONDON.longitude)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * Math.asin(Math.sqrt(h))
    }

    /** The zone a schedule is read in: the device's, as a place's chip days are. */
    fun zone(): ZoneId = ZoneId.systemDefault()

    /** [instant] in [zone], for the schedule checks above. */
    fun at(instant: Instant, zone: ZoneId = zone()): ZonedDateTime = instant.atZone(zone)
}
