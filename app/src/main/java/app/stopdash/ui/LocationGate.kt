package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.stopdash.R
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
 *   **Update available** button at the bottom when [updateAvailable] (the overflow that carries it
 *   is past the gate).
 * - [NoLocation][NearbyStopsViewModel.State.NoLocation] / [Empty][NearbyStopsViewModel.State.Empty]
 *   / [Failed][NearbyStopsViewModel.State.Failed] — the reason and a **Try again**.
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
    // it, or whose fix or lookup failed, can still look a station up. Null hides the button.
    onFindStation: (() -> Unit)? = null,
    // "No stops found nearby" was worked out from a coarse (network) fix, which can be hundreds of
    // meters out, so it says so until a precise fix confirms or replaces it (SPEC *Finding stops*).
    approximate: Boolean = false,
    // The clock the pinned trip card reads (a ticking one from the caller, so an old answer turns
    // to "Updating…" while the gate stays up); default for a test.
    now: Instant = Instant.now(),
) {
    // Saved so an open About dialog survives rotation on the gate.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // fillMaxSize before verticalScroll keeps the column's min height at the viewport, so the
    // content stays centered when it fits (unchanged look) but scrolls instead of clipping when a
    // short viewport + large font scale make it taller than the screen — the stuck states now
    // carry a third action (Send bug report), which can tip a landscape/160% layout over (Codex
    // P2 on #86). Matches MainScreen's Centered idiom.
    val scrollState = rememberScrollState()
    val locating = state == NearbyStopsViewModel.State.Locating
    Column(
        modifier = modifier
            .fillMaxSize()
            // The gate is drawn edge to edge with nothing above it insetting for the system bars,
            // so the viewport steps inside them here: otherwise the scroll cue's chevrons would
            // sit under the gesture handle and the status bar (Codex on #244).
            .windowInsetsPadding(WindowInsets.safeDrawing)
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
            // A trip on the way stays a tap away when near me can't come up (SPEC *On the way*).
            LocalOnTheWayBanner.current?.let { OnTheWayBanner(it, now, Modifier.padding(bottom = 24.dp)) }
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
                    Body(stringResource(R.string.location_no_stops))
                    if (approximate) Body(stringResource(R.string.location_coarse))
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
            // Always present, below the state's own action: the one way to reach the app version and
            // open-source attribution while stuck on the gate.
            TextButton(onClick = { showAbout = true }, modifier = Modifier.padding(top = 24.dp)) {
                Text(stringResource(R.string.menu_about))
            }
        }
        // The gate has no overflow menu, so this button is the only update affordance here.
        if (locating) UpdateAvailableButton(onClick = onOpenAppListing, modifier = Modifier.padding(top = 24.dp), shown = updateAvailable)
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
}
