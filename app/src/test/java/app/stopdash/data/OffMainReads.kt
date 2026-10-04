package app.stopdash.data

import androidx.datastore.core.DataStore
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * For a store's "read off the main thread" test (AGENTS.md *Main thread: read and dispatch only*): a
 * [worker] on a thread of its own, named [WORKER], and a [DataStore] holding [stored] that records the
 * thread each read of it ran on ([reads]). The read and the store's mapping of it run in one flow, so
 * the thread a read ran on is the thread the mapping ran on.
 */
internal class OffMainReads<T>(private val stored: T) : Closeable {
    /** The thread each read of [dataStore] ran on. */
    val reads = mutableListOf<String>()

    val worker: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, WORKER) }.asCoroutineDispatcher()

    val dataStore: DataStore<T> = object : DataStore<T> {
        override val data: Flow<T> = flow {
            synchronized(reads) { reads += Thread.currentThread().name.substringBefore(" @") }
            emit(stored)
        }

        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(stored)
    }

    override fun close() = worker.close()

    companion object {
        const val WORKER = "store-worker"
    }
}
