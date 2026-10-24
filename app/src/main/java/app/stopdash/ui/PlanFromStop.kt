package app.stopdash.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * A route page's Directions: the To… search from the stop the page was opened at ([stopId], called [name]), as
 * that stop's own page offers it, provided by the activity. Null (a look that starts nothing, or a test) shows none.
 */
val LocalPlanFromStop = compositionLocalOf<((stopId: String, name: String) -> Unit)?> { null }
