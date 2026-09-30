package app.stopdash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.stopdash.R

/**
 * Every screen, under one bar while TfL refuses the user's key (SPEC D7): "TfL rejected your API
 * key", with [onClearKey] to clear it so requests go on keyless. One bar for the whole app rather
 * than an action on each screen, since a refused key fails every request alike and many screens
 * fold a failure into a plain "couldn't update" ([app.stopdash.data.RejectedApiKey]).
 *
 * While a change to the key didn't save ([saveFailed]), a bar says so with [onRetrySave]: a Clear
 * that holds only in memory would bring the refused key back on a restart, and the widget, reading
 * the stored key, would go on sending it.
 *
 * The bar takes the top inset itself and consumes it for [content], so a screen's own app bar sits
 * right under it rather than leaving the status-bar gap twice. The content stays in the same slot
 * whether the bar shows or not, so it keeps its state when the bar comes and goes.
 */
@Composable
internal fun KeyRejectedFrame(
    rejected: Boolean,
    onClearKey: () -> Unit,
    saveFailed: Boolean = false,
    onRetrySave: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val shown = rejected || saveFailed
    Column(Modifier.fillMaxSize()) {
        if (shown) {
            // The banner's own color behind the status bar too, so the bars read as one strip.
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    if (saveFailed) {
                        ActionBanner(stringResource(R.string.api_key_write_failed), onTryAgain = onRetrySave)
                    }
                    if (rejected) {
                        ActionBanner(
                            stringResource(R.string.error_key_rejected),
                            actionLabel = stringResource(R.string.clear_api_key),
                            onAction = onClearKey,
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (shown) Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier),
        ) {
            content()
        }
    }
}
