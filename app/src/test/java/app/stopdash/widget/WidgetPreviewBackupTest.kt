package app.stopdash.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which app version last handed the widget picker its example is kept out of backup and device
 * transfer: the system's preview belongs to this install, and a restored record would stop a new
 * phone ever getting one at the same version (Codex on #758).
 */
class WidgetPreviewBackupTest {
    private val exclude = """<exclude domain="sharedpref" path="widget_preview.xml" />"""

    private fun rules(name: String): String = File("src/main/res/xml/$name").readText()

    @Test
    fun the_published_version_is_excluded_from_backup() {
        assertTrue(exclude in rules("backup_rules.xml"))
    }

    @Test
    fun the_published_version_is_excluded_from_cloud_backup_and_device_transfer() {
        val extraction = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = extraction.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(section, body.isNotBlank())
            assertTrue(section, exclude in body)
        }
    }
}
