package app.stopdash.domain

/**
 * A trip's stop to go via (SPEC *Trips with a change → Going via a stop*): the one in place ([via]), and a station
 * complex just picked ([pending]) that takes its place once its stations have been looked up, so Back meanwhile
 * leaves the trip as it was. Pure, so the picker's transitions are tested apart from the activity that holds them,
 * as [ToChoice]'s are.
 */
data class ViaChoice(val via: TripVia? = null, val pending: TripVia? = null) {
    /**
     * The stops looked up: the via in place and the pick pending, once each. Each keeps its lookup as the pick
     * becomes the via, so the trip shows the via it settles on without looking it up again.
     */
    val looked: List<TripVia> get() = listOfNotNull(via, pending).distinctBy { it.stopId }

    /**
     * The via the trip shows now: the pick pending once its stations are looked up ([pendingFound]), before
     * [resolved] has made it the via, so no route that skips it is shown meanwhile; else the via in place.
     */
    fun shown(pendingFound: Boolean): TripVia? = pending?.takeIf { pendingFound } ?: via

    /**
     * [pick] picked from the Via… search. The trip's own destination [toId] changes nothing; any other stop is
     * planned via as picked, one of the destination's own stations included (maintainer, 2026-10-16). A station
     * complex ([isComplex]) waits on its stations as [pending]; any other stop is the via at once.
     */
    fun picked(pick: TripVia, isComplex: Boolean, toId: String?): ViaChoice = when {
        pick.stopId == toId -> this
        isComplex -> copy(pending = pick)
        else -> ViaChoice(via = pick)
    }

    /** [pick]'s stations looked up: it is the via. A pick no longer pending (picked over, or cancelled) changes nothing. */
    fun resolved(pick: TripVia): ViaChoice = if (pending == pick) ViaChoice(via = pick) else this

    /** Back from a pick still being looked up: the via in place stays. */
    fun cancelled(): ViaChoice = copy(pending = null)

    /** The Via row's ×: the trip goes any way. */
    fun cleared(): ViaChoice = ViaChoice()

    /**
     * A choice made before the trip had a destination (a stop's Via, SPEC *Going via a stop*) for the trip to
     * [toId] once it has one: kept, less a stop that is that destination itself, as [picked] would drop it.
     */
    fun carriedTo(toId: String?): ViaChoice =
        ViaChoice(via = via?.takeUnless { it.stopId == toId }, pending = pending?.takeUnless { it.stopId == toId })
}

/**
 * A trip's [choice] and the [destination] it belongs to, null before one is picked. Another destination starts
 * without one; the first destination picked keeps what was chosen before it ([ViaChoice.carriedTo]), so a trip
 * opened via a stop goes via it wherever the rider then picks.
 */
data class DestinationViaChoice(val destination: String?, val choice: ViaChoice = ViaChoice()) {
    /** The choice for the trip to [to]. */
    fun to(to: String?): DestinationViaChoice = when (destination) {
        to -> this
        null -> DestinationViaChoice(to, choice.carriedTo(to))
        else -> DestinationViaChoice(to)
    }
}
