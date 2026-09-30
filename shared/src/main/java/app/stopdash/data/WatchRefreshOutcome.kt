package app.stopdash.data

/**
 * How the phone answered a watch's refresh request (dev-docs/wear-os.md *Refresh*). The phone
 * answers every request, so a tap never silently does nothing; a refresh that stored something
 * new also republishes the snapshot, which follows as its own item.
 */
enum class WatchRefreshOutcome {
    /** Every stop fetched. */
    REFRESHED,

    /** Some stops fetched, some failed (the snapshot marks them). */
    PARTLY_REFRESHED,

    /** Nothing fetched: TfL said to slow down. */
    RATE_LIMITED,

    /** Nothing fetched: TfL couldn't be reached. */
    UNREACHABLE,

    /** Nothing fetched: TfL refused the user's key, which only clearing it on the phone mends (SPEC D7). */
    KEY_REJECTED,

    /** The phone has no stops to fetch. */
    NO_STOPS,

    /** The snapshot is recent enough that nothing was fetched; the phone resends it. */
    DEBOUNCED,
    ;

    /** Why one stop's fetch failed, as far as the outcome cares. */
    enum class Failure { RATE_LIMITED, UNREACHABLE, KEY_REJECTED }

    companion object {
        /**
         * Whether a refresh that ended [age] ago with [last] answers a new request as is, within
         * [window]. Only one where every stop failed: it saved nothing, so the snapshot's own reuse
         * can't stop the next request fetching them all again during the same outage. One that
         * fetched anything saved it, and the next request reuses those stops.
         */
        fun answersAgain(last: WatchRefreshOutcome, age: java.time.Duration, window: java.time.Duration): Boolean =
            !age.isNegative && age < window && last in setOf(RATE_LIMITED, UNREACHABLE, KEY_REJECTED)

        /**
         * The outcome of a refresh of [stops] stops: [fetched] of them fetched fresh, [reused]
         * skipped as recent, and [failures] for the rest. A refused key on any stop names the
         * all-failed case, since it's the one the user has to act on (every request with the key
         * fails alike); else a rate limit, the one they can wait out. With nothing fetched fresh, a
         * line check refused the key ([statusKeyRejected]) says so too, even over arrivals all
         * reused: the refresh learned the key is refused, and "recent enough" would hide it.
         */
        fun of(
            stops: Int,
            fetched: Int,
            reused: Int,
            failures: List<Failure>,
            statusKeyRejected: Boolean = false,
        ): WatchRefreshOutcome = when {
            stops == 0 -> NO_STOPS
            fetched == 0 && (statusKeyRejected || Failure.KEY_REJECTED in failures) -> KEY_REJECTED
            fetched == 0 && reused == stops -> DEBOUNCED
            fetched == 0 && Failure.RATE_LIMITED in failures -> RATE_LIMITED
            fetched == 0 -> UNREACHABLE
            failures.isNotEmpty() -> PARTLY_REFRESHED
            else -> REFRESHED
        }
    }
}

/**
 * The phone's answer to the watch's refresh request [requestId]. The id lets the watch settle only
 * the request it's waiting on: a late answer to an earlier, timed-out one mustn't end a newer wait.
 * The id is a random number the watch picks per request; it carries nothing about the user.
 */
data class WatchRefreshReply(val requestId: Long, val outcome: WatchRefreshOutcome) {
    /** The message payload: `<id>:<OUTCOME>`. */
    fun encode(): ByteArray = "$requestId:${outcome.name}".encodeToByteArray()

    companion object {
        /** The request payload for [requestId]. */
        fun encodeRequest(requestId: Long): ByteArray = requestId.toString().encodeToByteArray()

        /** The request id in [bytes], or null if there isn't one. */
        fun decodeRequest(bytes: ByteArray): Long? = bytes.decodeToString().toLongOrNull()

        /** The reply in [bytes], or null for one this build can't read (an outcome from a newer phone). */
        fun decode(bytes: ByteArray): WatchRefreshReply? {
            val (id, name) = bytes.decodeToString().split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val outcome = WatchRefreshOutcome.entries.firstOrNull { it.name == name } ?: return null
            return id.toLongOrNull()?.let { WatchRefreshReply(it, outcome) }
        }
    }
}
