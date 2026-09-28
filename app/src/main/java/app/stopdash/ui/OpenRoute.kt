package app.stopdash.ui

import android.util.Log
import app.stopdash.data.PersistedTripLeg
import app.stopdash.domain.RideLines
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The route open on a trip's screen, kept whole rather than found again among the routes shown: its
 * own [key] ([routeKey]: its lines and stops), the planned route that offers it ([plan]), and for a
 * train through a change ([RideLines.through]) that train's [ride], standing in for the plan's two
 * rides from leg [at]. So an open train through a change stays open while the plan offers the change
 * it runs through, whether a train is predicted now or not (maintainer, 2026-09-29): its leg reads
 * "–", as a line with no trains does on the list. Saved as text ([encode]): a planned route by its key
 * alone.
 */
internal data class OpenRoute(val plan: String, val at: Int = -1, val ride: TripLeg? = null, val key: String = plan) {
    /** The planned routes that can offer it: itself as planned, and the one it's made from. */
    val keys: Set<String> get() = setOf(key, plan)

    /**
     * The route as [routes] offer it now, or null when none does. Planned as it is (a train through a
     * change that a new plan offers as a route of its own, too), the Planner's copy; otherwise the
     * plan's copy of the route it's made from (its walks and times as planned at the current pace),
     * with the train through in place of the two rides it joins, on the Planner's times for them
     * without the change, as [RideLines.through] gives it.
     */
    fun routeIn(routes: List<TripRoute>): TripRoute? {
        routes.firstOrNull { routeKey(it) == key }?.let { return it }
        val ride = ride ?: return null
        val route = routes.firstOrNull { routeKey(it) == plan } ?: return null
        val first = route.legs.getOrNull(at) ?: return null
        val second = route.legs.getOrNull(at + 1) ?: return null
        if (!first.mode.equals(ride.mode, ignoreCase = true) || !second.mode.equals(ride.mode, ignoreCase = true)) return null
        val joined = ride.copy(
            departure = first.departure,
            arrival = first.departure + first.run + second.run,
            changeAfter = second.changeAfter,
        )
        return TripRoute(route.legs.take(at) + joined + route.legs.drop(at + 2))
    }

    /** As saved ([parse]): the plan's key for a planned route, else the train through with it. */
    fun encode(): String =
        if (ride == null) plan else JSON.encodeToString(Saved(plan, at, PersistedTripLeg.of(ride), key))

    @Serializable
    private data class Saved(val plan: String, val at: Int, val ride: PersistedTripLeg, val key: String)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The route [encode] saved, or null for none (or one unreadable, which opens nothing). */
        fun parse(saved: String?): OpenRoute? {
            if (saved == null) return null
            if (!saved.startsWith("{")) return OpenRoute(saved)
            return try {
                JSON.decodeFromString<Saved>(saved).let { OpenRoute(it.plan, it.at, it.ride.toLeg(), it.key) }
            } catch (e: SerializationException) {
                // Saved by this app, so only an older format gets here: the list shows rather than a route.
                Log.w("StopDash.Trip", "open route unreadable: ${e::class.simpleName}")
                null
            } catch (e: DateTimeParseException) {
                Log.w("StopDash.Trip", "open route unreadable: ${e::class.simpleName}")
                null
            }
        }
    }
}
