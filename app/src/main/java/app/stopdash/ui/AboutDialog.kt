package app.stopdash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.BuildConfig
import app.stopdash.R

/**
 * The About dialog: the app name, the running version, where its data comes from (with NaPTAN's
 * licence statement), and the one action on it — the
 * open-source licenses screen (SPEC *About and open-source licenses*). Shared so it can be
 * reached from both the departures top bar and the location gate, since the license
 * attribution has to be reachable whatever state the app is in. The version comes from
 * [BuildConfig] so it always matches the installed build.
 */
@Composable
internal fun AboutDialog(onOpenLicenses: () -> Unit, onDismiss: () -> Unit) {
    // A window of its own: drawn over a glimpse of the page it's over, it would cover the page being swiped.
    if (!LocalPageOnTop.current) return
    // A dialog opens its own window, which does not inherit the theme's scaled density or its
    // pinch handler (SPEC *Display size*): [FontSizeWindow] re-applies the chosen size to each
    // slot, and [pinchFontSizeHost] lets a pinch resize while the dialog is up, so "pinch anywhere"
    // holds here too.
    AlertDialog(
        modifier = Modifier.pinchFontSizeHost(),
        onDismissRequest = onDismiss,
        title = { FontSizeWindow { Text(stringResource(R.string.about_title)) } },
        text = {
            FontSizeWindow {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // The data's sources and NaPTAN's licence statement (the bundled National Rail
                    // station codes are built from it).
                    Text(
                        text = stringResource(R.string.about_data_sources),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            FontSizeWindow {
                TextButton(onClick = onOpenLicenses) {
                    Text(stringResource(R.string.settings_licenses_title))
                }
            }
        },
        dismissButton = {
            FontSizeWindow {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}
