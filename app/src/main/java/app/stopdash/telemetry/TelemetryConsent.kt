package app.stopdash.telemetry

import android.content.Context
import app.stopdash.StopdashDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

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
 * One of two marks that keep the user's answer apart from the stored choice ([ConsentStore]), so
 * that losing that one record on its own doesn't lose the answer (maintainer, 2026-10-01): one marks
 * a no, the other a yes. A withdrawal sets the no before anything else and clears the yes; an opt-in
 * clears the no before anything else and sets the yes once it's stored. A set no outranks the stored
 * choice; a set yes is read only where the stored choice is missing.
 */
interface ConsentMark {
    /**
     * Whether it's set; null for a mark that keeps nothing ([NoConsentMark]). Throws when a kept mark
     * can't be read, which fails the load closed, as a store that can't be read does.
     */
    fun read(): Boolean?

    fun save(set: Boolean): Boolean

    /**
     * Sets this mark by moving [other] onto it, in one step that clears [other] with it; false where
     * that isn't done (as with nothing to move). A withdrawal whose no mark can't be created moves
     * the yes mark onto it instead: one step, where clearing the yes and deleting the stored choice
     * is two, with a yes still read back between them.
     */
    fun takeOver(other: ConsentMark): Boolean = false
}

/**
 * A mark that keeps nothing, so it says nothing: a lost stored choice is then never answered, as
 * before the marks existed.
 */
object NoConsentMark : ConsentMark {
    override fun read(): Boolean? = null

    override fun save(set: Boolean) = true
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
class FilePendingMarker(file: File) : PendingMarker {
    constructor(context: Context) : this(File(context.noBackupFilesDir, FILE_NAME))

    private val mark = FileMark(file, "pending opt-in")

    override fun read(): Boolean = mark.read() ?: false

    override fun save(pending: Boolean): Boolean = mark.save(pending)

    private companion object {
        const val FILE_NAME = "telemetry_opt_in_pending"
    }
}

/**
 * A [ConsentMark], kept as the presence of a file in `noBackupFilesDir` beside the pending opt-in,
 * apart from the consent prefs: a separate file, so the prefs lost or corrupted on their own leave it
 * standing, and like them never backed up or transferred, so a restored install still starts
 * unanswered. A read that fails throws, which [TelemetryConsentHolder.load] takes as a load failure,
 * closed: it can't say which answer was last given.
 */
class FileConsentMark(file: File, private val what: String) : ConsentMark {
    private val mark = FileMark(file, what)

    override fun read(): Boolean = mark.read() ?: throw IllegalStateException("$what unreadable")

    override fun save(set: Boolean): Boolean = mark.save(set)

    override fun takeOver(other: ConsentMark): Boolean = other is FileConsentMark && mark.takeOver(other.mark)

    companion object {
        fun optOut(context: Context) = FileConsentMark(File(context.noBackupFilesDir, "telemetry_opted_out"), "opt-out mark")

        fun optIn(context: Context) = FileConsentMark(File(context.noBackupFilesDir, "telemetry_opted_in"), "opt-in mark")
    }
}

// A flag kept as whether a file exists: set by creating it, cleared by deleting it, so clearing works
// even on a full disk. A failure is logged as [what], never with the path. A read that can't tell is
// null: `File.exists()` reads a stat that fails (an I/O error, a path that isn't a directory) as
// absent, so presence and absence are each asked for outright, and neither answering is neither
// (Codex, PR #454).
private class FileMark(private val file: File, private val what: String) {
    fun read(): Boolean? = try {
        val path = file.toPath()
        when {
            Files.exists(path) -> true
            Files.notExists(path) -> false
            else -> {
                StopdashDebugLog.warning("telemetry: %s unreadable", what)
                null
            }
        }
    } catch (e: SecurityException) {
        StopdashDebugLog.warning("telemetry: %s unreadable: %s", what, e::class.simpleName)
        null
    } catch (e: InvalidPathException) {
        StopdashDebugLog.warning("telemetry: %s unreadable: %s", what, e::class.simpleName)
        null
    }

    // Done outright, not after a check that can misread a failure as done: a create that finds the
    // file already there, or a delete that finds it already gone, is the flag as asked.
    fun save(set: Boolean): Boolean = try {
        val path = file.toPath()
        if (set) {
            try {
                Files.createFile(path)
            } catch (e: FileAlreadyExistsException) {
                // Already set: as asked.
            }
        } else {
            Files.deleteIfExists(path)
        }
        true
    } catch (e: IOException) {
        StopdashDebugLog.warning("telemetry: %s write failed: %s", what, e::class.simpleName)
        false
    } catch (e: SecurityException) {
        StopdashDebugLog.warning("telemetry: %s write failed: %s", what, e::class.simpleName)
        false
    } catch (e: InvalidPathException) {
        StopdashDebugLog.warning("telemetry: %s write failed: %s", what, e::class.simpleName)
        false
    }

