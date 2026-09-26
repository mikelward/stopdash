package app.stopdash.data

import java.io.File
import java.util.EnumMap

/**
 * Each station search's own recent picks (SPEC *Finding stops*): *From…*'s opened stations and
 * *To…*'s destinations kept apart, each a [FileRecentStationsStore] in [dir] (the app's no-backup
 * directory), so a search lists and records only its own history. One store per search, so its
 * writes are serialized.
 */
internal class RecentSearches(
    private val dir: File,
    private val warn: (String) -> Unit = {},
) {
    enum class Kind(val fileName: String) {
        // The file From… has always used, so its history carries over.
        FROM("recent-stations.json"),
        TO("recent-destinations.json"),
    }

    private val stores = EnumMap<Kind, FileRecentStationsStore>(Kind::class.java)

    @Synchronized
    fun store(kind: Kind): FileRecentStationsStore =
        stores.getOrPut(kind) { FileRecentStationsStore(File(dir, kind.fileName), warn) }
}
