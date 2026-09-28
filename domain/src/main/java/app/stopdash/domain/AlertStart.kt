package app.stopdash.domain

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/**
 * When a line alert's disruption starts, read from TfL's own text — the only place it is
 * written. TfL's `validityPeriods.fromDate` is when the alert was *posted* (a closure from 13 Oct
 * is dated from the day it was announced), and `isNow` marks "unplanned", not "current", so
 * neither can tell planned work that hasn't started yet from work under way (SPEC *Disruptions*).
 *
 * The text states dates in many shapes — "from 13 Oct 07:00", "From 08:00 Monday 20 July",
 * "on 24 October and 31 October", "nightly 20:00-05:00 12-17 October", "Until Monday 23
 * November". What they share is that the **first** date names either a start ("from", "on", a
 * bare date or the first of a range) or an end ("until", "to"): work that is only bounded by an
 * end is already under way. So [startDate] reads the first date only, and only when it is a
 * start; everything else — no date, an end first, a shape not recognized — is null, which the
 * caller treats as current. The guess therefore errs toward showing an alert as a disruption, the
 * safe side (SPEC principle 1): a disruption shown a day early beats one hidden while it runs.
 */
object AlertStart {
    /** London: TfL writes its dates in local time. */
    val ZONE: ZoneId = ZoneId.of("Europe/London")

    /**
     * The day [text]'s disruption starts, when its first date is a start; else null. TfL rarely
     * writes the year, so a missing one is the one nearest the day the alert was posted
     * ([postedAt], TfL's `fromDate`) — work is announced around when it starts. Without that, the most recent past one within [PAST_MONTHS], else the next: a date
     * half a year back is far likelier to be work under way than work a half-year off, and taking
     * it as under way is the safe mistake.
     */
    fun startDate(text: String?, now: Instant, postedAt: Instant? = null): LocalDate? {
        if (text.isNullOrBlank()) return null
        val match = DATE.find(text) ?: return null
        // A numeric date before it ("until 12/10/2026") is a first date this can't read: its role is
        // unknown, so the alert stays under way rather than trusting a later one (Codex, PR #337).
        if (NUMERIC_DATE.containsMatchIn(text.substring(0, match.range.first))) return null
        // Only a date the text affirmatively starts from counts: one after a start word ("from",
        // "on", "starting"), or the first of a range or list ("12-17 October"). Anything else —
        // "until", "expected to finish by", wording not foreseen here — is left as under way
        // (Codex, PR #337): a missed start keeps the ⚠, a misread end would hide it.
        if (!startsHere(text.substring(0, match.range.first), isRange = match.groupValues[2].isNotBlank())) return null
        val day = match.groupValues[1].toInt()
        val month = monthOf(match.groupValues[3]) ?: return null
        val year = match.groupValues[4].toIntOrNull()
        val today = now.atZone(ZONE).toLocalDate()
        if (year != null) return dateOrNull(year, month, day)
        val candidates = listOf(today.year - 1, today.year, today.year + 1).mapNotNull { dateOrNull(it, month, day) }
        // The year nearest the day TfL posted it: work is announced around when it starts, just
        // before or, as often, soon after (Codex, PR #337: "from 1 September" posted on the 14th
        // is this September, not next).
        val posted = postedAt?.atZone(ZONE)?.toLocalDate()
        if (posted != null) return candidates.minByOrNull { Math.abs(it.toEpochDay() - posted.toEpochDay()) }
        return candidates.lastOrNull { !it.isAfter(today) && it.isAfter(today.minusMonths(PAST_MONTHS)) }
            ?: candidates.firstOrNull { it.isAfter(today) }
    }

    /**
     * Whether [text]'s disruption hasn't started by [now]: its start day ([startDate]) is after
     * today in London. Judged by the day, not the hour, so work starting later today already counts
     * as under way.
     */
    fun isUpcoming(text: String?, now: Instant, postedAt: Instant? = null): Boolean =
        startDate(text, now, postedAt)?.isAfter(now.atZone(ZONE).toLocalDate()) == true

    private const val PAST_MONTHS = 9L

    // Whether the text [before] a date introduces it as a start. One rule for every shape of date
    // (Codex, PR #337): text that speaks of an end ("until", "ending", "expected to finish")
    // makes it an end, whatever follows; otherwise a start word right before it, past any weekday
    // or time ("from 08:00 Monday", "closed on"), or the first of a range ("12-17 October").
    private fun startsHere(before: String, isRange: Boolean): Boolean {
        // An end anywhere before the date, in its own clause or an earlier one, makes the alert under
        // way: "Service suspended until further notice. Replacement buses start from 13 October." is
        // a current suspension with a later change, not work that hasn't begun (Codex, PR #337).
        if (END_CLAUSE.containsMatchIn(before.replace(PLACE_END, ""))) return false
        val trimmed = before.replace(LEAD_IN, "")
        return isRange || START_WORD.containsMatchIn(trimmed) || ON.containsMatchIn(trimmed)
    }

