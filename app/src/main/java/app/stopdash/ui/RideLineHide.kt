package app.stopdash.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.stopdash.R
import app.stopdash.domain.FavoriteJourney
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineLabel
import app.stopdash.domain.LineRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A favorite ride's "Hide ‹line› on this ride", for the rows of its card (SPEC *Hiding a line from a
 * ride*): every line but the one it was saved on ([FavoriteJourney.hides]), whose route places the
 * ride's stops. [onHide] hides the line named.
 */
internal class RideLineHide(private val savedLineId: String, val onHide: (LineRef) -> Unit) {
    /** Whether a row of line [lineId] offers the hide. */
    fun offers(lineId: String): Boolean = lineId.isNotBlank() && !lineId.equals(savedLineId, ignoreCase = true)
}

/**
 * Hides a line from a ride through [onSetHidden] and offers "‹line› hidden on this ride · Undo" on
 * [host], as a long press's global hide offers its Undo ([rememberHideWithUndo]): Undo shows that line
 * on that ride again, and a second hide takes the first's offer away. Null when [onSetHidden] is.
 */
@Composable
internal fun rememberRideLineHideWithUndo(
    onSetHidden: ((FavoriteJourney, String, Boolean) -> Unit)?,
    host: SnackbarHostState,
): ((FavoriteJourney, LineRef) -> Unit)? {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val setHidden by rememberUpdatedState(onSetHidden)
    // One lambda for the screen's life reading the latest callback, so the cards it's handed to aren't
    // recomposed for a new one each time the screen is.
    val hide = remember(host, scope, resources) {
        var offer: Job? = null
        val hideThenOffer: (FavoriteJourney, LineRef) -> Unit = { journey, line ->
            // Saved by its English label, as every hidden line is (HiddenModes.lineKey); said in the app's words.
            val label = LineLabel.of(line.name.ifBlank { line.id }, line.mode)
            val entry = HiddenModes.lineKey(line.id, label.text())
            setHidden?.invoke(journey, entry, true)
            offer?.cancel()
            offer = scope.launch {
                val result = host.showSnackbar(
                    message = resources.getString(R.string.ride_line_hidden, label.text(lineWord(resources))),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) setHidden?.invoke(journey, entry, false)
            }
        }
        hideThenOffer
    }
    return if (onSetHidden == null) null else hide
}

/** [journey]'s [RideLineHide], one for as long as its saved line and [onHide] stay; null when [onHide] is. */
@Composable
internal fun rememberRideLineHide(journey: FavoriteJourney, onHide: ((FavoriteJourney, LineRef) -> Unit)?): RideLineHide? {
    val current by rememberUpdatedState(journey)
    return remember(journey.lineId, onHide) {
        onHide?.let { hide -> RideLineHide(journey.lineId) { line -> hide(current, line) } }
    }
}
