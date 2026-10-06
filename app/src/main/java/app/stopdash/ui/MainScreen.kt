@file:OptIn(ExperimentalMaterial3Api::class)

package app.stopdash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.WorkerThread
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.R
import app.stopdash.domain.AlertLinks
import app.stopdash.domain.AlertMarks
import app.stopdash.domain.AlertStart
import app.stopdash.domain.AlertsBehind
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.ClosedNotice
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.Connections
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureLabels
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.domain.DestinationGroup
import app.stopdash.domain.DismissedAlert
import app.stopdash.domain.EmptyTimes
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyCall
import app.stopdash.domain.JourneyChange
import app.stopdash.domain.JourneyEnd
import app.stopdash.domain.JourneySegment
import app.stopdash.domain.JourneyTrains
import app.stopdash.domain.Journeys
import app.stopdash.domain.LineMap
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.NATIONAL_RAIL_MODE
import app.stopdash.domain.NoTimes
import app.stopdash.domain.NoticePlan
import app.stopdash.domain.PlannedAlert
import app.stopdash.domain.PlatformDirection
import app.stopdash.domain.RelativeTime
import app.stopdash.domain.RouteFocus
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.RouteMiss
import app.stopdash.domain.RouteStop
import app.stopdash.domain.RouteStops
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.Staleness
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.StarredRow
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopDistance
import app.stopdash.domain.StopGroup
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.StopLocation
import app.stopdash.domain.StopQualifier
import app.stopdash.domain.TflException
import app.stopdash.domain.TripDestination
import app.stopdash.domain.TripLeg
import app.stopdash.domain.UntimedTrain
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.WidgetJourneyCheck
import app.stopdash.domain.abbreviateBranch
import app.stopdash.domain.cleanDisruptionBody
import app.stopdash.domain.followedDeparture
import app.stopdash.domain.hasTrains
import app.stopdash.domain.isPole
import app.stopdash.domain.lineLabel
import app.stopdash.domain.planNotices
import app.stopdash.domain.routeDepartures
import app.stopdash.domain.routeUntimed
import app.stopdash.domain.serviceName
import app.stopdash.domain.stopPlaceKey
import app.stopdash.domain.takesLineSuffix
import app.stopdash.telemetry.UsageEvents
import app.stopdash.ui.theme.LocalStarredBorderColor
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tag on the red "update available" dot overlaying the overflow menu icon. */
internal const val UPDATE_AVAILABLE_DOT_TAG = "update_available_dot"

/**
 * The departures view (SPEC D8): a flat list, one row per (service, stop, direction),
 * soonest-first across the watched stops. Pure — it renders only [state] and the
 * caller-supplied [now], with no I/O in composition (SPEC jank-free UI), so the same
 * function drives the app and the screenshot tests.
 *
 * The rows are recomputed from the snapshot's raw stops against [now], not cached from
 * fetch time, so a departed service leaves the list and the ordering advances as the
 * clock ticks (SPEC D4). Once the snapshot is [Staleness]-stale the countdowns are
 * withheld — the prediction set is likely wrong, so the honest answer is "refresh"
 * rather than live-looking numbers.
 */
