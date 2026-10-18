package app.stopdash.data

import app.stopdash.domain.Pick
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedPickTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every level is saved and read back`() {
        listOf(
            Pick("910GEXPANC", "Example Pancras", Pick.Level.STATION, extraStopId = "490000009A"),
            Pick("490000005B", "Mill Lane", Pick.Level.STOP),
            Pick("HUBEXX", "Example Cross", Pick.Level.HUB),
            Pick("place:1", "Home", Pick.Level.PLACE, latitude = 51.5, longitude = -0.12),
        ).forEach { pick ->
            val text = json.encodeToString(PersistedPick.of(pick))
            assertEquals(pick, json.decodeFromString<PersistedPick>(text).toDomain())
        }
    }

    @Test
    fun `what isn't set isn't written`() {
        val text = json.encodeToString(PersistedPick.of(Pick("490000005B", "Mill Lane", Pick.Level.STOP)))
        assertEquals("""{"id":"490000005B","name":"Mill Lane","level":"stop"}""", text)
        assertFalse("extraStopId" in text)
    }

    @Test
    fun `a level this build doesn't know reads as none`() {
        assertNull(json.decodeFromString<PersistedPick>("""{"id":"x","name":"X","level":"junction","later":1}""").toDomain())
    }
}
