package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Whether a step-free ride has lost its way on, or every way off, at its trip's level to the lifts out today
 * (dev-docs/station-resolution.md, *Getting off*: "else none does, and the ride says so").
 */
object StepFreeExits {
    /**
     * Whether [ride], getting off where [end] has it, can get off at the trip's level ([LegEnd.stepFree]) at none of
     * the calls it reaches there ([LegEnd.ids] on its path) nor at its own call ([TripLeg.toId]) with the lifts out
     * today ([today]), though it can at one of them with every lift working ([base]). A call named by its platform
     * ("9400…", "9100…") is judged as its station's record, which TfL's table holds it under, as [Arrival] reads it
     * ([platformRecord]). Only a lift going out says so: a
     * call [base] already fails is one the Planner, who checked it, may know better than TfL's table, so a ride whose
     * calls all fail the table without a lift out says nothing. Never at no level, nor for a ride from the street
     * ([StepFreeAccess.atStreet]: a bus).
     */
    @WorkerThread
    fun lost(ride: TripLeg, end: LegEnd, base: StepFreeAccess, today: StepFreeAccess): Boolean {
        if (end.stepFree.needs == null || StepFreeAccess.atStreet(ride.mode)) return false
        fun record(id: String) = if (id in end.ids) id else platformRecord(id) ?: id
        val calls = (ride.path.map(::record).filter { it in end.ids } + record(ride.toId)).filter { it.isNotBlank() }.distinct()
        fun passes(access: StepFreeAccess) = calls.any { LegEnd.meets(end.stepFree, access.levels(it), ride.lineId, ride.mode) }
        return passes(base) && !passes(today)
    }

    /**
     * Whether [ride] can no longer be boarded at the trip's level [stepFree] at its own call ([TripLeg.fromId]): it meets
     * the level there with every lift working ([base]) and doesn't with the lifts out today ([today]). Judged as [lost]
     * judges a call: one named by its platform as its station's record ([platformRecord]), and only a lift going out
     * saying so; never at no level, nor for a ride from the street.
     */
    @WorkerThread
    fun boardingLost(ride: TripLeg, stepFree: StepFree, base: StepFreeAccess, today: StepFreeAccess): Boolean {
        if (stepFree.needs == null || StepFreeAccess.atStreet(ride.mode) || ride.fromId.isBlank()) return false
        val at = platformRecord(ride.fromId) ?: ride.fromId
        fun meets(access: StepFreeAccess) = LegEnd.meets(stepFree, access.levels(at), ride.lineId, ride.mode)
        return meets(base) && !meets(today)
    }

    /**
     * Each place [route]'s rides have lost their way on ([boardingLost]) or every way off ([lost]) at [ends]' level
     * ([RideEnds.stepFree]) to the lifts out today, each getting off where [ends] has it: by its leg's index among
     * [route]'s legs and the stop, in route order. One warning a station ([RouteDisruption.Signal.NotStepFree.key]), so a
     * change with no way off the ride before or on the next is said once, by getting off. For a trip page's routes,
     * which no rider is on yet, so every ride's boarding counts. None at no level.
     */
    @WorkerThread
    fun along(route: TripRoute, ends: RideEnds, base: StepFreeAccess, today: StepFreeAccess): List<RouteDisruption.Signal.NotStepFree> {
        if (ends.stepFree.needs == null) return emptyList()
        val rides = route.legs.withIndex().filter { !it.value.isWalk }
        return ends.ends(route).zip(rides).flatMap { (ridden, leg) ->
            val (ride, end) = ridden
            listOfNotNull(
                RouteDisruption.Signal.NotStepFree(leg.index, ride.fromId, ride.fromName)
                    .takeIf { boardingLost(ride, ends.stepFree, base, today) },
                RouteDisruption.Signal.NotStepFree(leg.index, ride.toId, ride.toName).takeIf { lost(ride, end, base, today) },
            )
        }.distinctBy { it.key }
    }
}
