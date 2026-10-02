package app.stopdash

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards what tells a debug StopDash from the Play one on the same phone: the
 * launcher icon's "DEBUG" bar and the "Routemo Debug" label, both keyed on the
 * build type. The bar sits below the full-size mark rather than shrinking it, so
 * it must clear the mark; and a badge a launcher mask crops looks like the real
 * icon — a crop that only shows on a device — so the lettering is checked
 * against the safe circle here, in the resources where it is authored.
 */
class LauncherIconBadgeTest {
    /** Adaptive icons: 108dp viewport, key content inside a 66dp-diameter circle. */
    private val center = 54.0
    private val safeRadius = 33.0

    /** The badge group scales a 512-unit grid down to the 108dp viewport. */
    private val badgeScale = 108.0 / 512.0

    /** Where the bar starts, in viewport units (331.9 on the 512 grid). */
    private val barTop = 331.9 * badgeScale

    @Test
    fun `manifest takes its icon and label from the build type`() {
        val application = parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element

        assertEquals("\${launcherIcon}", application.getAttribute("android:icon"))
        assertEquals("\${launcherIcon}", application.getAttribute("android:roundIcon"))
        assertEquals("\${appLabel}", application.getAttribute("android:label"))
    }

    @Test
    fun `the debug build type wears the badge and the release one does not`() {
        val build = File("build.gradle.kts").readText()
        listOf(
            """val releaseLauncherIcon = "@mipmap/ic_launcher"""",
            """val debugLauncherIcon = "@mipmap/ic_launcher_debug"""",
            """val releaseAppLabel = "@string/app_name"""",
            """val debugAppLabel = "Routemo Debug"""",
        ).forEach { assertTrue("build.gradle.kts no longer declares: $it", build.contains(it)) }

        fun block(name: String): String {
            val start = build.indexOf("        $name {")
            return build.substring(start, build.indexOf("\n        }", start))
        }
        val release = block("release")
        val debug = block("debug")
        assertTrue(release.contains("""manifestPlaceholders["launcherIcon"] = releaseLauncherIcon"""))
        assertTrue(release.contains("""manifestPlaceholders["appLabel"] = releaseAppLabel"""))
        assertTrue(debug.contains("""manifestPlaceholders["launcherIcon"] = debugLauncherIcon"""))
        assertTrue(debug.contains("""manifestPlaceholders["appLabel"] = debugAppLabel"""))
    }

    @Test
    fun `the in-app name stays unbadged`() {
        // The header reads @string/app_name and is baked into recorded screenshots.
        assertTrue(
            File("src/main/res/values/strings.xml").readText()
                .contains("""<string name="app_name">Routemo</string>"""),
        )
    }

    @Test
    fun `the debug icon layers its own foreground and monochrome`() {
        val layers = parse(File("src/main/res/mipmap-anydpi-v26/ic_launcher_debug.xml"))
            .documentElement.childElements()
            .associate { it.tagName to it.getAttribute("android:drawable") }

        assertEquals("@color/ic_launcher_background", layers["background"])
        assertEquals("@drawable/ic_launcher_foreground_debug", layers["foreground"])
        assertEquals("@drawable/ic_launcher_monochrome_debug", layers["monochrome"])
    }

    @Test
    fun `the badged mark is the plain mark, unscaled and clear of the bar`() {
        val plain = paths(drawable("ic_launcher_foreground")).map { it.getAttribute("android:pathData") }
        listOf("foreground", "monochrome").forEach { layer ->
            val mark = paths(drawable("ic_launcher_${layer}_debug")).filter { it.isMark() }
            assertEquals("$layer: the badged mark drifted from the plain one", plain,
                mark.map { it.getAttribute("android:pathData") })

            val bottom = mark.maxOf { path ->
                val pad = path.getAttribute("android:strokeWidth").toDoubleOrNull()?.div(2) ?: 0.0
                inkPoints(path.getAttribute("android:pathData")).maxOf { it.second } + pad
            }
            assertTrue("$layer mark bottom $bottom runs under the bar at $barTop", bottom < barTop)
        }
    }

    @Test
    fun `the badge lettering stays inside the safe circle`() {
        val letters = paths(drawable("ic_launcher_foreground_debug"))
            .filter { it.getAttribute("android:fillColor") == "#FFC107" }
        assertEquals("expected D, E, B, U, G", 5, letters.size)
        letters.forEach { path ->
            inkPoints(path.getAttribute("android:pathData")).forEach { (x, y) ->
                val reach = hypot(x * badgeScale - center, y * badgeScale - center)
                assertTrue("DEBUG lettering reaches $reach from center; a mask will crop it", reach <= safeRadius)
            }
        }
    }

    @Test
    fun `the monochrome badge punches its lettering out of the bar`() {
        // Themed icons tint the whole layer one color, so lettering drawn over the
        // bar would vanish; it has to be a hole in the same even-odd path.
        val fg = paths(drawable("ic_launcher_foreground_debug")).filterNot { it.isMark() }
        val expected = fg.joinToString(" ") { it.getAttribute("android:pathData") }
        val mono = paths(drawable("ic_launcher_monochrome_debug")).filterNot { it.isMark() }.single()
        assertEquals("evenOdd", mono.getAttribute("android:fillType"))
        assertEquals(expected, mono.getAttribute("android:pathData"))
    }

    private fun drawable(name: String) = File("src/main/res/drawable/$name.xml")

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun paths(file: File): List<Element> {
        val nodes = parse(file).getElementsByTagName("path")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    /** The mark is drawn at the top level; the badge sits in its own scaled group. */
    private fun Element.isMark(): Boolean = (parentNode as Element).tagName == "vector"

    /**
     * Every endpoint and control point of [data], which bounds its ink for the
     * straight and Bézier segments these icons use, plus a bound for each arc.
     */
    private fun inkPoints(data: String): List<Pair<Double, Double>> {
        val tokens = Regex("[A-Za-z]|-?\\d*\\.?\\d+").findAll(data).map { it.value }.toList()
        val points = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        var i = 0
        var command = 'M'
        fun num() = tokens[i++].toDouble()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) command = tokens[i++][0]
            when (command) {
                'M', 'L' -> { x = num(); y = num() }
                'm', 'l' -> { x += num(); y += num() }
                'H' -> x = num()
                'h' -> x += num()
                'V' -> y = num()
                'v' -> y += num()
                'Q' -> { points += num() to num(); x = num(); y = num() }
                'C' -> { points += num() to num(); points += num() to num(); x = num(); y = num() }
                'a', 'A' -> {
                    val r = maxOf(num(), num())
                    repeat(3) { num() }
                    val (sx, sy) = x to y
                    if (command == 'a') { x += num(); y += num() } else { x = num(); y = num() }
                    // These icons only draw half-circle caps, whose center is the
                    // chord's midpoint; a circle's axis extremes then bound its ink.
                    // Any other arc falls back to its endpoints padded by the radius.
                    if (abs(hypot(x - sx, y - sy) - 2 * r) < 1e-6) {
                        val cx = (sx + x) / 2
                        val cy = (sy + y) / 2
                        points += listOf(cx - r to cy, cx + r to cy, cx to cy - r, cx to cy + r)
                    } else {
                        listOf(sx to sy, x to y).forEach { (px, py) ->
                            points += listOf(px - r to py - r, px + r to py - r, px - r to py + r, px + r to py + r)
                        }
                    }
                }
                'Z', 'z' -> continue
                else -> error("unsupported path command $command in a launcher drawable")
            }
            points += x to y
        }
        return points
    }
}
