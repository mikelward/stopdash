package app.stopdash.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubInfoCacheTest {
    @Test
    fun `a hub's names are asked for once, a lookup in flight included`() = runTest {
        val cache = HubInfoCache()
        val answer = CompletableDeferred<HubInfo>()
        var fetches = 0
        val first = async { cache.load("HUBKGX") { fetches++; answer.await() } }
        val second = async { cache.load("HUBKGX") { fetches++; answer.await() } }
        testScheduler.advanceUntilIdle()
        answer.complete(HubInfo("King's Cross & St Pancras International"))
        assertEquals("King's Cross & St Pancras International", first.await().name)
        assertEquals("King's Cross & St Pancras International", second.await().name)
        assertEquals(1, fetches)
        assertEquals("King's Cross & St Pancras International", cache.load("HUBKGX") { fetches++; HubInfo() }.name)
        assertEquals(1, fetches)
    }

    @Test
    fun `a lookup that names nothing isn't kept, so the next one asks again`() = runTest {
        val cache = HubInfoCache()
        var fetches = 0
        cache.load("HUBKGX") { fetches++; HubInfo() }
        assertNull(cache["HUBKGX"])
        cache.load("HUBKGX") { fetches++; HubInfo() }
        assertEquals(2, fetches)
    }
}
