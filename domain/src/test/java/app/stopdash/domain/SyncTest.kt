package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncTest {
    private val day = 24 * 60 * 60 * 1000L

    // Synthetic ride ids and values: no rider's stops.
    private fun rides(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    private fun SyncLedger.heldRides(): Map<String, String> = Sync.valuesOf(this, RIDES)

    // For a file that should read cleanly: any warning fails the test.
    private val noWarning: (String) -> Unit = { fail("unexpected warning: $it") }

    @Test
    fun `a stamp takes the clock's time when it's later`() {
        assertEquals(SyncStamp(2_000, 0, "a"), Sync.next(SyncStamp(1_000, 4, "b"), 2_000, "a"))
        assertEquals(SyncStamp(2_000, 0, "a"), Sync.next(null, 2_000, "a"))
    }

    @Test
    fun `a stamp counts on past the latest seen when the clock is behind it`() {
        assertEquals(SyncStamp(5_000, 3, "a"), Sync.next(SyncStamp(5_000, 2, "b"), 4_000, "a"))
        assertEquals(SyncStamp(5_000, 3, "a"), Sync.next(SyncStamp(5_000, 2, "b"), 5_000, "a"))
    }

    @Test
    fun `stamps order by time, then count, then phone`() {
        assertTrue(SyncStamp(1, 9, "z") < SyncStamp(2, 0, "a"))
        assertTrue(SyncStamp(1, 1, "z") < SyncStamp(1, 2, "a"))
        assertTrue(SyncStamp(1, 1, "a") < SyncStamp(1, 1, "b"))
    }

    @Test
    fun `a first sync stamps everything in the store`() {
        val ledger = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y"), 1_000, "a")
        assertEquals(rides("x" to "X", "y" to "Y"), ledger.heldRides())
        assertEquals(setOf(SyncStamp(1_000, 0, "a"), SyncStamp(1_000, 1, "a")), ledger.kinds.getValue(RIDES).values.map { it.stamp }.toSet())
        assertEquals(SyncStamp(1_000, 1, "a"), ledger.latest)
    }

    @Test
    fun `nothing changed leaves the ledger as it was`() {
        val ledger = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        assertSame(ledger, Sync.record(ledger, RIDES, rides("x" to "X"), 2_000, "a"))
    }

    @Test
    fun `a changed entry is stamped again, an unchanged one keeps its stamp`() {
        val first = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y"), 1_000, "a")
        val second = Sync.record(first, RIDES, rides("x" to "X2", "y" to "Y"), 2_000, "a")
        assertEquals(SyncEntry(SyncStamp(2_000, 0, "a"), "X2"), second.kinds.getValue(RIDES).getValue("x"))
        assertEquals(first.kinds.getValue(RIDES).getValue("y"), second.kinds.getValue(RIDES).getValue("y"))
    }

    @Test
    fun `a removal is kept as a record, and a ride saved again after it is stamped anew`() {
        val first = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        val removed = Sync.record(first, RIDES, rides(), 2_000, "a")
        assertEquals(SyncEntry(SyncStamp(2_000, 0, "a"), null), removed.kinds.getValue(RIDES).getValue("x"))
        assertEquals(rides(), removed.heldRides())
        // Still removed, it stays as it was.
        assertSame(removed, Sync.record(removed, RIDES, rides(), 3_000, "a"))
        val again = Sync.record(removed, RIDES, rides("x" to "X"), 4_000, "a")
        assertEquals(SyncEntry(SyncStamp(4_000, 0, "a"), "X"), again.kinds.getValue(RIDES).getValue("x"))
    }

    @Test
    fun `recording one kind leaves the others alone`() {
        val places = Sync.record(SyncLedger.EMPTY, "places", mapOf("home" to "H"), 1_000, "a")
        val both = Sync.record(places, RIDES, rides("x" to "X"), 2_000, "a")
        assertEquals(places.kinds.getValue("places"), both.kinds.getValue("places"))
    }

    @Test
    fun `a value this build re-encodes without a newer build's fields isn't a change here`() {
        val newer = SyncLedger(mapOf(RIDES to mapOf("x" to SyncEntry(SyncStamp(1_000, 0, "b"), "X+extra"))), SyncStamp(1_000, 0, "b"))
        // This build stores "X", having read past "+extra".
        val same = { ledgerValue: String, localValue: String -> ledgerValue.removeSuffix("+extra") == localValue }
        assertSame(newer, Sync.record(newer, RIDES, rides("x" to "X"), 2_000, "a", same = same))
        assertTrue(Sync.changes(rides("x" to "X"), newer.heldRides(), same).none)
    }

    @Test
    fun `the newest change of each entry wins`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xa", "y" to "Ya"), 1_000, "a")
        val b = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xb"), 2_000, "b")
        val aLater = Sync.record(a, RIDES, rides("x" to "Xa", "y" to "Ya2"), 3_000, "a")
        assertEquals(rides("x" to "Xb", "y" to "Ya2"), Sync.merge(aLater, listOf(b)).heldRides())
    }

    @Test
    fun `merging is order-free and repeatable`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xa"), 1_000, "a")
        val b = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xb", "y" to "Y"), 2_000, "b")
        val c = Sync.record(Sync.record(SyncLedger.EMPTY, RIDES, rides("y" to "Y"), 500, "c"), RIDES, rides(), 3_000, "c")
        val once = Sync.merge(a, listOf(b, c))
        assertEquals(once, Sync.merge(a, listOf(c, b)))
        assertEquals(once, Sync.merge(c, listOf(a, b)))
        assertEquals(once, Sync.merge(once, listOf(b, c, a)))
        assertEquals(rides("x" to "Xb"), once.heldRides())
    }

    @Test
    fun `a removal beats an older copy, and a newer save beats the removal`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        val b = Sync.merge(SyncLedger.EMPTY, listOf(a))
        val bRemoved = Sync.record(b, RIDES, rides(), 2_000, "b")
        assertEquals(rides(), Sync.merge(a, listOf(bRemoved)).heldRides())
        val aSavedAgain = Sync.record(Sync.merge(a, listOf(bRemoved)), RIDES, rides("x" to "X"), 3_000, "a")
        assertEquals(rides("x" to "X"), Sync.merge(bRemoved, listOf(aSavedAgain)).heldRides())
    }

    @Test
    fun `a phone joining adds its rides and takes the others', removing none`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y"), 1_000, "a")
        // Removed on the first phone before the second ever synced, which still has it.
        val aRemoved = Sync.record(a, RIDES, rides("y" to "Y"), 2_000, "a")
        // As a sync does: the other phones' files read first, this phone's changes stamped after them.
        val joining = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y", "z" to "Z"), 3_000, "b", Sync.latestOf(listOf(aRemoved)))
        assertEquals(rides("x" to "X", "y" to "Y", "z" to "Z"), Sync.merge(joining, listOf(aRemoved)).heldRides())
    }

    @Test
    fun `a phone joining with its clock a little behind still removes none of its rides`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        // Removed at two minutes by the first phone's clock; the second joins at one minute by its own,
        // both within Drive's five minutes (Codex on #1141).
        val aRemoved = Sync.record(a, RIDES, rides(), 120_000, "a")
        val joining = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 60_000, "b", Sync.latestOf(listOf(aRemoved)))
        assertEquals(rides("x" to "X"), Sync.merge(joining, listOf(aRemoved)).heldRides())
        assertTrue(joining.kinds.getValue(RIDES).getValue("x").stamp > aRemoved.kinds.getValue(RIDES).getValue("x").stamp)
    }

    @Test
    fun `an entry only another phone holds isn't taken for one removed here`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        val b = Sync.record(SyncLedger.EMPTY, RIDES, rides("z" to "Z"), 2_000, "b", Sync.latestOf(listOf(a)))
        // The second phone's store doesn't hold x yet: compared with its ledger, x is no removal.
        assertEquals(rides("x" to "X", "z" to "Z"), Sync.merge(b, listOf(a)).heldRides())
    }

    @Test
    fun `the latest stamp of the files read is the newest of any of them`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a")
        val b = Sync.record(SyncLedger.EMPTY, "places", mapOf("home" to "H"), 5_000, "b")
        assertEquals(SyncStamp(5_000, 0, "b"), Sync.latestOf(listOf(a, b)))
        assertNull(Sync.latestOf(emptyList()))
    }

    @Test
    fun `a phone back after a year drops what was removed while it was away`() {
        val both = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y"), 1_000, "a")
        val b = Sync.merge(SyncLedger.EMPTY, listOf(both))
        // The first phone removes y.
        val aRemoved = Sync.record(both, RIDES, rides("x" to "X"), 2_000, "a")
        // A year on, the second phone changes another ride, then syncs (Codex on #1141).
        val bBack = Sync.record(b, RIDES, rides("x" to "X2", "y" to "Y"), 365 * day, "b")
        assertEquals(rides("x" to "X2"), Sync.merge(bBack, listOf(aRemoved)).heldRides())
    }

    @Test
    fun `a change made after reading another phone's file comes after everything in it`() {
        // The other phone's clock ran ahead.
        val ahead = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xa"), 10 * day, "a")
        val b = Sync.merge(Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xb"), 1_000, "b"), listOf(ahead))
        assertEquals(SyncStamp(10 * day, 0, "a"), b.latest)
        val bChanged = Sync.record(b, RIDES, rides("x" to "Xb2"), 2_000, "b")
        assertEquals(rides("x" to "Xb2"), Sync.merge(ahead, listOf(bChanged)).heldRides())
    }

    @Test
    fun `changes are stamped by Drive's clock, whatever the phone's says`() {
        // Drive answered at 10:00:00 by its own clock; the phone's monotonic clock read 5 s then.
        val drive = DriveClock(driveMillis = 36_000_000, elapsedAtAnswer = 5_000)
        assertEquals(36_002_500L, drive.now(elapsedNow = 7_500))
        // Never before the answer itself.
        assertEquals(36_000_000L, drive.now(elapsedNow = 4_000))
        // Each phone stamps by Drive's time from its own sync: the second, read 2 s after its answer,
        // comes after the first, whatever either phone's own clock says.
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xa"), drive.now(6_000), "a")
        val b = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "Xb"), DriveClock(36_000_000, 900_000).now(902_000), "b", Sync.latestOf(listOf(a)))
        assertEquals(rides("x" to "Xb"), Sync.merge(a, listOf(b)).heldRides())
    }

    @Test
    fun `changes say what to write and what to remove`() {
        val changes = Sync.changes(rides("x" to "X", "y" to "Y", "z" to "Z"), rides("x" to "X", "y" to "Y2", "w" to "W"))
        assertEquals(rides("y" to "Y2", "w" to "W"), changes.put)
        assertEquals(setOf("z"), changes.removed)
        assertTrue(Sync.changes(rides("x" to "X"), rides("x" to "X")).none)
    }

    @Test
    fun `a file reads back as written`() {
        val ledger = Sync.merge(
            Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X"), 1_000, "a"),
            listOf(Sync.record(Sync.record(SyncLedger.EMPTY, RIDES, rides("y" to "Y"), 500, "b"), RIDES, rides(), 2_000, "b")),
        )
        assertEquals(SyncFileRead.Read(ledger), SyncFile.decode(SyncFile.encode(ledger), noWarning))
    }

    @Test
    fun `the same view always writes the same file`() {
        val a = Sync.record(SyncLedger.EMPTY, RIDES, rides("x" to "X", "y" to "Y"), 1_000, "a")
        val b = Sync.record(SyncLedger.EMPTY, "places", mapOf("home" to "H"), 2_000, "b")
        assertEquals(SyncFile.encode(Sync.merge(a, listOf(b))), SyncFile.encode(Sync.merge(b, listOf(a))))
    }

    @Test
    fun `a recorded file is read, with kinds and fields this build doesn't know carried`() {
        val read = SyncFile.decode(RECORDED, noWarning) as SyncFileRead.Read
        assertEquals(rides("x" to "X"), read.ledger.heldRides())
        assertEquals(SyncEntry(SyncStamp(1_700, 0, "b"), null), read.ledger.kinds.getValue(RIDES).getValue("y"))
        // A kind from a later build passes through this one's merge and file untouched.
        val merged = Sync.merge(Sync.record(SyncLedger.EMPTY, RIDES, rides("z" to "Z"), 2_000, "a"), listOf(read.ledger))
        val written = SyncFile.decode(SyncFile.encode(merged), noWarning) as SyncFileRead.Read
        assertEquals(mapOf("home" to "H"), Sync.valuesOf(written.ledger, "places"))
    }

    @Test
    fun `a newer format is skipped, and a broken file is said, never misread`() {
        assertEquals(SyncFileRead.Newer(2), SyncFile.decode("""{"version":2,"kinds":{}}""", noWarning))
        // Even where the newer format shapes a field this build knows another way (Codex on #1141).
        assertEquals(SyncFileRead.Newer(2), SyncFile.decode("""{"version":2,"kinds":[]}""", noWarning))
        val warnings = mutableListOf<String>()
        assertEquals(SyncFileRead.Unreadable, SyncFile.decode("""{"version":1,"kinds":""", warnings::add))
        assertEquals(SyncFileRead.Unreadable, SyncFile.decode("not json", warnings::add))
        assertEquals(SyncFileRead.Unreadable, SyncFile.decode("[]", warnings::add))
        assertEquals(SyncFileRead.Unreadable, SyncFile.decode("""{"version":1,"kinds":[]}""", warnings::add))
        // By the exception's type and where it was thrown, never its message or the file's text
        // (AGENTS.md *Error handling*; Codex on #1141).
        assertEquals(4, warnings.size)
        assertTrue(warnings.all { it.startsWith("sync file unreadable (") && " at " in it })
        assertTrue(warnings.none { "kinds" in it || "not json" in it || "version" in it })
    }

    @Test
    fun `a file without a version or a stamp is read`() {
        val read = SyncFile.decode("""{"kinds":{"rides":{}}}""", noWarning) as SyncFileRead.Read
        assertNull(read.ledger.latest)
        assertEquals(rides(), read.ledger.heldRides())
    }

    private companion object {
        const val RIDES = "rides"

        // As a phone wrote it, with a field and a kind a later build added.
        val RECORDED = """
            {
              "version": 1,
              "latest": {"wall": 1700, "counter": 0, "phone": "b"},
              "addedLater": true,
              "kinds": {
                "places": {"home": {"stamp": {"wall": 1500, "counter": 0, "phone": "b"}, "value": "H"}},
                "rides": {
                  "x": {"stamp": {"wall": 1000, "counter": 0, "phone": "a"}, "value": "X", "addedLater": 1},
                  "y": {"stamp": {"wall": 1700, "counter": 0, "phone": "b"}, "value": null}
                }
              }
            }
        """.trimIndent()
    }
}
