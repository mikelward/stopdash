package app.stopdash

/**
 * Where *Lines…* is: closed, showing, or stepped aside under the near-me trip a stop's To planned
 * from it, kept there so that trip's Back lands on the stop it was planned from rather than the
 * near-me list. One value, so "open" and "under the trip" can't disagree.
 */
internal enum class LinesPresence {
    CLOSED,
    SHOWN,
    UNDER_TRIP,
    ;

    /** Open, whether showing or hidden under a trip: its line, stop and scroll are kept. */
    val isOpen: Boolean get() = this != CLOSED

    /** Showing over the screen beneath. */
    val shown: Boolean get() = this == SHOWN

    /** Opened from a menu: shows, where it was left if it was under a trip. */
    fun opened(): LinesPresence = SHOWN

    /** Its own Back, or a flow that lands on the near-me list: closed. */
    fun closed(): LinesPresence = CLOSED

    /** A stop's To opened the near-me trip: Lines… steps aside for it. */
    fun stepAsideForTrip(): LinesPresence = if (this == SHOWN) UNDER_TRIP else this

    /** The near-me trip closed: Lines… under it shows again, at the stop. */
    fun tripClosed(): LinesPresence = if (this == UNDER_TRIP) SHOWN else this
}
