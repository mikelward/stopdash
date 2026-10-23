package app.stopdash.domain

/**
 * What the page of a station open on the back stack shows (SPEC *Finding stops*): its [name], the order its rows
 * lead in ([lead], for one of an interchange's names), the [level] it was picked at ([Pick.Level.code], where its row
 * said), and its trip's To… ([to]). Kept for each station open, so one opened over another (a station beside a route
 * page's stop) finds the one under it as it was on Back.
 */
data class OpenStation(
    val name: String,
    val lead: StationLead? = null,
    val level: String? = null,
    val to: ToChoice = ToChoice.NONE,
) {
    companion object {
        /**
         * What a station shows once opened as [picked]: a station [open] already is brought up as it was, but for a trip's
         * new start ([carryTo], a change of start), which opens at the trip's To… it was picked for.
         */
        fun opened(open: OpenStation?, picked: OpenStation, carryTo: Boolean): OpenStation = when {
            open == null -> picked
            carryTo -> open.copy(to = picked.to)
            else -> open
        }
    }
}
