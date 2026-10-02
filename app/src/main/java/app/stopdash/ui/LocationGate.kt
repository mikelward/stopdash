package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.TripDestination
import java.time.Instant

/**
 * The screen shown until the nearby-stops search resolves (SPEC *Finding stops*): the
 * location gate in front of the departures view. It renders every [NearbyStopsViewModel.State]
 * except [NearbyStopsViewModel.State.Ready] — Ready hands off to `MainScreen` — so each
 * non-happy outcome is an honest, actionable screen rather than a blank or a fake list
 * (SPEC principles 1–2):
 *
 * - [PermissionRequired][NearbyStopsViewModel.State.PermissionRequired] — the rationale
 *   (honest that the position is sent to TfL) and an **Allow location** button.
 * - [Locating][NearbyStopsViewModel.State.Locating] — a spinner, shown at once (SPEC 5), plus an
 *   **Update available** button at the bottom when [updateAvailable], a more direct prompt than the
 *   overflow's dot, as on the departures cold load.
 * - [NoLocation][NearbyStopsViewModel.State.NoLocation] / [Empty][NearbyStopsViewModel.State.Empty]
 *   / [Failed][NearbyStopsViewModel.State.Failed] — the reason and a **Try again**; Empty also says
 *   StopDash shows only London's stops, the usual reason there are none.
 *
 * Every state has the app bar every screen has (maintainer, 2026-10-02): the mark, the name, and
 * the overflow with what makes sense before any stop is found — From…, Settings, Send bug report and
 * About, and "Update available" when there is one.
 *
 * Pure: renders only [state], with no I/O, so the same function drives the app and the
 * screenshot tests.
 */
