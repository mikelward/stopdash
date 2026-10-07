package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.DEFAULT_FONT_SCALE
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.ModeGroups
import app.stopdash.domain.StepFree
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.MAX_FONT_SCALE
import app.stopdash.domain.MIN_FONT_SCALE
import app.stopdash.domain.fontScalePercent

/**
 * The Settings screen, hosted at the activity top level (like [LicensesScreen]) so it is
 * reachable from every state via the overflow menu, and rendered as an overlay whose own Back
 * closes it. Its first row opens the favorite-places editor (SPEC D9).
 *
 * Kept UI-only: it reflects [liveWidgetRefresh] and reports a change through
 * [onLiveWidgetRefreshChange]; persistence (the settings store) and the refresh scheduler
 * (WorkManager) are wired by the caller, so this composable stays JVM/Robolectric-renderable
 * for the screenshot test without touching Android services.
 */
@Composable
fun SettingsScreen(
    liveWidgetRefresh: Boolean,
    onLiveWidgetRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    liveWidgetRefreshFailed: Boolean = false,
    onDismissLiveWidgetRefreshError: () -> Unit = {},
    // False while the persisted setting hasn't been read yet (a slow or persistently-failing
    // DataStore read leaves the flow silent): the control is disabled so the user can't act on a
    // value that may not reflect the stored choice — showing it off-and-tappable would let a
    // previously-enabled install read as off (Codex P2 on #56).
    liveWidgetRefreshEnabled: Boolean = true,
    // The user's saved TfL app_key (empty when keyless — the default), and a report of a new value
    // to save. UI-only like the rest of the screen: persistence and the request clients are the
    // caller's job, so this stays Robolectric-renderable with no store and no network (SPEC D7).
    userApiKey: String = "",
    onUserApiKeyChange: (String) -> Unit = {},
    // False while the stored key hasn't been read yet (a slow or persistently-failing DataStore
    // read leaves the flow silent): the field is disabled so the user can't edit over a value that
    // may not reflect what's stored — a key arriving mid-edit would otherwise reset the field
    // (Codex P2, mirroring the live-widget switch). The key's own null (keyless) is a loaded state.
    userApiKeyLoaded: Boolean = true,
    // The user's saved Rail Data Marketplace key for National Rail times (empty when none), a report
    // of a new value, and whether the stored one has been read: as for the TfL key above.
    railApiKey: String = "",
    onRailApiKeyChange: (String) -> Unit = {},
    railApiKeyLoaded: Boolean = true,
    // The "Help make StopDash better" opt-in (SPEC *Privacy*): off by default; null until the stored
    // choice is read, when the switch is disabled so a slow read can't present "off" to act on.
    telemetryOptIn: Boolean? = false,
    onTelemetryOptInChange: (Boolean) -> Unit = {},
    // The distance-units choice (SPEC *Finding stops*) and a report of a new one. Disabled until the
    // stored choice is read, so a cold start's default can't be shown as the choice, or tapped over
    // it and overwrite it. [distanceUnitsWriteFailed] says a choice didn't save.
    distanceUnits: DistanceUnits = DistanceUnits.AUTOMATIC,
    onDistanceUnitsChange: (DistanceUnits) -> Unit = {},
    distanceUnitsLoaded: Boolean = true,
    distanceUnitsWriteFailed: Boolean = false,
    onDismissDistanceUnitsError: () -> Unit = {},
    // How fast the rider walks, for a trip's walks, as [distanceUnits] is handled: held until read.
    walkingSpeed: WalkingSpeed = WalkingSpeed.AVERAGE,
    onWalkingSpeedChange: (WalkingSpeed) -> Unit = {},
    walkingSpeedLoaded: Boolean = true,
    walkingSpeedWriteFailed: Boolean = false,
    onDismissWalkingSpeedError: () -> Unit = {},
    // The longest walk a trip's routes may take, as [walkingSpeed] is handled: held until read.
    maxWalk: MaxWalk = MaxWalk.DEFAULT,
    onMaxWalkChange: (MaxWalk) -> Unit = {},
    maxWalkLoaded: Boolean = true,
    maxWalkWriteFailed: Boolean = false,
    onDismissMaxWalkError: () -> Unit = {},
    // How step-free a trip's routes must be, as [maxWalk] is handled: held until read.
    stepFree: StepFree = StepFree.DEFAULT,
    onStepFreeChange: (StepFree) -> Unit = {},
    stepFreeLoaded: Boolean = true,
    stepFreeWriteFailed: Boolean = false,
    onDismissStepFreeError: () -> Unit = {},
    // Opens the favorite-places editor (SPEC D9), hosted as its own overlay by the caller.
    onOpenFavoritePlaces: () -> Unit = {},
    // Opens the favorite-journeys list (SPEC *Journeys*), hosted as its own overlay by the caller.
    onOpenFavoriteJourneys: () -> Unit = {},
    // Whether the home screen shows its disruptions row (maintainer, 2026-10-05), as [distanceUnits] is
    // handled: held until read, a choice that didn't save said here until dismissed.
    showDisruptionsRow: Boolean = true,
    onShowDisruptionsRowChange: (Boolean) -> Unit = {},
    showDisruptionsRowLoaded: Boolean = true,
    showDisruptionsRowWriteFailed: Boolean = false,
    onDismissShowDisruptionsRowError: () -> Unit = {},
    // The lines the row always covers, on top of the lines near the rider ([HomeLines.PICKER]; none by
    // default), handled as [showDisruptionsRow] is. A tap names its line ([onToggleSummaryLine]).
    summaryNetworks: Set<String> = HomeLines.DEFAULT_NETWORKS,
    onToggleSummaryLine: (String) -> Unit = {},
    summaryNetworksLoaded: Boolean = true,
    summaryNetworksWriteFailed: Boolean = false,
    onDismissSummaryNetworksError: () -> Unit = {},
    // The modes and lines hidden from the near-me list (SPEC *Finding stops → Hiding a mode*), listed
    // while any are, each with a Show that brings back just that one ([onShowHidden]).
    hiddenModes: Set<String> = emptySet(),
    onShowHidden: (ModeGroups.Group) -> Unit = {},
    // A Show that didn't save: the item is back for now but hidden again after a restart, so the
    // list says so here, as the other settings do, until dismissed.
    hiddenWriteFailed: Boolean = false,
    onDismissHiddenError: () -> Unit = {},
    // The lines trips avoid ([AvoidedLines]; SPEC *Trips with a change → Avoiding a line*), listed
    // while any are, each with a Remove that stops avoiding just that one ([onStopAvoiding]). A
    // Remove that didn't save is said here until dismissed, as a Show is.
    avoidedLines: Set<String> = emptySet(),
    onStopAvoiding: (String) -> Unit = {},
    avoidedWriteFailed: Boolean = false,
    onDismissAvoidedError: () -> Unit = {},
    // Opens StopDash's Play Store page on a connected watch that doesn't have it (SPEC *Wear OS*),
    // the near-me card's offer kept here for good; null (no such watch) shows no row.
    onInstallOnWatch: (() -> Unit)? = null,
    // Opened for the disruptions summary (the lines page's menu): its page is open over Settings from
    // the first frame, and its Back goes up to Settings, never straight out.
    startOnDisruptions: Boolean = false,
    // Its page closed, back up to Settings: the caller drops [startOnDisruptions], so Settings composed
    // afresh (back from a page opened over it) opens at the top, not on that page again (Codex, #607).
    onDisruptionsClosed: () -> Unit = {},
    // Favorite places opened from the Disruptions summary page, whose lines it includes: back from them
    // returns to that page, not Settings' top.
    onOpenFavoritePlacesFromDisruptions: () -> Unit = onOpenFavoritePlaces,
) {
    // Counts the overflow's openings: each re-masks both keys ([ApiKeyRow]) before "Send bug report"
    // can be picked, since the report's screenshot is of this screen and a revealed key would be
    // in it in plain text (Codex on #377). A credential is never one of the report's disclosures.
    var menuOpens by remember { mutableIntStateOf(0) }
    // The disruptions summary's own page, over this one; its Back returns here. Open from the start
    // when Settings is opened for it (the lines page's menu), so Back from it goes up to Settings.
    var disruptionsOpen by rememberSaveable(startOnDisruptions) { mutableStateOf(startOnDisruptions) }
    BackHandler(onBack = onBack)
    Box(modifier = Modifier.fillMaxSize()) {
    // Off the screen while the page is open, so neither TalkBack nor a keyboard reaches the covered
    // controls (Codex, #592); its state (the scroll, a key being typed) kept for its return.
    val saved = rememberSaveableStateHolder()
    if (!disruptionsOpen) saved.SaveableStateProvider("settings") {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // Title with a Back button at the end, matching the licenses screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The title takes what Back and the overflow leave, wrapping at a large text size
                // rather than squeezing them off the row.
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    AppMenuOverflow(onOpen = { menuOpens++ })
                }
            }
            // The rows scroll under the fixed header, so a large text size (up to 160%) stacked on
            // a large Android font scale can't push the lower controls off a short screen where
            // they'd be unreachable (Codex).
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.surface))
                    .verticalScroll(scrollState),
            ) {
                // Text size (display scaling, SPEC *Display size*): a slider that mirrors — and
                // moves — the same size a two-finger pinch changes, plus the switch that gates the
                // pinch. Read from the theme's shared handle so the slider, the pinch, and the app
                // all move one value; absent only outside the theme (a bare test render), where the
                // section hides.
                // Favorite places leads the list — the everyday setting a rider reaches for most.
                SettingNavRow(
                    title = stringResource(R.string.settings_favorite_places_title),
                    summary = stringResource(R.string.settings_favorite_places_summary),
                    onClick = onOpenFavoritePlaces,
                    testTag = "favoritePlacesRow",
                )
                // The home screen's disruptions row, second (maintainer, 2026-10-05): like the places, it
                // decides what the home screen leads with. Its settings are its own page, so none reads as
                // the app's at large (maintainer, 2026-10-05).
                SettingNavRow(
                    title = stringResource(R.string.settings_disruptions_row_title),
                    // Until both stored choices are read, neither "Off" nor a default that may not be the rider's
                    // (Codex, #592): a dash, as the trip pickers show.
                    summary = if (showDisruptionsRowLoaded && summaryNetworksLoaded) {
                        disruptionsSummary(showDisruptionsRow)
                    } else {
                        "–"
                    },
                    onClick = { disruptionsOpen = true },
                    testTag = "disruptionsSummaryRow",
                    // Its page opens once both are read, so its controls never jump as they land (Codex, #592).
                    enabled = showDisruptionsRowLoaded && summaryNetworksLoaded,
                )
                if (showDisruptionsRowWriteFailed || summaryNetworksWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.settings_disruptions_row_write_failed),
                        onDismiss = {
                            onDismissShowDisruptionsRowError()
                            onDismissSummaryNetworksError()
                        },
                    )
                }
                // The favorite journeys, third, after the disruptions switch
                // the maintainer placed second: what the rider has saved, like the places.
                SettingNavRow(
                    title = stringResource(R.string.settings_favorite_journeys_title),
                    summary = stringResource(R.string.settings_favorite_journeys_summary),
                    onClick = onOpenFavoriteJourneys,
                    testTag = "favoriteJourneysRow",
                )
                // What's hidden, under the places: like them, it decides what the list shows. Only
                // while something is, as with the list's banner.
                val hiddenItems = ModeGroups.hiddenItems(hiddenModes)
                if (hiddenItems.isNotEmpty()) HiddenRow(hiddenItems, onShowHidden)
                // Outside the list's own condition: the item that didn't save has already left it,
                // perhaps the last one.
                if (hiddenWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.hidden_modes_write_failed),
                        onDismiss = onDismissHiddenError,
                    )
                }
                // The lines trips avoid, under what's hidden: like it, only while there are any.
                val avoided = remember(avoidedLines) { AvoidedLines.labeled(avoidedLines) }
                if (avoided.isNotEmpty()) AvoidedRow(avoided, onStopAvoiding)
                if (avoidedWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.hidden_modes_write_failed),
                        onDismiss = onDismissAvoidedError,
                    )
                }
                val fontSize = LocalFontSizeState.current
                if (fontSize != null) {
                    TextSizeRow(
                        scale = fontSize.scale,
                        onPreview = fontSize::preview,
                        onSettled = { fontSize.commit(fontSize.scale) },
                        onReset = { fontSize.chooseScale(DEFAULT_FONT_SCALE) },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_pinch_title),
                        summary = stringResource(R.string.settings_pinch_summary),
                        checked = fontSize.pinchEnabled,
                        onCheckedChange = fontSize::choosePinch,
                        switchTestTag = "pinchSwitch",
                    )
                }
                DistanceUnitsRow(
                    selected = distanceUnits,
                    onSelect = onDistanceUnitsChange,
                    enabled = distanceUnitsLoaded,
                )
                if (distanceUnitsWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.settings_distance_units_write_failed),
                        onDismiss = onDismissDistanceUnitsError,
                    )
                }
                WalkingSpeedRow(
                    selected = walkingSpeed,
                    onSelect = onWalkingSpeedChange,
                    enabled = walkingSpeedLoaded,
                )
                if (walkingSpeedWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.settings_walking_speed_write_failed),
                        onDismiss = onDismissWalkingSpeedError,
                    )
                }
                // Six limits: the trip's one-row dropdown rather than six radio rows, which pushed
                // the rest of the screen well down.
                MaxWalkPicker(
                    maxWalk = maxWalk,
                    onChange = onMaxWalkChange,
                    tag = "maxWalkSetting",
                    enabled = maxWalkLoaded,
                )
                if (maxWalkWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.max_walk_write_failed),
                        onDismiss = onDismissMaxWalkError,
                    )
                }
                StepFreePicker(
                    stepFree = stepFree,
                    onChange = onStepFreeChange,
                    tag = "stepFreeSetting",
                    enabled = stepFreeLoaded,
                )
                if (stepFreeWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.step_free_write_failed),
                        onDismiss = onDismissStepFreeError,
                    )
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_live_widget_refresh_title),
                    summary = stringResource(R.string.settings_live_widget_refresh_summary),
                    checked = liveWidgetRefresh,
                    onCheckedChange = onLiveWidgetRefreshChange,
                    enabled = liveWidgetRefreshEnabled,
                    switchTestTag = "liveWidgetSwitch",
                )
                // Surfaced when applying the setting failed to schedule the refresh (a rare
                // DataStore or WorkManager error) — the choice is kept and self-heals, but the user
                // is told rather than left guessing (SPEC principle 2: fail visibly, not silently).
                if (liveWidgetRefreshFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.settings_live_widget_refresh_failed),
                        onDismiss = onDismissLiveWidgetRefreshError,
                    )
                }
                onInstallOnWatch?.let { install ->
                    SettingNavRow(
                        title = stringResource(R.string.settings_watch_install_title),
                        summary = stringResource(R.string.settings_watch_install_summary),
                        onClick = install,
                        testTag = "watchInstallRow",
                    )
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_telemetry_title),
                    summary = stringResource(R.string.settings_telemetry_summary),
                    checked = telemetryOptIn == true,
                    onCheckedChange = onTelemetryOptInChange,
                    enabled = telemetryOptIn != null,
                    switchTestTag = "telemetrySwitch",
                )
                // The optional user app_key (SPEC D7): keyless out of the box, a pasted key raises
                // the TfL request budget. Last because it's the advanced, rarely-touched control.
                ApiKeyRow(
                    apiKey = userApiKey,
                    loaded = userApiKeyLoaded,
                    onSave = onUserApiKeyChange,
                    title = stringResource(R.string.settings_api_key_title),
                    summary = stringResource(R.string.settings_api_key_summary),
                    tagPrefix = "apiKey",
                    maskedAt = menuOpens,
                )
                // The optional National Rail key (SPEC *National Rail*): without it, National Rail
                // lines say "No key"; with it, their live times from National Rail's own feed.
                ApiKeyRow(
                    apiKey = railApiKey,
                    loaded = railApiKeyLoaded,
                    onSave = onRailApiKeyChange,
                    title = stringResource(R.string.settings_rail_key_title),
                    summary = stringResource(R.string.settings_rail_key_summary),
                    tagPrefix = "railKey",
                    maskedAt = menuOpens,
                )
            }
        }
    }
    }
    if (disruptionsOpen) {
        DisruptionsSummaryPage(
            show = showDisruptionsRow,
            onShowChange = onShowDisruptionsRowChange,
            showLoaded = showDisruptionsRowLoaded,
            showWriteFailed = showDisruptionsRowWriteFailed,
            onDismissShowError = onDismissShowDisruptionsRowError,
            networks = summaryNetworks,
            onToggleLine = onToggleSummaryLine,
            networksLoaded = summaryNetworksLoaded,
            networksWriteFailed = summaryNetworksWriteFailed,
            onDismissNetworksError = onDismissSummaryNetworksError,
            onOpenFavoritePlaces = onOpenFavoritePlacesFromDisruptions,
            onBack = {
                disruptionsOpen = false
                onDisruptionsClosed()
            },
        )
    }
    }
}

