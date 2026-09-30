package app.stopdash.telemetry

import android.content.Context
import app.stopdash.StopdashDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/** Where the "Help make StopDash better" choice is kept. */
interface ConsentStore {
    /** The stored choice, or null if the user has never answered. Blocking. */
    fun read(): Boolean?

    /** Stores [optedIn] durably before returning; false if the write failed. Blocking. */
    fun save(optedIn: Boolean): Boolean

    /**
     * Deletes the stored choice, so it reads as never answered (off); false if it couldn't. The
     * fallback when an opt-out can't be written: a delete can succeed where a write fails (a full
     * disk). Blocking.
     */
    fun forget(): Boolean = false
}

/**
 * The choice in the app's private SharedPreferences, written with `commit()` so it's on disk
 * before a tap returns — an opt-out must survive a kill the instant after. Device-local on
 * purpose: this file and the SDKs' own collection flags are excluded from backup
 * (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`), so a restored install starts with
 * nothing stored and asks again.
 */
class PrefsConsentStore(context: Context) : ConsentStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): Boolean? = if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null

    override fun save(optedIn: Boolean): Boolean = prefs.edit().putBoolean(KEY, optedIn).commit()

    override fun forget(): Boolean = appContext.deleteSharedPreferences(PREFS)

    private companion object {
        const val PREFS = "telemetry"
        const val KEY = "opted_in"
    }
}

/**
 * The gate's pending opt-in, kept apart from the consent prefs as the presence of a file in
 * `noBackupFilesDir` (never backed up or transferred). Separate so that a withdrawal whose prefs
 * write fails still clears it: clearing is a delete, which succeeds even on a full disk, so a
 * stale "pending" can't later vouch for a "yes" the user withdrew. Any failure reads or saves as
 * not pending, which [TelemetryConsentHolder.load] resolves to off.
 */
class FilePendingMarker(private val file: File) : PendingMarker {
    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    override fun read(): Boolean = try {
        file.exists()
    } catch (e: SecurityException) {
        StopdashDebugLog.warning("telemetry: pending opt-in unreadable: %s", e::class.simpleName)
        false
    }

    override fun save(pending: Boolean): Boolean = try {
        if (pending) file.exists() || file.createNewFile() else !file.exists() || file.delete()
    } catch (e: IOException) {
        StopdashDebugLog.warning("telemetry: pending opt-in write failed: %s", e::class.simpleName)
        false
    } catch (e: SecurityException) {
        StopdashDebugLog.warning("telemetry: pending opt-in write failed: %s", e::class.simpleName)
        false
    }

    private companion object {
        const val FILE_NAME = "telemetry_opt_in_pending"
    }
}

/**
 * The choice in force right now (SPEC *Privacy*): the one source of truth the Settings switch,
 * the collection gate and the Crashlytics sink all read, so the switch can never show one thing
 * while telemetry does another. The process-wide instance; behavior in [TelemetryConsentHolder].
 */
object TelemetryConsent {
    private val holder = TelemetryConsentHolder()

    /** Null until the stored choice is loaded, then the user's answer. */
    val state: StateFlow<Boolean?> get() = holder.state

    /** Loads the stored choice and applies it to [gate] (null in a build without Firebase). Blocking. */
    fun load(store: ConsentStore, gate: TelemetryGate?) = holder.load(store, gate)

    /** Loading couldn't run at all: fail closed (SDKs off, switch off). */
    fun loadFailed(gate: TelemetryGate?) = holder.loadFailed(gate)

    /** The user flipped the switch: an opt-out reaches the SDKs before returning; see the holder. */
    fun set(enabled: Boolean) = holder.set(enabled)
}

/**
 * [TelemetryConsent]'s behavior. Every change is ordered so that neither a failed write nor a kill
 * mid-change can leave collection on against the user's choice:
 *
 * - [set]: a withdrawal switches the SDKs off before it's stored; an opt-in is stored first and
 *   only then handed to the SDKs ([TelemetryGate], which may start collection on a later launch).
 *   An opt-in whose save fails isn't applied at all. So a kill or a failed save anywhere in between
 *   leaves the stored value and the SDKs' own persisted flag disagreeing, with the SDKs off.
 * - [load] trusts the stored choice only when the SDKs agree with it (or it's a pending opt-in).
 *   Any disagreement (a failed save, a kill between the two steps, a backup restored onto a fresh
 *   install) resolves to **off** and is stored as off: the user asks again, and nothing is
 *   collected they didn't agree to in this install.
 */
