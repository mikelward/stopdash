package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import java.time.Duration
import java.time.Instant

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
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
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
                itemsIndexed(trip.route.legs, key = { index, _ -> "leg$index" }) { index, leg ->
                    LegLine(leg, current = index == trip.legIndex, done = index < trip.legIndex)
                }
            }
        }
    }
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
    // A train's time or stops from an answer too old to stand behind ([current]): the step stays,
    // its details wait for the next answer.
    val live = progress is TripProgress.Riding || (progress is TripProgress.Waiting && progress.due != null)
    if (live && !current) return nextStepText(progress, now).first to stringResource(R.string.on_the_way_updating)
    return when (progress) {
        is TripProgress.Waiting -> stringResource(R.string.on_the_way_board, progress.leg.lineName, progress.leg.fromName) to
            (progress.due?.let { stringResource(R.string.on_the_way_due, minutesUntil(now, it)) } ?: stringResource(R.string.on_the_way_finding))
        is TripProgress.Riding -> stringResource(R.string.on_the_way_get_off, progress.leg.toName) to
            when (val left = progress.stopsLeft) {
                null -> stringResource(R.string.on_the_way_next_is, progress.nextStop)
                0, 1 -> stringResource(R.string.on_the_way_next_stop)
                else -> pluralStringResource(R.plurals.on_the_way_stops, left, left, progress.nextStop)
            }
        is TripProgress.Walking -> stringResource(R.string.on_the_way_walk, progress.leg.toName) to
            stringResource(R.string.on_the_way_walk_time, minutesUntil(now, progress.until))
        is TripProgress.Lost -> stringResource(R.string.on_the_way_lost) to stringResource(R.string.on_the_way_finding)
        TripProgress.Arrived -> stringResource(R.string.on_the_way_arrived) to ""
        null -> stringResource(R.string.on_the_way) to stringResource(R.string.on_the_way_finding)
    }
}

/** One leg of the route: its line and ends, the leg the rider is on in bold, done legs muted. */
@Composable
private fun LegLine(leg: TripLeg, current: Boolean, done: Boolean) {
    val color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leg.isWalk) {
            Text(stringResource(R.string.on_the_way_walk_leg), style = MaterialTheme.typography.labelLarge, color = color)
        } else {
            LinePill(leg.lineName, leg.lineId, leg.mode)
        }
        Text(
            stringResource(R.string.on_the_way_leg, leg.fromName, leg.toName),
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