/**
 * The optional TfL `app_key` control (SPEC D7): a title + one-line explanation, a paste field, and
 * a Save (with a Clear once a key is stored). StopDash works keyless; a user's own free key raises
 * the request budget ~50→~500 req/min.
 *
 * [apiKey] is the saved value; the field edits a local [draft] seeded from it, so typing doesn't
 * persist until Save — Save reports [draft], Clear reports an empty string (keyless). Re-seeding on
 * [apiKey] change keeps the field honest if the stored value changes underneath (e.g. a restore).
 *
 * While [loaded] is false the stored key hasn't been read yet, so the field and its actions are
 * disabled — the field must not be editable over a value that hasn't arrived, since a key landing
 * mid-edit would re-seed [draft] and discard the input (Codex P2). The key is the user's own
 * credential shown on their own screen; it is sent only with their own TfL requests, never logged,
 * and never placed in any other off-device artifact (SPEC *Privacy*).
 */
@Composable
private fun ApiKeyRow(
    apiKey: String,
    loaded: Boolean,
    onSave: (String) -> Unit,
    title: String,
    summary: String,
    // Prefixes the row's test tags ("apiKeyField", "railKeyField"), one row per key.
    tagPrefix: String,
    // A change masks the key again, in the same frame: the screen's overflow opening (see [SettingsScreen]).
    maskedAt: Int = 0,
) {
    // The editable text, saved across rotation (rememberSaveable) so an unsaved paste survives a
    // configuration change. NOT keyed on [apiKey]: keying it would re-seed the draft on any
    // saved-value change, discarding an in-progress edit — including a save's own async store echo
    // arriving while the user keeps typing, or a rotation's transient load (Codex). Instead the
    // draft follows a new saved value only when it isn't dirty (see below). The draft is the user's
    // own key in the on-device saved-state bundle, not an off-device channel (SPEC *Privacy*).
    var draft by rememberSaveable { mutableStateOf(apiKey) }
    // Whether the user has edited since the field last synced to the saved value. An explicit flag,
    // not a `draft == savedValue` comparison: comparison would wrongly count an edit *back to* the
    // old key as clean and let a save's echo overwrite it (Codex). Set on any keystroke; cleared
    // when we adopt a saved value or the user commits one (Save/Clear).
    var dirty by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(apiKey) {
        // Adopt a genuinely-changed saved value (a Save landed, or an external restore) only when
        // the user hasn't edited since — otherwise keep their in-progress edit, so a stale echo
        // can't overwrite it.
        if (!dirty) draft = apiKey
    }
    // Masked by default — a credential shouldn't sit in plain sight (shoulder-surfing, screen
    // recordings; Codex P2). A Show/Hide toggle still lets the user verify a paste. Not saveable:
    // it resets to hidden on every recomposition-from-scratch, which is the safe default.
    var revealed by remember(maskedAt) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                dirty = true
                // Emptying the field (delete-all or before a fresh paste) re-arms masking, so a
                // pasted replacement isn't shown in plaintext off the back of an earlier Show (Codex).
                if (it.isEmpty()) revealed = false
            },
            singleLine = true,
            enabled = loaded,
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            visualTransformation =
                if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
            // The reveal toggle appears only once there's something to reveal, so an empty field
            // stays uncluttered.
            trailingIcon = if (draft.isNotEmpty()) {
                {
                    TextButton(
                        onClick = { revealed = !revealed },
                        modifier = Modifier.testTag("${tagPrefix}Reveal"),
                    ) {
                        Text(
                            stringResource(
                                if (revealed) R.string.settings_api_key_hide
                                else R.string.settings_api_key_show,
                            ),
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}Field"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Clear appears only once a loaded key is stored — it returns to keyless in one tap
            // without making the user select-and-delete the field first. Hidden while unloaded,
            // where an empty [apiKey] doesn't yet mean keyless.
            if (loaded && apiKey.isNotEmpty()) {
                TextButton(
                    onClick = {
                        draft = ""
                        dirty = false // committing keyless — the field now matches the saved value
                        revealed = false // nothing to reveal; re-arm masking for a later paste
                        onSave("")
                    },
                    modifier = Modifier.testTag("${tagPrefix}Clear"),
                ) { Text(stringResource(R.string.settings_api_key_clear)) }
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(
                onClick = {
                    // Normalize before saving, and adopt it locally: the store normalizes a pasted
                    // key the same way (trim, blank → keyless), so a draft that differs from the
                    // saved value only by surrounding whitespace would persist to the same value,
                    // re-emit nothing, and leave Save stuck enabled (Codex). Trimming here disables
                    // Save at once and shows the user the value that was actually stored.
                    val normalized = draft.trim()
                    draft = normalized
                    // The field now matches the value being persisted — clean until the next edit.
                    dirty = false
                    onSave(normalized)
                },
                // Enabled only once loaded and the field's normalized value differs from the saved
                // (already-normalized) value, so Save is a no-op only when there's a real change.
                enabled = loaded && draft.trim() != apiKey,
                modifier = Modifier.testTag("${tagPrefix}Save"),
            ) { Text(stringResource(R.string.settings_api_key_save)) }
        }
    }
}

/**
 * A settings row that navigates to a sub-screen: a title + summary, the whole row tappable (the
 * fewer-larger-targets discipline). Used for the favorite-places editor (SPEC D9).
 */
@Composable
private fun SettingNavRow(title: String, summary: String, onClick: () -> Unit, testTag: String? = null, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The Hidden list (SPEC *Finding stops → Hiding a mode*): a title and what hiding does, over one row
 * per hidden group or line ([items], from [ModeGroups.hiddenItems]) with a Show that brings back just
 * that one ([onShow]); the list's "Show all" is the way to bring back everything at once.
 */
@Composable
private fun HiddenRow(items: List<ModeGroups.Group>, onShow: (ModeGroups.Group) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("hiddenList")) {
        Text(text = stringResource(R.string.settings_hidden_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.settings_hidden_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (item in items) {
            val name = hiddenItemName(item)
            val showDescription = stringResource(R.string.settings_hidden_show_description, name)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(
                    onClick = { onShow(item) },
                    modifier = Modifier.semantics { contentDescription = showDescription },
                ) { Text(stringResource(R.string.settings_hidden_show)) }
            }
        }
    }
}

/**
 * The Avoided lines list (SPEC *Trips with a change → Avoiding a line*): a title and what avoiding does,
 * over one row per line ([lines], each its entry and label, from [AvoidedLines.labeled]) with a Remove
 * that stops avoiding just that one ([onRemove]), as its chip atop a trip does.
 */
@Composable
private fun AvoidedRow(lines: List<Pair<String, String>>, onRemove: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("avoidedList")) {
        Text(text = stringResource(R.string.settings_avoided_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.settings_avoided_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for ((entry, label) in lines) {
            val removeDescription = stringResource(R.string.trip_stop_avoiding, label)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(
                    onClick = { onRemove(entry) },
                    modifier = Modifier.semantics { contentDescription = removeDescription },
                ) { Text(stringResource(R.string.settings_avoided_remove)) }
            }
        }
    }
}

/** A setting's failure notice ([text]) with a Dismiss action, shown under its control. */
@Composable
private fun SettingErrorRow(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/**
 * The text-size control: the title with the current percentage and a Reset, over a slider across
 * the offered range (SPEC *Display size*). It moves the same size a two-finger pinch does — both
 * write one value — so a user who can't pinch (or would rather not) has an equivalent control.
 *
 * The slider is hosted at the **unscaled** density ([StableInputDensity]): the size it drags
 * changes the density every frame, and Compose restarts a pointer handler when the density under
 * it changes, so a slider under the scaled density would die on its first resizing movement.
 */
@Composable
private fun TextSizeRow(
    scale: Float,
    onPreview: (Float) -> Unit,
    onSettled: () -> Unit,
    onReset: () -> Unit,
) {
    val percent = fontScalePercent(scale)
    val sliderDescription = stringResource(R.string.settings_text_size_content_description, percent)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Weighted so at a large text size (up to 160% over a large Android font scale) the
            // title wraps and yields width; the percentage and Reset are then measured at their
            // full size and stay reachable rather than being clipped off the row's end (Codex).
            Text(
                text = stringResource(R.string.settings_text_size_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_text_size_value, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onReset) { Text(stringResource(R.string.settings_text_size_reset)) }
            }
        }
        StableInputDensity {
            Slider(
                value = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
                onValueChange = onPreview,
                onValueChangeFinished = onSettled,
                valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("textSizeSlider")
                    .semantics { contentDescription = sliderDescription },
            )
        }
    }
}

/**
 * The distance-units choice: a title over four radio rows — Auto (the phone's language decides),
 * Meters, Yards, Feet. Each names the short unit; the long one follows (km, mi, mi). Rows rather
 * than segments so every option keeps its full width at the largest text size, and each whole row
 * is the tap target. Nothing is selected while [enabled] is false (the stored choice isn't read
 * yet), so the default can't pass for it.
 */
@Composable
private fun DistanceUnitsRow(selected: DistanceUnits, onSelect: (DistanceUnits) -> Unit, enabled: Boolean) {
    val options = listOf(
        DistanceUnits.AUTOMATIC to stringResource(R.string.settings_distance_units_auto),
        DistanceUnits.METERS to stringResource(R.string.settings_distance_units_meters),
        DistanceUnits.YARDS to stringResource(R.string.settings_distance_units_yards),
        DistanceUnits.FEET to stringResource(R.string.settings_distance_units_feet),
    )
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        Text(
            text = stringResource(R.string.settings_distance_units_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        options.forEach { (units, label) ->
            val isSelected = enabled && units == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(units) },
                    )
                    .testTag("distanceUnits-${units.name}")
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The row handles the tap; the button only shows the state.
                RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * How fast the rider walks ([WalkingSpeed]), as [DistanceUnitsRow] lays out its choice: nothing
 * selected until the stored choice is read.
 */
@Composable
private fun WalkingSpeedRow(selected: WalkingSpeed, onSelect: (WalkingSpeed) -> Unit, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        Text(
            text = stringResource(R.string.walking_speed_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        WalkingSpeed.entries.forEach { speed ->
            val isSelected = enabled && speed == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(speed) },
                    )
                    .testTag("walkingSpeedSetting-${speed.name}")
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = walkingSpeedLabel(speed), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * A labelled setting row: a title + explanatory summary on the left, a [Switch] on the right.
 * The whole row is clickable so the tap target is the full width, not just the switch (the
 * fewer-larger-targets discipline the sibling apps follow). [switchTestTag] disambiguates the
 * switches when a screen has more than one.
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    switchTestTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}

/**
 * The disruptions row's line in Settings: what it does, since its name alone doesn't say (maintainer,
 * 2026-10-06), or "Off". What it covers is on its page.
 */
@Composable
private fun disruptionsSummary(on: Boolean): String =
    stringResource(if (on) R.string.settings_disruptions_row_description else R.string.settings_disruptions_row_off)

/**
 * The disruptions summary's own page (maintainer, 2026-10-05: its settings control only the row, so
 * they sit under it, not at the top level): the switch that shows the row, then, while it shows, the
 * networks it always covers ([SummaryNetworksRow]). Back returns to Settings.
 */
@Composable
internal fun DisruptionsSummaryPage(
    show: Boolean,
    onShowChange: (Boolean) -> Unit,
    showLoaded: Boolean,
    showWriteFailed: Boolean,
    onDismissShowError: () -> Unit,
    networks: Set<String>,
    onToggleLine: (String) -> Unit,
    networksLoaded: Boolean,
    networksWriteFailed: Boolean,
    onDismissNetworksError: () -> Unit,
    onBack: () -> Unit,
    onOpenFavoritePlaces: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag("disruptionsSummaryPage")) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // Title with a Back button at the end, as Settings' own.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_disruptions_row_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
            // Its controls only once both choices are read: a page restored after process death opens
            // before they are, and would otherwise show the defaults, then jump (Codex, #592).
            if (!showLoaded || !networksLoaded) return@Column
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.surface))
                    .verticalScroll(scrollState),
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_disruptions_row_show),
                    summary = stringResource(R.string.settings_disruptions_row_summary),
                    checked = show,
                    onCheckedChange = onShowChange,
                    enabled = showLoaded,
                    switchTestTag = "disruptionsRowSwitch",
                )
                if (showWriteFailed) {
                    SettingErrorRow(
                        text = stringResource(R.string.settings_disruptions_row_write_failed),
                        onDismiss = onDismissShowError,
                    )
                }
                // A line choice that didn't save, above the chips rather than under all twenty of them, and
                // brought into view and announced, so a tap far down the page never seems to have saved
                // (principle 2 ranks above holding still; Codex, #642).
                if (networksWriteFailed) {
                    LaunchedEffect(Unit) { scrollState.animateScrollTo(0) }
                    SettingErrorRow(
                        text = stringResource(R.string.settings_disruptions_row_write_failed),
                        onDismiss = onDismissNetworksError,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // Which lines it always covers: only while the row shows, as it says nothing otherwise.
                if (show) SummaryNetworksRow(networks, onToggleLine, enabled = networksLoaded, onOpenFavoritePlaces = onOpenFavoritePlaces)
            }
        }
    }
}

