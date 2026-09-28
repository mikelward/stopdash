package app.stopdash.domain

/**
 * A line's current TfL status, reduced to what the surfaces need: whether the line is
 * disrupted and a short description to show (SPEC *Disruptions* / D3). TfL reports one or
 * more statuses per line; this carries the worst of them. A line is disrupted whenever any
 * status is not a good service ([GOOD_SERVICE]), so a delayed or suspended line is marked
 * rather than have its countdowns shown as if they could be trusted (SPEC principle 1 —
 * never show a departure stopdash doesn't stand behind).
 *
 * [description] is the short chip label: TfL's own wording for the shown status ("Severe
 * Delays", "Suspended", "Good Service") where that already names the disruption, else a
 * concise label recovered from TfL's free-text reason when the wording is only a vague
 * "Special Service" ([resolveDisruption]).
 *
 * [fullText] is TfL's free-text reason for the shown disruption — the prose behind the chip
 * ("Victoria line: Severe delays while we fix a signal failure…"), for the tap-to-open route
 * detail (SPEC *Disruptions*): the compact card shows only [description], the detail shows
 * this. Null when there is no prose to show — a good service, or a disruption TfL worded but
 * gave no reason for — so the detail then has nothing to expand beyond the label.
 *
 * [byDirection] is the same reduction made once per direction of travel (TfL's `inbound` /
 * `outbound`), for a line whose alerts TfL scopes to one direction: a diversion on the way into
 * town says nothing about the buses heading out, so a row going the other way shouldn't carry it
 * ([forDirection]). An alert whose direction isn't known counts for both. Empty when the split
 * changes nothing — no directional alerts, or none looked up yet — so the line-wide status holds.
 *
 * [awaitingDirections] is true while a lookup of some alert's direction is under way: the status
 * is complete for now but will split once it lands, so a caller that reuses a recent status
 * asks again rather than keeping this one for its whole reuse window.
 */
data class LineStatus(
    val lineId: String,
    val severity: Int,
    val description: String,
    val fullText: String? = null,
    val byDirection: Map<String, LineStatus> = emptyMap(),
    val awaitingDirections: Boolean = false,
) {
    /** True when TfL reports anything other than a good service on this line. */
    val disrupted: Boolean get() = severity != GOOD_SERVICE

    /**
     * The status a row travelling in [direction] should show: that direction's own when TfL scoped
     * the line's alerts by direction ([byDirection]), else the line-wide one. A row with no TfL
     * direction (most rail predictions) keeps the line-wide status, so nothing is hidden from it.
     */
    fun forDirection(direction: String): LineStatus = byDirection[direction] ?: this

    /** This status and each per-direction one: every alert a row could show for the line. */
    val allStatuses: List<LineStatus> get() = listOf(this) + byDirection.values

    companion object {
        /** TfL's `statusSeverity` for a normal, undisrupted line. */
        const val GOOD_SERVICE = 10
    }
}