@Composable
fun LocationGate(
    state: NearbyStopsViewModel.State,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean = false,
    // Open the About dialog (version + the open-source licenses screen). Reachable here too, not
    // only past the gate, so the license attribution isn't stranded when location is denied and
    // departures never resolve (Codex). Default no-op so a screenshot test renders without it.
    onOpenLicenses: () -> Unit = {},
    // Send a bug report from a stuck gate state (no fix, TfL unreachable, nothing nearby, or a
    // permanent denial) — exactly the failures the diagnostic log explains (Codex P2 on #86).
    // Default no-op so a screenshot test renders without it.
    onSendBugReport: () -> Unit = {},
    // True when Google Play reports a newer version: the Locating spinner then offers an "Update
    // available" button, so a user waiting on the nearby-stops fix can update without first reaching
    // the departures overflow (which the gate is in front of). Off by default (and in debug — see
    // PlayUpdateChecker), so the common build/test renders the plain gate.
    updateAvailable: Boolean = false,
    // Open the Play Store listing (from the "Update available" button). Default no-op.
    onOpenAppListing: () -> Unit = {},
    // Open "Find a station" (SPEC *Finding stops*), which needs no location — so a user who denied
    // it, or whose fix or lookup failed, can still look a station up. The overflow's From… too. Null
    // hides both.
    onFindStation: (() -> Unit)? = null,
    // Open StopDash's own Settings, from the overflow ([onOpenSettings] is the system's app settings,
    // for the permission). Null offers no item.
    onOpenStopDashSettings: (() -> Unit)? = null,
    // Open About from the overflow, hosted by the caller above the gate, so a lookup finishing while
    // it's open (the gate giving way to departures) doesn't close it (Codex, #470). Null shows the
    // gate's own dialog, as a test does.
    onOpenAbout: (() -> Unit)? = null,
    // "No stops found nearby" was worked out from a coarse (network) fix, which can be hundreds of
    // meters out, so it says so until a precise fix confirms or replaces it (SPEC *Finding stops*).
    approximate: Boolean = false,
    // The clock the pinned trip card reads (a ticking one from the caller, so an old answer turns
    // to "Updating…" while the gate stays up); default for a test.
    now: Instant = Instant.now(),
    // The saved places to route to from "No stops found nearby", as atop the near-me list (SPEC
    // *Routing from the near-me list*): a trip from here plans from the rider's position, so it needs
    // no stop in range. The caller has already left out those the rider is at and those off today.
    // Shown only there: every other state has no position to plan from.
    places: List<FavoritePlace> = emptyList(),
    onRouteToPlace: (TripDestination.Place) -> Unit = {},
    // A long press on a chip: the saved places' own screen, as on the list. Null offers none.
    onEditPlaces: (() -> Unit)? = null,
) {
    // Saved so an open About dialog survives rotation on the gate.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // A height fixed before verticalScroll (the room under any pinned trip card) keeps the column's
    // min height at the viewport, so the content stays centered when it fits (unchanged look) but
    // scrolls instead of clipping when a short viewport + large font scale make it taller than the
    // screen — the stuck states now carry a third action (Send bug report), which can tip a
    // landscape/160% layout over (Codex P2 on #86). Matches MainScreen's Centered idiom.
    val scrollState = rememberScrollState()
    val locating = state == NearbyStopsViewModel.State.Locating
    // The gate is drawn edge to edge with nothing above it insetting for the system bars, so the
    // viewport steps inside them here: otherwise the scroll cue's chevrons would sit under the
    // gesture handle and the status bar (Codex on #244).
    val banner = LocalOnTheWayBanner.current
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // A trip on the way stays a tap away when near me can't come up (SPEC *On the way*): pinned at
        // the top, where the near-me list pins it (maintainer, 2026-09-29), not centered with the gate.
        // Only where that leaves the gate most of the height: on a short window or with large text the
        // card could squeeze the gate's actions out of reach, so there it scrolls with them, at the top
        // of the gate's content (Codex on #385).
        val pinned = maxHeight - GATE_BAR_HEIGHT >= PINNED_CARD_ROOM * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            GateTopBar(
                updateAvailable = updateAvailable,
                onOpenAppListing = onOpenAppListing,
                onFindStation = onFindStation,
                onOpenStopDashSettings = onOpenStopDashSettings,
                onSendBugReport = onSendBugReport,
                onOpenAbout = onOpenAbout ?: { showAbout = true },
            )
            if (pinned && banner != null) OnTheWayBanner(banner, now, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.background))
                    .verticalScroll(scrollState)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (locating) Arrangement.SpaceBetween else Arrangement.Center,
            ) {
                // The Locating spinner's update offer sits at the bottom, in a slot kept whether or not it
                // shows (with one as tall at the top), so the rest stays centered in one place either way.
                if (locating) UpdateAvailableButton(onClick = {}, modifier = Modifier.padding(bottom = 24.dp), shown = false)
                // Full width, so each item centers across the screen as it did before this group existed.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!pinned && banner != null) OnTheWayBanner(banner, now, Modifier.padding(bottom = 24.dp))
                    when (state) {
                        NearbyStopsViewModel.State.PermissionRequired -> {
                            Title(stringResource(R.string.location_title))
                            if (permanentlyDenied) {
                                // Re-requesting only re-denies, so send the user to Settings instead of
                                // stranding them on a button that can't grant the permission.
                                Body(stringResource(R.string.location_denied))
                                Action(stringResource(R.string.open_settings), onOpenSettings)
                            } else {
                                Body(stringResource(R.string.location_rationale))
                                Action(stringResource(R.string.location_allow), onAllow)
                            }
                        }

                        NearbyStopsViewModel.State.Locating -> {
                            CircularProgressIndicator()
                            Body(stringResource(R.string.location_finding))
                        }

                        NearbyStopsViewModel.State.NoLocation -> {
                            Body(stringResource(R.string.location_no_fix))
                            Action(stringResource(R.string.try_again), onRetry)
                        }

                        is NearbyStopsViewModel.State.Empty -> {
                            // Nothing near is just when a route elsewhere is wanted: the chips head
                            // the state, as they head the list's empty state, centered with the rest.
                            if (places.isNotEmpty()) {
                                FavoriteChips(
                                    places,
                                    onRouteToPlace,
                                    Modifier.padding(bottom = 16.dp),
                                    onEditPlaces = onEditPlaces,
                                    contentPadding = PaddingValues(0.dp),
                                    centered = true,
                                )
                            }
                            Body(stringResource(R.string.location_no_stops))
                            if (approximate) Body(stringResource(R.string.location_coarse))
                            // Most often, nowhere near London (maintainer, 2026-10-02).
                            Body(stringResource(R.string.location_london_only))
                            Action(stringResource(R.string.try_again), onRetry)
                        }

                        is NearbyStopsViewModel.State.Failed -> {
                            Body(stringResource(failureMessage(state.kind)))
                            Action(stringResource(R.string.try_again), onRetry)
                        }

                        // Ready is the caller's cue to show the departures screen, not the gate.
                        is NearbyStopsViewModel.State.Ready -> Unit
                    }
                    // Offered in the states where a fix or lookup actually happened — no fix, nothing nearby,
                    // TfL unreachable — so the diagnostic log (and, for Empty/Failed, the fix) is the point and
                    // departures never resolve to carry the overflow's own item. Not on PermissionRequired
                    // (grant-needed, nothing to diagnose yet — and its permanent-denial isn't reconstructed on
                    // a cold launch) nor the transient Locating spinner.
                    val stuck = when (state) {
                        NearbyStopsViewModel.State.NoLocation,
                        is NearbyStopsViewModel.State.Empty,
                        is NearbyStopsViewModel.State.Failed,
                        -> true
                        else -> false
                    }
                    if (stuck) {
                        TextButton(onClick = onSendBugReport, modifier = Modifier.padding(top = 24.dp)) {
                            Text(stringResource(R.string.menu_send_bug_report))
                        }
                    }
                    if (onFindStation != null) {
                        TextButton(onClick = onFindStation, modifier = Modifier.padding(top = 24.dp)) {
                            Text(stringResource(R.string.menu_find_station))
                        }
                    }
                }
                // A more direct prompt than the overflow's dot while the user waits, as on the cold load.
                if (locating) UpdateAvailableButton(onClick = onOpenAppListing, modifier = Modifier.padding(top = 24.dp), shown = updateAvailable)
            }
        }
    }
    if (showAbout) {
        AboutDialog(
            onOpenLicenses = {
                showAbout = false
                onOpenLicenses()
            },
            onDismiss = { showAbout = false },
        )
    }
}

