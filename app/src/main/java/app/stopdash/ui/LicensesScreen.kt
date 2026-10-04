package app.stopdash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.WorkerThread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.stopdash.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

/**
 * Open-source attribution, reached from the About dialog: the transitive dependency graph and
 * its licenses. The list is read from the committed `res/raw/aboutlibraries.json`, regenerated
 * with `./gradlew :app:exportBundledLicenses` — the AboutLibraries plugin can't wire the
 * resource in automatically under AGP 9 (see app/build.gradle.kts).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.licenses_link_failed)
    // rememberLibraries parses the bundled JSON off the composition thread and swaps it in when
    // ready, so the screen renders its title instantly (SPEC snapshot-render discipline).
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    LicensesContent(
        libraries = libraries,
        onBack = onBack,
        onOpenLicenseUrl = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (e: ActivityNotFoundException) {
                // A device with no browser (or a stripped build) has nothing to open the
                // license URL — tell the user rather than let the tap silently do nothing.
                // The URL is a public SPDX address from the bundled export, not user data.
                Log.w("Licenses", "No activity to open a license link", e)
                Toast.makeText(context, linkFailed, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
internal fun LicensesContent(
    libraries: Libs?,
    onBack: () -> Unit = {},
    onOpenLicenseUrl: (String) -> Unit = {},
) {
    // The tapped library's stable id, if any — its details fill the dialog below. Saved (not a
    // plain remember) so an open dialog survives rotation and process death.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // The library the tap named, kept as it is, so opening the dialog reads nothing.
    var tapped by remember { mutableStateOf<Library?>(null) }
    // After a restore only the id is left: it's found again in the list on the worker, never by a
    // scan of every library in composition (AGENTS.md *Main thread: read and dispatch only*).
    val restoredSlot = remember { mutableStateOf<Worked<Inputs, Library?>?>(null) }
    val restoreId = selectedId.takeIf { tapped == null }
    val restored = rememberWorked(restoredSlot, if (restoreId == null) Inputs() else Inputs(libraries, restoreId)) {
        libraryWithId(libraries, restoreId)
    }
    val selected = tapped?.takeIf { it.uniqueId == selectedId } ?: restored?.takeIf { it.uniqueId == selectedId }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            // Title with a Back button at the end.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The title takes what Back and the overflow leave, wrapping at a large text size
                // rather than squeezing them off the row.
                Text(
                    text = stringResource(R.string.settings_licenses_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    AppMenuOverflow()
                }
            }
            // Just the component names, one compact row each; the version and license live behind
            // a tap so the list stays scannable.
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize().scrollEdgeCue(listState, scrollCueColors(MaterialTheme.colorScheme.surface)),
                state = listState,
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(
                    items = libraries?.libraries.orEmpty(),
                    key = { it.uniqueId },
                ) { library ->
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tapped = library
                                selectedId = library.uniqueId
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    selected?.let { library ->
        LibraryDetailsDialog(
            library = library,
            onOpenLicenseUrl = onOpenLicenseUrl,
            onDismiss = {
                selectedId = null
                tapped = null
            },
        )
    }
}

/** The library in [libraries] with [id], if any: a pass over every library, so never in composition. */
@WorkerThread
internal fun libraryWithId(libraries: Libs?, id: String?): Library? =
    id?.let { libraries?.libraries?.firstOrNull { it.uniqueId == id } }

/**
 * Version, authors and license(s) for a tapped [library]. The bundled export carries no license
 * text (it's excluded to keep the regenerate-and-diff deterministic — see app/build.gradle.kts),
 * so each license with a URL is a link to the full text rather than inline body copy.
 */
@Composable
private fun LibraryDetailsDialog(
    library: Library,
    onOpenLicenseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val authors = remember(library) { library.authorsOrEmpty() }
    // A dialog opens its own window, which doesn't inherit the theme's scaled density or pinch
    // handler — FontSizeWindow re-applies the chosen text size to each slot and pinchFontSizeHost
    // lets a pinch resize while it's open (SPEC *Display size*).
    AlertDialog(
        modifier = Modifier.pinchFontSizeHost(),
        onDismissRequest = onDismiss,
        confirmButton = {
            FontSizeWindow {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        },
        title = { FontSizeWindow { Text(library.name) } },
        text = {
            FontSizeWindow {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                library.artifactVersion?.let { version ->
                    Text(
                        text = stringResource(R.string.settings_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (authors.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_licenses_authors, authors),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                library.licenses.forEach { license ->
                    val url = license.url
                    if (!url.isNullOrEmpty()) {
                        // A link to the full license text — primary color and a tap target
                        // signal it opens in the browser.
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLicenseUrl(url) }
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                }
            }
        },
    )
}

/**
 * Who wrote this component, as the export records it — the POM's declared developers, or the
 * organization that published it when no developer is named. Empty when the POM declares
 * neither, and the dialog then shows no authors line at all rather than an empty label.
 *
 * Apache-2.0 §4 asks that attribution travel with the code, and the license name alone does not
 * carry it. Names only — a second link per component would bury the license link this dialog
 * exists for.
 */
internal fun Library.authorsOrEmpty(): String =
    developers
        .mapNotNull { developer -> developer.name?.takeIf(String::isNotBlank) }
        .ifEmpty { listOfNotNull(organization?.name?.takeIf(String::isNotBlank)) }
        .joinToString(", ")
