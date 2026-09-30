package app.stopdash.data

import androidx.test.core.app.ApplicationProvider
import app.stopdash.domain.RouteTopology
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The synchronous [RouteTopologyStore.cached] peek, which is what lets a recreated activity
 * (rotation, its view models retained) seed its first frame with the merged grouping instead of
 * flickering through split rows while the async [RouteTopologyStore.load] re-runs. Needs a real
 * `Context` for the asset read, so it runs under Robolectric rather than as a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteTopologyStoreCacheTest {
    @Test
    fun `cached reflects a completed load, so a recreated surface sees the merged grouping`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Loading the bundled asset caches the parsed instance process-wide.
        val loaded = RouteTopologyStore.load(context)
        // Highgate → High Barnet merges (past the northern junction), so this is the real,
        // non-empty topology and not the safe EMPTY fallback.
        assertNull(loaded.grouping("northern", "940GZZLUHGT", "High Barnet", "Bank").label)

        // The synchronous peek now returns that same instance with no IO — the value a recreated
        // activity reads for its initial state, so the first frame is already merged.
        assertSame(loaded, RouteTopologyStore.cached())
    }

    @Test
    fun `a refreshed topology is used from then on, and the bundled one stays apart`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bundled = RouteTopologyStore.bundled(context)
        val refreshed = RouteTopology(bundled.patternsByLine)
        try {
            RouteTopologyStore.use(refreshed)
            assertSame(refreshed, RouteTopologyStore.load(context))
            assertSame(refreshed, RouteTopologyStore.cached())
            // A later refresh still compares with the asset, not with the last refresh.
            assertSame(bundled, RouteTopologyStore.bundled(context))
        } finally {
            // Process-wide: leave the bundled one in use for the other tests in this sandbox.
            RouteTopologyStore.use(bundled)
        }
    }
}
