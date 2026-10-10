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

/** How much location StopDash may use while in use, for Settings' Location row. */
enum class LocationAccess { PRECISE, APPROXIMATE, NONE }

/**
 * The [LocationAccess] held now, read on [io] as [foregroundLocationGranted] is: precise when
 * precise is granted, approximate when only approximate is, else none.
 */
internal suspend fun locationAccess(
    granted: (String) -> Boolean,
    io: CoroutineDispatcher = Dispatchers.IO,
): LocationAccess = withContext(io) {
    when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
        else -> LocationAccess.NONE
    }
}
