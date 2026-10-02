package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.StepFreeAccess
import app.stopdash.domain.StepFreeLevel
import app.stopdash.domain.StepFreePlatform
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled step-free access table (`stations/step_free.json`, built from TfL's station
 * data by `scripts/build_step_free.py`) once per process, off the main thread. Fails safe to
 * [StepFreeAccess.EMPTY]: a missing, corrupt or newer-format asset means no step-free marks, never
 * a crash or a guess. A platform whose level this build doesn't know (a newer builder's) is left
 * out, so it reads as undescribed rather than as any level.
 */
object StepFreeStore {
    private const val ASSET = "stations/step_free.json"
    private const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: StepFreeAccess? = null

    fun load(context: Context): StepFreeAccess {
        cached?.let { return it }
        val access = try {
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) { Log.w("StopDash.StepFree", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Static asset, no user data in the failure.
            Log.w("StopDash.StepFree", "step-free table load failed: ${e::class.simpleName}")
            StepFreeAccess.EMPTY
        }
        cached = access
        return access
    }

    internal fun parse(text: String, warn: (String) -> Unit = {}): StepFreeAccess {
        val file = json.decodeFromString<TableFile>(text)
        if (file.version != CURRENT_VERSION) {
            warn("step-free table version ${file.version} != $CURRENT_VERSION, ignoring")
            return StepFreeAccess.EMPTY
        }
        var unknown = 0
        val stops = file.stops.mapValues { (_, lines) ->
            lines.mapValues { (_, platforms) ->
                platforms.mapNotNull { p ->
                    val level = LEVELS[p.level] ?: run {
                        unknown++
                        return@mapNotNull null
                    }
                    StepFreePlatform(level, p.platform, p.direction, p.where, p.limitedLift, p.entrance)
                }
            }.filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
        if (unknown > 0) warn("step-free table: $unknown platforms of an unknown level left out")
        return StepFreeAccess(stops)
    }

    private val LEVELS = mapOf(
        "none" to StepFreeLevel.NONE,
        "platform" to StepFreeLevel.PLATFORM,
        "ramp" to StepFreeLevel.RAMP,
        "level" to StepFreeLevel.LEVEL,
    )

    @Serializable
    private data class TableFile(val version: Int = 0, val stops: Map<String, Map<String, List<PlatformEntry>>> = emptyMap())

    @Serializable
    private data class PlatformEntry(
        val level: String = "",
        val platform: String = "",
        val direction: String = "",
        val where: String = "",
        val limitedLift: Boolean = false,
        val entrance: String = "",
    )
}
