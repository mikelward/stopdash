package app.stopdash.ui

import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.stopdash.R
import app.stopdash.domain.ModeGroups
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [onHide], offering "‹name› hidden · Undo" on [host] each time it hides something (SPEC *Finding
 * stops → Hiding a mode*): Undo shows that one item again through [onUnhide] — the group or line just
 * hidden, as a [ModeGroups.of] key — and leaves anything else hidden as it was, where the banner's
 * "Show all" brings back everything. A second hide takes the first's offer away, so Undo always
 * means the latest. [onHide] as it is when there's no [onUnhide]; null when it's null.
 *
 * A hide can close the screen that offered it: hiding the only mode a trip starts from ends the trip.
 * An offer its screen left still showing is held by [LocalHideUndoCarrier], and the next screen to
 * offer Undo, the one landed on, offers it again, if it's still fresh ([HideUndoCarrier]).
 */
@Composable
internal fun rememberHideWithUndo(
    onHide: ((String) -> Unit)?,
    onUnhide: ((String) -> Unit)?,
    host: SnackbarHostState,
): ((String) -> Unit)? {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val carrier = LocalHideUndoCarrier.current
    val hide by rememberUpdatedState(onHide)
    val unhide by rememberUpdatedState(onUnhide)
    // One offer, and one lambda for the screen's life reading the latest callbacks, so the rows it's
    // handed to aren't recomposed for a new one each time the screen is.
    val offerUndo = remember(host, scope, resources, carrier) {
        val offer = UndoOffer()
        val show: (String) -> Unit = { entry ->
            // Canceling the last offer's wait takes its snackbar down (or out of the queue), and only
            // that one: a "didn't save" notice on the same host stays.
            offer.job?.cancel()
            carrier?.hold(entry)
            offer.job = scope.launch {
                val result = host.showSnackbar(
                    message = resources.getString(R.string.modes_hidden, hiddenItemName(ModeGroups.of(entry))),
                    actionLabel = resources.getString(R.string.action_undo),
                    duration = SnackbarDuration.Short,
                )
                // Answered or timed out here; only an offer whose screen left under it is carried.
                carrier?.release(entry)
                if (result == SnackbarResult.ActionPerformed) unhide?.invoke(entry)
            }
        }
        show
    }
    val withUndo = remember(offerUndo) {
        val hideThenOffer: (String) -> Unit = { entry ->
            hide?.invoke(entry)
            offerUndo(entry)
        }
        hideThenOffer
    }
    if (onHide == null || onUnhide == null) return onHide
    LaunchedEffect(carrier) { carrier?.take()?.let(offerUndo) }
    return withUndo
}

/**
 * The Undo offer a screen was showing when it left: [hold] as each is offered, [release] once it's
 * answered or times out, and [take]n by the next screen that can offer Undo. Only while fresh — within
 * a snackbar's own few seconds of the hide — so an offer isn't put back long after, on a screen the
 * rider came back to later.
 */
internal class HideUndoCarrier(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var entry: String? = null
    private var heldAt = 0L

    fun hold(entry: String) {
        this.entry = entry
        heldAt = now()
    }

    fun release(entry: String) {
        if (this.entry == entry) this.entry = null
    }

    /** The offer still owed, if fresh; either way it's no longer held. */
    fun take(): String? {
        val owed = entry?.takeIf { now() - heldAt < CARRY_MILLIS }
        entry = null
        return owed
    }

    private companion object {
        // A short snackbar's own time on screen: past it, the offer would have gone anyway.
        const val CARRY_MILLIS = 4_000L
    }
}

/** The app's [HideUndoCarrier]; none (an offer ends with its screen) where the app doesn't provide one. */
internal val LocalHideUndoCarrier = staticCompositionLocalOf<HideUndoCarrier?> { null }

/** The snackbar job of the latest Undo offer, so a newer hide can take it away. */
private class UndoOffer {
    var job: Job? = null
}
