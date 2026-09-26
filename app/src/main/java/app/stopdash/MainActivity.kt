package app.stopdash

import android.Manifest
import app.stopdash.data.FileNearbyStopsStore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import app.stopdash.domain.ArrivalsCache
import app.stopdash.domain.CachingTflClient
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.DepartureRow
import app.stopdash.domain.JourneyEnd
import androidx.compose.ui.res.stringResource
import app.stopdash.ui.rememberTripView
import app.stopdash.ui.hereOriginIds
import app.stopdash.ui.fartherCardsKey
import app.stopdash.ui.fartherReached
import app.stopdash.ui.reachedStopIds
import app.stopdash.ui.PendingTracker
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.PlanTargets
import app.stopdash.domain.stopPlace
import app.stopdash.data.FileStarredPlacesStore
import kotlinx.coroutines.flow.first
import app.stopdash.widget.logWidgetSnapshotWarning
import app.stopdash.domain.YourStops
import app.stopdash.domain.StarredRowSet
import app.stopdash.data.FileRecentStationsStore
import app.stopdash.data.RecentSearches
import app.stopdash.data.DataStoreSnapshotStore
import app.stopdash.data.FileRouteStopsStore
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stopdash.data.AndroidLocationProvider
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.DistanceUnitsSetting
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.data.DataStoreDismissedAlertsStore
import app.stopdash.data.DataStoreStarredRowsStore
import app.stopdash.data.KtorTflClient
import app.stopdash.data.RouteTopologyStore
import app.stopdash.data.StationIndexStore
import app.stopdash.domain.RecentPositions
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.data.SharedTflRateLimiter
import app.stopdash.data.SharedTflRequestPool
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.RailApiKeySetting
import app.stopdash.domain.TflClient
import app.stopdash.domain.RailAwareTflClient
import app.stopdash.data.RailStationCodesStore
import app.stopdash.data.KtorDarwinClient
import app.stopdash.data.UserApiKeySetting
import app.stopdash.domain.AppSettings
import app.stopdash.domain.BugReport
import java.io.IOException
import app.stopdash.domain.Journeys
import app.stopdash.data.DataStoreStarredJourneysStore
import app.stopdash.ui.rememberFarReveal
import app.stopdash.ui.rememberListStateFor
import app.stopdash.ui.rememberPendingTracker
import app.stopdash.domain.StarredJourney
import app.stopdash.domain.CachingStopFinder
import app.stopdash.domain.NearbyStopsCache
import app.stopdash.domain.Coordinates
import app.stopdash.domain.StationMatch
import app.stopdash.domain.StopMap
import app.stopdash.ui.BugReportConsentDialog
import app.stopdash.ui.FontSizeSetting
import app.stopdash.ui.LocalRouteStops
import app.stopdash.ui.LocalAppMenu
import app.stopdash.ui.AppMenuActions
import app.stopdash.ui.LocalRouteTopology
import app.stopdash.ui.LicensesScreen
import app.stopdash.telemetry.TelemetryConsent
import app.stopdash.ui.FarRevealState
import app.stopdash.ui.LocationBanner
import app.stopdash.ui.LocationGate
import app.stopdash.ui.MainScreen
import app.stopdash.ui.ARRIVALS_REUSE
import app.stopdash.ui.DISRUPTION_REUSE
import app.stopdash.ui.FAR_ARRIVALS_REUSE
import app.stopdash.ui.LINE_STATUS_REUSE
import app.stopdash.ui.MainViewModel
import app.stopdash.ui.NearbyStopsViewModel
import app.stopdash.domain.NearbySelection
import app.stopdash.domain.SnapshotStore
import app.stopdash.domain.FartherBuses
import app.stopdash.domain.FartherStations
import app.stopdash.ui.FartherLoad
import app.stopdash.ui.DeparturesUiState
import app.stopdash.ui.FartherCard
import app.stopdash.ui.FartherCardsViewModel
import app.stopdash.ui.withOpenedFarther
import app.stopdash.domain.CollapsedPlaces
import app.stopdash.domain.FixedLocation
import app.stopdash.ui.hereTripTiers
import app.stopdash.ui.HereTripTiers
import app.stopdash.ui.ProvideDistanceSystem
import app.stopdash.ui.SettingsScreen
import app.stopdash.ui.StationPlaceholderScreen
import app.stopdash.ui.StationSearchScreen
import app.stopdash.ui.StationSearchViewModel
import app.stopdash.ui.StationStopsViewModel
import app.stopdash.ui.StopRef
import app.stopdash.ui.TripScreen
import app.stopdash.ui.TripViewModel
import app.stopdash.domain.TripTiming
import app.stopdash.ui.WriteFailures
import app.stopdash.ui.theme.StopDashTheme
import app.stopdash.widget.LiveWidgetRefreshResult
import app.stopdash.widget.StopDashWidget
import app.stopdash.widget.WidgetSnapshotStore
import app.stopdash.widget.applyLiveWidgetRefresh
import app.stopdash.widget.syncLiveWidgetRefreshSchedule
import androidx.core.content.FileProvider
import androidx.glance.appwidget.updateAll
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ReportScreenshot
import com.mikelward.androidlog.android.ShareOutcome
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns a captured screenshot [file] into a shareable `content://` URI, degrading to `null` — a
 * text-only report, never a dropped share (SPEC principle 2) — rather than letting a `FileProvider`
 * failure escape the application-scoped share coroutine and crash it. The app mints the URI (the
 * provider and its authority are the app's), so this guard lives here rather than in the shared
 * capture, which took the app-local copy's place. [mint] is `FileProvider.getUriForFile` in
 * production, injected so the fallback is testable without a real provider. The orphaned PNG is
 * best-effort deleted; the shared capture's age-prune reclaims it otherwise.
 */
internal fun bugReportScreenshotUri(file: File, log: DebugLog, mint: (File) -> Uri): Uri? =
    try {
        mint(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warning("bug report: could not build the screenshot URI: %s", e.javaClass.simpleName)
        file.delete()
        null
    }

/**
 * Load state of the stored TfL app_key for the Settings field. Needed because the key itself is
 * nullable (null = keyless), so a plain nullable couldn't distinguish "not read yet" from "read,
 * no key" — and the field stays disabled until [Loaded] so a slow read can't be edited over a value
 * that hasn't arrived (Codex P2).
 */
private sealed interface ApiKeyLoad {
    data object Loading : ApiKeyLoad
    data class Loaded(val key: String?) : ApiKeyLoad
}

/**
 * A stored key ([read] from [settings]) for its Settings field, and whether it has been read yet:
 * [ApiKeyLoad] rather than a bare nullable, since a key is itself nullable (none), so a bare
 * `null` couldn't tell "not read yet" from "loaded, no key", and the field must stay disabled
 * until it's really loaded, so a slow read can't present an empty field the user edits over a key
 * that then arrives and resets the draft (Codex P2, mirroring the live-widget switch).
 *
 * Loading is a first-open concern, not a per-rotation one: the collection restarts at Loading on
 * every configuration change, and letting the field flash back through "not loaded"/empty would
 * re-seed and drop an unsaved edit (Codex). So the last loaded key and the loaded flag are retained
 * across recreation (saved under [name]), and after a rotation the field keeps the real key.
 */
@Composable
private fun rememberStoredKey(
    settings: AppSettings,
    name: String,
    read: (AppSettings) -> Flow<String?>,
): Pair<String?, Boolean> {
    val loadFlow = remember(settings) { read(settings).map<String?, ApiKeyLoad> { ApiKeyLoad.Loaded(it) } }
    val load: ApiKeyLoad by loadFlow.collectAsStateWithLifecycle(initialValue = ApiKeyLoad.Loading)
    var lastLoadedKey by rememberSaveable(key = "$name-key") { mutableStateOf<String?>(null) }
    var everLoaded by rememberSaveable(key = "$name-loaded") { mutableStateOf(false) }
    LaunchedEffect(load) {
        (load as? ApiKeyLoad.Loaded)?.let {
            lastLoadedKey = it.key
            everLoaded = true
        }
    }
    // Loaded(null) is no key: keep it null, don't fall back to the retained key.
    val value = when (val current = load) {
        is ApiKeyLoad.Loaded -> current.key
        ApiKeyLoad.Loading -> lastLoadedKey
    }
    return value to (load is ApiKeyLoad.Loaded || everLoaded)
}

class MainActivity : ComponentActivity() {
    // The nearby-stops lookup, shared by the near-me gate and a searched station's page (From…).
    // Reuses a recent lookup made close by (in memory, process-wide), so reopening the app near
    // where it was last used skips a request and a round trip.
    private val nearbyTflClient by lazy {
        KtorTflClient(
            httpClient,
            appKey = { UserApiKeySetting.current },
            rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
            requestPool = SharedTflRequestPool.pool,
        )
    }
    private val nearbyStopFinder by lazy { CachingStopFinder(nearbyTflClient, nearbyStopsCache(applicationContext)) }

    // A searched station's surroundings (From…) are looked up the same way but cached apart, in
    // memory only: they aren't places near the rider, so they mustn't show in the search as stops
    // the app has shown them nearby, or push the rider's own nearby lookups out of that cache.
    private val stationAreaStopFinder by lazy { CachingStopFinder(nearbyTflClient, stationAreaStopsCache) }

    // The location gate: resolves the nearby stops (an on-demand, location-sending action)
    // before the departures view, which then refreshes those stops location-free.
    private val nearbyViewModel: NearbyStopsViewModel by viewModels {
        viewModelFactory {
            initializer {
                NearbyStopsViewModel(
                    location = AndroidLocationProvider(
                        applicationContext,
                        warn = ::logLocationWarning,
                        position = ::recordPosition,
                    ),
                    // Reuses a recent lookup made close by (in memory, process-wide), so reopening
                    // the app near where it was last used skips a request and a round trip.
                    finder = nearbyStopFinder,
                    warn = ::logLocationWarning,
                    position = ::recordPosition,
                    // Waits for the stored set on a cold start, so the first pick already leaves out
                    // what the user hid rather than fetching it until the next re-locate.
                    hiddenModes = { HiddenModesSetting.loaded() },
                )
            }
        }
    }

    // The bundled branch topology, loaded off the main thread so the ~9 KB asset parse never
    // sits on the cold-start / first-frame path (SPEC principles 3–5). The initial value is the
    // process-wide cached instance if one is already parsed — free of IO, so after a rotation the
    // retained view models' departures render merged on the very first frame rather than
    // flickering through split rows while the async load re-runs — and RouteTopology.EMPTY (the
    // safe default: branches as TfL gives them, nothing merged) only on a true cold start, where
    // the async load below fills it the instant the asset is ready, well before the network
    // snapshot arrives. The widget loads the same cached instance in its own coroutine.
    private val routeTopology = mutableStateOf(RouteTopologyStore.cached())

    // Whether Google Play reports a newer version — drives the overflow "update available" dot
    // (SPEC *Update indicator*). Rechecked on each foreground ([onResume]); a background Play
    // Task, never on a render path. False on debug (checks are disabled there).
    private val updateAvailable = mutableStateOf(false)
    private val playUpdateChecker by lazy { PlayUpdateChecker(application, warn = ::logUpdateWarning) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Warm the chosen text size into memory (off the main thread) so the first frame is sized
        // from the user's setting rather than the default, then resized a beat later (SPEC *Display
        // size*). Idempotent and shares the process-singleton DataStore instance the settings
        // collector below uses.
        val appSettings = DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
        FontSizeSetting.warm(appSettings)
        // The user's TfL app_key holder ([UserApiKeySetting]) is warmed at process start in
        // StopdashApp (every process, so a widget-only process has it too), not here. The request
        // clients read the current key per request — a paste raises the budget on the next refresh
        // without rebuilding them (SPEC D7).
        lifecycleScope.launch {
            routeTopology.value = withContext(Dispatchers.IO) { RouteTopologyStore.load(applicationContext) }
        }
        setContent {
            StopDashAppRoot {
                val nearby by nearbyViewModel.state.collectAsStateWithLifecycle()

                // True once a request has come back denied with the rationale suppressed —
                // Android's "don't ask again" / permanently-denied signal. Then re-requesting
                // only re-denies, so the gate offers Settings instead (Codex). Survives
                // configuration change so a rotation doesn't drop back to the Allow button.
                var permissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    // The precise request has now been shown, whichever way it was answered —
                    // so an upgraded coarse-only user isn't prompted again on every open.
                    markPrecisePrompted()
                    // Request both so the runtime dialog offers the precise/approximate choice;
                    // either grant finds stops (precise preferred — see AndroidLocationProvider).
                    if (grants.values.any { it }) {
                        permissionPermanentlyDenied = false
                        nearbyViewModel.locate()
                    } else {
                        // A denial with no rationale allowed means the system won't prompt
                        // again — route the user to Settings rather than a dead re-request.
                        permissionPermanentlyDenied =
                            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                // Resolve when a location permission is held and nothing has resolved
                // yet: on open if already granted, and again on returning from Settings with a
                // fresh grant. Guarded on the still-unresolved PermissionRequired state so a
                // configuration change — which recreates the activity and re-runs this, while
                // the ViewModel and its resolved state survive — doesn't relocate over a
                // working (or in-flight) result and re-hit TfL (Codex).
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        if (nearbyViewModel.state.value is NearbyStopsViewModel.State.PermissionRequired) {
                            when (
                                nearbyPermissionAction(
                                    hasFine = hasFineLocation(),
                                    hasAnyLocation = hasLocationPermission(),
                                    precisePrompted = precisePrompted(),
                                )
                            ) {
                                NearbyPermissionAction.LOCATE -> nearbyViewModel.locate()
                                NearbyPermissionAction.REQUEST_PRECISE ->
                                    permissionLauncher.launch(locationPermissions)
                                NearbyPermissionAction.WAIT -> {}
                            }
                        }
                    }
                }

                // The retained latch for a pending foreground-return re-location, composed above the
                // overlay switch by [NearbyArea] below and consumed inside the departures view. See
                // [ForegroundReturnLatch] for why it's a ViewModel rather than a `remember`.
                val returnLatch: ForegroundReturnLatch = viewModel()

                // The licenses screen is hosted here, above the location gate — not inside the
                // departures view — so the open-source attribution (and the app version) stay
                // reachable in every state, including a permission-denied gate where departures
                // never resolve (Codex). It also means departures and their background refresh
                // leave composition while licenses is open, rather than polling TfL behind a
                // static screen. Saved so it survives rotation and process death; each screen's
                // own Back closes it.
                var licensesOpen by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                val openLicenses = { licensesOpen = true }
                // "Find a station" (SPEC *Finding stops*): the search, and the station opened from it
                // (its TfL id and name). Hosted as overlays like Settings, so the near-me departures
                // stop polling while they're up; back from a station returns to the search.
                var stationSearchOpen by rememberSaveable { mutableStateOf(false) }
                var openStationId by rememberSaveable { mutableStateOf<String?>(null) }
                var openStationName by rememberSaveable { mutableStateOf("") }
                // "To…" from an open station (SPEC *Finding stops → From… To…*): whether its
                // destination search is up, and the destination picked (id and name), which narrows
                // the station's page to the departures that go there.
                var tripPicking by rememberSaveable { mutableStateOf(false) }
                var tripToId by rememberSaveable { mutableStateOf<String?>(null) }
                var tripToName by rememberSaveable { mutableStateOf("") }
                // "To…" from the near-me list (SPEC *Finding stops → From… To…*): whether it's open,
                // whether its destination search is up, and the destination picked. Its starting
                // stops aren't kept: they're worked out from the current nearby set.
                var hereTripOpen by rememberSaveable { mutableStateOf(false) }
                var herePicking by rememberSaveable { mutableStateOf(false) }
                var hereToId by rememberSaveable { mutableStateOf<String?>(null) }
                var hereToName by rememberSaveable { mutableStateOf("") }
                // The list's retained departures model is dropped when the trip opens: while the trip
                // is up it re-locates for both, and a list model left behind would neither take those
                // re-picks (it'd hide rows by where the rider used to be) nor stop its own fetches (one
                // could save the old place's departures to the widget mid-fix). Back on the list, it
                // is rebuilt from the current set.
                val listStores: NearbyDeparturesStores = viewModel()
                val closeHereTrip = {
                    hereTripOpen = false
                    herePicking = false
                    hereToId = null
                    hereToName = ""
                }

                // App settings + the opt-in "live widget" refresh (SPEC D5). The setting is
                // collected here and applied to the scheduler at start — so an enabled toggle
                // resumes the ~1/min refresh chain after the process is recreated — and on every
                // change; the Settings screen itself stays UI-only (WorkManager is wired here).
                val settings = remember {
                    DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
                }
                // null until the first read lands (a slow or persistently-failing DataStore read
                // leaves the flow silent) — the Settings switch is disabled meanwhile so the user
                // can't act on an off value that may not reflect the stored choice (Codex P2 on #56).
                val liveWidgetRefresh: Boolean? by settings.liveWidgetRefresh()
                    .collectAsStateWithLifecycle(initialValue = null)
                val settingsScope = rememberCoroutineScope()
                // Whether the last apply of the live-widget setting failed to schedule, so the
                // Settings screen can surface it. The coordinator owns persist + schedule + the
                // error policy (see applyLiveWidgetRefresh); this is just its result.
                var liveWidgetRefreshFailed by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    liveWidgetRefreshFailed =
                        syncLiveWidgetRefreshSchedule(applicationContext, settings) ==
                            LiveWidgetRefreshResult.FAILED
                }

                // The bug-report consent gate. false-defaulted while the setting loads so a slow
                // read over-asks rather than sharing the location unprompted; "don't ask again"
                // (persisted) skips straight to the share sheet (SPEC *Privacy*).
                val skipBugReportConsent: Boolean by settings.skipBugReportConsent()
                    .collectAsStateWithLifecycle(initialValue = false)
                val telemetryOptIn: Boolean? by TelemetryConsent.state.collectAsStateWithLifecycle()
                val distanceUnits by DistanceUnitsSetting.changes.collectAsStateWithLifecycle()
                val distanceUnitsLoaded by DistanceUnitsSetting.isLoaded.collectAsStateWithLifecycle()
                val distanceUnitsWriteFailed by DistanceUnitsSetting.writeFailed.collectAsStateWithLifecycle()

                // The user's TfL app_key for the Settings field. Read from the store (the source of
                // truth), so an external change — a restore, or the warmed holder's own write —
                // reflects in the field; disabled until read ([rememberStoredKey]).
                val (userApiKeyValue, apiKeyLoaded) = rememberStoredKey(settings, "tfl") { it.userApiKey() }
                // The National Rail key, read the same way.
                val (railApiKeyValue, railApiKeyLoaded) = rememberStoredKey(settings, "rail") { it.railApiKey() }
                // Whether the consent dialog is open is held in a retained ViewModel, not the saved
                // bundle: it survives a configuration change (rotation) so the open dialog isn't
                // discarded with the Send (Codex P2 on #86), but resets on process death — where the
                // in-memory fix is gone anyway (never persisted, SPEC *Privacy*), so a restored
                // dialog would only build a location-unavailable report (Codex P2 on #86). The report
                // inputs are rebuilt from the current [nearby] state (also ViewModel-backed) at send.
                // The departures list's scroll position, held here — above the Settings/Licenses
                // overlays and the route page, which take the list out of composition — so returning
                // from any of them lands where the rider left off, and it survives a rotation too.
                // It follows the nearby set, as the departures themselves do: a different set of stops
                // starts at the top (see rememberListStateFor).
                val departuresListState = rememberListStateFor(
                    (nearby as? NearbyStopsViewModel.State.Ready)?.clusterSetKey,
                )
                // The Faraway favorites tap, held here for the same reason and following the same set.
                val farReveal = rememberFarReveal((nearby as? NearbyStopsViewModel.State.Ready)?.clusterSetKey)
                // The list's held loading cards ([PendingTracker]), held here too so an overlay over
                // the list doesn't drop them; following the same set.
                val departuresTracker = rememberPendingTracker(
                    (nearby as? NearbyStopsViewModel.State.Ready)?.clusterSetKey,
                )
                val bugReportConsent: BugReportConsentViewModel = viewModel()
                val requestBugReport = {
                    if (skipBugReportConsent) shareBugReport(bugReportRequestFor(nearby))
                    else bugReportConsent.open = true
                }

                // Licenses and Settings are activity-level overlays (like the licenses screen's
                // existing hosting), reachable from every state and closed by their own Back, so
                // opening either takes the departures view and its background refresh out of
                // composition rather than polling TfL behind a static screen. [NearbyArea] hosts the
                // switch and composes the foreground-return observer in its aboveOverlay slot — above
                // the switch — so a return that lands while an overlay is open is still seen (#136).
                // The app's overflow actions, for a screen several layers down (a trip) to offer too.
                CompositionLocalProvider(
                    LocalAppMenu provides AppMenuActions(
                        updateAvailable = updateAvailable.value,
                        onOpenAppListing = ::openPlayListing,
                        onSendBugReport = requestBugReport,
                        onOpenLicenses = openLicenses,
                    ),
                ) {
                    NearbyArea(
                        overlayOpen = licensesOpen || settingsOpen || stationSearchOpen || openStationId != null,
                        aboveOverlay = {
                            ForegroundReturnLatcher(
                                isReady = { nearbyViewModel.state.value is NearbyStopsViewModel.State.Ready },
                                isBusy = { nearbyViewModel.relocating.value },
                                onReturn = { returnLatch.pending = true },
                            )
                        },
                        overlayContent = {
                            // Licenses wins if both are somehow set; each closes via its own Back.
                            if (licensesOpen) {
                                LicensesScreen(onBack = { licensesOpen = false })
                            } else if (!settingsOpen) {
                                StationSearchArea(
                                    stationId = openStationId,
                                    stationName = openStationName,
                                    onOpenStation = { match ->
                                        openStationId = match.id
                                        openStationName = match.name
                                    },
                                    // The name goes with the id, so nothing about a station looked at is
                                    // kept in the saved state once it's closed.
                                    onCloseStation = {
                                        openStationId = null
                                        openStationName = ""
                                        tripPicking = false
                                        tripToId = null
                                        tripToName = ""
                                    },
                                    onCloseSearch = {
                                        stationSearchOpen = false
                                        openStationId = null
                                        openStationName = ""
                                        tripPicking = false
                                        tripToId = null
                                        tripToName = ""
                                    },
                                    tripPicking = tripPicking,
                                    tripToId = tripToId,
                                    tripToName = tripToName,
                                    onPlanTo = { tripPicking = true },
                                    onPickTo = { match ->
                                        tripPicking = false
                                        tripToId = match.id
                                        tripToName = match.name
                                    },
                                    onClosePicker = { tripPicking = false },
                                    onClearTo = {
                                        tripToId = null
                                        tripToName = ""
                                    },
                                )
                            } else {
                                SettingsScreen(
                                    liveWidgetRefresh = liveWidgetRefresh == true,
                                    liveWidgetRefreshEnabled = liveWidgetRefresh != null,
                                    liveWidgetRefreshFailed = liveWidgetRefreshFailed,
                                    onLiveWidgetRefreshChange = { enabled ->
                                        settingsScope.launch {
                                            liveWidgetRefreshFailed =
                                                applyLiveWidgetRefresh(applicationContext, settings, enabled) ==
                                                    LiveWidgetRefreshResult.FAILED
                                        }
                                    },
                                    onDismissLiveWidgetRefreshError = { liveWidgetRefreshFailed = false },
                                    // The paste field. Applied to memory at once (next refresh uses it) and
                                    // persisted in the background; a blank clears it back to keyless (SPEC D7).
                                    // Disabled until the stored key has actually been read, so the field can't be
                                    // edited over a value that hasn't loaded yet.
                                    userApiKey = userApiKeyValue.orEmpty(),
                                    userApiKeyLoaded = apiKeyLoaded,
                                    onUserApiKeyChange = { key -> UserApiKeySetting.set(key) },
                                    // The National Rail key, handled the same way (SPEC *National Rail*).
                                    railApiKey = railApiKeyValue.orEmpty(),
                                    railApiKeyLoaded = railApiKeyLoaded,
                                    onRailApiKeyChange = { key -> RailApiKeySetting.set(key) },
                                    // Crash reports and usage stats, off until the user opts in here
                                    // (SPEC *Privacy*); the holder applies it at once, the gate follows.
                                    telemetryOptIn = telemetryOptIn,
                                    onTelemetryOptInChange = TelemetryConsent::set,
                                    // Applied in memory at once (the list re-labels), persisted in order.
                                    distanceUnits = distanceUnits,
                                    onDistanceUnitsChange = DistanceUnitsSetting::set,
                                    distanceUnitsLoaded = distanceUnitsLoaded,
                                    distanceUnitsWriteFailed = distanceUnitsWriteFailed,
                                    onDismissDistanceUnitsError = DistanceUnitsSetting::writeFailureShown,
                                    onBack = { settingsOpen = false },
                                )
                            }
                        },
                        body = {
                            // The precise-fix follow-up is foreground-only: it stops when the nearby
                            // surface leaves (an overlay replaces it) or the app goes to the background,
                            // and asks again on return if the outcome is still flagged coarse.
                            LifecycleStartEffect(Unit) {
                                nearbyViewModel.resumeRefining()
                                onStopOrDispose { nearbyViewModel.pauseRefining() }
                            }
                            when (val state = nearby) {
                                // "To…" from the near-me list takes the list's place while it's open,
                                // inside the nearby lifecycle: the location gate, its errors and a
                                // re-locate on return apply to it as they do to the list.
                                is NearbyStopsViewModel.State.Ready -> if (hereTripOpen) {
                                    val hidden by HiddenModesSetting.changes.collectAsStateWithLifecycle()
                                    // A precise fix that moves the set moves the trip too, as its own
                                    // re-locate does: the origins are worked out from the set shown.
                                    val refinementNow by nearbyViewModel.refinement.collectAsStateWithLifecycle()
                                    LaunchedEffect(refinementNow?.id) {
                                        refinementNow?.let { nearbyViewModel.applyRefinement(it) }
                                    }
                                    HereTripArea(
                                        // Worked out from the current set, so a re-locate moves the
                                        // trip with the rider; none left (all hidden) ends it (below).
                                        origin = remember(state, hidden) {
                                            val byId = state.nearbyStops.associateBy { it.id }
                                            hereOriginIds(state.eagerStops, state.nearbyStops, state.distanceMeters, hidden)
                                                .mapNotNull { byId[it] }
                                        },
                                        // The Planner starts from the nearest stop of any mode, hidden or
                                        // not (it walks on to a better one); hidden modes filter its routes.
                                        anchors = state.nearbyStops,
                                        distanceMeters = state.distanceMeters,
                                        clusters = state.eager + state.more,
                                        hiddenModes = hidden,
                                        picking = herePicking,
                                        toId = hereToId,
                                        toName = hereToName,
                                        onPlanTo = { herePicking = true },
                                        onPickTo = { match ->
                                            herePicking = false
                                            hereToId = match.id
                                            hereToName = match.name
                                        },
                                        // Back from the search returns to the trip, or to the list when
                                        // no destination was picked yet.
                                        onClosePicker = { if (hereToId == null) closeHereTrip() else herePicking = false },
                                        onClose = closeHereTrip,
                                        // A return to the foreground re-locates first, as the list does,
                                        // then refreshes the trip if the rider is still near its stops.
                                        foregroundReturnPending = returnLatch.pending,
                                        onForegroundReturnConsumed = { returnLatch.pending = false },
                                        isRelocating = { nearbyViewModel.relocating.value },
                                        relocate = { nearbyViewModel.relocate() },
                                        // "Show all" re-picks the set from the same fix, as the list's does,
                                        // so a hidden mode's stops can become origins again.
                                        showAllModes = {
                                            HiddenModesSetting.showAll()
                                            nearbyViewModel.refilter()
                                        },
                                        relocating = nearbyViewModel.relocating,
                                        repicked = nearbyViewModel.repicked,
                                        locationBanner = nearbyViewModel.locationBanner,
                                    )
                                } else {
                                    DeparturesForStops(
                                        ready = state,
                                        relocate = { onSameSet -> nearbyViewModel.relocate(onSameSet) },
                                        relocating = nearbyViewModel.relocating,
                                        locationBanner = nearbyViewModel.locationBanner,
                                        refinement = nearbyViewModel.refinement,
                                        applyRefinement = nearbyViewModel::applyRefinement,
                                        onOpenLicenses = openLicenses,
                                        onOpenSettings = { settingsOpen = true },
                                        onFindStation = { stationSearchOpen = true },
                                        onPlanTo = {
                                            listStores.clearAll()
                                            hereTripOpen = true
                                            herePicking = true
                                        },
                                        updateAvailable = updateAvailable.value,
                                        onOpenAppListing = ::openPlayListing,
                                        onSendBugReport = requestBugReport,
                                        // A foreground return that landed while an overlay was open is latched
                                        // above; consume it here so re-entering departures relocates.
                                        foregroundReturnPending = returnLatch.pending,
                                        onForegroundReturnConsumed = { returnLatch.pending = false },
                                        listState = departuresListState,
                                        farReveal = farReveal,
                                        pendingTracker = departuresTracker,
                                    )
                                }
                                else -> {
                                    // While the gate is up (a failed/empty relocate, or a retry), drop
                                    // any departures store retained from the pre-gate set, so recovering
                                    // to the same stop IDs rebuilds the ViewModel and re-fetches instead
                                    // of showing the pre-gate departures until the next auto-refresh
                                    // (Codex). Ready never enters this branch, so a same-set relocate is
                                    // untouched. Runs once on gate entry (keyed Unit).
                                    val stores: NearbyDeparturesStores = viewModel()
                                    // The from-here trip's origins too, for the same reason (Codex).
                                    val hereTripStores: NearbyDeparturesStores = viewModel(key = "here-trip-stores")
                                    DisposableEffect(Unit) {
                                        stores.clearAll()
                                        hereTripStores.clearAll()
                                        onDispose {}
                                    }
                                    // "No stops nearby" from a coarse fix: a precise fix that lands
                                    // elsewhere looks again from there (nothing is fetched to cancel).
                                    val gateRefinement by nearbyViewModel.refinement.collectAsStateWithLifecycle()
                                    LaunchedEffect(gateRefinement?.id) {
                                        gateRefinement?.let { nearbyViewModel.applyRefinement(it) }
                                    }
                                    val gateBanner by nearbyViewModel.locationBanner.collectAsStateWithLifecycle()
                                    LocationGate(
                                        state = state,
                                        approximate = gateBanner == LocationBanner.COARSE,
                                        permanentlyDenied = permissionPermanentlyDenied,
                                        onAllow = { permissionLauncher.launch(locationPermissions) },
                                        onRetry = {
                                            if (hasLocationPermission()) nearbyViewModel.locate()
                                            else permissionLauncher.launch(locationPermissions)
                                        },
                                        onOpenSettings = ::openAppSettings,
                                        onOpenLicenses = openLicenses,
                                        // The report is most useful in exactly these stuck states (no fix,
                                        // TfL unreachable, nothing nearby), so it is reachable here too, not
                                        // only past the gate — with no location or stops (Codex P2 on #86).
                                        onSendBugReport = requestBugReport,
                                        // The gate is in front of the departures overflow (which carries the
                                        // update item), so surface an available update on the Locating spinner.
                                        updateAvailable = updateAvailable.value,
                                        onOpenAppListing = ::openPlayListing,
                                        // The station search needs no location, so it's offered here too:
                                        // most useful to exactly the users who can't use near me.
                                        onFindStation = { stationSearchOpen = true },
                                    )
                                }
                            }
                        },
                    )
                }

                // The consent gate overlays whatever is shown; requested from the Ready overflow or
                // a stuck gate. Confirm builds the report from the current state and shares it
                // (persisting the opt-out if ticked); cancel just closes. Nothing is assembled until
                // the user confirms here.
                if (bugReportConsent.open) {
                    BugReportConsentDialog(
                        onConfirm = { dontAskAgain ->
                            bugReportConsent.open = false
                            // Persist the opt-out on the application scope, not settingsScope: this
                            // Activity can be recreated the instant Continue is tapped (config
                            // change), which would cancel a settingsScope write and silently lose
                            // the "don't ask again" choice — the same reason shareBugReport uses it.
                            if (dontAskAgain) {
                                val optOutScope =
                                    (application as? StopdashApp)?.applicationScope ?: settingsScope
                                optOutScope.launch { persistBugReportOptOut(settings) }
                            }
                            shareBugReport(bugReportRequestFor(nearby))
                        },
                        onDismiss = { bugReportConsent.open = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every foreground (covers first launch, since onResume follows onCreate,
        // and a return from the Play listing or the background). Cheap and off the main thread.
        playUpdateChecker.checkForUpdate { available -> updateAvailable.value = available }
    }

    /**
     * Opens this app's Google Play listing so the user can update, tried Play-app-first then the
     * web listing. Reached only from the release build's overflow "update available" item, where
     * [packageName] is the real `app.stopdash` id (debug disables the check). If neither opens
     * (no Play app and no browser — rare, since the item only shows once Play's own check reported
     * an update), the tap would otherwise do nothing, so it shows a toast and logs rather than
     * failing silently (SPEC principle 2), mirroring the license-link flow in [LicensesScreen].
     */
    private fun openPlayListing() {
        for (uri in PLAY_LISTING_URIS) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("$uri$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // No handler for this target (e.g. no Play app for market://). Log the miss —
                // sanitized, the exception class only — so a browser-only fallback is
                // diagnosable, then try the next URI (a later success returns before the
                // toast + summary below). The scheme is a fixed constant, not user data.
                logUpdateWarning("Play listing target unavailable: ${e.javaClass.simpleName}")
            }
        }
        Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        logUpdateWarning("No app to open the Play listing")
    }

    /**
     * Shows a stop in the user's maps app, a labeled pin at TfL's published stop position (never
     * the user's fix). With no app to handle `geo:` the tap would otherwise do nothing, so it shows
     * a toast and logs rather than failing silently (SPEC principle 2); the log carries no coordinate.
     */
    private fun openStopMap(latitude: Double, longitude: Double, name: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StopMap.geoUri(latitude, longitude, name))))
        } catch (e: android.content.ActivityNotFoundException) {
            logLocationWarning("no maps app to show a stop: ${e.javaClass.simpleName}")
            Toast.makeText(this, R.string.map_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Assembles and shares the consent-gated bug report — the diagnostic log plus the **exact
     * location** and per-stop distances the [request] captured. Reached only after
     * [BugReportConsentDialog] (or the persisted "don't ask again"): this is the sanctioned
     * exception to the on-device-only log rule, so it runs only past that gate (SPEC *Privacy*).
     *
     * The shared `mikelward/androidlog` `DebugReport` does the mechanism: [DebugReport.collect]
     * reads (and, once shared, consumes) the persisted earlier runs off the main thread, wrapping
     * this app's section; [DebugReport.deliver] copies to the clipboard, attaches the screenshot,
     * and opens the share sheet on the main thread. The shared androidlog [ReportScreenshot.capture]
     * takes the shot first — of this Activity's window, which excludes the consent dialog's separate
     * window, so it is the screen being reported, not the dialog over it; a failed capture is a
     * text-only report, never a dropped share. A `COPIED_ONLY`/`FAILED` outcome is surfaced, not swallowed
     * (SPEC principle 2).
     */
    private fun shareBugReport(request: BugReportRequest) {
        val app = application as? StopdashApp
        // Null in a test Application (or if setup failed) — the report then carries no earlier
        // runs, which is exactly what a null sink means to collect().
        val sink = app?.diagnosticSink
        // Run on the application scope with the application context, not lifecycleScope + the
        // Activity: collect reads the persisted log (up to ~10 s) and deliver opens the share
        // sheet, so a rotation mid-collect must not cancel the coroutine and drop the share with
        // no sheet or toast (SPEC principle 2; Codex P2 on #86). The chooser is launched with
        // FLAG_ACTIVITY_NEW_TASK by DebugReport, so the app context is fine. Falls back to the
        // Activity scope only in a test Application that isn't StopdashApp.
        val scope = app?.applicationScope ?: lifecycleScope
        val context = applicationContext
        // Held only until the capture returns (early in the coroutine, before the ~10 s collect);
        // capture guards a finished window and yields null rather than touching a stale one.
        val activity = this
        scope.launch {
            // The shared androidlog ReportScreenshot captures the PNG off the main thread and
            // returns the file; this app mints the FileProvider URI from it — the provider and its
            // authority are the app's (see the manifest and @xml/file_paths). A null capture is a
            // text-only report, never a dropped share.
            val screenshot = withContext(Dispatchers.IO) {
                ReportScreenshot.capture(activity, File(context.cacheDir, "bug-reports"), StopdashDebugLog)
                    ?.let { file ->
                        bugReportScreenshotUri(file, StopdashDebugLog) {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                        }
                    }
            }
            val report = withContext(Dispatchers.IO) {
                DebugReport.collect(StopdashDebugLog, sink) {
                    BugReport.compose(
                        header = bugReportHeader(),
                        location = request.location,
                        stops = request.stops.map {
                            BugReport.StopLine(it.name, it.id, request.distanceMeters[it.id])
                        },
                        // This run's buffer, rendered in full (DEVICE fidelity) — the report is
                        // consent-gated, so it is not the redacted, location-safe export.
                        logLines = StopdashDebugLog.snapshot(),
                        recentPositions = recentPositions.recent(SystemClock.elapsedRealtime()),
                    )
                }
            }
            val outcome = DebugReport.deliver(
                context = context,
                log = StopdashDebugLog,
                report = report,
                subject = context.getString(R.string.bug_report_subject),
                chooserTitle = context.getString(R.string.bug_report_chooser_title),
                clipboardLabel = context.getString(R.string.bug_report_clipboard_label),
                screenshot = screenshot,
            )
            when (outcome) {
                ShareOutcome.SHARED -> {}
                ShareOutcome.COPIED_ONLY ->
                    Toast.makeText(context, R.string.bug_report_copied, Toast.LENGTH_LONG).show()
                ShareOutcome.FAILED ->
                    Toast.makeText(context, R.string.bug_report_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * The departures view for a resolved nearby set. The [MainViewModel] is created here —
     * not as an activity field — because its watched stops aren't known until location
     * resolves; each nearby set gets its own instance, scoped to a per-set store owner that
     * clears the previous one (cancelling its in-flight fetch) when the set changes, rather
     * than reusing a stale one or accumulating them.
     *
     * The [WidgetSnapshotStore] here is save-only: it writes each authoritative snapshot to
     * the file the widget reads (and pokes the widget to re-render) but its `load` returns
     * null, so the in-app view does **not** restore it. That asymmetry is deliberate — the
     * watched set here is derived from location and changes as the user moves, so restoring
     * it in-app would show a previous location's departures under the newly-resolved stops,
     * and cards omit the stop name so those rows would look like the new stops' (Codex).
     * The widget wants the same last-good the app just fetched, so it gets it; the in-app
     * view still resolves fresh each open. Proper per-set persistence (and in-app offline
     * last-good) returns with Phase 2's user-chosen watched stops.
     */
    @Composable
    private fun DeparturesForStops(
        ready: NearbyStopsViewModel.State.Ready,
        // A refresh (button or pull) re-resolves the nearby set (a fresh fix) as well as
        // re-fetching departures — so walking to the next stop and refreshing updates both.
        // Called with a reconcile action, which relocate runs only when the fix confirms the
        // *same* nearby set (a moved-to set gets a fresh ViewModel that fetches on init; a
        // failed/empty relocation shows the honest gate) — so a refresh never re-fetches the
        // previous location's stops in parallel with the fix (see relocate). The fresh
        // [NearbyStopsViewModel.State.Ready] is handed back so the retained ViewModel can
        // reconcile both tiers in place and keep a revealed expansion.
        relocate: (onSameSet: (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit,
        // True while a relocate's fresh fix is in flight (the departures screen stays up). ORed
        // into the refresh indicator so pull-to-refresh doesn't retract the instant the fetch
        // is enqueued, leaving the fix to change the set under a screen that reads as settled.
        relocating: StateFlow<Boolean>,
        // Why the shown nearby set's location is low-confidence, or null — drives the top banner
        // over the list (SPEC *Finding stops*). From the gate's NearbyStopsViewModel, which owns
        // the fix and its confidence.
        locationBanner: StateFlow<LocationBanner?>,
        onOpenLicenses: () -> Unit,
        onOpenSettings: () -> Unit,
        onFindStation: () -> Unit,
        // "To…" from the near-me list (SPEC *Finding stops → From… To…*).
        onPlanTo: () -> Unit,
        // Play reports a newer version — the overflow gets its red dot and "Update available"
        // item. Threaded from the activity's [updateAvailable] state, refreshed on each resume.
        updateAvailable: Boolean,
        onOpenAppListing: () -> Unit,
        // Overflow "Send bug report": built at the Ready branch so it captures the fix + distances.
        onSendBugReport: () -> Unit,
        // A background→foreground return, latched by the activity-level observer above the overlay
        // switch (so a return while Settings/Licenses is open isn't lost). True means "re-locate on
        // (re)entry"; [onForegroundReturnConsumed] clears it once acted on.
        foregroundReturnPending: Boolean,
        onForegroundReturnConsumed: () -> Unit,
        // The departures list's scroll position, hoisted by the caller so it survives the overlays.
        listState: LazyListState = rememberLazyListState(),
        farReveal: FarRevealState? = null,
        // The held loading cards, hoisted with [listState] for the same reason; null keeps them in
        // the screen.
        pendingTracker: PendingTracker? = null,
        // The crosshairs, where it doesn't re-locate here: a From… station page's return to near me.
        onLocate: (() -> Unit)? = null,
        // A searched station's page (From…) is this same list around the station: its own retained
        // models ([storesKey]), never the widget's list ([forWidget] false), titled with the station
        // and closed by back ([stationTitle], [onCloseStation]).
        storesKey: String? = null,
        // Re-picks the set from the same place after the hidden modes change ("Show all", a mode
        // shown again): the near-me gate's by default, a station's own for its page.
        refilter: (onSameSet: (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit = { onSameSet ->
            nearbyViewModel.refilter(onSameSet)
        },
        forWidget: Boolean = true,
        stationTitle: String? = null,
        onCloseStation: () -> Unit = {},
        // A precise fix that arrived after the set was shown from a coarse one and would move it
        // (SPEC *Finding stops*), and how to apply it: the same cancel-then-re-pick a refresh runs.
        refinement: StateFlow<NearbyStopsViewModel.Refinement?> = MutableStateFlow(null),
        applyRefinement: (NearbyStopsViewModel.Refinement, (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit =
            { _, _ -> },
    ) {
        // Each nearby set gets its own MainViewModel, and the previous one is CLEARED when
        // the set changes (the user moved and re-located) rather than left keyed in the
        // activity's store: relocating repeatedly would otherwise pile up view models and
        // leave an old location's in-flight fetch running after its screen is gone (Codex).
        //
        // The per-set ViewModelStore lives in [NearbyDeparturesStores], an activity-scoped
        // holder that survives configuration changes — so a rotation reuses the same store and
        // its MainViewModel (no reload, no duplicate fetch) — while [NearbyDeparturesStores.
        // ownerFor] clears every *other* set's store, cancelling a moved-away set's in-flight
        // fetch. A plain `remember`-created owner did neither: it was recreated on every
        // configuration change, forcing a reload and a fresh TfL fetch on each rotation (Codex).
        // Capture the application context once so callbacks stored on the retained ViewModel
        // (onStarsChanged below) close over it rather than over this Activity. The ViewModel
        // survives configuration changes, so a lambda that resolved `applicationContext` on the
        // Activity would keep the destroyed Activity reachable until the ViewModel is cleared.
        val appContext = applicationContext
        // Key the retained per-set ViewModel on the WHOLE nearby cluster set (order-independent),
        // not the eager stop ids: a relocation that only reorders the clusters, or shifts one across
        // the eager/more boundary while all stay in range, keeps the same key and so the same
        // ViewModel — preserving a revealed "More" expansion, which a rebuild would drop.
        val stopsKey = remember(ready) { ready.clusterSetKey }
        val stores: NearbyDeparturesStores = if (storesKey == null) viewModel() else viewModel(key = storesKey)
        val storeOwner = remember(stores, stopsKey) { stores.ownerFor(stopsKey, this@MainActivity) }
        // Shared with a searched station's page, so a write failure there surfaces here too.
        val writeFailures = viewModel<WriteFailuresHolder>().failures
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val viewModel: MainViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MainViewModel(
                            client = departuresClient(appContext),
                            departureSourceChanges = RailApiKeySetting.changes,
                            seedStops = ready.eagerStops,
                            initialMore = ready.more,
                            // Save-only snapshot store: the app writes each fresh snapshot for
                            // the widget to render, but this nearby set is not restored in-app
                            // (its load() returns null) — a previous location's stops must not
                            // resurface under a newly-resolved set.
                            snapshotStore = if (forWidget) WidgetSnapshotStore(applicationContext) else SnapshotStore.NONE,
                            // A station's list isn't the widget's: the near-me model keeps the journey pins.
                            ownsWidgetJourneys = forWidget,
                            // Starring is persisted per row across every nearby set (it's keyed
                            // by row identity, not tied to this stop set), so the store is the
                            // shared process-wide one, not scoped to this ViewModel's key.
                            starredStore = DataStoreStarredRowsStore.from(appContext, warn = ::logStarWarning),
                            // Dismissed alerts are persisted per place across every nearby set, so
                            // the store is the shared process-wide one too.
                            dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
                            warn = ::logDepartureWarning,
                            // Re-render the widget when a star changes (its pinned order — SPEC
                            // D8) or after a refresh that didn't save, so its age/staleness stays
                            // current rather than frozen at the last save (SPEC D4).
                            redrawWidget = { StopDashWidget().updateAll(appContext) },
                            // A quick retry after a rate-limited refresh refetches only the
                            // stops still missing, and a stop's closure check is reused for a
                            // few minutes — both spare TfL's keyless rate budget.
                            arrivalsReuse = ARRIVALS_REUSE,
                            sharedArrivals = ArrivalsCache.SHARED,
                            disruptionReuse = DISRUPTION_REUSE,
                            lineStatusReuse = LINE_STATUS_REUSE,
                            // Stops past the walking reach refresh every other minute on the timer.
                            stopDistanceMeters = ready.distanceMeters,
                            farArrivalsReuse = FAR_ARRIVALS_REUSE,
                            // Feeds the per-fetch debug-log line: time spent rate-limited.
                            rateWaitMillis = { SharedTflRateLimiter.waitedMillis },
                            logStats = ::logDepartureWarning,
                            writeFailures = writeFailures,
                            onStarToggled = { row -> rememberStarredPlace(appContext, row) },
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val journeyDestinationStops by viewModel.journeyDestinationStops.collectAsStateWithLifecycle()
            val journeyDestinationsUnknown by viewModel.journeyDestinationsUnknown.collectAsStateWithLifecycle()
            val departuresRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()
            // A relocate holds the indicator on for the whole fresh fix, not just the departures
            // fetch that follows a same-set confirmation.
            val relocatingNow by relocating.collectAsStateWithLifecycle()
            val refreshing = departuresRefreshing || relocatingNow
            val locationBannerNow by locationBanner.collectAsStateWithLifecycle()
            val hiddenModes by HiddenModesSetting.changes.collectAsStateWithLifecycle()
            // The nearest station of each rail line nothing nearby reaches, from the
            // bundled index (read off the main thread, once per process): no request.
            // Null until the picks are worked out (the bundled list loads off the main thread), so a
            // recreated screen (a rotation, a return from Settings) doesn't read "nothing offered" and
            // close the cards the retained list has open.
            val shownNearStops by viewModel.shownNearStops.collectAsStateWithLifecycle()
            // The shown stops the loaded list has departures for (null while still loading): one whose
            // fetch failed isn't on the list, so its lines keep their farther cards.
            val loadedStopIds = reachedStopIds(state)
            val reachedStops = remember(shownNearStops, loadedStopIds) { fartherReached(shownNearStops, loadedStopIds) }
            // Every list offers them, near me and a From… station's page alike (SPEC *Finding stops
            // → Farther stations*): the cards are how farther stations and bus stops page in.
            val farther by produceState<List<CollapsedPlaces.Place>?>(null, ready, hiddenModes, reachedStops) {
                // Each shown nearby stop's lines, and its ids: its index record says which route
                // ends its services reach.
                val reached = reachedStops
                // The candidate bus places come from the nearby lookup's farther tier, already in
                // memory; the screen narrows them to the cards to show, against the rows it draws. Each
                // place records the eager stations it stands by; one the screen shows wins it a tie.
                val stationStops = FartherBuses.stationStops(ready.eager, hiddenModes)
                val buses = FartherBuses.candidates(ready.more, hiddenModes, stationStops).map { CollapsedPlaces.of(it) }
                value = withContext(Dispatchers.IO) {
                    val index = StationIndexStore.load(appContext)
                    val stations = FartherStations.pick(index.stations, ready.location, reached, hiddenModes)
                        .map { CollapsedPlaces.of(it, index.lineNames) }
                    CollapsedPlaces.ordered(stations, buses)
                }
            }
            val hiddenModesWriteFailed by HiddenModesSetting.writeFailed.collectAsStateWithLifecycle()
            val starred by viewModel.starred.collectAsStateWithLifecycle()
            // Starred journeys (SPEC *Journeys*): read from the device, each turned so its origin is
            // the end nearer this fix, or flipped by a tap on its card. The shown origins are fetched
            // alongside the near-me stops.
            val journeyStore = remember { DataStoreStarredJourneysStore.from(appContext, warn = ::logStarWarning) }
            // Null until the first read arrives (and when unreadable), so journey starring stays off
            // rather than showing a saved journey as unstarred and letting a tap remove it.
            // Wrapped so "not read yet" (null) stays distinct from "read, but unreadable" (a read of
            // null): a journey view restored across a rotation waits out the first, not the second.
            val journeysRead by remember(journeyStore) { journeyStore.journeys().map { JourneysRead(it) } }
                .collectAsStateWithLifecycle(initialValue = null)
            val savedJourneys = journeysRead?.journeys
            var flippedJourneys by rememberSaveable { mutableStateOf(emptyList<String>()) }
            val shownJourneys = remember(savedJourneys, ready.location, flippedJourneys) {
                savedJourneys.orEmpty().map { journey ->
                    val oriented = Journeys.oriented(journey, ready.location.latitude, ready.location.longitude)
                    if (journey.key in flippedJourneys) oriented.reversed() else oriented
                }
            }
            // Journeys more than a mile from both ends wait behind the Faraway favorites button,
            // unfetched (SPEC *Journeys*). Only a confirmed fix holds one back: without a fix, or on
            // an approximate or unrefreshed one (a banner is up), every journey shows in full.
            val farJourneyMeters = remember(shownJourneys, ready.location, locationBannerNow) {
                Journeys.farJourneys(
                    shownJourneys,
                    ready.location.latitude,
                    ready.location.longitude,
                    fixConfirmed = locationBannerNow == null,
                )
            }
            val journeyScope = rememberCoroutineScope()
            var journeyWriteFailed by rememberSaveable { mutableStateOf(false) }
            // The route page's journey tip: hidden (true) until the setting is read, so it never
            // flashes up for someone who already dismissed it.
            val tipSettings = remember { DataStoreAppSettings.from(appContext, warn = ::logAppSettingsWarning) }
            val journeyTipStored by remember(tipSettings) { tipSettings.journeyTipDismissed() }
                .collectAsStateWithLifecycle(initialValue = true)
            // Closed for this session at once on "Got it", whether or not the save lands (a failed
            // one is logged, and the tip returns next launch). Held for the process, not this
            // composition, which Settings or Licenses replaces.
            val journeyTipDismissed = journeyTipStored || JourneyTipSession.closed
            val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
            val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
            val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
            val dismissWriteFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
            // Each farther station's collapsed card and where it stands; a relocation keeps the ones
            // still offered open, measured from the new fix.
            // An opened card has its own departures model, as a From… page's station does, kept in
            // this nearby set's store: it never joins the list's fetched set or the widget, and
            // nothing about it is saved (SPEC *Finding stops → Farther stations*).
            val fartherModels: FartherCardsViewModel = viewModel(
                key = fartherCardsKey(storesKey),
                factory = viewModelFactory {
                    initializer {
                        FartherCardsViewModel(
                            stationStops = stationFinder::stationStops,
                            newModel = { stops, distances ->
                                fartherCardModel(departuresClient(appContext), appContext, stops, distances, writeFailures)
                            },
                            warn = ::logDepartureWarning,
                        )
                    }
                },
            )
            val fartherLoads by fartherModels.cards.collectAsStateWithLifecycle()
            LaunchedEffect(farther, ready.location) { farther?.let { fartherModels.retain(it, ready.location) } }
            val fartherCards = remember(farther, fartherLoads) { farther.orEmpty().map { FartherCard(it, fartherLoads[it.key]) } }
            val fartherDistanceMeters = remember(fartherLoads) {
                fartherLoads.values.filterIsInstance<FartherLoad.Open>().fold(emptyMap<String, Double>()) { acc, open -> acc + open.distanceMeters }
            }
            // Each open card's departures, kept live while shown as the list's are, then shown
            // through the list's own rows beside its card.
            val openedStates = fartherLoads.entries.mapNotNull { (cardKey, load) ->
                val open = load as? FartherLoad.Open ?: return@mapNotNull null
                val model = fartherModels.model(cardKey) ?: return@mapNotNull null
                key(cardKey) {
                    AutoRefresh(model, relocating)
                    open.distanceMeters.keys to model.state.collectAsStateWithLifecycle().value
                }
            }
            val shownState = (state as? DeparturesUiState.Loaded)?.let { withOpenedFarther(it, openedStates) } ?: state
            // These background refreshes are composed only while the departures view is shown:
            // the licenses screen is hosted above this subtree (see onCreate), so opening it
            // removes DeparturesForStops from composition and stops the polling (Codex).
            // Refresh = re-locate (a fresh fix, re-resolving the nearby set) then re-fetch the
            // confirmed same set; a moved-to set's new ViewModel fetches on its own init. Used by
            // the refresh control AND by a return to the foreground below, so walking away and back
            // moves the nearby set to where you are now (maintainer, 2026-09-23) — the auto-refresh
            // timer stays departures-only, it doesn't relocate. The cancel-then-relocate-then-
            // reconcile composition is factored into [relocateAction] so a regression back to a
            // departures-only refresh is caught by a unit test.
            val shownFarReveal = farReveal ?: rememberFarReveal(stopsKey)
            val shownTracker = pendingTracker ?: rememberSaveable(stopsKey, saver = PendingTracker.Saver) { PendingTracker() }
            // Each shown journey's fetched stops, as the screen last reported them; read by a relocate.
            val journeyStopIds = remember { mutableStateOf(emptyMap<String, Set<String>>()) }
            val openJourneyKey = remember { mutableStateOf<String?>(null) }
            // Brings the retained departures in line with a re-picked nearby set of the same places:
            // updates its tiers and distances and re-fetches (a relocation, or "Show all").
            val reconcileSameSet: (NearbyStopsViewModel.State.Ready) -> Unit = { fresh ->
                    // A journey the fresh fix puts over a mile away is held back from this refresh
                    // too, not just from the next one once the screen catches up (Codex). Relocate
                    // sets the banner before calling here, so a retained or last-known fix (banner
                    // up) holds nothing back, as on the screen.
                    val fixConfirmed = locationBanner.value == null
                    val drop = Journeys.stopIdsToHoldBack(
                        savedJourneys.orEmpty(),
                        fresh.location.latitude,
                        fresh.location.longitude,
                        fixConfirmed = fixConfirmed,
                        revealed = shownFarReveal.revealed,
                        stopIdsByJourney = journeyStopIds.value,
                        openJourneyKey = openJourneyKey.value,
                    )
                    // One the fix releases (back in range, or an unconfirmed fix that holds nothing
                    // back) waits for the screen to report its stops, so the refresh runs once with
                    // them (its new card always changes that report). One the screen already shows
                    // (revealed, or its own view open) isn't waited on.
                    val await = Journeys.releasesHeldJourney(
                        savedJourneys.orEmpty(),
                        fresh.location.latitude,
                        fresh.location.longitude,
                        fixConfirmed = fixConfirmed,
                        heldNow = farJourneyMeters.keys - journeyStopIds.value.keys,
                    )
                    viewModel.reconcile(
                        fresh.eager,
                        fresh.more,
                        fresh.distanceMeters,
                        dropJourneyStopIds = drop,
                        awaitJourneyStops = await,
                    )
                    // The opened farther cards refresh with the list; a relocation that stops
                    // offering one closes it once the picks are redone ([FartherCardsViewModel.retain]).
                    fartherModels.refresh()
                }
            val onRelocate: () -> Unit = relocateAction(
                cancelFetch = viewModel::cancelFetch,
                relocate = relocate,
                reconcile = reconcileSameSet,
            )
            // "Show all" re-picks the nearby set from the same fix with every mode back, then either
            // a new set's departures load on their own or the same places' are reconciled and
            // re-fetched, as after a relocation (SPEC *Finding stops → Hiding a mode*).
            val onShowAllModes: () -> Unit = relocateAction(
                cancelFetch = viewModel::cancelFetch,
                relocate = { onSameSet ->
                    HiddenModesSetting.showAll()
                    refilter(onSameSet)
                },
                reconcile = reconcileSameSet,
            )
            // The overflow menu's checkboxes: hiding a group filters at once, as from a long press;
            // showing one again re-picks the set so its stops come back, as "Show all" does.
            val onSetModeGroupShown: (ModeGroups.Group, Boolean) -> Unit = { group, shown ->
                if (shown) {
                    relocateAction(
                        cancelFetch = viewModel::cancelFetch,
                        relocate = { onSameSet ->
                            HiddenModesSetting.setGroupHidden(group, hidden = false)
                            refilter(onSameSet)
                        },
                        reconcile = reconcileSameSet,
                    )()
                } else {
                    HiddenModesSetting.setGroupHidden(group, hidden = true)
                }
            }
            // Consume a latched foreground return (set by the activity-level observer above the
            // overlay switch). Because the latch lives above this view, it survives this view being
            // out of composition (an overlay) until a re-entry consumes it here — the whole point.
            ConsumeForegroundReturn(
                pending = foregroundReturnPending,
                isBusy = { relocating.value },
                onConsumed = onForegroundReturnConsumed,
                onRelocate = onRelocate,
            )
            // A precise fix that moves the set: re-picked like a refresh, so the shown set's fetch is
            // canceled first and a same-place re-pick reconciles in place.
            val refinementNow by refinement.collectAsStateWithLifecycle()
            LaunchedEffect(refinementNow?.id) {
                val pending = refinementNow ?: return@LaunchedEffect
                relocateAction(
                    cancelFetch = viewModel::cancelFetch,
                    relocate = { onSameSet -> applyRefinement(pending, onSameSet) },
                    reconcile = reconcileSameSet,
                )()
            }
            AutoRefresh(viewModel, relocating)
            // Provide the branch topology so the card groups a branching row the way the widget
            // does — equivalent trunks merged, the label kept only where the trunk is a choice
            // ahead of the stop (see DepartureRows.destinationLines).
            CompositionLocalProvider(
                LocalRouteTopology provides routeTopology.value,
                LocalRouteStops provides routeStops(appContext),
            ) {
                MainScreen(
                    listState = listState,
                    pendingTracker = shownTracker,
                    state = shownState,
                    now = tickingNow(),
                    // Re-locates then re-fetches (see onRelocate above) — the same action a return
                    // to the foreground runs, so the refresh control and reopening the app both move
                    // the nearby set to the current position.
                    onRefresh = onRelocate,
                    // A pull asks every stop afresh, here and on the other screens (SPEC *Freshness →
                    // Shared arrivals*); the crosshairs re-locate and reuse what's recent.
                    onPullRefresh = {
                        ArrivalsCache.SHARED.clear()
                        viewModel.forceNextFetch()
                        // The opened farther cards refresh with the list, and afresh too.
                        fartherModels.forceNextFetch()
                        onRelocate()
                    },
                    onLocate = onLocate,
                    refreshing = refreshing,
                    // From "near me now": collapse a line served by several adjacent nearby stops
                    // to its nearest stop (SPEC *Finding stops → Near me now*). Spans both tiers, so
                    // a revealed stop collapses like an eager one. Empty for a location-free list
                    // (a watched-stops view), which is shown as-is.
                    // Plus an opened farther station's stops, so its departures show beside its card.
                    stopDistanceMeters = ready.distanceMeters + fartherDistanceMeters,
                    journeys = shownJourneys,
                    farJourneyMeters = farJourneyMeters,
                    nearbyKey = stopsKey,
                    farReveal = shownFarReveal,
                    // The journeys' origins (this way round) are fetched alongside the near-me stops.
                    onJourneyOrigins = viewModel::setJourneyStops,
                    onJourneyStopIds = { stopIds, openKey ->
                        journeyStopIds.value = stopIds
                        openJourneyKey.value = openKey
                        viewModel.journeyStopsReported()
                    },
                    onJourneyDestinations = viewModel::setJourneyDestinations,
                    journeyDestinationStops = journeyDestinationStops,
                    journeyDestinationsUnknown = journeyDestinationsUnknown,
                    onWidgetJourneys = viewModel::setWidgetJourneys,
                    journeysKnown = savedJourneys != null,
                    journeysLoading = journeysRead == null,
                    // Null (stations inert) while the saved journeys are a newer app version's file this
                    // build can't read: it's preserved untouched, so a toggle could only be ignored.
                    onToggleJourney = if (savedJourneys == null) {
                        null
                    } else {
                        { journey ->
                            journeyScope.launch {
                                try {
                                    journeyStore.toggle(journey)
                                } catch (e: IOException) {
                                    // Logged without the stations, and surfaced so the tap isn't
                                    // silently lost (SPEC principle 2).
                                    logStarWarning("journey star not saved: ${e::class.simpleName}")
                                    journeyWriteFailed = true
                                }
                            }
                        }
                    },
                    journeyWriteFailed = journeyWriteFailed,
                    onJourneyWriteFailureShown = { journeyWriteFailed = false },
                    onDismissJourneyTip = if (journeyTipDismissed) {
                        null
                    } else {
                        {
                            JourneyTipSession.closed = true
                            // On the application scope, like the bug-report opt-out: a rotation or
                            // Settings disposes this composition's scope, which would cancel the save.
                            ((application as? StopdashApp)?.applicationScope ?: journeyScope).launch {
                                try {
                                    tipSettings.setJourneyTipDismissed(true)
                                } catch (e: IOException) {
                                    // Closed for this session already; it shows again next launch.
                                    logAppSettingsWarning("journey tip dismissal not saved: ${e::class.simpleName}")
                                }
                            }
                        }
                    },
                    onFlipJourney = { journey ->
                        flippedJourneys = if (journey.key in flippedJourneys) flippedJourneys - journey.key else flippedJourneys + journey.key
                    },
                    // A tap on a header's distance shows that stop in the maps app. The stop comes
                    // from the same nearby set the distances span, so every distance can resolve.
                    onOpenStopMap = { stopId, name ->
                        val stop = (ready.eager + ready.more)
                            .firstNotNullOfOrNull { c -> c.stops.firstOrNull { it.id == stopId } }
                            // Or an opened farther station's stop, looked up when its card was tapped.
                            ?: fartherLoads.values.filterIsInstance<FartherLoad.Open>()
                                .firstNotNullOfOrNull { open -> open.stops.firstOrNull { it.id == stopId } }
                        if (stop != null) {
                            openStopMap(stop.latitude, stop.longitude, name)
                        } else {
                            logLocationWarning("map tap for a stop not in the nearby set: $stopId")
                        }
                    },
                    starred = starred,
                    onToggleStar = viewModel::toggleStar,
                    starringAvailable = starringAvailable,
                    starWriteFailed = starWriteFailed,
                    dismissed = dismissed,
                    onDismissAlert = viewModel::dismissAlert,
                    dismissWriteFailed = dismissWriteFailed,
                    onDismissWriteFailureShown = viewModel::dismissWriteFailureShown,
                    onStarWriteFailureShown = viewModel::starWriteFailureShown,
                    onOpenLicenses = onOpenLicenses,
                    onOpenSettings = onOpenSettings,
                    onFindStation = onFindStation,
                    stationTitle = stationTitle,
                    onCloseStation = onCloseStation,
                    // Offered only while some nearby stop could start a trip (not every mode hidden).
                    onPlanTo = if (
                        hereOriginIds(ready.eagerStops, ready.nearbyStops, ready.distanceMeters, hiddenModes).isEmpty()
                    ) {
                        null
                    } else {
                        { onPlanTo() }
                    },
                    updateAvailable = updateAvailable,
                    onOpenAppListing = onOpenAppListing,
                    farther = fartherCards,
                    // Ignored while a relocation's fresh fix is in flight, so a tap can't open a card
                    // picked from the pre-fix set.
                    onOpenFarther = { place -> if (!relocatingNow) fartherModels.open(place, ready.location) },
                    onSendBugReport = onSendBugReport,
                    locationBanner = locationBannerNow,
                    // Hiding filters the list at once; the hidden mode's stops stop being fetched
                    // from the next re-locate. Showing them again re-picks the set from the same
                    // fix, so they come back now (SPEC *Finding stops → Hiding a mode*).
                    hiddenModes = hiddenModes,
                    // A long press hides the mode's whole group ("Train" for Thameslink), as its
                    // overflow checkbox does.
                    onHideMode = { mode -> HiddenModesSetting.setGroupHidden(ModeGroups.of(mode), hidden = true) },
                    onShowAllModes = onShowAllModes,
                    onSetModeGroupShown = onSetModeGroupShown,
                    hiddenModesWriteFailed = hiddenModesWriteFailed,
                    onHiddenModesWriteFailureShown = HiddenModesSetting::writeFailureShown,
                )
            }
        }
    }

    /**
     * "Find a station" (SPEC *Finding stops*): the name search, or — once a match is picked — that
     * station's live departures. The search's ViewModel is activity-retained, so back from a
     * station finds the query and matches as they were. A station gets its own retained store
     * (like a nearby set's), cleared when another station opens or the search closes, so its
     * fetch stops with it. Its departures are never saved for the widget (no snapshot store): the
     * widget shows the near-me set, and a station looked up once isn't one the user watches.
     */
    @Composable
    private fun StationSearchArea(
        stationId: String?,
        stationName: String,
        onOpenStation: (StationMatch) -> Unit,
        onCloseStation: () -> Unit,
        onCloseSearch: () -> Unit,
        tripPicking: Boolean = false,
        tripToId: String? = null,
        tripToName: String = "",
        onPlanTo: () -> Unit = {},
        onPickTo: (StationMatch) -> Unit = {},
        onClosePicker: () -> Unit = {},
        onClearTo: () -> Unit = {},
    ) {
        // Captured once, so lambdas the retained ViewModels keep close over the application, not
        // this Activity (which a rotation destroys).
        val appContext = applicationContext
        val search: StationSearchViewModel = viewModel(
            key = "station-search",
            factory = viewModelFactory {
                initializer {
                    // From…'s own recent opens, read and recorded alike.
                    val recents = recentSearches(appContext).store(RecentSearches.Kind.FROM)
                    StationSearchViewModel(
                        stationFinder,
                        createSavedStateHandle(),
                        // The bundled index, read off the main thread on the search's first query.
                        loadIndex = { StationIndexStore.load(appContext) },
                        // The user's own stops, from the device: listed before typing, matched as they type.
                        loadYours = { loadYourStops(appContext, recents) },
                        recordOpen = { recents.add(it) },
                        warn = ::logDepartureWarning,
                    )
                }
            },
        )
        val stores: NearbyDeparturesStores = viewModel(key = "station-stores")
        // Leaving the search, or a station, drops the station's retained models (its stops, the
        // nearby set around it, its list and any trip from it), so their fetches stop with the
        // page and reopening it looks everything up afresh.
        val closeSearch = {
            stores.clearAll()
            search.clear()
            onCloseSearch()
        }
        val closeStation = {
            stores.clearAll()
            onCloseStation()
        }
        if (stationId == null) {
            val state by search.state.collectAsStateWithLifecycle()
            // Reread the user's own stops each time the search shows: a star may have changed.
            LaunchedEffect(Unit) { search.refreshYours() }
            StationSearchScreen(
                state = state,
                onQueryChange = search::onQueryChange,
                onOpenStation = { match ->
                    search.onOpened(match)
                    onOpenStation(match)
                },
                onRetry = search::retry,
                onBack = closeSearch,
            )
            return
        }
        val storeOwner = remember(stationId) { stores.ownerFor(stationId, this@MainActivity) }
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val stopsModel: StationStopsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { StationStopsViewModel(stationFinder, stationId, warn = ::logDepartureWarning) }
                },
            )
            val stops by stopsModel.state.collectAsStateWithLifecycle()
            val ready = stops as? StationStopsViewModel.State.Ready
            val center = ready?.center
            if (ready == null || center == null) {
                // No stops yet (or none TfL placed, so nowhere to stand): the station's own page.
                if (ready == null) {
                    StationPlaceholderScreen(
                        title = stationName,
                        state = stops,
                        onRetry = stopsModel::retry,
                        onBack = closeStation,
                        onLocate = closeSearch,
                    )
                } else {
                    LookDepartures(
                        stops = ready.stops,
                        title = stationName,
                        onClose = closeStation,
                        onLocate = closeSearch,
                        onPlanTo = null,
                        destination = null,
                        destinationName = "",
                        onClearDestination = null,
                        writeFailures = viewModel<WriteFailuresHolder>().failures,
                    )
                }
                return@CompositionLocalProvider
            }
            FromStationArea(
                stationName = stationName,
                center = center,
                stationStopIds = ready.stops.mapTo(HashSet()) { it.id },
                onClose = closeStation,
                onBackToNearMe = closeSearch,
                tripPicking = tripPicking,
                tripToId = tripToId,
                tripToName = tripToName,
                onPlanTo = onPlanTo,
                onPickTo = onPickTo,
                onClosePicker = onClosePicker,
                onClearTo = onClearTo,
            )
        }
    }

    /**
     * A searched station's page (SPEC *Finding stops → From… To…*): the near-me list as if the rider
     * stood at the station — its own stops and the others around it, with distances from it — and
     * **To…** from there works as it does from the near-me list. The station's position stands in
     * for the device's ([FixedLocation]), so the same nearby resolve, list and trip serve it; its
     * departures are never the widget's, and a refresh re-picks from the same place. Its models live
     * in the station's store (the caller's), so leaving the station drops them all.
     */
    @Composable
    private fun FromStationArea(
        stationName: String,
        center: Coordinates,
        stationStopIds: Set<String>,
        onClose: () -> Unit,
        // The crosshairs: "use my location" leaves the station for the near-me list.
        onBackToNearMe: () -> Unit,
        tripPicking: Boolean,
        tripToId: String?,
        tripToName: String,
        onPlanTo: () -> Unit,
        onPickTo: (StationMatch) -> Unit,
        onClosePicker: () -> Unit,
        onClearTo: () -> Unit,
    ) {
        val fromNearby: NearbyStopsViewModel = viewModel(
            key = "from-nearby",
            factory = viewModelFactory {
                initializer {
                    NearbyStopsViewModel(
                        location = FixedLocation(center),
                        finder = stationAreaStopFinder,
                        warn = ::logLocationWarning,
                        hiddenModes = { HiddenModesSetting.loaded() },
                        anchorStopIds = stationStopIds,
                    ).also { it.locate() }
                }
            },
        )
        val state by fromNearby.state.collectAsStateWithLifecycle()
        val hidden by HiddenModesSetting.changes.collectAsStateWithLifecycle()
        // Showing a mode here changes the setting for every list, so the near-me set re-picks too,
        // and its retained list is dropped so it's rebuilt from that set on the way back.
        val nearMeStores: NearbyDeparturesStores = viewModel(viewModelStoreOwner = this@MainActivity)
        val nearMeRefilter = {
            nearMeStores.clearAll()
            nearbyViewModel.refilter()
        }
        // A return to the foreground refreshes the page, as it does the near-me list: here by
        // re-picking from the same place (nothing moves), then refreshing a same-set page.
        var returnPending by rememberSaveable { mutableStateOf(false) }
        ForegroundReturnLatcher(
            isReady = { fromNearby.state.value is NearbyStopsViewModel.State.Ready },
            isBusy = { fromNearby.relocating.value },
            onReturn = { returnPending = true },
        )
        val closeTrip = {
            onClosePicker()
            onClearTo()
        }
        val ready = state as? NearbyStopsViewModel.State.Ready
        // The page's held loading cards ([PendingTracker]), held here — above the To… flow, which
        // takes the list out of composition — so closing it keeps them; following the stop set.
        val listTracker = rememberPendingTracker(ready?.clusterSetKey)
        if (ready == null) {
            // Nothing to show from yet (or the lookup failed): the station's placeholder, whose
            // retry looks again. Any list or trip kept from before is dropped, so a recovered page
            // fetches afresh rather than showing what was loaded before the failure.
            val listStores: NearbyDeparturesStores = viewModel(key = "from-list-stores")
            val tripStores: NearbyDeparturesStores = viewModel(key = "from-trip-stores")
            DisposableEffect(Unit) {
                listStores.clearAll()
                tripStores.clearAll()
                onDispose {}
            }
            StationPlaceholderScreen(
                title = stationName,
                state = when (val s = state) {
                    is NearbyStopsViewModel.State.Failed -> StationStopsViewModel.State.Failed(s.kind)
                    is NearbyStopsViewModel.State.Empty, NearbyStopsViewModel.State.NoLocation ->
                        StationStopsViewModel.State.NoStops
                    else -> StationStopsViewModel.State.Loading
                },
                onRetry = fromNearby::locate,
                onBack = onClose,
                onLocate = onBackToNearMe,
            )
            return
        }
        if (tripPicking || tripToId != null) {
            HereTripArea(
                origin = remember(ready, hidden) {
                    val byId = ready.nearbyStops.associateBy { it.id }
                    hereOriginIds(ready.eagerStops, ready.nearbyStops, ready.distanceMeters, hidden)
                        .mapNotNull { byId[it] }
                },
                distanceMeters = ready.distanceMeters,
                clusters = ready.eager + ready.more,
                hiddenModes = hidden,
                picking = tripPicking,
                toId = tripToId,
                toName = tripToName,
                onPlanTo = onPlanTo,
                onPickTo = onPickTo,
                onClosePicker = { if (tripToId == null) closeTrip() else onClosePicker() },
                onClose = closeTrip,
                foregroundReturnPending = returnPending,
                onForegroundReturnConsumed = { returnPending = false },
                isRelocating = { fromNearby.relocating.value },
                relocate = { fromNearby.relocate() },
                showAllModes = {
                    HiddenModesSetting.showAll()
                    fromNearby.refilter()
                    nearMeRefilter()
                },
                relocating = fromNearby.relocating,
                repicked = fromNearby.repicked,
                locationBanner = fromNearby.locationBanner,
                keyPrefix = "from",
                fromName = stationName,
                fromStopIds = stationStopIds,
                onLocate = onBackToNearMe,
            )
        } else {
            DeparturesForStops(
                ready = ready,
                relocate = { onSameSet -> fromNearby.relocate(onSameSet) },
                relocating = fromNearby.relocating,
                locationBanner = fromNearby.locationBanner,
                // The station page has no overflow (back and To… are in its bar).
                onOpenLicenses = {},
                onOpenSettings = {},
                onFindStation = {},
                onPlanTo = onPlanTo,
                updateAvailable = false,
                onOpenAppListing = {},
                onSendBugReport = {},
                foregroundReturnPending = returnPending,
                onForegroundReturnConsumed = { returnPending = false },
                storesKey = "from-list-stores",
                refilter = { onSameSet ->
                    fromNearby.refilter(onSameSet)
                    nearMeRefilter()
                },
                forWidget = false,
                stationTitle = stationName,
                onCloseStation = onClose,
                onLocate = onBackToNearMe,
                pendingTracker = listTracker,
            )
        }
    }

    /**
     * "To…" from the near-me list (SPEC *Finding stops → From… To…*): the destination search, then
     * the direct trips there from the [origin] stops near the rider, titled "To ‹place›". Like a
     * searched station, a look: its models are retained apart from the near-me list's and dropped
     * when it closes, nothing is saved, and the destination isn't added to the search's Recent list.
     */
    @Composable
    private fun HereTripArea(
        origin: List<StopRef>,
        // The stops a trip from here may start at, whatever their modes: the nearest is the
        // Planner's start. Empty uses [origin] (a From… station starts at its own stops).
        anchors: List<StopRef> = emptyList(),
        // Each stop's distance from the rider: the trip's rows show a line once, from its nearest
        // stop, ordered nearest first, with distances in the headers, as the near-me list does.
        distanceMeters: Map<String, Double>,
        // The whole nearby set, split into the trip's origins and the places around them for its model.
        clusters: List<NearbySelection.NearbyCluster>,
        hiddenModes: Set<String>,
        picking: Boolean,
        toId: String?,
        toName: String,
        onPlanTo: () -> Unit,
        onPickTo: (StationMatch) -> Unit,
        onClosePicker: () -> Unit,
        onClose: () -> Unit,
        foregroundReturnPending: Boolean,
        onForegroundReturnConsumed: () -> Unit,
        isRelocating: () -> Boolean,
        relocate: () -> Unit,
        showAllModes: () -> Unit,
        relocating: StateFlow<Boolean>,
        // Each finished re-pick of the nearby set (a fresh fix, or "Show all").
        repicked: StateFlow<NearbyStopsViewModel.Repick?>,
        // Whether the nearby set's fix is low-confidence (a re-locate that kept the old set, or an
        // approximate fix): shown over the trip as over the list, so its departures never read as
        // from where the rider is now when the location couldn't be confirmed.
        locationBanner: StateFlow<LocationBanner?>,
        // Keys this trip's retained models apart from another's: the near-me list's ("here"), or a
        // searched station's (From…), whose trip is titled "‹station› ➔ ‹to›" by [fromName].
        keyPrefix: String = "here",
        fromName: String? = null,
        // A From… station's own stops: its trip starts at one of them, never a neighbor.
        fromStopIds: Set<String> = emptySet(),
        // The crosshairs from a From… station's trip: back to the near-me list. Null re-locates.
        onLocate: (() -> Unit)? = null,
    ) {
        val appContext = applicationContext
        ConsumeForegroundReturn(
            pending = foregroundReturnPending,
            isBusy = isRelocating,
            onConsumed = onForegroundReturnConsumed,
            onRelocate = relocate,
        )
        val search: StationSearchViewModel = viewModel(
            key = "$keyPrefix-to-search",
            factory = viewModelFactory {
                initializer {
                    // Its own recent list, apart from From…'s: where the rider goes, most recent
                    // first (maintainer, 2026-09-26), read and recorded alike.
                    val recents = recentSearches(appContext).store(RecentSearches.Kind.TO)
                    StationSearchViewModel(
                        stationFinder,
                        createSavedStateHandle(),
                        loadIndex = { StationIndexStore.load(appContext) },
                        loadYours = { loadYourStops(appContext, recents) },
                        recordOpen = { recents.add(it) },
                        warn = ::logDepartureWarning,
                    )
                }
            },
        )
        val stores: NearbyDeparturesStores = viewModel(key = "$keyPrefix-trip-stores")
        val toStores: NearbyDeparturesStores = viewModel(key = "$keyPrefix-to-stores")
        val writeFailures = viewModel<WriteFailuresHolder>().failures
        val close = {
            stores.clearAll()
            toStores.clearAll()
            search.clear()
            onClose()
        }
        // Nothing nearby to start from (every mode hidden, or a relocation that left none of the
        // origins): end the trip before offering a destination search it couldn't use.
        if (origin.isEmpty()) {
            LaunchedEffect(Unit) { close() }
            return
        }
        if (picking || toId == null) {
            val state by search.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { search.refreshYours() }
            StationSearchScreen(
                state = state,
                onQueryChange = search::onQueryChange,
                onOpenStation = { match ->
                    toStores.clearAll()
                    search.onOpened(match)
                    search.clear()
                    onPickTo(match)
                },
                onRetry = search::retry,
                onBack = {
                    search.clear()
                    if (toId == null) close() else onClosePicker()
                },
                hint = stringResource(R.string.station_search_to_hint),
            )
            return
        }
        val title = if (fromName == null) {
            stringResource(R.string.trip_title_here, toName)
        } else {
            stringResource(R.string.journey_title, fromName, toName)
        }
        // A trip with a change (SPEC *Trips with a change*): TfL's Journey Planner from the nearest
        // origin stop to the destination, timed by live trains. The Planner takes a stop or station
        // id, not an interchange ("HUB…"): an ordinary stop is planned to as picked, at once; an
        // interchange is looked up first and planned to at each of its stations and its bus stops,
        // the best way there whatever the line or mode.
        val toStopIds = if (!toId.startsWith(HUB_PREFIX)) {
            listOf(toId)
        } else {
            val toOwner = remember(toId) { toStores.ownerFor(toId, this@MainActivity) }
            val toModel: StationStopsViewModel = viewModel(
                viewModelStoreOwner = toOwner,
                factory = viewModelFactory {
                    initializer { StationStopsViewModel(stationFinder, toId, warn = ::logDepartureWarning) }
                },
            )
            val to by toModel.state.collectAsStateWithLifecycle()
            val members = (to as? StationStopsViewModel.State.Ready)?.stops
            if (members == null) {
                StationPlaceholderScreen(
                    title = title,
                    state = to,
                    onRetry = toModel::retry,
                    onBack = close,
                    // From a From… station, back to near me; from here, re-locate, as the trip's page does.
                    onLocate = onLocate ?: relocate,
                )
                return
            }
            PlanTargets.of(members.map { PlanTargets.Member(it.id, it.lines) }).ifEmpty { listOf(members.first().id) }
        }
        // From a From… station, one of its own stops (the neighbors around it are no start); else
        // the stop nearest the rider.
        val starts = anchors.ifEmpty { origin }.filter { !it.id.startsWith(HUB_PREFIX) && it.lines.isNotEmpty() }
            .ifEmpty { origin.filterNot { it.id.startsWith(HUB_PREFIX) } }
        val fromStop = (starts.filter { it.id in fromStopIds }.ifEmpty { starts })
            .minByOrNull { distanceMeters[it.id] ?: Double.MAX_VALUE } ?: origin.first()
        // Keyed on both ends, so a relocation to a new nearest stop plans afresh.
        val tripKey = "${fromStop.id}>${toStopIds.joinToString(",")}"
        val owner = remember(tripKey) { stores.ownerFor(tripKey, this@MainActivity) }
        val trip: TripViewModel = viewModel(
            viewModelStoreOwner = owner,
            factory = viewModelFactory {
                initializer {
                    TripViewModel(
                        journeyPlanner, departuresClient(appContext), fromStop.id, toStopIds, warn = ::logDepartureWarning,
                        arrivals = ArrivalsCache.SHARED, departureSourceChanges = RailApiKeySetting.changes,
                        poles = { area -> routeStops(appContext).loadPoles(area).map { it.id } },
                        savedState = createSavedStateHandle(),
                        dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
                        writeFailures = writeFailures,
                    )
                }
            },
        )
        val lifecycleOwner = LocalLifecycleOwner.current
        SideEffect { trip.hiddenModes = hiddenModes }
        // A re-pick of the nearby set (a fresh fix, a retried location) that kept the same nearest
        // stop keeps this trip, but its walk and live times follow the new fix at once rather than
        // wait for the next tick.
        val repick by repicked.collectAsStateWithLifecycle()
        // The model remembers which re-pick it refreshed for, so a rotation (a new effect over the
        // retained model) doesn't fetch again.
        LaunchedEffect(trip, repick) { trip.refreshFor(repick?.id) }
        // The list's own foreground tick: live times refresh, and a plan past its reuse is planned
        // again, only while the trip is on screen (no background work, SPEC *Trips with a change*).
        LaunchedEffect(lifecycleOwner, trip) {
            autoRefresh(
                lifecycleOwner.lifecycle,
                // A relocation in flight hands over a re-pick when it lands: a tick meanwhile would
                // fetch for an origin about to change.
                isRefreshing = { trip.state.value.refreshing || trip.state.value.planning || isRelocating() },
            ) { trip.refresh() }
        }
        val tripState by trip.state.collectAsStateWithLifecycle()
        // From here the rider still has to reach the first stop; at a From… station they're at its
        // own stops (a neighbor, when every own stop is hidden, is still a walk from it).
        val access = if (fromStop.id in fromStopIds) Duration.ZERO else TripTiming.accessWalk(distanceMeters[fromStop.id] ?: 0.0)
        TripScreen(
            title = title,
            state = tripState,
            now = tickingNow(),
            access = access,
            // The same route data as the list checks its trains against (SPEC *Trips with a change*).
            routeStops = routeStops(appContext),
            onBack = close,
            onRetry = trip::retry,
            locationBanner = locationBanner.collectAsStateWithLifecycle().value,
            relocating = relocating.collectAsStateWithLifecycle().value,
            // From a From… station, back to near me; from here, re-locate.
            onRelocate = onLocate ?: relocate,
            hiddenModes = hiddenModes,
            onShowAllModes = showAllModes,
            menu = LocalAppMenu.current,
            openRoute = trip.openRoute,
            dismissed = trip.dismissed.collectAsStateWithLifecycle().value,
            onDismissAlert = trip::dismissAlert,
            dismissWriteFailed = trip.dismissWriteFailed.collectAsStateWithLifecycle().value,
            onDismissWriteFailureShown = trip::dismissWriteFailureShown,
        )
    }

    /**
     * A looked-up set of [stops]' live departures (SPEC *Finding stops*) — a searched station's page,
     * or a To… trip from the stops near the rider — under [title], with its own retained
     * [MainViewModel] from the caller's store. With a [destination] ([destinationName]'s stops) it
     * keeps only the departures that go there directly (*From… To…*). Never saved for the widget.
     * Back runs [onClearDestination] while a destination is set and it is given, else [onClose].
     */
    @Composable
    private fun LookDepartures(
        stops: List<StopRef>,
        title: String,
        onClose: () -> Unit,
        // Null hides To… (a station page with nowhere to stand).
        onPlanTo: (() -> Unit)?,
        destination: List<StopRef>?,
        destinationName: String,
        onClearDestination: (() -> Unit)?,
        writeFailures: WriteFailures,
        // Refresh on a return to the foreground. Off for a trip from here, whose caller re-locates
        // first and hands over the outcome as a [repick].
        refreshOnReturn: Boolean = true,
        // A trip from here: the latest finished re-pick of the nearby set, to reconcile to.
        repick: NearbyStopsViewModel.Repick? = null,
        // In place of a plain refresh (the trip from here re-locates first), and its fix in flight.
        onRefresh: (() -> Unit)? = null,
        relocating: StateFlow<Boolean>? = null,
        // The crosshairs; null re-runs [onRefresh] (a trip from here re-locates).
        onLocate: (() -> Unit)? = null,
        distanceMeters: Map<String, Double> = emptyMap(),
        hiddenModes: Set<String> = emptySet(),
        onShowAllModes: () -> Unit = HiddenModesSetting::showAll,
        // A trip from here: the model is built from, and each new [repick] reconciles it to,
        // these tiers, as the near-me list's is, rather than refreshing a seeded stop list.
        hereTiers: HereTripTiers? = null,
        // The near-me set's location banner, for a trip from here.
        locationBanner: StateFlow<LocationBanner?>? = null,
    ) {
        val appContext = applicationContext
        val viewModel: MainViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    MainViewModel(
                        client = departuresClient(appContext),
                        departureSourceChanges = RailApiKeySetting.changes,
                        seedStops = stops,
                        initialMore = hereTiers?.more.orEmpty(),
                        stopDistanceMeters = hereTiers?.distanceMeters.orEmpty(),
                        // A trip from here spaces out its far origins' fetches as the list does.
                        farArrivalsReuse = if (hereTiers != null) FAR_ARRIVALS_REUSE else java.time.Duration.ZERO,
                        // Stars and dismissals are per row/place across every view, so a star
                        // set here shows on the near-me list too, and the other way round.
                        starredStore = DataStoreStarredRowsStore.from(appContext, warn = ::logStarWarning),
                        dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
                        warn = ::logDepartureWarning,
                        arrivalsReuse = ARRIVALS_REUSE,
                        sharedArrivals = ArrivalsCache.SHARED,
                        disruptionReuse = DISRUPTION_REUSE,
                        lineStatusReuse = LINE_STATUS_REUSE,
                        rateWaitMillis = { SharedTflRateLimiter.waitedMillis },
                        logStats = ::logDepartureWarning,
                        // Not the widget's list: the near-me model keeps the journey pins.
                        ownsWidgetJourneys = false,
                        // A star set here reorders the widget's pinned rows too, so redraw it.
                        redrawWidget = { StopDashWidget().updateAll(appContext) },
                        writeFailures = writeFailures,
                        onStarToggled = { row -> rememberStarredPlace(appContext, row) },
                    )
                }
            },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
        val starred by viewModel.starred.collectAsStateWithLifecycle()
        val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
        val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
        val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
        val dismissWriteFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
        // Kept live while shown, like the near-me list; there's no location to re-resolve.
        AutoRefresh(viewModel, relocating ?: NOT_RELOCATING)
        val relocatingNow = relocating?.collectAsStateWithLifecycle()?.value ?: false
        val locationBannerNow = locationBanner?.collectAsStateWithLifecycle()?.value
        val hiddenModesWriteFailed by HiddenModesSetting.writeFailed.collectAsStateWithLifecycle()
        // And refreshed on a return to the foreground, as the near-me list is (by its relocate),
        // so coming back to the app doesn't leave aged departures up until the next tick.
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner, viewModel, refreshOnReturn) {
            if (!refreshOnReturn) return@LaunchedEffect
            var returning = false
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (returning && !viewModel.refreshing.value) viewModel.refresh()
                returning = true
            }
        }
        val latestTiers by rememberUpdatedState(hereTiers)
        // Which re-pick this model last took, kept beside the model in its own store, so it
        // outlives this composition (the To… search replacing the page, a rotation) exactly as
        // long as the model does. A new model (new origins) starts from the latest: it fetches on
        // its own. A re-pick it hasn't taken reconciles it — any that finished while the page was
        // away included — re-fetching with the current stops, places and distances, and replacing
        // any fetch still in flight rather than skipping behind it.
        val taken: TakenRepick = viewModel(
            factory = viewModelFactory { initializer { TakenRepick(repick?.id) } },
        )
        LaunchedEffect(viewModel, repick) {
            val done = repick ?: return@LaunchedEffect
            val tiers = latestTiers ?: return@LaunchedEffect
            if (done.id == taken.id) return@LaunchedEffect
            taken.id = done.id
            viewModel.reconcile(tiers.eager, tiers.more, tiers.distanceMeters)
        }
        // A trip from here re-locates in the background: stop its fetch while the fix is in
        // flight, as the list's refresh does, so the old origins' departures aren't stamped as new.
        if (relocating != null && hereTiers != null) {
            LaunchedEffect(viewModel, relocating) {
                relocating.collect { if (it) viewModel.cancelFetch() }
            }
        }
        CompositionLocalProvider(
            LocalRouteTopology provides routeTopology.value,
            LocalRouteStops provides routeStops(appContext),
        ) {
            val now = tickingNow()
            val hubs = remember(stops) { stops.associate { it.id to it.hubId } }
            val tripEnds = remember(destination) {
                destination?.map { DirectTrips.End(it.id, it.name, it.hubId) }
            }
            // With a destination, only the departures that go there (SPEC *Finding stops →
            // From… To…*); the whole page otherwise.
            val trip = tripEnds?.let { rememberTripView(state, it, destinationName, hubs, now, hiddenModes) }
            MainScreen(
                state = trip?.state ?: state,
                now = now,
                // Each station page or trip origin set has its own model, and so its own list.
                listKey = viewModel,
                onRefresh = onRefresh ?: { viewModel.refresh() },
                onPullRefresh = {
                    ArrivalsCache.SHARED.clear()
                    viewModel.forceNextFetch()
                    (onRefresh ?: { viewModel.refresh() })()
                },
                onLocate = onLocate,
                refreshing = refreshing || relocatingNow,
                starred = starred,
                onToggleStar = viewModel::toggleStar,
                stopDistanceMeters = distanceMeters,
                hiddenModes = hiddenModes,
                onShowAllModes = onShowAllModes,
                // A "Show all" that couldn't be saved says so here too, as on the list.
                hiddenModesWriteFailed = hiddenModesWriteFailed,
                onHiddenModesWriteFailureShown = HiddenModesSetting::writeFailureShown,
                locationBanner = locationBannerNow,
                starringAvailable = starringAvailable,
                starWriteFailed = starWriteFailed,
                onStarWriteFailureShown = viewModel::starWriteFailureShown,
                dismissed = dismissed,
                onDismissAlert = viewModel::dismissAlert,
                dismissWriteFailed = dismissWriteFailed,
                onDismissWriteFailureShown = viewModel::dismissWriteFailureShown,
                stationTitle = title,
                onCloseStation = if (trip == null || onClearDestination == null) onClose else onClearDestination,
                onPlanTo = onPlanTo?.let { planTo -> { planTo() } },
                tripNotice = trip?.notice,
                emptyMessage = trip?.emptyMessage,
            )
        }
    }

    // Either grant is enough to find stops; precise (FINE) is preferred and requested first.
    private fun hasLocationPermission(): Boolean =
        hasFineLocation() ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Whether the precise-location request has been shown at least once. Persisted so an
    // existing coarse-only install (upgraded from before FINE was requested) is prompted for
    // precise exactly once — adding FINE to the manifest does not upgrade a live coarse grant,
    // so without this such a user would silently keep the inaccurate coarse behavior (Codex).
    // A completed approximate choice sets it too, so the user isn't nagged every open.
    private val locationPrefs by lazy {
        getSharedPreferences("stopdash.location", MODE_PRIVATE)
    }

    private fun precisePrompted(): Boolean = locationPrefs.getBoolean(KEY_PRECISE_PROMPTED, false)

    private fun markPrecisePrompted() {
        locationPrefs.edit().putBoolean(KEY_PRECISE_PROMPTED, true).apply()
    }

    /** Opens this app's system settings so the user can grant a permanently-denied permission. */
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        // FINE first so the runtime dialog leads with precise; COARSE alongside so the dialog
        // offers the approximate choice and an approximate grant still finds stops.
        private val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        private const val KEY_PRECISE_PROMPTED = "precise_prompted"

        // The Play listing, Play-app scheme first then the web fallback; the applicationId is
        // appended at open time (see [openPlayListing]).
        private val PLAY_LISTING_URIS = listOf(
            "market://details?id=",
            "https://play.google.com/store/apps/details?id=",
        )

        // Process-scoped: one OkHttp engine and connection pool shared by every ViewModel
        // the process creates, rather than a fresh client leaked per ViewModel (nothing
        // closes a Ktor client, so a per-launch one accumulates engine/pool resources). A
        // single long-lived client is OkHttp's own recommended shape; it lives for the
        // process and dies with it.
        private val httpClient by lazy { KtorTflClient.defaultHttpClient() }

        // A departures list's client: TfL's, plus National Rail's own live departures at a rail
        // station once the user has pasted a National Rail key (SPEC *National Rail*). The key and
        // the bundled station codes are read per request, so a paste applies on the next refresh.
        // Every screen's arrivals land in the shared cache, so each shows what the others just
        // fetched (SPEC *Freshness → Shared arrivals*).
        private fun departuresClient(context: Context): TflClient = CachingTflClient(RailAwareTflClient(
            tfl = KtorTflClient(
                httpClient,
                appKey = { UserApiKeySetting.current },
                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                requestPool = SharedTflRequestPool.pool,
                warn = ::logDepartureWarning,
            ),
            rail = KtorDarwinClient(httpClient, apiKey = { RailApiKeySetting.current }, warn = ::logDepartureWarning),
            codes = { RailStationCodesStore.load(context.applicationContext) },
            warn = ::logDepartureWarning,
        ))

        // "Find a station": the name search and a station's stop lookup, both on demand from the
        // search screen, never on the refresh path. The typed query goes to TfL only (SPEC *Privacy*).
        private val stationFinder by lazy {
            KtorTflClient(
                httpClient,
                appKey = { UserApiKeySetting.current },
                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                requestPool = SharedTflRequestPool.pool,
            )
        }

        // A trip with a change's planner (SPEC *Trips with a change*): on demand while a trip is on
        // screen, never on the refresh path of the list.
        private val journeyPlanner by lazy {
            KtorTflClient(
                httpClient,
                appKey = { UserApiKeySetting.current },
                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                requestPool = SharedTflRequestPool.pool,
                warn = ::logDepartureWarning,
            )
        }

        // The Planner takes stop and station ids but not an interchange's.
        private const val HUB_PREFIX = "HUB"

        // The station view has no location fix to wait on, so its auto-refresh is never held off by one.
        private val NOT_RELOCATING: StateFlow<Boolean> = MutableStateFlow(false)

        // The route detail's stop lists and stop areas' poles, kept for a day in memory and in a
        // file in the app's cache directory (never backed up), so reopening a route — or the app
        // after the process was killed — shows its stops without refetching. Fetched only when a
        // route page or journey card needs them — never on the refresh path. The file is read
        // once, off the main thread, as soon as the repository is built.
        private val routeStopsLock = Any()
        private var routeStopsInstance: RouteStopsRepository? = null

        private fun routeStops(context: Context): RouteStopsRepository = synchronized(routeStopsLock) {
            routeStopsInstance ?: RouteStopsRepository(
                source = KtorTflClient(
                    httpClient,
                    appKey = { UserApiKeySetting.current },
                    rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                    requestPool = SharedTflRequestPool.pool,
                ),
                warn = ::logRouteStopsWarning,
                store = FileRouteStopsStore(File(context.applicationContext.cacheDir, "route-stops.json"), ::logRouteStopsWarning),
                // Places a station departures are listed under by an id its line's route doesn't
                // call at (St Pancras's Thameslink platforms), wherever that id comes from.
                stations = { StationIndexStore.load(context.applicationContext).stations },
            ).also { repository ->
                routeStopsInstance = repository
                (context.applicationContext as? StopdashApp)?.applicationScope?.launch { repository.warm() }
            }
        }
    }
}

/**
 * What a bug report is filed from: the [location] fix and the watched [stops] with their
 * [distanceMeters]. Built from the current nearby state by [bugReportRequestFor] at the moment the
 * user sends (so it survives a rotation mid-consent). [location] is the fix — from [Ready], or the
 * one [Empty]/[Failed] resolved against — and null only where none was obtained (no permission, no
 * fix yet); the report then states the location as unavailable. [stops] is empty off [Ready].
 */
internal data class BugReportRequest(
    val location: Coordinates?,
    val stops: List<StopRef>,
    val distanceMeters: Map<String, Double>,
)

/**
 * The report inputs for the current nearby [state]: the retained fix, all resolved nearby stops
 * (both tiers), and their distances when [NearbyStopsViewModel.State.Ready]; the retained fix alone
 * for [Empty]/[Failed]; otherwise a null-location request. Kept out of composition so the request
 * is rebuilt fresh each time the user sends.
 */
/**
 * Holds whether the bug-report consent dialog is open, in an activity-scoped [ViewModel] so it
 * survives a configuration change (the dialog stays up through a rotation) but resets on process
 * death — where the in-memory location fix is gone anyway, so restoring the dialog would only build
 * a location-unavailable report (Codex P2 on #86). A plain flag, not the report inputs: the
 * coordinate is never persisted (SPEC *Privacy*), so the request is rebuilt from live state at send.
 */
internal class BugReportConsentViewModel : androidx.lifecycle.ViewModel() {
    var open by mutableStateOf(false)
}

/** The build/device header for a bug report — no user data (SPEC *Privacy*). Top-level so the
 *  application-scoped share coroutine doesn't capture the Activity. */
internal fun bugReportHeader(): BugReport.Header = BugReport.Header(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE.toLong(),
    device = "${Build.MANUFACTURER} ${Build.MODEL}",
    androidRelease = Build.VERSION.RELEASE,
    sdkInt = Build.VERSION.SDK_INT,
    capturedAt = Instant.now(),
)

internal fun bugReportRequestFor(state: NearbyStopsViewModel.State): BugReportRequest =
    when (state) {
        is NearbyStopsViewModel.State.Ready ->
            // Every resolved nearby stop (both tiers), so a report after a "More" reveal carries
            // the farther stops too — matching distanceMeters and the consent's "each nearby stop".
            BugReportRequest(state.location, state.nearbyStops, state.distanceMeters)
        // Empty and Failed obtained a fix (the lookup ran) — carry it so a report from those gates
        // says where "no stops nearby" / "can't reach TfL" happened, the context they need.
        is NearbyStopsViewModel.State.Empty ->
            BugReportRequest(state.location, stops = emptyList(), distanceMeters = emptyMap())
        is NearbyStopsViewModel.State.Failed ->
            BugReportRequest(state.location, stops = emptyList(), distanceMeters = emptyMap())
        // PermissionRequired / Locating / NoLocation have no fix to carry.
        else -> BugReportRequest(location = null, stops = emptyList(), distanceMeters = emptyMap())
    }

/**
 * Persists the bug-report "don't ask again" opt-out, guarded. A failed DataStore write is a lost
 * preference, not a crash: consent is simply asked again next time (honest, not a blank — SPEC
 * principle 2), so it is logged sanitized and swallowed rather than propagated out of the UI
 * coroutine (Codex P2 on #86 / *Error handling*). Cancellation rethrows first.
 */
/** The route page's journey tip, once closed this process (whether or not its dismissal was saved). */
internal object JourneyTipSession {
    var closed by mutableStateOf(false)
}

internal suspend fun persistBugReportOptOut(settings: AppSettings) {
    try {
        settings.setSkipBugReportConsent(true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StopdashDebugLog.warning("bug report: consent opt-out not saved: %s", e::class.simpleName)
    }
}

/**
 * An activity-scoped holder of the per-nearby-set [ViewModelStore]s, so the departures
 * [MainViewModel] for the current set survives a configuration change (a rotation reuses the
 * same store rather than rebuilding it and re-fetching), while a set the user has moved away
 * from is cleared — cancelling its in-flight fetch — rather than piling up (Codex, PR #43).
 *
 * Being a [ViewModel] is what buys the config-change survival: the activity keeps the same
 * instance across recreation. [ownerFor] returns the store for [key] (creating it once) and
 * clears every other key, since only one nearby set is shown at a time; [onCleared] clears
 * them all when the activity is finished for good.
 */
/** The activity's one [WriteFailures], retained across rotation and shared by every departures model. */
internal class WriteFailuresHolder : androidx.lifecycle.ViewModel() {
    val failures = WriteFailures()
}

/** The last nearby re-pick a trip's model was reconciled to, retained beside that model. */
internal class TakenRepick(var id: Long?) : androidx.lifecycle.ViewModel()

internal class NearbyDeparturesStores : androidx.lifecycle.ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    /**
     * The retained store for [key], clearing any other set's store first.
     *
     * With [defaults] (the activity), the owner also carries the activity's creation extras, so a
     * model made inside it can take a [androidx.lifecycle.SavedStateHandle]: a bare store has no
     * saved-state registry, and `createSavedStateHandle()` throws — which crashed To… from a
     * searched station, whose To search lives inside that station's store.
     */
    fun ownerFor(key: String, defaults: HasDefaultViewModelProviderFactory? = null): ViewModelStoreOwner {
        val stale = stores.keys.filter { it != key }
        for (k in stale) stores.remove(k)?.clear()
        val store = stores.getOrPut(key) { ViewModelStore() }
        if (defaults == null) {
            return object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = store
            override val defaultViewModelProviderFactory get() = defaults.defaultViewModelProviderFactory
            override val defaultViewModelCreationExtras get() = defaults.defaultViewModelCreationExtras
        }
    }

    /**
     * Drop every retained per-set store, canceling any in-flight departures fetch. Called when
     * the departures view is replaced by the location gate (a failed/empty re-locate, or a
     * retry): otherwise a recovery to the same stop IDs would reuse the retained [MainViewModel],
     * whose `init` doesn't re-run (and a foreground return only re-locates when already Ready), so
     * the pre-gate departures would return without the re-fetch the user asked for, stale until
     * the next auto-refresh (Codex).
     */
    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }

    override fun onCleared() = clearAll()
}

/** What the nearby gate should do for the current location-permission state (see [nearbyPermissionAction]). */
internal enum class NearbyPermissionAction { LOCATE, REQUEST_PRECISE, WAIT }

/**
 * The nearby gate's action for a held (or absent) location permission, from the three facts the
 * runtime exposes: whether precise (FINE) is granted, whether *any* location permission is
 * granted, and whether the precise request has already been shown once (persisted).
 *
 * Extracted pure so the coarse-only-upgrade path is unit-testable off a device — the reported
 * bug was an install predating FINE keeping a live coarse grant, which the manifest change does
 * not upgrade, so it must be offered precise exactly once (AGENTS testing rule: a bug fix gets a
 * regression test). The [MainActivity] `LaunchedEffect` only supplies the three booleans and
 * carries out the returned action.
 *
 * - **precise held** → [LOCATE]: the accurate fix is available.
 * - **coarse held, precise already prompted** → [LOCATE]: the user's approximate choice stands;
 *   re-prompting every open would nag.
 * - **coarse held, precise never prompted** → [REQUEST_PRECISE]: the upgrade case — offer precise
 *   once rather than silently keeping the inaccurate coarse fix.
 * - **no location permission** → [WAIT]: the gate shows its Allow button; nothing auto-fires.
 */
internal fun nearbyPermissionAction(
    hasFine: Boolean,
    hasAnyLocation: Boolean,
    precisePrompted: Boolean,
): NearbyPermissionAction = when {
    hasFine -> NearbyPermissionAction.LOCATE
    hasAnyLocation && precisePrompted -> NearbyPermissionAction.LOCATE
    hasAnyLocation -> NearbyPermissionAction.REQUEST_PRECISE
    else -> NearbyPermissionAction.WAIT
}

/**
 * The app's composition root: the theme plus a single full-size themed [Surface]. A screen
 * without its own background — the location gate is a bare `Column`; only `MainScreen` brings
 * a `Scaffold` — then paints on `colorScheme.surface` and inherits `onSurface` as its content
 * color. Without the Surface the gate rendered over the raw window background with a black
 * default content color, unreadable in dark mode (charcoal ground, black title).
 *
 * Extracted from `onCreate` so the wrapper is unit-testable: `StopDashAppRootTest` asserts the
 * content color inside it is `onSurface`, which fails if the Surface is dropped — the existing
 * `LocationGateScreenshotTest` can't catch that, since it installs its own Surface.
 */
@Composable
internal fun StopDashAppRoot(content: @Composable () -> Unit) {
    StopDashTheme {
        Surface(modifier = Modifier.fillMaxSize()) { ProvideDistanceSystem(content) }
    }
}

/**
 * The near-me area's overlay host. [aboveOverlay] composes **unconditionally** — regardless of
 * whether a Settings/Licenses overlay is open — while [overlayContent] (the open overlay) and [body]
 * (the location gate or the departures view) switch on [overlayOpen]. Production puts the
 * foreground-return observer ([ForegroundReturnLatcher]) in [aboveOverlay] so a return that lands
 * while an overlay is open is still observed and latched — the departures view that consumes the
 * latch is out of composition then (#136). Extracted as the real host so [ForegroundReturnTest] can
 * render it and pin that the above-overlay slot survives the overlay, rather than reconstructing the
 * topology in the test.
 */
@Composable
internal fun NearbyArea(
    overlayOpen: Boolean,
    aboveOverlay: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    aboveOverlay()
    if (overlayOpen) overlayContent() else body()
}

/**
 * Holds the one bit "a background→foreground return is pending re-location," retained across a
 * configuration change but reset on process recreation. A rotation while a Settings/Licenses overlay
 * is still open must not drop a latched return — the recreated observer skips its first foreground,
 * so the reopened departures view would show the pre-move set. A plain `remember` drops it on
 * rotation; `rememberSaveable` would wrongly carry it through process death, where the ViewModel
 * init's own reload already covers the return. A retained ViewModel is exactly "survive a config
 * change, die with the process" (Codex).
 */
internal class ForegroundReturnLatch : androidx.lifecycle.ViewModel() {
    var pending by mutableStateOf(false)
}

/**
 * Observes the activity lifecycle for a genuine background→foreground return and, when the near-me
 * set is already [isReady], calls [onReturn] to latch a pending re-location (SPEC *Finding stops* /
 * D6). Hosted **above** the Settings/Licenses overlay switch so a return that lands while an overlay
 * is open is still seen — the departures view that consumes the latch is out of composition then, so
 * an observer hosted there would miss the return entirely. The first foreground per activity instance
 * is skipped (the ViewModel init covers it, and a rotation restarts this with its own skip), and
 * [isBusy] gates a return that lands mid-relocate. Extracted (with [ConsumeForegroundReturn]) so the
 * observer/overlay/consume wiring is exercised by a test rather than restated — a regression that
 * moved this back inside the departures view leaves the latch unset while an overlay is open.
 */
@Composable
internal fun ForegroundReturnLatcher(
    isReady: () -> Boolean,
    isBusy: () -> Boolean,
    onReturn: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read the latest lambdas without re-keying the effect (a fresh lambda each recomposition would
    // restart it and reset the first-foreground skip).
    val currentIsReady = rememberUpdatedState(isReady)
    val currentIsBusy = rememberUpdatedState(isBusy)
    val currentOnReturn = rememberUpdatedState(onReturn)
    LaunchedEffect(lifecycleOwner) {
        refreshOnForeground(lifecycleOwner.lifecycle, isBusy = { currentIsBusy.value() }) {
            if (currentIsReady.value()) currentOnReturn.value()
        }
    }
}

/**
 * Consumes a latched foreground return: when [pending] flips true, re-locate via [onRelocate] (unless
 * [isBusy] — an in-flight relocate already covers it) and clear the latch via [onConsumed]. Composed
 * inside the departures view, so a latch set by [ForegroundReturnLatcher] while this view was out of
 * composition (an overlay) fires the moment it re-enters. [onRelocate] is read latest so a moved-to
 * set's fresh action is used.
 */
@Composable
internal fun ConsumeForegroundReturn(
    pending: Boolean,
    isBusy: () -> Boolean,
    onConsumed: () -> Unit,
    onRelocate: () -> Unit,
) {
    val currentIsBusy = rememberUpdatedState(isBusy)
    val currentOnRelocate = rememberUpdatedState(onRelocate)
    LaunchedEffect(pending) {
        if (pending) {
            if (!currentIsBusy.value()) currentOnRelocate.value()
            onConsumed()
        }
    }
}

/**
 * The re-locate action shared by the refresh control and the foreground return (SPEC *Finding
 * stops*): cancel any in-flight departures fetch first — so it can't finish and re-stamp the old
 * set as fresh during the fix window (Codex) — then force a fresh fix that re-resolves the nearby
 * set and [reconcile]s the confirmed same set in place. Extracted so the wiring that a refresh and
 * a reopen **re-locate** — rather than a departures-only [MainViewModel.refresh] — is pinned by a
 * unit test: a regression back to a location-free refresh would neither cancel first nor re-resolve.
 */
internal fun relocateAction(
    cancelFetch: () -> Unit,
    relocate: (onSameSet: (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit,
    reconcile: (NearbyStopsViewModel.State.Ready) -> Unit,
): () -> Unit = {
    cancelFetch()
    relocate(reconcile)
}

/**
 * Runs [onForeground] each time [lifecycle] re-enters STARTED **except the first**: the
 * first foreground is the initial load (done in the ViewModel's `init`, and re-run on a
 * fresh activity after process death), and a configuration change restarts this with its
 * own first-skip, so neither path double-fetches while a genuine return from the
 * background does refresh (SPEC D6). Extracted so the skip-first/return-again rule is
 * unit-testable off a device.
 */
internal suspend fun refreshOnForeground(
    lifecycle: Lifecycle,
    isBusy: () -> Boolean = { false },
    onForeground: () -> Unit,
) {
    var firstForeground = true
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (firstForeground) firstForeground = false else if (!isBusy()) onForeground()
    }
}

/**
 * Re-fetches on a fixed cadence while the screen is on, so an always-open surface — a
 * kiosk, or a phone left on the departures screen — keeps its predictions live without a
 * manual pull (SPEC D5/D6). A failed tick keeps the last-good departures and surfaces a
 * "couldn't refresh" banner rather than blanking (handled in [MainViewModel]); once truly
 * stale the per-row countdowns withhold. Gated on the RESUMED lifecycle so a backgrounded
 * screen isn't woken for nothing (battery); the [delay] runs *before* the first tick, so a
 * return to the foreground doesn't double-fetch with [refreshOnForeground]. Extracted so
 * the cadence is unit-testable off a device.
 */
internal suspend fun autoRefresh(
    lifecycle: Lifecycle,
    intervalMillis: Long = AUTO_REFRESH_MILLIS,
    isRefreshing: () -> Boolean = { false },
    onTick: () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            delay(intervalMillis)
            // Skip a tick while a refresh is still running. refresh() cancels the in-flight
            // fetch, so ticking during a slow refresh — several TfL requests timing out past
            // one interval — would repeatedly cancel it, starving a cold load at Loading or
            // keeping an aged screen from ever reaching its "couldn't refresh" state (Codex).
            // A skipped tick simply waits for the next interval, by which point the refresh
            // has settled and its result (fresh, or the failure banner) is on screen.
            if (!isRefreshing()) onTick()
        }
    }
}

/**
 * How often the on-screen view re-fetches (SPEC D5). One minute keeps TfL predictions
 * (which update roughly every ~30 s) fresh enough for a glance surface while staying a tiny
 * fraction of the keyless per-IP rate budget — the intended targets are home users and a
 * kiosk display, where the request volume is low. Reversible: one constant, pinned by
 * [app.stopdash.AutoRefreshTest].
 */
internal const val AUTO_REFRESH_MILLIS = 60_000L

/**
 * Drives [autoRefresh] from the activity's lifecycle. [relocating] joins the busy gate so the
 * one-minute tick doesn't fire `refresh()` on the current (soon-to-be-previous) set while a
 * manual re-locate's fix is still in flight — which would fetch and re-stamp the old location's
 * departures in parallel with the fix, exactly the parallel-fetch the manual path already avoids
 * (Codex). Once the relocate resolves the same-set refresh (or a new set's own fetch) takes over.
 */
@Composable
private fun AutoRefresh(viewModel: MainViewModel, relocating: StateFlow<Boolean>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed on [viewModel] too: a moved-to relocate replaces the per-set model under this
    // still-composed effect, and a lifecycle-only key would leave the timer ticking the cleared
    // old model while the new set never auto-refreshed and went stale (Codex).
    LaunchedEffect(lifecycleOwner, viewModel) {
        autoRefresh(
            lifecycleOwner.lifecycle,
            isRefreshing = { viewModel.refreshing.value || relocating.value },
        ) { viewModel.refresh(automatic = true) }
    }
}

/**
 * A clock that advances on screen so countdowns and the freshness stamp recompute
 * without a new fetch (SPEC D4). Ten seconds is enough to keep "N min"/"0 min" honest
 * while staying off a per-frame recomposition. The tick is gated on the RESUMED
 * lifecycle so a backgrounded screen isn't woken every 10 s for nothing (battery).
 */
@Composable
private fun tickingNow(): Instant {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by androidx.compose.runtime.produceState(initialValue = Instant.now(), lifecycleOwner) {
        val scope = this
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                scope.value = Instant.now()
                delay(10_000)
            }
        }
    }
    return now
}

/**
 * The production sink for the location seam's warnings: coarse messages carrying no
 * coordinate or key (SPEC *Privacy*), so a diagnosis of a misfiring fix isn't discarded in
 * the shipped app. Routes to [StopdashDebugLog] — the shared diagnostic log, which fans out
 * to Logcat and the on-device persisted file (`docs/PRIVACY.md`). It never leaves the device;
 * a user-shareable export with travel data redacted is a later change.
 *
 * Top-level, not an Activity method: a `MainActivity::` method reference is held by the
 * ViewModels it's passed to, and an activity-scoped ViewModel outlives the Activity across
 * configuration changes — so a bound reference would pin each destroyed Activity in the
 * ViewModel store (Codex). A top-level function captures nothing.
 */
private fun logLocationWarning(message: String) = StopdashDebugLog.warning("location: %s", message)

/**
 * The last few positions the rider's fixes and lookups placed them at (SPEC *Privacy*): in memory
 * only, a short window for the consent-gated bug report, never the diagnostic log or its file.
 */
private val recentPositions = RecentPositions()

private val positionStamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ROOT)

private val positionExpiry by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

// Sweeps for expired positions at the oldest one's 15-minute deadline (capped at a minute, since a
// delayed message doesn't count deep sleep), measuring age by elapsed realtime — so a position goes
// at its deadline while the phone is awake, and within a minute of it waking otherwise, even if
// nothing reads the window again. Stops itself when the window is empty.
private val positionSweep: Runnable = object : Runnable {
    override fun run() {
        val now = SystemClock.elapsedRealtime()
        recentPositions.expire(now)
        scheduleSweep(now)
    }
}

private fun scheduleSweep(nowElapsedMillis: Long) {
    positionExpiry.removeCallbacks(positionSweep)
    val until = recentPositions.untilNextExpiry(nowElapsedMillis) ?: return
    positionExpiry.postDelayed(positionSweep, minOf(until, RecentPositions.SWEEP_MILLIS))
}

private fun recordPosition(what: String, at: Coordinates) {
    val now = SystemClock.elapsedRealtime()
    recentPositions.record(what, at, now, java.time.LocalTime.now().format(positionStamp))
    scheduleSweep(now)
}

private fun logRouteStopsWarning(message: String) = StopdashDebugLog.warning("route stops: %s", message)

/**
 * The process-wide nearby-lookup cache: top-level so it outlives an Activity or ViewModel (a
 * rotation, a relocation), backed by a file in the app's cache directory (never backed up) so it
 * also survives the process. Built on first use; it reads the file lazily, on the IO lookup path.
 */
private val nearbyStopsCacheLock = Any()
private var nearbyStopsCacheInstance: NearbyStopsCache? = null

/** The in-memory cache of searched stations' surroundings (From…), apart from the rider's own. */
private val stationAreaStopsCache = NearbyStopsCache()

private fun nearbyStopsCache(context: Context): NearbyStopsCache = synchronized(nearbyStopsCacheLock) {
    nearbyStopsCacheInstance ?: NearbyStopsCache(
        FileNearbyStopsStore(File(context.applicationContext.cacheDir, "nearby-stops.json"), warn = ::logLocationWarning),
    ).also { nearbyStopsCacheInstance = it }
}

/**
 * Each station search's recent picks ([RecentSearches]: *From…*'s and *To…*'s apart), process-wide,
 * backed by files in the app's no-backup directory, so each list survives a restart but never
 * leaves the device.
 */
private val recentSearchesLock = Any()
private var recentSearchesInstance: RecentSearches? = null

private fun recentSearches(context: Context): RecentSearches = synchronized(recentSearchesLock) {
    recentSearchesInstance ?: RecentSearches(context.applicationContext.noBackupFilesDir, warn = ::logDepartureWarning)
        .also { recentSearchesInstance = it }
}

/**
 * The place behind each starred row's stop, recorded on each toggle ([rememberStarredPlace]) so a
 * star is listed by name in "Find a station" after its stop has left the caches: process-wide,
 * backed by a file in the app's no-backup directory, never leaving the device.
 */
private val starredPlacesLock = Any()
private var starredPlacesInstance: FileStarredPlacesStore? = null

// Serializes each read of the star set with the places write it drives, so two quick toggles (or a
// toggle and Find opening) can't apply an older star set last.
private val starredPlacesMutex = Mutex()

private fun starredPlacesStore(context: Context): FileStarredPlacesStore = synchronized(starredPlacesLock) {
    starredPlacesInstance ?: FileStarredPlacesStore(
        File(context.applicationContext.noBackupFilesDir, "starred-places.json"),
        warn = ::logStarWarning,
    ).also { starredPlacesInstance = it }
}

/**
 * After a star toggle, record [row]'s place if its stop now holds a star, and forget every stop
 * that doesn't — at once, so an unstarred stop's place isn't kept until Find next opens.
 */
private suspend fun rememberStarredPlace(context: Context, row: DepartureRow) = starredPlacesMutex.withLock {
    val starred = try {
        DataStoreStarredRowsStore.from(context, warn = ::logStarWarning).starred().first() as? StarredRowSet.Loaded
    } catch (e: IOException) {
        logStarWarning("starred places not updated: ${e::class.simpleName}")
        null
    } ?: return@withLock
    val stopIds = starred.starred.mapTo(HashSet()) { it.stopId }
    val place = stopPlace(row.stopId, row.stopName, row.clusterId, listOf(row.mode))
    starredPlacesStore(context).reconcile(stopIds, mapOf(row.stopId to place))
}

/**
 * The user's own stops for a station search (SPEC *Finding stops*), all read from the device: the
 * starred journeys' ends; the places holding a starred row; that search's own recent picks
 * ([recents]: *From…*'s opens or *To…*'s destinations); and every place the
 * app has lately shown (the widget's snapshot and the nearby-lookup cache), so they match with no
 * TfL search. A source that can't be read is logged by kind alone and left out. Call off the main
 * thread.
 */
private suspend fun loadYourStops(context: Context, recents: FileRecentStationsStore): YourStops {
    val journeys = readOrEmpty("starred journeys") {
        DataStoreStarredJourneysStore.from(context, warn = ::logStarWarning).journeys().first().orEmpty()
    }
    val shown = readOrEmpty("snapshot") {
        DataStoreSnapshotStore.from(context, warn = ::logWidgetSnapshotWarning).load()?.stops.orEmpty()
            .map { it.stopId to stopPlace(it.stopId, it.stopName, it.clusterId, it.lines.map { line -> line.mode }) }
    }
    val nearby = nearbyStopsCache(context).recentStops()
        .map { it.id to stopPlace(it.id, it.name, it.clusterId, it.lines.map { line -> line.mode }) }
    val known = nearby + shown
    val placeOf = known.toMap()
    // A starred stop's place: as lately shown, else as recorded when it was starred. What's shown is
    // recorded too (a star from before places were recorded), and unstarred stops are forgotten —
    // under the same lock as a toggle's update, reading the star set inside it.
    val (starred, unnamed) = starredPlacesMutex.withLock {
        // Null when the starred set couldn't be read, so the recorded places aren't pruned against it.
        val starredStopIds = try {
            (DataStoreStarredRowsStore.from(context, warn = ::logStarWarning).starred().first() as? StarredRowSet.Loaded)
                ?.starred?.mapTo(HashSet()) { it.stopId }
        } catch (e: IOException) {
            logDepartureWarning("find a station: starred rows unreadable (${e::class.simpleName})")
            null
        }
        val places = starredPlacesStore(context)
        val recorded = places.load()
        val resolved = starredStopIds.orEmpty().mapNotNull { id -> (placeOf[id] ?: recorded[id])?.let { id to it } }
        starredStopIds?.let { ids -> places.reconcile(ids, resolved.toMap()) }
        resolved to starredStopIds.orEmpty().filter { it !in placeOf && it !in recorded }.sorted()
    }
    // A journey starred before its ends' stop areas were saved takes them from what's been shown.
    fun JourneyEnd.withArea() = if (areaId.isNotBlank()) this else copy(areaId = placeOf[stopId]?.id.orEmpty())
    return YourStops.of(
        journeys = journeys.map { it.copy(from = it.from.withArea(), to = it.to.withArea()) },
        starred = starred.map { it.second }.distinctBy { it.id },
        recent = recents.load(),
        known = known.map { it.second },
        unnamedStarred = unnamed,
    )
}

private inline fun <T> readOrEmpty(what: String, read: () -> List<T>): List<T> =
    try {
        read()
    } catch (e: IOException) {
        logDepartureWarning("find a station: $what unreadable (${e::class.simpleName})")
        emptyList()
    }

/**
 * The production sink for the departures/disruption seam's warnings. Without it wired,
 * `MainViewModel`'s `warn` defaulted to a no-op, so a persistent "couldn't check for
 * disruptions" left nothing in logcat to explain which line or lookup was unknown. The
 * messages are coarse — a count, a line id, an HTTP reason — with no coordinate, stop-set,
 * or key (SPEC *Privacy*: line ids are allowed). Routes to [StopdashDebugLog] like
 * [logLocationWarning], top-level for the same no-Activity-capture reason.
 */
private fun logDepartureWarning(message: String) = StopdashDebugLog.warning("departures: %s", message)

/**
 * The production sink for the starred-rows store's warnings — a discarded corrupt star file,
 * or a preserved newer-schema file. Without it wired the store defaulted to a no-op, so those
 * recovery paths left nothing in logcat. The messages are coarse facts (no stop/line id is
 * needed and none is logged); routed to [StopdashDebugLog] like [logLocationWarning], for the
 * same no-Activity-capture reason.
 */
private fun logStarWarning(message: String) = StopdashDebugLog.warning("stars: %s", message)

/**
 * The production sink for the Play update checker's warnings — a failed availability fetch.
 * Coarse and PII-free (an exception class name, no user data); routed to [StopdashDebugLog]
 * like [logLocationWarning], for the same no-Activity-capture reason.
 */
private fun logUpdateWarning(message: String) = StopdashDebugLog.warning("update: %s", message)

/** One read of the saved journeys: [journeys] is null when the store couldn't be read. */
private class JourneysRead(val journeys: List<StarredJourney>?)

/**
 * The departures model for an opened farther station's card (SPEC *Finding stops → Farther
 * stations*), built as a From… page's station's is: only its [stops], never saved or on the widget,
 * with stars and dismissals shared with every other list.
 */
private fun fartherCardModel(
    client: TflClient,
    appContext: Context,
    stops: List<StopRef>,
    distanceMeters: Map<String, Double>,
    writeFailures: WriteFailures,
) = MainViewModel(
    client = client,
    departureSourceChanges = RailApiKeySetting.changes,
    seedStops = stops,
    stopDistanceMeters = distanceMeters,
    farArrivalsReuse = FAR_ARRIVALS_REUSE,
    starredStore = DataStoreStarredRowsStore.from(appContext, warn = ::logStarWarning),
    dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
    warn = ::logDepartureWarning,
    arrivalsReuse = ARRIVALS_REUSE,
    sharedArrivals = ArrivalsCache.SHARED,
    disruptionReuse = DISRUPTION_REUSE,
    lineStatusReuse = LINE_STATUS_REUSE,
    rateWaitMillis = { SharedTflRateLimiter.waitedMillis },
    logStats = ::logDepartureWarning,
    // Not the widget's list: the near-me model keeps the journey pins.
    ownsWidgetJourneys = false,
    writeFailures = writeFailures,
    onStarToggled = { row -> rememberStarredPlace(appContext, row) },
)
