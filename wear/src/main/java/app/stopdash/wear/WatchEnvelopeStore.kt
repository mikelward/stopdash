package app.stopdash.wear

import android.content.Context
import android.util.Log
import app.stopdash.data.DeviceSteadyClock
import app.stopdash.data.WatchDecode
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchEnvelopes
import app.stopdash.domain.SteadyClock
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the watch last received from the phone. */
sealed interface WatchReceived {
    /** Not read from disk yet. */
    data object Loading : WatchReceived

    /** Nothing has arrived since install. */
    data object NeverSynced : WatchReceived

    /**
     * [envelope] as it arrived, at [receivedAt], and the watch's clock frame then ([arrivedIn]), so a
     * later read can take out any setting of the clock since ([current]).
     */
    data class Received(val envelope: WatchEnvelope, val receivedAt: Instant, val arrivedIn: SteadyClock.Frame? = null) : WatchReceived {
        /**
         * [envelope] as the watch's wall clock reads it in frame [now]: every stamp moved by however
         * far the clock has been set since it arrived ([SteadyClock.shiftBetween]), so a stop fetched
         * before the clock was set back reads at its real age, not as new once the clock catches up
         * with its old stamp (Codex, PR #371). One that arrived in an earlier boot can't be moved,
         * but it arrived before this boot started, which bounds it ([WatchEnvelope.fromEarlierBoot]).
         * Unmoved where either frame is unknown.
         */
        fun current(now: SteadyClock.Frame?): WatchEnvelope =
            if (arrivedIn != null && now != null && arrivedIn.boot != now.boot) {
                envelope.fromEarlierBoot(now.originMillis)
            } else {
                envelope.shifted(SteadyClock.shiftBetween(arrivedIn, now))
            }
    }
}

/**
 * The latest envelope the phone sent, kept on the watch so every surface renders from it without
 * waiting on the phone (the network is never on a render path). One file, written atomically: a
 * header line, when it arrived and the watch's clock frame then (`<received ms> <boot> <origin ms>`,
 * or `<received ms> -` where the frame can't be told), then the envelope as it came. The two are one
 * write, so a failed one can't leave an envelope beside another's frame (Codex, PR #371). An
 * envelope this build can't read is logged and dropped, keeping the last good one, which the
 * surfaces age honestly.
 */
