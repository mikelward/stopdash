package app.stopdash.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import app.stopdash.R

/**
 * A line's name with "line" in [resources]' language ("Northern" → "Northern line"; TfL's name itself is
 * never translated), for [lineLabel] and [app.stopdash.domain.savedLineLabel]. A hidden or avoided line's
 * entry keeps the English, so a saved set reads the same in every language and this is applied as it's
 * shown.
 */
internal fun lineWord(resources: Resources): (name: String) -> String = { resources.getString(R.string.line_label, it) }

/** [app.stopdash.domain.lineLabel] in [resources]' language: "Northern line", "134", "Elizabeth". */
internal fun lineLabel(lineName: String, mode: String, resources: Resources): String =
    app.stopdash.domain.lineLabel(lineName, mode, lineWord(resources))

/** [lineWord] for the app's language, again when it changes. */
@Composable
internal fun rememberLineWord(): (name: String) -> String {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { lineWord(resources) }
}
