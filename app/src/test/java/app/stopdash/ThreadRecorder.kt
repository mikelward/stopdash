package app.stopdash

/**
 * Where a test's work ran (AGENTS.md *Main thread*): [note] from any thread records the current one's name
 * (a coroutine's " @coroutine#n" suffix dropped); [threads] is a snapshot taken under the same lock, so a read
 * never catches a write (a plain list threw ConcurrentModificationException on CI, #676's run). [clear] forgets
 * what was noted so far, for a test that only counts what a later step does.
 */
internal class ThreadRecorder {
    private val names = ArrayList<String>()

    fun note() {
        synchronized(names) { names += Thread.currentThread().name.substringBefore(" @") }
    }

    fun threads(): List<String> = synchronized(names) { names.toList() }

    fun clear() {
        synchronized(names) { names.clear() }
    }
}
