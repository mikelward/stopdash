package app.stopdash.ui

import androidx.annotation.WorkerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import app.stopdash.domain.Coordinates
import app.stopdash.domain.Journeys
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.LineRef

/**
 * The saved journeys as the list shows them ([shownJourneysOf]): nearest first, each turned so its
 * origin is the end nearer the rider, or the other way if they flipped it ([journeys]), and those held
 * back as collapsed cards until tapped, with each one's meters to its nearer end ([farMeters], SPEC
 * *Journeys*), the lines its card names, less any the rider hid from it or with no name to show ([farLines]),
 * and those journeys alone in order ([far]), so the list walks none to find them.
 */
internal class ShownJourneys(
    val journeys: List<FavoriteJourney>,
    val farMeters: Map<String, Double>,
    val farLines: Map<String, List<LineRef>> = emptyMap(),
    val far: List<FavoriteJourney> = emptyList(),
)

/**
 * [ShownJourneys] for [saved] at [at]: nearest first ([Journeys.nearestFirst]), each oriented, the
 * [flipped] ones reversed, and the far ones measured, only on a [fixConfirmed] fix. Walks every saved
 * journey, so on the worker, never in composition (AGENTS.md *Main thread*).
 */
@WorkerThread
internal fun shownJourneysOf(
    saved: List<FavoriteJourney>,
    at: Coordinates,
    flipped: List<String>,
    fixConfirmed: Boolean,
): ShownJourneys {
    val shown = Journeys.nearestFirst(saved, at.latitude, at.longitude).map { journey ->
        val oriented = Journeys.oriented(journey, at.latitude, at.longitude)
        if (journey.key in flipped) oriented.reversed() else oriented
    }
    val far = Journeys.farJourneys(shown, at.latitude, at.longitude, fixConfirmed)
    val farOnes = shown.filter { it.key in far }
    val farLines = farOnes.associate { journey -> journey.key to journey.lines.filter { it.name.isNotBlank() && !journey.hides(it.id) } }
    return ShownJourneys(shown, far, farLines, farOnes)
}

/**
 * The saved journeys as shown ([shown], null until the first answer is in, or while they haven't been
 * read), and whether that answer is for the journeys, fix and flips as they are now ([current]) rather
 * than one standing in while a newer one is worked out. The cards show a standing-in answer; what's
 * written for the widget waits for a current one.
 */
internal class WorkedJourneys(val shown: ShownJourneys?, val current: Boolean)

/**
 * [shownJourneysOf], worked out on [LocalWorker] into [slot]: the last answer stands in while a newer
 * one (the rider moved, a journey saved or flipped) is worked out. Null until the first is in, which
 * the screen reads as the journeys still loading. Null too while [saved] hasn't been read.
 */
@Composable
internal fun rememberShownJourneys(
    slot: MutableState<Worked<Inputs, ShownJourneys>?>,
    saved: List<FavoriteJourney>?,
    at: Coordinates,
    flipped: List<String>,
    fixConfirmed: Boolean,
): WorkedJourneys {
    val inputs = Inputs(saved, at, flipped, fixConfirmed)
    // The last answer stands in, unless it was worked out before the journeys were read: that one
    // says none, which isn't so.
    val worked = rememberWorked(slot, inputs, keep = { held, _ -> held.parts[0] != null }) {
        shownJourneysOf(saved.orEmpty(), at, flipped, fixConfirmed)
    }
    // Without a confirmed fix every journey shows in full, at once: an answer standing in from one
    // still holds none back while the new one is worked out (Codex, #593).
    val shown = remember(worked, fixConfirmed) {
        if (worked != null && !fixConfirmed && worked.farMeters.isNotEmpty()) ShownJourneys(worked.journeys, emptyMap()) else worked
    }.takeIf { saved != null }
    return WorkedJourneys(shown, current = shown != null && slot.value?.key == inputs)
}
