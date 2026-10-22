package app.stopdash.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import app.stopdash.R
import java.util.Locale

/**
 * A TfL mode's name in the app's language ("national-rail" → "National Rail"); a mode this build
 * doesn't know keeps its id tidied ([app.stopdash.domain.modeName]), as TfL gave it.
 */
internal fun modeName(mode: String, resources: Resources): String =
    MODE_NAMES[mode.lowercase(Locale.ROOT)]?.let { resources.getString(it) } ?: app.stopdash.domain.modeName(mode)

/**
 * A station's [modes] as a screen reader hears them on its row, in the app's language and TfL's order:
 * "Tube · Elizabeth · National Rail". Worked out where the row draws (2026-10-22; the maintainer left
 * where to the code): a station serves a handful of modes at most, so it's a fixed few lookups, not
 * work that grows with a list, and the words follow the language the row is drawn in. A station match
 * is made in places with no resources to say them (the station index, recents, favorites).
 */
internal fun modesSpoken(modes: List<String>, resources: Resources): String =
    modes.filter { it.isNotBlank() }.distinct().joinToString(" · ") { modeName(it, resources) }

/** [modesSpoken] for [modes], once per language and set of modes rather than on every recomposition. */
@Composable
internal fun rememberModesSpoken(modes: List<String>): String {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(modes, resources, configuration) { modesSpoken(modes, resources) }
}

/** The modes this build names, by TfL's id; the rest keep their tidied id. */
private val MODE_NAMES: Map<String, Int> = mapOf(
    "tube" to R.string.mode_name_tube,
    "bus" to R.string.mode_name_bus,
    "dlr" to R.string.mode_name_dlr,
    "overground" to R.string.mode_name_overground,
    "elizabeth-line" to R.string.mode_name_elizabeth_line,
    "national-rail" to R.string.mode_name_national_rail,
    "tram" to R.string.mode_name_tram,
    "river-bus" to R.string.mode_name_river_bus,
    "cable-car" to R.string.mode_name_cable_car,
)