class TelemetryConsentHolder {
    private var store: ConsentStore? = null
    private var gate: TelemetryGate? = null
    private val lock = Any()

    private val _state = MutableStateFlow<Boolean?>(null)
    val state: StateFlow<Boolean?> = _state.asStateFlow()

    fun load(store: ConsentStore, gate: TelemetryGate?) {
        synchronized(lock) {
            this.store = store
            this.gate = gate
            gate?.onOptInLost = ::optInLost
            // Fails closed: an unreadable store or a throwing SDK leaves collection off and the
            // switch usable (showing off), never collecting with no way to withdraw.
            val choice = try {
                val stored = store.read() ?: false
                // A pending opt-in is a yes whose collection waits on a clean launch (TelemetryGate),
                // so the SDKs being off then is agreement, not a half-done switch.
                val consistent = gate == null || gate.collecting == stored || (stored && gate.pendingOptIn)
                (stored && consistent).also { choice ->
                    if (choice != stored && !store.save(choice)) {
                        StopdashDebugLog.warning("telemetry: could not store the reset opt-in")
                    }
                }
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: consent load failed: %s", e::class.simpleName)
                false
            }
            try {
                gate?.apply(choice)
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: applying consent failed: %s", e::class.simpleName)
                if (choice) {
                    // The full withdrawal, not just a switch-off: each failing step is logged, and the
                    // stored yes is replaced (or deleted), so the next start can't read it back as on.
                    withdraw()
                    return
                }
            }
            _state.value = choice
        }
    }

    /**
     * The load itself couldn't run (the store couldn't even be opened): switch the SDKs off if there
     * are any, and show the choice as off so the user can still act on it.
     */
    fun loadFailed(gate: TelemetryGate?) {
        synchronized(lock) {
            this.gate = gate
            runCatching { gate?.apply(false) }
                .onFailure { StopdashDebugLog.warning("telemetry: fail-closed switch-off failed: %s", it::class.simpleName) }
            _state.value = false
        }
    }

    // The gate couldn't record a pending opt-in, so the next start would read it as half-done:
    // withdraw it now, visibly, rather than show a yes that isn't collecting and will be reset.
    private fun optInLost() {
        synchronized(lock) {
            StopdashDebugLog.warning("telemetry: pending opt-in could not be saved; switched off")
            withdraw()
        }
    }

    fun set(enabled: Boolean) {
        synchronized(lock) {
            if (!enabled) {
                withdraw()
                return
            }
            // A new opt-in is stored before it reaches the SDKs, so a kill between the two leaves a
            // mismatch with the SDKs off, which [load] reads as off. No store (it couldn't be
            // opened) counts as a failed save: an opt-in nothing can record isn't applied.
            val saved = try {
                store?.save(true) == true
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: opt-in write threw: %s", e::class.simpleName)
                false
            }
            if (!saved) {
                // Not durable, so not applied: the switch stays off rather than collect on a choice
                // the next start can't see.
                StopdashDebugLog.warning("telemetry: opt-in write failed; left off")
                _state.value = false
                return
            }
            // Published before applying, so a lost opt-in reported during apply has the last word.
            _state.value = true
            try {
                gate?.apply(true)
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: applying the opt-in failed: %s", e::class.simpleName)
                withdraw()
            }
        }
    }

    /**
     * Turns collection off, failing closed at every step: the in-memory state first — so the log
     * sink, which reads it at delivery, stops before the SDKs' reports are cleared and nothing
     * queued lands after the cleanup — then the SDKs, then the stored choice. A step that throws is
     * logged and the rest still run; an SDK left on is caught at the next start as a mismatch.
     */
    private fun withdraw() {
        _state.value = false
        try {
            gate?.apply(false)
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: switching the SDKs off failed: %s", e::class.simpleName)
        }
        val saved = try {
            store?.save(false) != false
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: opt-out write threw: %s", e::class.simpleName)
            false
        }
        if (saved) return
        // The stored yes must not outlive this: with a pending marker that also failed to clear,
        // the pair would read as an opt-in waiting to start. Deleting the choice leaves nothing to
        // read but off.
        val forgotten = try {
            store?.forget() == true
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: forgetting the opt-in threw: %s", e::class.simpleName)
            false
        }
        StopdashDebugLog.warning(
            if (forgotten) "telemetry: opt-out write failed; stored choice deleted instead"
            else "telemetry: opt-out write and delete failed; the SDKs are off",
        )
    }
}