// The height the gate needs, at the default text size, before the trip card is pinned above it: a
// card of a few lines at the top still leaves a phone's portrait gate room to center in, where a
// landscape phone, a split screen or a large text size gets the card scrolling with the gate.
private val PINNED_CARD_ROOM = 480.dp

// The app bar's height above it all ([GateTopBar]): Material's small top bar, whatever the text size.
private val GATE_BAR_HEIGHT = 64.dp

// The gate's app bar: the mark, the name, and the app's overflow with what makes sense before any
// stop is found. The gate already sits inside the system bars, so the bar adds no insets of its own.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateTopBar(
    updateAvailable: Boolean,
    onOpenAppListing: () -> Unit,
    onFindStation: (() -> Unit)?,
    onOpenStopDashSettings: (() -> Unit)?,
    onSendBugReport: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    TopAppBar(
        title = { AppTitle() },
        navigationIcon = { AppBarMark() },
        windowInsets = WindowInsets(0, 0, 0, 0),
        actions = {
            AppOverflowMenu(updateAvailable, onOpenAppListing) { close ->
                // From… needs no location, so it works whatever kept the stops from being found.
                if (onFindStation != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_from)) },
                        onClick = {
                            close()
                            onFindStation()
                        },
                    )
                }
                if (onOpenStopDashSettings != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        onClick = {
                            close()
                            onOpenStopDashSettings()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_send_bug_report)) },
                    onClick = {
                        close()
                        onSendBugReport()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_about)) },
                    onClick = {
                        close()
                        onOpenAbout()
                    },
                )
            }
        },
    )
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(top = 24.dp)) { Text(text) }
}

private fun failureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.error_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.error_rate_limited
    DeparturesUiState.Error.Kind.NETWORK, DeparturesUiState.Error.Kind.SERVER -> R.string.error_unreachable
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.error_key_rejected
}