@Composable
fun MainScreen(
    state: DeparturesUiState,
    now: Instant,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    // The app bar's crosshairs: "use my location". Null runs [onRefresh], which on the near-me list
    // and a trip from here re-locates; a From… station page passes a return to the near-me list.
    onLocate: (() -> Unit)? = null,
    // A pull-to-refresh (and the list's own Refresh buttons): the user asking for fresh times, so the
    // caller fetches every stop afresh rather than reuse a recent fetch (SPEC *Freshness → Shared
    // arrivals*). Null runs [onRefresh].
    onPullRefresh: (() -> Unit)? = null,
    // The full list's scroll position. Hoisted by the caller above the route page and the overlays,
    // which take this screen (or its list) out of composition, so a return lands where it was.
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    // From "near me now" (`stopId` → meters): collapse a line served by several adjacent
    // nearby stops to its nearest stop. Empty for a location-free list, shown unchanged.
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    // Each line's stop nearest the rider within walking reach, both tiers' (line id → stop id), which a
    // line's map keeps on the page. Empty for a location-free list.
    nearestStops: Map<String, String> = emptyMap(),
    // Shows a near-me stop (`stopId`, place name) in the maps app, from a tap on its header's
    // distance; null leaves the distance inert.
    onOpenStopMap: ((String, String) -> Unit)? = null,
    // The starred journeys (SPEC *Journeys*), each already turned so its origin is the end nearer the
    // rider: shown as cards atop the near-me list, and starrable from a route page's stop list.
    journeys: List<StarredJourney> = emptyList(),
    // The journeys more than a mile from the rider (key → meters to the nearer end): held behind the
    // Faraway favorites button, and not fetched until it's tapped ([Journeys.farJourneys]).
    farJourneyMeters: Map<String, Double> = emptyMap(),
    // The nearby stop set shown ([NearbyStopsViewModel.State.Ready.clusterSetKey]): a tap on Faraway
    // favorites holds for this set only, so a relocation elsewhere holds far journeys back again.
    nearbyKey: String? = null,
    // The Faraway favorites tap; the app hoists it above the overlays so opening one keeps it.
    farReveal: FarRevealState = rememberFarReveal(nearbyKey),
    // What this list is a list of (a nearby set, a trip's origins): a new one starts the held
    // loading cards afresh ([PendingTracker]). The nearby set's key by default.
    listKey: Any? = nearbyKey,
    // The list's held cards, kept above the list so they outlive a full-screen page over it (a
    // route's detail) and a rotation; the near-me list hoists it above the app's overlays too.
    pendingTracker: PendingTracker = rememberSaveable(listKey, saver = PendingTracker.Saver) { PendingTracker() },
    // The list's rows as last worked out off the main thread ([ListWork]), kept above the list as
    // [pendingTracker] is, so a return to it draws them at once. It follows [listKey] too.
    listWork: ListWork = remember(listKey) { ListWork() },
    onToggleJourney: ((StarredJourney) -> Unit)? = null,
    // Dismisses the route page's tip on starring a journey; null (dismissed, or not read yet) hides it.
    onDismissJourneyTip: (() -> Unit)? = null,
    // The journeys' far ends as shown, reported so their closures are checked; and those checks, as
    // departure-less stops carrying any stop-level disruption (SPEC *Journeys*).
    onJourneyDestinations: (List<StopRef>) -> Unit = {},
    journeyDestinationStops: List<StopArrivals> = emptyList(),
    // The far ends whose closure check failed with nothing known ([JourneyCard.destinationUnchecked]).
    journeyDestinationsUnknown: Set<String> = emptySet(),
    // Shows the other direction of a journey card (a tap on its header).
    onFlipJourney: (StarredJourney) -> Unit = {},
    // True while a journey-star write has failed and not yet been surfaced: the same acknowledged
    // snackbar seam as [starWriteFailed], cleared by [onJourneyWriteFailureShown].
    journeyWriteFailed: Boolean = false,
    onJourneyWriteFailureShown: () -> Unit = {},
    // The stops to fetch for the journey cards (each journey's origin this way round), reported
    // whenever they change; the ViewModel fetches them alongside the near-me stops.
    onJourneyOrigins: (List<StopRef>) -> Unit = {},
    // The same stops by journey (key → its origin and neighboring poles' ids), and the journey whose
    // own view is open (kept however far), so a relocate can drop a journey's stops the moment it's
    // held back, before the screen reports again.
    onJourneyStopIds: (Map<String, Set<String>>, String?) -> Unit = { _, _ -> },
    // The starred journeys' keys and each placed journey's latest check (the departures found to call
    // at its far end), for the widget to pin (it can't load route data itself); reported whenever
    // they change. The ViewModel keeps what they add up to.
    onWidgetJourneys: (Set<String>, List<WidgetJourneyCheck>, Map<String, String>, Map<String, Set<String>>) -> Unit =
        { _, _, _, _ -> },
    // Whether [journeys] is the saved list yet: until it is, nothing is reported for the widget, so
    // a list still loading isn't taken for "no journeys" and unpins them.
    journeysKnown: Boolean = true,
    // Whether the saved journeys' read has settled: read and placed, or found unreadable (then none for
    // good), so the disruptions row, which ranks by them, never waits on a store that won't answer (Codex, #598).
    journeysSettled: Boolean = true,
    // True only until the saved journeys' first read arrives (not when that read failed): an open
    // journey view restored across a rotation waits this out rather than closing.
    journeysLoading: Boolean = false,
    // The rows the user has starred (SPEC D8): pinned to the top, and their star filled.
    // Empty by default so an unwired build/test renders the plain soonest-first list.
    starred: Set<StarredRow> = emptySet(),
    // Whether [starred] is the stored set yet (read, or found unreadable): the disruptions row ranks by it.
    starredKnown: Boolean = true,
    onToggleStar: (DepartureRow) -> Unit = {},
    // False only when the stored star set is a newer-schema file this build can't read: the
    // star control is then hidden rather than shown unfilled (which would falsely read as
    // "nothing starred"). Defaults true, the normal case.
    starringAvailable: Boolean = true,
    // True while a star write has failed and not yet been surfaced (SPEC principle 2): the
    // screen shows a transient snackbar so a tap that didn't take isn't swallowed silently,
    // then calls [onStarWriteFailureShown] to clear it. Acknowledged state, not a one-shot
    // event, so a rotation between the failed tap and the snackbar doesn't drop it. False by
    // default so an unwired build/test renders no snackbar.
    starWriteFailed: Boolean = false,
    onStarWriteFailureShown: () -> Unit = {},
    // The service alerts the user has dismissed (SPEC *Disruptions*): matching closure cards and line
    // statuses are hidden until their content changes. Empty by default so an unwired build/test
    // shows every alert. [onDismissAlert] is called with the alert's row when its dismiss is tapped.
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // The home screen's disruptions row ([HomeLines]), at the very top (maintainer, 2026-10-05): shown
    // when true, with the tube's lines as the list's checks found them ([tube]).
    showDisruptionsRow: Boolean = false,
    always: HomeLines.Always? = null,
    // The lines the row always covers, the networks the rider chose ([HomeLines.Network]).
    alwaysNetworks: Set<String> = HomeLines.DEFAULT_NETWORKS,
    // True while a dismiss write has failed and not yet been surfaced (SPEC principle 2): same
    // snackbar seam as [starWriteFailed], so a dismiss tap that didn't persist isn't swallowed
    // silently. Acknowledged state; the screen calls [onDismissWriteFailureShown] to clear it.
    dismissWriteFailed: Boolean = false,
    onDismissWriteFailureShown: () -> Unit = {},
    // Open the open-source licenses screen (from the About dialog). Default no-op so an
    // unwired build/test renders the screen without a licenses destination.
    onOpenLicenses: () -> Unit = {},
    // Open the Settings screen (from the overflow menu). Default no-op so an unwired build/test
    // renders the screen without a settings destination.
    onOpenSettings: () -> Unit = {},
    // True when Google Play reports a newer version is available: the overflow icon gets a red
    // dot and the menu gains an "Update available" item. Off by default (and in debug — see
    // PlayUpdateChecker), so the common build/test renders the plain overflow.
    updateAvailable: Boolean = false,
    // Open the Play Store listing (from the "Update available" item). Default no-op.
    onOpenAppListing: () -> Unit = {},
    // The nearest station of each tube line or rail mode the list doesn't reach, and the nearest
    // bus places with routes it doesn't show (SPEC *Finding stops → Farther stations*): a collapsed
    // card each below the loaded places, which a tap loads and opens in place ([onOpenFarther]).
    // Empty by default, as on a station's page.
    farther: List<FartherCard> = emptyList(),
    onOpenFarther: (CollapsedPlaces.Place) -> Unit = {},
    // Start the consent-gated bug report (from the overflow). Default no-op so an unwired
    // build/test renders the menu without one.
    onSendBugReport: () -> Unit = {},
    // Non-null when the shown stops are backed by a low-confidence location (SPEC *Finding stops*):
    // a top banner over the list says so and offers "Try again" (which runs [onRefresh], a re-locate).
    // Null hides it. Default null so an unwired build/test renders without it.
    locationBanner: LocationBanner? = null,
    // The transport modes hidden from the near-me list (SPEC *Finding stops → Hiding a mode*): their
    // rows are left out and a one-line banner says so, with [onShowAllModes] to show them again. A
    // non-null [onHideMode] makes a long press on a near-me row or header offer "Hide ‹mode›".
    hiddenModes: Set<String> = emptySet(),
    onHideMode: ((String) -> Unit)? = null,
    onShowAllModes: () -> Unit = {},
    // Shows or hides a whole group of modes from the overflow menu's checkboxes, one per
    // [ModeGroups.ALL] group, ticked when shown; also the Undo a long press's hide offers, for the
    // group or line just hidden. Null leaves the menu without them, and a hide without Undo.
    onSetModeGroupShown: ((ModeGroups.Group, Boolean) -> Unit)? = null,
    // A change of hidden modes failed to save: a snackbar says so, then [onHiddenModesWriteFailureShown].
    hiddenModesWriteFailed: Boolean = false,
    onHiddenModesWriteFailureShown: () -> Unit = {},
    // Open the station search (SPEC *Finding stops*); null hides the overflow's "From…" item.
    onFindStation: (() -> Unit)? = null,
    // Non-null when this screen shows one searched station rather than the near-me list (SPEC
    // *Finding stops*): the app bar is titled by it with a back arrow ([onCloseStation]) in place of
    // the app's mark, and the overflow menu is left out — it belongs to the main list.
    stationTitle: String? = null,
    onCloseStation: () -> Unit = {},
    // "To…" (SPEC *Finding stops → From… To…*): pick a destination, then only the departures that go
    // there. An app-bar action on a searched station's page; an overflow item on the near-me list,
    // where the trip starts from the stops near the rider. Null leaves it out.
    onPlanTo: (() -> Unit)? = null,
    // The saved favorite places to offer as route chips atop the near-me list (SPEC D9 → *Routing
    // from the near-me list*), already less the ones the rider is at; [onRouteToPlace] plans a trip
    // to the one tapped. Empty (the default, and on a station's page) shows no row.
    favoritePlaces: List<FavoritePlace> = emptyList(),
    // The chips aren't worked out yet for places that can be read: the list waits on them as on its rows,
    // so the row is there when the list is.
    favoritePlacesPending: Boolean = false,
    onRouteToPlace: (TripDestination.Place) -> Unit = {},
    // A long press on a chip opens the places' own screen, to edit them; null offers no long press.
    onEditFavoritePlaces: (() -> Unit)? = null,
    // Answers the "Help make StopDash better" question from its card atop the near-me list
    // ([TelemetryInviteCard]); null (answered, or still loading) shows no card.
    onTelemetryInviteAnswer: ((Boolean) -> Unit)? = null,
    // Offers StopDash to a connected watch without it ([WatchInstallCard]), where the telemetry
    // question isn't being asked: one question at a time. Null (no such watch, or dismissed) shows none.
    watchInstall: WatchInstallActions? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // A long press's hide offers Undo for a moment, which shows that one item again as ticking its
    // overflow checkbox does (SPEC *Finding stops → Hiding a mode*).
    val hideMode = rememberHideWithUndo(
        onHide = onHideMode,
        onUnhide = onSetModeGroupShown?.let { setShown -> { entry: String -> setShown(ModeGroups.of(entry), true) } },
        host = snackbarHostState,
    )
    // Overflow-menu and About-dialog visibility. Saved so an open dialog survives rotation.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val starWriteFailedMessage = stringResource(R.string.star_write_failed)
    // The rows the screen renders, grouped against the live clock (SPEC D4) — computed once here so
    // both the list and the route-detail page below read the SAME rows. Empty for any non-Loaded
    // state. Cheap and pure; line statuses stamp each row so a disrupted line is marked (SPEC D3).
    val loaded = state as? DeparturesUiState.Loaded
    // The routes (both directions) of each journey's starred line and of every line at its origin,
    // which say where the journey boards and alights and which trains or buses call at the far end.
    // From the process cache at once when a line was loaded already; otherwise fetched off the render
    // path (the same lookup the route page makes, one or two requests per line a day). A failed
    // load is kept as such (null), so the card says so and offers a retry rather than checking forever.
    val routeStopsRepository = LocalRouteStops.current
    var journeyRouteRetry by rememberSaveable { mutableIntStateOf(0) }
    val loadedSequences = remember { mutableStateMapOf<String, LineSequence?>() }
    // The loaders below run again each hour, so a route or area the repository has let expire (a day
    // old) is refetched while the screen stays up; the old copy shows until the new one is in.
    val routeRecheck = now.epochSecond / 3600
    fun sequencesFor(lineIds: Collection<String>): Map<String, LineSequence?> = buildMap {
        for (id in lineIds) {
            if (id in loadedSequences) put(id, loadedSequences[id]) else routeStopsRepository?.cached(id, "")?.let { put(id, it) }
        }
    }
    // The starred journey whose own view is open (its key), from a tap on its heading, or null.
    var journeyViewKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the rider has tapped "Faraway favorites" to show the far journeys in full, for this
    // nearby set ([rememberFarReveal]).
    val farRevealed = farReveal.revealed
    // The journeys shown in full and fetched: the near ones, the far ones once revealed, and a far
    // one while its own view is open.
    val cardJourneys = remember(journeys, farJourneyMeters, journeyViewKey, farRevealed) {
        journeys.filter { it.key !in farJourneyMeters || farRevealed || it.key == journeyViewKey }
    }
    val journeyStarLines = remember(cardJourneys) { cardJourneys.map { it.lineId }.filter { it.isNotBlank() }.distinct() }
    // One map while the routes stay the same, so the work keyed on it runs once per change.
    val starSequences by remember(journeyStarLines, routeStopsRepository) { derivedStateOf { sequencesFor(journeyStarLines) } }
    // Where each journey boards and alights this way round: a bus's way back uses other poles. Worked
    // out on the list's worker; the last answer for the same journeys stands in while a reloaded route
    // is placed again, so the stops fetched don't drop out and back, but none for other journeys (a
    // flip keeps a journey's key, not which way round it is) (Codex, #564).
    val segmentsWanted = Inputs(cardJourneys, starSequences)
    val workedSegments = rememberWorked(listWork.segments, segmentsWanted, keep = { held, wanted -> held.parts[0] === wanted.parts[0] }) {
        val segments = cardJourneys.associate { j -> j.key to starSequences[j.lineId]?.let { Journeys.segment(j, it) } }
        val unplaced = cardJourneys.filter { starSequences[it.lineId] != null && segments[it.key] == null }.mapTo(LinkedHashSet()) { it.lineId }
        JourneySegments(segments, unplaced)
    }
    // Whether the journeys are still being placed on their routes as they stand: a journey whose route
    // is in checks meanwhile rather than read "Couldn't check", and the cards wait (below).
    val segmentsPending = listWork.segments.value?.key != segmentsWanted
    val journeySegments = workedSegments?.segments.orEmpty()
    // A journey its loaded route can't place reads "Couldn't check": the log says which, once per
    // distinct set, off composition.
    val unplacedJourneys = workedSegments?.takeIf { !segmentsPending }?.unplaced.orEmpty()
    LaunchedEffect(routeStopsRepository, unplacedJourneys) {
        unplacedJourneys.forEach { lineId -> routeStopsRepository?.reportUnplaced(lineId) }
    }
    // A bus journey's origin stop area (from its starred line's route) and the area's poles, looked
    // up once a day off the render path: another line may board beside the origin (stop K by
    // stop L) and reach the far end too (SPEC *Journeys*). A failed lookup is kept as such (null).
    val journeyAreas = remember(cardJourneys, journeySegments, starSequences) {
        cardJourneys.filter { it.bus }.mapNotNull { j ->
            val originId = journeySegments[j.key]?.originId ?: return@mapNotNull null
            starSequences[j.lineId]?.stopAreas?.get(originId)?.takeIf { it.isNotBlank() }?.let { j.key to it }
        }.toMap()
    }
    val loadedPoles = remember { mutableStateMapOf<String, List<StopLocation>?>() }
    LaunchedEffect(routeStopsRepository, journeyAreas, journeyRouteRetry, routeRecheck) {
        val repository = routeStopsRepository ?: return@LaunchedEffect
        for (areaId in journeyAreas.values.distinct()) {
            val held = loadedPoles[areaId]
            if (held != null && repository.cachedPoles(areaId) != null) continue
            if (held == null) loadedPoles.remove(areaId)
            val poles = repository.cachedPoles(areaId) ?: try {
                repository.loadPoles(areaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Logged (sanitized) by the repository; null marks the failure for the card, unless
                // an expired copy is held: route data a day old beats none.
                held
            }
            loadedPoles[areaId] = poles
        }
    }
    // Each bus journey's area poles, by journey key: absent while loading, null when it failed.
    // One map until a lookup comes in, so the work keyed on it isn't done again each recomposition.
    val journeyPoles: Map<String, List<StopLocation>?> by remember(journeyAreas) {
        derivedStateOf {
            journeyAreas.mapNotNull { (key, areaId) ->
                if (areaId in loadedPoles) key to loadedPoles[areaId] else null
            }.toMap()
        }
    }
    // The stop each journey is fetched from ([journeyOriginsOf]) and, with them, the lines whose routes
    // the cards need ([journeyLineIdsOf]), worked out together on the list's worker: the lines never run
    // against origins standing in, which the current ones would then change, canceling the routes they
    // started loading, asked again after (Codex, #625). The last answer stands in meanwhile, so the lines
    // loaded don't drop out and back; none until the first is in. The lines are the same [Reported] while
    // they're unchanged, so the loader keyed on them is told apart by identity and loads nothing again.
    val loadedStops = loaded?.stops
    val originsWanted = Inputs(cardJourneys, journeySegments, starSequences, journeyPoles, journeyStarLines, loadedStops)
    val journeyRoutes = rememberWorked(listWork.journeyOrigins, originsWanted, keep = { _, _ -> true }) {
        val last = listWork.journeyOrigins.value?.value
        val origins = journeyOriginsOf(cardJourneys, journeySegments, starSequences, journeyPoles)
        val lines = journeyLineIdsOf(journeyStarLines, origins, loadedStops.orEmpty(), cardJourneys, journeySegments, journeyPoles)
        JourneyOrigins(origins, Reported.of(lines, last?.lineIds))
    }
    val journeyOrigins = journeyRoutes?.origins
    // Whether they're still being worked out for the journeys as they stand: the stops reported, worked
    // out from them, hold their last answer meanwhile ([heldWhile]).
    val originsPending = listWork.journeyOrigins.value?.key != originsWanted
    val journeyRouteLines = journeyRoutes?.lineIds
    // Loads [lineIds]' routes into [loadedSequences], each line at once, so one slow route doesn't hold
    // up the rest.
    suspend fun loadSequences(repository: RouteStopsRepository, lineIds: Collection<String>) = coroutineScope {
        for (lineId in lineIds) {
            val held = loadedSequences[lineId]
            if (held != null && repository.cached(lineId, "") != null) continue
            if (held == null) loadedSequences.remove(lineId)
            launch {
                loadedSequences[lineId] = try {
                    repository.load(lineId, "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TflException) {
                    // Logged (sanitized) by the repository; null marks the failure for the card,
                    // unless an expired copy is held: route data a day old beats none.
                    held
                }
            }
        }
    }
    LaunchedEffect(routeStopsRepository, journeyRouteLines, journeyRouteRetry, routeRecheck) {
        loadSequences(routeStopsRepository ?: return@LaunchedEffect, journeyRouteLines?.value ?: return@LaunchedEffect)
    }
    // One map until a route comes in, as for the alerts' routes below.
    val journeySequences by remember(journeyRouteLines, routeStopsRepository) {
        val lineIds = journeyRouteLines?.value.orEmpty()
        derivedStateOf { sequencesFor(lineIds) }
    }
    // The routes of the shown stops' bus lines whose alert may lie wholly behind a stop, so a
    // diversion a bus from there never reaches doesn't flag it ([DepartureRows.withAlertsBehind]):
    // one request per such line a day, the route page's own. Until one is in, or where it failed, the
    // alert stays on, as it was.
    // Read again each London day, as planned work whose day has come counts ([LineStatus.asOf]).
    val alertDay = now.atZone(AlertStart.ZONE).toLocalDate()
    // Worked out off the main thread; the last lines stand in meanwhile, so their routes don't drop
    // and come back.
    val alertLineIds = rememberWorked(
        listWork.alertLines,
        Inputs(loaded?.stops, loaded?.lineStatuses, alertDay, dismissed),
        keep = { _, _ -> true },
    ) {
        loaded?.let { DepartureRows.linesWithAlertsToPlace(it.stops, it.lineStatuses, now, dismissed) }.orEmpty()
    }.orEmpty()
    LaunchedEffect(routeStopsRepository, alertLineIds, routeRecheck) {
        loadSequences(routeStopsRepository ?: return@LaunchedEffect, alertLineIds)
    }
    // One map until a route comes in, so the work keyed on it isn't done again each recomposition.
    val alertSequences by remember(alertLineIds, routeStopsRepository) { derivedStateOf { sequencesFor(alertLineIds) } }
    // The verdicts those routes give, kept for the widget and the watch, which have no routes to reach
    // them (SPEC *Disruptions*). Keyed on the alerts and routes, not the clock: a verdict says where an
    // alert is, not when.
    val alertsBehind = LocalAlertsBehind.current
    val alertVerdicts = rememberWorked(listWork.alertVerdicts, Inputs(loaded?.stops, loaded?.lineStatuses, alertDay, alertSequences)) {
        // At every stop shown, placed or not: the store keeps verdicts at these alone.
        loaded?.let { ld -> AlertsBehind.placement(ld.stops, ld.lineStatuses, alertSequences, now) }
    }
    // Nothing until the list has stops: an empty first frame says nothing of where the rider is.
    LaunchedEffect(alertsBehind, alertVerdicts) {
        alertVerdicts?.let { alertsBehind?.record(it) }
    }
    // The poles beside each bus journey's origin that board a line reaching its far end, fetched
    // alongside the origin (one arrivals request each) and shown on its card under their letter.
    // Worked out on the list's worker, the last answer for the same journeys standing in meanwhile.
    val siblingsWanted = Inputs(cardJourneys, journeySegments, journeyPoles, journeySequences)
    val journeySiblings = rememberWorked(listWork.siblings, siblingsWanted, keep = { held, wanted -> held.parts[0] === wanted.parts[0] }) {
        cardJourneys.mapNotNull { j ->
            val originId = journeySegments[j.key]?.originId ?: return@mapNotNull null
            val poles = journeyPoles[j.key] ?: return@mapNotNull null
            j.key to Journeys.siblingPoles(j, originId, poles, journeySequences)
        }.toMap()
    }.orEmpty()
    // Whether they're still being worked out as things stand: a bus journey with poles checks meanwhile,
    // and the cards wait (below).
    val siblingsPending = listWork.siblings.value?.key != siblingsWanted
    // Each journey's far end this way round: its own stop, and the stops the route places it at (a
    // bus's other poles), whose closure the card shows.
    val journeyDestinationIds = remember(cardJourneys, journeySegments) {
        cardJourneys.associate { j -> j.key to (setOf(j.to.stopId) + journeySegments[j.key]?.destinationIds.orEmpty()) }
    }
    // The stops the journeys are fetched from, and each one's boarding stops ([journeyStopsOf]), worked
    // out on the list's worker, the last answer standing in meanwhile; none until the origins are in.
    // Held while the origins are worked out for the journeys as they stand: worked out against the ones
    // standing in, a new journey's ids would be reported ahead of its stops, releasing a refresh that
    // waits on them without its origin (Codex, #625).
    val journeyStops = rememberWorked(
        listWork.journeyStops,
        heldWhile(originsPending, listWork.journeyStops, Inputs(journeyOrigins, cardJourneys, journeySegments, journeySiblings, journeyViewKey)),
        keep = { _, _ -> true },
    ) {
        // The last answer as it stands when this runs, read on the worker, whose reports this one reuses
        // where they're unchanged.
        val last = listWork.journeyStops.value?.value
        journeyOrigins?.let { journeyStopsOf(it, cardJourneys, journeySegments, journeySiblings, journeyViewKey, last) }
    }
    // The stops first, then the ids that say they're in, each only as it changes: an answer standing in
    // reports nothing again, so the view model never hears the same stops as if newly reported, which a
    // refresh waiting on the screen's report would take as its answer (#548). A new list (with its own
    // model) reports them afresh.
    ReportChanges(journeyStops?.fetched, listWork, onJourneyOrigins)
    ReportChanges(journeyStops?.stopIds, listWork) { (stopIds, viewKey) -> onJourneyStopIds(stopIds, viewKey) }
    // The snapshot's rows ([listRowsOf]), built off the main thread from the snapshot as it stands,
    // the list's last rows standing in meanwhile. Null only until a new list's first rows are in:
    // the screen then shows a spinner rather than call the list empty. None are built before the first
    // snapshot, so nothing from a cold load's wait stands in for its first rows (Codex, #524).
    val listRows = rememberWorked(
        listWork.rows,
        ListInputs(
            listKey,
            // The snapshot itself too, so a change to its checks or loading stops alone (a canceled
            // fetch keeps the same stops) is drawn at once (Codex, #524).
            Inputs(loaded, now, stopDistanceMeters, dismissed, hiddenModes, alertSequences, journeyDestinationStops, journeyDestinationsUnknown),
        ),
        keep = ListInputs.sameList,
    ) {
        loaded?.let {
            listRowsOf(it, now, stopDistanceMeters, dismissed, hiddenModes, alertSequences, journeyDestinationStops, journeyDestinationsUnknown)
        }
    }
    val rowsPending = loaded != null && listRows == null
    // The journey cards: the trains or buses from each journey's origin that call at its far end, on
    // any line, the origin's closure notice if it has one, or why they can't be shown yet (SPEC
    // principle 1).
    // How the list's and the journey cards' stop cards group a branching line's trains ([stopCard]):
    // worked out with them on the list's worker, again if it's replaced once its patterns load.
    val topology = LocalRouteTopology.current
    // Judged against the snapshot and time [rows] were built from ([ListRows.source]), with the list
    // they're drawn beside, on the list's worker ([shownRowsOf]'s stage) (Codex, #524).
    fun judgeCards(rows: ListRows): List<JourneyCard> {
        val ld = rows.source
        val judgedAt = rows.now
        // Dismissals apply here as on the list, so an alert dismissed anywhere is gone from the card,
        // and an alert behind the origin flags it no more than the list ([ListRows.all]).
        val across = rows.all
        return cardJourneys.map { journey ->
            val segment = journeySegments[journey.key]
            val originId = segment?.originId ?: journey.from.stopId
            val origin = ld?.stops?.firstOrNull { it.stopId == originId }
            // Every boarding stop's closure notice: the origin's, and each neighboring pole's.
            val boardingStops = listOf(originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
            // The far-end stops the card's departures reach (another line may use another pole).
            var reached = emptySet<String>()
            // The departures its check left out as unchecked, for the debug log.
            var misses = emptySet<RouteMiss>()
            // The stop being fetched: known before the route is in for a station (see journeyOrigins).
            val fetchedId = segment?.originId ?: journey.from.stopId.takeUnless { journey.bus }
            val state = when {
                // Its route in but the stops not yet placed on it as it stands.
                segmentsPending && starSequences[journey.lineId] != null -> JourneyCardState.Checking
                // Asked for and not come back: the fetch failed with nothing earlier to show.
                origin == null && fetchedId != null && fetchedId in ld?.unavailableStopIds.orEmpty() ->
                    JourneyCardState.NotChecked()
                journey.lineId !in journeySequences -> JourneyCardState.Checking
                journeySequences[journey.lineId] == null -> JourneyCardState.RouteFailed
                // The route can't place the stops this way round (no single way-back stop).
                segment == null -> JourneyCardState.NotChecked()
                origin == null -> JourneyCardState.Checking
                // A bus origin's neighboring poles still being looked up, or their lines' routes loading.
                journey.key in journeyAreas && journey.key !in journeyPoles -> JourneyCardState.Checking
                siblingsPending && journeyPoles[journey.key] != null -> JourneyCardState.Checking
                journeySiblings[journey.key]?.pendingLines.orEmpty().isNotEmpty() -> JourneyCardState.Checking
                else -> {
                    val siblings = journeySiblings[journey.key]?.poles.orEmpty()
                    val siblingStops = siblings.map { pole -> ld?.stops?.firstOrNull { it.stopId == pole.id } }
                    // The origin's trains, then each neighboring pole's (matched to the far end the same way).
                    val parts = listOf(Journeys.trains(segment, across, journeySequences, journey)) +
                        siblings.map { pole -> Journeys.trains(JourneySegment(pole.id, emptySet()), across, journeySequences, journey) }
                    val trains = JourneyTrains(
                        rows = parts.flatMap { it.rows },
                        pending = parts.any { it.pending },
                        unresolved = parts.any { it.unresolved },
                        routeFailed = parts.any { it.routeFailed },
                        misses = parts.flatMapTo(LinkedHashSet()) { it.misses },
                    )
                    misses = trains.misses
                    // Trains on another branch, offered with where to change when no direct one is due.
                    val changes = Journeys.changesWithoutDirect(trains.rows, parts.flatMap { it.changes })
                    reached = parts.flatMapTo(HashSet()) { it.reachedIds }
                    // A neighboring pole whose fetch failed, whose lookup did, or whose line's route
                    // did, may have had a bus.
                    val polesFailed = journey.key in journeyPoles && journeyPoles[journey.key] == null ||
                        journeySiblings[journey.key]?.failedLines.orEmpty().isNotEmpty()
                    val siblingsMissed = siblings.zip(siblingStops).any { (pole, stop) ->
                        stop == null && pole.id in ld?.unavailableStopIds.orEmpty()
                    } || polesFailed
                    // "No trains" is only a claim a fresh, current fetch can make: an origin whose last
                    // refresh failed (kept aged) or has gone stale says it couldn't check instead —
                    // and so does one with a departure whose path couldn't be resolved, since it may
                    // well call at the far end. A line whose route is still loading says checking.
                    // The same holds for each neighboring pole.
                    val current = (listOf(origin) + siblingStops.filterNotNull()).all { stop ->
                        stop.arrivalsFresh && !Staleness.isStale(stop.fetchedAt, judgedAt)
                    }
                    // A line still loading holds the whole card at "checking", so a first line's trains
                    // aren't shown as if they were all; one that couldn't be checked is said so beneath
                    // the rest.
                    when {
                        trains.pending -> JourneyCardState.Checking
                        // A neighboring pole asked for and not in yet.
                        siblings.zip(siblingStops).any { (pole, stop) -> stop == null && pole.id !in ld?.unavailableStopIds.orEmpty() } ->
                            JourneyCardState.Checking
                        trains.rows.isNotEmpty() || changes.isNotEmpty() ->
                            JourneyCardState.Trains(
                                trains.rows,
                                // With only trains to change from, "no direct trains" is a claim
                                // only a fresh, current fetch can make too.
                                incomplete = trains.unresolved || siblingsMissed || trains.rows.isEmpty() && !current,
                                retry = trains.routeFailed || polesFailed,
                                changes = changes,
                                topology = topology,
                            )
                        // A route that failed to load is the one gap a retry can close.
                        trains.routeFailed -> JourneyCardState.RouteFailed
                        !current || trains.unresolved || siblingsMissed -> JourneyCardState.NotChecked(retry = polesFailed)
                        else -> JourneyCardState.Trains(emptyList())
                    }
                }
            }
            // A complete check judged every departure at the origin: the widget drops any it now rejects.
            // Per boarding stop: the origin, then each neighboring pole (pinned separately on the widget).
            val boardingIds = listOfNotNull(segment?.originId) + journeySiblings[journey.key]?.poles.orEmpty().map { it.id }
            val checked =
                if (state is JourneyCardState.Trains && !state.incomplete) {
                    boardingIds.associateWith { id ->
                        across.filter { it.stopId == id && it.lineId.isNotBlank() }
                            .flatMapTo(HashSet()) { row -> row.upcoming.map(JourneyCall::of) }
                    }
                } else {
                    emptyMap()
                }
            // And the far end's (closed or moved), from its own check, for every stop the card's departures
            // reach there: a journey can't end as shown. One card per notice, however many poles carry it.
            val destinationIds = journeyDestinationIds[journey.key].orEmpty() + reached
            val destinationClosures = rows.destinationClosures
                .filter { it.stopId in destinationIds }.groupBy { it.stopDisruption }.values.toList()
            val closures = boardingStops.mapNotNull { id ->
                across.firstOrNull { it.stopId == id && it.stopDisruption != null }?.let(::listOf)
            } + destinationClosures
            // A destination whose check failed with nothing known: the card says so, not "open".
            // From the same check as the far end's closures ([ListRows.destinationsUnknown]) (Codex, #524).
            val destinationUnchecked = destinationIds.any { it in rows.destinationsUnknown }
            JourneyCard(journey, state, closures, checked, boardingIds, destinationIds, destinationUnchecked, misses)
        }
    }
    val nearbyOrdered = listRows?.nearby.orEmpty()
    val nearbyMarked = listRows?.marked ?: noRows
    // A line on good service whose times are missing shows as "?" where its timetable says a train
    // is due, in the near-me list only (SPEC *Departures*); worked out off the main thread ([withQuietRows]).
    // On the minute tick ([EmptyTimesState.now]), not the screen's own: a timetable's answer moves no
    // faster, and a new input reruns the background work.
    val quietTick = LocalEmptyTimes.current.now
    // From the snapshot the rows were built from, so the "?" rows join the rows of their own snapshot
    // (Codex, #524).
    val quietFrom = listRows?.source
    val quietInputs = remember(quietFrom, quietTick, stopDistanceMeters, hiddenModes, dismissed) {
        val ld = quietFrom
        // Not while nearby stops are still loading: one of them may have trains for a line that
        // would otherwise read "?" at a stop already in. Nor while a stop's closure check is out: a
        // closed station's timetable trains aren't due, so "?" waits until it's known.
        if (ld == null || stopDistanceMeters.isEmpty() || ld.pendingStops.isNotEmpty() || ld.closurePending.isNotEmpty()) null else QuietInputs(ld.stops, ld.lineStatuses, ld.determinedLineIds, quietTick, stopDistanceMeters, hiddenModes, dismissed, ld.stopsDisruptionUnknown)
    }
    val nearbyRows = withQuietRows(nearbyMarked, quietInputs)
    // The journey cards ([judgeCards]), then the rows drawn and the dismissed closures that keep a
    // heading ([shownRowsOf]), in one go off the main thread, so the cards and the list beside them
    // are always from one snapshot (Codex, #524); null while the snapshot's rows are.
    // While a journey's segment or neighboring poles are being worked out, the list's last cards and
    // rows stay up, as they do while new rows are built, rather than flash "Checking" for the moment
    // that takes; a list with nothing drawn yet judges at once, the journey checking meanwhile.
    val shownWanted = ListInputs(
        listKey,
        Inputs(
            listRows, nearbyRows, starred, stopDistanceMeters, cardJourneys, journeySegments, journeySequences,
            journeyAreas, journeyPoles, journeySiblings, journeyDestinationIds, segmentsPending, siblingsPending, topology,
        ),
    )
    val shownHeld = listWork.shown.value?.key
    val journeyWorkPending = segmentsPending || siblingsPending
    val shown = rememberWorked(
        listWork.shown,
        if (journeyWorkPending && shownHeld != null && ListInputs.sameList(shownHeld, shownWanted)) shownHeld else shownWanted,
        keep = ListInputs.sameList,
    ) {
        listRows?.let { from ->
            val cards = judgeCards(from)
            // What the journey cards above already show: a near-me row they cover in full isn't repeated.
            val cardRows = cards.flatMap { (it.state as? JourneyCardState.Trains)?.shownRows.orEmpty() }
            shownRowsOf(from, nearbyRows, cardRows, starred, stopDistanceMeters, cards, cardJourneys, topology)
        }
    }?.takeIf { listRows != null }
    val journeyCards = shown?.cards.orEmpty()
    val journeyRowsShown = shown?.cardRows.orEmpty()
    // The disruptions row, worked out off the main thread, the last one standing while the next is
    // (a moment's old row over a "Checking…" that blinks); "Checking…" until the first is in. Its pills
    // lead with the rider's own lines, so it waits for the starred rows and journeys to be read: ranked
    // on a store still loading, they would reshuffle as it lands (Codex, #598).
    val homeWork = remember { mutableStateOf<Worked<Inputs, TripRow>?>(null) }
    val disruptionsRow = when {
        !showDisruptionsRow -> null
        !journeysSettled || !starredKnown -> homeWork.value?.value ?: TripRow.CHECKING
        else -> {
            val held = homeWork.value?.value
            rememberWorked(
                homeWork,
                // Compared part by part, a snapshot by identity, never by its contents (Codex, #598).
                Inputs(loaded, stopDistanceMeters, always, alwaysNetworks, dismissed, now, refreshing, starred, journeys, shown, cardJourneys, nearestStops),
                keep = { _, _ -> true },
            ) {
                // A journey card still checking may yet show another line it rides: the last row (or
                // "Checking…") stands until the cards are in, so the pills never reorder as one lands (Codex, #598).
                // Nor cards judged for another set of journeys, one just added or removed, held meanwhile (Codex, #598).
                // Nor cards from an older snapshot than the statuses ranked here, held while a refresh's are
                // worked out (Codex, #598).
                if (shown == null || shown.journeys !== cardJourneys || shown.from.source !== loaded ||
                    journeyCards.any { it.state is JourneyCardState.Checking }
                ) {
                    return@rememberWorked held ?: TripRow.CHECKING
                }
                // A journey's lines: the one it was starred from, and every line its card shows reaching the
                // far end, another pole's bus included (Codex, #598).
                val journeyLines = HashSet<String>()
                journeys.forEach { journeyLines += it.lineId }
                journeyCards.forEach { card -> (card.state as? JourneyCardState.Trains)?.rows?.forEach { journeyLines += it.lineId } }
                HomeLines.row(loaded, stopDistanceMeters, always, dismissed, now, refreshing, alwaysNetworks, starred, journeyLines, journeys, nearestStops)
            } ?: TripRow.CHECKING
        }
    }
    val rows = shown?.rows.orEmpty()
    val dismissedClosures = shown?.dismissedClosures.orEmpty()
    val listPending = rowsPending || loaded != null && (shown == null || favoritePlacesPending)
    // What the screen draws against: the snapshot and time the drawn rows were built from, so rows
    // held while new ones are built are never drawn against a newer clock (a train just gone would
    // read "0 min") or a newer snapshot's checks or loading cards (Codex, #524).
    val drawnFrom = shown?.from ?: listRows
    val drawnLoaded = drawnFrom?.source ?: loaded
    // The drawn rows' own shared notices (Codex, #524).
    val sharedNotices = drawnFrom?.shared.orEmpty()
    // What the judged cards report ([cardReportsOf]), worked out on the list's worker, the last answer
    // standing in meanwhile; none before the cards are judged, which would read as no far ends to check.
    // Each report is made only as it changes.
    val cardReportsWanted = Inputs(shown, cardJourneys, farJourneyMeters, journeyAreas, journeyPoles, journeySiblings, siblingsPending)
    val cardReports = rememberWorked(listWork.cardReports, cardReportsWanted, keep = { _, _ -> true }) {
        val last = listWork.cardReports.value?.value
        cardReportsOf(shown, cardJourneys, farJourneyMeters, journeyAreas, journeyPoles, journeySiblings, siblingsPending, last)
    }
    // The cards say only "Some routes couldn't be checked"; the log says which trains and why, once
    // per distinct set (a refresh finding the same misses logs nothing new).
    ReportChanges(cardReports?.misses, listWork) { routeStopsRepository?.reportMisses(it) }
    // The far ends to check for a closure.
    ReportChanges(cardReports?.destinations, listWork, onJourneyDestinations)
    // What each placed journey's card found for the widget (it can't load routes itself): the
    // origin's departures that call at the far end, by line, destination and branch, and — from a
    // complete check — every departure it judged. The ViewModel merges these into what it pins.
    // Not while the snapshot's rows are still being built: every card says "checking" then, which
    // would unpin the widget's trains for a moment. Judged for the journeys as they stand: a flip's or
    // reload's old cards aren't reported against the new direction (Codex, #524), nor an answer
    // standing in for older cards (#593).
    val cardsJudged = shown != null && shown.journeys === cardJourneys
    val cardReportsCurrent = listWork.cardReports.value?.key == cardReportsWanted
    ReportChanges(cardReports?.widget?.takeIf { journeysKnown && cardsJudged && cardReportsCurrent }, listWork) {
        onWidgetJourneys(it.keys, it.checks, it.from, it.boarding)
    }
    // The farther bus cards to show ([fartherShownOf]), decided against the rows the screen draws, on the
    // list's worker; the last ones stand in meanwhile, so the cards don't drop out and back as the rows
    // change.
    val fartherShown = rememberWorked(listWork.farther, Inputs(farther, shown, drawnLoaded), keep = { _, _ -> true }) {
        fartherShownOf(farther, shown?.rows.orEmpty(), shown?.cardRows.orEmpty(), drawnLoaded?.pendingStops.orEmpty())
    }.orEmpty()

    // The platform/pole the user drilled into by tapping its group header, or null for the full list
    // (SPEC D8): its stop ids (comma-joined; for a whole-station view, its cluster keys instead), its [StopGroup.splitKey], and its header text (the
    // app-bar fallback while no group matches), as saveable strings so the view survives rotation.
    // The filtered rows are rebuilt from the same snapshot on every recomposition, so the view stays
    // live and never fetches on its own (SPEC D4).
    var platformStopIds by rememberSaveable { mutableStateOf<String?>(null) }
    var platformKey by rememberSaveable { mutableStateOf("") }
    var platformTitle by rememberSaveable { mutableStateOf("") }
    // True when the view was opened from a place name: it is then the whole station, always titled
    // by the bare place name, however many groups it currently holds.
    var platformIsStation by rememberSaveable { mutableStateOf(false) }
    // The whole-station view a platform view was opened from, if any (its cluster keys and title),
    // so back steps out to the station rather than past it to the full list. Null when the platform
    // was opened straight from the full list.
    var parentStationIds by rememberSaveable { mutableStateOf<String?>(null) }
    var parentStationTitle by rememberSaveable { mutableStateOf("") }
    // Its rows and title ([platformViewOf]), off the main thread; the same view's last rows stand in
    // meanwhile. Null while closed, and while a view just opened has none worked out yet.
    val platformView = rememberWorked(
        listWork.platform,
        PlatformInputs(
            platformStopIds.orEmpty(), platformKey, platformIsStation,
            Inputs(shown, platformTitle, starred, dismissed, hiddenModes, alertSequences, topology),
        ),
        keep = PlatformInputs.sameView,
    ) {
        val ids = platformStopIds?.split(',')?.toSet()
        // From the drawn list's own snapshot, time and rows (its closure cards), so the view never
        // mixes two snapshots (Codex, #524). Nothing before the list's first rows: a view restored
        // while loading waits for its rows rather than read as gone.
        val drawn = shown
        if (ids == null || drawn == null) {
            null
        } else {
            platformViewOf(
                drawn.from.source, drawn.from.now, ids, platformKey, platformIsStation, platformTitle, starred, dismissed,
                drawn.rows, hiddenModes, alertSequences, topology,
            )
        }
    }?.takeIf { platformStopIds != null }
    // A view opened over a loaded list whose rows aren't in yet: a spinner, not the full list.
    val platformPending = platformStopIds != null && loaded != null && platformView == null
    // Close the view when the snapshot no longer holds the tapped group: its stops weren't fetched
    // (a near-me set re-resolved elsewhere), its last departure passed, or the feed dropped the
    // platform number it was keyed on. An empty page would assert "no departures" for a platform
    // that may still have trains (SPEC principle 1), so the full list shows instead — at once, not a
    // frame later — and the saved view is cleared.
    val platformGone = loaded != null && platformView != null && platformView.title == null
    // Leave the current view one level: a platform opened from a station returns to that station;
    // anything else returns to the full list.
    fun closeView() {
        val parent = parentStationIds
        parentStationIds = null
        if (parent != null && !platformIsStation) {
            platformStopIds = parent
            platformIsStation = true
            platformKey = ""
            platformTitle = parentStationTitle
        } else {
            platformStopIds = null
        }
    }
    // A vanished platform steps back to its station (which closes in turn if it is gone too).
    // Keyed on the view too: when a refresh drops both a platform and its station, closing to the
    // station leaves platformGone true, and the effect must run again to close that as well.
    LaunchedEffect(platformGone, platformStopIds, platformIsStation) { if (platformGone) closeView() }
    val platformRows = platformView?.rows?.takeUnless { platformGone } ?: emptyList<DepartureRow>().takeIf { platformPending }
    val shownRows = platformRows ?: rows
    // The drawn rows' stop cards, worked out with them: the platform view's own, or the list's.
    val shownCards = if (platformRows != null) platformView?.takeUnless { platformGone }?.listCards ?: ListCards.NONE else shown?.listCards ?: ListCards.NONE
    BackHandler(enabled = platformRows != null) { closeView() }
    val drawnNow = (if (platformRows != null) platformView?.now else drawnFrom?.now) ?: now
    // A platform or station view's rows are built apart from the list's, maybe from another
    // snapshot: the view is drawn against its own (Codex, #524).
    val drawnState = (if (platformRows != null) platformView?.source else null) ?: drawnLoaded
    // A searched station's page closes back to the search; a drill-down inside it steps out first
    // (this one is off while a drill-down is open, so the handler above takes that back).
    BackHandler(enabled = stationTitle != null && platformRows == null, onBack = onCloseStation)

    // The starred journey whose own view is open (its key), from a tap on its heading, or null. It
    // resolves against the current cards each recomposition, so it follows a swap and its trains stay
    // live; once the saved journeys are known and it isn't among them (unstarred), the view closes.
    val journeyViewCard = journeyViewKey?.let { key -> journeyCards.firstOrNull { it.journey.key == key } }
    // Only once the cards are judged for the journeys as they stand: held cards from before a reload
    // of the saved journeys don't say it's gone.
    val cardsCurrent = shown?.journeys === cardJourneys
    LaunchedEffect(journeyViewKey, journeyViewCard == null, journeysLoading, cardsCurrent) {
        if (journeyViewKey != null && journeyViewCard == null && !journeysLoading && cardsCurrent) journeyViewKey = null
    }
    // Open while its card is shown, and while the saved journeys' first read is pending (after a
    // rotation they re-read from disk): the view then holds a placeholder rather than flashing to the
    // list, and Back still leaves it (Codex). A read that failed isn't pending, so the view closes
    // rather than spin forever (Codex).
    val journeyViewOpen = journeyViewKey != null && (journeyViewCard != null || journeysLoading || !cardsCurrent)
    BackHandler(enabled = journeyViewOpen) { journeyViewKey = null }
    // A platform, station or journey view scrolls on its own, from the top when first opened. Each
    // open view keeps its own place — across a route page opened from it, a rotation, and a platform
    // opened from a station and backed out of (the station's place is still there) (Codex) — until
    // the full list is back; the full list keeps [listState].
    val drillScroll = rememberSaveable(saver = DrillScrollStates.Saver) { DrillScrollStates() }
    val drillOpen = platformStopIds != null || journeyViewKey != null
    val drillListState = drillScroll.stateFor("$journeyViewKey|$platformStopIds|$platformKey|$platformIsStation")
    LaunchedEffect(drillOpen) { if (!drillOpen) drillScroll.clear() }

    // The route whose detail is open, held by its stable row identity rather than the row object: a
    // saveable String survives a configuration change (the page stays open on rotation) and resets on
    // process death, and it re-resolves against the current `rows` each recomposition so the page
    // reflects a refreshed row and closes itself if the row leaves the list — the coordinate it shows
    // is never persisted (mirrors the bug-report flow).
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Which of the row's routes was tapped (a card shows one route row per destination), so the page
    // follows that route rather than whichever train is soonest. Two saveable strings, not a
    // RouteFocus, so it survives rotation with no custom Saver; null destination = no focus.
    var detailDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBranch by rememberSaveable { mutableStateOf<String?>(null) }
    // Opens a row's route page, counted as a tap on [tap]'s kind (UsageEvent; opted in only): a
    // row on a journey's change card is a change card, any other a stop row.
    fun openDetail(tap: UsageEvent.Tap): (DepartureRow, RouteFocus?) -> Unit = { row, focus ->
        UsageEvents.log(UsageEvent.Tapped(tap))
        detailKey = row.detailKey()
        detailDestination = focus?.destination
        detailBranch = focus?.branch
    }
    // A journey card's train opens too: its farther origin isn't in the near-me rows, so the key is
    // also looked up among the journey cards' rows (shown only on the full list, as the cards are).
    // Worked out with the cards ([ShownRows.cardRows]).
    val journeyRows = if (platformRows != null) emptyList() else journeyRowsShown
    // And, last, among every loaded stop's rows: a page opened from a journey card stays open when
    // that journey is unstarred from the page itself, while its origin's departures are still loaded.
    val loadedRows = drawnFrom?.all.orEmpty()
    // With the snapshot and time of the rows it came from, which the page is drawn against (Codex,
    // #524): the drawn list's (or an open platform view's), or the journey cards' and loaded rows,
    // which are the drawn list's too.
    val detailHit = detailKey?.let { key ->
        shownRows.firstOrNull { it.detailKey() == key }?.let { Triple(it, drawnState, drawnNow) }
            ?: (journeyRows.firstOrNull { it.detailKey() == key }
                ?: loadedRows.takeIf { platformRows == null }?.firstOrNull { it.detailKey() == key })
                ?.let { Triple(it, drawnFrom?.source, drawnFrom?.now ?: now) }
    }
    val detailRow = detailHit?.first
    // When the open route's row leaves the list — its last departure passed on the 10s clock, or it
    // was pruned — clear the saved key so the vanished page stays closed rather than silently
    // reopening if a later refresh reproduced that same stop/line/direction identity (Codex). A live
    // refresh keeps the same identity, so this fires only on a genuine disappearance.
    // Not while the rows are still being worked out (a return to the list, a rotation): the row isn't
    // gone, just not in yet.
    val rowsKnown = !listPending && !platformPending
    LaunchedEffect(detailKey, detailRow == null, rowsKnown) {
        if (detailKey != null && detailRow == null && rowsKnown) detailKey = null
    }
    // Surface a failed star write as a snackbar (SPEC principle 2: a tap that didn't take isn't
    // swallowed). Gated on the list being shown — the snackbar host lives in the departures Scaffold,
    // not the full-screen route page below, so a failure while the page is open holds the flag until
    // the user returns, then shows, rather than firing at a host that isn't composed. Clear first,
    // then show, so a rotation while it's visible doesn't re-trigger it.
    // A hidden-modes change that didn't save holds only until the app restarts: say so, the same way
    // (SPEC principle 2).
    val hiddenModesWriteFailedMessage = stringResource(R.string.hidden_modes_write_failed)
    LaunchedEffect(hiddenModesWriteFailed, detailRow == null) {
        if (hiddenModesWriteFailed && detailRow == null) {
            onHiddenModesWriteFailureShown()
            snackbarHostState.showSnackbar(hiddenModesWriteFailedMessage)
        }
    }
    LaunchedEffect(starWriteFailed, detailRow == null) {
        if (starWriteFailed && detailRow == null) {
            onStarWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
    }
    // A failed journey-star write, surfaced like a failed row star (and gated the same way, since
    // the snackbar host lives in the departures Scaffold, not the route page the tap came from).
    LaunchedEffect(journeyWriteFailed, detailRow == null) {
        if (journeyWriteFailed && detailRow == null) {
            onJourneyWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
    }
    // Surface a failed dismiss write the same way as a failed star write, and gated the same way —
    // the snackbar host lives in the departures Scaffold, not the full-screen route page below, so a
    // failure while the page is open holds the flag until the user returns, then shows.
    val dismissWriteFailedMessage = stringResource(R.string.dismiss_write_failed)
    // And held while a lines page is open over the list, which says it itself (Codex, #603).
    val linesOpen = LocalDismissLineAlert.current?.pagesOpen?.intValue ?: 0
    LaunchedEffect(dismissWriteFailed, detailRow == null && linesOpen == 0) {
        if (dismissWriteFailed && detailRow == null && linesOpen == 0) {
            // Clear-then-show, same reasoning as the star-write snackbar above.
            onDismissWriteFailureShown()
            snackbarHostState.showSnackbar(dismissWriteFailedMessage)
        }
    }
    // A full-screen page (its own app bar) that REPLACES the departures Scaffold, so it covers the
    // top bar and reads as a real destination rather than an overlay — the home for the star and the
    // line's full disruption text, and where the maps/nav hand-off will land (`TODO.md`).
    val detailLoaded = detailHit?.second
    val detailNow = detailHit?.third ?: now
    if (detailLoaded != null && detailRow != null) {
        RouteDetailScreen(
            row = detailRow,
            isStarred = StarredRow.of(detailRow) in starred,
            // Same rule as the list card: only a timed row with starring available is pinnable.
            starrable = starringAvailable && detailRow.stopDisruption == null && detailRow.upcoming.isNotEmpty(),
            // Per this row, across BOTH uncertainty axes: its line wasn't determined (a blank id, or
            // one TfL omitted), OR its stop's own disruption lookup (a closure/move) failed. Either
            // leaves the row unchecked — clean only when TfL checked its line AND its stop's
            // disruption returned (SPEC principle 1).
            disruptionUnknown = detailLoaded.lineUncheckedFor(detailRow) ||
                detailRow.stopId in detailLoaded.stopsDisruptionUnknown,
            // A check for this row still out on a cold load — its stop's closure check, or its line's
            // status with nothing failed yet: "checking", neither "couldn't check" nor a clean
            // "no disruptions" until it's back.
            disruptionChecking = detailLoaded.checkingDisruptionsFor(detailRow),
            // Its line's page has its line's check alone, aged by its own stamp (SPEC D4).
            lineUnknown = detailLoaded.lineDoubtedFor(detailRow, detailNow),
            lineChecking = detailLoaded.checkingLineFor(detailRow),
            // This row's own age (the same per-row rule the card uses to withhold countdowns): a
            // stale snapshot's disruption status isn't presented as current (SPEC D4).
            stale = Staleness.isStale(detailRow.fetchedAt, detailNow),
            now = detailNow,
            onToggleStar = { onToggleStar(detailRow) },
            onBack = { detailKey = null },
            focus = detailDestination?.let { RouteFocus(it, detailBranch) },
            onDismissAlert = if (detailRow.status != null) {
                { onDismissAlert(detailRow) }
            } else {
                null
            },
            // One planned alert dismissed on its own: passed as a row standing for just that alert.
            onDismissPlanned = { planned ->
                onDismissAlert(detailRow.copy(status = null, stopDisruption = null, plannedAlerts = listOf(planned)))
            },
            journeys = journeys,
            journeysLoading = journeysLoading,
            onToggleJourney = onToggleJourney,
            onDismissJourneyTip = onDismissJourneyTip,
        )
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // A journey's own view heads its content with the full journey instead: the bar
                    // shares its row with the freshness stamp, too narrow for both names.
                    if (journeyViewOpen) {
                        Unit
                    } else if (platformRows != null) {
                        // Elided from the start, so a narrow bar (it shares the row with the
                        // freshness stamp) keeps the platform — the part that tells platforms apart.
                        Text(
                            withArrowIcons(platformView?.title ?: platformTitle),
                            inlineContent = arrowInlineContent(LocalContentColor.current),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.StartEllipsis,
                        )
                    } else {
                        if (stationTitle != null) {
                            // The station is the page's subject, so a long name shows as much as fits.
                            Text(stationTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            AppTitle()
                        }
                    }
                },
                navigationIcon = {
                    // A drilled-into platform view is a destination of its own: back returns to the
                    // full list (the system back does too — see the BackHandler above).
                    if (journeyViewOpen) {
                        IconButton(onClick = { journeyViewKey = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else if (platformRows != null) {
                        IconButton(onClick = { closeView() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else if (stationTitle != null) {
                        IconButton(onClick = onCloseStation) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    } else {
                        AppBarMark()
                    }
                },
                actions = {
                    // The drawn rows' own fetch, not a newer one they don't hold yet (Codex, #524).
                    FreshnessStamp(drawnState ?: state, drawnNow, onRefresh)
                    // Crosshairs: "use my location" (SPEC *Finding stops*). Near me it re-locates, as
                    // pull-to-refresh does; on a From… station page it goes back to the near-me list.
                    IconButton(onClick = onLocate ?: onRefresh) {
                        Icon(CrosshairIcon, contentDescription = stringResource(R.string.locate_here))
                    }
                    // A station's "To…": pick where to, and plan the trip there.
                    if (stationTitle != null && onPlanTo != null && platformRows == null && !journeyViewOpen) {
                        TextButton(onClick = onPlanTo) { Text(stringResource(R.string.menu_to)) }
                    }
                    // Near me, the same in one tap: the Directions glyph, To… for a screen reader. The
                    // overflow keeps its To… item beside From….
                    if (stationTitle == null && onPlanTo != null && platformRows == null && !journeyViewOpen) {
                        IconButton(onClick = onPlanTo) {
                            Icon(DirectionsIcon, contentDescription = stringResource(R.string.menu_to))
                        }
                    }
                    if (stationTitle == null) {
                        AppOverflowMenu(updateAvailable, onOpenAppListing) { close ->
                            if (onFindStation != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_from)) },
                                    onClick = {
                                        close()
                                        onFindStation()
                                    },
                                )
                            }
                            // To… from here: pick a destination, then plan the trip from the stops
                            // near the rider (SPEC *Trips with a change*).
                            if (onPlanTo != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_to)) },
                                    onClick = {
                                        close()
                                        onPlanTo()
                                    },
                                )
                            }
                            // One checkbox per mode nearby (SPEC *Finding stops → Hiding a mode*):
                            // ticked is shown. The menu stays open, so several can be toggled.
                            if (onSetModeGroupShown != null) {
                                HorizontalDivider()
                                ModeGroups.ALL.forEach { group ->
                                    val shown = !ModeGroups.isHidden(group, hiddenModes)
                                    DropdownMenuItem(
                                        text = { Text(groupName(group)) },
                                        leadingIcon = { Checkbox(checked = shown, onCheckedChange = null) },
                                        onClick = { onSetModeGroupShown(group, !shown) },
                                        modifier = Modifier.semantics {
                                            toggleableState = ToggleableState(shown)
                                            role = Role.Checkbox
                                        },
                                    )
                                }
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = {
                                    close()
                                    onOpenSettings()
                                },
                            )
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
                                    showAbout = true
                                },
                            )
                        }
                    } else {
                        // A station's page keeps the near-me list's own items to it, but a problem seen
                        // here is reported from here (maintainer, 2026-09-29): the activity's app menu,
                        // as a trip's page offers it, since a station is hosted without the list's.
                        AppMenuOverflow()
                    }
                },
            )
        },
    ) { innerPadding ->
        // The trip on the way, pinned above the near-me list (SPEC *On the way*): from the tracker's
        // state, no request of its own. Not on a station's page, nor a drill-down from the list (a
        // station, a platform or a starred journey), each its own view.
        val onTheWay = LocalOnTheWayBanner.current?.takeIf { stationTitle == null && platformRows == null && !journeyViewOpen }
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (onTheWay != null) OnTheWayBanner(onTheWay, now, Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            val content = Modifier.fillMaxWidth().weight(1f)
            when (state) {
                // A more direct update prompt than the top-bar overflow dot, which is easy to miss
                // while waiting on a cold load: at the bottom, in a slot kept (with one as tall at the
                // top) whether or not it shows, so the spinner stays centered in one place either way.
                // Scrolls when a short screen and a large font can't fit the slots and the spinner,
                // rather than clipping the button; it spaces out to the full height otherwise.
                DeparturesUiState.Loading -> {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = content
                            .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.background))
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        UpdateAvailableButton(onClick = {}, modifier = Modifier.padding(bottom = 16.dp), shown = false)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.departures_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        UpdateAvailableButton(onClick = onOpenAppListing, modifier = Modifier.padding(top = 16.dp), shown = updateAvailable)
                    }
                }

                is DeparturesUiState.Loaded -> if (journeyViewOpen && journeyViewCard == null) {
                    // The saved journeys are still loading: a placeholder until the card is back.
                    Centered(content) { CircularProgressIndicator() }
                } else if (platformPending || platformRows == null && journeyViewCard == null && listPending) {
                    // The snapshot's rows are being worked out off the main thread, for a list or view
                    // with none to stand in yet: a placeholder, never an empty list that reads as
                    // "No departures" (SPEC principle 1).
                    Centered(content) { CircularProgressIndicator() }
                } else if (journeyViewCard != null) {
                    // A journey's own view: just its card, each group headed by where it boards, under
                    // Swap and Unstar. Rendered from the same snapshot as the list (SPEC D4).
                    LoadedContent(
                        drawnLoaded ?: state, drawnNow, onPullRefresh ?: onRefresh, refreshing, content, rows = emptyList(),
                        listState = drillListState,
                        journeyCards = listOf(journeyViewCard),
                        journeyView = true,
                        onFlipJourney = onFlipJourney,
                        onUnstarJourney = onToggleJourney,
                        onRetryJourneyRoutes = { journeyRouteRetry++ },
                        starred = starred,
                        onToggleStar = onToggleStar,
                        starringAvailable = starringAvailable,
                        onOpenDetail = openDetail(UsageEvent.Tap.STOP_ROW),
                        onOpenChangeDetail = openDetail(UsageEvent.Tap.CHANGE_CARD),
                        onOpenSettings = onOpenSettings,
                        dismissed = dismissed,
                        onDismissAlert = onDismissAlert,
                    )
                } else {
                    LoadedContent(
                        drawnState ?: state, drawnNow, onPullRefresh ?: onRefresh, refreshing, content, shownRows,
                        listCards = shownCards,
                        listState = if (platformRows != null) drillListState else listState,
                        dismissedClosures = if (platformRows != null) emptyList() else dismissedClosures,
                        sharedNotices = sharedNotices,
                        // The platform view is one place: no distances (its header would only repeat the
                        // title).
                        stopDistanceMeters = if (platformRows != null) emptyMap() else stopDistanceMeters,
                        onOpenStopMap = onOpenStopMap,
                        // Journey cards sit atop the near-me list only, not a platform or station view.
                        // A far journey's card sits at the foot, once revealed, never among the near ones.
                        journeyCards = if (platformRows != null) emptyList() else journeyCards.filter { it.journey.key !in farJourneyMeters },
                        farJourneyCards = if (platformRows != null || !farRevealed) emptyList() else journeyCards.filter { it.journey.key in farJourneyMeters },
                        farJourneyMeters = farJourneyMeters,
                        onRevealFar = if (platformRows == null && !farRevealed && farJourneyMeters.isNotEmpty()) {
                            {
                                UsageEvents.log(UsageEvent.FarawayFavorites)
                                farReveal.reveal()
                            }
                        } else {
                            null
                        },
                        // Every nearby row is on a journey card above: nothing to call "no departures".
                        nearbyShownAbove = platformRows == null && rows.isEmpty() && nearbyRows.isNotEmpty(),
                        onFlipJourney = onFlipJourney,
                        onRetryJourneyRoutes = { journeyRouteRetry++ },
                        starred = starred,
                        onToggleStar = onToggleStar,
                        starringAvailable = starringAvailable,
                        hiddenModes = hiddenModes,
                        onHideMode = hideMode,
                        modesByPlace = drawnFrom?.placeModes.orEmpty(),
                        onShowAllModes = onShowAllModes,
                        // Not on a platform's own view, which is one place.
                        farther = if (platformRows != null) emptyList() else fartherShown,
                        // Hiding a mode applies to the loading cards too, as to the loaded rows.
                        // Unfiltered: hiding a mode drops its loading cards from view, but they're still
                        // loading (see [DepartureList]'s held cards).
                        // From the drawn snapshot, as the rows are: a stop that lands is pending no
                        // more in the same frame its rows appear.
                        pending = if (platformRows != null) emptyList() else (drawnLoaded ?: state).pendingStops,
                        holdLanded = platformRows == null,
                        pendingTracker = pendingTracker,
                        onOpenFarther = onOpenFarther,
                        onOpenDetail = openDetail(UsageEvent.Tap.STOP_ROW),
                        onOpenChangeDetail = openDetail(UsageEvent.Tap.CHANGE_CARD),
                        onOpenSettings = onOpenSettings,
                        dismissed = dismissed,
                        onDismissAlert = onDismissAlert,
                        // The full list and a whole-station view drill down to a platform; inside a platform
                        // view the header is inert. From a station, the station is remembered so back
                        // returns to it.
                        onOpenPlatform = if (platformRows != null && !platformIsStation) {
                            null
                        } else {
                            { group ->
                                if (platformRows != null) {
                                    parentStationIds = platformStopIds
                                    parentStationTitle = platformTitle
                                } else {
                                    parentStationIds = null
                                }
                                platformStopIds = group.rows.mapTo(LinkedHashSet()) { it.stopId }.joinToString(",")
                                platformIsStation = false
                                platformKey = group.splitKey
                                platformTitle = groupHeaderTitle(group.stopName, group.qualifier)
                            }
                        },
                        // The whole station: the tapped place's clusters, keyed blank so the view keeps all of
                        // their stops' groups (see platformView). Membership is resolved from each snapshot's
                        // stops, not the groups on screen: the near-me fold can leave a sibling platform with
                        // no group, and a warned stop groups under a per-stop key (Codex).
                        onOpenStation = if (platformRows != null) {
                            null
                        } else {
                            { group ->
                                platformStopIds = group.rows.mapTo(LinkedHashSet()) { stationClusterOf(it.clusterId, it.stopId) }
                                    .joinToString(",")
                                platformIsStation = true
                                parentStationIds = null
                                platformKey = ""
                                platformTitle = group.stopName
                            }
                        },
                        // A platform/station drill-down shows one place's stops, not the near-me set, so
                        // the "your location is low-confidence" banner doesn't apply there.
                        locationBanner = if (platformRows != null) null else locationBanner,
                        // A journey heading opens the journey's own view (from the full list only).
                        onOpenJourney = { journey ->
                            UsageEvents.log(UsageEvent.Tapped(UsageEvent.Tap.JOURNEY_CARD))
                            journeyViewKey = journey.key
                        },
                        // The full near-me list only: not a platform or station drill-down, nor a
                        // searched station's page, each of which is about one place.
                        favoritePlaces = if (platformRows != null || stationTitle != null) emptyList() else favoritePlaces,
                        onRouteToPlace = onRouteToPlace,
                        onEditFavoritePlaces = onEditFavoritePlaces,
                        onTelemetryInviteAnswer = onTelemetryInviteAnswer.takeIf { platformRows == null && stationTitle == null },
                        watchInstall = watchInstall.takeIf {
                            onTelemetryInviteAnswer == null && platformRows == null && stationTitle == null
                        },
                        // The full list only, as the place chips: not a platform, station or searched page.
                        disruptionsRow = disruptionsRow.takeIf { platformRows == null && stationTitle == null },
                    )
                }

                is DeparturesUiState.Error ->
                    // Under the pull box with a scrollable child so a downward swipe refreshes
                    // the error screen too (SPEC D6), not only the button.
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onPullRefresh ?: onRefresh, modifier = content) {
                        val scrollState = rememberScrollState()
                        Centered(
                            Modifier.fillMaxSize()
                                .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.background))
                                .verticalScroll(scrollState),
                        ) {
                            Text(
                                text = stringResource(errorMessage(state.kind)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            RefreshButton(onPullRefresh ?: onRefresh, Modifier.padding(top = 16.dp))
                        }
                    }
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


/** A stop's station identity for the whole-station view: its StopArea cluster, else the stop alone. */
internal fun stationClusterOf(clusterId: String, stopId: String): String = clusterId.ifBlank { "\u0000$stopId" }

/** The app's mark at the start of the departures app bar, and the location gate's. */
@Composable
internal fun AppBarMark() {
    // The app's route-lines mark on a themed tile — white in light, black in
    // dark — so it sits on the app bar without a fixed dark box. The arrow is a
    // separate, tintable layer flipped to contrast the tile (dark on white, white
    // on black); the colored lines stay put. The marks fill only the ~72/108
    // adaptive-icon safe zone, so requiredSize(48dp) draws them larger than the
    // 32dp box and the box clips back, rather than sitting tiny in the middle.
    // Decorative — the title already names the app, so no content description.
    // Follow the active Material theme (which may be dark from the system or an
    // explicit override), not the raw system setting, so the tile matches the
    // surface it sits on.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (darkTheme) Color.Black else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_appbar_route_lines),
            contentDescription = null,
            modifier = Modifier.requiredSize(48.dp),
        )
        Image(
            painter = painterResource(R.drawable.ic_appbar_route_arrow),
            contentDescription = null,
            modifier = Modifier.requiredSize(48.dp),
            colorFilter = ColorFilter.tint(if (darkTheme) Color.White else Color.Black),
        )
    }
}

