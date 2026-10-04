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
    // What follows the step ([OnTheWay.etaTail] goes over the route's legs) and the card's words worked
    // out on the worker, never in composition (AGENTS.md *Main thread*; Codex, #526). The last frame
    // stands in while the next is worked out, but only for the same step ([sameTrip]); the step's
    // details and the arrival are timed from the live state at once, which is cheap, so a changed
    // prediction shows as it comes, never the frame's older one, and the card never blinks to a
    // placeholder for it (Codex, #526). Until the first frame is in, the card shows without a time.
    val slot = remember { mutableStateOf<Worked<Inputs, BannerFrame>?>(null) }
    // Its words ([destinationTitleForms], [stepTitleForms] shorten places) in the configuration's
    // language: a new configuration is another key.
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    // Not keyed on the clock or the update's time: nothing in the frame changes with them (the step's
    // titles never read the time, only its details do, drawn live below), so a tick never runs it again
    // (Codex, #526).
    // Nor on a new prediction: at the same step ([sameTrip]) the frame in hand is the one wanted, its
    // words and what follows the leg the same, so a refresh never runs it again either (Codex, #526).
    val wanted = Inputs(state.trip, state.progress, configuration)
    val key = slot.value?.key?.takeIf { sameTrip(it, wanted) } ?: wanted
    val frame = rememberWorked(slot, key, keep = ::sameTrip) {
        BannerFrame(
            OnTheWay.etaTail(state.trip),
            destinationTitleForms(resources, state.trip.destinationName),
            // Both ways, as whether the answer still stands is judged against the live clock below.
            stepTitleForms(resources, state.progress, now, current = true),
            stepTitleForms(resources, state.progress, now, current = false),
        )
    }
    // None until the frame is in: the placeholder card says only "On the way", never a step's stops or
    // time without the step they belong to (Codex, #526). Once it's in, the live step, which [sameTrip]
    // holds to the frame's.
    val progress = state.progress.takeIf { frame != null }
    // Judged against the live clock and the live state's own update: an answer it has since withdrawn
    // (a failed refresh, a step moved on by hand) isn't current (Codex, #526).
    val current = ActiveTripTracker.isCurrent(state.updatedAt, now)
    // Held back, as on the trip screen, while the answer it's from is too old to stand behind.
    val stale = !current && fromTfl(progress)
    NextStep(
        state.trip.destinationName,
        // Timed from the live progress and clock ([OnTheWay.etaFrom]), so it counts down and drops once
        // what it's timed from has gone by, as the trip screen's does (Codex, #526).
        frame?.etaTail?.let { tail -> OnTheWay.etaFrom(state.trip, state.progress, now, tail) }?.takeIf { !stale },
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
        // The train followed: another picked (an alternative service) is another instruction (Codex, #526).
        a.vehicleId == b.vehicleId && a.vehicleLeg?.lineId == b.vehicleLeg?.lineId &&
        sameStep(held.parts[1] as TripProgress?, wanted.parts[1] as TripProgress?) &&
        held.parts[2] === wanted.parts[2]
}

// Whether two progresses are the same step: the same kind, on the same line, and for a ride, its train
// seen boarded, "get off soon" and counted from TfL or from where the rider was seen alike, which choose
// its words ([nextStepText]) and whether it can go stale ([fromTfl]; Codex, #526).
private fun sameStep(a: TripProgress?, b: TripProgress?): Boolean = when (a) {
    // Its predictions (a time, stops, a due train), there or not, don't choose its words or what follows
    // the leg, and are drawn from the live state: a time TfL adds or withdraws never drops the card to
    // its placeholder (Codex, #526).
    is TripProgress.Riding -> b is TripProgress.Riding && a.leg.lineId == b.leg.lineId &&
        a.seen == b.seen && a.getOffSoon == b.getOffSoon && a.byPosition == b.byPosition
    is TripProgress.Waiting -> b is TripProgress.Waiting && a.leg.lineId == b.leg.lineId && a.lineName == b.lineName
    else -> a?.javaClass == b?.javaClass
}

/**
 * What the pinned card works out on the worker for a step: what follows it for the arrival
 * ([etaTail], null with no current leg), the place's forms, and the step's while its answer stands
 * ([titleForms]) and once it doesn't ([staleTitleForms]).
 */
internal class BannerFrame(
    val etaTail: OnTheWay.EtaTail?,
    val destinationForms: List<String>,
    val titleForms: List<String>,
    val staleTitleForms: List<String>,
)
