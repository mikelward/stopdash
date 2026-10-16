package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.domain.PlaceIndex
import app.stopdash.domain.SharedPlace
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedInTest {
    private val noFollow: suspend (String) -> String? = { error("followed $it") }

    @Test
    fun `a share is read off the caller's thread`() {
        // AGENTS.md *Main thread*: the caller is the main thread when a share arrives, and shared text can
        // be long, so the reading runs on the worker.
        val caller = Executors.newSingleThreadExecutor { Thread(it, "test-caller") }.asCoroutineDispatcher()
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }.asCoroutineDispatcher()
        val threads = ThreadRecorder()
        try {
            val ask = runBlocking(caller) {
                readSharedIn(SharedIn(text = "Somewhere\nhttps://maps.app.goo.gl/X"), compute = worker) {
                    threads.note()
                    null
                }
            }
            assertEquals(SharedPlace.Ask.Search("Somewhere"), ask)
            assertEquals(listOf("test-worker"), threads.threads())
        } finally {
            caller.close()
            worker.close()
        }
    }

    @Test
    fun `shared text is searched, and nothing to go on reads as nothing`() = runBlocking {
        assertEquals(SharedPlace.Ask.Search("Somewhere"), readSharedIn(SharedIn(text = "Somewhere"), follow = noFollow))
        assertNull(readSharedIn(SharedIn(), follow = noFollow))
    }

    @Test
    fun `a short Maps link is followed to its place's pin, titled by the shared name`() = runBlocking {
        val asked = mutableListOf<String>()
        val ask = readSharedIn(SharedIn(text = "Somewhere\nhttps://maps.app.goo.gl/X")) {
            asked += it
            "https://www.google.com/maps/place/Somewhere/@51.6,-0.2,17z/data=!8m2!3d51.5!4d-0.12"
        }
        assertEquals(SharedPlace.Ask.Point(Coordinates(51.5, -0.12), "Somewhere"), ask)
        assertEquals(listOf("https://maps.app.goo.gl/X"), asked)
    }

    @Test
    fun `a full Maps link is read without asking anyone`() = runBlocking {
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.12), null),
            readSharedIn(SharedIn(text = "https://maps.google.com/?q=51.5,-0.12"), follow = noFollow),
        )
    }

    @Test
    fun `a link that can't be followed, or names no spot or postcode, falls back to the name`() = runBlocking {
        val shared = SharedIn(text = "Somewhere\nhttps://maps.app.goo.gl/X")
        assertEquals(SharedPlace.Ask.Search("Somewhere"), readSharedIn(shared) { null })
        assertEquals(
            SharedPlace.Ask.Search("Somewhere"),
            readSharedIn(shared) { "https://maps.google.com/?q=Somewhere,+A+Street&ftid=0x0:0x1" },
        )
        // A link alone that names its place only by text searches that text.
        assertEquals(
            SharedPlace.Ask.Search("Somewhere, A Street"),
            readSharedIn(SharedIn(text = "https://maps.app.goo.gl/X")) { "https://maps.google.com/?q=Somewhere,+A+Street" },
        )
        assertNull(readSharedIn(SharedIn(text = "https://maps.app.goo.gl/X")) { null })
    }

    private val index = PlaceIndex.read(
        BufferedReader(StringReader(listOf(
            "# stopdash place index v3\ttest",
            "C\tXX1 1XX\t\t51.50000\t-0.10000\t0\tXX1",
        ).joinToString("\n"))),
    )

    @Test
    fun `an address with a bundled postcode plans to the postcode, titled by its street`() = runBlocking {
        val at = SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "10 Example Street")
        val logged = mutableListOf<String>()
        val shared = SharedIn(text = "10 Example Street, London XX1 1XX, UK")
        assertEquals(at, readSharedIn(shared, follow = noFollow, places = { index }, log = { logged += it }))
        // As Calendar's Open with sends it.
        val geo = SharedIn(geo = "geo:0,0?q=10+Example+Street%2C+London+XX1+1XX%2C+UK")
        assertEquals(at, readSharedIn(geo, follow = noFollow, places = { index }, log = { logged += it }))
        // What kind of share came and how it was read, never what it said.
        assertEquals(listOf("share: text, read as a bundled postcode", "share: geo link, read as a bundled postcode"), logged)
        // A share with nothing to go on says so, not that it was read.
        logged.clear()
        assertNull(readSharedIn(SharedIn(geo = "geo:0,0"), follow = noFollow, log = { logged += it }))
        assertNull(readSharedIn(SharedIn(text = "  "), follow = noFollow, log = { logged += it }))
        assertEquals(listOf("share: geo link, read as nothing", "share: text, read as nothing"), logged)
    }

    @Test
    fun `a postcode the bundle doesn't hold is searched, as typing it is`() = runBlocking {
        assertEquals(
            SharedPlace.Ask.Search("YY1 1YY"),
            readSharedIn(SharedIn(text = "1 Far Road, Elsewhere YY1 1YY"), follow = noFollow, places = { index }),
        )
    }

    @Test
    fun `a lowercase postcode counts only where the bundle holds it`() = runBlocking {
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "10 Example Street"),
            readSharedIn(SharedIn(text = "10 Example Street, London xx1 1xx"), follow = noFollow, places = { index }),
        )
        // A real postcode after words that only look like one is still found.
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "Room b12 3rd floor"),
            readSharedIn(SharedIn(text = "Room b12 3rd floor, 10 Example Street, London XX1 1XX"), follow = noFollow, places = { index }),
        )
        // A bundled postcode before others that only look like one still wins.
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "10 Example Street"),
            readSharedIn(SharedIn(text = "10 Example Street, XX1 1XX, B12 3RD B13 3RD B14 3RD B15 3RD"), follow = noFollow, places = { index }),
        )
        // A share full of postcode-shaped runs still finds the real one.
        val decoys = (1..300).joinToString(" ") { "b${it % 90 + 10} 3rd" }
        val found = readSharedIn(SharedIn(text = "$decoys XX1 1XX"), follow = noFollow, places = { index })
        assertEquals(Coordinates(51.5, -0.1), (found as SharedPlace.Ask.Point).coordinate)
        // Words that only look like a postcode are searched as the share's name, as before.
        assertEquals(
            SharedPlace.Ask.Search("Room b12 3rd floor"),
            readSharedIn(SharedIn(text = "Room b12 3rd floor"), follow = noFollow, places = { index }),
        )
    }

    @Test
    fun `a Maps link that names its place by text plans to its address's postcode`() = runBlocking {
        // What a short link shared from the Maps app leads to: the place's name and address, no spot.
        val led = "https://maps.google.com?q=Somewhere,+10+Example+Street,+London+XX1+1XX&ftid=0x0:0x1&entry=gps"
        val logged = mutableListOf<String>()
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "Somewhere"),
            readSharedIn(SharedIn(text = "Somewhere\nhttps://maps.app.goo.gl/X?g_st=ac"), places = { index }, log = { logged += it }) { led },
        )
        assertEquals(listOf("share: text, read as a bundled postcode in a Maps link"), logged)
        // A link alone is titled by the place's name, the address's first part.
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.5, -0.1), "Somewhere"),
            readSharedIn(SharedIn(text = "https://maps.app.goo.gl/X"), places = { index }) { led },
        )
        // A postcode the bundle doesn't hold is searched, as a shared address's is.
        assertEquals(
            SharedPlace.Ask.Search("YY1 1YY"),
            readSharedIn(SharedIn(text = "Somewhere\nhttps://maps.app.goo.gl/X"), places = { index }) {
                "https://maps.google.com?q=Somewhere,+1+Far+Road,+Elsewhere+YY1+1YY&ftid=0x0:0x1"
            },
        )
        // An address in the share itself still comes first.
        assertEquals(
            SharedPlace.Ask.Search("YY1 1YY"),
            readSharedIn(SharedIn(text = "1 Far Road, Elsewhere YY1 1YY\nhttps://maps.app.goo.gl/X"), places = { index }) { led },
        )
    }

    @Test
    fun `a Maps link's spot still beats the address beside it`() = runBlocking {
        val shared = SharedIn(text = "10 Example Street, London XX1 1XX\nhttps://maps.google.com/?q=51.6,-0.2")
        assertEquals(
            SharedPlace.Ask.Point(Coordinates(51.6, -0.2), "10 Example Street"),
            readSharedIn(shared, follow = noFollow, places = { index }),
        )
    }
}
