package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.RouteTopology
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled route-topology asset into a [RouteTopology] (SPEC *Branch merging*). The
 * asset is static app data regenerated from TfL Route/Sequence, so it is read **once per
 * process** and cached — both the app and the widget worker share the one instance, and it is
 * warmed off the render path (the activity's `onCreate`, the widget's coroutine). The app then
 * puts TfL's current patterns over it where they still cover it ([use]), so a line extended since
 * the build is grouped as it runs now.
 *
 * Fails safe to [RouteTopology.EMPTY]: a missing or corrupt asset, or a version this build
 * doesn't understand, degrades to "show TfL's branch as-is, merge nothing" rather than
 * crashing a surface (SPEC principle 2 — the screen must still paint). The version gate means a
 * later asset format can't be half-read by an older build.
 */
object RouteTopologyStore {
    private const val ASSET = "route_topology.json"
    private const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    // The topology in use: the bundled one, or TfL's current patterns over it ([use]).
    @Volatile
    private var cached: RouteTopology? = null

    // The bundled asset as parsed, kept apart from [cached] so a refresh always starts from it.
    @Volatile
    private var bundled: RouteTopology? = null

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
     * The topology in use: the bundled one, parsed once and cached, or the refreshed one once [use]
     * has put it in place. Safe to call from any thread.
     */
    fun load(context: Context): RouteTopology {
        cached?.let { return it }
        val topology = bundled(context)
        // A refresh that landed while the asset was read stays in place.
        return cached ?: topology.also { cached = it }
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
     * Puts [topology] in use for the rest of the process: the bundled one with TfL's current
     * patterns over it, which [load] and [cached] then return (SPEC *Branch merging*).
     */
    fun use(topology: RouteTopology) {
        cached = topology
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
        val lines: Map<String, List<PatternDto>> = emptyMap(),
    )

    @Serializable
    private data class PatternDto(
        val branch: String? = null,
        val stops: List<String> = emptyList(),
        val endA: String = "",
        val endB: String = "",
    ) {
        fun toPattern(): RoutePattern? {
            if (stops.size < 2 || endA.isBlank() || endB.isBlank()) return null
            return RoutePattern(branch = branch?.ifBlank { null }, stops = stops, endA = endA, endB = endB)
        }
    }
}
