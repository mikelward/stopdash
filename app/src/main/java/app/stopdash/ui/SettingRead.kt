package app.stopdash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a stored setting a screen draws by has been read: true at once if it already has ([isLoaded]),
 * else once it is, or once [loaded] gives up on a stuck read (`StoredSettingHolder.loaded`), so this never
 * holds a screen for good. A screen holds its placeholder until then rather than draw by the default and
 * redraw a moment later.
 */
@Composable
fun rememberSettingRead(isLoaded: StateFlow<Boolean>, loaded: suspend () -> Any?): Boolean {
    val read by isLoaded.collectAsStateWithLifecycle()
    val gaveUp by produceState(false) {
        loaded()
        value = true
    }
    return read || gaveUp
}
