package app.stopdash.domain

/**
 * The stop the in-app near-me list shows a line's [direction] from ([DepartureRows.nearbyChoices]),
 * saved with the widget's snapshot so the widget and the watch show the line from the same stop
 * ([DepartureRows.glanceFolded]). A stop id and a line, never a distance: which nearby stop serves
 * a line for the rider says no more about where they are than the stops' order already does.
 * [direction] is the fold's cross-stop key: TfL's direction, or a line-status row's sentinel.
 */
data class FoldChoice(val lineId: String, val direction: String, val stopId: String)