class WatchEnvelopeStore(
    private val file: File,
    private val now: () -> Instant = Instant::now,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    // The watch's clock frame as of now ([DeviceSteadyClock.frameNow]): kept with each envelope that
    // arrives, and read again to tell how far the clock has been set since. Null in a test that
    // doesn't set one, so nothing is moved.
    private val frame: () -> SteadyClock.Frame? = { null },
    // Where an older build kept the envelope, bare, with no frame: read once, and rewritten in
    // [file] ([adoptOlder]).
    private val older: File? = null,
) {
    /** The watch's clock frame as of now, for [WatchReceived.Received.current]. */
    fun frameNow(): SteadyClock.Frame? = frame()

    /**
     * The envelope last received as the watch reads it now, or null: moved for any setting of the
     * clock since it arrived ([WatchReceived.Received.current]), and distrusting anything stamped
     * ahead of now ([WatchEnvelope.distrustingFuture]). What that finds is kept, in the state and
     * the file, as arriving in this frame, so each judgment of what the clock has done is made once,
     * not again by each later read against a clock that has moved on (Codex, PR #371): a stop found
     * stale across a reboot stays stale however the clock is set after, and one stamped ahead, with
     * no frame to move it by, stays stale once the clock catches up. A read with nothing to move or
     * distrust writes nothing. Call off the main thread.
     */
    fun current(): WatchEnvelope? = synchronized(lock) {
        (_state.value as? WatchReceived.Received)?.let(::kept)
    }

    // Under [lock]: [received] judged as of now, kept if that changed anything. A failed write is
    // logged; it's kept in the state for this process all the same.
    private fun kept(received: WatchReceived.Received): WatchEnvelope {
        val frame = frameNow()
        val judged = received.current(frame).distrustingFuture(now())
        // Re-anchored to this frame only once it's been moved into it (another boot, or a setting of
        // the clock), or when the frame it arrived in couldn't be told: within a minute's drift the
        // frame it arrived in stands.
        val arrivedIn = received.arrivedIn
        val moved = frame != null && (arrivedIn == null || arrivedIn.boot != frame.boot || !SteadyClock.shiftBetween(arrivedIn, frame).isZero)
        if (judged == received.envelope && !moved) return judged
        val kept = received.copy(envelope = judged, arrivedIn = if (moved) frame else arrivedIn)
        try {
            write(WatchEnvelopes.encode(judged), kept.receivedAt, kept.arrivedIn)
        } catch (e: IOException) {
            log("envelope rewrite failed: ${e::class.simpleName}")
        }
        _state.value = kept
        return judged
    }

    private val _state = MutableStateFlow<WatchReceived>(WatchReceived.Loading)
    val state: StateFlow<WatchReceived> = _state.asStateFlow()

    private val lock = Any()

    /** Envelopes stored this process, so a startup lookup can tell the listener got a newer one
     *  while it was reading ([ingestCount]). */
    private var ingests = 0L

    /** How many envelopes have been stored this process; pass it back as `ifNoneSince`. */
    fun ingestCount(): Long = synchronized(lock) { ingests }

    /** Reads the stored envelope once. Call off the main thread. */
    fun load() {
        synchronized(lock) {
            if (_state.value != WatchReceived.Loading) return
            _state.value = read()
        }
    }

    /**
     * Stores [bytes] if they decode, and publishes them. Returns false (logged, nothing stored)
     * for an envelope this build refuses or can't read, and (quietly) when [ifNoneSince] is given
     * and another envelope was stored since that [ingestCount]: the listener's is newer. Call off the main thread.
     */
    fun ingest(bytes: ByteArray, ifNoneSince: Long? = null): Boolean {
        val envelope = decoded(bytes, "envelope") ?: return false
        synchronized(lock) {
            if (ifNoneSince != null && ingests != ifNoneSince) return false
            ingests++
            val arrivedIn = frame()
            val receivedAt = now()
            try {
                write(bytes, receivedAt, arrivedIn)
            } catch (e: IOException) {
                // Still shown this session; the next envelope tries the write again.
                log("envelope write failed: ${e::class.simpleName}")
            }
            _state.value = WatchReceived.Received(envelope, receivedAt, arrivedIn)
        }
        return true
    }

    // The envelope [bytes] as it came, under its header, in one atomic replacement of [file]. Once
    // written, an older build's copy is superseded, and removed.
    private fun write(bytes: ByteArray, receivedAt: Instant, arrivedIn: SteadyClock.Frame?) {
        val header = "${receivedAt.toEpochMilli()} ${arrivedIn?.let { "${it.boot} ${it.originMillis}" } ?: "-"}\n"
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(header.encodeToByteArray())
            out.write(bytes)
        }
        if (!tmp.renameTo(file)) throw IOException("rename failed")
        // Read after [file] anyway, so one left behind is only disk.
        if (older?.exists() == true && !older.delete()) log("older envelope delete failed")
    }

    // [bytes] decoded, or null (logged, as [what]) for an envelope this build refuses or can't read.
    private fun decoded(bytes: ByteArray, what: String): WatchEnvelope? =
        when (val decoded = WatchEnvelopes.decode(bytes)) {
            is WatchDecode.Ok -> decoded.envelope
            is WatchDecode.UnsupportedVersion -> {
                log("$what refused: version ${decoded.version}")
                null
            }
            is WatchDecode.Unreadable -> {
                log("$what unreadable: ${decoded.reason}")
                null
            }
        }

    private fun read(): WatchReceived {
        if (!file.exists()) return adoptOlder()
        return try {
            val bytes = file.readBytes()
            val end = bytes.indexOf('\n'.code.toByte())
            val header = if (end < 0) null else Header.parse(bytes.decodeToString(0, end))
            if (header == null) {
                log("stored envelope header unreadable")
                return WatchReceived.NeverSynced
            }
            decoded(bytes.copyOfRange(end + 1, bytes.size), "stored envelope")
                ?.let { WatchReceived.Received(it, header.receivedAt, header.frame) }
                ?: WatchReceived.NeverSynced
        } catch (e: IOException) {
            log("stored envelope read failed: ${e::class.simpleName}")
            WatchReceived.NeverSynced
        }
    }

    /**
     * An older build's envelope ([older]), rewritten in [file] as arriving now, distrusting anything
     * stamped ahead of now ([WatchEnvelope.distrustingFuture]). Its stamps are the wall clock's with
     * no frame to move them by, so a stop fetched before the clock was set back is caught only while
     * it's ahead; written back, it stays stale once the clock catches up, rather than every later
     * read taking it afresh (Codex, PR #371). A failed rewrite is logged; it's still distrusted in
     * this process, and tried again in the next.
     */
    private fun adoptOlder(): WatchReceived {
        val older = older?.takeIf { it.exists() } ?: return WatchReceived.NeverSynced
        val envelope = try {
            decoded(older.readBytes(), "older envelope")
        } catch (e: IOException) {
            log("older envelope read failed: ${e::class.simpleName}")
            null
        } ?: return WatchReceived.NeverSynced
        val adopted = envelope.distrustingFuture(now())
        val receivedAt = Instant.ofEpochMilli(older.lastModified())
        val arrivedIn = frame()
        try {
            write(WatchEnvelopes.encode(adopted), receivedAt, arrivedIn)
        } catch (e: IOException) {
            log("older envelope rewrite failed: ${e::class.simpleName}")
        }
        return WatchReceived.Received(adopted, receivedAt, arrivedIn)
    }

    // A stored envelope's header line: when it arrived, and its frame, null where it couldn't be told.
    private class Header(val receivedAt: Instant, val frame: SteadyClock.Frame?) {
        companion object {
            // Null when it can't be read.
            fun parse(line: String): Header? {
                val parts = line.split(' ')
                val receivedAt = parts[0].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
                return when {
                    parts.size == 2 && parts[1] == "-" -> Header(receivedAt, null)
                    parts.size == 3 && parts[1].isNotEmpty() ->
                        parts[2].toLongOrNull()?.let { Header(receivedAt, SteadyClock.Frame(parts[1], it)) }
                    else -> null
                }
            }
        }
    }

    companion object {
        private const val TAG = "StopDash.Watch"

        @Volatile
        private var instance: WatchEnvelopeStore? = null

        fun from(context: Context): WatchEnvelopeStore =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext.let { app ->
                    WatchEnvelopeStore(
                        File(app.filesDir, "watch-envelope"),
                        frame = { DeviceSteadyClock.frameNow(app) },
                        older = File(app.filesDir, "watch-envelope.json"),
                    )
                }.also { instance = it }
            }
    }
}
