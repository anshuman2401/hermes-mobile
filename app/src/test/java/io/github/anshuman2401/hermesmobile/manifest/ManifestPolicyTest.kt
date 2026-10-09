package io.github.anshuman2401.hermesmobile.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestPolicyTest {

    private val manifestFile = File("src/main/AndroidManifest.xml").let {
        if (it.exists()) it else File("app/src/main/AndroidManifest.xml")
    }

    @Test
    fun manifest_declaresInternetPermissionAndDeliberateCleartextForStructuredHttp() {
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(manifestFile)

        // 1. Must declare android.permission.INTERNET
        val usesPermissions = doc.getElementsByTagName("uses-permission")
        var hasInternet = false
        for (i in 0 until usesPermissions.length) {
            val item = usesPermissions.item(i)
            val name = item.attributes?.getNamedItem("android:name")?.nodeValue
            if (name == "android.permission.INTERNET") {
                hasInternet = true
                break
            }
        }
        assertTrue("Manifest must declare android.permission.INTERNET", hasInternet)

        // 2. Application element checks
        val appList = doc.getElementsByTagName("application")
        assertTrue("Manifest must have an application tag", appList.length > 0)
        val appNode = appList.item(0)
        val attrs = appNode.attributes

        val usesCleartext = attrs.getNamedItem("android:usesCleartextTraffic")?.nodeValue
        assertEquals("android:usesCleartextTraffic must be explicitly enabled for CHG-001 HTTP", "true", usesCleartext)

        val allowBackup = attrs.getNamedItem("android:allowBackup")?.nodeValue
        assertEquals("android:allowBackup must be false", "false", allowBackup)

        val appName = attrs.getNamedItem("android:name")?.nodeValue
        assertEquals("android:name must be .bootstrap.HermesBotApplication", ".bootstrap.HermesBotApplication", appName)
    }

    @Test
    fun manifest_declaresCorrectMainActivity() {
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(manifestFile)

        val activities = doc.getElementsByTagName("activity")
        var hasLauncher = false
        for (i in 0 until activities.length) {
            val act = activities.item(i)
            val name = act.attributes?.getNamedItem("android:name")?.nodeValue
            if (name == ".bootstrap.MainActivity") {
                hasLauncher = true
                val exported = act.attributes?.getNamedItem("android:exported")?.nodeValue
                assertEquals("MainActivity must be exported", "true", exported)
            }
        }
        assertTrue("Manifest must declare .bootstrap.MainActivity as launcher", hasLauncher)
    }
}
