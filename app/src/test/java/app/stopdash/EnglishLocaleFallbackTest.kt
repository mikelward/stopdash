package app.stopdash

import android.content.res.Configuration
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The British strings reach the other Commonwealth English locales without a values-en-rXX copy
 * each: since API 24 the resource matcher falls back from en-AU (and the rest of CLDR's en-001
 * family) to values-en-rGB, while en-US keeps the US base. This pins that, so a British override
 * added to values-en-rGB alone still reaches an Australian or Canadian phone — and so a future
 * alias directory is added because this goes red, not on the assumption that it would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnglishLocaleFallbackTest {
    private val overrides: Set<String> =
        STRING_NAME.findAll(File("src/main/res/values-en-rGB/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()

    private fun stringsFor(tag: String): Map<String, String> {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        val context = base.createConfigurationContext(config)
        return overrides.associateWith { name ->
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            assertNotEquals("no string resource named $name", 0, id)
            context.getString(id)
        }
    }

    @Test
    fun `other English locales read the British overrides`() {
        assertTrue("found no en-GB overrides to check", overrides.isNotEmpty())
        val british = stringsFor("en-GB")
        // Without this the test would pass on a tree where en-GB overrides nothing.
        assertNotEquals("en-GB reads the same as en-US", stringsFor("en-US"), british)
        for (tag in COMMONWEALTH) {
            assertEquals("$tag should read the en-GB strings", british, stringsFor(tag))
        }
    }

    @Test
    fun `US English keeps the base strings`() {
        val us = stringsFor("en-US")
        val british = stringsFor("en-GB")
        assertTrue("en-US read a British override", overrides.all { us[it] != british[it] })
    }

    private companion object {
        val STRING_NAME = Regex("""<string name="([^"]+)"""")
        val COMMONWEALTH = listOf("en-AU", "en-NZ", "en-IE", "en-IN", "en-ZA", "en-CA", "en-SG")
    }
}
