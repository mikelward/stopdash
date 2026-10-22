package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * Whether a step-free ride has lost every way off at its trip's level to the lifts out today (dev-docs/station-resolution.md,
 * *Getting off*: "else none does, and the ride says so").
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
}
