package app.stopdash

import android.Manifest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether location is allowed while in use (precise or approximate), read on [io]: a permission
 * check is a call into the system, and the UI asks this from a tap (AGENTS.md *Main thread*).
 * [granted] answers for one permission, `ContextCompat.checkSelfPermission` in the app.
 */
internal suspend fun foregroundLocationGranted(
    granted: (String) -> Boolean,
    io: CoroutineDispatcher = Dispatchers.IO,
): Boolean = withContext(io) {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any(granted)
}
