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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public station names and example ids only. */
class FileActiveTripStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private val trip = ActiveTrip(
        route = TripRoute(
            listOf(
                TripLeg(
                    "tube", "jubilee", "Jubilee", "940GZZLUCWR", "Canada Water", "940GZZLUWLO", "Waterloo",
                    t0, t0.plusSeconds(600), path = listOf("940GZZLUBMY", "940GZZLUWLO"),
                    pathNames = listOf("Bermondsey", "Waterloo"),
                    changeAfter = Duration.ofMinutes(3), headings = listOf("Stanmore"),
                ),
                TripLeg(TripLeg.WALKING, "", "", "940GZZLUWLO", "Waterloo", "910GWLOO", "Waterloo", t0.plusSeconds(600), t0.plusSeconds(900)),
            ),
        ),
        destinationName = "Waterloo",
        startedAt = t0,
        legIndex = 0,
        vehicleId = "162",
        boardsAt = Instant.parse("2026-09-26T08:02:00Z"),
        boarded = true,
        dueOffAt = Instant.parse("2026-09-26T08:14:00Z"),
        warnedLeg = 0,
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
    fun `a temp file cut short when the app died is dropped, and the kept trip stands`() {
        val file = File(tmp.root, "active-trip.json")
        FileActiveTripStore(file).save(trip)
        File(file.path + ".tmp").writeText("{\"legs\": [")
        assertEquals(trip, FileActiveTripStore(file).load())
        assertFalse(File(file.path + ".tmp").exists())
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
}
