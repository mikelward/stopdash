package app.stopdash.data

import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.RoutePattern
import app.stopdash.domain.RouteTopology
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The synchronous [RouteTopologyStore.cached] peek, which is what lets a recreated activity
 * (rotation, its view models retained) seed its first frame with the merged grouping instead of
 * flickering through split rows while the async [RouteTopologyStore.load] re-runs; and TfL's
 * current patterns put in use and kept for the next process ([RouteTopologyStore.use]). Needs a
 * real `Context` for the asset and cache reads, so it runs under Robolectric rather than as a
 * plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteTopologyStoreCacheTest {
    @Test
    fun `cached reflects a completed load, so a recreated surface sees the merged grouping`() {
        // Loading the bundled asset caches the parsed instance process-wide.
        val loaded = RouteTopologyStore.load(context)
        // Highgate → High Barnet merges (past the northern junction), so this is the real,
        // non-empty topology and not the safe EMPTY fallback.
        assertNull(loaded.grouping("northern", "940GZZLUHGT", "High Barnet", "Bank").label)

        // The synchronous peek now returns that same instance with no IO — the value a recreated
        // activity reads for its initial state, so the first frame is already merged.
        assertSame(loaded, RouteTopologyStore.cached())
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val liveFile get() = File(context.cacheDir, "route-topology-live.json")

    // A new process: nothing read yet, and nothing kept from another test.
    @Before
    fun startFresh() {
        liveFile.deleteRecursively()
        RouteTopologyStore.forget()
    }

    @After
    fun leaveBundled() {
        liveFile.deleteRecursively()
        RouteTopologyStore.forget()
    }

    // The bundled Northern line with its first route run on one stop further: still covering it.
    private fun extendedNorthern(bundled: RouteTopology): List<RoutePattern> {
        val patterns = bundled.patternsByLine.getValue("northern")
        val first = patterns.first()
        return listOf(first.copy(stops = first.stops + "940GEXTENDED", endB = "Extended")) + patterns.drop(1)
    }

    @Test
    fun `current patterns are used from then on, and the bundled topology stays apart`() {
        val bundled = RouteTopologyStore.bundled(context)
        val northern = extendedNorthern(bundled)
        val refreshed = RouteTopologyStore.use(context, mapOf("northern" to northern))
        assertEquals(northern, refreshed.patternsByLine["northern"])
        assertEquals(bundled.patternsByLine["central"], refreshed.patternsByLine["central"])
        assertSame(refreshed, RouteTopologyStore.load(context))
        assertSame(refreshed, RouteTopologyStore.cached())
        // A later refresh still compares with the asset, not with the last refresh.
        assertSame(bundled, RouteTopologyStore.bundled(context))
    }

    @Test
    fun `a new process starts from what the last refresh left, without asking TfL`() {
        val bundled = RouteTopologyStore.bundled(context)
        val northern = extendedNorthern(bundled)
        RouteTopologyStore.use(context, mapOf("northern" to northern))
        // Only the line that differs from the asset is kept.
        assertTrue(liveFile.readText().contains("northern"))
        assertFalse(liveFile.readText().contains("central"))

        RouteTopologyStore.forget()
        val loaded = RouteTopologyStore.load(context)
        assertEquals(northern, loaded.patternsByLine["northern"])
        assertEquals(bundled.patternsByLine["piccadilly"], loaded.patternsByLine["piccadilly"])
    }

    @Test
    fun `a refresh that can't read a line keeps what the last one left for it`() {
        val bundled = RouteTopologyStore.bundled(context)
        val northern = extendedNorthern(bundled)
        RouteTopologyStore.use(context, mapOf("northern" to northern))
        RouteTopologyStore.forget()
        // Today's refresh read only the Central line, unchanged.
        val refreshed = RouteTopologyStore.use(context, mapOf("central" to bundled.patternsByLine.getValue("central")))
        assertEquals(northern, refreshed.patternsByLine["northern"])
    }

    @Test
    fun `a save that fails is tried again by the next refresh`() {
        val bundled = RouteTopologyStore.bundled(context)
        val northern = extendedNorthern(bundled)
        // Something in the file's place that it can't replace.
        liveFile.mkdirs()
        File(liveFile, "blocker").writeText("x")
        val refreshed = RouteTopologyStore.use(context, mapOf("northern" to northern))
        // In use for this process all the same.
        assertEquals(northern, refreshed.patternsByLine["northern"])
        assertTrue(liveFile.isDirectory)

        liveFile.deleteRecursively()
        // The same lines from the next refresh: not taken as already kept.
        RouteTopologyStore.use(context, mapOf("northern" to northern))
        assertTrue(liveFile.isFile)
        RouteTopologyStore.forget()
        assertEquals(northern, RouteTopologyStore.load(context).patternsByLine["northern"])
    }

    @Test
    fun `a line back to the asset's routes is no longer kept`() {
        val bundled = RouteTopologyStore.bundled(context)
        RouteTopologyStore.use(context, mapOf("northern" to extendedNorthern(bundled)))
        val refreshed = RouteTopologyStore.use(context, mapOf("northern" to bundled.patternsByLine.getValue("northern")))
        assertEquals(bundled.patternsByLine, refreshed.patternsByLine)
        assertFalse(liveFile.exists())
        RouteTopologyStore.forget()
        assertSame(bundled, RouteTopologyStore.load(context))
    }

    @Test
    fun `kept lines that no longer cover the asset, or a file that can't be read, leave the bundled topology`() {
        val bundled = RouteTopologyStore.bundled(context)
        // Kept by an older build whose asset had this line differently: this one's routes aren't all run.
        val stale = bundled.patternsByLine.getValue("northern").drop(1)
        liveFile.writeText(
            buildJsonObject {
                put("version", 1)
                putJsonObject("lines") {
                    putJsonArray("northern") {
                        stale.forEach { p ->
                            addJsonObject {
                                put("branch", p.branch)
                                putJsonArray("stops") { p.stops.forEach { add(it) } }
                                put("endA", p.endA)
                                put("endB", p.endB)
                            }
                        }
                    }
                }
            }.toString(),
        )
        assertEquals(bundled.patternsByLine, RouteTopologyStore.load(context).patternsByLine)

        RouteTopologyStore.forget()
        liveFile.writeText("{not json")
        assertEquals(bundled.patternsByLine, RouteTopologyStore.load(context).patternsByLine)
        assertFalse(liveFile.exists())
    }
}
