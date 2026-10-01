package app.stopdash.ui

import app.stopdash.domain.SteadyClock
import app.stopdash.domain.StopClosureCache
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.StopDisruptionBatch
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Closure checks of a trip's stops (SPEC *Disruptions*), shared by the trip's screen and a trip on
 * the way, so both ask the same way and reuse each other's answers through [cache]: each stop from
 * [cache] when looked up within [reuse], else asked for (a bus stop's poles several to a request, as
 * the list asks them) and kept there. A failure is logged with [what] asked it, never claimed as open.
 */
internal class StopClosureChecks(
    private val client: TflClient,
    private val cache: StopClosureCache,
    private val reuse: Duration,
    private val io: CoroutineDispatcher,
    // Coarse facts only: an error kind and a stop id, never where the rider is going.
    private val warn: (String) -> Unit,
    private val what: String,
) {
    /**
     * What one check found: each stop's notices ([found]) and when they were looked up ([at], by the
     * steady clock), and the stops whose request failed with no later lookup kept ([failed]).
     */
    class Result(
        val found: Map<String, List<StopDisruption>>,
        val at: Map<String, Instant>,
        val failed: Set<String>,
    )

    /**
     * Checks [ids] at [now] as the lookup [ticket] names ([StopClosureCache.ask], taken by the caller
     * before anything is sent), each settled in [cache] as it lands, so a later lookup already kept
     * wins over this one, failed or not.
     */
    suspend fun check(ids: List<String>, ticket: StopClosureCache.Ask, now: Instant): Result {
        val found = HashMap<String, List<StopDisruption>>()
        val at = HashMap<String, Instant>()
        val ask = ids.filter { id ->
            // Aged by the steady clock it's stamped by ([StopClosureCache.Ask.at]). Dated after now (the
            // clock set back, across a reboot) is an age that can't be told, so asked again.
            val held = cache[id]?.takeIf { SteadyClock.age(it.at, now).let { age -> !age.isNegative && age < reuse } }
            held?.let {
                found[id] = it.notices
                at[id] = it.at
            }
            held == null
        }
        val (poles, others) = ask.partition(StopDisruptionBatch::isPole)
        val answers = coroutineScope {
            (
                poles.chunked(StopDisruptionBatch.MAX_PER_REQUEST).map { chunk ->
                    async { chunk to request("${chunk.size} bus stop(s)") { client.poleDisruptions(chunk) } }
                } + others.map { id ->
                    async { listOf(id) to request("stop $id") { mapOf(id to client.stopDisruptions(id)) } }
                }
            ).awaitAll()
        }
        val failed = HashSet<String>()
        for ((asked, answer) in answers) {
            for (id in asked) {
                cache.settle(id, ticket, answer.map { it[id].orEmpty() })
                    .onSuccess {
                        found[id] = it.notices
                        at[id] = it.at
                    }
                    .onFailure { failed += id }
            }
        }
        return Result(found, at, failed)
    }

    // One closure request's answer, or its failure (logged: an error kind and what was asked).
    private suspend fun <T> request(asked: String, request: suspend () -> T): kotlin.Result<T> =
        try {
            kotlin.Result.success(withContext(io) { request() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("$what closure check failed: ${e::class.simpleName} for $asked")
            kotlin.Result.failure(e)
        }
}