/**
 * What the disruptions summary always covers: a chip per line, the Tube's and the Overground's each under
 * the network's name and the single-line networks' under "Other" (maintainer, 2026-10-06: lines picked
 * one by one, in groups; 2026-10-07: no chip for a whole network, the row leans on the rider's own). A chip shows
 * selected while covered. The lines near the rider, their favorites', and the stations' near each favorite
 * place (a button away) are covered whatever's chosen,
 * so none chosen is fine. Until the stored choice is read ([enabled] false) none shows selected.
 *
 * A tap names its line, and the setting turns it the other way on its latest choice, off the main
 * thread and in order, whether or not this page is still open ([app.stopdash.data.SummaryNetworksSetting.toggle]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryNetworksRow(selected: Set<String>, onToggle: (String) -> Unit, enabled: Boolean, onOpenFavoritePlaces: () -> Unit) {
    val slot = remember { mutableStateOf<Worked<Inputs, List<List<Boolean>>>?>(null) }
    // The last chips stand while a tap's are worked out, so nothing blinks out under the finger. Keyed by
    // the choice's identity ([Inputs]), so nothing compares the set's contents on this thread (Codex, #642).
    val covered = rememberWorked(slot, Inputs(selected), keep = { _, _ -> true }) { HomeLines.covered(selected) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("summaryNetworks")) {
        Text(stringResource(R.string.settings_summary_networks_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_summary_networks_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The places whose stations' lines it includes, a tap away (maintainer, 2026-10-07).
        TextButton(onClick = onOpenFavoritePlaces, modifier = Modifier.testTag("summaryFavoritePlaces")) {
            Text(stringResource(R.string.settings_favorite_places_title))
        }
        // Every chip is drawn from the first frame, none selected or tappable until which are selected is
        // worked out (Codex, #642).
        HomeLines.PICKER.forEachIndexed { g, (network, lines) ->
            Text(
                stringResource(network?.let(::networkName) ?: R.string.settings_summary_other),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                lines.forEachIndexed { i, line ->
                    FilterChip(
                        selected = enabled && covered?.get(g)?.get(i) == true,
                        onClick = { onToggle(line.id) },
                        label = { Text(line.name, maxLines = 1) },
                        // Not before which are selected is in: a chip shown off that is on would turn off
                        // when tapped to turn it on (Codex, #642).
                        enabled = enabled && covered != null,
                        modifier = Modifier.testTag("summaryLine-${line.id}"),
                    )
                }
            }
        }
    }
}

private fun networkName(network: HomeLines.Network): Int = when (network) {
    HomeLines.Network.TUBE -> R.string.network_tube
    HomeLines.Network.OVERGROUND -> R.string.network_overground
    HomeLines.Network.ELIZABETH -> R.string.network_elizabeth
    HomeLines.Network.DLR -> R.string.network_dlr
    HomeLines.Network.TRAM -> R.string.network_tram
}
