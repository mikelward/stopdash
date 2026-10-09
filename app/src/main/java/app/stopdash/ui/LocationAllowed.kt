package app.stopdash.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the app holds a location grant, read by every "use my location" control (the crosshairs,
 * From…'s Here) so none is offered where it could only lead back to the location gate (SPEC
 * *Without location*). Provided by the activity from the grant itself, read off composition at
 * creation and on each start, so a restored screen shows the same controls from its first frame.
 * True by default, as a test or preview has nothing to withhold.
 */
val LocalLocationAllowed = compositionLocalOf { true }
