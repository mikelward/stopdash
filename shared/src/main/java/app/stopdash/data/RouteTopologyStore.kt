package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.withLive
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Loads the bundled route-topology asset into a [RouteTopology] (SPEC *Branch merging*). The
 * asset is static app data regenerated from TfL Route/Sequence, so it is read **once per
 * process** and cached — both the app and the widget worker share the one instance, and it is
 * warmed off the render path (the activity's `onCreate`, the widget's coroutine). The app then
 * puts TfL's current patterns over it where they still cover it ([use]), so a line extended since
 * the build is grouped as it runs now, and keeps those lines in the cache directory, so a process
 * the app didn't start (the widget's) and the next one it does group the same way.
 *
 * Fails safe to [RouteTopology.EMPTY]: a missing or corrupt asset, or a version this build
 * doesn't understand, degrades to "show TfL's branch as-is, merge nothing" rather than
 * crashing a surface (SPEC principle 2 — the screen must still paint). The version gate means a
 * later asset format can't be half-read by an older build.
 */
object RouteTopologyStore {
    private const val ASSET = "route_topology.json"
    private const val CURRENT_VERSION = 1

    // TfL's current patterns for the lines where they differ from the asset, in the asset's own
    // format, in the cache directory: never backed up, and cleared only back to the asset.
    private const val LIVE_FILE = "route-topology-live.json"

    private val json = Json { ignoreUnknownKeys = true }

    // The topology in use: the bundled one, or TfL's current patterns over it ([use]).
    @Volatile
    private var cached: RouteTopology? = null

    // The bundled asset as parsed, kept apart from [cached] so a refresh always starts from it.
    @Volatile
    private var bundled: RouteTopology? = null

    // The lines [LIVE_FILE] holds, once read.
    @Volatile
    private var stored: Map<String, List<RoutePattern>>? = null

    private val fileLock = Any()

    private val refreshed = MutableStateFlow<Map<String, List<RoutePattern>>>(emptyMap())

    /**
     * [refreshedLines] as they change in this process: set by [load] and each [use], so the phone's
     * watch sync can republish when a refresh changes what the watch should group by.
     */
    val refreshedChanges: StateFlow<Map<String, List<RoutePattern>>> = refreshed.asStateFlow()

    // The last topology [over] built, and the lines it was built from.
    @Volatile
    private var lastOver: Pair<Map<String, List<RoutePattern>>, RouteTopology>? = null

    /**
     * The topology if it is **already parsed and cached**, else [RouteTopology.EMPTY] — a
     * synchronous, IO-free peek. Once the process has loaded the asset (via [load]) this returns
     * the real value on the calling thread with no disk access, so a surface recreated while the
     * process is alive (an activity after rotation, its view models retained) can seed its first
     * frame with the merged grouping instead of [RouteTopology.EMPTY] and flickering split rows
     * once the async [load] catches up. Before the first load it is [RouteTopology.EMPTY], the
     * same safe default, and never touches the main thread with a parse.
     */
    fun cached(): RouteTopology = cached ?: RouteTopology.EMPTY

    /**
     * The topology in use: the bundled one with the lines an earlier refresh kept over it, where
     * they still cover it (a build with a newer asset checks them again), or what [use] has put in
     * place since. Read once per process and cached. Blocking file IO: call off the main thread.
     */
    fun load(context: Context): RouteTopology {
        cached?.let { return it }
        val bundled = bundled(context)
        val topology = bundled.withLive(stored(context))
        // A refresh that landed while the files were read stays in place.
        return cached ?: topology.also {
            cached = it
            refreshed.value = differing(it, bundled)
        }
    }

