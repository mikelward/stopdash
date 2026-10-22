package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

/** A stop header's words ([headerWords]) in the app's language, made once per language rather than on every recomposition. */
@Composable
internal fun rememberHeaderWords(): HeaderWords {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { headerWords(resources) }
}
