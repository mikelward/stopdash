package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.toJavaDuration

/**
 * Where TfL's live lift outages come from (`/Disruptions/Lifts/v2`): the id of every lift out of
 * service across TfL, the ids its station data names lifts by ([LiftMap]). Throws on a transport or
 * decode failure, like [TflClient.arrivals].
 */
fun interface LiftOutageSource {
    suspend fun liftsOut(): Set<String>
}

/**
 * The lifts out of service as of one answer ([ids], TfL's lift ids), and how long until that answer
 * ages out and is worth asking for again ([askAgainIn]).
 */
data class LiftsOut(val ids: Set<String>, val askAgainIn: Duration)

/**
 * Where a screen gets TfL's lift outages: what's [known] now, without waiting, to draw its first
 * frame from, and the [current] answer, asking TfL first when it's due.
 */
interface LiftOutageCache {
    /** The lifts out as of TfL's last answer; none before its first. */
    val known: Set<String>

    suspend fun current(): LiftsOut
}

/**
 * TfL's lift outages, asked for at most once every [maxAge] whichever screen wants them, a failed ask
 * included: one request answers for every station, and lifts go out and come back over hours, not
 * seconds. Each answer says when it's next worth asking, so a screen given one fetched a while ago by
 * another asks again then, not a whole [maxAge] later. The last answer TfL gave stands until it gives
 * another, through a failed ask too ([warn] is told why, a failure class only): it only ever takes
 * marks off, so it errs toward "not step-free", where none would put marks back on lifts TfL never
 * said were working.
 */
class LiftOutages(
    private val source: LiftOutageSource,
    private val clock: () -> Instant = Instant::now,
    private val maxAge: Duration = MAX_AGE,
    private val warn: (String) -> Unit = {},
) : LiftOutageCache {
    private val mutex = Mutex()

    // When TfL last finished answering, or failing to, stamped in the steady frame ([SteadyClock]) so
    // setting the clock doesn't age it; and the last answer it gave (none before its first).
    private var askedAt: Instant? = null

    // Written under the mutex; read without it by a screen drawing its first frame.
    @Volatile
    private var ids: Set<String> = emptySet()

    override val known: Set<String> get() = ids

    /**
     * The lifts out as of TfL's last answer, asking again first when it was last asked [maxAge] or
     * more ago (or never, or by a clock since set back).
     */
    override suspend fun current(): LiftsOut = mutex.withLock {
        val now = clock()
        askedAt?.let { asked ->
            val age = Staleness.age(asked, now).toJavaDuration()
            if (!age.isNegative && age < maxAge) return@withLock LiftsOut(ids, maxAge - age)
        }
        try {
            ids = source.liftsOut()
        } catch (e: TflException) {
            warn("lift outages failed: ${e::class.simpleName}")
        }
        // Only an ask that finished counts: one canceled on the way (its screen left) asked nothing.
        // The clock is read again for the stamp: one set while TfL was answering would otherwise
        // stamp the time before it in the frame after it.
        askedAt = SteadyClock.stamp(clock())
        LiftsOut(ids, maxAge)
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
