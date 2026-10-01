package app.stopdash.data

import app.stopdash.domain.AppSettings
import app.stopdash.domain.AvoidedLines
import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.WalkingSpeed
import app.stopdash.domain.ModeGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The user's TfL `app_key` in force right now, for every TfL request in the process (SPEC D7).
 *
 * The behavior lives in [UserApiKeyHolder] (scope-injected, so it's unit-testable); this object is
 * the process-wide singleton every caller reads. Held here, warmed from [AppSettings], because the
 * request clients are long-lived — a nearby ViewModel's client is built once and outlives a later
 * paste — so they read the key through this cache ([KtorTflClient]'s `appKey` provider is
 * `{ current }`) rather than capturing it at construction. That way a key pasted in Settings raises
 * the request budget on the very next refresh, and clearing it returns to keyless, with nothing
 * rebuilt.
 *
 * The value is a plain volatile, never a disk read on the caller's thread: requests run off the
 * render path already, and this keeps the read free. **The key is a credential**: it is sent only
 * with the user's own TfL requests (its purpose — the higher budget), held in memory here and in
 * the private settings file, and it rides Android backup like the rest of their settings. It is
 * never logged and never placed in any other off-device artifact (SPEC *Privacy*, `docs/PRIVACY.md`).
 */
object UserApiKeySetting {
    private val holder = UserApiKeyHolder(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** The key applied to TfL requests right now, or null when keyless. See [UserApiKeyHolder]. */
    val current: String? get() = holder.current

    /** [current] as a flow, so a screen can act when the key changes. */
    val changes: StateFlow<String?> get() = holder.changes

    /**
     * True while the latest change failed to save: it holds in memory but not across a restart, nor
     * for the widget, which reads the stored key. A Clear that didn't save would leave a refused key
     * to come back, so the app says so ([writeFailed] until a later save succeeds).
     */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** Begins reading the stored key into [current] and keeps it live for later writes. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** The user pasted [key] (or cleared it — null/blank). Applied at once, persisted in order. */
    fun set(key: String?) = holder.set(key)
}

/**
 * The process-wide app_key cache's behavior, with its [scope] injected so a test can drive it on a
 * virtual clock. One instance backs [UserApiKeySetting]; production passes a `Dispatchers.Default`
 * scope. Blank normalizes to null (keyless).
 */
class UserApiKeyHolder(
    scope: CoroutineScope,
    // Which stored key this holder keeps: the TfL app_key by default, or another credential
    // ([RailApiKeySetting]) handled the same way.
    read: (AppSettings) -> Flow<String?> = AppSettings::userApiKey,
    write: suspend (AppSettings, String?) -> Unit = { settings, key -> settings.setUserApiKey(key) },
) : StoredSettingHolder<String?>(
    scope,
    initial = null,
    read = read,
    write = write,
    normalize = { key -> key?.trim()?.takeIf(String::isNotEmpty) },
    // The failure class only is logged, never the key itself (SPEC *Privacy*).
    label = "api key",
)

/**
 * A setting every part of the process reads synchronously ([current]), warmed from [AppSettings]
 * and written back in order. [UserApiKeyHolder] and [HiddenModesSetting] are its uses.
 */
open class StoredSettingHolder<T>(
    private val scope: CoroutineScope,
    initial: T,
    private val read: (AppSettings) -> Flow<T>,
    private val write: suspend (AppSettings, T) -> Unit,
    private val normalize: (T) -> T = { it },
    // Names the setting in a failed-write log line; never its value.
    private val label: String,
) {

    private var settings: AppSettings = AppSettings.NONE
    private var collectJob: Job? = null
    private var writeJob: Job? = null

    /**
     * Writes are drained in send order by one consumer, so two quick edits — a paste then a Clear —
     * can't race to DataStore on independent coroutines and land out of order (the earlier value
     * written last, restored after process death). FIFO and unbounded.
     */
    private val writes = Channel<T>(Channel.UNLIMITED)

    /**
     * Guards [current] and [userHasSet] so [set] (usually the main thread) and the store collector
     * (a background coroutine) can't interleave into a check-then-write race — an earlier version
     * gated the collector on a counter, but reading the counter and assigning [current] weren't
     * atomic, so the collector could still restore a stale value between a user's change and its
     * write (Codex P2). Both critical sections are two field writes with no suspension, so the
     * monitor is held only briefly.
     */
    private val lock = Any()

    /**
     * Whether the user has set the value in this process. This holder is the process's sole writer
     * of it, so every store emission after the warm read is an echo of one of our own writes — once
     * the user has made a choice, [current] is authoritative and those echoes must not roll it back
     * (that is exactly how a cleared key could otherwise be restored and then sent). Before the
     * first [set], the warm read seeds [current] from the store.
     */
    private var userHasSet = false

    // Completed once [current] holds the stored value (the warm read landed) or the user's own.
    private val loadedSignal = CompletableDeferred<Unit>()

    private val _isLoaded = MutableStateFlow(false)

    /**
     * True once [current] holds the stored value or the user's own: a screen offering the setting
     * disables its control until then, so a tap can't act on (and then overwrite the store with a
     * choice made over) [initial] shown in place of the stored value.
     */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /**
     * The value in force right now ([initial] until [warm] reads the stored one). Volatile so a
     * background request coroutine sees the latest change without synchronization.
     */
    @Volatile
    var current: T = initial
        private set(value) {
            field = value
            _changes.value = value
        }

    private val _changes = MutableStateFlow(initial)

    private val _writeFailed = MutableStateFlow(false)

    /**
     * True while the latest write has failed: the value holds in memory but won't survive the
     * process, so a screen that offers the setting says so (SPEC principle 2). Cleared by
     * [writeFailureShown], or by a later write that succeeds.
     */
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    /** The screen has told the user about a failed write. */
    fun writeFailureShown() {
        _writeFailed.value = false
    }

    /** [current] as a flow, so a screen can act when the value changes. */
    val changes: StateFlow<T> = _changes.asStateFlow()

    /**
     * Begins reading the stored value into [current], off the main thread, and keeps it live for
     * later writes. Idempotent — a second call is ignored — so it can be called from
     * `StopdashApp.onCreate` without stacking collectors. Reads before the warm read lands see
     * [initial] (for a key, keyless: the safe default, since a real key only raises the budget).
     */
    fun warm(appSettings: AppSettings) {
        settings = appSettings
        if (writeJob == null) {
            writeJob = scope.launch {
                for (value in writes) {
                    try {
                        write(settings, value)
                        // The latest choice is saved, so an earlier failure no longer describes it.
                        _writeFailed.value = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort: the value is already applied in memory, so a failed write
                        // leaves the app using it until a restart re-reads the store. Sanitized —
                        // the failure class only, never the value itself (SPEC *Privacy*).
                        logAppSettingsWarning("$label write failed: ${e::class.simpleName}")
                        _writeFailed.value = true
                    }
                }
            }
        }
        if (collectJob != null) return
        collectJob = scope.launch {
            read(appSettings).collect { stored ->
                // Seed from the store only until the user makes their own change; after that
                // [current] is theirs and store echoes of our writes must not roll it back. Under
                // the lock so this decision is atomic with a concurrent [set].
                synchronized(lock) {
                    if (!userHasSet) current = stored
                }
                loadedSignal.complete(Unit)
                _isLoaded.value = true
            }
        }
    }

    /**
     * [current] once the stored value has been read, for a caller that must not act on [initial]
     * before then (a cold start's first nearby pick). Returns at once when [warm] was never called
     * (tests, a build with no store), and after [LOAD_TIMEOUT_MILLIS] if the store is slow, so a
     * stuck read can delay but never block the caller.
     */
    suspend fun loaded(): T {
        if (collectJob != null) withTimeoutOrNull(LOAD_TIMEOUT_MILLIS) { loadedSignal.await() }
        return current
    }

    /**
     * The user changed the value to [value]. Applied to [current] at once so the next read uses it,
     * and persisted in order in the background.
     */
    fun set(value: T) {
        val normalized = normalize(value)
        synchronized(lock) {
            userHasSet = true
            current = normalized
        }
        loadedSignal.complete(Unit)
        _isLoaded.value = true
        writes.trySend(normalized)
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * The user's Rail Data Marketplace key in force right now, for National Rail's live departures
 * (SPEC *National Rail*): the same process-wide cache as [UserApiKeySetting], over its own stored
 * setting. Null means no key, and then no National Rail request is made. **A credential**: sent only
 * with the user's own National Rail requests, never logged or placed in any other off-device
 * artifact (`docs/PRIVACY.md`).
 */
object RailApiKeySetting {
    private val holder = UserApiKeyHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        read = AppSettings::railApiKey,
        write = { settings, key -> settings.setRailApiKey(key) },
    )

    /** The key applied to National Rail requests right now, or null. */
    val current: String? get() = holder.current

    /** [current] as a flow: a departures list refetches when it changes. */
    val changes: StateFlow<String?> get() = holder.changes

    /** Begins reading the stored key into [current]. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** The user pasted [key] (or cleared it). Applied at once, persisted in order. */
    fun set(key: String?) = holder.set(key)
}

/**
 * The transport modes the user has hidden from the near-me list (SPEC *Finding stops → Hiding a
 * mode*), in force right now for the nearby lookup, the list and the widget: the same process-wide
 * cache as [UserApiKeySetting], so each reads it without a disk read.
 */
object HiddenModesSetting {
    private val holder = StoredSettingHolder<Set<String>>(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = emptySet(),
        read = AppSettings::hiddenModes,
        write = { settings, modes -> settings.setHiddenModes(modes) },
        label = "hidden modes",
    )

    /** The hidden modes right now; empty (everything shows) until [warm] reads the stored set. */
    val current: Set<String> get() = holder.current

    /** The hidden modes once the stored set has been read (see [StoredSettingHolder.loaded]). */
    suspend fun loaded(): Set<String> = holder.loaded()

    /** [current] as a flow, for the list and its banner. */
    val changes: StateFlow<Set<String>> get() = holder.changes

    /** Begins reading the stored set into [current]. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Hides [mode] (or shows it again when [hidden] is false). Applied at once, persisted in order. */
    fun setHidden(mode: String, hidden: Boolean) =
        holder.set(if (hidden) current + mode else current.filterNot { it.equals(mode, ignoreCase = true) }.toSet())

    /** Hides all of [group] (or shows it again when [hidden] is false). */
    fun setGroupHidden(group: ModeGroups.Group, hidden: Boolean) =
        holder.set(ModeGroups.withGroup(current, group, hidden))

    /** Shows every mode again. */
    fun showAll() = holder.set(emptySet())

    /** True while the latest change failed to save (cleared by a later successful save); see [writeFailureShown]. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** The list has told the user a change didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * The walking-speed choice (SPEC *Trips with a change*), held in memory for a trip to read before its
 * first plan and persisted in order. Starts at [WalkingSpeed.AVERAGE], the stored default and the
 * Planner's own, so a plan made before [warm] lands is timed as it always was.
 */
object WalkingSpeedSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = WalkingSpeed.AVERAGE,
        read = AppSettings::walkingSpeed,
        write = { settings, speed -> settings.setWalkingSpeed(speed) },
        label = "walking speed",
    )

    /** [WalkingSpeed] as a flow, for a trip's plans, its dropdown and the Settings row. */
    val changes: StateFlow<WalkingSpeed> get() = holder.changes

    /** Begins reading the stored choice. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored choice has been read, so Settings can hold its row until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** The user chose [speed]: applied at once, persisted in order. */
    fun set(speed: WalkingSpeed) = holder.set(speed)

    /** True while the latest choice failed to save (a later successful save clears it); Settings says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** Settings has told the user a choice didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * The max-walk choice (SPEC *Trips with a change*), held in memory for a trip to read before its
 * first plan and persisted in order. Starts at [MaxWalk.DEFAULT], the stored default, so a plan made
 * before [warm] lands asks for the walks a rider who never chose gets.
 */
object MaxWalkSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = MaxWalk.DEFAULT,
        read = AppSettings::maxWalk,
        write = { settings, maxWalk -> settings.setMaxWalk(maxWalk) },
        label = "max walk",
    )

    /** [MaxWalk] as a flow, for a trip's plans and its dropdown. */
    val changes: StateFlow<MaxWalk> get() = holder.changes

    /** Begins reading the stored choice. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored choice has been read, so Settings can hold its row until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** The user chose [maxWalk]: applied at once, persisted in order. */
    fun set(maxWalk: MaxWalk) = holder.set(maxWalk)

    /** True while the latest choice failed to save (a later successful save clears it); the trip says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** The trip has told the user a choice didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * The step-free choice (SPEC *Trips with a change*), held in memory for a trip to read before its
 * first plan and persisted in order. Starts at [StepFree.DEFAULT], no requirement; a trip waits for
 * [isLoaded] before planning, so a rider who needs step-free routes isn't shown others first.
 */
object StepFreeSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = StepFree.DEFAULT,
        read = AppSettings::stepFree,
        write = { settings, stepFree -> settings.setStepFree(stepFree) },
        label = "step-free",
    )

    /** [StepFree] as a flow, for a trip's plans and its dropdown. */
    val changes: StateFlow<StepFree> get() = holder.changes

    /** Begins reading the stored choice. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored choice has been read, so a trip and Settings can hold until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** The user chose [stepFree]: applied at once, persisted in order. */
    fun set(stepFree: StepFree) = holder.set(stepFree)

    /** True while the latest choice failed to save (a later successful save clears it); the screen says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** The screen has told the user a choice didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * Which kinds of transport a trip may ride (SPEC *Trips with a change*), held in memory for a trip
 * to read before its first plan and persisted in order. Starts at [TripModes.DEFAULT], every group;
 * a trip waits for [isLoaded] before planning, so a rider who turned buses off isn't shown them first.
 */
object TripModesSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = TripModes.DEFAULT,
        read = AppSettings::tripModes,
        write = { settings, modes -> settings.setTripModes(modes) },
        label = "trip modes",
    )

    /** [TripModes] as a flow, for a trip's plans and its toggles. */
    val changes: StateFlow<TripModes> get() = holder.changes

    /** Begins reading the stored choice. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored choice has been read, so a trip can hold until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** The user chose [modes]: applied at once, persisted in order. */
    fun set(modes: TripModes) = holder.set(modes)

    /** True while the latest choice failed to save (a later successful save clears it); the screen says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** The screen has told the user a choice didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * The lines a trip avoids ([AvoidedLines]; SPEC *Trips with a change → Avoiding a line*), held in memory
 * for a trip to read before its first plan and persisted in order. Starts with none; a trip waits for
 * [isLoaded] before planning, so a route on a line the rider avoids isn't shown first.
 */
object AvoidedLinesSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = emptySet<String>(),
        read = AppSettings::avoidedLines,
        write = { settings, lines -> settings.setAvoidedLines(lines) },
        label = "avoided lines",
    )

    /** The avoided lines as a flow, for a trip's routes, its chips and Settings. */
    val changes: StateFlow<Set<String>> get() = holder.changes

    /** Begins reading the stored lines. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored lines have been read, so a trip can hold until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** Avoid the line [entry] ([AvoidedLines.key]), or stop avoiding it: applied at once, persisted in order. */
    fun setAvoided(entry: String, avoided: Boolean) =
        holder.set(if (avoided) holder.current + entry else holder.current - entry)

    /** True while the latest change failed to save (a later successful save clears it); the screen says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** The screen has told the user a change didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}

/**
 * The distance-units choice (SPEC *Finding stops*), held in memory for the list to read on its
 * first frame and persisted in order. Starts at [DistanceUnits.AUTOMATIC] — the stored default —
 * so a frame drawn before [warm] lands follows the locale, as a user who never chose would see.
 */
object DistanceUnitsSetting {
    private val holder = StoredSettingHolder(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        initial = DistanceUnits.AUTOMATIC,
        read = AppSettings::distanceUnits,
        write = { settings, units -> settings.setDistanceUnits(units) },
        label = "distance units",
    )

    /** [DistanceUnits] as a flow, for the list's labels and the Settings row. */
    val changes: StateFlow<DistanceUnits> get() = holder.changes

    /** Begins reading the stored choice. Idempotent. */
    fun warm(appSettings: AppSettings) = holder.warm(appSettings)

    /** Whether the stored choice has been read, so Settings can hold its row until then. */
    val isLoaded: StateFlow<Boolean> get() = holder.isLoaded

    /** The user chose [units]: applied at once, persisted in order. */
    fun set(units: DistanceUnits) = holder.set(units)

    /** True while the latest choice failed to save (a later successful save clears it); Settings says so. */
    val writeFailed: StateFlow<Boolean> get() = holder.writeFailed

    /** Settings has told the user a choice didn't save. */
    fun writeFailureShown() = holder.writeFailureShown()
}