@Composable
private fun LoadedContent(
    state: DeparturesUiState.Loaded,
    now: Instant,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier,
    // The rows to render, computed once by the caller so the list and the route-detail page share
    // the same grouping/ordering (SPEC D4 / D8).
    rows: List<DepartureRow>,
    // [rows] as the list's stop cards draw them, worked out with them on the list's worker.
    listCards: ListCards = ListCards.NONE,
    listState: LazyListState = rememberLazyListState(),
    // The near-me closures dismissed, for a closed place's heading ([DepartureList]).
    dismissedClosures: List<DepartureRow> = emptyList(),
    // The notices filed against more than one stop of a place ([planNotices]).
    sharedNotices: Set<Pair<String, String>> = emptySet(),
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    onOpenStopMap: ((String, String) -> Unit)? = null,
    journeyCards: List<JourneyCard> = emptyList(),
    // True when the nearby list is empty only because the journey cards above already show it all.
    nearbyShownAbove: Boolean = false,
    onFlipJourney: (StarredJourney) -> Unit = {},
    onRetryJourneyRoutes: () -> Unit = {},
    onOpenJourney: ((StarredJourney) -> Unit)? = null,
    journeyView: Boolean = false,
    onUnstarJourney: ((StarredJourney) -> Unit)? = null,
    farJourneyCards: List<JourneyCard> = emptyList(),
    farJourneyMeters: Map<String, Double> = emptyMap(),
    onRevealFar: (() -> Unit)? = null,
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    starringAvailable: Boolean = true,
    farther: List<FartherCard> = emptyList(),
    onOpenFarther: (CollapsedPlaces.Place) -> Unit = {},
    // Open the full-screen route detail for a tapped card; the caller holds the open-route state.
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit = { _, _ -> },
    // The same for a row on a journey's change card, counted apart (UsageEvent).
    onOpenChangeDetail: (DepartureRow, RouteFocus?) -> Unit = onOpenDetail,
    // Opens Settings from a National Rail line's "No key".
    onOpenSettings: () -> Unit = {},
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // Drill into one group's platform/pole; null disables the tap.
    onOpenPlatform: ((StopGroup) -> Unit)? = null,
    // Drill into the whole place of the tapped group, from a tap on the header's place name.
    onOpenStation: ((StopGroup) -> Unit)? = null,
    // Non-null when the shown stops are backed by a low-confidence location: a top banner says so
    // and offers "Try again" (runs [onRefresh], a re-locate). Null hides it.
    locationBanner: LocationBanner? = null,
    // The modes hidden from this list, their banner's "Show all", and the long-press "Hide ‹mode›"
    // (null on a list that doesn't offer it). See [MainScreen].
    hiddenModes: Set<String> = emptySet(),
    onHideMode: ((String) -> Unit)? = null,
    onShowAllModes: () -> Unit = {},
    // Each place's modes not yet hidden (by cluster), for a header's "Hide ‹mode›" items.
    modesByPlace: Map<String, Set<String>> = emptyMap(),
    // A cold load's stops not back yet ([DeparturesUiState.Loaded.pendingStops]), each a "Loading"
    // card where it will land; empty where the view is one place (a platform, a journey). Hidden
    // modes are filtered out here, for display ([visiblePending]).
    pending: List<StopRef> = emptyList(),
    // Whether a loading card that lands on screen is held as a card (see [DepartureList]).
    holdLanded: Boolean = false,
    pendingTracker: PendingTracker = remember { PendingTracker() },
    // The favorite places to offer as route chips atop the list (see [MainScreen]); empty for none.
    favoritePlaces: List<FavoritePlace> = emptyList(),
    onRouteToPlace: (TripDestination.Place) -> Unit = {},
    onEditFavoritePlaces: (() -> Unit)? = null,
    // The telemetry question's answer (see [MainScreen]); null shows no card.
    onTelemetryInviteAnswer: ((Boolean) -> Unit)? = null,
    // The watch install offer (see [MainScreen]); null shows no card.
    watchInstall: WatchInstallActions? = null,
    // The disruptions row ([HomeDisruptionsRow]), under the place chips; null shows none.
    disruptionsRow: TripRow? = null,
) {
    // Hiding a mode applies to the loading cards too, as to the loaded rows.
    val shownPending = remember(pending, hiddenModes) { visiblePending(pending, hiddenModes) }
    // Whether an empty list can be trusted as a real "no departures". It can only when
    // EVERY retained stop is fresh and the refresh was complete: a stale or un-refreshed
    // stop's empty rows might be expired predictions, not a true absence, and newer
    // services we couldn't fetch may exist (SPEC D4 / principle 1). So this reads every
    // stop's age and the partial/failure flags — not the freshest-stop stamp, which would
    // let one fresh stop mask a stale one's uncertainty. Per-row staleness (the withhold)
    // is decided per stop inside the card from that row's own age.
    val emptyStateUncertain = rememberEmptyStateUncertain(state, now)
    // Pull-to-refresh over the whole loaded surface (SPEC D6).
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // The location behind these stops isn't current (SPEC *Finding stops*, principle 2):
            // either a stale last-known fix (APPROXIMATE) or a re-locate that couldn't update
            // (UPDATE_FAILED). Shown first — it frames every stop below — with a "Try again" that
            // re-locates (onRefresh). It clears once a fresh fix resolves.
            locationBanner?.let {
                ActionBanner(
                    text = stringResource(
                        when (it) {
                            LocationBanner.APPROXIMATE -> R.string.location_approximate
                            LocationBanner.UPDATE_FAILED -> R.string.location_update_failed
                            LocationBanner.COARSE -> R.string.location_coarse
                        },
                    ),
                    onTryAgain = onRefresh,
                )
            }
            // Modes the user hid (SPEC *Finding stops → Hiding a mode*): one line saying which, so a
            // shorter list never passes for all there is, with "Show all" to bring them back.
            if (hiddenModes.isNotEmpty()) {
                ActionBanner(
                    text = stringResource(
                        R.string.modes_hidden,
                        hiddenGroupsLabel(hiddenModes),
                    ),
                    actionLabel = stringResource(R.string.modes_show_all),
                    onAction = onShowAllModes,
                )
            }
            // Independent, not exclusive: a kept snapshot can be BOTH incomplete (a stop
            // was missing) and failed-to-refresh, and both facts have to stay visible —
            // collapsing them into one branch would drop the "stops missing" warning and
            // let the omitted stops go silent (SPEC principle 2).
            if (state.refreshFailure != null) {
                Banner(stringResource(refreshFailureMessage(state.refreshFailure)))
            }
            if (state.partialRefresh) {
                Banner(partialRefreshMessage(state.partialStops.values.map { it.name }.distinct(), state.partialReason))
            }
            // Arrivals loaded but their disruption status couldn't be checked — say so
            // rather than let the times read as verified-clean (SPEC *Disruptions*).
            // While a cold load is still checking, the stamp says so instead, so a banner doesn't
            // push the list down and back up (SPEC *Freshness → Cold load*).
            // Where the disruptions row shows, it says so itself, as "Unknown" (maintainer, 2026-10-05): a
            // banner coming and going would move the list under it.
            if (state.disruptionUnknown && !state.checkingDisruptions && disruptionsRow == null) {
                Banner(stringResource(R.string.disruptions_unknown))
            }
            // Starred journeys still show when nothing nearby has departures: their origins can be
            // farther away, and hiding them behind "No departures" would drop live trains.
            // A dismissed closure still draws its place's heading, so it keeps the list up.
            if (rows.isEmpty() && journeyCards.isEmpty() && farJourneyCards.isEmpty() && onRevealFar == null && shownPending.isEmpty() && dismissedClosures.isEmpty()) {
                // Scrollable even though it doesn't overflow: PullToRefreshBox reads the
                // pull from a scrollable child's nested-scroll events, so a plain Column
                // here would leave pull-to-refresh dead on the empty state (only the
                // button would work). verticalScroll forwards the gesture; the content
                // still centers. With farther cards below, it can overflow, so it fades like a list.
                val scrollState = rememberScrollState()
                Centered(
                    Modifier.fillMaxSize()
                        .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.background))
                        .verticalScroll(scrollState),
                ) {
                    // The telemetry question still leads where there's no list to lead (Codex, PR #447):
                    // a first run with nothing near has it asked all the same.
                    onTelemetryInviteAnswer.takeIf { !journeyView }?.let { answer ->
                        TelemetryInviteCard(onAnswer = answer, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    watchInstall.takeIf { !journeyView }?.let { actions ->
                        WatchInstallCard(actions, modifier = Modifier.padding(bottom = 16.dp))
                    }
                    // No list to lead, so the place chips head the empty state, inside its scroller so
                    // they move with it (Codex): nothing near has departures, just when a route
                    // elsewhere is wanted. Centered like the rest, and inset already by [Centered].
                    if (favoritePlaces.isNotEmpty()) {
                        FavoriteChips(
                            favoritePlaces,
                            onRouteToPlace,
                            Modifier.padding(bottom = 16.dp),
                            onEditPlaces = onEditFavoritePlaces,
                            contentPadding = PaddingValues(0.dp),
                            centered = true,
                        )
                    }
                    // Under the place chips, as atop the list.
                    disruptionsRow?.let { HomeDisruptionsRow(it, Modifier.padding(bottom = 16.dp)) }
                    // A stale snapshot with nothing left can't be read as "no departures"
                    // — the data is too old to trust that conclusion, and newer ones may
                    // exist (SPEC D4). Prompt a refresh instead of asserting an empty list.
                    Text(
                        // With modes hidden, an empty list says so rather than "no departures": the
                        // hidden modes may well be running (SPEC *Finding stops → Hiding a mode*).
                        text = when {
                            hiddenModes.isNotEmpty() -> stringResource(
                                R.string.modes_hidden_empty,
                                hiddenGroupsLabel(hiddenModes),
                            )
                            emptyStateUncertain -> stringResource(R.string.departures_stale_empty)
                            else -> stringResource(R.string.departures_empty)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                    // Keep the farther cards reachable even when the nearest clusters returned
                    // nothing — that's exactly when they are most useful (SPEC principle 2).
                    FartherCards(
                        farther,
                        state.stops.mapTo(HashSet()) { it.stopId } - state.openedLoadingStopIds,
                        state.unavailableStopIds,
                        onOpenFarther,
                        Modifier.padding(top = 16.dp),
                    )
                }
            } else {
                DepartureList(
                    rows, listCards, now, starred, onToggleStar, starringAvailable, stopDistanceMeters,
                    farther = farther,
                    onOpenFarther = onOpenFarther,
                    pending = shownPending,
                    trackedPending = pending,
                    hiddenModes = hiddenModes,
                    holdLanded = holdLanded,
                    pendingTracker = pendingTracker,
                    fetchedStopIds = state.stops.mapTo(HashSet()) { it.stopId } - state.openedLoadingStopIds,
                    unavailableStopIds = state.unavailableStopIds,
                    closurePending = state.closurePending,
                    listState = listState,
                    onOpenSettings = onOpenSettings,
                    onHideMode = onHideMode,
                    modesByPlace = modesByPlace,
                    onOpenDetail = onOpenDetail,
                    onOpenChangeDetail = onOpenChangeDetail,
                    onDismissAlert = onDismissAlert,
                    dismissedClosures = dismissedClosures,
                    sharedNotices = sharedNotices,
                    stopHubIds = remember(state.stops) {
                        state.stops.filter { it.hubId.isNotBlank() }.associate { it.stopId to it.hubId }
                    },
                    // Each stop's real StopArea (not the display-name fallback), so a place's notice
                    // can stand at its nearest member even where that member has nothing listed.
                    stopClusterIds = remember(state.stops) {
                        state.stops.filter { it.clusterId.isNotBlank() && it.clusterId != it.stopName }
                            .associate { it.stopId to it.clusterId }
                    },
                    onOpenPlatform = onOpenPlatform,
                    onOpenStation = onOpenStation,
                    onOpenStopMap = onOpenStopMap,
                    journeyCards = journeyCards,
                    onFlipJourney = onFlipJourney,
                    onRetryJourneyRoutes = onRetryJourneyRoutes,
                    onOpenJourney = onOpenJourney,
                    journeyView = journeyView,
                    onUnstarJourney = onUnstarJourney,
                    farJourneyCards = farJourneyCards,
                    farJourneyMeters = farJourneyMeters,
                    onRevealFar = onRevealFar,
                    // Not in a journey's own view, nor when every nearby row is already on a journey
                    // card above.
                    favoritePlaces = favoritePlaces,
                    onRouteToPlace = onRouteToPlace,
                    onEditFavoritePlaces = onEditFavoritePlaces,
                    // Not in a journey's own view, which is about that journey.
                    onTelemetryInviteAnswer = onTelemetryInviteAnswer.takeIf { !journeyView },
                    watchInstall = watchInstall.takeIf { !journeyView },
                    disruptionsRow = disruptionsRow.takeIf { !journeyView },
                    nearbyEmptyNote = if (rows.isEmpty() && !journeyView && !nearbyShownAbove && shownPending.isEmpty() && dismissedClosures.isEmpty()) {
                        // With modes hidden, say so rather than "no departures": they may be running.
                        if (hiddenModes.isNotEmpty()) {
                            stringResource(
                                R.string.modes_hidden_empty,
                                hiddenGroupsLabel(hiddenModes),
                            )
                        } else {
                            stringResource(
                                if (emptyStateUncertain) R.string.departures_stale_empty else R.string.departures_empty_nearby,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Whether an empty list can't be trusted as a real "no departures" at [now] ([emptyStateUncertain]).
 * The pass over the stops, finding the oldest and newest stamps, runs on the worker once per state
 * (AGENTS.md *Main thread: read and dispatch only*). Each tick then reads only those two and the
 * state's flags, so it is exact at once. While a new state's stamps are being worked out, an empty
 * list isn't trusted (SPEC principle 1; Codex on #553).
 */
@Composable
internal fun rememberEmptyStateUncertain(state: DeparturesUiState.Loaded, now: Instant): Boolean {
    val slot = remember { mutableStateOf<Worked<Inputs, StopStamps>?>(null) }
    val stamps = rememberWorked(slot, Inputs(state.stops)) { stopStamps(state.stops) } ?: return true
    return emptyStateUncertain(state, stamps, now)
}

/** The oldest and newest of some stops' fetch stamps; both null for none. */
internal class StopStamps(val oldest: Instant?, val newest: Instant?)

/** [stops]' oldest and newest fetch stamps: a pass over every stop, so never in composition. */
@WorkerThread
internal fun stopStamps(stops: List<StopArrivals>): StopStamps {
    var oldest: Instant? = null
    var newest: Instant? = null
    for (stop in stops) {
        if (oldest == null || stop.fetchedAt < oldest) oldest = stop.fetchedAt
        if (newest == null || stop.fetchedAt > newest) newest = stop.fetchedAt
    }
    return StopStamps(oldest, newest)
}

/**
 * Whether an empty list can't be trusted as a real "no departures" at [now]: the refresh failed or
 * was partial, or any retained stop is stale. Every stop's age counts, not the freshest stop's stamp.
 * Every stop is aged by the same clock, so the oldest stop is the first past the threshold, and the
 * newest is the first stamped ahead of the clock ([Staleness.isFromFuture]): those two stand for all.
 */
internal fun emptyStateUncertain(state: DeparturesUiState.Loaded, stamps: StopStamps, now: Instant): Boolean =
    state.refreshFailure != null ||
        state.partialRefresh ||
        stamps.oldest?.let { Staleness.isStale(it, now) } == true ||
        stamps.newest?.let { Staleness.isStale(it, now) } == true ||
        // No retained stops to age individually — fall back to the snapshot stamp, so an
        // aged empty snapshot (e.g. one restored from storage) still prompts a refresh.
        (state.stops.isEmpty() && Staleness.isStale(state.fetchedAt, now))

/** Each saved journey that boards at a route page's stop, with the page's stops it ends at, and all of those. */
internal class JourneysHere(val byJourney: Map<StarredJourney, Set<String>>, val starredStopIds: Set<String>) {
    companion object {
        val NONE = JourneysHere(emptyMap(), emptySet())
    }
}

/**
 * [JourneysHere] as a route page shows it (a stand-in for the same stop and line included), and whether
 * it's [current]: worked out for exactly the journeys and stops the page has now. Only a current answer
 * takes a tap or readies the rail (Codex on #555).
 */
internal class JourneysShown(val here: JourneysHere, val current: Boolean)

/**
 * [journeysHere], worked out on the worker (AGENTS.md *Main thread: read and dispatch only*). The last
 * answer stands in for this same stop and line while the next is worked out (a journey starred, the
 * stops landing), so a star doesn't blink off and back; none for another row's. Only a [current]
 * answer may serve a tap: a stand-in, or none, could miss the saved journey the tap should take off and
 * star a second one instead (Codex on #555).
 */
@Composable
internal fun rememberJourneysHere(
    journeys: List<StarredJourney>,
    stops: RouteStopsUi,
    stopId: String,
    lineId: String,
): JourneysShown {
    val key = Inputs(stopId, lineId, journeys, stops)
    // The same page reopened finds its last answer at once ([JourneysMemo]), so its stars are there in
    // its first frame rather than inserted after, rewrapping a row (Codex on #555).
    val slot = remember { mutableStateOf(JourneysMemo.get(key)) }
    val here = rememberWorked(slot, key, keep = ::sameRowAndLine) {
        journeysHere(journeys, stops as? RouteStopsUi.Loaded, stopId, lineId)
    } ?: return JourneysShown(JourneysHere.NONE, current = false)
    val answered = slot.value
    SideEffect { if (answered != null) JourneysMemo.put(answered) }
    return JourneysShown(here, current = answered?.key == key)
}

/** [liftDependent], worked out on the worker; false until it's in. */
@Composable
internal fun rememberLiftDependent(table: StepFreeAccess?, listed: List<RouteStop>): Boolean {
    val slot = remember { mutableStateOf<Worked<Inputs, Boolean>?>(null) }
    return rememberWorked(slot, Inputs(table, listed)) { liftDependent(table, listed) } == true
}

/**
 * [stepFreeLevels], worked out on the worker with the lifts out applied to the whole table; null until
 * there's an answer for these stops, so the page holds its placeholder rather than draw the rail
 * unmarked and mark it a moment later (Codex on #555). An earlier answer stands in only where it's
 * exact: for the same table, lifts out, line and mode a station's level is the same whatever list it's
 * in, so a refreshed copy of the stops keeps its marks ([sameLevels]). Never across lifts newly out:
 * one may take a station's mark away, so while that's worked out none shows rather than one that may
 * be wrong (Codex on #555); a rail already shown stays ([rememberRailState]). Lifts only back in service
 * ([cleared]) can only add marks, so the last ones stay meanwhile rather than all blinking off (Codex on
 * #555). The last answers app-wide are kept ([StepFreeMemo]), so a page reopened has its marks in its
 * first frame.
 */
@Composable
internal fun rememberStepFreeLevels(
    table: StepFreeAccess?,
    liftsOut: Set<String>,
    listed: List<RouteStop>,
    lineId: String,
    mode: String,
    cleared: LiftsCleared? = null,
): StepFreeShown {
    // No table: none to mark by, unless it's still being read, when the answer is still to come.
    if (table == null) {
        val reading = LocalStepFreeLoading.current
        return StepFreeShown(if (reading) null else emptyMap(), known = !reading)
    }
    val key = Inputs(table, liftsOut, listed, lineId, mode)
    val slot = remember { mutableStateOf(StepFreeMemo.get(key)) }
    val levels = rememberWorked(slot, key, keep = { held, wanted -> sameLevels(held, wanted, cleared) }) { stepFreeLevels(table, liftsOut, listed, lineId, mode) }
    val answered = slot.value
    SideEffect { if (answered != null) StepFreeMemo.put(answered) }
    // Known only once worked out for exactly these inputs: a stand-in shows its marks, but never
    // readies a waiting rail, for a new route or new lifts out (Codex on #555).
    return StepFreeShown(levels, known = answered?.key == key)
}

/**
 * A route page's step-free marks: [levels] to show (a stand-in included; null while there's none), and
 * whether they're [known] for the inputs as they are now.
 */
internal class StepFreeShown(val levels: Map<String, StepFreeLevel>?, val known: Boolean)

/**
 * What a route page's rail shows: [stops], but held behind the list's own placeholder until its marks
 * are ready (the first step-free answer and the saved journeys' stars, [marksReady]), so the rail
 * doesn't shift as they land. Once these stops' rail has shown it stays, whatever answer is pending
 * after (the step-free table loading late, say), so the list being read never goes back to the
 * placeholder (Codex on #555); marks still to come land on it. Kept per page and followed route ([page]),
 * not per stops object, so a refresh that hands the same page a new copy of its stops (a cached
 * route's stops resolved again) never takes the rail away either; and never by the stops' contents,
 * which would walk the route on the main thread. A route that goes back to loading (the followed train
 * now another branch or destination) starts afresh: its new stops wait for their own marks.
 */
@Composable
internal fun rememberRailState(stops: RouteStopsUi, marksReady: Boolean, page: String): RouteStopsUi {
    var shown by remember(page) { mutableStateOf(false) }
    val rail = if (!marksReady && stops is RouteStopsUi.Loaded && !shown) RouteStopsUi.Loading else stops
    if (rail is RouteStopsUi.Loaded && !shown) SideEffect { shown = true }
    if (stops !is RouteStopsUi.Loaded && shown) SideEffect { shown = false }
    return rail
}

/**
 * Whether a step-free answer for [held] may stand in for [wanted]'s stations: the same table, line and
 * mode, whatever the list of stops, and the same set of lifts out (by identity), exact; or the set
 * before lifts only came back ([cleared]), which can only lack a mark, never show a wrong one.
 */
private fun sameLevels(held: Inputs, wanted: Inputs, cleared: LiftsCleared?): Boolean {
    val lifts = held.parts[1] === wanted.parts[1] ||
        (cleared != null && held.parts[1] === cleared.from && wanted.parts[1] === cleared.to)
    return held.parts[0] === wanted.parts[0] && lifts && held.parts[3] == wanted.parts[3] && held.parts[4] == wanted.parts[4]
}

/** Lifts out [to] after an answer that only brought some of [from] back into service. */
internal class LiftsCleared(val from: Set<String>, val to: Set<String>)

/**
 * The last route pages' answers by their exact inputs, so a page reopened finds its own, however many
 * others were opened since, up to [MAX_ENTRIES] (Codex on #555). A lookup hashes a key of a few parts,
 * never a pass over a list.
 */
internal open class AnswerMemo<T> {
    private val held = LruCache<Inputs, Worked<Inputs, T>>(MAX_ENTRIES)

    fun get(key: Inputs): Worked<Inputs, T>? = held.get(key)

    fun put(answer: Worked<Inputs, T>) {
        held.put(answer.key, answer)
    }

    companion object {
        const val MAX_ENTRIES = RouteStopsMemo.MAX_ENTRIES
    }
}

/** Saved-journeys answers for route pages reopened ([rememberJourneysHere]). */
internal object JourneysMemo : AnswerMemo<JourneysHere>()

/** Step-free answers for route pages reopened ([rememberStepFreeLevels]). */
internal object StepFreeMemo : AnswerMemo<Map<String, StepFreeLevel>>()

/** Whether two route-page keys are for the same stop and line, so one answer may stand in for the other. */
internal fun sameRowAndLine(held: Inputs, wanted: Inputs): Boolean =
    held.parts[0] == wanted.parts[0] && held.parts[1] == wanted.parts[1]

/**
 * Each of [journeys] that boards at [stopId] on [lineId]'s route page, with the stops on [page] it ends
 * at: by id, or placed on the page's route the way the journey card places it (a bus's way back boards
 * across the road).
 */
@WorkerThread
internal fun journeysHere(
    journeys: List<StarredJourney>,
    page: RouteStopsUi.Loaded?,
    stopId: String,
    lineId: String,
): JourneysHere {
    val pageSequence = page?.sequence
    val pageStopIds = page?.stops?.mapTo(HashSet()) { it.id }.orEmpty()
    val byJourney = journeys.mapNotNull { j ->
        // The saved ids only when the other end is itself on this page's list; a bus's
        // way back may board at a saved pole but alight across the road.
        val byId = when (stopId) {
            j.from.stopId -> setOf(j.to.stopId)
            j.to.stopId -> setOf(j.from.stopId)
            else -> null
        }?.takeIf { pageSequence == null || it.any { id -> id in pageStopIds } }
        val placed = byId ?: pageSequence?.let { seq ->
            listOf(j, j.reversed()).firstNotNullOfOrNull { cand ->
                Journeys.segment(cand, seq, lineId)?.takeIf { it.originId == stopId }?.destinationIds
            }
        }
        placed?.let { j to it }
    }.toMap()
    return JourneysHere(byJourney, byJourney.values.flatMapTo(HashSet()) { it })
}

/** Whether any of [listed] is step-free only by a lift in [table], so a lift outage can change it. */
@WorkerThread
internal fun liftDependent(table: StepFreeAccess?, listed: List<RouteStop>): Boolean =
    table != null && listed.any { table.byLift(it.id) }

/** Each of [listed]'s step-free level for [lineId] in [table], with the lifts in [liftsOut] out. */
@WorkerThread
internal fun stepFreeLevels(
    table: StepFreeAccess?,
    liftsOut: Set<String>,
    listed: List<RouteStop>,
    lineId: String,
    mode: String,
): Map<String, StepFreeLevel> {
    val withOut = table?.withLiftsOut(liftsOut) ?: return emptyMap()
    return listed.mapNotNull { stop -> withOut.levelFor(stop.id, lineId, mode)?.let { stop.id to it } }.toMap()
}

@Composable
private fun FreshnessStamp(state: DeparturesUiState, now: Instant, onRefresh: () -> Unit) {
    val text = when (state) {
        // The placeholder frame (before any snapshot is read from disk) still carries a
        // stamp — a pending "Loading…" — so the top bar is present from the first frame and
        // fills in with the real age when the snapshot arrives (SPEC snapshot-render), rather
        // than the stamp popping in late.
        DeparturesUiState.Loading -> stringResource(R.string.loading_stamp)
        is DeparturesUiState.Loaded -> {
            val age = Staleness.age(state.fetchedAt, now)
            when {
                // A cold load with nothing back yet — only failures so far, or cut short before any
                // stop landed: no update to stamp, so still loading, not "Just now".
                state.stops.isEmpty() && (state.statusPending || state.partialRefresh) -> stringResource(R.string.loading_stamp)
                // Stale outranks checking: rows past the cutoff withhold their times, and the stamp
                // must still offer the refresh they need (SPEC D4).
                Staleness.isStale(age) -> stringResource(R.string.stale_stamp)
                // Disruptions still being checked mid-load: said here rather than in a banner that
                // would move the list (maintainer, 2026-09-30).
                state.checkingDisruptions -> stringResource(R.string.checking_stamp)
                // The age alone ("1 min ago", "Just now"): an "Updated" before it cost top-bar room
                // and said nothing the position doesn't (maintainer, 2026-09-26).
                else -> RelativeTime.formatAge(age).replaceFirstChar { it.uppercaseChar() }
            }
        }
        // An error has its own full-screen message (no snapshot, so no age to stamp).
        is DeparturesUiState.Error -> return
    }
    // The stamp is tappable too, so the "Tap to refresh" it shows when stale does what
    // it says (the Refresh action beside it is the always-present control).
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
internal fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A [Banner] with a trailing "Try again" action — used for the low-confidence-location banner, where
 * the honest state is also actionable (re-locate). The text takes the remaining width so the action
 * stays a fixed trailing target; the button's own padding keeps the bar the same height as a plain
 * [Banner].
 */
@Composable
internal fun ActionBanner(
    text: String,
    onTryAgain: () -> Unit = {},
    actionLabel: String = stringResource(R.string.try_again),
    onAction: () -> Unit = onTryAgain,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DepartureList(
    rows: List<DepartureRow>,
    // [rows] grouped as the list draws them, each group's card with it ([ListCards]).
    listCards: ListCards,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    stopDistanceMeters: Map<String, Double>,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    onOpenChangeDetail: (DepartureRow, RouteFocus?) -> Unit = onOpenDetail,
    listState: LazyListState,
    farther: List<FartherCard> = emptyList(),
    onOpenFarther: (CollapsedPlaces.Place) -> Unit = {},
    // A cold load's stops not back yet, shown as "Loading" cards among the places ([pendingSlots]).
    pending: List<StopRef> = emptyList(),
    // Whether this is the list that shows those cards, so one landing on screen is held as a card
    // ([PendingTracker]); false on a platform or journey view.
    holdLanded: Boolean = false,
    // [pending] before hidden modes were filtered out: what the tracker follows, so hiding a mode
    // doesn't read as its stops landing.
    trackedPending: List<StopRef> = pending,
    // The modes hidden from this list, filtered out of a held card's lines too.
    hiddenModes: Set<String> = emptySet(),
    // Where the held cards live ([MainScreen] keeps it, so they outlive this list).
    pendingTracker: PendingTracker = remember { PendingTracker() },
    // The stops whose departures have been fetched, so an opened card with none shown can tell
    // "still loading" from "nothing running".
    fetchedStopIds: Set<String> = emptySet(),
    // The stops whose fetch failed, so an opened card whose stops all failed can offer a retry.
    unavailableStopIds: Set<String> = emptySet(),
    // The stops shown whose own closure check is still out: their headings show a spinner.
    closurePending: Set<String> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // The near-me closure notices the user dismissed ([ClosedNotice] wording only): a closed place
    // with nothing else to show keeps its heading and "Closed" chip without the notice.
    dismissedClosures: List<DepartureRow> = emptyList(),
    // Each stop's interchange, so an interchange's notice finds a member's sections ([planNotices]).
    stopHubIds: Map<String, String> = emptyMap(),
    stopClusterIds: Map<String, String> = emptyMap(),
    // The notices filed against more than one stop of a place, as (place, text) ([planNotices]).
    sharedNotices: Set<Pair<String, String>> = emptySet(),
    // Opens Settings from a National Rail line's "No key" (SPEC *National Rail*).
    onOpenSettings: () -> Unit = {},
    // Hides a mode from a long press on a near-me row or header; null keeps long-press as starring.
    onHideMode: ((String) -> Unit)? = null,
    // Each place's modes not yet hidden (by cluster), for a header's "Hide ‹mode›" items.
    modesByPlace: Map<String, Set<String>> = emptyMap(),
    onOpenPlatform: ((StopGroup) -> Unit)? = null,
    onOpenStation: ((StopGroup) -> Unit)? = null,
    onOpenStopMap: ((String, String) -> Unit)? = null,
    journeyCards: List<JourneyCard> = emptyList(),
    onFlipJourney: (StarredJourney) -> Unit = {},
    onRetryJourneyRoutes: () -> Unit = {},
    // Opens a journey's own view from a tap on its heading; null leaves the heading inert.
    onOpenJourney: ((StarredJourney) -> Unit)? = null,
    // The faraway journeys' cards once revealed, with each one's meters to its nearer end; and the
    // button that reveals them, null once they are (or when there are none).
    farJourneyCards: List<JourneyCard> = emptyList(),
    farJourneyMeters: Map<String, Double> = emptyMap(),
    onRevealFar: (() -> Unit)? = null,
    // True in a journey's own view: the title bar names the journey, so its heading is dropped, and
    // each of its groups is headed by its platform/pole so the rider sees where to board.
    journeyView: Boolean = false,
    // Unstars a journey from its own view's action row; null hides the button.
    onUnstarJourney: ((StarredJourney) -> Unit)? = null,
    // Why the near-me part is empty, shown under the journey cards when there are no nearby rows.
    nearbyEmptyNote: String? = null,
    // The favorite places to route to, as a chip row atop the list (near-me only); empty for none.
    favoritePlaces: List<FavoritePlace> = emptyList(),
    onRouteToPlace: (TripDestination.Place) -> Unit = {},
    onEditFavoritePlaces: (() -> Unit)? = null,
    // Answers the telemetry question from its card, first in the list; null shows no card.
    onTelemetryInviteAnswer: ((Boolean) -> Unit)? = null,
    // The watch install offer, first in the list too; null shows no card.
    watchInstall: WatchInstallActions? = null,
    // The disruptions row ([HomeDisruptionsRow]), under the place chips; null shows none.
    disruptionsRow: TripRow? = null,
    modifier: Modifier,
) {
    // The units near-me distances are written in: the Settings choice, resolved against the locale;
    // null (no labels) until the stored choice has been read.
    val distanceSystem = LocalDistanceSystem.current
    // Stop notices (SPEC *Disruptions*). On the near-me list (distances present) each rides with its
    // place: on its own pole's card, or as the place's own group above its sections, at the place's
    // distance ([NoticePlan]). Elsewhere (the watched list, a platform's own view) they lead as
    // standalone cards at the top — warnings lead there — each carrying its own place heading. The
    // remaining rows (timed and line-status) cluster into per-place groups so each gets a name header
    // — the flat list gives a card no boarding location once >1 place is on screen (SPEC D8).
    val closureRows = remember(rows) { rows.filter { it.stopDisruption != null } }
    val noticesInPlace = stopDistanceMeters.isNotEmpty()
    val leadingNotices = if (noticesInPlace) emptyList() else closureRows
    // The stops whose notice says they're closed, so a card with nothing running says "Closed".
    val closedStops = remember(closureRows) { ClosedStops.of(closureRows) }
    // Pass the full row set: groupByStop groups only the non-closure rows but counts each closure
    // alert's stop as a place, so a lone departures group beside a closure-only stop still shows
    // its header (SPEC *Disruptions*).
    // On the near-me list (distances present) a place is ordered by distance, not lifted for
    // carrying a line-status alert; the watched list keeps warnings leading (D1, SPEC *Disruptions*).
    // Worked out with the rows on the list's worker ([ListCards.of]), never here.
    val groups = listCards.groups
    // The groups with a stop whose closure check is still out, by key, worked out off the main
    // thread ([LocalWorker]) so each heading only looks itself up. None to find when none is out.
    val closureWorker = LocalWorker.current
    // Keyed by identity: comparing the groups or pending set by content would walk them here.
    val checkingGroups by produceState(emptySet<String>(), ByIdentity(groups), ByIdentity(closurePending), closureWorker) {
        value = if (closurePending.isEmpty()) {
            emptySet()
        } else {
            withContext(closureWorker) {
                groups.filter { group -> group.rows.any { it.stopId in closurePending } }.mapTo(HashSet()) { it.key }
            }
        }
    }
    // One place-wide header distance per cluster: the nearest of ALL the place's members, shared by
    // every direction group of that place — so a station split into direction headers shows one
    // consistent distance (its nearest member), not each direction's own members' nearest, which
    // could differ or read farther than the place's nearest (Codex P2, PR #109). Empty on the
    // watched list (no distances, D1).
    val placeDistanceMeters = remember(groups, stopDistanceMeters) {
        if (stopDistanceMeters.isEmpty()) {
            emptyMap()
        } else {
            groups.groupBy { it.placeKey }.mapNotNull { (place, placeGroups) ->
                placeGroups.asSequence()
                    .flatMap { it.rows.asSequence() }
                    .mapNotNull { stopDistanceMeters[it.stopId] }
                    .minOrNull()
                    ?.let { place to it }
            }.toMap()
        }
    }
    // The places still loading, less any already on screen (a station with one platform back) —
    // judged by every row, closures included, since a closure-only place has no group.
    val shownPlaceKeys = remember(rows) { rows.mapTo(HashSet()) { placeOf(it.clusterId, it.stopId) } }
    val pendingPlaces = remember(pending, shownPlaceKeys, stopDistanceMeters) {
        pendingPlaces(pending, shownPlaceKeys, stopDistanceMeters)
    }
    val trackedPlaces = remember(trackedPending, shownPlaceKeys, stopDistanceMeters) {
        pendingPlaces(trackedPending, shownPlaceKeys, stopDistanceMeters)
    }
    // A loading place that lands while its card is on screen stays a card, so the list doesn't jump
    // under the rider's eyes (SPEC *Freshness → Cold load*); one that lands off screen opens in full.
    // Which items the last frame drew, read only when a loading place lands (not on every scroll),
    // so a plain field rather than state.
    // Nothing counts as on screen once the list isn't (a full-screen page over it, another view):
    // a stop landing meanwhile lands out of sight.
    // A card with no distance (the watched list) is parked at the foot until its stop lands, so it's
    // held whenever it's drawn: where the stop goes soonest-first isn't known until then.
    val undistancedKeys = remember(trackedPlaces) {
        trackedPlaces.filterNot { it.distanced }.mapTo(HashSet<Any>()) { pendingItemKey(it) }
    }
    val currentUndistanced by rememberUpdatedState(undistancedKeys)
    LaunchedEffect(listState, pendingTracker) {
        try {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
                .collect { pendingTracker.onScreen = aboveLoadedRows(it, currentUndistanced) }
        } finally {
            pendingTracker.onScreen = emptySet()
        }
    }
    // Only the list that shows the loading cards holds them: a platform or journey view doesn't
    // (it has no loading cards, and a held place of the list's isn't one of its own).
    // Nor does it track while shown elsewhere: a card left on screen as the rider drilled in would
    // otherwise read as landing in view.
    val held = remember(trackedPlaces, holdLanded, pendingTracker.revision) {
        if (holdLanded) pendingTracker.update(trackedPlaces) else emptyMap()
    }
    // A held place that failed is let go (the banner names it), so if a later refresh brings it
    // back it joins the list as any stop does, not as a card it never was. So is one loading again
    // (a fresh cold load): it's held again only if it lands on screen again.
    val letGo = remember(held, trackedPlaces, groups, fetchedStopIds, closedStops) {
        val stillPending = trackedPlaces.mapTo(HashSet()) { it.placeKey }
        val groupPlaces = groups.mapTo(HashSet()) { groupPlace(it) }
        held.values
            .filter { it.placeKey in stillPending || heldCue(it, groupPlaces, fetchedStopIds, closedStops) == null }
            .map { it.placeKey }
    }
    if (letGo.isNotEmpty()) SideEffect { letGo.forEach(pendingTracker::forget) }
    // A tapped card's rows keep the card's slot until a later refresh with nothing loading; then
    // they take their own place in the list, as any stop's do, when the whole list re-sorts anyway.
    // A refresh is a newer fetch, not a new list: the clock drops a gone departure every tick.
    val settled = trackedPlaces.isEmpty()
    val lastFetch = remember(rows) { rows.maxOfOrNull { it.fetchedAt } }
    SideEffect {
        if (settled && lastFetch != null && pendingTracker.opened.isNotEmpty()) {
            pendingTracker.openedToRelease(lastFetch).forEach(pendingTracker::forget)
        }
    }
    // Each loading card with every stop its place has waited on (lines, nearest distance), so one
    // coming back empty doesn't strip the card; less any hidden mode.
    val loadingCards = remember(pendingPlaces, trackedPlaces, held, hiddenModes, stopDistanceMeters) {
        pendingPlaces.mapNotNull { withoutHidden(remeasured(pendingTracker.widened(it), stopDistanceMeters), hiddenModes) }
    }
    // A journey's heading (or, in its own view, its actions), closure notices, and trains or note —
    // shared by the near journeys at the top and the revealed faraway ones at the bottom, whose
    // headings also carry their distance ([farMeters]).
    fun LazyListScope.journeyItems(cards: List<JourneyCard>, farMeters: Map<String, Double>? = null) {
            cards.forEachIndexed { index, card ->
                if (journeyView) {
                    item(key = "journey-actions|${card.journey.key}") {
                        JourneyActions(
                            card.journey,
                            onSwap = { onFlipJourney(card.journey) },
                            onUnstar = onUnstarJourney?.let { unstar -> { unstar(card.journey) } },
                        )
                    }
                } else {
                    item(key = "journey-header|${card.journey.key}") {
                        JourneyHeader(
                            card.journey,
                            // First on screen, or first under the "Faraway favorites" label: no break.
                            firstOnScreen = index == 0,
                            onSwap = { onFlipJourney(card.journey) },
                            onOpen = onOpenJourney?.let { open -> { open(card.journey) } },
                            distanceLabel = distanceSystem?.let { system -> farMeters?.get(card.journey.key)?.let { StopDistance.label(it, system) } },
                        )
                    }
                }
                // The origin's closure notice rides the journey card: a farther origin isn't on the near-me
                // list, so without it the trains below would read as catchable at a closed station.
                card.closures.forEach { rows ->
                    val closure = rows.first()
                    item(key = "journey-closure|${card.journey.key}|${closure.stopId}") {
                        // Dismissed on every pole that carries it, so the next pole's copy doesn't take its place.
                        StopClosureCard(closure, onDismiss = { rows.forEach(onDismissAlert) })
                    }
                }
                if (card.destinationUnchecked) {
                    item(key = "journey-destination-unchecked|${card.journey.key}") {
                        JourneyNote(stringResource(R.string.journey_destination_unchecked, card.journey.to.name))
                    }
                }
                when (val state = card.state) {
                    is JourneyCardState.Trains -> if (state.rows.isEmpty()) {
                        // Only trains to change from: "no direct trains" unless some departure couldn't be
                        // checked (it may be direct), which the note below says instead.
                        if (state.changes.isEmpty() || !state.incomplete) {
                            item(key = "journey-none|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(
                                        when {
                                            state.changes.isNotEmpty() -> R.string.journey_none_direct
                                            card.journey.bus -> R.string.journey_none_bus
                                            else -> R.string.journey_none
                                        },
                                        card.journey.to.name,
                                    ),
                                )
                            }
                        }
                        journeyChanges(card, state, now, starred, onToggleStar, starringAvailable, onOpenChangeDetail, onOpenSettings)
                        if (state.incomplete) {
                            item(key = "journey-note|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(R.string.journey_incomplete),
                                    onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                                )
                            }
                        }
                    } else {
                        // Any bus boarding elsewhere than the origin (a pole beside it), even when it's the
                        // only one: each stop's buses go under its own heading and pole letter, so none
                        // reads as leaving from the stop the rider is at. The journey's own view heads
                        // every group that way ("King's Cross St. Pancras – Platform 7"), so it shows
                        // where to board; the list's card otherwise leaves that to the journey heading.
                        val severalStops = state.rows.any { it.stopId != card.boardingIds.firstOrNull() }
                        state.groups.forEachIndexed { groupIndex, group ->
                            if (severalStops || journeyView) {
                                item(key = "journey-stop|${card.journey.key}|${group.key}") {
                                    StopGroupHeader(
                                        group.stopName,
                                        group.qualifier,
                                        distanceLabel = null,
                                        // In the journey view the first group heads the page under the
                                        // actions, with no group break above it.
                                        firstOnScreen = journeyView && groupIndex == 0,
                                    )
                                }
                            }
                            item(key = "journey-card|${card.journey.key}|${group.key}") {
                                StopGroupCard(
                                    state.cards[groupIndex],
                                    now,
                                    starred = starred,
                                    onToggleStar = onToggleStar,
                                    starringAvailable = starringAvailable,
                                    onOpenDetail = onOpenDetail,
                                    onOpenSettings = onOpenSettings,
                                )
                            }
                        }
                        // A suspended line's status row is no direct train: with trains to change from, say so under it.
                        if (state.changes.isNotEmpty() && !state.incomplete && !Journeys.directDue(state.rows)) {
                            item(key = "journey-none|${card.journey.key}") {
                                JourneyNote(stringResource(R.string.journey_none_direct, card.journey.to.name))
                            }
                        }
                        journeyChanges(card, state, now, starred, onToggleStar, starringAvailable, onOpenChangeDetail, onOpenSettings)
                        if (state.incomplete) {
                            item(key = "journey-note|${card.journey.key}") {
                                JourneyNote(
                                    stringResource(R.string.journey_incomplete),
                                    onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                                )
                            }
                        }
                    }
                    JourneyCardState.Checking -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(
                            stringResource(if (card.journey.bus) R.string.journey_checking_bus else R.string.journey_checking),
                        )
                    }
                    is JourneyCardState.NotChecked -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(
                            stringResource(if (card.journey.bus) R.string.journey_not_checked_bus else R.string.journey_not_checked),
                            onRetry = onRetryJourneyRoutes.takeIf { state.retry },
                        )
                    }
                    JourneyCardState.RouteFailed -> item(key = "journey-note|${card.journey.key}") {
                        JourneyNote(stringResource(R.string.journey_route_failed), onRetry = onRetryJourneyRoutes)
                    }
                }
            }
    }
    LazyColumn(
        // Fades the edges while the list scrolls on past them, so more below reads as more.
        modifier = modifier.scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.background)),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A closure alert keys on its stop and hub so a recycled row can't carry another
        // alert's expanded state onto it.
        // The saved places to route to, first of all (SPEC D9 → *Routing from the near-me list*): an
        // item of the list, so it scrolls away with it rather than taking a pinned row's space. No
        // padding of its own — the list's 16dp inset already lines it up with the cards.
        // The telemetry question, put once to an install that never answered it (SPEC *Privacy*):
        // first, as the one card that asks something of the rider, and gone once answered.
        onTelemetryInviteAnswer?.let { answer ->
            item(key = "telemetry-invite") { TelemetryInviteCard(onAnswer = answer) }
        }
        watchInstall?.let { actions ->
            item(key = "watch-install") { WatchInstallCard(actions) }
        }
        if (favoritePlaces.isNotEmpty()) {
            item(key = "favorite-chips") { FavoriteChips(favoritePlaces, onRouteToPlace, contentPadding = PaddingValues(0.dp), onEditPlaces = onEditFavoritePlaces) }
        }
        // The lines a rider here may take and how they're running, under the place chips, so Home and
        // the favorites keep the top (maintainer, 2026-10-05). One line high whatever it says, so
        // nothing under it moves.
        disruptionsRow?.let { row -> item(key = "disruptions") { HomeDisruptionsRow(row) } }
        // Starred journeys lead the list (SPEC *Journeys*): each a header naming the direction shown,
        // tappable to show the other, over a card of just the trains that call at the far end.
        journeyItems(journeyCards)
        nearbyEmptyNote?.let { note ->
            item(key = "nearby-empty") { JourneyNote(note) }
        }
        items(leadingNotices, key = { "closure|${it.stopId}|${it.hubId}" }) { row ->
            StopClosureCard(row, onDismiss = { onDismissAlert(row) })
        }
        // One combined one-line header per group, then the group's card (SPEC D8): the place name and
        // its platform/pole qualifier read on one title-case line ("King's Cross St. Pancras – Platform
        // 1 (120 m)"), and every route of the group's stop(s) sits as an interior row of the one card
        // below. The place name repeats on each platform header of a station (as does the distance) —
        // the near-me rider judges each platform on its own line.
        // Filled in below once the notices are placed ([planNotices]): a lettered pole's own notice,
        // drawn under its heading, and which poles a notice says are closed (their "Closed" chip).
        var poleNotices = emptyMap<String, DepartureRow>()
        var closedPoles = emptySet<String>()
        // Whether the next heading drawn is the first thing on screen (no top break above it).
        var headingLeads = leadingNotices.isEmpty() && journeyCards.isEmpty()
        fun groupItems(index: Int, group: StopGroup) {
            val first = index == 0 && headingLeads
            if (index == 0) headingLeads = false
            // The near-me list carries a per-stop distance; the watched list doesn't, so the label is
            // present only when this place's stops are in the map (D1). A place groups several stops (a
            // junction's poles, a station's platforms), so it shows the distance to the *closest* of
            // them — the one a rider walks to (placeDistanceMeters).
            val distanceLabel = distanceSystem?.let { system -> placeDistanceMeters[group.placeKey]?.let { StopDistance.label(it, system) } }
            // Draw the header when the grouping distinguishes this place (>1 place, a split into
            // platforms/poles, a closure) OR there's a distance to promise. A lone bare near-me place
            // still shows its name and distance, else a one-place result would drop both (Codex, PR
            // #82). The first group on screen takes no extra top break — unless a closure alert precedes
            // it. Keyed on the raw distance, not the label, so a lone place keeps its name while the
            // units are still being read (the label alone is withheld then).
            if (group.showHeader || placeDistanceMeters[group.placeKey] != null) {
                item(key = "header|${group.key}") {
                    StopGroupHeader(
                        group.stopName,
                        group.qualifier,
                        distanceLabel,
                        firstOnScreen = first,
                        onClick = onOpenPlatform?.let { open -> { open(group) } },
                        onNameClick = onOpenStation?.let { open -> { open(group) } },
                        // The distance opens the group's own nearest stop in the maps app — for a
                        // bus place that's this pole, the one the rider walks to.
                        onDistanceClick = onOpenStopMap?.let { open ->
                            group.rows
                                .mapNotNull { r -> stopDistanceMeters[r.stopId]?.let { r.stopId to it } }
                                .minByOrNull { it.second }
                                ?.let { (stopId, _) -> { open(stopId, group.stopName) } }
                        },
                        hideModes = if (onHideMode != null) headerModes(group, modesByPlace) else emptyList(),
                        onHideMode = onHideMode,
                        closed = group.key in closedPoles,
                        checkingClosure = group.key in checkingGroups,
                    )
                }
            }
            // A notice about this pole alone sits under its heading, above its departures.
            poleNotices[group.key]?.let { notice ->
                item(key = "notice|${notice.stopId}|${notice.hubId}") {
                    StopNoticeCard(notice, closed = group.key in closedPoles, onDismiss = { onDismissAlert(notice) })
                }
            }
            item(key = "card|${group.key}") {
                StopGroupCard(
                    listCards.of(group),
                    now,
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = onOpenDetail,
                    onOpenSettings = onOpenSettings,
                    onHideMode = onHideMode,
                )
            }
        }
        // An opened farther station's groups stay with its card below the loaded places, rather than
        // moving up among them, so a tap never makes the list jump.
        val fartherStopIds = farther.associate { card ->
            card.place.key to (card.load as? FartherLoad.Open)?.distanceMeters?.keys.orEmpty()
        }
        val fartherGroups = groups.groupBy { group ->
            fartherStopIds.entries.firstOrNull { (_, ids) -> ids.isNotEmpty() && group.rows.all { it.stopId in ids } }?.key
        }
        // The loading places go where they'll land: by distance on the near-me list, below the
        // starred band; at the foot of the watched list, whose soonest-first order isn't known yet.
        // A held place's groups stay with its card, in the card's slot, rather than at their own.
        val unfarther = fartherGroups[null].orEmpty()
        val groupsByPlace = unfarther.groupBy { groupPlace(it) }
        val pendingKeys = trackedPlaces.mapTo(HashSet()) { it.placeKey }
        val heldCards = held.values.mapNotNull { heldPlace ->
            if (heldPlace.placeKey in pendingKeys) return@mapNotNull null
            val place = withoutHidden(remeasured(heldPlace, stopDistanceMeters), hiddenModes) ?: return@mapNotNull null
            heldCue(place, groupsByPlace.keys, fetchedStopIds, closedStops)?.let { cue -> place to cue }
        }
        val heldKeys = heldCards.mapTo(HashSet()) { it.first.placeKey }
        val listed = unfarther.filter { groupPlace(it) !in heldKeys }
        // Every card in first-seen order, so one landing never reorders the cards around it.
        val cards = (loadingCards.map { it to FartherCue.LOADING } + heldCards)
            .sortedBy { (place, _) -> pendingTracker.order(place.placeKey) }
        // A place is in the starred band if any of its groups is, so no card splits a starred station.
        val starredPlaces = listed.filter { group -> group.rows.any { StarredRow.of(it) in starred } }.mapTo(HashSet()) { it.placeKey }
        val slots = pendingSlots(
            listed.map { placeDistanceMeters[it.placeKey] },
            listed.map { it.placeKey in starredPlaces },
            cards.map { it.first },
        )
        // The near-me notices, each drawn once (SPEC *Disruptions*, [planNotices]). One whose place
        // is a held loading card or an opened farther card goes in that card's slot, so the place
        // shows once; the rest with no section go by distance. A dismissed closure keeps only its
        // heading and "Closed" chip, and only where its place has nothing else to show.
        // A held place's groups stay hidden until it's tapped open.
        val hiddenGroupKeys = heldCards
            .filter { (place, _) -> place.placeKey !in pendingTracker.opened }
            .flatMapTo(HashSet()) { (place, _) -> groupsByPlace[place.placeKey].orEmpty().map { it.key } }
        val plan = if (noticesInPlace) {
            planNotices(closureRows, groups, listed, stopHubIds, sharedNotices, hiddenGroupKeys)
        } else {
            NoticePlan()
        }
        val dismissedPlan = if (noticesInPlace) {
            planNotices(dismissedClosures, groups, listed, stopHubIds, sharedNotices, hiddenGroupKeys)
        } else {
            NoticePlan()
        }
        poleNotices = plan.onPole
        closedPoles = (plan.onPole + dismissedPlan.onPole)
            .filterValues { ClosedNotice.saysClosed(it.stopDisruption.orEmpty()) }.keys
        // A card's stops, and their interchanges: a hub notice kept on one member goes with a card
        // that holds another member, so the interchange isn't drawn twice. The first card in list
        // order (held cards by distance) wins where several share a stop or hub, so the notice sits at
        // the nearest one.
        fun List<Pair<String, String>>.firstWins() = distinctBy { it.first }.toMap()
        val heldByStop = heldCards
            .sortedBy { (place, _) -> place.stopIds.minOfOrNull { stopDistanceMeters[it] ?: Double.MAX_VALUE } }
            .flatMap { (place, _) -> place.stopIds.map { it to place.placeKey } }
            .firstWins()
        val fartherByStop = fartherStopIds.flatMap { (key, ids) -> ids.map { it to key } }.firstWins()
        val heldByHub = heldByStop.entries.mapNotNull { (stop, key) -> stopHubIds[stop]?.let { it to key } }.firstWins()
        val fartherByHub = fartherByStop.entries.mapNotNull { (stop, key) -> stopHubIds[stop]?.let { it to key } }.firstWins()
        // A notice about one pole alone, not shared across its place.
        fun poleOnly(notice: DepartureRow) =
            isPole(notice) && (stopPlaceKey(notice) to notice.stopDisruption.orEmpty()) !in sharedNotices
        // Where a notice stands on the list: its own stop, or for a place-wide notice the nearest
        // measured member of its interchange or StopArea — (that stop, its distance).
        fun noticeNearest(notice: DepartureRow): Pair<String, Double>? {
            val placeWide = !poleOnly(notice)
            val hub = notice.hubId.ifBlank { null }?.takeIf { placeWide }
            val cluster = stopClusterIds[notice.stopId]?.takeIf { placeWide }
            return stopDistanceMeters.entries
                .filter { (stop, _) ->
                    stop == notice.stopId ||
                        (hub != null && stopHubIds[stop] == hub) ||
                        (cluster != null && stopClusterIds[stop] == cluster)
                }
                .minByOrNull { it.value }?.let { it.key to it.value }
        }
        // Active and dismissed together, nearest first, so each slot draws them in distance order.
        val placeless = (plan.placeless.map { it to false } + dismissedPlan.placeless.map { it to true })
            .sortedBy { (notice, _) -> noticeNearest(notice)?.second ?: Double.MAX_VALUE }
            .groupBy { (notice, _) ->
                // A pole's own notice matches its own stop only, never a sibling member's card.
                val hub = notice.hubId.ifBlank { null }?.takeUnless { poleOnly(notice) }
                (heldByStop[notice.stopId] ?: hub?.let(heldByHub::get))?.let { "held|$it" }
                    ?: (fartherByStop[notice.stopId] ?: hub?.let(fartherByHub::get))?.let { "farther|$it" }
            }
        val loose = placeless[null].orEmpty()
        val looseSlots = distanceSlots(
            listed.map { placeDistanceMeters[it.placeKey] },
            listed.map { it.placeKey in starredPlaces },
            loose.map { (notice, _) -> noticeNearest(notice)?.second },
        )
        // A notice's own group: its place's heading (the interchange, else the stop; a pole's letter)
        // with its distance and a "Closed" chip when it says so, then the notice unless dismissed.
        // The distance opens [mapStopId] in the maps app, as a section heading's does: the notice's
        // own stop, or for a place-wide notice the nearest stop of the section it heads.
        fun noticeGroupItems(
            notice: DepartureRow,
            dismissed: Boolean,
            meters: Double?,
            mapStopId: String? = notice.stopId.takeIf { it in stopDistanceMeters },
            // The section a place-wide notice heads: its name opens the whole station, as that
            // section's own heading does.
            station: StopGroup? = null,
        ) {
            val closed = ClosedNotice.saysClosed(notice.stopDisruption.orEmpty())
            val name = notice.hubName.ifBlank { notice.stopName }
            val first = headingLeads
            headingLeads = false
            item(key = "notice-header|${notice.stopId}|${notice.hubId}") {
                StopGroupHeader(
                    name,
                    // A pole's letter only when the notice is that pole's alone, not its junction's.
                    // A letter-less pole reads by its sign's "towards", else its bearing, as its own
                    // section's heading does.
                    when {
                        !isPole(notice) || (stopPlaceKey(notice) to notice.stopDisruption.orEmpty()) in sharedNotices -> null
                        notice.stopLetter.isNotBlank() -> StopQualifier.BusStop(notice.stopLetter, notice.towards.ifBlank { null })
                        notice.towards.isNotBlank() -> StopQualifier.Towards(notice.towards)
                        notice.bearing.isNotBlank() -> StopQualifier.BusBearing(notice.bearing)
                        else -> null
                    },
                    distanceSystem?.let { system -> meters?.let { StopDistance.label(it, system) } },
                    firstOnScreen = first,
                    onNameClick = station?.let { group -> onOpenStation?.let { open -> { open(group) } } },
                    onDistanceClick = mapStopId?.takeIf { meters != null }?.let { stopId ->
                        onOpenStopMap?.let { open -> { open(stopId, name) } }
                    },
                    closed = closed,
                )
            }
            if (!dismissed) {
                item(key = "notice|${notice.stopId}|${notice.hubId}") {
                    StopNoticeCard(notice, closed, onDismiss = { onDismissAlert(notice) })
                }
            }
        }
        fun cardItem(place: PendingPlace, cue: FartherCue) {
            // A held place's notice heads its slot; with nothing running there, it stands for the place.
            val shown = place.placeKey in pendingTracker.opened && cue == FartherCue.TAP_TO_SEE
            // A dismissed closure keeps its heading only where the place has nothing else to show — or
            // where it's a pole's, whose chip stays on while its buses are listed (behind the tap here).
            val placeNotices = placeless["held|${place.placeKey}"].orEmpty()
                .filterNot { (notice, dismissed) ->
                    dismissed && !poleOnly(notice) && groupsByPlace[place.placeKey].orEmpty().isNotEmpty()
                }
            // The distance shown is the card's, so its tap opens the card's nearest stop.
            val nearest = place.stopIds.minByOrNull { stopDistanceMeters[it] ?: Double.MAX_VALUE }
                ?.takeIf { it in stopDistanceMeters }
            placeNotices.forEach { (notice, dismissed) ->
                noticeGroupItems(notice, dismissed, place.place.meters.takeIf { place.distanced }, nearest)
            }
            if (placeNotices.isNotEmpty() && cue != FartherCue.TAP_TO_SEE && cue != FartherCue.LOADING) return
            if (shown) {
                groupsByPlace[place.placeKey].orEmpty().forEach { group -> groupItems(1, group) }
            } else {
                // One key from "Loading" to "Tap to see", so the list keeps its anchor on the card.
                item(key = pendingItemKey(place)) {
                    FartherCardView(
                        FartherCard(place.place),
                        cue,
                        onOpen = { pendingTracker.opened += place.placeKey },
                        showDistance = place.distanced,
                        // A pole's own notice heads that pole only, so the card keeps the place's
                        // heading over its other poles' routes whenever one is among them.
                        showHeading = placeNotices.isEmpty() || placeNotices.any { (notice, _) -> poleOnly(notice) },
                    )
                }
            }
        }
        // A slot's loose notices (nearest first) and its cards, merged by distance: each notice goes
        // before the first card farther than it, so the slot still reads closest first.
        fun slotItems(slot: Int) {
            val notices = ArrayDeque(loose.filterIndexed { k, _ -> looseSlots[k] == slot })
            fun noticesUpTo(meters: Double) {
                while (notices.isNotEmpty() && (noticeNearest(notices.first().first)?.second ?: Double.MAX_VALUE) <= meters) {
                    val (notice, dismissed) = notices.removeFirst()
                    val nearest = noticeNearest(notice)
                    noticeGroupItems(notice, dismissed, nearest?.second, nearest?.first)
                }
            }
            cards.forEachIndexed { k, (place, cue) ->
                if (slots[k] != slot) return@forEachIndexed
                noticesUpTo(place.place.meters.takeIf { place.distanced } ?: Double.MAX_VALUE)
                cardItem(place, cue)
            }
            noticesUpTo(Double.POSITIVE_INFINITY)
        }
        listed.forEachIndexed { index, group ->
            slotItems(index)
            // A place-wide notice: its own group, directly above the place's first section.
            // A dismissed pole's closure keeps its heading while its buses are listed (a place-wide
            // one doesn't, the place having its sections to show).
            val above = plan.aboveGroup[index].orEmpty().map { it to false } +
                dismissedPlan.aboveGroup[index].orEmpty().filter { poleOnly(it) }.map { it to true }
            above.forEach { (notice, dismissed) ->
                // The place's distance is its nearest member's, so the tap opens that member —
                // across every section of the place, not just the first one listed.
                // An interchange's members can be separate places, so its hub counts too; and a
                // warning can carve a member into its own group key, so match the physical place.
                val hub = notice.hubId.ifBlank { null }
                val place = groupPlace(group)
                val nearest = groups
                    .filter { g ->
                        g.placeKey == group.placeKey || groupPlace(g) == place ||
                            (hub != null && g.rows.any { r -> r.hubId == hub || stopHubIds[r.stopId] == hub })
                    }
                    .flatMap { it.rows }
                    .mapNotNull { r -> stopDistanceMeters[r.stopId]?.let { r.stopId to it } }
                    .minByOrNull { it.second }
                noticeGroupItems(
                    notice, dismissed, nearest?.second ?: placeDistanceMeters[group.placeKey], nearest?.first, station = group,
                )
            }
            groupItems(index, group)
        }
        slotItems(listed.size)
        // Below the loaded places: the farther stations and bus places, each collapsed until tapped
        // (in [CollapsedPlaces.ordered]'s order).
        for (card in farther) {
            // An opened station's notice heads it; with nothing running there, it stands for the card.
            val opened = fartherGroups[card.place.key]
            // A dismissed closure keeps its heading only where the place has nothing else to show —
            // or where it's a pole's, whose chip stays on while its buses are listed.
            val cardNotices = placeless["farther|${card.place.key}"].orEmpty()
                .filterNot { (notice, dismissed) -> dismissed && opened != null && !poleOnly(notice) }
            // The card's distance, so its tap opens the card's nearest stop: an opened card's measured
            // stops, else a bus place's own (the notice's when it is one of them); a station not yet
            // opened has none to resolve, so its distance stays plain.
            val nearest = (card.load as? FartherLoad.Open)?.distanceMeters?.minByOrNull { it.value }?.key
                ?: card.place.stops.map { it.id }.let { ids -> cardNotices.firstNotNullOfOrNull { (n, _) -> n.stopId.takeIf { it in ids } } ?: ids.firstOrNull() }
            cardNotices.forEach { (notice, dismissed) -> noticeGroupItems(notice, dismissed, card.place.meters, nearest) }
            if (opened != null) {
                opened.forEach { group -> groupItems(1, group) }
            } else if (cardNotices.isEmpty()) {
                item(key = "farther|${card.place.key}") {
                    FartherCardView(card, fartherCue(card, fetchedStopIds, unavailableStopIds, closedStops.stopIds), onOpen = onOpenFarther)
                }
            }
        }
        // The faraway journeys (SPEC *Journeys*), at the foot below the farther cards: a button, then,
        // once tapped, their cards under a "Faraway favorites" label (revealing is what fetches them).
        if (farJourneyCards.isNotEmpty()) {
            item(key = "far-journeys-label") {
                Text(
                    text = stringResource(R.string.journeys_faraway),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                )
            }
            journeyItems(farJourneyCards, farJourneyMeters)
        } else if (onRevealFar != null) {
            item(key = "far-journeys-reveal") {
                TextButton(onClick = onRevealFar, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.journeys_faraway))
                }
            }
        }
    }
}

/** A farther station's collapsed card and where it stands ([FartherLoad]; null until tapped). */
data class FartherCard(val place: CollapsedPlaces.Place, val load: FartherLoad? = null)

/**
 * The farther stations' collapsed cards in a plain column, for the empty near-me list, where the
 * lazy list isn't shown (SPEC principle 2: they stay one tap away).
 */
@Composable
private fun FartherCards(
    farther: List<FartherCard>,
    fetchedStopIds: Set<String>,
    unavailableStopIds: Set<String>,
    onOpen: (CollapsedPlaces.Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (farther.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (card in farther) FartherCardView(card, fartherCue(card, fetchedStopIds, unavailableStopIds), onOpen = onOpen)
    }
}

/**
 * [pending] without a hidden mode's lines, and without a stop left serving only hidden modes
 * (SPEC *Finding stops → Hiding a mode*) — the same rule [HiddenModes.stops] picks stops by.
 */
internal fun visiblePending(pending: List<StopRef>, hiddenModes: Set<String>): List<StopRef> {
    if (hiddenModes.isEmpty()) return pending
    return pending.mapNotNull { stop ->
        if (stop.lines.isEmpty()) return@mapNotNull stop
        val kept = stop.lines.filterNot { HiddenModes.isHidden(it, hiddenModes) }
        when {
            kept.isEmpty() -> null
            kept.size == stop.lines.size -> stop
            else -> stop.copy(lines = kept)
        }
    }
}

/**
 * A place a cold load is still waiting on, as a collapsed card: its [placeKey] (as [placeOf] keys a
 * place), its [stopIds], and [distanced] when its distance is known.
 */
internal data class PendingPlace(
    val place: CollapsedPlaces.Place,
    val distanced: Boolean,
    val placeKey: String = place.key.removePrefix("pending:"),
    val stopIds: Set<String> = emptySet(),
)

/** The lazy-list key of [place]'s card, the same whether it says "Loading" or "Tap to see". */
internal fun pendingItemKey(place: PendingPlace): String = "pending|${place.place.key}"

/**
 * Remembers the cold load's loading places across updates, so a place that lands while its card is
 * [on screen][update] is held as a card ("Tap to see") rather than expanding in place — the list
 * never jumps under the rider's eyes (SPEC *Freshness → Cold load*). One that lands off screen isn't
 * held and opens in full. Also keeps each place's first-seen [order], so a card turning from
 * "Loading" to "Tap to see" never moves among its neighbors.
 */
class PendingTracker {
    /**
     * The item keys the list drew last frame with loaded times drawn below them ([aboveLoadedRows]):
     * the cards whose opening would push what the rider may be reading.
     */
    internal var onScreen: Set<Any> = emptySet()

    /** The held cards the rider has tapped open, by place. State, so a tap redraws the list. */
    internal var opened: Set<String> by mutableStateOf(emptySet())
    /** Bumped when a held place is let go, so a cached [update] result is taken again. */
    internal var revision by mutableIntStateOf(0)
        private set
    private var previous: List<PendingPlace> = emptyList()
    private val held = LinkedHashMap<String, PendingPlace>()
    private val seen = HashMap<String, Int>()
    // Each place as the union of every stop it has waited on, including ones already back: its
    // stop ids, lines and nearest distance, so a card doesn't lose them as its stops come in.
    private val merged = HashMap<String, PendingPlace>()

    /** The places held so far, after [current] replaces the last update. */
    internal fun update(current: List<PendingPlace>): Map<String, PendingPlace> {
        current.forEach {
            seen.getOrPut(it.placeKey) { seen.size }
            merged[it.placeKey] = widened(it)
        }
        val still = current.mapTo(HashSet()) { it.placeKey }
        previous
            .filter { it.placeKey !in still && pendingItemKey(it) in onScreen }
            .forEach { held[it.placeKey] = widened(it) }
        previous = current
        return held.toMap()
    }

    /** [place] with every stop, line and the nearest distance its place has waited on so far. */
    internal fun widened(place: PendingPlace): PendingPlace {
        val before = merged[place.placeKey] ?: return place
        val meters = listOfNotNull(
            before.place.meters.takeIf { before.distanced },
            place.place.meters.takeIf { place.distanced },
        ).minOrNull()
        return before.copy(
            place = before.place.copy(
                meters = meters ?: before.place.meters,
                lines = (before.place.lines + place.place.lines).distinctBy { it.id },
            ),
            distanced = meters != null,
            stopIds = before.stopIds + place.stopIds,
        )
    }

    // The latest fetch each opened card was first seen settled with, so a later one releases it.
    private val openedSince = HashMap<String, Instant>()

    /**
     * The opened held cards to let go now that the list has a newer fetch ([lastFetch]) than when
     * they were opened: their rows have kept the card's slot through the tap, and now join the list.
     */
    internal fun openedToRelease(lastFetch: Instant): List<String> {
        openedSince.keys.retainAll(opened)
        return opened.filter { it in held && lastFetch > openedSince.getOrPut(it) { lastFetch } }
    }

    /** Lets go of a held place (one that failed, or opened and since refreshed), so it's no longer held. */
    internal fun forget(placeKey: String) {
        if (held.remove(placeKey) != null) revision++
        opened = opened - placeKey
    }

    /** Where [placeKey] first appeared among the loading places, for a stable card order. */
    internal fun order(placeKey: String): Int = seen[placeKey] ?: Int.MAX_VALUE

    companion object {
        private fun encode(p: PendingPlace): ArrayList<Any> = arrayListOf(
            p.placeKey, p.place.key, p.place.stationId, p.place.name, p.place.meters, p.distanced,
            ArrayList(p.stopIds), ArrayList(p.place.lines.map { it.id }),
            ArrayList(p.place.lines.map { it.name }), ArrayList(p.place.lines.map { it.mode }),
        )

        @Suppress("UNCHECKED_CAST")
        private fun decode(entry: List<Any>): PendingPlace {
            val ids = entry[7] as List<String>
            val names = entry[8] as List<String>
            val modes = entry[9] as List<String>
            return PendingPlace(
                CollapsedPlaces.Place(
                    key = entry[1] as String,
                    stationId = entry[2] as String,
                    name = entry[3] as String,
                    meters = entry[4] as Double,
                    lines = ids.indices.map { LineRef(ids[it], names[it], modes[it]) },
                ),
                distanced = entry[5] as Boolean,
                placeKey = entry[0] as String,
                stopIds = (entry[6] as List<String>).toSet(),
            )
        }

        /**
         * Keeps the held places, the first-seen order, and the loading cards on screen across a
         * recreated screen, as plain lists — so a stop landing mid-rotation still holds its card.
         */
        val Saver = listSaver<PendingTracker, Any>(
            save = { tracker ->
                val shown = tracker.previous.filter { pendingItemKey(it) in tracker.onScreen }.map(tracker::widened)
                listOf(
                    ArrayList(tracker.held.values.map(::encode)),
                    ArrayList(tracker.seen.entries.sortedBy { it.value }.map { it.key }),
                    ArrayList(shown.map(::encode)),
                    ArrayList(tracker.merged.values.map(::encode)),
                    ArrayList(tracker.opened),
                )
            },
            restore = { saved ->
                PendingTracker().apply {
                    @Suppress("UNCHECKED_CAST")
                    (saved[0] as List<List<Any>>).map(::decode).forEach { held[it.placeKey] = it }
                    @Suppress("UNCHECKED_CAST")
                    (saved[1] as List<String>).forEach { seen[it] = seen.size }
                    // The loading cards that were on screen count as still drawn until the new
                    // list draws its own frame.
                    @Suppress("UNCHECKED_CAST")
                    previous = (saved[2] as List<List<Any>>).map(::decode)
                    // Every place's stops so far, visible or not, so a card still knows a sibling
                    // came back empty after the screen is recreated.
                    @Suppress("UNCHECKED_CAST")
                    (saved[3] as List<List<Any>>).map(::decode).forEach { merged[it.placeKey] = it }
                    @Suppress("UNCHECKED_CAST")
                    opened = (saved[4] as List<String>).toSet()
                    onScreen = previous.mapTo(HashSet()) { pendingItemKey(it) }
                }
            },
        )
    }
}

/**
 * A [PendingTracker] for the list of [listKey], saved with the key it belongs to. Like
 * [rememberListStateFor], only a different known list starts a fresh one: a null key (the nearby
 * set not resolved yet, as after process death) keeps the restored tracker until the list is known.
 */
@Composable
fun rememberPendingTracker(listKey: String?): PendingTracker {
    val holder = rememberSaveable(saver = PendingTrackerHolder.Saver) { PendingTrackerHolder() }
    return holder.trackerFor(listKey)
}

/** The tracker [rememberPendingTracker] keeps, and the list it belongs to. */
internal class PendingTrackerHolder(
    private var owner: String? = null,
    private var tracker: PendingTracker = PendingTracker(),
) {
    fun trackerFor(listKey: String?): PendingTracker {
        if (listKey != null && listKey != owner) {
            if (owner != null) tracker = PendingTracker()
            owner = listKey
        }
        return tracker
    }

    companion object {
        val Saver = listSaver<PendingTrackerHolder, Any?>(
            save = { holder -> listOf(holder.owner, with(PendingTracker.Saver) { save(holder.tracker) }) },
            restore = { saved ->
                PendingTrackerHolder(
                    owner = saved[0] as String?,
                    tracker = saved[1]?.let { PendingTracker.Saver.restore(it) } ?: PendingTracker(),
                )
            },
        )
    }
}

/**
 * [place] at its nearest stop's distance from the current fix ([stopDistanceMeters]), so a card
 * kept across a move reads and sorts as the rows around it do; as it was when none of its stops has
 * a distance (the watched list).
 */
internal fun remeasured(place: PendingPlace, stopDistanceMeters: Map<String, Double>): PendingPlace {
    val meters = place.stopIds.mapNotNull { stopDistanceMeters[it] }.minOrNull() ?: return place
    return place.copy(place = place.place.copy(meters = meters), distanced = true)
}

// The item-key prefixes that aren't loaded rows: the loading and farther cards, headings (each sits
// just above its own rows), and the faraway-journeys label and button. Every other item — a place's
// group card, a journey's trains or its settled "none"/note — is something the rider may be reading.
private val NOT_LOADED_KEYS = listOf("pending|", "farther|", "header|", "journey-header|", "far-journeys-")

/**
 * Of the list's drawn item [keys], top to bottom, the ones a landing card is held at: those with a
 * loaded row drawn below them, since opening in full would push it down, while one with only cards
 * (or nothing) below it opens where it is. A group showing only a status (a suspended line) or
 * withheld countdowns still counts. Also any drawn key in [alwaysHold]: a watched-list card, parked
 * at the foot only until its stop's soonest-first place is known, which could be anywhere above.
 */
internal fun aboveLoadedRows(keys: List<Any>, alwaysHold: Set<Any> = emptySet()): Set<Any> {
    val lastLoaded = keys.indexOfLast { key -> key !is String || NOT_LOADED_KEYS.none { key.startsWith(it) } }
    val above = if (lastLoaded <= 0) emptySet() else keys.subList(0, lastLoaded).toSet()
    return if (alwaysHold.isEmpty()) above else above + keys.filter { it in alwaysHold }
}

/** [place] without a hidden mode's lines, or null when it served only hidden modes. */
internal fun withoutHidden(place: PendingPlace, hiddenModes: Set<String>): PendingPlace? {
    if (hiddenModes.isEmpty() || place.place.lines.isEmpty()) return place
    val kept = place.place.lines.filterNot { HiddenModes.isHidden(it, hiddenModes) }
    return if (kept.isEmpty()) null else place.copy(place = place.place.copy(lines = kept))
}

/**
 * A held place's cue: "Tap to see" while it has groups to open ([groupPlaces]); with none, "Closed"
 * when a notice in force says so ([closed]), a dash when it came back with nothing running
 * ([fetchedStopIds]), else null — it failed, and the banner names it.
 */
internal fun heldCue(
    place: PendingPlace,
    groupPlaces: Set<String>,
    fetchedStopIds: Set<String>,
    closed: ClosedStops = ClosedStops.NONE,
): FartherCue? = when {
    place.placeKey in groupPlaces -> FartherCue.TAP_TO_SEE
    place.placeKey in closed.placeKeys || place.stopIds.any { it in closed.stopIds } -> FartherCue.CLOSED
    place.stopIds.any { it in fetchedStopIds } -> FartherCue.NO_DEPARTURES
    else -> null
}

/**
 * The stops (and their places, as [placeOf] keys them — a hub's folded notice names one pole) whose
 * notice in force says the stop itself is closed ([ClosedNotice]), from the list's closure rows.
 */
internal data class ClosedStops(val stopIds: Set<String>, val placeKeys: Set<String>) {
    companion object {
        val NONE = ClosedStops(emptySet(), emptySet())

        fun of(closureRows: List<DepartureRow>): ClosedStops {
            val closed = closureRows.filter { ClosedNotice.saysClosed(it.stopDisruption.orEmpty()) }
            if (closed.isEmpty()) return NONE
            return ClosedStops(
                closed.mapTo(HashSet()) { it.stopId },
                closed.mapTo(HashSet()) { placeOf(it.clusterId, it.stopId) },
            )
        }
    }
}

/** The place a group belongs to, keyed as [placeOf] keys a stop (not [StopGroup.placeKey], which
 *  splits a warned stop off on its own). */
private fun groupPlace(group: StopGroup): String =
    group.rows.first().let { placeOf(it.clusterId, it.stopId) }

/**
 * The places [pending] stops make, one card each, keyed as [StopGrouping] keys a place, less any in
 * [shownPlaces] (already on screen). On the near-me list ([stopDistanceMeters] non-empty) only the
 * nearby stops count, nearest first; the watched list keeps their order.
 */
internal fun pendingPlaces(
    pending: List<StopRef>,
    shownPlaces: Set<String>,
    stopDistanceMeters: Map<String, Double>,
): List<PendingPlace> {
    val nearMe = stopDistanceMeters.isNotEmpty()
    val places = pending
        .filter { !nearMe || it.id in stopDistanceMeters }
        .groupBy { placeOf(it.clusterId, it.id) }
        .filterKeys { it !in shownPlaces }
        .map { (key, stops) ->
            val meters = stops.mapNotNull { stopDistanceMeters[it.id] }.minOrNull()
            PendingPlace(
                CollapsedPlaces.Place(
                    key = "pending:$key",
                    stationId = stops.first().id,
                    name = stops.first().name,
                    meters = meters ?: 0.0,
                    lines = stops.flatMap { it.lines }.distinctBy { it.id },
                ),
                distanced = meters != null,
                placeKey = key,
                stopIds = stops.mapTo(HashSet()) { it.id },
            )
        }
    return if (nearMe) places.sortedBy { it.place.meters } else places
}

/**
 * Where each of [pending] goes among the listed groups: the index of the group it precedes, or the
 * group count for the foot. One with a distance follows the last group no farther away, else leads
 * the groups past the starred band ([pinned]), as it will once loaded; one without (the watched
 * list) goes to the foot.
 */
internal fun pendingSlots(groupMeters: List<Double?>, pinned: List<Boolean>, pending: List<PendingPlace>): List<Int> =
    distanceSlots(groupMeters, pinned, pending.map { p -> p.place.meters.takeIf { p.distanced } })

/**
 * Where each of [meters] goes among the listed groups, as [pendingSlots] places a loading card: after
 * the last group no farther away, else leading the groups past the starred band; with no distance,
 * at the foot.
 */
internal fun distanceSlots(groupMeters: List<Double?>, pinned: List<Boolean>, meters: List<Double?>): List<Int> =
    meters.map { m ->
        if (m == null) return@map groupMeters.size
        val after = groupMeters.indices.lastOrNull { i -> !pinned[i] && groupMeters[i]?.let { it <= m } == true }
        after?.plus(1) ?: pinned.indexOfFirst { !it }.takeIf { it >= 0 } ?: groupMeters.size
    }

/** What a collapsed card says where the times would be, and whether a tap acts on it. */
internal enum class FartherCue(val tappable: Boolean) { TAP_TO_SEE(true), LOADING(false), RETRY(true), NO_DEPARTURES(false), CLOSED(false) }

/**
 * A collapsed card's cue, from its [FartherCard.load] and the fetch so far: [fetchedStopIds] came
 * back, [unavailableStopIds] failed. An opened card whose stops all failed offers a retry rather
 * than "Loading…" forever; one with no departures to show says "Closed" when a notice in force says
 * so ([closedStopIds]), else shows a dash once a stop is back.
 */
internal fun fartherCue(
    card: FartherCard,
    fetchedStopIds: Set<String>,
    unavailableStopIds: Set<String>,
    closedStopIds: Set<String> = emptySet(),
): FartherCue =
    when (val load = card.load) {
        null -> FartherCue.TAP_TO_SEE
        FartherLoad.Loading -> FartherCue.LOADING
        FartherLoad.Failed -> FartherCue.RETRY
        is FartherLoad.Open -> {
            val ids = load.distanceMeters.keys
            when {
                ids.any { it in closedStopIds } -> FartherCue.CLOSED
                ids.any { it in fetchedStopIds } -> FartherCue.NO_DEPARTURES
                ids.isNotEmpty() && ids.all { it in unavailableStopIds } -> FartherCue.RETRY
                else -> FartherCue.LOADING
            }
        }
    }

/**
 * A farther station's collapsed card (SPEC *Finding stops → Farther stations*): headed like a loaded
 * place, its name and distance, over one row of the lines it adds and a [cue] where the times would
 * be. A tap loads it and opens it in place ([onOpen]); while it loads, or if it failed, the cue says
 * so, and a failed one taps to retry.
 */
@Composable
private fun FartherCardView(
    card: FartherCard,
    cue: FartherCue,
    onOpen: (CollapsedPlaces.Place) -> Unit,
    // False on the watched list, which gives no distances (D1).
    showDistance: Boolean = true,
    // False when the place's notice group already heads it, so the place isn't named twice.
    showHeading: Boolean = true,
) {
    val place = card.place
    val cueText = stringResource(
        when (cue) {
            FartherCue.TAP_TO_SEE -> R.string.farther_tap_to_see
            FartherCue.LOADING -> R.string.farther_loading
            FartherCue.RETRY -> R.string.farther_retry
            // A dash where the times go, as a line row with nothing running shows.
            FartherCue.NO_DEPARTURES -> R.string.status_no_departures
            FartherCue.CLOSED -> R.string.farther_closed
        },
    )
    val cueSpoken = if (cue == FartherCue.NO_DEPARTURES) stringResource(R.string.status_no_departures_description) else cueText
    val tappable = cue.tappable
    // In the chosen units, like every near-me header; none while the stored choice is being read.
    val distanceSystem = LocalDistanceSystem.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) {
            StopGroupHeader(
                place.name,
                qualifier = null,
                distanceLabel = distanceSystem?.takeIf { showDistance }?.let { StopDistance.label(place.meters, it) },
                firstOnScreen = false,
            )
        }
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (tappable) Modifier.clickable { onOpen(place) } else Modifier),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (line in place.lines) LinePill(line.name, line.id, line.mode)
                }
                Text(
                    text = cueText,
                    modifier = Modifier.semantics { contentDescription = cueSpoken },
                    style = MaterialTheme.typography.labelLarge,
                    // Error-toned, as the closure card at the top of the list is.
                    color = when {
                        tappable -> MaterialTheme.colorScheme.primary
                        cue == FartherCue.CLOSED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The one combined header above a group's card (SPEC D8): the place name and its platform/pole
 * qualifier on a single title-case line — "King's Cross St. Pancras – Platform 1", "Cranley Gardens –
 * Stop G", "Highgate – Eastbound" — with [distanceLabel] ("(120 m)", null on the location-free watched
 * list, D1) appended dimmed. No small caps, no tracking: the place name and the qualifier share one
 * [MaterialTheme.typography.labelLarge] / [FontWeight.SemiBold] / `onSurface` style, and only the
 * distance is muted to `onSurfaceVariant`.
 *
 * The place name is the one element that clips: it takes the row's slack and ellipsizes when long,
 * while the " – <qualifier>" and " (<distance>)" are reserved (measured first) so they stay fully
 * visible — a long "King's Cross St. Pancras – Platform 7 (120 m)" keeps "– Platform 7 (120 m)" and
 * clips the name. The whole row announces one screen-reader label — the place name, the spoken
 * qualifier (which keeps the direction/towards the visible segment drops), then the distance. Extra
 * top space marks the break between groups; the first on screen takes none.
 */
@Composable
internal fun StopGroupHeader(
    name: String,
    qualifier: StopQualifier?,
    distanceLabel: String?,
    firstOnScreen: Boolean,
    // Opens this group's platform view; null leaves the header inert.
    onClick: (() -> Unit)? = null,
    // Opens the whole place from a tap on the name itself (the rest of the row opens the platform).
    // Null leaves the name to the row.
    onNameClick: (() -> Unit)? = null,
    // Shows the stop in the maps app from a tap on the distance. Null leaves the distance to the row.
    onDistanceClick: (() -> Unit)? = null,
    // A place name stays one line; a journey's change heading wraps rather than lose its "(for …)".
    nameMaxLines: Int = 1,
    // What a screen reader hears for [name] when it differs ("King's Cross to Camden Town, for High
    // Barnet", never the drawn arrow). Null reads [name].
    spokenName: String? = null,
    // The modes a long press on the header offers to hide (SPEC *Finding stops → Hiding a mode*);
    // empty or a null [onHideMode] leaves the header without a long press.
    hideModes: List<String> = emptyList(),
    onHideMode: ((String) -> Unit)? = null,
    // A notice in force says this place is closed ([ClosedNotice]): a "Closed" chip after the name,
    // so a closure stands out where it sits in the list (SPEC *Disruptions*).
    closed: Boolean = false,
    // This place's closure check is still out: a small spinner where the "Closed" chip would be,
    // until it's back (SPEC *Freshness → Cold load*).
    checkingClosure: Boolean = false,
) {
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val closedLabel = stringResource(R.string.farther_closed)
    val openLabel = stringResource(R.string.action_show_platform)
    val stationLabel = stringResource(R.string.action_show_station)
    val mapLabel = stringResource(R.string.action_show_on_map)
    val label = remember(qualifier) { groupHeaderLabel(qualifier) }
    // The full spoken label: the place name, the spoken qualifier (direction/towards kept), then the
    // distance — read as one, so a screen reader hears the whole header rather than three fragments.
    val checkingLabel = stringResource(R.string.closure_checking)
    val spoken = remember(name, spokenName, qualifier, distanceLabel, closed, closedLabel, checkingClosure, checkingLabel) {
        buildString {
            append(spokenName ?: name)
            groupHeaderSpoken(qualifier)?.let { append(", ").append(it) }
            distanceLabel?.let { append(", ").append(it) }
            if (closed) append(", ").append(closedLabel)
            else if (checkingClosure) append(", ").append(checkingLabel)
        }
    }
    val hideable = onHideMode != null && hideModes.isNotEmpty()
    var hideMenuOpen by remember { mutableStateOf(false) }
    // The latest tap action, read by a detector keyed only on whether there is one: the caller
    // builds [onClick] afresh on every recomposition (the clock ticks every few seconds), and keying
    // the detector on it would restart it and drop a long press in progress, as [RouteRow] avoids.
    val currentOnClick by rememberUpdatedState(onClick)
    val tappable = onClick != null
    val moreLabel = stringResource(R.string.more_actions)
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The tap target spans the full row including the group-break space above the text, so
            // the header grows no taller for being tappable and the list keeps its spacing.
            .then(
                if (hideable) {
                    // A tap opens the platform, a long press the "Hide ‹mode›" menu — the same
                    // gesture handling as a route row, with both actions announced.
                    Modifier
                        .pointerInput(tappable) {
                            detectTapGestures(
                                onTap = { currentOnClick?.invoke() },
                                onLongPress = { hideMenuOpen = true },
                            )
                        }
                        .semantics {
                            if (tappable) onClick(label = openLabel) { currentOnClick?.invoke(); true }
                            onLongClick(label = moreLabel) { hideMenuOpen = true; true }
                        }
                } else if (onClick != null) {
                    Modifier.clickable(onClickLabel = openLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(start = 4.dp, end = 4.dp, top = if (firstOnScreen) 0.dp else 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The distance ("(120 m)") is short and reserved (unweighted, measured first). The name and
        // the qualifier then SHARE the rest, each weighted, so neither can consume the whole row and
        // crowd the other to zero: a long place name and a long qualifier each keep at least their
        // half and clip within it, so the rider's boarding place always stays visible (Codex P1, PR
        // #122). `fill = false` lets a short name/qualifier sit at its natural width and pack left
        // rather than pad out its half. The name ellipsizes; the qualifier hard-clips its glyphs.
        Text(
            // A journey's change heading carries the "heading to" arrow ("King's Cross ➔ Camden Town").
            text = withArrowIcons(name),
            inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = nameMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                // The name is its own tap target (child first, so it wins over the row's platform tap)
                // and its own screen-reader button, read as the place name with a "whole station" hint.
                .then(if (onNameClick != null) Modifier.clickable(onClickLabel = stationLabel, onClick = onNameClick) else Modifier),
        )
        if (label != null) {
            Text(
                text = withArrowIcons("${groupHeaderJoin(label)}$label"),
                inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                // Elide with a single "…" (never a mid-glyph cut) when the name and qualifier can't
                // both fit their shared half of the row.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (distanceLabel != null) {
            Text(
                text = " ($distanceLabel)",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                // Its own tap target (child first, so it wins over the row's platform tap) and its own
                // screen-reader button, like the place name.
                modifier = if (onDistanceClick != null) {
                    Modifier.clickable(onClickLabel = mapLabel, onClick = onDistanceClick)
                } else {
                    Modifier
                },
            )
        }
        if (closed) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    text = closedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else if (checkingClosure) {
            // Small enough to sit in the heading's line without growing it.
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(12.dp),
            )
        }
    }
    if (hideable) {
        HideModeMenu(
            expanded = hideMenuOpen,
            onDismiss = { hideMenuOpen = false },
            modes = hideModes,
            onHideMode = { mode -> onHideMode?.invoke(mode) },
        )
    }
    }
}

/**
 * The long-press menu of a near-me header or row (SPEC *Finding stops → Hiding a mode*): an
 * optional [leading] item (a row's pin/unpin), then "Hide ‹mode›" for each of [modes], then "Hide
 * ‹line›" for each of [lines], each handed to [onHideMode] as its [HiddenModes.lineKey]; and, given
 * [onAvoidLine] (a trip's route card), "Avoid ‹line›" for each of them too, handed over as its
 * [AvoidedLines.key]. Wrapped in [StopDashMenu] like the overflow menu, so it looks the same and the
 * chosen text size reaches it.
 */
@Composable
internal fun HideModeMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modes: List<String>,
    onHideMode: (String) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    lines: List<LineRef> = emptyList(),
    onAvoidLine: ((String) -> Unit)? = null,
) {
    StopDashMenu(expanded = expanded, onDismissRequest = onDismiss) {
        leading?.invoke()
        // One item per group the modes fall in ("Hide all train services" for Thameslink or the Overground);
        // hiding it hides the whole group, as the overflow menu's checkbox does.
        modes.map(ModeGroups::of).distinctBy { it.key }.forEach { group ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hide_mode, groupNameInSentence(group))) },
                onClick = {
                    onDismiss()
                    onHideMode(group.modes.first())
                },
            )
        }
        lines.distinctBy { it.id.lowercase() }.forEach { line ->
            val label = lineLabel(line.name.ifBlank { line.id }, line.mode)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hide_line, label)) },
                onClick = {
                    onDismiss()
                    onHideMode(HiddenModes.lineKey(line.id, label))
                },
            )
        }
        // A trip's lines to avoid, after the hides: avoiding leaves a line out of trips only.
        if (onAvoidLine != null) {
            lines.distinctBy { it.id.lowercase() }.forEach { line ->
                val label = lineLabel(line.name.ifBlank { line.id }, line.mode)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.avoid_line, label)) },
                    onClick = {
                        onDismiss()
                        onAvoidLine(AvoidedLines.key(line.id, label))
                    },
                )
            }
        }
    }
}

