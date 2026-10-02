package app.stopdash.domain

/**
 * Compiled patterns built from a stop's name ("between A and B", "A Station (L)"), kept so each
 * compiles once rather than on every read of an alert. A fixed pattern is a `private val`; this is
 * for the ones only known from the route, which reading a line's alert asks for per station and per
 * pair of stations, again for every page and every refresh.
 *
 * Bounded, least recently used out first: a route's names repeat across reads, and a long bus route
 * under a wordy alert asks for a few thousand, so this holds a few routes' worth without growing
 * with every line ever opened. Thread-safe, since alerts are read off the main thread.
 */
internal object Patterns {
    private const val MAX = 4096

    private val compiled = object : LinkedHashMap<Pair<String, Boolean>, Regex>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, Regex>): Boolean =
            size > MAX
    }

    /** [pattern] compiled, ignoring case when [ignoreCase]: the same instance while it's held. */
    fun of(pattern: String, ignoreCase: Boolean = false): Regex {
        val key = pattern to ignoreCase
        synchronized(compiled) { compiled[key]?.let { return it } }
        // Compiled outside the lock, so one slow compile doesn't hold up another thread's lookups;
        // two threads asking at once for a new pattern may both compile it, which is harmless.
        val regex = if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        synchronized(compiled) { compiled.getOrPut(key) { regex } }
        return regex
    }

    /** How many are held, for a test. */
    internal fun size(): Int = synchronized(compiled) { compiled.size }
}
