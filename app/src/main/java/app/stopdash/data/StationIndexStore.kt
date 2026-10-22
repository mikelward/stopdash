package app.stopdash.data

import android.content.Context
import app.stopdash.domain.StationIndex
import app.stopdash.domain.StationIndexFile

/**
 * The bundled station index (SPEC *Finding stops → Find a station*), built from TfL by
 * `scripts/build_station_index.py` and shipped in the build's station bundle ([StationBundleStore]).
 * Read once per process, off the main thread.
 *
 * Fails safe to [StationIndex.EMPTY]: a bundle that can't be read leaves the search on TfL's own
 * matching, as before the index existed, rather than breaking it (SPEC principle 2). A stop's page reads
 * that empty index as the failure it is and says so ([app.stopdash.domain.StopLinks.FAILED]).
 */
object StationIndexStore {
    /** The bundled index. Reads the bundle the first time: call it off the main thread. */
    fun load(context: Context): StationIndex = StationBundleStore.load(context)?.index ?: StationIndex.EMPTY

    /** The source file's text read as the index ([StationIndexFile.parse]), for tests of the file itself. */
    internal fun parse(text: String, warn: (String) -> Unit = {}): StationIndex = StationIndexFile.parse(text, warn)
}