    private fun dateOrNull(year: Int, month: Month, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun monthOf(name: String): Month? =
        Month.entries.firstOrNull { it.name.startsWith(name.take(3).uppercase()) }

    private const val WEEKDAY = "(?:mon|tues?|wed(?:nes)?|thu(?:rs?)?|fri|sat(?:ur)?|sun)(?:day)?"
    // "May" is the month only where a date would go on: a year, a time, punctuation, the end, or a
    // joining word ("3 May 2027", "3 May, 08:00", "3 May until"). Otherwise it is the modal ("Route 1
    // may miss stops from 13 October") — Codex, PR #337. A missed May date keeps the ⚠, the safe side.
    private const val MONTH =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may(?=\\s*(?:$|[.,;:)!?]|-|–|\\d|(?:and|until|till|to|from|at|between|for|onwards|inclusive)\\b))|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    // Not the minutes of a time ("08:00, Monday 20 July" starts on the 20th, not the "00").
    private const val DAY = "(?<![:.\\d])(\\d{1,2})(?:st|nd|rd|th)?"
    private const val TIME = "\\d{1,2}[:.]\\d{2}"

    // A day, or the first of a list or range of days ("12-17", "12 to 17", "28, 29 and 30", "3 and Sunday 4"),
    // then its month and an optional year: groups day, the rest of the list or range, month, year.
    // The first day is the one kept: it is the start.
    private val DATE = Regex(
        "\\b$DAY((?:\\s*(?:,|and|&|-|–|to)\\s*(?:$WEEKDAY\\s+)?\\d{1,2}(?:st|nd|rd|th)?)*)\\s+($MONTH)\\b\\.?(?:,?\\s+(\\d{4})\\b)?",
        RegexOption.IGNORE_CASE,
    )

    // What may sit between a start or end word and its date: a weekday, a time or time range,
    // "at", "the", commas ("from 08:00, Monday 20 July"). "on" is read apart ([startsHere]).
    private val LEAD_IN = Regex(
        "(?:\\s|,|\\bat\\b|\\bthe\\b|\\b$WEEKDAY\\b|$TIME(?:\\s*(?:-|–|to)\\s*$TIME)?)*$",
        RegexOption.IGNORE_CASE,
    )

    // "12/10", "12/10/2026", "12.10.26": a day and month in figures.
    private val NUMERIC_DATE = Regex("(?<![\\d:])\\d{1,2}[/.]\\d{1,2}(?:[/.]\\d{2,4})?(?![\\d:])")

    // "End" in a place's name ("West End Lane", "Mile End") says nothing of an end (Codex, PR #337):
    // a title-case word then "End", case-sensitive, so "works end on" and "WORKS END ON" still count,
    // and never after a word for the work itself or a verb before it ("Works End on", "Will End on"
    // are its end). All-caps text isn't read as a place: there "END" can't be told from the verb,
    // and a ⚠ shown early is the safe mistake (Codex, PR #337).
    private val PLACE_END = Regex("(?<=\\b(?!(?:Works|Work|Closure|Closures|Diversion|Diversions|Service|Services|Suspension|Suspensions|Engineering|Will|Would|Shall|Should|Must|May|Might|Could|Can|To|Expected|Due|Scheduled|Planned)\\s)[A-Z][a-z']{1,20}\\s)End\\b")

    private val ON = Regex("\\bon\\s*$", RegexOption.IGNORE_CASE)

    // Any of these before a date makes it an end ("until 17:00 on Tuesday",
    // "ending on", "expected to finish on", "reopening on", "service resumes on", "normal service
    // returns on"), or dates only the next update ("next update on"). Not "normal service" alone:
    // "normal service will be suspended from" is a start (Codex, PR #337). Not "to", "by",
    // "due" or "through": "closed to traffic on", "due to works on" are starts. Nor "expected",
    // which says nothing either way ("expected to start on"), nor "complete" describing what is
    // closed ("complete closure from", "complete line closure from") — both Codex, PR #337.
    private val END_CLAUSE = Regex(
        "\\b(?:until|till|til|end|ends|ended|ending|finish|finishes|finished|finishing|" +
            "complete(?!(?:\\s+[a-z-]+){0,2}?\\s+(?:closure|closures|suspension|shutdown)\\b)|completes|completed|completing|completion|" +
            "update|updates|updated|review|reviewed|publish|published|announce|announced|confirm|confirmed|" +
            "lift|lifts|lifted|lifting|" +
            "reopen|reopens|reopened|reopening|restart|restarts|restarted|restarting|recommence|recommences|recommenced|recommencing|open|opens|opened|opening|resume|resumes|resumed|resuming|resumption|" +
            "restore|restores|restored|restoring|return|returns|returned|returning|reinstate|reinstates|reinstated|reinstating|" +
            "back to normal|expire|expires)\\b",
        RegexOption.IGNORE_CASE,
    )

    // A first date right after one of these is where the work starts.
    private val START_WORD = Regex(
        "\\b(?:from|starting|starts|start|beginning|begins|begin|commencing|commences|commence|between)(?:\\s+on)?\\s*$",
        RegexOption.IGNORE_CASE,
    )
}
