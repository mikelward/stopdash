package app.stopdash.data

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public station names and example ids only. */
class FileActiveTripStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private val jubilee = TripLeg(
        "tube", "jubilee", "Jubilee", "940GZZLUCWR", "Canada Water", "940GZZLUWLO", "Waterloo",
        t0, t0.plusSeconds(600), path = listOf("940GZZLUBMY", "940GZZLUWLO"),
        pathNames = listOf("Bermondsey", "Waterloo"),
        changeAfter = Duration.ofMinutes(3), headings = listOf("Stanmore"),
        fromAt = app.stopdash.domain.Coordinates(51.5, -0.12),
        toAt = app.stopdash.domain.Coordinates(51.53, -0.12),
    )
    private val trip = ActiveTrip(
        route = TripRoute(
            listOf(
                jubilee,
                TripLeg(TripLeg.WALKING, "", "", "940GZZLUWLO", "Waterloo", "910GWLOO", "Waterloo", t0.plusSeconds(600), t0.plusSeconds(900)),
            ),
        ),
        destinationName = "Waterloo",
        startedAt = t0,
        legIndex = 0,
        vehicleId = "162",
        boardsAt = Instant.parse("2026-09-26T08:02:00Z"),
        boarded = true,
        boardedAt = Instant.parse("2026-09-26T08:02:00Z"),
        dueOffAt = Instant.parse("2026-09-26T08:14:00Z"),
        warnedLeg = 0,
        alertLeft = true,
        vehicleOffId = "940GZZLUWLO",
        waitFrom = Instant.parse("2026-09-26T08:01:00Z"),
        boardWarned = "0/162",
        disruptionsHeard = setOf("line/0/red/6/Severe Delays", "stop/1/C/closed"),
        // A Jubilee ride followed on another line's train, as that line runs it: by its own stops between.
        vehicleLeg = jubilee.copy(
            lineId = "metropolitan", lineName = "Metropolitan", path = listOf("940GZZLUWLO"), pathNames = listOf("Waterloo"),
            headings = emptyList(), fromAt = null, toAt = null,
        ),
        heldFrom = Instant.parse("2026-09-26T08:01:30Z"),
    )

    @Test
    fun `a started trip is kept across a reload, and forgotten when it ends`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        assertEquals(trip, FileActiveTripStore(file).load())
        FileActiveTripStore(file).save(null)
        assertNull(FileActiveTripStore(file).load())
        assertFalse(file.exists())
    }

    @Test
    fun `a bus leg moved to its route's stand keeps the stand the Planner named across a reload`() {
        val file = File(tmp.root, "active-trip.json")
        val bus = TripLeg(
            "bus", "1", "1", "STAND-A", "Bus Station", "STAND-C", "Town Centre", t0, t0.plusSeconds(600),
            plannedFromId = "STAND-B", plannedToId = "STAND-D",
        )
        val moved = trip.copy(route = TripRoute(listOf(bus)))
        FileActiveTripStore(file).save(moved)
        assertEquals(moved, FileActiveTripStore(file).load())
    }

    @Test
    fun `an ended trip whose file can't be deleted doesn't come back`() {
        val file = File(tmp.root, "active-trip.json")
        val stuck = FileActiveTripStore(file, delete = { false })
        stuck.save(trip)
        stuck.save(null)
        assertNull(FileActiveTripStore(file, delete = { false }).load())
    }

    @Test
    fun `a trip that can't be renamed into place is still kept, not the one before`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        val movedOn = trip.copy(legIndex = 1, vehicleId = "", boarded = false, dueOffAt = null)
        FileActiveTripStore(file, rename = { _, _ -> false }).save(movedOn)
        assertEquals(movedOn, FileActiveTripStore(file).load())
    }

    @Test
    fun `a trip that can't be written says so`() {
        // A directory where the file should be: nothing can be written there.
        val file = File(tmp.root, "active-trip.json").apply { mkdir() }
        assertFalse(FileActiveTripStore(file).save(trip))
        assertTrue(FileActiveTripStore(File(tmp.root, "other.json")).save(trip))
    }

    @Test
    fun `a trip written but not yet moved into place when the app died is kept`() {
        val file = File(tmp.root, "active-trip.json")
        val older = trip.copy(legIndex = 1, vehicleId = "", boarded = false, dueOffAt = null)
        FileActiveTripStore(file).save(older)
        // The newer trip reached the temp file, then the process died before the rename.
        val written = File(tmp.root, "staged.json").also { FileActiveTripStore(it).save(trip) }.readText()
        File(file.path + ".tmp").writeText(written)
        assertEquals(trip, FileActiveTripStore(file).load())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `a whole temp file that can't be moved into place is still read, and kept`() {
        val file = File(tmp.root, "active-trip.json")
        val older = trip.copy(legIndex = 1, vehicleId = "", boarded = false, dueOffAt = null)
        FileActiveTripStore(file).save(older)
        val written = File(tmp.root, "staged.json").also { FileActiveTripStore(it).save(trip) }.readText()
        File(file.path + ".tmp").writeText(written)
        // Neither the rename nor a write in place works: the file is a directory's worth of stuck.
        file.delete()
        file.mkdir()
        assertEquals(trip, FileActiveTripStore(file, rename = { _, _ -> false }).load())
        assertTrue(File(file.path + ".tmp").exists())
    }

    @Test
    fun `an ended trip read from a temp file doesn't come back from it`() {
        val file = File(tmp.root, "active-trip.json")
        val written = File(tmp.root, "staged.json").also { FileActiveTripStore(it).save(trip) }.readText()
        File(file.path + ".tmp").writeText(written)
        val stuck = FileActiveTripStore(file, rename = { _, _ -> false })
        file.mkdir()
        assertEquals(trip, stuck.load())
        file.delete()
        assertTrue(stuck.save(null))
        assertNull(FileActiveTripStore(file).load())
    }

    @Test
    fun `an ended trip doesn't come back when the app dies partway through forgetting it`() {
        val file = File(tmp.root, "active-trip.json")
        val older = trip.copy(legIndex = 0, vehicleId = "")
        FileActiveTripStore(file).save(older)
        // The newer trip kept in its temp file, which couldn't be moved into place.
        val written = File(tmp.root, "staged.json").also { FileActiveTripStore(it).save(trip) }.readText()
        File(file.path + ".tmp").writeText(written)
        class Died : RuntimeException()
        val dying = FileActiveTripStore(
            file,
            rename = { _, _ -> false },
            delete = { f -> if (f == file) throw Died() else f.delete() },
        )
        assertEquals(trip, dying.load())
        // Ended, and the app dies on reaching the kept file: neither trip comes back.
        try {
            dying.save(null)
        } catch (_: Died) {
            // The process is gone here; what's on disk is all the next start sees.
        }
        assertNull(FileActiveTripStore(file).load())
    }

    @Test
    fun `a trip started after one ended with files that can't be deleted is kept`() {
        val file = File(tmp.root, "active-trip.json")
        val stuck = FileActiveTripStore(file, delete = { false })
        stuck.save(trip)
        assertTrue(stuck.save(null))
        assertNull(FileActiveTripStore(file, delete = { false }).load())
        // The ended mark couldn't be deleted either: it mustn't forget the next trip too.
        val next = trip.copy(legIndex = 0, vehicleId = "")
        assertTrue(stuck.save(next))
        assertEquals(next, FileActiveTripStore(file, delete = { false }).load())
    }

    @Test
    fun `the ended mark is only ever put in place whole`() {
        val file = File(tmp.root, "active-trip.json")
        var placedWhole = false
        val store = FileActiveTripStore(file, rename = { from, to ->
            if (to.name.endsWith(".ended")) placedWhole = from.readText() == "ended"
            from.renameTo(to)
        })
        store.save(trip)
        assertTrue(store.save(null))
        // Written aside and renamed in, so dying mid-write leaves no empty mark to read as cleared.
        assertTrue(placedWhole)
        assertNull(FileActiveTripStore(file).load())
    }

    @Test
    fun `an ended mark written but not yet moved into place when the app died still ends the trip`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        File(file.path + ".ended.tmp").writeText("ended")
        assertNull(FileActiveTripStore(file).load())
        // Once finished, the staged mark is gone, so it can't end the next trip.
        assertFalse(File(file.path + ".ended.tmp").exists())
        FileActiveTripStore(file).save(trip)
        assertEquals(trip, FileActiveTripStore(file).load())
        // One cut short mid-write says nothing: the kept trip stands.
        val other = File(tmp.root, "other.json")
        FileActiveTripStore(other).save(trip)
        File(other.path + ".ended.tmp").writeText("en")
        assertEquals(trip, FileActiveTripStore(other).load())
        assertFalse(File(other.path + ".ended.tmp").exists())
    }

    @Test
    fun `an ended mark that can't be renamed in stands where it was written, never rewritten in place`() {
        val file = File(tmp.root, "active-trip.json")
        // Nothing named .ended can be deleted, so a mark written there would be left behind.
        val stuck = FileActiveTripStore(
            file,
            rename = { _, _ -> false },
            delete = { f -> if (f.name.endsWith(".ended")) false else f.delete() },
        )
        stuck.save(trip)
        assertTrue(stuck.save(null))
        // Rewriting the mark in place could leave it empty if the app died mid-write: never written.
        assertFalse(File(file.path + ".ended").exists())
        assertNull(FileActiveTripStore(file, rename = { _, _ -> false }).load())
    }

    @Test
    fun `ending again leaves a mark already staged as it is`() {
        val file = File(tmp.root, "active-trip.json").apply { mkdir() }
        // The kept file can't be cleared and the mark can't be renamed in: it stays staged.
        val stuck = FileActiveTripStore(
            file,
            rename = { _, _ -> false },
            delete = { f -> if (f == file) false else f.delete() },
        )
        val staged = File(file.path + ".ended.tmp").apply { writeText("ended"); setLastModified(1_000_000L) }
        assertTrue(stuck.save(null))
        // Not rewritten: dying mid-rewrite could leave it cut short, and the trip back.
        assertEquals(1_000_000L, staged.lastModified())
        assertEquals("ended", staged.readText())
    }

    @Test
    fun `a save cut short before any of it was written leaves the kept trip standing`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        // The temp file created, then the app died before a byte of the newer trip reached it.
        File(file.path + ".tmp").writeText("")
        assertEquals(trip, FileActiveTripStore(file).load())
    }

    @Test
    fun `a temp file cut short when the app died is dropped, and the kept trip stands`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        File(file.path + ".tmp").writeText("{\"legs\": [")
        assertEquals(trip, FileActiveTripStore(file).load())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `a staged ended mark that can't be read is kept, not taken for a partial one`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        // A directory where the staged mark was: reading it throws, as a storage error would.
        val staged = File(file.path + ".ended.tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { FileActiveTripStore(file).load() }
        assertTrue(staged.exists())
        // Read again once it can be: a whole mark still ends the trip.
        staged.delete()
        staged.writeText("ended")
        assertNull(FileActiveTripStore(file).load())
    }

    @Test
    fun `a temp file that can't be read is kept for another try, not dropped as partial`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip.copy(legIndex = 0, vehicleId = ""))
        // A directory where the temp file was: reading it throws, as a storage error would.
        val staged = File(file.path + ".tmp").apply { mkdir() }
        assertThrows(java.io.IOException::class.java) { FileActiveTripStore(file).load() }
        assertTrue(staged.exists())
    }

    @Test
    fun `a file that can't be read is kept for another try`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        val saved = file.readText()
        // A directory where the file was: reading it throws, as a storage error would.
        file.delete()
        file.mkdir()
        assertThrows(java.io.IOException::class.java) { FileActiveTripStore(file).load() }
        assertTrue(file.exists())
        file.delete()
        file.writeText(saved)
        assertEquals(trip, FileActiveTripStore(file).load())
    }

    @Test
    fun `an unparseable file is no trip, and is deleted`() {
        val file = File(tmp.root, "active-trip.json").apply { writeText("not json") }
        val warnings = mutableListOf<String>()
        assertNull(FileActiveTripStore(file, warn = { warnings += it }).load())
        assertFalse(file.exists())
        assertTrue(warnings.single().startsWith("active trip unparseable"))
    }

    @Test
    fun `no file is no trip`() {
        assertNull(FileActiveTripStore(File(tmp.root, "none.json")).load())
    }

    @Test
    fun `a trip saved before the rename resumes naming its West Midlands Trains leg London Northwestern`() {
        // Its directions ("Board London Northwestern Railway") then agree with the leg's LNR pill.
        val file = File(tmp.root, "active-trip.json")
        val rail = TripLeg(
            "national-rail", "west-midlands-trains", "West Midlands Trains", "910GA", "Example A",
            "910GB", "Example B", t0, t0.plusSeconds(1_800),
        )
        FileActiveTripStore(file).save(trip.copy(route = TripRoute(listOf(rail))))
        assertEquals("London Northwestern Railway", FileActiveTripStore(file).load()?.route?.legs?.single()?.lineName)
    }

    @Test
    fun `a trip saved before trains were kept with their line follows its ride's own line`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip.copy(vehicleLeg = null))
        assertEquals(false, file.readText().contains("vehicleLeg"))
        assertEquals(trip.copy(vehicleLeg = null), FileActiveTripStore(file).load())
        assertEquals("jubilee", app.stopdash.domain.OnTheWay.followedLine(FileActiveTripStore(file).load()!!))
        assertEquals("Jubilee", app.stopdash.domain.OnTheWay.followedLineName(FileActiveTripStore(file).load()!!))
    }

    @Test
    fun `a trip saved before a held train's time was kept has none`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip.copy(heldFrom = null))
        assertEquals(false, file.readText().contains("heldFrom"))
        assertEquals(trip.copy(heldFrom = null), FileActiveTripStore(file).load())
    }
}
