package app.stopdash

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app opts in to predictive back, so Android 14 and 15 show the system's back-to-home peek as
 * Android 16 does for a target-36 app, and hand the gesture's progress to the pages (SPEC *Back*).
 */
class PredictiveBackOptInTest {
    @Test
    fun `the app opts in to predictive back`() {
        val application = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttribute("android:enableOnBackInvokedCallback"))
    }
}
