package app.stopdash.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.stopdash.R
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.TripProgress
import java.time.Instant

/**
 * A trip on the way, for the card pinned atop the main view: the [trip], where it stands, when that
 * was last brought up to date ([updatedAt], see [ActiveTripTracker.isCurrent]), and [onOpen].
 */
class OnTheWayBannerState(val trip: ActiveTrip, val progress: TripProgress?, val updatedAt: Instant?, val onOpen: () -> Unit)

/** The trip on the way, provided by the activity; null with none (or in a test). */
val LocalOnTheWayBanner = compositionLocalOf<OnTheWayBannerState?> { null }

/**
 * The card pinned at the top of the main view while a trip is on the way (SPEC *On the way*): the trip
 * screen's own card ([NextStep]), where it's going and when, then the next step, from the tracker's
 * state alone (no request of its own), so the two read alike (maintainer, 2026-10-04). Its arrival
 * time has no board's next train to go by, which only the trip screen fetches. A tap opens the trip.
 */
@Composable
internal fun OnTheWayBanner(state: OnTheWayBannerState, now: Instant, modifier: Modifier = Modifier) {
    // The arrival worked out on the worker ([OnTheWay.eta] goes over the route's legs), never in
    // composition (AGENTS.md *Main thread*; Codex, #526). The card is drawn from the trip, progress and
    // time it was worked out for, the last standing in while the next is worked out; until the first is
    // in, the card shows without a time.
    val slot = remember { mutableStateOf<Worked<Inputs, BannerFrame>?>(null) }
    // The last frame stands in only for the same trip: a re-plan or a new trip (another route) waits for
    // its own, the card drawn from the current state without a time meanwhile (Codex, #526).
    // Its words too ([destinationTitleForms], [stepTitleForms] shorten places), in the configuration's
    // language: a new configuration is another key.
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    val frame = rememberWorked(slot, Inputs(state.trip, state.progress, now, configuration, state.updatedAt), keep = ::sameTrip) {
        BannerFrame(
            state.trip,
            state.progress,
            state.updatedAt,
            now,
            OnTheWay.eta(state.trip, state.progress, now, null),
            OnTheWay.etaSource(state.trip, state.progress, now)?.first,
            destinationTitleForms(resources, state.trip.destinationName),
            // Both ways, as whether the answer still stands is judged against the live clock below.
            stepTitleForms(resources, state.progress, now, current = true),
            stepTitleForms(resources, state.progress, now, current = false),
        )
    }
    val trip = frame?.trip ?: state.trip
    val progress = frame?.progress ?: state.progress
    // Judged against the live clock, not the frame's: a frame held while the worker is slow mustn't
    // keep an old answer looking current. And by when the frame's own progress was brought up to date,
    // never a newer update's, which the frame doesn't show yet (Codex, #526).
    // And only while the live state stands too: an answer it has since withdrawn (a failed refresh, a
    // step moved on by hand) isn't current however fresh the frame's (Codex, #526).
    val current = ActiveTripTracker.isCurrent(state.updatedAt, now) && (frame == null || ActiveTripTracker.isCurrent(frame.updatedAt, now))
    // Held back, as on the trip screen, while the answer it's from is too old to stand behind.
    val stale = !current && fromTfl(progress)
    // Counted against the live clock: the frame's arrival and the step's times are instants, so a frame
    // held while the worker is busy still counts down, and an arrival gone by drops (Codex, #526).
    NextStep(
        trip.destinationName,
        // Nor once what it's timed from has gone by (a train due, a walk's end), as [OnTheWay.eta] at
        // the live clock would have it (Codex, #526).
        frame?.eta?.takeIf { !stale && it.arrival.isAfter(now) && frame.etaFrom?.isBefore(now) != true },
        progress,
        now,
        current,
        modifier = modifier.clickable(role = Role.Button, onClick = state.onOpen),
        tag = "onTheWayBanner",
        // Until the first frame is in, the card reads "On the way" (a screen reader's label too), its
        // place and time to come (Codex, #526).
        destinationForms = frame?.destinationForms ?: listOf(""),
        titleForms = (if (current) frame?.titleForms else frame?.staleTitleForms) ?: listOf(stringResource(R.string.on_the_way)),
    )
}

// Whether a banner frame worked out for [held] may stand in for [wanted]: the same trip, its route the
// same object (a re-plan or a new trip brings another) and started at the same time, at the same step
// (its leg, whether the rider's on board, and what they're doing on it: a step moved on, Next tapped
// once the train's left included, never shows the last one's words or times), in the same
// configuration (another language's words never stand in; Codex, #526).
internal fun sameTrip(held: Inputs, wanted: Inputs): Boolean {
    val a = held.parts[0] as ActiveTrip
    val b = wanted.parts[0] as ActiveTrip
    return a.route === b.route && a.startedAt == b.startedAt && a.destinationName == b.destinationName &&
        a.legIndex == b.legIndex && a.boarded == b.boarded && a.onBoardSeen == b.onBoardSeen &&
        sameStep(held.parts[1] as TripProgress?, wanted.parts[1] as TripProgress?) &&
        held.parts[3] === wanted.parts[3]
}

// Whether two progresses are the same step: the same kind, and for a ride, its train seen boarded and
// "get off soon" alike, which choose its words ([nextStepText]).
private fun sameStep(a: TripProgress?, b: TripProgress?): Boolean =
    a?.javaClass == b?.javaClass &&
        (a !is TripProgress.Riding || (b is TripProgress.Riding && a.seen == b.seen && a.getOffSoon == b.getOffSoon))

/**
 * The pinned card's arrival ([eta]) and words, and the trip, progress (brought up to date at
 * [updatedAt]) and time [now] they were worked out for: the place's forms, and the step's while its answer stands ([titleForms]) and once it doesn't
 * ([staleTitleForms]).
 */
internal class BannerFrame(
    val trip: ActiveTrip,
    val progress: TripProgress?,
    val updatedAt: Instant?,
    val now: Instant,
    val eta: OnTheWay.Eta?,
    // What [eta] is timed from ([OnTheWay.etaSource]): it stands only while that's still to come.
    val etaFrom: Instant?,
    val destinationForms: List<String>,
    val titleForms: List<String>,
    val staleTitleForms: List<String>,
)
