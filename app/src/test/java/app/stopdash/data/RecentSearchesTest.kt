package app.stopdash.data

import app.stopdash.domain.StationMatch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Public station names only. */
class RecentSearchesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))
    private val bank = StationMatch("940GZZLUBNK", "Bank", listOf("tube"))

    @Test
    fun `From and To keep separate histories`() {
        val searches = RecentSearches(tmp.root)
        searches.store(RecentSearches.Kind.FROM).add(oxford)
        searches.store(RecentSearches.Kind.TO).add(bank)
        assertEquals(listOf(oxford), searches.store(RecentSearches.Kind.FROM).load())
        assertEquals(listOf(bank), searches.store(RecentSearches.Kind.TO).load())
        // Read back afresh, as after a restart: each from its own file.
        assertEquals(listOf(bank), RecentSearches(tmp.root).store(RecentSearches.Kind.TO).load())
    }

    @Test
    fun `Via keeps its own history, apart from From and To`() {
        val searches = RecentSearches(tmp.root)
        val finsburyPark = StationMatch("HUBFPK", "Finsbury Park", listOf("tube", "national-rail"))
        searches.store(RecentSearches.Kind.TO).add(bank)
        searches.store(RecentSearches.Kind.VIA).add(finsburyPark)
        assertEquals(listOf(finsburyPark), searches.store(RecentSearches.Kind.VIA).load())
        assertEquals(listOf(bank), searches.store(RecentSearches.Kind.TO).load())
        assertTrue(searches.store(RecentSearches.Kind.FROM).load().isEmpty())
        // Read back afresh, as after a restart: from its own file.
        assertEquals(listOf(finsburyPark), RecentSearches(tmp.root).store(RecentSearches.Kind.VIA).load())
    }

    @Test
    fun `From keeps the file it always used, so its history survives the update`() {
        FileRecentStationsStore(File(tmp.root, "recent-stations.json")).add(oxford)
        val searches = RecentSearches(tmp.root)
        assertEquals(listOf(oxford), searches.store(RecentSearches.Kind.FROM).load())
        assertTrue(searches.store(RecentSearches.Kind.TO).load().isEmpty())
    }

    @Test
    fun `each search's store is one instance, so its writes are serialized`() {
        val searches = RecentSearches(tmp.root)
        assertSame(searches.store(RecentSearches.Kind.TO), searches.store(RecentSearches.Kind.TO))
    }
}
