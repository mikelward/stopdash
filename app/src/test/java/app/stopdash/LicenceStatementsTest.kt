package app.stopdash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data credits' licence statements ("Contains … © Crown copyright and database right …") are
 * required word for word, so every translation keeps them in English (`dev-docs/translations.md`),
 * whatever it does with the rest of the credits.
 */
class LicenceStatementsTest {
    private fun credits(file: File): String? =
        file.takeIf { it.exists() }?.readText()?.let { CREDITS.find(it)?.groupValues?.get(1) }

    @Test
    fun `every translation keeps the licence statements in English`() {
        val statements = STATEMENT.findAll(credits(File("src/main/res/values/strings.xml")).orEmpty()).map { it.value }.toList()
        assertTrue("found no licence statements in the base credits", statements.isNotEmpty())
        val translations = File("src/main/res").listFiles().orEmpty().filter { it.name.startsWith("values-") }
        for (dir in translations) {
            val text = credits(File(dir, "strings.xml")) ?: continue
            assertEquals("${dir.name} drops or rewords a licence statement", emptyList<String>(), statements.filterNot { it in text })
        }
    }

    private companion object {
        val CREDITS = Regex("""<string name="about_data_sources">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        // "Contains … v3.0." or "Contains … 2026.": each statement up to its licence version or year.
        val STATEMENT = Regex("""Contains [^.]*?(?:v\d+\.\d+|\d{4})\.""")
    }
}
