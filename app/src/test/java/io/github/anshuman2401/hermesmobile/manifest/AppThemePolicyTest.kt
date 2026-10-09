package io.github.anshuman2401.hermesmobile.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression contract for the system title bar fix: the app must declare a
 * theme that suppresses the system window title/action bar on both the
 * <application> element and MainActivity, and the theme resource itself must
 * carry the no-title/no-action-bar window attributes.
 *
 * Without an application theme, MainActivity fell back to the device default
 * theme, which drew a window title bar showing the app label ("Hermes Mobile")
 * on every screen.
 */
class AppThemePolicyTest {

    // Gradle JVM tests run with the app/ module dir as the working dir; fall
    // back to repo-root-relative paths so the test also runs from the root.
    private val manifestFile: File =
        resolveFirst("src/main/AndroidManifest.xml", "../app/src/main/AndroidManifest.xml")
    private val themesFile: File =
        resolveFirst("src/main/res/values/themes.xml", "../app/src/main/res/values/themes.xml")

    private fun resolveFirst(vararg candidates: String): File =
        candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "None of the expected files exist: ${candidates.joinToString()}"
            )

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    @Test
    fun manifest_appliesThemeToApplicationAndMainActivity() {
        val doc = parse(manifestFile)

        val applications = doc.getElementsByTagName("application")
        assertEquals("Manifest must have exactly one application tag", 1, applications.length)
        val appTheme = applications.item(0).attributes
            .getNamedItem("android:theme")?.nodeValue
        assertEquals(
            "Application must declare android:theme with no system title/action bar",
            "@style/Theme.HermesBot",
            appTheme,
        )

        val activities = doc.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .firstOrNull {
                it.attributes.getNamedItem("android:name")?.nodeValue == ".bootstrap.MainActivity"
            }
        assertNotNull("Manifest must declare .bootstrap.MainActivity", mainActivity)
        val activityTheme = mainActivity!!.attributes
            .getNamedItem("android:theme")?.nodeValue
        assertEquals(
            "MainActivity must pin the no-title-bar theme",
            "@style/Theme.HermesBot",
            activityTheme,
        )
    }

    @Test
    fun theme_resourceSuppressesSystemTitleAndActionBar() {
        val doc = parse(themesFile)

        val styles = doc.getElementsByTagName("style")
        val theme = (0 until styles.length)
            .map { styles.item(it) }
            .firstOrNull { styleNode ->
                // themes.xml has no xmlns:android declaration, so the JVM DOM
                // parser reports style attributes as unprefixed ("name",
                // "parent"), unlike AndroidManifest.xml where the android:
                // prefix resolves through the declared namespace.
                styleNode.attributes.getNamedItem("name")?.nodeValue == "Theme.HermesBot"
            }
        assertNotNull("themes.xml must define style Theme.HermesBot", theme)

        val items = mutableMapOf<String, String>()
        var index = 0
        while (index < theme!!.childNodes.length) {
            val child: Node = theme.childNodes.item(index)!!
            if (child.nodeName == "item") {
                val attrName = child.attributes.getNamedItem("android:name")
                    ?: child.attributes.getNamedItem("name")
                items[attrName!!.nodeValue] = child.textContent.trim()
            }
            index++
        }

        assertEquals(
            "Theme must set android:windowNoTitle to true",
            "true",
            items["android:windowNoTitle"],
        )
        assertEquals(
            "Theme must set android:windowActionBar to false",
            "false",
            items["android:windowActionBar"],
        )
        assertTrue(
            "Theme parent must be a NoActionBar variant",
            (theme.attributes.getNamedItem("android:parent")
                ?: theme.attributes.getNamedItem("parent"))
                ?.nodeValue?.contains("NoActionBar") == true,
        )
    }
}
