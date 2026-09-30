package app.stopdash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * en-GB is first-tier (the app's users are in London); en-US is the base for tooling and parity with
 * the sibling repos. So a base string that uses a US spelling must have an en-GB override that
 * doesn't, or UK users read the US form.
 */
class EnGbStringsTest {
    private fun strings(path: String): Map<String, String> =
        STRING.findAll(File(path).readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val base = strings("src/main/res/values/strings.xml")
    private val enGb = strings("src/main/res/values-en-rGB/strings.xml")

    private fun usForms(text: String): List<String> =
        US_TO_GB.keys.filter { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    @Test
    fun `every base string with a US spelling has an en-GB override without it`() {
        val missing = base.filter { (name, text) -> usForms(text).isNotEmpty() && name !in enGb }.keys
        assertEquals("base strings with a US spelling and no en-GB override", emptySet<String>(), missing)
        val stillUs = enGb.filter { (_, text) -> usForms(text).isNotEmpty() }
        assertEquals("en-GB overrides still using a US spelling", emptyMap<String, String>(), stillUs)
    }

    @Test
    fun `every en-GB override names a base string`() {
        assertTrue("en-GB overrides with no base string: ${enGb.keys - base.keys}", base.keys.containsAll(enGb.keys))
    }

    private companion object {
        val STRING = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        // US forms a user could meet in this app's copy, with their en-GB form. -ize stays: British
        // publishers accept it, and "resize" reads the same either way.
        val US_TO_GB = mapOf(
            "favorite" to "favourite", "favorites" to "favourites",
            "favor" to "favour", "color" to "colour", "colors" to "colours",
            "center" to "centre", "gray" to "grey",
            "license" to "licence", "licenses" to "licences",
            "canceled" to "cancelled", "canceling" to "cancelling",
            "traveled" to "travelled", "traveling" to "travelling",
            "behavior" to "behaviour", "neighbor" to "neighbour", "neighbors" to "neighbours",
            "catalog" to "catalogue", "meter" to "metre", "meters" to "metres",
            "percent" to "per cent", "stroller" to "buggy",
        )
    }
}
