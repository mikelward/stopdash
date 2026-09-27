package app.stopdash

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the near-me chips take from each favorites-store answer, on a synthetic place (SPEC *Privacy*). */
class SavedPlacesOfTest {
    private val home = FavoritePlace("home", FavoriteKind.HOME, "Home", Coordinates(51.5, -0.12))

    @Test
    fun `a read list is used as is`() {
        assertEquals(listOf(home), savedPlacesOf(FavoritePlacesSet.Loaded(listOf(home))))
    }

    @Test
    fun `a read outage is not read, not an empty list`() {
        // Null leaves the chips' "already there" memory alone until the store recovers (Codex).
        assertNull(savedPlacesOf(FavoritePlacesSet.Unavailable))
    }

    @Test
    fun `a discarded file really is empty`() {
        assertEquals(emptyList<FavoritePlace>(), savedPlacesOf(FavoritePlacesSet.Discarded))
    }
}
