package app.stopdash.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import app.stopdash.R

/**
 * Where a rider subscribes to the free Rail Data Marketplace product a key of their own is for: the Live
 * Departure Board's page, which a key from any other product doesn't open.
 */
internal const val RAIL_DATA_MARKETPLACE_URL =
    "https://raildata.org.uk/dashboard/dataProduct/P-d81d6eaf-8060-4467-a339-1c833e50cbbe/overview"

/**
 * Asked when the rider ticks National Rail with no key set (SPEC *Finding stops → Hiding a mode*):
 * without one its rows have no times, so it says where to get a free key and offers Settings to
 * paste it, or [onShowAnyway] to show the rows as they are.
 */
@Composable
internal fun RailKeyDialog(onAddKey: () -> Unit, onShowAnyway: () -> Unit, onDismiss: () -> Unit) {
    // A window of its own: drawn over a glimpse of the page it's over, it would cover the page being swiped.
    if (!LocalPageOnTop.current) return
    val context = LocalContext.current
    val linkFailed = stringResource(R.string.rail_key_link_failed)
    AlertDialog(
        modifier = Modifier.pinchFontSizeHost(),
        onDismissRequest = onDismiss,
        title = { FontSizeWindow { Text(stringResource(R.string.rail_key_title)) } },
        text = { FontSizeWindow { Text(stringResource(R.string.rail_key_body)) } },
        confirmButton = {
            FontSizeWindow {
                Row {
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, RAIL_DATA_MARKETPLACE_URL.toUri()))
                            } catch (e: ActivityNotFoundException) {
                                // No browser to open it: say so rather than let the tap do nothing.
                                Log.w("RailKey", "No activity to open the key sign-up page", e)
                                Toast.makeText(context, linkFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text(stringResource(R.string.rail_key_get)) }
                    TextButton(onClick = onAddKey) { Text(stringResource(R.string.rail_key_add)) }
                }
            }
        },
        dismissButton = {
            FontSizeWindow {
                TextButton(onClick = onShowAnyway) { Text(stringResource(R.string.rail_key_show_anyway)) }
            }
        },
    )
}