    // A rename: one step, and one that needs no new file, so it can land where a create can't.
    fun takeOver(other: FileMark): Boolean = try {
        Files.move(other.file.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: NoSuchFileException) {
        // Nothing to move: the other mark isn't set.
        false
    } catch (e: IOException) {
        StopdashDebugLog.warning("telemetry: %s move failed: %s", what, e::class.simpleName)
        false
    } catch (e: SecurityException) {
        StopdashDebugLog.warning("telemetry: %s move failed: %s", what, e::class.simpleName)
        false
    } catch (e: InvalidPathException) {
        StopdashDebugLog.warning("telemetry: %s move failed: %s", what, e::class.simpleName)
        false
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

    /** Whether to put the question to the user: loaded, and never answered on this install. */
    val unanswered: StateFlow<Boolean> get() = holder.unanswered

    /**
     * Loads the stored choice and applies it to [gate] (null in a build without Firebase), reading a
     * lost one back from the marks [optOut] and [optIn]. Blocking.
     */
    fun load(store: ConsentStore, gate: TelemetryGate?, optOut: ConsentMark = NoConsentMark, optIn: ConsentMark = NoConsentMark) =
        holder.load(store, gate, optOut, optIn)

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
 *   install) resolves to **off**, and the stored yes is deleted: the user is asked again, and
 *   nothing is collected they didn't agree to in this install. So is a yes the SDKs fail to take
 *   up after a tap.
 * - The answer is also kept in two marks apart from the stored choice ([ConsentMark]): a no, set
 *   first on a withdrawal and cleared first on an opt-in, which outranks the stored choice, so a
 *   kill partway through a withdrawal still reads as the no; and a yes, set once an opt-in is
 *   stored. A stored choice missing on its own (its file lost or corrupted while the rest stood) is
 *   read back from them rather than asked again ([answer], maintainer 2026-10-01). Never from the
 *   SDKs' flags or a pending opt-in: before the marks, a deleted stored choice was how a withdrawal
 *   that couldn't write its no left things off, with those flags possibly still set (Codex, PR #454).
 *   The SDKs start themselves from their own flag before the app reads anything, so an opted-in
 *   start's crashes are reported at no cost to it.
 */
class TelemetryConsentHolder {
    private var store: ConsentStore? = null
    private var gate: TelemetryGate? = null
    private var optOut: ConsentMark = NoConsentMark
    private var optIn: ConsentMark = NoConsentMark
    private val lock = Any()

    private val _state = MutableStateFlow<Boolean?>(null)
    val state: StateFlow<Boolean?> = _state.asStateFlow()

    // True only once a load has read no stored choice at all, until the user gives one (either way):
    // the home screen's invite (SPEC *Privacy*). False while loading, and after a load that couldn't
    // read the store, where an answer couldn't be kept either.
    private val _unanswered = MutableStateFlow(false)
    val unanswered: StateFlow<Boolean> = _unanswered.asStateFlow()

    fun load(store: ConsentStore, gate: TelemetryGate?, optOut: ConsentMark = NoConsentMark, optIn: ConsentMark = NoConsentMark) {
        synchronized(lock) {
            this.store = store
            this.gate = gate
            this.optOut = optOut
            this.optIn = optIn
            gate?.onOptInLost = ::optInLost
            // Fails closed: an unreadable store or a throwing SDK leaves collection off and the
            // switch usable (showing off), never collecting with no way to withdraw.
            var neverAnswered = false
            val choice = try {
                val read = store.read()
                val out = optOut.read()
                // Read only where it can speak: no stored choice, and no no outranking it.
                val marked = if (read == null && out != true) optIn.read() else null
                val answer = answer(read, out, marked)
                // What's stored is brought into line with the answer: a lost choice written back, and
                // a yes a later no outranks replaced (Codex, PR #454).
                if (answer != null && answer != read && !store.save(answer)) {
                    StopdashDebugLog.warning("telemetry: could not store the recalled choice")
                }
                neverAnswered = answer == null
                val stored = answer ?: false
                // A pending opt-in is a yes whose collection waits on a clean launch (TelemetryGate),
                // so the SDKs being off then is agreement, not a half-done switch.
                val consistent = gate == null || gate.collecting == stored || (stored && gate.pendingOptIn)
                (stored && consistent).also { choice ->
                    if (choice != stored) {
                        // A yes the SDKs never took up (a kill mid-change, a failed opt-out, a restore)
                        // is reset to off, and the user asked again (SPEC *Privacy*): the stored choice
                        // is deleted, so this install reads as never answered until they do answer,
                        // and the invite puts the question (Codex, PR #447). Where it can't be deleted,
                        // off is stored instead: still off, though the question then isn't put again.
                        neverAnswered = true
                        // Its yes mark goes first, or the deleted choice would read back from it. Where
                        // that fails, off is stored rather than the choice deleted.
                        if (!(unmarkYes() && store.forget()) && !store.save(false)) {
                            StopdashDebugLog.warning("telemetry: could not store the reset opt-in")
                        }
                    } else if (answer != null) {
                        // The marks brought into line with the answer, so an install upgraded with its
                        // answer gets the same keeping (Codex, PR #454).
                        keepMarks(answer, out)
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
                    // The full rollback, not just a switch-off: each failing step is logged, and the
                    // stored yes is deleted (or replaced), so the next start can't read it back as on.
                    rollBackOptIn()
                    return
                }
            }
            _state.value = choice
            _unanswered.value = neverAnswered
        }
    }

    // The user's answer, from the stored choice [read] and the marks: [out] for a no, [marked] for a
    // yes (null: not kept, or not read). The no outranks everything: a withdrawal sets it before
    // anything else, so a kill partway through still leaves the old yes stored and the SDKs on, and
    // the mark is the no that followed them (Codex, PR #454); a mark that can't be read has already
    // failed the load closed. Otherwise the stored choice; and where that's missing, never given or
    // lost on its own, the yes mark. Null when nothing says: never answered, and asked.
    private fun answer(read: Boolean?, out: Boolean?, marked: Boolean?): Boolean? = when {
        out == true -> false
        read != null -> read
        marked == true -> true
        else -> null
    }

    // Both marks set to [yes], the answer just loaded: a no from before the marks existed is marked
    // too, a yes the same, and the other cleared. Best effort: each failure is logged.
    private fun keepMarks(yes: Boolean, out: Boolean?) {
        if (yes) {
            if (!optIn.save(true)) StopdashDebugLog.warning("telemetry: opt-in mark not set")
            return
        }
        if (out == false && !optOut.save(true)) StopdashDebugLog.warning("telemetry: opt-out mark not set")
        unmarkYes()
    }

    // Clears the yes mark, so a stored choice deleted after it can't read back as a yes. False, and
    // logged, where it can't.
    private fun unmarkYes(): Boolean {
        val cleared = try {
            optIn.save(false)
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: clearing the opt-in mark threw: %s", e::class.simpleName)
            false
        }
        if (!cleared) StopdashDebugLog.warning("telemetry: opt-in mark not cleared")
        return cleared
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
    // roll it back now, visibly, rather than show a yes that isn't collecting and will be reset.
    private fun optInLost() {
        synchronized(lock) {
            StopdashDebugLog.warning("telemetry: pending opt-in could not be saved; switched off")
            rollBackOptIn()
        }
    }

    /**
     * Undoes a yes the SDKs couldn't take up, at a start or after a tap, and puts the question again
     * (SPEC *Privacy*): the stored choice is deleted rather than kept as a no nobody gave, so the
     * next start asks too (Codex, PR #447). Where it can't be deleted, off is stored: still off,
     * though then only this run asks.
     */
    private fun rollBackOptIn() {
        withdraw(keepAsNo = false)
        _unanswered.value = true
    }

    fun set(enabled: Boolean) {
        synchronized(lock) {
            if (!enabled) {
                // A no is an answer even where it can't be stored: not asked again this run, though
                // one that wasn't stored reads as never answered next start.
                _unanswered.value = false
                withdraw()
                return
            }
            // A no from before no longer stands, and goes first: the mark outranks a stored yes, so
            // one left set would read this yes back as a no. One that can't be cleared is an opt-in
            // that won't last, so, like a failed save, it isn't applied.
            val cleared = try {
                optOut.save(false)
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: clearing the opt-out mark threw: %s", e::class.simpleName)
                false
            }
            if (!cleared) {
                StopdashDebugLog.warning("telemetry: opt-out mark not cleared; left off")
                _state.value = false
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
                // the next start can't see, and a question it answered stays up, since it wasn't kept.
                StopdashDebugLog.warning("telemetry: opt-in write failed; left off")
                _state.value = false
                return
            }
            // Its own copy, so a stored choice lost later reads back as this yes. Best effort: the
            // stored choice is the record, and a load sets a missing mark again.
            val marked = try {
                optIn.save(true)
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: marking the opt-in threw: %s", e::class.simpleName)
                false
            }
            if (!marked) StopdashDebugLog.warning("telemetry: opt-in mark not set")
            // Published before applying, so a lost opt-in reported during apply has the last word.
            _state.value = true
            _unanswered.value = false
            try {
                gate?.apply(true)
            } catch (e: Exception) {
                StopdashDebugLog.warning("telemetry: applying the opt-in failed: %s", e::class.simpleName)
                rollBackOptIn()
            }
        }
    }

    /**
     * Turns collection off, failing closed at every step: the in-memory state first — so the log
     * sink, which reads it at delivery, stops before the SDKs' reports are cleared and nothing
     * queued lands after the cleanup — then the SDKs, then the stored choice. A step that throws is
     * logged and the rest still run; an SDK left on is caught at the next start as a mismatch.
     *
     * With [keepAsNo] false (a yes rolled back, not a no given), the stored choice is deleted first,
     * and off is stored only where it can't be. With it true, the no is marked apart from the stored
     * choice ([ConsentMark]) before the SDKs are touched, or kept another way where it can't be, so
     * whatever fails or is cut short after it, the next start reads this no (or, at worst, never
     * answered), never the yes still stored or SDKs left on.
     */
    private fun withdraw(keepAsNo: Boolean = true) {
        _state.value = false
        // A no is kept before anything reaches the SDKs: none of the steps that switch them off (the
        // pending opt-in cleared, then each SDK's own flag) reads as a no until all are done, so a
        // kill partway would keep the old yes, and with it collection (Codex, PR #454). Kept by the
        // first of these that holds, each one step: its mark, which outranks the rest; the yes mark
        // moved onto it; the stored no; or, with no yes mark left, the stored yes deleted, which reads
        // as never answered (off, and asked).
        // The cost is that write before the switch-off: a kill in it leaves the SDKs' own flags on,
        // so the next start collects from its first moment until its load reads this no and switches
        // them off. Bounded, and taken over the other order, whose kill keeps collecting for good.
        var saved = false
        if (keepAsNo && !markNo() && !moveYesToNo()) {
            saved = saveNo()
            if (!saved && !(yesUnmarked() && forgetChoice())) {
                StopdashDebugLog.warning("telemetry: opt-out not kept before the switch-off")
            }
        }
        // The SDKs next: straight after the no is first kept, so nothing else comes before them.
        try {
            gate?.apply(false)
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: switching the SDKs off failed: %s", e::class.simpleName)
        }
        // The yes mark goes either way: a no is no longer a yes, and a rolled-back yes is asked again.
        val unmarked = unmarkYes()
        // Deleted only once its yes mark is gone, or it would read back from that.
        if (!keepAsNo && unmarked && forgetChoice()) return
        if (saved || saveNo()) return
        // The stored yes must not outlive this: with a pending marker that also failed to clear,
        // the pair would read as an opt-in waiting to start. Deleting the choice leaves the opt-out
        // mark to read back as this no, or, where that failed too and the yes mark is gone, nothing.
        // (A rollback already tried that first.)
        val forgotten = keepAsNo && forgetChoice()
        StopdashDebugLog.warning(
            if (forgotten) "telemetry: opt-out write failed; stored choice deleted instead"
            else "telemetry: opt-out write and delete failed; the SDKs are off",
        )
    }

    private fun markNo(): Boolean {
        val marked = try {
            optOut.save(true)
        } catch (e: Exception) {
            StopdashDebugLog.warning("telemetry: marking the opt-out threw: %s", e::class.simpleName)
            false
        }
        if (!marked) StopdashDebugLog.warning("telemetry: opt-out mark not set")
        return marked
    }

    private fun moveYesToNo(): Boolean = try {
        optOut.takeOver(optIn)
    } catch (e: Exception) {
        StopdashDebugLog.warning("telemetry: moving the opt-in mark threw: %s", e::class.simpleName)
        false
    }

    // Whether no yes mark is set: deleting the stored yes is then one step on its own. Where one is,
    // clearing it first would read back as the yes until the delete lands, so that waits until after
    // the SDKs are off.
    private fun yesUnmarked(): Boolean = try {
        optIn.read() == false
    } catch (e: Exception) {
        StopdashDebugLog.warning("telemetry: reading the opt-in mark threw: %s", e::class.simpleName)
        false
    }

    private fun saveNo(): Boolean = try {
        store?.save(false) != false
    } catch (e: Exception) {
        StopdashDebugLog.warning("telemetry: opt-out write threw: %s", e::class.simpleName)
        false
    }

    private fun forgetChoice(): Boolean = try {
        store?.forget() == true
    } catch (e: Exception) {
        StopdashDebugLog.warning("telemetry: forgetting the opt-in threw: %s", e::class.simpleName)
        false
    }
}
