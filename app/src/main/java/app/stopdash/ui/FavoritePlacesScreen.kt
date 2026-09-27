package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.PlaceCandidate
import app.stopdash.domain.StationMatch
import app.stopdash.domain.UkPostcode

/**
 * The favorite-places editor (SPEC D9), reached from Settings and hosted as an activity-level overlay
 * like [LicensesScreen]/[SettingsScreen], so its own Back closes it. It lists the saved places with
 * add/edit/delete; a place is saved by coordinate, resolved from a stop/postcode the user types
 * (sent only to TfL). Routing to a favorite is a later slice; here the list is edit-only.
 *
 * UI-only: it reflects [state] and reports intents through the callbacks, so it stays
 * JVM/Robolectric-renderable for the screenshot test without a store or network.
 */
@Composable
fun FavoritePlacesScreen(
    state: FavoritePlacesViewModel.State,
    onBack: () -> Unit,
    onStartAdd: (FavoriteKind, String) -> Unit,
    onStartEdit: (FavoritePlace) -> Unit,
    onDelete: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onPick: (StationMatch) -> Unit,
    onResolvePostcode: () -> Unit = {},
    onPickCandidate: (PlaceCandidate) -> Unit = {},
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancelEditor: () -> Unit,
    onRetrySearch: () -> Unit = {},
    onDismissWriteError: () -> Unit = {},
) {
    // Back closes the editor first, then the screen — so it unwinds a step at a time.
    BackHandler(onBack = { if (state.editor != null) onCancelEditor() else onBack() })
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            val editor = state.editor
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        when {
                            editor == null -> R.string.favorite_places_title
                            editor.editing -> R.string.favorite_place_edit_title
                            else -> R.string.favorite_place_add_title
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { if (editor != null) onCancelEditor() else onBack() }) {
                    Text(stringResource(if (editor != null) R.string.favorite_place_cancel else R.string.action_back))
                }
            }
            // Separate scroll states for the list and the editor, so opening the editor starts at the
            // top rather than inheriting the list's offset (and each keeps its own position). Codex.
            val listScroll = rememberScrollState()
            val editorScroll = rememberScrollState()
            val scrollState = if (editor == null) listScroll else editorScroll
            // Reset the editor's scroll to the top each time an editor session opens: the state is
            // remembered across sessions, so a fresh add/edit would otherwise inherit the previous
            // session's offset and start with its fields scrolled off screen (Codex). The editor always
            // opens from the list (editor becomes null in between), so this fires once per session.
            LaunchedEffect(editor != null) {
                if (editor != null) editorScroll.scrollTo(0)
            }
            // The write-failure notice sits outside the scroll container, so a save/delete that fails
            // while the list or editor is scrolled down is still visible rather than inserted above the
            // current offset (Codex). It shows above whichever view is up (a failed save keeps the
            // editor open, so it must be visible there too).
            if (state.writeFailed) {
                WriteErrorRow(onDismiss = onDismissWriteError)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEdgeCue(scrollState, scrollCueColors(MaterialTheme.colorScheme.surface))
                    .verticalScroll(scrollState),
            ) {
                if (editor == null) {
                    PlacesList(
                        places = state.places,
                        loaded = state.loaded,
                        onStartAdd = onStartAdd,
                        onStartEdit = onStartEdit,
                        onDelete = onDelete,
                    )
                } else {
                    PlaceEditor(
                        editor = editor,
                        loaded = state.loaded,
                        onQueryChange = onQueryChange,
                        onPick = onPick,
                        onResolvePostcode = onResolvePostcode,
                        onPickCandidate = onPickCandidate,
                        onLabelChange = onLabelChange,
                        onSave = onSave,
                        onRetrySearch = onRetrySearch,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacesList(
    places: FavoritePlacesSet,
    loaded: Boolean,
    onStartAdd: (FavoriteKind, String) -> Unit,
    onStartEdit: (FavoritePlace) -> Unit,
    onDelete: (String) -> Unit,
) {
    // A newer-schema file this build can't read: say so rather than showing "nothing saved" and
    // offering to overwrite it (SPEC principle 2; the store preserves it untouched).
    if (places is FavoritePlacesSet.Unavailable) {
        Text(
            text = stringResource(R.string.favorite_places_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    // Before the store's first emission: show a placeholder so the screen appears at once rather than
    // blank, and don't offer add actions yet — a slow read could otherwise let the user add a Home
    // before the stored one appears and replace it (SPEC jank-free UI; Codex).
    if (!loaded) {
        Text(
            text = stringResource(R.string.favorite_places_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    // A corrupt file was discarded: the prior favorites are lost, so say so rather than showing the
    // now-empty list as "nothing saved" — but still offer the add actions, since the store is writable
    // and the user can start again (SPEC *never lose work silently*; Codex).
    if (places is FavoritePlacesSet.Discarded) {
        Text(
            text = stringResource(R.string.favorite_places_discarded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    val list = (places as? FavoritePlacesSet.Loaded)?.places.orEmpty()
    if (places is FavoritePlacesSet.Loaded && list.isEmpty()) {
        Text(
            text = stringResource(R.string.favorite_places_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    list.forEach { place ->
        PlaceRow(place = place, onEdit = { onStartEdit(place) }, onDelete = { onDelete(place.id) })
    }
    // Add buttons: the reserved kinds only while unset (they are single slots); a custom place always.
    // Labels are resolved here in composable scope, then handed to the (non-composable) click lambdas.
    val kinds = list.map { it.kind }.toSet()
    val homeLabel = stringResource(R.string.favorite_place_home)
    val workLabel = stringResource(R.string.favorite_place_work)
    val schoolLabel = stringResource(R.string.favorite_place_school)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (FavoriteKind.HOME !in kinds) {
            AddButton(R.string.favorite_places_add_home, "addHome") {
                onStartAdd(FavoriteKind.HOME, homeLabel)
            }
        }
        if (FavoriteKind.WORK !in kinds) {
            AddButton(R.string.favorite_places_add_work, "addWork") {
                onStartAdd(FavoriteKind.WORK, workLabel)
            }
        }
        if (FavoriteKind.SCHOOL !in kinds) {
            AddButton(R.string.favorite_places_add_school, "addSchool") {
                onStartAdd(FavoriteKind.SCHOOL, schoolLabel)
            }
        }
        AddButton(R.string.favorite_places_add_custom, "addCustom") {
            onStartAdd(FavoriteKind.CUSTOM, "")
        }
    }
}

@Composable
private fun AddButton(labelRes: Int, tag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
    ) {
        Text(stringResource(labelRes))
    }
}

@Composable
private fun PlaceRow(place: FavoritePlace, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place.label, style = MaterialTheme.typography.bodyLarge)
            place.placeName?.takeIf { it.isNotBlank() && it != place.label }?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        // TalkBack reads the place name, not just "Delete", so the right row's action is clear.
        val deleteDescription = stringResource(R.string.favorite_place_delete_description, place.label)
        TextButton(
            onClick = onDelete,
            modifier = Modifier
                .testTag("delete-${place.id}")
                .semantics { contentDescription = deleteDescription },
        ) { Text(stringResource(R.string.favorite_place_delete)) }
    }
}

@Composable
private fun PlaceEditor(
    editor: FavoritePlacesViewModel.Editor,
    loaded: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (StationMatch) -> Unit,
    onResolvePostcode: () -> Unit,
    onPickCandidate: (PlaceCandidate) -> Unit,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetrySearch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        OutlinedTextField(
            value = editor.query,
            onValueChange = onQueryChange,
            singleLine = true,
            // Frozen while a save is in flight, so a completion can't close the editor over a late edit.
            enabled = !editor.saving,
            label = { Text(stringResource(R.string.favorite_places_search_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("placeSearchField"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        // When the text looks like a postcode, offer to resolve it (a station name search would never
        // match one). Shown above the station results; only a complete postcode is tappable (SPEC D9).
        val showingPostcode = editor.coordinate == null && UkPostcode.looksLikePartial(editor.query)
        if (showingPostcode) {
            PostcodeSection(
                query = editor.query,
                editor = editor,
                onResolve = onResolvePostcode,
                onPickCandidate = onPickCandidate,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        when {
            editor.searching -> Text(
                text = stringResource(R.string.favorite_places_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            editor.searchFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.favorite_places_search_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetrySearch, modifier = Modifier.testTag("placeSearchRetry")) {
                    Text(stringResource(R.string.route_stops_retry))
                }
            }
            // A place is chosen: confirm it rather than prompting to search — the query field can be
            // blank here (a draft restored after process death doesn't re-save the typed text), and Save
            // is enabled, so the user must see what they'd save (Codex).
            editor.coordinate != null -> Text(
                text = stringResource(
                    R.string.favorite_places_selected,
                    editor.placeName?.takeIf { it.isNotBlank() } ?: editor.label,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // The postcode section is the answer for a postcode-shaped query, so don't also say the
            // station search found "no matches" beneath it (Codex).
            editor.query.trim().length >= FavoritePlacesViewModel.MIN_QUERY_LENGTH &&
                editor.results.isEmpty() && !showingPostcode -> Text(
                text = stringResource(R.string.station_search_no_matches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            editor.query.isBlank() -> Text(
                text = stringResource(R.string.favorite_places_search_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        editor.results.forEach { match ->
            MatchResultRow(
                match = match,
                resolving = editor.resolvingId == match.id,
                unresolvable = match.id in editor.unresolvableIds,
                resolveFailed = match.id in editor.resolveFailedIds,
                onPick = onPick,
            )
        }
        // TfL's search failed but the bundled index matched: say bus stops weren't searched, as the
        // station search does, rather than presenting the local list as the whole answer.
        if (editor.remoteFailed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.station_search_bus_stops_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetrySearch, modifier = Modifier.testTag("placeRemoteRetry")) {
                    Text(stringResource(R.string.route_stops_retry))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = editor.label,
            onValueChange = onLabelChange,
            singleLine = true,
            enabled = !editor.saving,
            label = { Text(stringResource(R.string.favorite_places_label_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("placeLabelField"),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                // Also gated on [loaded]: a draft restored before the store's first emission mustn't be
                // saveable, since the write could no-op against a not-yet-known newer-schema file (Codex).
                onClick = onSave,
                enabled = editor.canSave && loaded,
                modifier = Modifier.testTag("placeSave"),
            ) { Text(stringResource(R.string.favorite_place_save)) }
        }
    }
}

/** A write-failure notice with a Dismiss action, shown above the list when a save/delete threw. */
@Composable
private fun WriteErrorRow(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.favorite_places_write_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("dismissWriteError")) {
            Text(stringResource(R.string.action_dismiss))
        }
    }
}

/**
 * The postcode affordance shown above the station results when the query looks like a postcode: a
 * tappable row that resolves a complete postcode (SPEC D9), a spinner while it resolves, the resolved
 * place candidates to choose from, or a retry/"no places" state.
 */
@Composable
private fun PostcodeSection(
    query: String,
    editor: FavoritePlacesViewModel.Editor,
    onResolve: () -> Unit,
    onPickCandidate: (PlaceCandidate) -> Unit,
) {
    val code = UkPostcode.format(query) ?: query.trim().uppercase()
    val complete = UkPostcode.isComplete(query)
    when {
        editor.postcodeResolving -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("postcodeResolving"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.favorite_places_postcode_resolving, code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        editor.postcodeCandidates.isNotEmpty() -> Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.favorite_places_postcode_heading, code),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            editor.postcodeCandidates.forEach { candidate ->
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickCandidate(candidate) }
                        .padding(vertical = 8.dp)
                        .testTag("postcodeCandidate"),
                )
            }
        }
        editor.postcodeFailed -> Text(
            text = stringResource(R.string.favorite_places_postcode_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onResolve() }
                .padding(vertical = 8.dp)
                .testTag("postcodeFailed"),
        )
        editor.postcodeNoResults -> Text(
            text = stringResource(R.string.favorite_places_postcode_none, code),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("postcodeNone"),
        )
        // Not yet looked up: the affordance. Tappable only once the postcode is complete.
        else -> Text(
            text = stringResource(
                if (complete) R.string.favorite_places_postcode_lookup else R.string.favorite_places_postcode_partial,
                code,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (complete) Modifier.clickable { onResolve() } else Modifier)
                .padding(vertical = 8.dp)
                .testTag("postcodeRow"),
        )
    }
}

@Composable
private fun MatchResultRow(
    match: StationMatch,
    resolving: Boolean,
    unresolvable: Boolean,
    resolveFailed: Boolean,
    onPick: (StationMatch) -> Unit,
) {
    // Tappable unless it's already known unplaceable or currently being resolved. A positionless result
    // is still tappable: the tap resolves its coordinate from the stop's members (TfL) before giving up.
    // A transiently-failed resolve stays tappable too — the tap retries it.
    val tappable = !unresolvable && !resolving
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tappable) Modifier.clickable { onPick(match) } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (unresolvable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            when {
                // Definitively no location: shown, not tappable.
                unresolvable -> Text(
                    text = stringResource(R.string.favorite_places_no_position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A transient lookup failure: say so and invite a retry (the row stays tappable) rather
                // than presenting a temporary outage as "no location" (Codex).
                resolveFailed -> Text(
                    text = stringResource(R.string.favorite_places_resolve_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    // The modes disambiguate two same-named places TfL kept distinct (>250 m apart), so
                    // the user doesn't pick the wrong coordinate — as the station search's rows do (Codex).
                    val modes = modesLabel(match.modes)
                    if (modes.isNotEmpty()) {
                        Text(
                            text = modes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (resolving) {
            // Resolving this result's coordinate from the stop's members.
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).testTag("resolving-${match.id}"),
                strokeWidth = 2.dp,
            )
        }
    }
}
