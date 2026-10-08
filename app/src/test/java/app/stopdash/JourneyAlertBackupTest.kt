package app.stopdash

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journey alerts' state describes this install's notifications and permission prompt, so it is
 * kept out of Android backup and device transfer: restored, a "prompt gone" would send Allow to
 * Settings on a fresh install that can still ask (Codex on #700).
 */
class JourneyAlertBackupTest {
    private val files = listOf("journey-alerts-state.xml", "journey-alerts-announced.xml", "journey-alerts-dismissed.xml")

    private fun rules(name: String): String = File("src/main/res/xml/$name").readText()

    @Test
    fun the_journey_alert_state_is_excluded_from_backup() {
        val backup = rules("backup_rules.xml")
        for (file in files) assertTrue(file, """<exclude domain="sharedpref" path="$file" />""" in backup)
    }

    @Test
    fun the_journey_alert_state_is_excluded_from_cloud_backup_and_device_transfer() {
        val extraction = rules("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = extraction.substringAfter("<$section>").substringBefore("</$section>")
            assertTrue(section, body.isNotBlank())
            for (file in files) assertTrue("$section $file", """<exclude domain="sharedpref" path="$file" />""" in body)
        }
    }
}
