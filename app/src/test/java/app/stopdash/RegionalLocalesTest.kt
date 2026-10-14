package app.stopdash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Each translation lives in a language-only folder (`values-fr`), so a phone set to any region of
 * that language (Canadian French, Austrian German, Mexican Spanish, Brazilian Portuguese) reads it,
 * not the English base. A region folder (`values-pt-rBR`) only overrides what differs, so its
 * region reads the override and the language folder for the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RegionalLocalesTest {
    private fun folder(qualifier: String) = File("src/main/res/values-$qualifier/strings.xml")

    private fun string(qualifier: String, name: String): String? =
        folder(qualifier).takeIf { it.exists() }?.readText()
            ?.let { Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(it)?.groupValues?.get(1) }
            ?.replace("\\'", "'")?.replace("\\\"", "\"")?.replace("&amp;", "&")

    @Test
    fun `every region of a translated language reads its translation`() {
        val english = RuntimeEnvironment.getApplication().getString(R.string.refresh)
        for ((language, regions) in REGIONS) {
            val translated = string(language, "refresh")
            assertNotEquals("values-$language translates refresh", null, translated)
            assertNotEquals("values-$language's refresh differs from English", english, translated)
            for (region in regions) {
                RuntimeEnvironment.setQualifiers("$language-r$region")
                val expected = string("$language-r$region", "refresh") ?: translated
                assertEquals("$language-$region", expected, RuntimeEnvironment.getApplication().getString(R.string.refresh))
            }
        }
    }

    @Test
    fun `every translated language has its regions checked here`() {
        val languages = File("src/main/res").list().orEmpty()
            .filter { it.startsWith("values-") && File("src/main/res/$it/strings.xml").exists() }
            // values-de, values-pt-rBR, values-b+es+419: the language is the first subtag.
            .map { it.removePrefix("values-").removePrefix("b+").substringBefore("-r").substringBefore("+") }
            .filter { it != "en" }
            .toSet()
        assertEquals(languages, REGIONS.keys)
    }

    @Test
    fun `Spanish permission steps quote each region's own Android labels`() {
        // Android words its permission buttons differently in Spain and in Latin America, and the steps
        // quote them, so Latin America (es-419, which Mexico, the US and the rest fall back to) has its own.
        val steps = listOf(R.string.journey_alerts_location_rationale_steps, R.string.journey_alerts_location_rationale_steps_foreground)
        val names = listOf("journey_alerts_location_rationale_steps", "journey_alerts_location_rationale_steps_foreground")
        for ((qualifier, folder) in listOf("es-rES" to "es", "es" to "es", "es-rMX" to "b+es+419", "es-rUS" to "b+es+419", "es-rAR" to "b+es+419")) {
            RuntimeEnvironment.setQualifiers(qualifier)
            steps.zip(names).forEach { (id, name) ->
                val expected = string(folder, name)
                assertNotEquals("values-$folder has $name", null, expected)
                assertEquals("$qualifier $name", expected, RuntimeEnvironment.getApplication().getString(id))
            }
        }
        assertNotEquals(
            "Spain and Latin America quote different labels",
            string("es", names[0]), string("b+es+419", names[0]),
        )
    }

    private companion object {
        // A language's regions with a sizable London community or a phone default of their own.
        val REGIONS = mapOf(
            "bn" to listOf("BD", "IN"),
            "de" to listOf("DE", "AT", "CH"),
            "es" to listOf("ES", "MX", "US", "CO", "AR"),
            "fr" to listOf("FR", "CA", "BE", "CH"),
            "it" to listOf("IT", "CH"),
            "pl" to listOf("PL"),
            "pt" to listOf("PT", "BR", "AO"),
            "ro" to listOf("RO", "MD"),
            "tr" to listOf("TR", "CY"),
        )
    }
}
