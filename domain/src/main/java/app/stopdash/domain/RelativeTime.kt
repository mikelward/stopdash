package app.stopdash.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How long ago data was fetched, for the age stamp a surface carries (SPEC *Freshness*): "Just now",
 * "2 min ago". Pure and clock-free — the caller supplies the age — so it is JVM-testable without
 * Android. It says which bucket the age falls in and how many whole units; the words are the app's
 * string resources, so they follow the app's language.
 *
 * This is the freshness *stamp*, not the staleness *policy* ([Staleness]), the single threshold past
 * which numbers are withheld.
 */
object RelativeTime {
    fun of(age: Duration): RelativeAge = when {
        age < 60.seconds -> RelativeAge.JustNow
        age < 60.minutes -> RelativeAge.MinutesAgo(age.inWholeMinutes)
        else -> RelativeAge.HoursAgo(age.inWholeHours)
    }
}

/** An age as [RelativeTime.of] buckets it: under a minute, whole minutes, or whole hours. */
sealed interface RelativeAge {
    data object JustNow : RelativeAge
    data class MinutesAgo(val minutes: Long) : RelativeAge
    data class HoursAgo(val hours: Long) : RelativeAge
}
