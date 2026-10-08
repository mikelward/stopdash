package app.stopdash.data

import app.stopdash.domain.LineRef
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LineCatalogTest {
    private val dir: File = Files.createTempDirectory("lines").toFile()
    private val file = File(dir, "lines.json")
    private val bus = LineRef("299", "299", "bus")
    private val tube = LineRef("victoria", "Victoria", "tube")
    private var clock = Instant.parse("2026-10-07T08:00:00Z")
    private var fetches = 0
    private var answer: () -> List<LineRef> = { listOf(bus) }
    private val warnings = mutableListOf<String>()

    private fun catalog() = LineCatalog(
        fetch = { fetches++; answer() },
        store = FileLineCatalogStore(file),
        now = { clock },
        warn = { warnings += it },
    )

    @Test
    fun a_list_under_a_day_old_is_used_without_asking_tfl_even_after_a_restart() = runBlocking {
        assertEquals(listOf(bus), catalog().lines())
        clock += Duration.ofHours(23)
        // A new catalog reads the kept file, as a new process would.
        val restarted = catalog()
        assertEquals(listOf(bus), restarted.lines())
        assertNull(restarted.refreshed())
        assertEquals(1, fetches)
    }

    @Test
    fun a_day_old_list_is_answered_at_once_and_renewed_behind_it() = runBlocking {
        catalog().lines()
        clock += Duration.ofDays(1)
        answer = { listOf(bus, tube) }
        // A new process: the kept list straight away, with no request to wait on.
        val restarted = catalog()
        assertEquals(listOf(bus), restarted.lines())
        assertEquals(1, fetches)
        // Then renewed, and kept.
        assertEquals(listOf(bus, tube), restarted.refreshed())
        assertEquals(listOf(bus, tube), catalog().lines())
        assertEquals(2, fetches)
    }

    @Test
    fun a_failed_renewal_keeps_the_old_list_and_says_so() = runBlocking {
        val lines = catalog()
        lines.lines()
        clock += Duration.ofDays(2)
        answer = { throw IOException("offline") }
        assertNull(lines.refreshed())
        assertEquals(listOf(bus), lines.lines())
        assertEquals(1, warnings.size)
    }

    @Test
    fun a_failed_fetch_with_nothing_kept_throws() = runBlocking {
        answer = { throw IOException("offline") }
        try {
            catalog().lines()
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun a_list_dated_after_now_is_renewed() = runBlocking {
        catalog().lines()
        clock -= Duration.ofHours(1)
        assertEquals(listOf(bus), catalog().refreshed())
        assertEquals(2, fetches)
    }

    @Test
    fun an_unreadable_file_is_dropped() = runBlocking {
        file.writeText("not json")
        assertEquals(listOf(bus), catalog().lines())
        assertTrue(file.readText().contains("299"))
    }

    @Test
    fun recent_lines_survive_a_restart_newest_first() {
        val store = FileRecentLinesStore(File(dir, "recent.json"))
        store.add(bus)
        store.add(tube)
        store.add(bus)
        assertEquals(listOf(bus, tube), FileRecentLinesStore(File(dir, "recent.json")).load())
    }

    @Test
    fun a_saved_line_is_renamed_on_the_way_back_in() = runBlocking {
        val tfl = LineRef("elizabeth", "Elizabeth line", "elizabeth-line")
        FileRecentLinesStore(File(dir, "recent.json")).add(tfl)
        assertEquals(listOf(tfl.copy(name = "Elizabeth")), FileRecentLinesStore(File(dir, "recent.json")).load())
        val catalogFile = File(dir, "catalog.json")
        FileLineCatalogStore(catalogFile).save(LineCatalogFile(0L, listOf(tfl)))
        assertEquals(listOf(tfl.copy(name = "Elizabeth")), FileLineCatalogStore(catalogFile).load()?.lines)
    }
}
