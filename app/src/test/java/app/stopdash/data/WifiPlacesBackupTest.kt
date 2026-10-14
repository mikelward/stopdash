package app.stopdash.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi learned at saved places rides the rider's own backup and device transfer, with the key its
 * networks are kept by, as the saved places themselves do: a new phone at home or work recognizes it
 * at once (maintainer, 2026-10-14).
 */
class WifiPlacesBackupTest {
    private val exclude = """path="${WifiPlacesStore.FILE}.xml""""

    private fun rules(name: String): String = File("src/main/res/xml/$name").readText()

    @Test
    fun the_learned_wifi_is_backed_up() {
        val rules = rules("backup_rules.xml")
        // The rules are read at all: something else is still left out.
        assertTrue("<exclude " in rules)
        assertFalse(exclude in rules)
    }

    @Test
    fun the_learned_wifi_rides_cloud_backup_and_device_transfer() {
        val extraction = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = extraction.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(section, "<exclude " in body)
            assertFalse(section, exclude in body)
        }
    }
}
