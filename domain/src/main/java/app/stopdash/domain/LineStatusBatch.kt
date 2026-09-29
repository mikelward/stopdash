package app.stopdash.domain

import kotlin.coroutines.cancellation.CancellationException

/**
 * How [TflClient.lineStatuses] splits its lines across `/Line/{ids}/Status` requests. TfL's server
 * refuses a path segment longer than [MAX_SEGMENT_LENGTH] characters with a bare HTTP 400 ("Invalid
 * URL") before the API sees it, so a busy hub's ~30 lines in one comma-joined segment failed every
 * line's check at once and showed "couldn't check for disruptions". Measured against the live API:
 * a 260-character segment is answered, 261 is refused.
 */
object LineStatusBatch {
    /** The longest `{ids}` segment TfL accepts. */
    const val MAX_SEGMENT_LENGTH = 260

    /**
     * [lineIds], in order and without duplicates, grouped so each group joined with commas fits in
     * [MAX_SEGMENT_LENGTH]. An id already too long on its own gets a group of its own; TfL decides
     * what that means rather than this dropping it silently.
     */
    fun chunks(lineIds: Collection<String>): List<List<String>> {
        val groups = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var length = 0
        for (id in lineIds.distinct()) {
            val added = if (current.isEmpty()) id.length else length + 1 + id.length
            if (current.isNotEmpty() && added > MAX_SEGMENT_LENGTH) {
                groups += current
                current = mutableListOf(id)
                length = id.length
            } else {
                current += id
                length = added
            }
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /**
     * Asks about [lineIds] one [chunks] group at a time via [request] and keeps each group's outcome
     * apart, so one group failing never discards what another returned. Every caller that looks lines
     * up in bulk goes through here; the partial-answer cases that kept surfacing one caller at a time
     * (a later group failing, an empty answer, a group TfL knows none of) are decided once.
     *
     * [request] answers a group with a value, or null (or a throw) when it failed. A
     * [TflException.NotFound] is TfL knowing none of the group's lines: an answer, not a failure.
     * After the first failure the remaining groups aren't sent: a rate limit or a dead connection
     * would most likely fail them too, and they read unchecked like the failed one.
     */
    suspend fun <T : Any> request(lineIds: Collection<String>, request: suspend (List<String>) -> T?): Results<T> {
        val answers = mutableListOf<Answer<T>>()
        val unknown = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val unsent = mutableListOf<String>()
        var failure: Exception? = null
        var failedAny = false
        var requests = 0
        for (chunk in chunks(lineIds)) {
            if (failedAny) {
                failed += chunk
                unsent += chunk
                continue
            }
            requests++
            try {
                val value = request(chunk)
                if (value != null) {
                    answers += Answer(chunk, value)
                } else {
                    failedAny = true
                    failed += chunk
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException.NotFound) {
                unknown += chunk
            } catch (e: Exception) {
                failedAny = true
                failure = e
                failed += chunk
            }
        }
        return Results(answers, unknown, failed, failure, requests, unsent)
    }

    /** One group of line ids and what TfL answered for it. */
    data class Answer<T>(val lineIds: List<String>, val value: T)

    /**
     * What a [request] found, group by group: the [answers], the lines in groups TfL knows none of
     * ([unknown]), and the lines in a group that failed or went unsent after one did ([failed], with
     * the [failure] when one was thrown). [requests] counts only what was sent, and [unsent] names
     * the lines of [failed] that were never asked about, which a caller counting failures leaves out.
     */
    class Results<T>(
        val answers: List<Answer<T>>,
        val unknown: List<String>,
        val failed: List<String>,
        val failure: Exception?,
        val requests: Int,
        val unsent: List<String> = emptyList(),
    ) {
        /** Whether TfL answered any group, an empty or unknown-lines answer included. */
        val anyAnswered: Boolean get() = answers.isNotEmpty() || unknown.isNotEmpty()

        /** The lines TfL gave a verdict on: in an answered group, or one it knows none of. */
        val answeredIds: Set<String> get() = answers.flatMapTo(LinkedHashSet()) { it.lineIds } + unknown
    }
}
