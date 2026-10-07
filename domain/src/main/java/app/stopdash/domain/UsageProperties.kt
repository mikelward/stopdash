package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The rider's standing choices and setup, sent as Analytics user properties with **Help make StopDash
 * better** on (SPEC *Privacy*), so each usage event can be read against them: the share of riders who
 * walk slowly, need step-free routes, keep a widget or wear a watch. Like [UsageEvent], every value is
 * a category or a bucket: counts, never what was counted (no stop, line, place or key).
 *
 * A null field couldn't be read and is sent as "unknown".
 */
data class UsageState(
    val walkingSpeed: WalkingSpeed,
    val maxWalk: MaxWalk,
    val stepFree: StepFree,
    val tripModes: TripModes,
    val avoidedLines: Int,
    // The hidden-modes set as stored ([HiddenModes]): mode ids and hidden lines' keys.
    val hiddenModes: Set<String>,
    val distanceUnits: DistanceUnits,
    val disruptionsRow: Boolean,
    val liveWidget: Boolean,
    val textSize: Float,
    val pinchToResize: Boolean,
    val tflKey: Boolean,
    val railKey: Boolean,
    // Widgets placed, on the home screen or the lock screen.
    val widgets: Int?,
    val watch: Watch?,
    val starredRows: Int?,
    val favoritePlaces: Int?,
    val favoriteJourneys: Int?,
    val notifications: Boolean?,
    val location: UsageEvent.Grant?,
) {
    /**
     * A watch: a paired one with StopDash (connected or not), else one connected now without it, else
     * none seen. The Wearable Data Layer lists a paired watch without the app only while it's connected,
     * so a disconnected one reads as none.
     */
    enum class Watch(val value: String) { NONE("none"), WITHOUT_APP("connected_no_app"), WITH_APP("app") }
}

/**
 * [state] as Analytics user properties: each name at most 24 characters and each value at most 36, the
 * SDK's limits, and at most 25 of them.
 */
@WorkerThread
fun usageProperties(state: UsageState): Map<String, String> = mapOf(
    "walking_speed" to UsageEvent.walkingSpeedValue(state.walkingSpeed),
    "max_walk" to "${state.maxWalk.minutes}",
    "step_free" to UsageEvent.stepFreeValue(state.stepFree),
    "trip_modes_off" to joinedGroups(ModeGroups.ALL.filterNot(state.tripModes::rides).map { it.key }),
    "avoided_lines" to UsageEvent.countBucket(state.avoidedLines),
    "hidden_modes" to joinedGroups(
        ModeGroups.hiddenGroups(state.hiddenModes).map { group -> group.key.takeIf { key -> ModeGroups.ALL.any { it.key == key } } ?: "other" }.distinct(),
    ),
    "hidden_lines" to UsageEvent.countBucket(state.hiddenModes.count(HiddenModes::isLineKey)),
    "distance_units" to UsageEvent.distanceUnitsValue(state.distanceUnits),
    "disruptions_row" to UsageEvent.onOff(state.disruptionsRow),
    "live_widget" to UsageEvent.onOff(state.liveWidget),
    "text_size" to UsageEvent.textSizeBand(state.textSize),
    "pinch_resize" to UsageEvent.onOff(state.pinchToResize),
    "own_tfl_key" to yesNo(state.tflKey),
    "rail_key" to yesNo(state.railKey),
    "widgets" to bucketOrUnknown(state.widgets),
    "watch" to (state.watch?.value ?: UNKNOWN),
    "starred_rows" to bucketOrUnknown(state.starredRows),
    "favorite_places" to bucketOrUnknown(state.favoritePlaces),
    "favorite_journeys" to bucketOrUnknown(state.favoriteJourneys),
    "notifications" to (state.notifications?.let(::yesNo) ?: UNKNOWN),
    "location" to (state.location?.value ?: UNKNOWN),
)

private const val UNKNOWN = "unknown"

/** The longest user-property value Analytics keeps. */
private const val MAX_VALUE = 36

private fun yesNo(yes: Boolean) = if (yes) "yes" else "no"

private fun bucketOrUnknown(count: Int?) = count?.let(UsageEvent::countBucket) ?: UNKNOWN

/** Group keys joined in menu order ("bus+boat"), "none" for none, and "many" past the SDK's limit. */
private fun joinedGroups(keys: List<String>): String = when {
    keys.isEmpty() -> "none"
    else -> keys.joinToString("+").takeIf { it.length <= MAX_VALUE } ?: "many"
}
