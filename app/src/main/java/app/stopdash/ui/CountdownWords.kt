package app.stopdash.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import app.stopdash.R
import app.stopdash.domain.CountdownWords

/** A countdown's words ([CountdownWords]) in [resources]' language: "3 · 7 min". */
internal fun countdownWords(resources: Resources): CountdownWords =
    CountdownWords(resources.getString(R.string.countdown_between)) { resources.getString(R.string.countdown_minutes, it) }

/** [countdownWords] in the app's language, made once per language rather than on every recomposition. */
@Composable
internal fun rememberCountdownWords(): CountdownWords {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { countdownWords(resources) }
}