    /**
     * The bundled asset alone, parsed once and cached, whatever is in use: what a refresh compares
     * TfL's current patterns with ([app.stopdash.domain.withLive]). Safe to call from any thread.
     */
    fun bundled(context: Context): RouteTopology {
        bundled?.let { return it }
        val topology = try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            // Static asset, no user data in a failure — safe to name the failure mode in the log.
            parse(text) { Log.w("StopDash.Topology", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("StopDash.Topology", "route topology load failed: ${e::class.simpleName}")
            RouteTopology.EMPTY
        }
        bundled = topology
        return topology
    }

    /**
     * Puts TfL's [current] patterns in use over the bundled ones where they still cover them
     * ([withLive]), keeping what an earlier refresh left for a line this one didn't read, and returns
     * the topology now in use, which [load] and [cached] then return (SPEC *Branch merging*). The
     * lines that differ from the asset are saved for the next process; nothing is written when they
     * haven't changed. Blocking file IO: call off the main thread.
     */
    fun use(context: Context, current: Map<String, List<RoutePattern>>): RouteTopology = synchronized(fileLock) {
        val bundled = bundled(context)
        val before = stored(context)
        val topology = bundled.withLive(before + current)
        val differing = differing(topology, bundled)
        if (differing != before) save(context, differing)
        cached = topology
        refreshed.value = differing
        topology
    }

    /**
     * The lines of the topology in use that differ from the asset: TfL's current patterns where a
     * refresh took them. Usually none. What the phone sends the watch ([WatchEnvelope.routeLines]).
     * Blocking file IO on a process's first read: call off the main thread.
     */
    fun refreshedLines(context: Context): Map<String, List<RoutePattern>> = differing(load(context), bundled(context))

    /**
     * The asset with [lines] over it where they still cover it ([withLive]): what the watch groups
     * by, from the lines the phone sent ([refreshedLines]), checked against the watch's own asset.
     * The last one built is kept, so each render of the same envelope doesn't build it again.
     */
    fun over(context: Context, lines: Map<String, List<RoutePattern>>): RouteTopology {
        val bundled = bundled(context)
        if (lines.isEmpty()) return bundled
        lastOver?.let { (built, topology) -> if (built == lines) return topology }
        return bundled.withLive(lines).also { lastOver = lines to it }
    }

    private fun differing(topology: RouteTopology, bundled: RouteTopology): Map<String, List<RoutePattern>> =
        topology.patternsByLine.filter { (lineId, patterns) -> patterns != bundled.patternsByLine[lineId] }

    // What [LIVE_FILE] holds, read once; empty when there's none or it can't be read.
    private fun stored(context: Context): Map<String, List<RoutePattern>> {
        stored?.let { return it }
        return synchronized(fileLock) {
            stored ?: readStored(context).also { stored = it }
        }
    }

    private fun readStored(context: Context): Map<String, List<RoutePattern>> {
        val file = File(context.cacheDir, LIVE_FILE)
        if (!file.exists()) return emptyMap()
        return try {
            parse(file.readText()) { Log.w("StopDash.Topology", "refreshed $it") }.patternsByLine
        } catch (e: IOException) {
            discard(file, "unreadable", e)
        } catch (e: SerializationException) {
            discard(file, "unparseable", e)
        } catch (e: IllegalArgumentException) {
            discard(file, "invalid", e)
        }
    }

    // A file that can't be read is deleted, so the bundled topology stands until the next refresh.
    private fun discard(file: File, why: String, e: Exception): Map<String, List<RoutePattern>> {
        val deleted = file.delete()
        Log.w("StopDash.Topology", "refreshed route topology $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return emptyMap()
    }

    private fun save(context: Context, lines: Map<String, List<RoutePattern>>) {
        val file = File(context.cacheDir, LIVE_FILE)
        val tmp = File(file.path + ".tmp")
        // Recorded as kept only once it's on disk, so a failed save is tried again by the next
        // refresh in this process; until then the topology is in use here either way.
        try {
            if (lines.isEmpty()) {
                if (!file.exists() || file.delete()) stored = lines else Log.w("StopDash.Topology", "refreshed route topology not cleared")
            } else {
                tmp.writeText(json.encodeToString(TopologyFile(CURRENT_VERSION, lines.mapValues { (_, patterns) -> patterns.map(PersistedRoutePattern::of) })))
                // Replace in one step, so a reader never sees a half-written file.
                if (tmp.renameTo(file)) stored = lines else Log.w("StopDash.Topology", "refreshed route topology not saved: rename failed")
            }
        } catch (e: IOException) {
            Log.w("StopDash.Topology", "refreshed route topology not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Back to the state of a process that hasn't read anything yet, as a new one would start. */
    internal fun forget() {
        cached = null
        stored = null
        lastOver = null
        refreshed.value = emptyMap()
    }

    /**
     * Parse the asset text into a [RouteTopology]. Split out from [load] (and kept free of any
     * Android call) so the bundled asset can be validated by a plain JVM test. A wrong version,
     * or a pattern with too few stops / a blank terminus, is dropped rather than trusted; [warn]
     * reports the version case (the caller routes it to the log).
     */
    internal fun parse(text: String, warn: (String) -> Unit = {}): RouteTopology {
        val file = json.decodeFromString<TopologyFile>(text)
        if (file.version != CURRENT_VERSION) {
            warn("route topology version ${file.version} != $CURRENT_VERSION, ignoring")
            return RouteTopology.EMPTY
        }
        // Disable a whole line if any of its patterns is invalid (a blank endpoint, too few
        // stops) rather than silently dropping just that route: a missing pattern can leave a
        // leg looking single-path, and grouping() would then merge or drop a label where it
        // should have kept both branches. A disabled line is simply unknown to the topology, so
        // every arrival on it keeps TfL's raw label — the fail-safe default.
        return RouteTopology(
            file.lines.mapNotNull { (lineId, dtos) ->
                val patterns = dtos.map { it.toPattern() }
                if (patterns.any { it == null }) {
                    warn("route topology line '$lineId' has an invalid pattern; disabling that line")
                    null
                } else {
                    lineId to patterns.filterNotNull()
                }
            }.toMap(),
        )
    }

    @Serializable
    private data class TopologyFile(
        val version: Int = 0,
        val lines: Map<String, List<PersistedRoutePattern>> = emptyMap(),
    )
}

/**
 * A [RoutePattern] as the route topology asset, the refresh the app keeps, and the watch envelope
 * write it. [toPattern] is null for one too short or missing an end, which the reader then treats
 * as the whole line unknown ([RouteTopologyStore.parse]).
 */
@Serializable
data class PersistedRoutePattern(
    val branch: String? = null,
    val stops: List<String> = emptyList(),
    val endA: String = "",
    val endB: String = "",
) {
    fun toPattern(): RoutePattern? {
        if (stops.size < 2 || endA.isBlank() || endB.isBlank()) return null
        return RoutePattern(branch = branch?.ifBlank { null }, stops = stops, endA = endA, endB = endB)
    }

    companion object {
        fun of(pattern: RoutePattern) = PersistedRoutePattern(pattern.branch, pattern.stops, pattern.endA, pattern.endB)
    }
}

/** Each line whose patterns all read ([PersistedRoutePattern.toPattern]); a line with one that doesn't is left out. */
fun Map<String, List<PersistedRoutePattern>>.toPatterns(): Map<String, List<RoutePattern>> =
    mapNotNull { (lineId, persisted) ->
        val patterns = persisted.map { it.toPattern() }
        if (patterns.any { it == null }) null else lineId to patterns.filterNotNull()
    }.toMap()
