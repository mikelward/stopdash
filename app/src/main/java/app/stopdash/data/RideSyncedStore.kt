package app.stopdash.data

import app.stopdash.domain.SyncedStore

/**
 * Favorite rides as the sync runner sees them ([app.stopdash.domain.SyncRun]): [RideSync]'s entries,
 * read and applied through [store]'s own edits, under its write lock.
 */
internal class RideSyncedStore(private val store: DataStoreFavoriteJourneysStore) : SyncedStore {
    override val kinds: List<String> = RideSync.KINDS

    override suspend fun entries(): Map<String, Map<String, String>>? = store.syncEntries()

    override suspend fun apply(expected: Map<String, Map<String, String>>, merged: Map<String, Map<String, String>>, mark: String): Boolean =
        store.applySync(expected, merged, mark)

    override suspend fun mark(): String? = store.syncMark()

    override fun understood(kind: String, value: String): Boolean = RideSync.understood(kind, value)

    override fun same(kind: String, a: String, b: String): Boolean = RideSync.same(kind, a, b)
}