/**
 * The modes a header's place serves, in a stable order, for its "Hide ‹mode›" items: from every
 * stop of the place — its cluster ([placeModes]), so a sibling platform or pole counts too — by
 * their served lines and departures, not just the rows on screen, so a mode with no train due can
 * still be hidden. A stop closure has no mode of its own to hide.
 */
private fun headerModes(group: StopGroup, placeModes: Map<String, Set<String>>): List<String> =
    group.rows.asSequence()
        .flatMap { row ->
            val served = placeModes[placeOf(row.clusterId, row.stopId)].orEmpty()
            if (row.stopDisruption == null && row.mode.isNotBlank()) served + row.mode else served
        }
        .distinctBy { it.lowercase() }
        .sortedBy(::modeName)
        .toList()

/**
 * Each place's modes, by cluster (a stop in none is its own place), from its stops' served lines
 * and departures, for [headerModes].
 */
@WorkerThread
internal fun placeModes(stops: List<StopArrivals>): Map<String, Set<String>> {
    val modes = HashMap<String, LinkedHashSet<String>>()
    for (stop in stops) {
        val place = modes.getOrPut(placeOf(stop.clusterId, stop.stopId)) { LinkedHashSet() }
        (stop.lines.map { it.mode } + stop.departures.map { it.mode }).filterTo(place) { it.isNotBlank() }
    }
    return modes
}

