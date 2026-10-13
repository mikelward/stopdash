package app.stopdash.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi learned at saved places is kept out of backup and device transfer: a router's address
 * stays on the phone (docs/PRIVACY.md), and a new phone relearns its own.
 */
class WifiPlacesBackupTest {
    private val exclude = """<exclude domain="sharedpref" path="${WifiPlacesStore.FILE}.xml" />"""

    private fun rules(name: String): String = File("src/main/res/xml/$name").readText()

    @Test
    fun the_learned_wifi_is_excluded_from_backup() {
        assertTrue(exclude in rules("backup_rules.xml"))
    }

    @Test
    fun the_learned_wifi_is_excluded_from_cloud_backup_and_device_transfer() {
        val extraction = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = extraction.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(section, body.isNotBlank())
            assertTrue(section, exclude in body)
        }
    }
}
