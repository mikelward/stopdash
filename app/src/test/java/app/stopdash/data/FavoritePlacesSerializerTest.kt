package app.stopdash.data

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The JSON on-disk format for favorites (mirrors [StarredRowsSerializerTest]). Coordinates here are
 * synthetic (SPEC *Privacy*).
 */
class FavoritePlacesSerializerTest {
    private val sample = PersistedFavoritePlaces(
        places = listOf(
            PersistedFavoritePlace("home-1", "HOME", "Home", 51.5, -0.12),
            PersistedFavoritePlace("custom-1", "CUSTOM", "Gym", 51.6, -0.10, placeName = "Leisure Centre"),
        ),
    )

    @Test
    fun `write then read round trips`() = runTest {
        val out = ByteArrayOutputStream()
        FavoritePlacesSerializer.writeTo(sample, out)
        val read = FavoritePlacesSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample, read)
    }

    @Test
    fun `empty input reads as nothing saved`() = runTest {
        assertNull(FavoritePlacesSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corrupt bytes throw so the failure is surfaced, not taken for empty`() {
        val corrupt = "{not valid json".encodeToByteArray()
        assertThrows(CorruptionException::class.java) {
            runBlocking { FavoritePlacesSerializer.readFrom(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test
    fun `writing null writes nothing and reads back null`() = runTest {
        val out = ByteArrayOutputStream()
        FavoritePlacesSerializer.writeTo(null, out)
        assertEquals(0, out.size())
        assertNull(FavoritePlacesSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}
