package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Where a trip to a station complex is planned to (SPEC *Trips with a change*). The Journey Planner
 * takes one stop per end and leans toward that stop's own mode, so a trip to an interchange such
 * as King's Cross St. Pancras is planned once to each of its distinct stations and once to its
 * bus stops, and the answers are merged: the best way there whatever the line or mode.
 */
object PlanTargets {
    /**
     * One member stop of a complex: its [id] and the [lines] it serves. It's a bus stop when every
     * line it serves is a bus: those are planned to once for them all.
     */
    data class Member(val id: String, val lines: List<LineRef>) {
        val bus: Boolean get() = lines.isNotEmpty() && lines.all { it.mode.equals(BUS, ignoreCase = true) }
    }

    /**
     * The stop ids to plan to among [members], in their order: each station code, and
     * one bus stop for all the bus stops (the Planner walks between them). An interchange's own id
     * ("HUB…") is never one: the Planner can't plan to it. Station codes are never merged: neither
     * names (shortened for display, so Hammersmith's two stations share one) nor ids (Abbey Wood's
     * Elizabeth line code extends its National Rail one) tell a platform variant from another
     * station, and a code planned to twice costs a call where two stations merged would skip one.
     */
    fun of(members: List<Member>): List<String> {
        val stations = mutableListOf<Member>()
        var bus: Member? = null
        for (member in members) {
            if (member.id.isBlank() || member.id.startsWith(HUB_PREFIX)) continue
            if (member.bus) {
                if (bus == null) bus = member
            } else if (stations.none { it.id == member.id }) {
                stations += member
            }
        }
        return stations.map { it.id } + listOfNotNull(bus?.id)
    }

    /**
     * The stop ids to plan via among [members], for a trip the rider sends through a complex
     * ([TripVia]; SPEC *Going via a stop*): each station code, as [of] gives them, since the Planner
     * only counts a route as passing the station it was asked via (via Finsbury Park's Underground
     * station, a Great Northern train through its National Rail station doesn't). Its bus stops only
     * where it has no station: every stop asked via multiplies each of the plan's requests, and a rider
     * going via a bus stop picks that stop, which the search lists on its own.
     */
    fun via(members: List<Member>): List<String> {
        val targets = of(members)
        val stations = targets.filter { id -> members.none { it.id == id && it.bus } }
        return stations.ifEmpty { targets }
    }

    /**
     * What a trip via the complex of [members] asks the Planner ([via]), with its key ([ViaStops]): planned via as
     * picked, one holding a station the trip goes to included (maintainer, 2026-10-16). Walks every member, so on
     * a worker.
     */
    @WorkerThread
    fun viaStops(members: List<Member>): ViaStops =
        ViaStops(via(members).ifEmpty { listOfNotNull(members.firstOrNull()?.id) })

    private const val HUB_PREFIX = "HUB"
    private const val BUS = "bus"
}
