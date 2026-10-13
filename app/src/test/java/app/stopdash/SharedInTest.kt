package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.domain.SharedPlace
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executors

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
    fun `a link that can't be followed, or names no spot, falls back to the name`() = runBlocking {
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
}
