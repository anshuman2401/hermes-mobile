package com.hermes.bot.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestPolicyTest {

    @Test
    fun cameraUsesOptionalExternalActivityAndNarrowNonExportedCacheProvider() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val permissions = doc.getElementsByTagName("uses-permission")
        for (i in 0 until permissions.length) {
            val name = permissions.item(i).attributes.getNamedItem("android:name").nodeValue
            assertFalse(name == "android.permission.CAMERA" || name.contains("STORAGE") || name.startsWith("android.permission.READ_MEDIA"))
        }
        val features = doc.getElementsByTagName("uses-feature")
        val camera = (0 until features.length).map { features.item(it) }
            .single { it.attributes.getNamedItem("android:name").nodeValue == "android.hardware.camera.any" }
        assertEquals("false", camera.attributes.getNamedItem("android:required").nodeValue)
        val providers = doc.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) }
            .single { it.attributes.getNamedItem("android:authorities").nodeValue.endsWith(".cameraphotos") }
        assertEquals("false", provider.attributes.getNamedItem("android:exported").nodeValue)
        assertEquals(".data.attachments.CameraPhotoProvider", provider.attributes.getNamedItem("android:name").nodeValue)
        assertEquals("true", provider.attributes.getNamedItem("android:grantUriPermissions").nodeValue)
        val pathsFile = File(manifestFile.parentFile, "res/xml/camera_photo_paths.xml")
        val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pathsFile).documentElement
        val elements = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertEquals(1, elements.size)
        assertEquals("cache-path", elements.single().nodeName)
        assertEquals("camera-attachments/", elements.single().attributes.getNamedItem("path").nodeValue)
    }

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
