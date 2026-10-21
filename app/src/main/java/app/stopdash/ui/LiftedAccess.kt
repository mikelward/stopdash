package app.stopdash.ui

import androidx.annotation.WorkerThread
import app.stopdash.domain.RideEnds
import app.stopdash.domain.StepFree
import app.stopdash.domain.StepFreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TfL's step-free table with the lifts out of service now, for a step-free trip's ride ends to be checked against
 * ([RideEnds.Destination.access]): the bundled [table], read once when a step-free trip first needs it, and rebuilt
 * ([StepFreeAccess.withLiftsOut]) only for a set of lifts out it wasn't last rebuilt for, as that walks the whole
 * table. A table that couldn't be read, or read empty, is none, said to [warn]: the trip's rides then get off where
 * the Planner gets them off, as it checked them.
 */
internal class LiftedAccess(private val table: suspend () -> StepFreeAccess?, private val warn: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private var read = false
    private var base: StepFreeAccess? = null
    private var lifted: Pair<Set<String>, StepFreeAccess>? = null

    /** The table with [out] (TfL's lift ids) out of service; null where there's none to check by. On a worker only. */
    @WorkerThread
    suspend fun with(out: Set<String>): StepFreeAccess? = mutex.withLock {
        if (!read) {
            base = try {
                table()?.takeIf { !it.isEmpty }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The rides get off where the Planner does, as with no table: said, not dropped.
                warn("step-free table unreadable (${e.javaClass.simpleName})")
                null
            }
            read = true
        }
        val table = base ?: return@withLock null
        lifted?.takeIf { it.first == out }?.let { return@withLock it.second }
        val now = if (out.isEmpty()) table else table.withLiftsOut(out)
        lifted = out to now
        now
    }

    /**
     * [destination] as a trip at [stepFree] checks its ends through: as it is at no level, else with the table and the
     * lifts in [out] out of service ([with]). On a worker only.
     */
    @WorkerThread
    suspend fun destination(destination: RideEnds.Destination, stepFree: StepFree, out: Set<String>): RideEnds.Destination =
        if (stepFree == StepFree.ANY) destination else RideEnds.Destination(destination.model, destination.hubs, with(out))
}
