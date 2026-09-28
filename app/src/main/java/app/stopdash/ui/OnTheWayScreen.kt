package app.stopdash.ui

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.OnTheWay
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.Staleness
import app.stopdash.domain.cleanStopName
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * A trip on the way (SPEC *On the way*): the next step over the route, leg by leg, the leg the rider
 * is on marked, with **End trip**. Renders from the tracker's [trip] and [progress] alone; the caller
 * refreshes them about every 30 s while it's shown. [failed] says the last refresh couldn't reach TfL,
 * so the step isn't passed off as current. Its live details (due, stops left, get off soon) show
 * only while [current]: back after a while away, they wait for the next answer. Arrived ([trip]
 * null), it says so, and Done closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnTheWayScreen(
    trip: ActiveTrip?,
    progress: TripProgress?,
    failed: Boolean,
    now: Instant,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    current: Boolean = true,
    // The trip couldn't be saved on the device, so a restart may lose it or bring it back out of date: said, not hidden.
    notKept: Boolean = false,
    // End trip couldn't forget the trip on the device, so it's still on the way.
    endFailed: Boolean = false,
    // Notifications are off for the app, so "get off soon" can't alert: said here, not left to be
    // found out at the stop (SPEC principle 2).
    alertsOff: Boolean = false,
    // Android refused to follow the trip with the app closed ([app.stopdash.OnTheWayService.refused]):
    // it's followed only while the app is open, and reopening it tries again.
    appOpenOnly: Boolean = false,
    // Every train at the next boarding stop that takes the rider on ([rememberNextTrains]): shown
    // while they walk, change or wait for it; null while riding.
    nextTrains: NextTrains? = null,
) {
    BackHandler(onBack = onBack)
    val destination = trip?.destinationName
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Text(
                        destination?.let { stringResource(R.string.on_the_way_title, it) } ?: stringResource(R.string.on_the_way),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        bottomBar = {
            // Above the system navigation bar: the app draws edge to edge.
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                if (trip == null) {
                    Button(onClick = onBack, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.on_the_way_done)) }
                } else {
                    OutlinedButton(onClick = onEnd, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.on_the_way_end)) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("onTheWay"),
        ) {
            item(key = "next") { NextStep(progress, now, current) }
            // The next ride's trains go under its own row below (maintainer, 2026-09-28); here only
            // if that ride isn't among the legs still ahead, so they're never lost.
            val nextAt = if (nextTrains != null && trip != null) {
                trip.route.legs.indices.firstOrNull { it >= trip.legIndex && trip.route.legs[it] == nextTrains.ride }
            } else {
                null
            }
            if (nextTrains != null && trip != null && nextAt == null) item(key = "nextTrains") { NextTrainsSection(nextTrains, now) }
            if (endFailed && trip != null) {
                item(key = "endFailed") {
                    Text(
                        stringResource(R.string.on_the_way_end_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (notKept && trip != null) {
                item(key = "notKept") {
                    Text(
                        stringResource(R.string.on_the_way_not_kept),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (appOpenOnly && trip != null) {
                item(key = "appOpenOnly") {
                    Text(
                        stringResource(R.string.on_the_way_app_open_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (failed) {
                item(key = "failed") {
                    Text(
                        stringResource(R.string.on_the_way_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (alertsOff && trip != null) {
                item(key = "alertsOff") {
                    Text(
                        stringResource(R.string.on_the_way_alerts_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trip != null) {
                trip.route.legs.forEachIndexed { index, leg ->
                    item(key = "leg$index") {
                        LegLine(leg, trip.route.rides, current = index == trip.legIndex, done = index < trip.legIndex)
                    }
                    if (index == nextAt && nextTrains != null) item(key = "nextTrains") { NextTrainsSection(nextTrains, now) }
                }
            }
        }
    }
}

/**
 * The trains at the boarding stop of the rider's next ride that take them where they get off
 * (maintainer, 2026-09-28): every line of the ride's mode, not just the Planner's, each a way to the
 * same stop ([OnTheWay.boardTrains]). [pending] while a line's route is still loading and
 * [unresolved] when a train couldn't be checked, so a short or empty list is never passed off as the
 * whole answer; [stale] when the last board is too old to stand behind (D4), when its times give way
 * to "Updating…"; [failed] when the last update couldn't reach TfL, said beside whatever is shown.
 * A train due before [readyAt] (the rider still walking or changing) is grayed: listed, but not one
 * they can catch.
 */
data class NextTrains(
    val ride: TripLeg,
    val trains: List<Departure>,
    val pending: Boolean = false,
    val unresolved: Boolean = false,
    val stale: Boolean = false,
    val failed: Boolean = false,
    val readyAt: Instant? = null,
    // When the board was read: its rows' age, as the departures board ages a stop's.
    val fetchedAt: Instant? = null,
)

/**
 * [board] ([ActiveTripTracker.nextBoard]) as [NextTrains] at [now]: its trains kept to those whose
 * line's route reaches the ride's alighting stop, from the same route data the trip's cards check
 * against ([rememberLineSequences]). Null with no board.
 */
@Composable
internal fun rememberNextTrains(
    board: ActiveTripTracker.NextBoard?,
    now: Instant,
    readyAt: Instant? = null,
    // The ride ahead ([OnTheWay.upcomingRide]): its section shows at once, "Loading" until its first
    // board is in, rather than appear only once TfL answers.
    ride: TripLeg? = board?.ride,
): NextTrains? {
    val lineIds = board?.let { OnTheWay.boardLineIds(it.ride, it.departures) }.orEmpty()
    val sequences = rememberLineSequences(lineIds, now)
    // No board of this ride's yet (just started, or back after a restart): its section, loading.
    if (board == null || board.ride != ride) return ride?.let { NextTrains(it, emptyList(), pending = true, readyAt = readyAt) }
    // Never read: nothing to show but that the update failed.
    val fetchedAt = board.fetchedAt ?: return NextTrains(board.ride, emptyList(), failed = board.failed, readyAt = readyAt)
    val found = OnTheWay.boardTrains(board.ride, board.departures, fetchedAt, sequences, now)
    // A train its route couldn't place, logged where every trip filter logs it, so "Couldn't check
    // every line" can be explained.
    val routes = LocalRouteStops.current
    LaunchedEffect(routes, found.misses) { routes?.reportMisses(found.misses) }
    val stale = Staleness.isStale(Duration.between(fetchedAt, now).toKotlinDuration())
    return NextTrains(board.ride, found.trains, pending = found.pending, unresolved = found.unresolved, stale = stale, failed = board.failed, readyAt = readyAt, fetchedAt = fetchedAt)
}

/**
 * The next ride's trains ([NextTrains]) under that ride's own row, drawn as the departures board
 * draws a stop (maintainer, 2026-09-28): its platform header over the board's own card, a row per
 * line and terminus with its next few times, so they read as the board rather than as another step.
 * A time due before the rider can be there is grayed. Nothing to open from here: a row takes no tap
 * and announces no action.
 */
@Composable
private fun NextTrainsSection(next: NextTrains, now: Instant) {
    val groups = remember(next, now) {
        StopGrouping.groupByStop(
            DepartureRows.forStop(next.ride.fromId, next.ride.fromName, next.trains, now, fetchedAt = next.fetchedAt ?: now),
        )
    }
    Column(Modifier.fillMaxWidth().testTag("onTheWayTrains"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A failed update is said whatever else shows: the rows may be the last good board's.
        if (next.failed) NoteText(stringResource(R.string.on_the_way_failed))
        if (next.stale) {
            if (!next.failed) NoteText(stringResource(R.string.on_the_way_updating))
            return@Column
        }
        if (next.failed && next.trains.isEmpty()) return@Column
        groups.forEach { group ->
            StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = false)
            StopGroupCard(
                group,
                now,
                starred = emptySet(),
                onToggleStar = {},
                starringAvailable = false,
                onOpenDetail = null,
                grayBefore = next.readyAt,
            )
        }
        // What the rows may be missing, said rather than left to be taken as the whole answer.
        when {
            next.pending && groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_loading))
            next.pending -> NoteText(stringResource(R.string.on_the_way_trains_checking))
            next.unresolved -> NoteText(stringResource(R.string.on_the_way_trains_unchecked))
            groups.isEmpty() -> NoteText(stringResource(R.string.on_the_way_trains_none, next.ride.toName))
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The card at the top: what the rider does next, from [progress]. */
@Composable
private fun NextStep(progress: TripProgress?, now: Instant, current: Boolean) {
    val (title, detail) = nextStepText(progress, now, current)
    Card(colors = nextStepColors(progress, current), modifier = Modifier.fillMaxWidth().testTag("onTheWayNext")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.on_the_way_next), style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The next step's card colors: "get off soon" stands out, the one step with a deadline a stop away. */
@Composable
internal fun nextStepColors(progress: TripProgress?, current: Boolean = true): CardColors =
    if (progress is TripProgress.Riding && progress.getOffSoon && current) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    }

/** What the rider does next, as a title and a detail line — the trip's screen and its banner alike. */
@Composable
internal fun nextStepText(progress: TripProgress?, now: Instant, current: Boolean = true): Pair<String, String> {
    LocalConfiguration.current // Read again on a configuration change (locale, font scale).
    return nextStepText(LocalContext.current.resources, progress, now, current)
}

/** [nextStepText] from [resources], for the trip's ongoing notification too. */
internal fun nextStepText(resources: Resources, progress: TripProgress?, now: Instant, current: Boolean = true): Pair<String, String> {
    // A train's time or stops from an answer too old to stand behind ([current]): the step stays,
    // its details wait for the next answer.
    val live = progress is TripProgress.Riding || (progress is TripProgress.Waiting && progress.due != null)
    if (live && !current) return nextStepText(resources, progress, now).first to resources.getString(R.string.on_the_way_updating)
    return when (progress) {
        is TripProgress.Waiting -> resources.getString(R.string.on_the_way_board, progress.leg.lineName, progress.leg.fromName) to
            (progress.due?.let { resources.getString(R.string.on_the_way_due, minutesUntil(now, it)) } ?: resources.getString(R.string.on_the_way_finding))
        is TripProgress.Riding -> resources.getString(R.string.on_the_way_get_off, progress.leg.toName) to
            when (val left = progress.stopsLeft) {
                null -> resources.getString(R.string.on_the_way_next_is, progress.nextStop)
                0, 1 -> resources.getString(R.string.on_the_way_next_stop)
                else -> resources.getQuantityString(R.plurals.on_the_way_stops, left, left, progress.nextStop)
            }
        is TripProgress.Changing -> resources.getString(R.string.on_the_way_change, progress.leg.lineName, progress.leg.fromName) to
            resources.getString(R.string.on_the_way_change_time, minutesUntil(now, progress.until))
        is TripProgress.Walking -> resources.getString(R.string.on_the_way_walk, progress.leg.toName) to
            resources.getString(R.string.on_the_way_walk_time, minutesUntil(now, progress.until))
        is TripProgress.Lost -> resources.getString(R.string.on_the_way_lost) to resources.getString(R.string.on_the_way_finding)
        TripProgress.Arrived -> resources.getString(R.string.on_the_way_arrived) to ""
        null -> resources.getString(R.string.on_the_way) to resources.getString(R.string.on_the_way_finding)
    }
}

/**
 * One leg of the route: its line and ends, the leg the rider is on in bold, done legs muted. A walk
 * shows a walker in the room the [rides]' pills take, so its text lines up with theirs, as on a trip
 * card.
 */
@Composable
private fun LegLine(leg: TripLeg, rides: List<TripLeg>, current: Boolean, done: Boolean) {
    val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leg.isWalk) {
            // The route's pills, unseen, give the slot its width in the same pass.
            Box(contentAlignment = Alignment.Center) {
                rides.forEach { LinePill(it.lineName, it.lineId, it.mode, Modifier.alpha(0f).clearAndSetSemantics {}) }
                Icon(
                    painter = painterResource(R.drawable.ic_walk),
                    contentDescription = stringResource(R.string.on_the_way_walk_leg),
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            LinePill(leg.lineName, leg.lineId, leg.mode)
        }
        Text(
            // The walk from where the rider set off names only where it goes.
            if (leg.fromName.isBlank()) stringResource(R.string.on_the_way_walk, leg.toName) else stringResource(R.string.on_the_way_leg, leg.fromName, leg.toName),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Whole minutes from [now] to [at], rounded up and never below zero: "0 min" is due now.
internal fun minutesUntil(now: Instant, at: Instant): Int {
    val seconds = Duration.between(now, at).seconds.coerceAtLeast(0)
    return ((seconds + 59) / 60).toInt()
}