private fun placeOf(clusterId: String, stopId: String): String = clusterId.ifBlank { "\u0000stop:$stopId" }

/**
 * A stop notice in place (SPEC *Disruptions*): the notice alone, collapsed to its first line and
 * expanded on tap, under the heading of the place it's about (the heading names the place, so it
 * carries no title of its own). Error-toned when it says the stop is closed ([closed]), else the
 * quieter tertiary tone — a lift outage shouldn't look like a shut station.
 */
@Composable
private fun StopNoticeCard(row: DepartureRow, closed: Boolean, onDismiss: () -> Unit) {
    CollapsibleStatus(
        text = cleanDisruptionBody(
            row.stopDisruption.orEmpty(),
            stopName = row.stopName,
            hubName = row.hubName,
            aliases = row.placeAliases,
        ),
        title = null,
        onDismiss = onDismiss,
        container = if (closed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (closed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

/**
 * A stop-closure alert as its own header-less card at the top of the list (SPEC *Disruptions*): the
 * disruption in an error-toned surface titled by the interchange (else the stop), the body collapsed
 * to its first line and expanded on tap. Kept as a standalone card — a whole-stop closure has no
 * platform/pole qualifier to head, and its departures (if any) show in the group cards below.
 */
@Composable
internal fun StopClosureCard(row: DepartureRow, onDismiss: (() -> Unit)?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // A traversal group, matching the departure cards. The place name is stripped from the body
        // at display (not upstream) so the near-me fold's identity stays member-independent
        // (cleanDisruptionBody); the title supplies the stop name a bus "Bus Stop Closed" never does.
        Column(modifier = Modifier.padding(16.dp).semantics { isTraversalGroup = true }) {
            StopClosureContent(
                cleanDisruptionBody(
                    row.stopDisruption.orEmpty(),
                    stopName = row.stopName,
                    hubName = row.hubName,
                    aliases = row.placeAliases,
                ),
                title = row.hubName.ifBlank { row.stopName },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * One card per group (SPEC D8): an [OutlinedCard] whose body is a [Column] of interior **route
 * rows** — one per destination line of each of the group's rows — separated by thin dividers. Each
 * route row carries its line pill, its destination, an inline ⚠ when the line is disrupted, and the
 * merged countdown; a starred route wears a gold leading-edge bar (the per-row accent that replaces
 * the old whole-card gold border, which no longer maps now a card holds several routes).
 *
 * Interactions live on each route row, not the card: a TAP opens the detail view and a LONG-PRESS
 * pins (where starrable). Staleness is per row, from the row's own stop age, so a stale stop
 * withholds its countdowns ("?") while a fresh stop's card beside it stays live (SPEC D4).
 */
/**
 * A starred journey as shown on the near-me list: [journey] turned to the direction shown, and its
 * [rows] — the trains from its origin that call at its destination ([Journeys.rows]). Null rows while
 * the origin's departures or the line's route aren't in yet, so the card says it's checking rather
 * than claim there are no trains (SPEC principle 1).
 */
internal data class JourneyCard(
    val journey: StarredJourney,
    val state: JourneyCardState,
    // The (undismissed) closure notices of its boarding stops and far end, shown whatever the trains'
    // state: each one notice, as the rows of every pole that carries it (dismissed together).
    val closures: List<List<DepartureRow>> = emptyList(),
    // Every departure a complete check judged (empty otherwise), for the widget, by boarding stop.
    val checked: Map<String, Set<JourneyCall>> = emptyMap(),
    // The stops it boards from: its origin, then any neighboring poles (empty until placed).
    val boardingIds: List<String> = emptyList(),
    // Its far-end stops whose closure is checked: its own, the route's, and any a departure reaches.
    val destinationIds: Set<String> = emptySet(),
    // Some far-end stop's closure check failed with nothing known, so it may be closed.
    val destinationUnchecked: Boolean = false,
    // The departures left out as unchecked, by line, stop and reason, for the debug log.
    val misses: Set<RouteMiss> = emptySet(),
)

/** What a journey card can say about its trains. */
internal sealed interface JourneyCardState {
    /** The origin's departures or the line's route aren't in yet. */
    data object Checking : JourneyCardState

    /** The line's route couldn't be loaded, so which trains call at the far end is unknown. */
    data object RouteFailed : JourneyCardState

    /**
     * The origin's last refresh failed or has gone stale, and there's nothing current to show. [retry]
     * when a lookup a retry can redo failed (the poles beside a bus journey's origin).
     */
    data class NotChecked(val retry: Boolean = false) : JourneyCardState

    /**
     * The trains that call at the far end — empty only from a fresh, current fetch. [incomplete]
     * when some departure or line couldn't be checked, so the list may be missing one.
     */
    data class Trains(
        val rows: List<DepartureRow>,
        val incomplete: Boolean = false,
        // [incomplete] because some line's route failed to load: offer a retry.
        val retry: Boolean = false,
        // Trains on another branch, with where to change, when no direct train is due.
        val changes: List<JourneyChange> = emptyList(),
        // How the card's stop cards group a branching line's trains ([stopCard]).
        val topology: RouteTopology = RouteTopology.EMPTY,
    ) : JourneyCardState {
        /**
         * Every row the card shows, the direct trains and those to change from, with the two parts of
         * one row (the same stop, line, direction and platform) joined again: the near-me list's
         * no-repeat check and a tapped train's route page each look for the whole row.
         */
        val shownRows: List<DepartureRow>
            get() = (rows + changes.map { it.row })
                .groupBy { listOf(it.stopId, it.lineId, it.directionKey, it.platform) }
                .values
                .map { parts -> parts.singleOrNull() ?: DepartureRows.joined(parts) }

        /** [rows] by boarding stop, as the card draws them: grouped where the card is judged, on the worker. */
        val groups: List<StopGroup> = StopGrouping.groupByStop(rows, warningsLead = false)

        /** Each of [groups] as its stop card draws it ([stopCard]), worked out with them. */
        val cards: List<StopCard> = groups.map { stopCard(it, topology) }

        /** [changes] by change stop, in order, each stop's rows by boarding stop as [groups] are. */
        val changeStops: List<ChangeStop> = changes.groupBy { it.stopId }.map { (stopId, atStop) ->
            val groups = StopGrouping.groupByStop(atStop.map { it.row }, warningsLead = false)
            ChangeStop(stopId, atStop.first().stopName, groups, groups.map { stopCard(it, topology) })
        }
    }

    /** A stop to change at ([Trains.changeStops]), and the trains to change from, by boarding stop, each with its card. */
    class ChangeStop(val stopId: String, val stopName: String, val groups: List<StopGroup>, val cards: List<StopCard>)
}

/**
 * A journey card's trains on another branch ([JourneyCardState.Trains.changes]), under a "King's Cross
 * ➔ Camden Town (for High Barnet)" heading per change stop, each boarding stop's in its own card like the direct trains.
 */
private fun LazyListScope.journeyChanges(
    card: JourneyCard,
    state: JourneyCardState.Trains,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    state.changeStops.forEach { change ->
        val stopId = change.stopId
        item(key = "journey-change|${card.journey.key}|$stopId") {
            StopGroupHeader(
                stringResource(R.string.journey_change_at, card.journey.from.name, change.stopName, card.journey.to.name),
                qualifier = null,
                distanceLabel = null,
                firstOnScreen = false,
                nameMaxLines = 2,
                spokenName = stringResource(
                    R.string.journey_change_at_spoken,
                    card.journey.from.name,
                    change.stopName,
                    card.journey.to.name,
                ),
            )
        }
        change.cards.forEach { stopCard ->
            item(key = "journey-change-card|${card.journey.key}|$stopId|${stopCard.group.key}") {
                StopGroupCard(
                    stopCard,
                    now,
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = onOpenDetail,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

/** A journey card's one-line status in place of trains, with a retry when there's one to offer. */
@Composable
private fun JourneyNote(text: String, onRetry: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}

/**
 * A journey's own view's heading and actions, above its trains: the journey in full, then swap the
 * direction shown, and unstar the journey
 * (which closes the view, as its card is gone). Labeled buttons rather than bare icons — this is the
 * view a rider opens to act on the journey.
 */
@Composable
private fun JourneyActions(journey: StarredJourney, onSwap: () -> Unit, onUnstar: (() -> Unit)?) {
    val spoken = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    Column {
        // The journey in full, wrapping as far as it needs, so both names show however long they are
        // and at any text size (Codex).
        Text(
            text = withArrowIcons(stringResource(R.string.journey_title, journey.from.name, journey.to.name)),
            inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                .semantics { heading(); contentDescription = spoken },
        )
        // Wraps, so at a large text size or on a narrow screen Unstar drops to its own line rather
        // than being squeezed or clipped (Codex).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSwap) {
                Icon(SwapIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_flip_journey), modifier = Modifier.padding(start = 8.dp))
            }
            if (onUnstar != null) {
                OutlinedButton(onClick = onUnstar) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = LocalStarredBorderColor.current,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.action_unstar_journey), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * A journey card's heading, "Highgate ➔ King's Cross St. Pancras ★": the direction shown, styled like
 * a stop group header (same weight and group-break space) so the list reads as one. A tap on the
 * heading opens the journey's own view ([onOpen], null leaves it inert — as in that view); the ⇄
 * button at the end swaps the direction in place ([onSwap]), for planning the way back without
 * leaving the list (maintainer, 2026-09-24).
 */
@Composable
private fun JourneyHeader(
    journey: StarredJourney,
    firstOnScreen: Boolean,
    // Null hides the ⇄ (a far journey's collapsed heading: nothing below it to swap).
    onSwap: (() -> Unit)?,
    onOpen: (() -> Unit)? = null,
    // How far away a collapsed far journey is ("2.4 km"), dimmed after the star; null for none.
    distanceLabel: String? = null,
) {
    val openLabel = stringResource(R.string.action_open_journey)
    val swapLabel = stringResource(R.string.action_flip_journey)
    val title = stringResource(R.string.journey_title_spoken, journey.from.name, journey.to.name)
    val spoken = distanceLabel?.let { "$title, $it" } ?: title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (firstOnScreen) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = openLabel, onClick = onOpen) else Modifier)
                // Read "Highgate to King's Cross", not the arrow's name.
                .semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = withArrowIcons(stringResource(R.string.journey_title, journey.from.name, journey.to.name)),
                inlineContent = arrowInlineContent(MaterialTheme.colorScheme.onSurface),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The gold star marks a starred journey, telling its heading apart from a bus place's
            // "Place ➔ Destination" header (maintainer, 2026-09-24). Decorative: the card is a journey
            // by construction, so it adds nothing to the spoken label.
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = LocalStarredBorderColor.current,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            if (distanceLabel != null) {
                Text(
                    text = " ($distanceLabel)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (onSwap == null) return@Row
        // Compact on purpose (maintainer, 2026-09-24): a 24dp target with no 48dp minimum, so the
        // heading keeps a header's height while the swap control is still being tried out. The
        // journey's own view carries a full-size Swap direction button.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            IconButton(onClick = onSwap, modifier = Modifier.size(24.dp)) {
                Icon(
                    SwapIcon,
                    contentDescription = swapLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A stop's card as [StopGroupCard] draws it: its [group], and each of its rows' route rows ([lines],
 * in row order; none for a status row), worked out together on the worker ([stopCard]).
 */
class StopCard(val group: StopGroup, val lines: List<List<DestinationGroup>>)

/**
 * [group] as its card draws it: each row's trains grouped by destination and branch under [topology]
 * ([DepartureRows.destinationLines]). Walks every row's trains, so on the worker with the group
 * itself, never in composition (AGENTS.md *Main thread*).
 */
@WorkerThread
internal fun stopCard(group: StopGroup, topology: RouteTopology): StopCard =
    StopCard(group, group.rows.map { row -> if (row.hasTrains) DepartureRows.destinationLines(row, MAX_TIMES, topology) else emptyList() })

@Composable
internal fun StopGroupCard(
    // The stop's group and its rows' route rows, worked out on the worker ([stopCard]).
    card: StopCard,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    // Null (a trip on the way's board) leaves the rows with nothing to open ([RouteRow]).
    onOpenDetail: ((DepartureRow, RouteFocus?) -> Unit)?,
    onOpenSettings: () -> Unit = {},
    // Offers "Hide ‹mode›" in each row's long-press menu; null keeps long-press as starring.
    onHideMode: ((String) -> Unit)? = null,
    // Grays a time due before the rider can board ([CountdownLabel]); null grays none.
    grayBefore: Instant? = null,
    // Draws a timed row's times in place of its countdowns: a trip's later ride, which says how often
    // its line runs. Null counts down.
    timesInstead: (@Composable (DepartureRow) -> Unit)? = null,
) {
    val group = card.group
    // Cap the line pill at half the card's inner width, so a long name at a large font scale
    // ellipsizes rather than consuming the card and starving the countdown, which must stay one line
    // (SPEC D8). Inner width ≈ screen minus the list's 16dp side padding and the row's 16dp padding.
    val cardInnerWidth = LocalConfiguration.current.screenWidthDp.dp - 64.dp
    val pillModifier = Modifier.widthIn(max = cardInnerWidth * 0.5f)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // The card is a traversal group; each interior route row is its own sub-group (below) so its
        // ⚠ precedes its own countdown, not the next row's (SPEC principle 2).
        Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
            var firstRow = true
            group.rows.forEachIndexed { rowIndex, row ->
                val stale = Staleness.isStale(row.fetchedAt, now)
                val isStarred = StarredRow.of(row) in starred
                if (!row.hasTrains) {
                    // A status row: the line is suspended (its reason shown) and returned no
                    // predictions (SPEC *Departures*). Tappable to the detail view, but not starrable
                    // — a no-departures row has nothing to rank.
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    RouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = false,
                        onToggleStar = onToggleStar,
                        // A "?" row opens nothing: its line's page has no times to show and no way yet to say why
                        // they're missing, so it would read as a clean line (Codex, PR #505).
                        onOpenDetail = onOpenDetail.takeUnless { row.quiet },
                        onHideMode = onHideMode,
                    ) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                        // The reason chip lives in the weighted slack so it absorbs the shrink (and
                        // ellipsizes) when space is tight; the status text is unweighted, so the Row
                        // reserves its width — the status can't be squeezed to zero.
                        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            // Its disruption dismissed, planned work it still carries is noted instead.
                            row.status?.let { status -> DisruptionChip(status.description) }
                                ?: row.plannedAlerts.firstOrNull()?.let { planned -> PlannedAlertGlyph(planned) }
                        }
                        // A dash when the line's source answered with no trains and none is due
                        // ([EmptyTimes]): a National Rail board lists every train it runs, and a TfL
                        // line's timetable says what TfL's live list leaves out. "?" when one might be
                        // coming. "No data" when no source answered; "No key" (a tap away in
                        // Settings) for a National Rail line a key would give times.
                        val noTimes = NoTimes.of(row)
                        val noKey = noTimes == NoTimes.NO_KEY
                        // A quiet row is only in the list once its mark is "?" ([withQuietRows]).
                        val mark = when {
                            row.quiet -> EmptyTimes.Mark.UNKNOWN
                            noTimes == NoTimes.NO_TRAINS && !row.mode.equals(NATIONAL_RAIL_MODE, ignoreCase = true) && !row.notRunningHere ->
                                emptyTimesMark("line:${row.stopId}|${row.lineId}") {
                                    EmptyTimes.Board(listOf(EmptyTimes.Key(row.stopId, row.lineId)))
                                }
                            else -> null
                        }
                        val unknown = mark == EmptyTimes.Mark.UNKNOWN
                        if (mark == EmptyTimes.Mark.LOADING) {
                            // Its timetable is still being fetched: a spinner says "wait", where a "?" read
                            // as an answer (maintainer, 2026-10-03).
                            val loading = stringResource(R.string.status_times_loading_description)
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(16.dp)
                                    .semantics { contentDescription = loading },
                            )
                            return@RouteRow
                        }
                        val spoken = stringResource(
                            if (unknown) R.string.status_times_unknown_description else R.string.status_no_departures_description,
                        )
                        Text(
                            text = stringResource(
                                when {
                                    unknown -> R.string.status_times_unknown
                                    noTimes == NoTimes.NO_TRAINS -> R.string.status_no_departures
                                    noKey -> R.string.status_no_rail_key
                                    else -> R.string.status_no_data
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (noKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .then(
                                    // The dash alone could be heard as missing data, and "?" as nothing at
                                    // all; say "No departures" or "Times unknown".
                                    if (noTimes == NoTimes.NO_TRAINS) {
                                        Modifier.semantics { contentDescription = spoken }
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (noKey) {
                                        Modifier.clickable(
                                            onClickLabel = stringResource(R.string.status_no_rail_key_action),
                                            role = Role.Button,
                                            onClick = onOpenSettings,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    return@forEachIndexed
                }
                // Rows with trains: grouped by destination *and branch* (the shared `destinationLines`,
                // so the widget can't drift), each destination its own route row with its own merged
                // countdown — a countdown is never read under the wrong destination or branch (SPEC
                // D8). A branching row (Northern to Morden and to Battersea) shows its pill on each.
                // A row whose every train has no time ("Cancelled") isn't starrable: no train to rank.
                val starrable = starringAvailable && row.stopDisruption == null && row.upcoming.isNotEmpty()
                val destinationLines = card.lines[rowIndex]
                destinationLines.forEach { group2 ->
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    // The destination, or the direction key as a cue when TfL gives no destination, so
                    // cards TfL keeps distinct stay distinguishable (SPEC principle 1).
                    val label = DepartureLabels.destinationLabel(group2.destination, row.directionKey)
                        ?: stringResource(R.string.destination_unknown)
                    LineRouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = starrable,
                        onToggleStar = onToggleStar,
                        onOpenDetail = onOpenDetail,
                        // The route row's own soonest train names the route the detail follows.
                        focus = RouteFocus.of(group2),
                        onHideMode = onHideMode,
                        destination = { modifier -> DestinationLabelContent(label = label, branch = group2.branch, modifier = modifier) },
                        times = {
                            if (timesInstead != null) {
                                timesInstead(row)
                            } else {
                                CountdownLabel(group2.times, stale, now, grayBefore = grayBefore, untimed = group2.untimed)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A line's timed route row, drawn the same wherever a line's trains are listed — a stop's card on the
 * main screen, a trip's route card: its pill (capped at half the card, so a long name can't starve
 * the times), its [destination], a disrupted line's ⚠ just left of the [times] and announced first
 * (SPEC D3 / principle 2; the full status text stays reachable in the detail view), then the times.
 * Handled as a [RouteRow]: a tap opens the line's page, a long press pins it or opens its menu. Only
 * the destination and times differ between lists, so they're the slots; [destination] gets the
 * modifier that sizes and spaces it.
 */
@Composable
internal fun LineRouteRow(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    onToggleStar: (DepartureRow) -> Unit,
    // Null leaves the row with nothing to open ([RouteRow]).
    onOpenDetail: ((DepartureRow, RouteFocus?) -> Unit)?,
    focus: RouteFocus?,
    onHideMode: ((String) -> Unit)?,
    destination: @Composable RowScope.(Modifier) -> Unit,
    times: @Composable RowScope.() -> Unit,
    // Set where the row is part of something bigger that owns its tap and long press (a trip's
    // card): see [RouteRow].
    onOpenInstead: (() -> Unit)? = null,
    onLongPressInstead: (() -> Unit)? = null,
) {
    // Inner width ≈ screen minus the list's 16dp side padding and the row's 16dp padding.
    val cardInnerWidth = LocalConfiguration.current.screenWidthDp.dp - 64.dp
    RouteRow(
        row = row,
        isStarred = isStarred,
        starrable = starrable,
        onToggleStar = onToggleStar,
        onOpenDetail = onOpenDetail,
        focus = focus,
        onHideMode = onHideMode,
        onOpenInstead = onOpenInstead,
        onLongPressInstead = onLongPressInstead,
    ) {
        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = Modifier.widthIn(max = cardInnerWidth * 0.5f))
        destination(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp))
        row.status?.let { status -> DisruptionWarningGlyph(status.description, Modifier.padding(end = 8.dp)) }
            // Work still to come notes the row without flagging it: the disruption ⚠ wins when
            // both apply, and the page lists both (SPEC *Disruptions*).
            ?: row.plannedAlerts.firstOrNull()?.let { planned -> PlannedAlertGlyph(planned, Modifier.padding(end = 8.dp)) }
        times()
    }
}

/** A thin divider between the interior route rows of a group card, in the outline color. */
@Composable
private fun RouteDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One interior route row of a group card: a [Row] whose [content] is the pill, destination, optional
 * ⚠, and countdown. A TAP opens the detail view; a LONG-PRESS pins the route when [starrable]
 * (starring available, not a stop closure, and it has upcoming departures). A [isStarred] route wears
 * a 3dp gold leading-edge bar — all destination rows of one starred [DepartureRow] show it, since
 * starring is keyed per line+direction ([StarredRow.of]).
 *
 * A `pointerInput` gesture plus a **non-merging** `semantics` block (NOT `combinedClickable`, which
 * flattens the descendant traversal so the ⚠ could no longer precede its countdown): the block adds
 * the labeled tap/long-press and marks the row its own traversal sub-group. Callbacks are read via
 * `rememberUpdatedState` and the gesture is keyed on the stable [starrable] so the 10s clock's
 * recomposition never drops an in-progress long-press (Codex).
 */
@Composable
internal fun RouteRow(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    onToggleStar: (DepartureRow) -> Unit,
    // Opens the line's page; null (a trip on the way's board) leaves a tap to nothing, and no
    // action is announced.
    onOpenDetail: ((DepartureRow, RouteFocus?) -> Unit)?,
    // The route this row shows (null for a status row), handed to the detail so it opens on it.
    focus: RouteFocus? = null,
    // Makes a long press open a menu — the pin/unpin, then "Hide ‹mode›" for this row's mode (SPEC
    // *Finding stops → Hiding a mode*) — instead of pinning at once. Null keeps the direct pin.
    onHideMode: ((String) -> Unit)? = null,
    // A tap that does this instead of opening the line's page, unlabeled like the card's own tap —
    // a trip's card, where the row is one of the trip's choices, not a line to look up.
    onOpenInstead: (() -> Unit)? = null,
    // A long press that does this instead of the row's own pin or menu: the card's menu.
    onLongPressInstead: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val starActionLabel = stringResource(if (isStarred) R.string.unstar else R.string.star)
    val detailActionLabel = stringResource(R.string.departure_details)
    val moreLabel = stringResource(R.string.more_actions)
    val currentRow by rememberUpdatedState(row)
    val currentToggleStar by rememberUpdatedState(onToggleStar)
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentFocus by rememberUpdatedState(focus)
    val currentOpenInstead by rememberUpdatedState(onOpenInstead)
    val currentLongPressInstead by rememberUpdatedState(onLongPressInstead)
    val opensInstead = onOpenInstead != null
    val opens = opensInstead || onOpenDetail != null
    val longPressesInstead = onLongPressInstead != null
    val hideMode = row.mode.takeIf { onHideMode != null && it.isNotBlank() && !longPressesInstead }
    var menuOpen by remember { mutableStateOf(false) }
    fun open() = currentOpenInstead?.invoke() ?: currentOpenDetail?.invoke(currentRow, currentFocus)
    val onLongPress: ((Offset) -> Unit)? = when {
        longPressesInstead -> { _ -> currentLongPressInstead?.invoke() }
        hideMode != null -> { _ -> menuOpen = true }
        starrable -> { _ -> currentToggleStar(currentRow) }
        else -> null
    }
    val starColor = LocalStarredBorderColor.current
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // A row with no tap and no long press takes no gesture at all: a detector with
                // nothing to do still consumes the touch.
                if (opens || onLongPress != null) {
                    Modifier.pointerInput(starrable, hideMode, longPressesInstead, opens) {
                        detectTapGestures(
                            onTap = if (opens) ({ _ -> open() }) else null,
                            onLongPress = onLongPress,
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                isTraversalGroup = true
                if (opens) onClick(label = if (opensInstead) null else detailActionLabel) { open(); true }
                when {
                    longPressesInstead -> onLongClick(label = moreLabel) { currentLongPressInstead?.invoke(); true }
                    hideMode != null -> onLongClick(label = moreLabel) { menuOpen = true; true }
                    starrable -> onLongClick(label = starActionLabel) { currentToggleStar(currentRow); true }
                }
            }
            .then(
                if (isStarred) {
                    Modifier.drawBehind {
                        drawRect(color = starColor, size = size.copy(width = 3.dp.toPx()))
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    if (hideMode != null) {
        HideModeMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            modes = listOf(hideMode),
            // Just this row's line too ("Hide Northern line"), for a rider of the mode who never takes it.
            lines = if (row.lineId.isNotBlank()) listOf(LineRef(row.lineId, row.lineName, row.mode)) else emptyList(),
            onHideMode = { mode -> onHideMode?.invoke(mode) },
            leading = if (starrable) {
                {
                    DropdownMenuItem(
                        text = { Text(starActionLabel) },
                        onClick = {
                            menuOpen = false
                            currentToggleStar(currentRow)
                        },
                    )
                }
            } else {
                null
            },
        )
    }
    }
}

/**
 * The inline disruption warning ⚠ on a route row (SPEC D3): a small glyph in the error color, sat
 * just left of the countdown but announced first ([traversalIndex] = -1f) so a screen reader voices
 * the warning — TfL's full status wording, its [description] — before the countdown it qualifies
 * (SPEC principle 2). A glyph `Text`, not `Icons.Filled.Warning`, since that isn't in
 * material-icons-core (like the outline star the app already vendors).
 */
@Composable
internal fun DisruptionWarningGlyph(description: String, modifier: Modifier = Modifier) {
    Text(
        text = "⚠",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics {
            contentDescription = description
            traversalIndex = -1f
        },
    )
}

/**
 * The note on a route row for work that hasn't started ([PlannedAlert]): a calendar ([CalendarIcon])
 * in the muted text color, not the error one, since nothing is wrong yet — the row's countdowns
 * stand. Sized in sp, like the ⚠ glyph it stands in for, so the two scale alike with the font.
 * Announced first, like [DisruptionWarningGlyph], as the alert and the day it starts.
 */
@Composable
internal fun PlannedAlertGlyph(alert: PlannedAlert, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.planned_alert_description, alert.label, plannedDate(alert))
    val size = with(LocalDensity.current) { PLANNED_GLYPH_SIZE.toDp() }
    Icon(
        imageVector = CalendarIcon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size).semantics { traversalIndex = -1f },
    )
}

/** The row's calendar, the height of the titleMedium ⚠ it stands in for. */
private val PLANNED_GLYPH_SIZE = 18.sp

/** The day [alert] starts, short and in the rider's own locale ("13 Oct"). */
@Composable
internal fun plannedDate(alert: PlannedAlert): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(alert.startsOn, locale) {
        alert.startsOn.format(DateTimeFormatter.ofPattern("d MMM", locale))
    }
}

/**
 * A planned alert on a route's page: its label in a neutral chip with the day it starts beside it,
 * then TfL's text collapsed to its first line, in the muted surface rather than the error one.
 */
@Composable
private fun PlannedAlertBlock(alert: PlannedAlert, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Outlined and neutral: a filled tint reads as a warning, and nothing is wrong yet.
            Surface(
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The row's calendar, so the chip reads as the same planned work; the date beside
                    // the chip says when, so the icon itself is left unannounced.
                    Icon(
                        imageVector = CalendarIcon,
                        contentDescription = null,
                        modifier = Modifier.size(with(LocalDensity.current) { 14.sp.toDp() }),
                    )
                    Text(
                        text = alert.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.planned_alert_from, plannedDate(alert)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            // Every service alert is dismissible (SPEC *Disruptions*), a planned one on its own.
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (alert.fullText.isNotBlank()) {
            CollapsibleStatus(
                text = alert.fullText,
                title = null,
                modifier = Modifier.padding(top = 8.dp),
                container = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A stop closure (a stop-level disruption) as the card's content: the disruption in an
 * error-toned surface under the place's own [title] (the interchange name, else the stop). The
 * stop's own departures, if any, show in their own cards below.
 *
 * **The title heads the card always; the body collapses to one line and expands on tap.** Not
 * every notice names its own stop — a bus "Bus Stop Closed" never does — so the heading is what
 * says *which* stop even when collapsed (SPEC *Disruptions*); a tube notice's own leading repeat of
 * the name is stripped upstream ([cleanDisruptionBody]) so the heading isn't said twice. TfL's
 * notices are prose (a paragraph on a lift outage), and a glance surface shouldn't be dominated by
 * one, so collapsed the [disruption] body is its first line alone and tapping expands it to the full
 * text (SPEC *Concise copy* / jank-free UI). The body `Text` exposes its full string to the
 * accessibility tree regardless of the visual clip, so a screen reader reads the whole notice
 * whether or not it is expanded; the tap only changes what is drawn. Expanded state is
 * `rememberSaveable`, keyed on the notice text, so it survives a configuration change and never
 * bleeds onto a different notice when a `LazyColumn` row is recycled.
 */
@Composable
private fun StopClosureContent(disruption: String, title: String, onDismiss: (() -> Unit)? = {}) {
    CollapsibleStatus(text = disruption, title = title, onDismiss = onDismiss)
}

/**
 * The shared "first line, tap to expand" disruption surface (SPEC *Disruptions* / *Concise
 * copy*): an error-toned rounded box that collapsed shows [text]'s first line alone and
 * expanded shows it in full, with a chevron marking it expandable. When a [title] is given it
 * heads the surface, collapsed and expanded. Used for the stop-closure card
 * ([StopClosureContent], where [title] is the interchange/stop name) and for the route detail's
 * line disruption ([RouteDetailScreen], where [title] is null — the app bar already carries the
 * line and destination).
 *
 * The `text` `Text` exposes its full string to the accessibility tree regardless of the visual
 * clip, so a screen reader reads the whole notice whether or not it is expanded; the tap only
 * changes what is drawn. Expanded state is `rememberSaveable`, keyed on [text], so it survives a
 * configuration change and never bleeds onto a different notice when a `LazyColumn` row is
 * recycled.
 *
 * When [onDismiss] is non-null a leading dismiss (×) button hides the alert (the stop-closure card
 * — the user's read-and-clear control, SPEC *Disruptions*); the route detail passes null, since its
 * line alert's dismiss sits beside the status chip instead. The button has its own click target, so a
 * dismiss tap doesn't also toggle the expand/collapse.
 */
/**
 * [text] with each web link ([AlertLinks]) made a tappable, underlined link. Opening goes through
 * Compose's [LinkAnnotation.Url], which hands the URL to the platform's browser — only `http`/`https`
 * links are produced, so a tap can't open any other scheme. A link is also an accessibility action,
 * so a screen reader can open it.
 */
private fun linkified(text: String, onOpen: LinkInteractionListener): AnnotatedString {
    val links = AlertLinks.find(text)
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (link in links) {
            append(text, at, link.range.first)
            withLink(LinkAnnotation.Url(link.url, style, onOpen)) { append(text.substring(link.range)) }
            at = link.range.last + 1
        }
        append(text, at, text.length)
    }
}

@Composable
private fun CollapsibleStatus(
    text: String,
    title: String?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val clickLabel = stringResource(if (expanded) R.string.alert_collapse else R.string.alert_expand)
    Surface(
        color = container,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel) { expanded = !expanded },
    ) {
        // A dismissible notice is as tall as its 48dp controls when collapsed: the text carries its own
        // vertical margin rather than the row padding above and below the buttons (maintainer,
        // 2026-09-26: "make them shorter"), centered in the 48dp so one line sits level with the
        // chevron and ×.
        val dismissible = onDismiss != null
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (dismissible) 0.dp else 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f)
                    .then(if (dismissible) Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp) else Modifier),
                verticalArrangement = Arrangement.Center,
            ) {
                // The title (the interchange/stop name) heads the surface whenever one is given —
                // collapsed and expanded — so a notice that doesn't name its own stop still says
                // which stop. The route detail passes null (its dialog header already carries the
                // line and place), so nothing is shown there.
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Web links in the notice (TfL often names a page for more detail) are underlined
                // and open in the browser on tap; the rest of the card still toggles expand/collapse.
                // Opened through a guarded listener rather than the default URI handler, which throws
                // on a device with no browser: say so instead of crashing (as LicensesScreen does).
                val context = LocalContext.current
                val linkFailed = stringResource(R.string.link_open_failed)
                val onOpenLink = remember(context, linkFailed) {
                    LinkInteractionListener { annotation ->
                        val url = (annotation as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            // The URL is TfL's own alert text, not user data; the log still omits it.
                            Log.w("Alerts", "No activity to open an alert link", e)
                            Toast.makeText(context, linkFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val linked = remember(text, onOpenLink) { linkified(text, onOpenLink) }
                Text(
                    text = linked,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (title != null) Modifier.padding(top = 4.dp) else Modifier,
                )
            }
            // A quiet chevron marks the row as expandable; the click label carries the action
            // for a screen reader, so the icon itself needs no separate description.
            val chevron = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
            if (onDismiss != null) {
                // On a dismissible closure card the chevron and the × are BOTH centered in a 48dp
                // band, so their glyphs line up on one axis, with a clear gap between them — they read
                // as two separate controls instead of a cramped, misaligned cluster.
                Box(
                    modifier = Modifier.padding(start = 8.dp).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(chevron, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                // The dismiss (×) keeps its default 48dp interactive target, flush against the chevron
                // band so there is no dead gap between them: the visual separation from the chevron is
                // the button's own internal padding, which is part of the dismiss hit target — a near
                // miss just left of the glyph still dismisses rather than toggling expand/collapse
                // (SPEC *Disruptions*). No outer padding, which would sit outside the clickable and
                // fall through to the card.
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // A status card without its own × (the route detail's line prose) keeps the top-aligned
                // chevron beside its text — no × to align with.
                Icon(chevron, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
            }
        }
    }
}

/** The stable identity of the route a [DepartureRow] represents — its stop, line, and direction —
 *  used as the saveable key for the open route-detail page so it re-resolves against live rows. */
internal fun DepartureRow.detailKey(): String = "$stopId|$lineId|$directionKey|$platform"

/**
 * [row]'s line on a page of its own ([OneLinePage]), as a route page's "View line" opens it (maintainer,
 * 2026-10-06), its row worked out by [routeLineRow].
 */
@Composable
private fun RouteLinePage(row: DepartureRow, ride: TripLeg?, unknown: Boolean, checking: Boolean, onClose: () -> Unit) {
    val slot = remember { mutableStateOf<Worked<Inputs, TripRow>?>(null) }
    val status = row.status ?: row.statusBehind
    val lineRow = rememberWorked(slot, Inputs(row, ride, status, row.statusDismissed, unknown, checking), keep = ::sameVerdict) {
        routeLineRow(row, ride, unknown, checking)
    }
    OneLinePage(lineRow, row.lineId, row.lineName, row.mode, onClose)
}

/**
 * One line on a page of its own ([TripLinesPage] alone): a route page's "View line", or a train tapped on
 * a trip's board (maintainer, 2026-10-06). Its row ([lineRow]) is worked out on the worker by the caller,
 * kept while the next is worked out only while it says the same, as sure ([sameVerdict]). The page is up
 * at once, the line's pill alone claiming no status until the row is in ([lineStandIn]), so Back closes
 * this page, never the one under it (Codex, #623).
 */
@Composable
internal fun OneLinePage(lineRow: TripRow?, lineId: String, lineName: String, mode: String, onClose: () -> Unit) {
    val shown = lineRow ?: remember(lineId, lineName, mode) { lineStandIn(lineId, lineName, mode) }
    TripLinesPage(shown, onClose, dismissal = LocalDismissLineAlert.current, alone = true)
}

/**
 * Whether a one-line page's row ([OneLinePage]) worked out for [held] may stay up while [wanted]'s is
 * worked out: only while it says the same, as sure as before (Codex, #623). A check gone stale, failed
 * or out again never leaves the last verdict up meanwhile, nor does an alert that began, ended or was
 * dismissed: its map would draw a closure that has ended, or none where one began (SPEC *Line page*).
 * Statuses are fetched again as new objects with every check, so the alert is compared by what the
 * page shows of it ([sameAlert]); one fetched again unchanged keeps the page, and its map, still.
 * [held] and [wanted] are the page's [Inputs], ending with the line's status, whether it was dismissed,
 * unknown and checking.
 */
internal fun sameVerdict(held: Inputs, wanted: Inputs): Boolean {
    val n = held.parts.size
    if (n < 4 || wanted.parts.size != n) return false
    return sameAlert(held.parts[n - 4] as LineStatus?, wanted.parts[n - 4] as LineStatus?) &&
        (n - 3 until n).all { held.parts[it] == wanted.parts[it] }
}

/**
 * Whether [a] and [b] read the same on a line's page: the same object, or the same severity and words,
 * with as many closures under way. Field reads and the words compared, never a walk of the alert's
 * stretches, so it's cheap enough for composition. A closure's stretch changed with its count and the
 * words shown unchanged reads the same here until its row is in (`TODO.md`).
 */
internal fun sameAlert(a: LineStatus?, b: LineStatus?): Boolean =
    a === b || (a != null && b != null && a.severity == b.severity && a.description == b.description &&
        a.fullText == b.fullText && a.closures.size == b.closures.size)

/**
 * A line as its page ([OneLinePage]) stands in for it while its row is worked out: its pill alone, no
 * status claimed and no map drawn ([TripLine.restoring]). One line, so cheap enough to build in place.
 */
internal fun lineStandIn(lineId: String, lineName: String, mode: String): TripRow {
    val leg = TripLeg(mode, lineId, lineName, "", "", "", "", Instant.EPOCH, Instant.EPOCH)
    return TripRow(checking = true, every = listOf(TripLine(pillNamed(leg), status = null, restoring = true)))
}

/**
 * The row of one line a route page's "View line" opens ([RouteLinePage]): the line's status as the
 * route page has it, an alert behind the stop included since the page is about the whole line
 * ([lineRow]). [unknown] where the route page couldn't check the line or its check has gone stale.
 */
@WorkerThread
internal fun routeLineRow(row: DepartureRow, ride: TripLeg?, unknown: Boolean, checking: Boolean): TripRow =
    lineRow(
        row.mode, row.lineId, row.lineName, row.stopId, row.stopName,
        status = row.status ?: row.statusBehind, dismissed = row.statusDismissed, ride = ride, unknown = unknown, checking = checking,
    )

/**
 * The row of one line on a page of its own ([OneLinePage]), at [stopId]: its [status] and whether the
 * rider [dismissed] it. The trip's [ride] on it, where there is one, gives its map where the ride boards
 * and gets off and its stretch ([TripLine.rides]); else [stopId] is kept on the map as the rider's.
 * [unknown] where its check couldn't be made or has gone stale, so the page never claims a good service
 * it can't stand behind (SPEC D4), and says so beside a status kept from before too (Codex, #623);
 * [checking] while its check is out.
 */
@WorkerThread
internal fun lineRow(
    mode: String,
    lineId: String,
    lineName: String,
    stopId: String,
    stopName: String,
    status: LineStatus?,
    dismissed: Boolean,
    ride: TripLeg?,
    unknown: Boolean,
    checking: Boolean,
): TripRow {
    val known = mode.ifBlank { ride?.mode.orEmpty() }.ifBlank { Connections.knownMode(lineId).orEmpty() }
    val leg = ride?.takeIf { it.lineId == lineId }
        ?: TripLeg(known, lineId, lineName, stopId, stopName, "", "", Instant.EPOCH, Instant.EPOCH)
    val line = TripLine(
        pillNamed(leg.copy(mode = known, lineName = lineName.ifBlank { leg.lineName })),
        status,
        dismissed = dismissed,
        checking = checking,
        unknown = unknown && !checking,
        // What its map draws of the alert, so the same alert fetched again keeps the map up (Codex, #623).
        mapKey = LineMap.alertKey(status),
    )
    return TripRow(checking = checking, every = listOf(line))
}

/**
 * The tap-to-open route detail (SPEC D8 / *Disruptions*, `TODO.md`): a full-screen page — reached by
 * a tap on a timed or line-status card (a stop-closure card expands in place, so it never reaches
 * here) — that replaces the departures screen with its own app bar. The bar names the route (line
 * pill + destination) and carries the star (the list card only long-presses to pin); the body names
 * the boarding stop and the line's full disruption text the compact chip stands in for.
 *
 * A full screen rather than a dialog (maintainer, 2026-09-22): it will grow the maps/nav hand-off
 * and other per-route actions (`TODO.md`), which a dialog would cap. Mirrors the app's other full
 * screens ([LicensesScreen], [SettingsScreen]) — a composable with an [onBack], switched in by the
 * host's screen state; [BackHandler] routes the system back to [onBack] too, and it inherits the
 * theme's scaled density from the host, so no per-window font host is needed here.
 *
 * Pure: renders only [row] and reports the two actions. The star shows only when [starrable] (a
 * timed row with starring available) — a no-departures status row has nothing to rank, matching the
 * list's own rule — while the disruption text shows whenever the line carries prose, so a status
 * row still opens to its full alert.
 */
@Composable
internal fun RouteDetailScreen(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    // True when THIS row's disruption state is unchecked (its line was blank-id or omitted by TfL,
    // the line-status lookup failed, or its stop's own disruption lookup failed) rather than
    // checked-clean: the detail then says "couldn't check" rather than a verified-clean "no
    // disruptions" (SPEC principle 1). The caller decides this per row, so one unknown line or stop
    // doesn't taint a checked-clean row.
    disruptionUnknown: Boolean,
    // True while a check for this row is still out on a cold load (its stop's closure check, or its
    // line's status): the page says it's checking, ahead of [disruptionUnknown], rather than claim
    // "no disruptions", or a failure, before the check is back (SPEC principle 1).
    disruptionChecking: Boolean = false,
    // True when this row's snapshot has crossed the staleness threshold: the status is from an old
    // fetch, so the page — which carries no freshness stamp of its own, unlike the list — caveats it
    // and never claims "no disruptions" from stale data (SPEC D4).
    stale: Boolean,
    // The ticking clock the countdowns count down against, as on the list.
    now: Instant,
    onToggleStar: () -> Unit,
    onBack: () -> Unit,
    // The stop list for the soonest train. Null resolves it from [LocalRouteStops]; a screenshot
    // test passes a fixed state.
    routeStops: RouteStopsUi? = null,
    // The stop list for a row with no train to follow (a trip leg's), given the page's retry count;
    // null follows the row's train as above.
    loadRouteStops: (@Composable (retry: Int) -> RouteStopsUi)? = null,
    // The route tapped on the card; null (a status row, or a caller with no route) follows the
    // row's soonest train.
    focus: RouteFocus? = null,
    // Dismisses the line's status alert (SPEC *Disruptions*): hidden until TfL changes its severity
    // or wording. Null shows no dismiss control.
    onDismissAlert: (() -> Unit)? = null,
    // Dismisses one of the row's planned alerts ([DepartureRow.plannedAlerts]); null offers no ×.
    onDismissPlanned: ((PlannedAlert) -> Unit)? = null,
    // The starred journeys (SPEC *Journeys*): a station on the stop list with one from this stop is
    // starred, and tapping a station stars or unstars the journey there. Null (a bus, whose return
    // leaves from another pole, or a caller without journeys) leaves the stations inert.
    journeys: List<StarredJourney> = emptyList(),
    // True while the saved journeys are still being read: an empty [journeys] isn't yet "none saved".
    journeysLoading: Boolean = false,
    onToggleJourney: ((StarredJourney) -> Unit)? = null,
    // Dismisses the tip on starring a journey from the stop list; null shows none.
    onDismissJourneyTip: (() -> Unit)? = null,
    // The trip's ride this page is for: its line's page ("View line") keeps where it boards and gets off,
    // and shows an alert on its stretch in full. Null keeps the row's own stop alone.
    ride: TripLeg? = null,
    // The line's own check alone, for its page ("View line"): couldn't be made or isn't current
    // ([lineUnknown]), or still out ([lineChecking]). That page is about the line, not this stop, so the
    // stop's closure check behind [disruptionUnknown] and [disruptionChecking] stays off it (Codex, #623).
    lineUnknown: Boolean = disruptionUnknown || stale,
    lineChecking: Boolean = disruptionChecking,
) {
    BackHandler(onBack = onBack)
    // "View line" in the overflow: the line's own page over this one, with its map (maintainer,
    // 2026-10-06).
    var lineOpen by rememberSaveable { mutableStateOf(false) }
    if (lineOpen) RouteLinePage(row, ride, unknown = lineUnknown, checking = lineChecking, onClose = { lineOpen = false })
    val followed = followedDeparture(row, focus, LocalRouteTopology.current)
    var routeStopsRetry by rememberSaveable { mutableIntStateOf(0) }
    // A stale row's soonest prediction may not be the next train any more, so its stop list is
    // withheld rather than shown as current (SPEC D4); it returns as soon as a refresh lands.
    val stops = when {
        // Only a row with a train to follow: a status row has no list to withhold.
        stale && row.hasTrains && row.lineId.isNotBlank() -> RouteStopsUi.Stale
        routeStops != null -> routeStops
        loadRouteStops != null -> loadRouteStops(routeStopsRetry)
        else -> rememberRouteStops(row, followed, routeStopsRetry)
    }
    // A first guess at the stretch the line's alert is about: the listed stations its prose names
    // (SPEC *Disruptions*). Only for a shown alert, so a dismissed one leaves the page unmarked too.
    // A page with no stop list to show — a status row (no train to follow), or a stale one whose
    // train-specific list is withheld — loads the line's stations just to name them; they aren't
    // listed, since which direction or branch to list is a guess (and a stale train may be gone).
    // The alert the page tells of: the one flagging the row, else one wholly behind its stop
    // ([DepartureRow.statusBehind]), told muted.
    val alertText = (row.status ?: row.statusBehind)?.fullText
    val lineStops = rememberLineStops(
        row.lineId,
        // Not for a line TfL doesn't know: its route request already failed, and asking again can't
        // name anything.
        wanted = alertText != null && when (stops) {
            RouteStopsUi.Hidden, RouteStopsUi.Stale -> true
            is RouteStopsUi.Unavailable -> stops.reason != RouteStops.Resolution.UnknownLine
            else -> false
        },
    )
    // What the alert marks ([AlertMarks]): the stations its prose names (SPEC *Disruptions*), the
    // train's stops it touches, for their ⚠s (those it names and, on the train's own list, in route
    // order, the ones between two named ends of a stretch it gives, "between Moorgate and Monument"),
    // and where it is, beside the chip (on the train's list, else its whole route, else the stations
    // it names; each name once, as a bus line's stops on both sides of the road share one). None for
    // an alert behind the stop, which flags nothing. Read off the main thread: matching builds a
    // pattern per station and per pair of them, which froze the page on a long bus route. The page
    // shows at once, unmarked, and the marks follow.
    val trainStops = (stops as? RouteStopsUi.Loaded)?.stops
    val trainSequence = (stops as? RouteStopsUi.Loaded)?.sequence
    val statusText = row.status?.fullText
    val hasStatus = row.status != null
    // A new alert's marks clear the old ones at once, so a reworded or cleared alert never shows the
    // last one's ⚠s or places while its own are read; a stop list refreshed under the same alert keeps
    // them, so an ordinary refresh doesn't make them blink.
    val alertWorker = LocalWorker.current
    val readAlert by produceState<Pair<Any?, AlertMarks>>(null to AlertMarks.NONE, alertText, statusText, hasStatus, trainStops, trainSequence, lineStops, alertWorker) {
        val alert = Triple(alertText, statusText, hasStatus)
        if (value.first != alert) value = alert to AlertMarks.NONE
        // The whole route the train's list is part of, from its first stop: the list starts at this
        // stop, so a stretch the alert gives before it is named from here (maintainer, 2026-10-02).
        val wholeRoute = withContext(alertWorker) {
            if (trainStops == null || trainSequence == null) emptyList() else RouteStops.wholeRouteOf(trainSequence, trainStops)
        }
        value = alert to AlertMarks.of(alertText, statusText, hasStatus, trainStops, lineStops, wholeRoute, shortName = { stop ->
            stop.name.substringBefore("/").trim().ifBlank { stop.name.ifBlank { stop.id } }
        }, worker = alertWorker)
    }
    val alertMarks = readAlert.second
    val alertStretchIds = alertMarks.stretch
    val alertPlaces = alertMarks.places
    // Every upcoming train on the followed route, not the card's first few — TfL predicts ~30 min
    // ahead, and the page has the room (SPEC *Route detail*).
    val topology = LocalRouteTopology.current
    // The route's trains and termini, worked out off the main thread from the row ([routeDetailWork]),
    // each answer kept with the ask it was worked out for: a token remembered under the same keys,
    // compared the same way, so it changes exactly when the work is asked for again (a refreshed row),
    // and an equal row passed afresh (the trip page's copy) keeps its answer. Trains are shown only
    // from the current ask's answer, never a refreshed-away row's (a canceled train would read as
    // live); until it lands the countdown keeps its line, so nothing below it moves. The termini name
    // the route, not a train, so the last answer's stand in meanwhile.
    val routeWorker = LocalWorker.current
    val routeAsk = remember(row, focus, topology) { Any() }
    val routeWork by produceState<Pair<Any, RouteDetailWork>?>(null, routeAsk, routeWorker) {
        value = routeAsk to routeDetailWork(row, focus, topology, routeWorker)
    }
    val workForRow = routeWork?.takeIf { it.first === routeAsk }?.second
    val departures = workForRow?.departures.orEmpty().filterNot { Countdown.hasDeparted(it, now) }
    // The route's trains its board lists with no time, among its countdowns in their place; a
    // canceled one gone at its time, as a timed one is ([Countdown.stillShown]).
    val untimed = workForRow?.untimed.orEmpty().filter { Countdown.stillShown(it, now) }
    val destinations = routeWork?.second?.destinations.orEmpty()
    // The followed train's branch as the card labels it ("Hainault/Newbury Park"), so the title names
    // the same route the tapped row did; null where the card shows no branch (it makes no difference
    // from this stop, or the row has none).
    val titleBranch = if (focus != null && followed != null && destinations.size == 1) {
        topology.grouping(row.lineId, row.stopId, followed.destination, followed.branch).label
    } else {
        null
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                // Name the route being viewed — the line pill, then where it's going — so the bar
                // reflects the page's subject (maintainer, 2026-09-22). A status row with no
                // predictions shows just the pill.
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode)
                        if (titleBranch != null) {
                            DestinationLabelContent(
                                label = destinations.single(),
                                branch = titleBranch,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        } else if (destinations.isNotEmpty()) {
                            Text(
                                text = destinations.joinToString(", "),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                },
                actions = {
                    // The star (pin-to-top) as the bar's action — the discoverable control, shown
                    // only where the row is pinnable (same rule the list card uses). Filled and gold
                    // when starred, matching the list card's gold pin border; the vendored outline
                    // star when not (material-icons-core ships the filled Star but not its outline).
                    // No visible label — the contentDescription flips with state so a screen reader
                    // still hears what the tap does (SPEC principle 2).
                    if (starrable) {
                        IconButton(onClick = onToggleStar) {
                            Icon(
                                imageVector = if (isStarred) Icons.Filled.Star else StarBorderIcon,
                                contentDescription = stringResource(
                                    if (isStarred) R.string.unstar else R.string.star,
                                ),
                                tint = if (isStarred) {
                                    LocalStarredBorderColor.current
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    AppMenuOverflow { close ->
                        // A status row about a stop has no line to show.
                        if (row.lineId.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.route_detail_view_line)) },
                                onClick = {
                                    close()
                                    lineOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // Scrollable so a long expanded alert or a large text size isn't clipped (SPEC *Display
        // size*); the 16dp gutter matches the app's other reading surfaces.
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Before verticalScroll, so the cue stays pinned to the viewport ([scrollEdgeCue]).
                .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.background))
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // "From Victoria" names the boarding stop only while no stop list does: the list opens on
            // it, so with the list shown the label is repeated noise, but a status row (no train to
            // follow) or a loading, failed, or withheld list would otherwise leave the page not saying
            // which stop it's about — ambiguous when one line is watched at two stops (Codex).
            // The service's full name first, so the pill's short code (LNR, AWC, HAM) is never
            // a puzzle; left out where the pill already says it all (a bus number, DLR).
            // TfL can leave the soonest departure's mode off: take it from another departure, else
            // from the line id, so a tube line still reads "Victoria line".
            val headingMode = row.mode
                .ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
                .ifBlank { Connections.knownMode(row.lineId).orEmpty() }
            val service = serviceName(row.lineName, headingMode, row.lineId)
            if (service != null) {
                Text(
                    text = if (takesLineSuffix(row.lineName, headingMode)) {
                        stringResource(R.string.route_detail_line_name, service)
                    } else {
                        service
                    },
                    style = MaterialTheme.typography.titleMedium,
                    // A heading, so TalkBack's heading navigation lands on what the page is about.
                    modifier = Modifier.semantics { heading() },
                )
            }
            val place = row.hubName.ifBlank { row.stopName }
            val showFrom = stops !is RouteStopsUi.Loaded && place.isNotBlank()
            if (showFrom) {
                Text(
                    text = stringResource(R.string.route_detail_from, place),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (service != null) 4.dp else 0.dp),
                )
            }
            // The followed route's countdowns, leading the page — the card's one-line format, times
            // only, but across the page's full width so more fit; the ones that don't ellipsize off
            // the end, keeping the soonest. Left out while stale — the stale caveat below says why —
            // so an old prediction is never shown as live (SPEC D4).
            if (workForRow == null && row.hasTrains && !stale) {
                // The row's trains are being worked out: hold the countdown's line, blank.
                Text(
                    text = "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(top = if (showFrom || service != null) 8.dp else 0.dp),
                )
            } else if ((departures.isNotEmpty() || untimed.isNotEmpty()) && !stale) {
                val entries = Countdown.entries(departures, untimed)
                // A train with no time is read aloud in full ("delayed, no estimate"), as the card's
                // is ([CountdownLabel]).
                val spoken = if (untimed.isEmpty()) null else spokenCountdown(entries, now)
                Text(
                    text = Countdown.mergedLabel(
                        entries,
                        now,
                        stringResource(R.string.countdown_canceled),
                        stringResource(R.string.countdown_delayed),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = if (showFrom || service != null) 8.dp else 0.dp)
                        .let { if (spoken != null) it.semantics { contentDescription = spoken } else it },
                )
            }
            val status = row.status
            if (status != null) {
                // The short chip label always; the full prose below it, collapsed to its first line
                // with tap-to-expand, when TfL gave a reason (SPEC *Disruptions*). The dismiss (×) sits
                // at the chip's end, so it's there whether or not TfL gave prose.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        DisruptionChip(status.description)
                        // Where it is, beside what it is: on the train's own list, the runs of stops the
                        // alert touches (the ones its ⚠s mark), each by its ends, "A to B"; with no list
                        // to follow, the stations it names. Each stop by the part a rider reads on it (a
                        // bus stop's own name, not its cross street).
                        if (alertPlaces.isNotEmpty()) {
                            Text(
                                text = alertPlaces.joinToString(", "),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (onDismissAlert != null) {
                        IconButton(onClick = onDismissAlert) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.alert_dismiss),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                status.fullText?.let { fullText ->
                    CollapsibleStatus(
                        text = fullText,
                        title = null,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // A line alert wholly behind this stop (a bus diversion a bus from here has passed): told,
            // muted, with where it is, but never flagged as this row's (SPEC *Disruptions*).
            val behind = row.statusBehind
            if (status == null && behind != null) {
                Text(
                    text = if (alertPlaces.isEmpty()) {
                        stringResource(R.string.route_detail_alert_behind, behind.description)
                    } else {
                        stringResource(R.string.route_detail_alert_behind_at, behind.description, alertPlaces.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                behind.fullText?.let { fullText ->
                    CollapsibleStatus(
                        text = fullText,
                        title = null,
                        modifier = Modifier.padding(top = 8.dp),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Work still to come, after any disruption now: each with the day it starts, so a
            // closure next month reads as notice, not as today's trouble (SPEC *Disruptions*).
            row.plannedAlerts.forEach { planned ->
                PlannedAlertBlock(
                    planned,
                    Modifier.padding(top = 12.dp),
                    onDismiss = onDismissPlanned?.let { dismiss -> { dismiss(planned) } },
                )
            }
            // The disruption-check state, independent of staleness (the two caveats are separate
            // facts, both shown when both apply — Codex): "couldn't check" whenever this row's line
            // was undetermined OR its stop's disruption lookup failed — a known line alert doesn't
            // mean the stop-level closure/move check ran, so the note sits alongside the alert too,
            // and the stale caveat below never stands in for it. "No disruptions reported" only when
            // determined-clean AND fresh — never claimed from stale or unchecked data (SPEC principle
            // 1 / D4). A stale but determined-clean row shows neither here; the stale caveat covers it.
            // A dismissed line alert is its own fact, shown beside any "couldn't check" note: the line
            // IS disrupted, the user only hid the alert, so never claim a clean line (SPEC principle 1).
            if (row.statusDismissed) {
                Text(
                    text = stringResource(R.string.route_detail_alert_dismissed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (disruptionChecking) {
                Text(
                    text = stringResource(R.string.disruptions_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (disruptionUnknown) {
                Text(
                    text = stringResource(R.string.disruptions_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (status == null && !row.statusDismissed && row.statusBehind == null && row.stopDisruption == null && !stale) {
                // Nor with a notice in force at its stop (a closure, a moved stop): not a clean stop.
                Text(
                    text = stringResource(R.string.route_detail_no_disruption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // The page has no freshness stamp of its own, so a stale snapshot is caveated here — an
            // independent fact from the disruption-check state above: it sits under a shown
            // disruption (which may be resolved or superseded), replaces a "no disruptions" claim
            // (a new one may exist), and sits alongside a "couldn't check" note (age and a failed
            // check are different gaps) alike (SPEC D4).
            if (stale) {
                Text(
                    text = stringResource(R.string.route_detail_status_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // Each saved journey that boards here on this page, with the stops on this page it ends at:
            // by id, or placed on this page's route the way the journey card places it (a bus's way
            // back boards across the road).
            // The row's mode, from any of its departures when TfL left it off the soonest one.
            val rowMode = row.mode.ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
            val journeysHere = rememberJourneysHere(journeys, stops, row.stopId, row.lineId)
            // Each listed station's step-free level for this line, from TfL's bundled table (SPEC
            // *Step-free access*); nothing until the table is read. Where a station is step-free only
            // by a lift, TfL's lift outages are asked for once the list is up, and again when that
            // answer ages out (it may be another screen's, from minutes ago) while the page is in the
            // foreground, so a mark comes off while a lift its route needs is out and comes back with
            // it, without reopening the page. An answer stands until the next replaces it: it only
            // ever takes marks off, so holding it through its refresh errs toward "not step-free".
            val stepFreeTable = LocalStepFree.current
            val liftOutages = LocalLiftsOut.current
            val lifecycleOwner = LocalLifecycleOwner.current
            val listed = (stops as? RouteStopsUi.Loaded)?.stops.orEmpty()
            val byLift = rememberLiftDependent(stepFreeTable, listed)
            // The first frame draws from TfL's last answer, held in memory, so a page opened (or
            // turned) while a lift is out never shows its mark, even before the fresh ask returns.
            var liftsOut by remember(liftOutages) { mutableStateOf(liftOutages?.known.orEmpty()) }
            var liftsCleared by remember(liftOutages) { mutableStateOf<LiftsCleared?>(null) }
            val liftWorker = LocalWorker.current
            LaunchedEffect(liftOutages, byLift, lifecycleOwner, liftWorker) {
                if (!byLift || liftOutages == null) return@LaunchedEffect
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        val answer = liftOutages.current()
                        // The same outages keep the same set, so the marks aren't worked out again (and
                        // hidden meanwhile) on every ask; compared on the worker, never here.
                        val held = liftsOut
                        val (changed, onlyCleared) = withContext(liftWorker) {
                            val changed = answer.ids != held
                            changed to (changed && held.containsAll(answer.ids))
                        }
                        if (changed) {
                            liftsCleared = if (onlyCleared) LiftsCleared(held, answer.ids) else null
                            liftsOut = answer.ids
                        }
                        // At least a second apart, whatever an answer says, so nothing can spin.
                        delay(answer.askAgainIn.toMillis().coerceAtLeast(1_000))
                    }
                }
            }
            val stepFree = rememberStepFreeLevels(stepFreeTable, liftsOut, listed, row.lineId, rowMode, liftsCleared)
            // No saved journeys (a trip's route page, say) and no star still shown from before the last was
            // taken off: there's nothing to wait for, nor any star a tap could take off (Codex on #555).
            val noJourneysHere = journeys.isEmpty() && journeysHere.here.byJourney.isEmpty()
            // Not before the saved journeys are read, when an empty list only means unread.
            val starsReady = !journeysLoading && (journeysHere.current || noJourneysHere)
            // The page and the route it follows, as the route's stops are keyed ([rememberRouteStops]): a
            // refresh of the same route keeps its rail up; another (the followed train gone, its next on
            // another branch, or under the bus rule, even from the memo) waits for its own marks.
            val railKey = "${row.stopId}|${row.lineId}|${row.direction}|${rowMode.equals("bus", ignoreCase = true)}|" +
                "${followed?.destination}|${followed?.branch}|${followed?.platform}|${followed?.direction}|" +
                "${followed?.destinationId}|${followed?.via}"
            val railState = rememberRailState(stops, marksReady = stepFree.known && starsReady, page = railKey)
            // Every station from here to where the soonest train terminates (SPEC *Route detail*).
            RouteStopsSection(
                state = railState,
                railColor = railColorFor(row),
                onRetry = { routeStopsRetry++ },
                modifier = Modifier.padding(top = 16.dp),
                // Rail reads it off the followed train's platform ("Southbound - Platform 2"); a bus
                // off its pole's compass bearing. Neither known, no heading rather than a guess.
                direction = PlatformDirection.of(followed?.platform) ?: bearingDirection(row.bearing),
                // By segment, whatever line or direction it was starred from: the 43 and the 134
                // between two shared stops are one journey, and so is its way back from the poles
                // across the road — so any of those pages shows (and toggles) the same star.
                starredStopIds = journeysHere.here.starredStopIds,
                alertStopIds = alertStretchIds,
                stepFree = stepFree.levels.orEmpty(),
                onDismissJourneyTip = onDismissJourneyTip,
                // Not until the page's journeys are worked out for what it shows: a tap goes by them.
                // With none here, a tap can only star: the first lands at once. A star still shown after the
                // last was taken off waits, or a second tap would put it back.
                journeyTapsReady = journeysHere.current || noJourneysHere,
                onToggleJourneyTo = onToggleJourney
                    ?.takeIf { row.lineId.isNotBlank() && (Connections.isRail(rowMode, row.lineId) || rowMode.equals("bus", ignoreCase = true)) }
                    ?.let { toggle ->
                        { stop ->
                            val positions = (stops as? RouteStopsUi.Loaded)?.positions.orEmpty()
                            val areas = (stops as? RouteStopsUi.Loaded)?.sequence?.stopAreas.orEmpty()
                            // The name as the list shows it: a stop TfL gave no name keeps its id,
                            // so a saved journey's heading never has a blank end. Its stop area rides
                            // along, so "Find a station" can open the end as the whole place.
                            fun end(id: String, name: String) =
                                JourneyEnd(id, name.ifBlank { id }, positions[id]?.first, positions[id]?.second, areas[id].orEmpty())
                            // A saved journey this stop already ends on this page is toggled (off) as
                            // itself, rather than starred again under this direction's pole ids.
                            // A tap's own lookup, over this row's few saved journeys.
                            val existing = journeysHere.here.byJourney.entries.firstOrNull { stop.id in it.value }?.key
                            toggle(
                                existing ?: StarredJourney(
                                    end(row.stopId, row.stopName), end(stop.id, stop.name), row.lineId, row.lineName, rowMode,
                                ),
                            )
                        }
                    },
            )
        }
    }
}

/**
 * One destination line within a card — `destination · · · countdown` — used for the
 * soonest destination and for each divergent one of a branching direction alike, so they
 * render at the **same weight and the same indentation** (all sit in the card's one
 * destination column, beside the pill). [times] empty is a status row: the line is named
 * with no countdown. A long destination first shortens common whole words
 * ([DestinationAbbreviations]) and then, if it still doesn't fit, is hard-truncated with a
 * clean cut — no ellipsis (maintainer preference) — rather than crowding out the countdown.
 * The whole destination line — terminus and its branch/via — clips; only the countdown
 * keeps its ellipsis (below).
 */
@Composable
internal fun DestinationLine(
    label: String,
    times: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
    // The "via" branch (TfL's `towards`), joined to the destination with a slash —
    // "Battersea/Charing X" — the cue a rider uses to pick a train. Null for most
    // services; a branch-free row whose name has no abbreviatable word takes the cheap
    // no-measuring path below.
    branch: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DestinationLabelContent(label, branch, Modifier.weight(1f).padding(end = 12.dp))
        if (times.isNotEmpty()) {
            CountdownLabel(times, stale, now)
        }
    }
}

/**
 * The destination label of a route — the terminus, its abbreviation/branch rendering — laid into the
 * [modifier] the caller provides (a weighted slot beside the countdown, or beside the pill on a group
 * card). Extracted from [DestinationLine] so both the standalone destination line and the group
 * card's route rows render the label identically. A branch-free name shortens common whole words
 * ([DestinationAbbreviations]) then hard-clips (no ellipsis); a branching name shares the width with
 * its "/branch" cue ([branchedLabel]), the branch outranking the terminus. The full name stays the
 * screen-reader label when the visible text is shortened.
 */
@Composable
private fun RowScope.DestinationLabelContent(label: String, branch: String?, modifier: Modifier) {
    if (branch == null) {
        // The longest of full, word-abbreviated and floor that fits, eliding with a single "…" only
        // below the floor (SPEC destination-label — shorten before eliding, never cut mid-glyph).
        ShortenedName(label, MaterialTheme.typography.titleMedium, modifier)
    } else {
        // The branch is the cue that tells a branching line's two trunks apart, so it is kept whole:
        // the branch lays out at its natural width and the terminus takes the leftover, shrinking
        // (word-abbreviated, then floored) and eliding with a single "…" before it would cut — never
        // mid-glyph (SPEC destination-label). A branch TfL already gives in board form ("Charing X")
        // is unchanged by [abbreviateBranch]; a spelled-out one shortens ("Newbury Park" → "Newbury
        // Pk"). A fuller rider-readable form is a follow-up (TODO). branchedLabel turns the measured
        // widths into the strings, so the rule is unit-tested apart from the render.
        BoxWithConstraints(modifier = modifier) {
            val style = MaterialTheme.typography.titleMedium
            val measurer = rememberTextMeasurer()
            val abbreviatedLabel = remember(label) { DestinationAbbreviations.abbreviate(label) }
            val floorLabel = remember(label) { DestinationAbbreviations.floor(label) }
            val shortBranch = remember(branch) { abbreviateBranch(branch) }
            // Key each measurement on the font scale as well as the text, so a display-size /
            // accessibility resize re-measures rather than reusing a width cached on the string alone.
            val fontScale = LocalDensity.current.fontScale
            fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
            val resolved = branchedLabel(
                label = label,
                abbreviatedLabel = abbreviatedLabel,
                floorLabel = floorLabel,
                branch = branch,
                abbreviatedBranch = shortBranch,
                maxWidth = constraints.maxWidth,
                labelWidth = remember(label, style, fontScale) { widthOf(label) },
                abbrevLabelWidth = remember(abbreviatedLabel, style, fontScale) { widthOf(abbreviatedLabel) },
                fullBranchWidth = remember(branch, style, fontScale) { widthOf("/$branch") },
                abbrevBranchWidth = remember(shortBranch, style, fontScale) { widthOf("/$shortBranch") },
                minStubWidth = remember(floorLabel, style, fontScale) { widthOf("${floorLabel.take(1)}…") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (resolved.terminus.isNotEmpty()) {
                    Text(
                        text = resolved.terminus,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        // Ellipsize cleanly (single "…", never mid-glyph) in the room the whole branch
                        // leaves; branchedLabel already shrank the terminus to a form that fits.
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // Keep the full name for a screen reader when the visible text is shortened.
                            .then(
                                resolved.contentDescription?.let { full ->
                                    Modifier.semantics { contentDescription = full }
                                } ?: Modifier,
                            ),
                    )
                }
                Text(
                    text = resolved.branch,
                    style = style,
                    maxLines = 1,
                    // Kept whole at its natural width; ellipsis is a last resort only for a long
                    // branch standing alone in the narrowest row (nothing left to yield).
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // A shortened branch keeps its full name for a screen reader ("via Newbury Park"),
                    // and a branch standing alone (no terminus shown) carries BOTH the full terminus
                    // and the full branch, so two otherwise-identical Bank and Charing Cross rows
                    // stay distinguishable (Codex P2).
                    modifier = resolved.branchDescription?.let { spoken ->
                        Modifier.semantics { contentDescription = spoken }
                    } ?: Modifier,
                )
            }
        }
    }
}

/**
 * What the route page shows of its row, worked out together off the main thread ([routeDetailWork]):
 * every upcoming train on the followed route ([routeDepartures]), its trains with no time
 * ([routeUntimed]), and the terminus or termini it runs to.
 */
internal data class RouteDetailWork(
    val departures: List<Departure>,
    val untimed: List<UntimedTrain>,
    val destinations: List<String>,
) {
    companion object {
        val NONE = RouteDetailWork(emptyList(), emptyList(), emptyList())
    }
}

/**
 * [RouteDetailWork] for [row] and [focus] under [topology], on [worker] (AGENTS.md *Main thread*):
 * each part walks the row's trains, and the termini group them by destination and branch
 * ([DepartureRows.destinationLines]). The termini are empty for a status row (no predictions), which
 * then shows only the line and its disruption; a tapped route names just that route, matching the
 * stop list below it.
 */
internal suspend fun routeDetailWork(
    row: DepartureRow,
    focus: RouteFocus?,
    topology: RouteTopology,
    worker: CoroutineDispatcher,
): RouteDetailWork = withContext(worker) {
    val followed = followedDeparture(row, focus, topology)
    val destinations = if (!row.hasTrains) {
        emptyList()
    } else if (focus != null && followed != null) {
        listOfNotNull(DepartureLabels.destinationLabel(followed.destination, row.directionKey))
    } else {
        DepartureRows.destinationLines(row, MAX_TIMES, topology)
            .mapNotNull { DepartureLabels.destinationLabel(it.destination, row.directionKey) }
            .distinct()
    }
    RouteDetailWork(routeDepartures(row, focus, topology), routeUntimed(row, focus, topology), destinations)
}

/**
 * A service's merged countdown — "0 · 3 · 6 min" (SPEC D8, one line per destination).
 * Withheld once the stop is stale, since the underlying predictions are likely wrong and a
 * live-looking number would misrepresent them (SPEC D4): the soonest train's predicted time
 * stands in, marked as a guess ("21:14?", [Countdown.staleLabel]). A National Rail train
 * its board lists with no time ([untimed]) takes its place among them as a word: "Canceled",
 * or "Delayed" for one with no estimate: a word, since "?" is a stale countdown
 * ([Countdown.entries]).
 */
@Composable
internal fun CountdownLabel(
    departures: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
    // A time due before the rider can be there (a trip on the way, still walking) is grayed: listed,
    // but not one they can catch. Null grays none.
    grayBefore: Instant? = null,
    untimed: List<UntimedTrain> = emptyList(),
) {
    val entries = Countdown.entries(departures, untimed)
    val canceled = stringResource(R.string.countdown_canceled)
    val delayed = stringResource(R.string.countdown_delayed)
    val gray = MaterialTheme.colorScheme.outline
    val early = grayBefore?.let { ready ->
        entries.map { (it as? Countdown.Entry.Timed)?.departure?.expectedArrival?.isBefore(ready) == true }
    }
    // Each entry's own text, the "min" unit written once after the last number, as
    // [Countdown.mergedLabel] writes them.
    val last = entries.indexOfLast { it is Countdown.Entry.Timed }
    val parts = entries.map { entry ->
        when (entry) {
            is Countdown.Entry.Timed -> Countdown.minutes(entry.departure.expectedArrival, now).toString()
            is Countdown.Entry.Untimed -> if (entry.train.canceled) canceled else delayed
        }
    }
    // Gray alone doesn't reach a screen reader: each time too soon to catch says so, as a trip's
    // list card says it of its trains.
    val spoken = if (stale || ((early == null || early.none { it }) && untimed.isEmpty())) null else spokenCountdown(entries, now, early)
    Text(
        text = when {
            stale -> AnnotatedString(Countdown.staleLabel(departures))
            else -> buildAnnotatedString {
                parts.forEachIndexed { i, part ->
                    if (i > 0) append(" · ")
                    if (early?.getOrNull(i) == true) withStyle(SpanStyle(color = gray)) { append(part) } else append(part)
                    if (i == last) append(" min")
                }
            }
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        // One line, never wrapped — the countdown is the one thing that must stay legible
        // (SPEC D8). It's the unweighted element of its row, so it's measured first and its
        // width reserved; the destination beside it ellipsizes when space is tight. In the
        // extreme (a long pill + the full "0 · 3 · 6 min" at a large font, on a narrow
        // screen) even the whole line can be too short: ellipsize from the end rather than
        // hard-clip, so the soonest times — which lead the label — stay legible and the
        // truncation reads as one ("0 · 3 …").
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color =
            if (stale) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        modifier = if (spoken != null) modifier.semantics { contentDescription = spoken } else modifier,
    )
}

/**
 * [entries] read aloud, one by one: a time's minutes, and whether it can be caught where [early] says
 * it leaves too soon; and what a train with no time is, in full ("delayed, no estimate"; SPEC
 * *National Rail*).
 */
@Composable
internal fun spokenCountdown(entries: List<Countdown.Entry>, now: Instant, early: List<Boolean>? = null): String =
    entries.mapIndexed { i, entry ->
        when (entry) {
            is Countdown.Entry.Timed -> stringResource(
                if (early?.getOrNull(i) == true) R.string.countdown_time_unusable_description else R.string.countdown_time_description,
                Countdown.minutes(entry.departure.expectedArrival, now).toString(),
            )
            is Countdown.Entry.Untimed -> stringResource(
                if (entry.train.canceled) R.string.countdown_canceled_description else R.string.countdown_delayed_description,
            )
        }
    }.joinToString(", ")

/**
 * Marks a row whose line is disrupted (SPEC D3). A small error-toned chip carrying TfL's
 * status wording, sat between the header and the destination so it reads before the
 * countdowns it qualifies. The pill above already names the line, so the chip is the
 * status alone ("Severe Delays"), not "Victoria line: severe delays".
 */
@Composable
internal fun DisruptionChip(
    description: String,
    modifier: Modifier = Modifier,
    // Toned down, for an alert the rider dismissed but that's still under way.
    muted: Boolean = false,
) {
    Surface(
        color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/**
 * The app's name in the top bar, drawn only when it fits whole. The bar's actions (the freshness
 * stamp, refresh, the menu) come first, and at a large text size they can leave the title a sliver:
 * wrapped it stacked one letter per line, and ellipsized it was a bare "…". The mark beside it already
 * says which app this is, so the name is left out rather than cut; a screen reader still hears it.
 */
@Composable
internal fun AppTitle() {
    var fits by remember { mutableStateOf(true) }
    Text(
        stringResource(R.string.app_name),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { fits = !it.hasVisualOverflow },
        modifier = Modifier.drawWithContent { if (fits) drawContent() },
    )
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onRefresh, modifier = modifier) {
        Text(stringResource(R.string.refresh))
    }
}

internal fun errorMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.error_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.error_rate_limited
    DeparturesUiState.Error.Kind.NETWORK, DeparturesUiState.Error.Kind.SERVER -> R.string.error_unreachable
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.error_key_rejected
}

/**
 * The "couldn't be refreshed" banner, kept short: the nearest stop, how many more, and why when
 * every failure agrees — "Oxford Circus +2: network error" — so a TfL outage at one station reads as
 * that, not as the app failing somewhere unnamed (SPEC principle 6). Falls back to "Some stops
 * couldn't be refreshed" when no stop is named.
 */
@Composable
private fun partialRefreshMessage(names: List<String>, reason: DeparturesUiState.Error.Kind?): String {
    val which = when (names.size) {
        0 -> return stringResource(R.string.partial_refresh)
        1 -> names[0]
        else -> stringResource(R.string.partial_refresh_more, names[0], names.size - 1)
    }
    return if (reason == null) {
        stringResource(R.string.partial_refresh_no_reason, which)
    } else {
        stringResource(R.string.partial_refresh_reason, which, stringResource(partialReason(reason)))
    }
}

private fun partialReason(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.partial_reason_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.partial_reason_rate_limited
    DeparturesUiState.Error.Kind.NETWORK -> R.string.partial_reason_network
    DeparturesUiState.Error.Kind.SERVER -> R.string.partial_reason_server
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.partial_reason_key_rejected
}

private fun refreshFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.refresh_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.refresh_failed_rate_limited
    DeparturesUiState.Error.Kind.NETWORK -> R.string.refresh_failed_network
    DeparturesUiState.Error.Kind.SERVER -> R.string.refresh_failed_server
    DeparturesUiState.Error.Kind.KEY_REJECTED -> R.string.refresh_failed_key_rejected
}

private const val MAX_TIMES = 3
// A stale stop's countdown is unknown, not zero, so it withholds the number as "?" — "—"
// read as "none," which is a different thing (that's the no-departures status). The stamp
// up top ("Tap to refresh") says why.

/**
 * The scroll positions of the drill-down views open from the full list (a station, a platform in
 * it, a journey), one per view identity, saved across rotation. Held in a plain map — it is read
 * only to hand a view its [LazyListState], never observed — and cleared when the full list is back.
 */
internal class DrillScrollStates(private val states: HashMap<String, LazyListState> = HashMap()) {
    fun stateFor(key: String): LazyListState = states.getOrPut(key) { LazyListState() }

    fun clear() = states.clear()

    companion object {
        val Saver: Saver<DrillScrollStates, Any> = listSaver(
            save = { holder ->
                holder.states.flatMap { (key, state) ->
                    listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            },
            restore = { saved ->
                DrillScrollStates(
                    saved.chunked(3).associateTo(HashMap()) { (key, index, offset) ->
                        key as String to LazyListState(index as Int, offset as Int)
                    },
                )
            },
        )
    }
}

/**
 * One [StopRef] per journey origin stop: the lines of every journey fetching it, and the first
 * interchange any of them knows, so one whose route isn't in yet doesn't blank another's.
 */
@WorkerThread
internal fun mergeJourneyOrigins(refs: List<StopRef>): List<StopRef> =
    refs.groupBy { it.id }.map { (_, same) ->
        same.first().copy(
            lines = same.flatMap { it.lines }.distinctBy { it.id },
            hubId = same.firstOrNull { it.hubId.isNotBlank() }?.hubId.orEmpty(),
        )
    }

