package app.stopdash.wear

import app.stopdash.data.PersistedLineStatus
import app.stopdash.data.PersistedStop
import app.stopdash.data.WatchEnvelope
import app.stopdash.data.WatchEnvelopes
import app.stopdash.domain.SteadyClock
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's envelope store: synthetic stops only, no user data. */
class WatchHomeTest {
    private val at = Instant.parse("2026-09-24T08:00:00Z")

    @Test
    fun `the store keeps the last good envelope and refuses one it can't read`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope.json")
            val logged = mutableListOf<String>()
            val store = WatchEnvelopeStore(file, now = { at }, log = logged::add)
            store.load()
            assertEquals(WatchReceived.NeverSynced, store.state.value)

            val good = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example")))
            assertTrue(store.ingest(WatchEnvelopes.encode(good)))
            assertEquals(WatchReceived.Received(good, at), store.state.value)

            val newer = """{"version":${WatchEnvelope.CURRENT_VERSION + 1}}""".encodeToByteArray()
            assertFalse(store.ingest(newer))
            assertEquals(listOf("envelope refused: version ${WatchEnvelope.CURRENT_VERSION + 1}"), logged)
            assertEquals(WatchReceived.Received(good, at), store.state.value)

            // A fresh process reads the stored one back.
            val reopened = WatchEnvelopeStore(file, now = { at }, log = logged::add)
            reopened.load()
            assertEquals(good, (reopened.state.value as WatchReceived.Received).envelope)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a startup lookup can't replace an envelope the listener stored while it read`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val store = WatchEnvelopeStore(File(dir, "envelope.json"), now = { at }, log = {})
            val older = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example")))
            val newer = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE2", "Other")))
            val since = store.ingestCount()
            assertTrue(store.ingest(WatchEnvelopes.encode(newer)))
            assertFalse(store.ingest(WatchEnvelopes.encode(older), ifNoneSince = since))
            assertEquals(newer, (store.state.value as WatchReceived.Received).envelope)
            // With nothing in between, the lookup's envelope is stored.
            assertTrue(store.ingest(WatchEnvelopes.encode(older), ifNoneSince = store.ingestCount()))
        } finally {
            dir.deleteRecursively()
        }
    }

    // A store in a fresh directory holding [envelope], arrived in boot 7 at origin 0, reopened as a
    // later process reads it in [frame] at [now].
    private fun reopenedIn(dir: File, envelope: WatchEnvelope, frame: SteadyClock.Frame, now: Instant = at): WatchEnvelopeStore {
        val file = File(dir, "envelope")
        assertTrue(WatchEnvelopeStore(file, now = { at }, log = {}, frame = { SteadyClock.Frame("device/7", 0L) }).ingest(WatchEnvelopes.encode(envelope)))
        return WatchEnvelopeStore(file, now = { now }, log = {}, frame = { frame }).also { it.load() }
    }

    @Test
    fun `an envelope read after the watch's clock was set back is as old as it is`() {
        val fetched = at.toEpochMilli()
        val envelope = WatchEnvelope(
            stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = fetched)),
            lineStatuses = listOf(PersistedLineStatus("victoria", 6, "Minor Delays", fetched)),
        )
        val dirs = List(3) { Files.createTempDirectory("watch").toFile() }
        try {
            // Unmoved in the frame it arrived in.
            assertEquals(envelope, reopenedIn(dirs[0], envelope, SteadyClock.Frame("device/7", 0L)).current())
            // The clock set back an hour since: read back an hour earlier, so a tile built once the
            // clock passes the old stamp ages it an hour, not as new.
            val back = reopenedIn(dirs[1], envelope, SteadyClock.Frame("device/7", -3_600_000L), at.minusSeconds(3600)).current()!!
            assertEquals(fetched - 3_600_000L, back.stops.single().fetchedAtMillis)
            assertEquals(fetched - 3_600_000L, back.lineStatuses.single().checkedAtMillis)
            // After a reboot, what came before the boot started is read as it came.
            assertEquals(envelope, reopenedIn(dirs[2], envelope, SteadyClock.Frame("device/8", fetched + 600_000L), at.plusSeconds(700)).current())
        } finally {
            dirs.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `an envelope from before a reboot the clock was set back across stays stale, however it's set after`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val fetched = at.toEpochMilli()
            val envelope = WatchEnvelope(
                stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = fetched)),
                lineStatuses = listOf(PersistedLineStatus("victoria", 6, "Minor Delays", fetched)),
            )
            // Boot 8 started, as the clock read, half an hour before the stamp: it can't be that new.
            val bootStart = fetched - 1_800_000L
            val first = reopenedIn(dir, envelope, SteadyClock.Frame("device/8", bootStart), at.minusSeconds(1500)).current()!!
            assertEquals(bootStart - 300_000L, first.stops.single().fetchedAtMillis)
            assertFalse(first.stops.single().arrivalsFresh)
            assertTrue(first.lineStatuses.isEmpty())
            // A later process of that boot, the clock set forward so the boot started, as it now reads,
            // at the old stamp: still stale three minutes on, as the first read found it (Codex, PR #371).
            val later = WatchEnvelopeStore(File(dir, "envelope"), now = { at.plusSeconds(180) }, log = {}, frame = { SteadyClock.Frame("device/8", fetched) })
            later.load()
            assertEquals(fetched - 300_000L, later.current()!!.stops.single().fetchedAtMillis)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an envelope stored with no frame is read as it came, and one whose header can't be read is dropped`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope")
            val envelope = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = at.toEpochMilli())))
            WatchEnvelopeStore(file, now = { at }, log = {}).ingest(WatchEnvelopes.encode(envelope))
            val logged = mutableListOf<String>()
            val reopened = WatchEnvelopeStore(file, now = { at }, log = logged::add, frame = { SteadyClock.Frame("device/7", -3_600_000L) })
            reopened.load()
            assertEquals(envelope, reopened.current())
            assertTrue(logged.isEmpty())
            for (garbled in listOf("garbage\n", "garbage", "1 device/7\n")) {
                file.writeBytes(garbled.encodeToByteArray() + WatchEnvelopes.encode(envelope))
                val store = WatchEnvelopeStore(file, now = { at }, log = logged::add)
                store.load()
                assertEquals(WatchReceived.NeverSynced, store.state.value)
            }
            assertEquals(List(3) { "stored envelope header unreadable" }, logged)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an envelope stored where the frame couldn't be told stays stale once seen ahead`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope")
            val fetched = at.toEpochMilli()
            var now = at
            val store = WatchEnvelopeStore(file, now = { now }, log = {})
            assertTrue(store.ingest(WatchEnvelopes.encode(WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = fetched))))))
            // The clock set back an hour: stale as of then, for a surface built now...
            now = at.minusSeconds(3600)
            val back = store.current()!!.stops.single()
            assertEquals(now.toEpochMilli() - 300_000L, back.fetchedAtMillis)
            assertFalse(back.arrivalsFresh)
            // ...and still, for one built once the clock has caught up, in this process or the next
            // (Codex, PR #371).
            now = at.plusSeconds(60)
            assertEquals(back, store.current()!!.stops.single())
            val reopened = WatchEnvelopeStore(file, now = { now }, log = {})
            reopened.load()
            assertEquals(back, reopened.current()!!.stops.single())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an older build's envelope stamped ahead stays stale once the clock catches up`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope")
            // Stored bare by an older build, with no frame: a stop fetched at [at], a line checked then.
            val older = File(dir, "older.json")
            val fetched = at.toEpochMilli()
            older.writeBytes(
                WatchEnvelopes.encode(
                    WatchEnvelope(
                        stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = fetched)),
                        lineStatuses = listOf(PersistedLineStatus("victoria", 6, "Minor Delays", fetched)),
                    ),
                ),
            )
            val frame = SteadyClock.Frame("device/8", 0L)
            // First read by this build after the clock was set back an hour: stale as of then.
            val back = at.minusSeconds(3600)
            val first = WatchEnvelopeStore(file, now = { back }, log = {}, frame = { frame }, older = older)
            first.load()
            val adopted = first.current()!!
            assertEquals(back.toEpochMilli() - 300_000L, adopted.stops.single().fetchedAtMillis)
            assertFalse(adopted.stops.single().arrivalsFresh)
            assertTrue(adopted.lineStatuses.isEmpty())
            // Rewritten in this build's format, so a later read, once the clock has caught up with the
            // old stamp, is still stale rather than taking it afresh (Codex, PR #371).
            assertFalse(older.exists())
            val later = WatchEnvelopeStore(file, now = { at.plusSeconds(60) }, log = {}, frame = { frame }, older = older)
            later.load()
            assertEquals(adopted, later.current())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a line check a little ahead of the watch's clock is kept, one well ahead is dropped`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            // The phone's clock runs half a minute ahead of the watch's: its check is kept, and still
            // there once the watch's clock has passed it, not dropped for good as if from before the
            // clock was set back (Codex, PR #371). One stamped two minutes ahead is.
            val checked = at.toEpochMilli()
            val envelope = WatchEnvelope(
                stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = checked)),
                lineStatuses = listOf(
                    PersistedLineStatus("victoria", 6, "Minor Delays", checked + 30_000L),
                    PersistedLineStatus("central", 6, "Minor Delays", checked + 120_000L),
                ),
            )
            var now = at
            val store = WatchEnvelopeStore(File(dir, "envelope"), now = { now }, log = {}, frame = { SteadyClock.Frame("device/7", 0L) })
            assertTrue(store.ingest(WatchEnvelopes.encode(envelope)))
            assertEquals(listOf("victoria"), store.current()!!.lineStatuses.map { it.lineId })
            now = at.plusSeconds(180)
            assertEquals(listOf("victoria"), store.current()!!.lineStatuses.map { it.lineId })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed write leaves the envelope before it with the frame it arrived in`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope")
            var frame = SteadyClock.Frame("device/7", 0L)
            val fetched = at.toEpochMilli()
            val logged = mutableListOf<String>()
            val store = WatchEnvelopeStore(file, now = { at }, log = logged::add, frame = { frame })
            assertTrue(store.ingest(WatchEnvelopes.encode(WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example", fetchedAtMillis = fetched))))))
            // The clock set back an hour, then an envelope whose write fails (its temporary file can't
            // be written).
            frame = SteadyClock.Frame("device/7", -3_600_000L)
            assertTrue(File(dir, "envelope.tmp").mkdir())
            assertTrue(store.ingest(WatchEnvelopes.encode(WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE2", "Other", fetchedAtMillis = fetched))))))
            assertEquals(listOf("envelope write failed: FileNotFoundException"), logged)
            // A fresh process reads the first one back with the frame it arrived in, so it's moved by
            // the setting since, not left as new (Codex, PR #371).
            val reopened = WatchEnvelopeStore(file, now = { at }, log = {}, frame = { frame })
            reopened.load()
            val stop = reopened.current()!!.stops.single()
            assertEquals("940GEXAMPLE1", stop.stopId)
            assertEquals(fetched - 3_600_000L, stop.fetchedAtMillis)
        } finally {
            dir.deleteRecursively()
        }
    }
}
