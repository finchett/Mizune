package app.mizune.desktop

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserRegistrationTest {
    private val home: File = Files.createTempDirectory("home").toFile()
    private val app = "/Applications/Mizune.app/Contents/MacOS/Mizune"

    @After fun cleanUp() { home.deleteRecursively() }

    @Test fun macWritesForInstalledChromiumBrowsersAndAlwaysForFirefox() {
        File(home, "Library/Application Support/BraveSoftware/Brave-Browser").mkdirs()
        val registration = BrowserRegistration(home, "mac os x", app, File(home, "unused"))

        assertEquals(listOf("Brave", "Firefox"), registration.register())

        val brave = JSONObject(File(home, "Library/Application Support/BraveSoftware/Brave-Browser/NativeMessagingHosts/app.mizune.host.json").readText())
        assertEquals(app, brave.getString("path"))
        assertEquals("chrome-extension://${BrowserHost.CHROMIUM_ID}/", brave.getJSONArray("allowed_origins").getString(0))
        val firefox = JSONObject(File(home, "Library/Application Support/Mozilla/NativeMessagingHosts/app.mizune.host.json").readText())
        assertEquals(BrowserHost.GECKO_ID, firefox.getJSONArray("allowed_extensions").getString(0))
        // Brave reads Chrome's folder first, so that gets one too, though Chrome isn't installed
        assertTrue(File(home, "Library/Application Support/Google/Chrome/NativeMessagingHosts/app.mizune.host.json").isFile)
        assertFalse(File(home, "Library/Application Support/Microsoft Edge").exists())

        registration.unregister()
        assertFalse(File(home, "Library/Application Support/Mozilla/NativeMessagingHosts/app.mizune.host.json").exists())
        assertFalse(File(home, "Library/Application Support/BraveSoftware/Brave-Browser/NativeMessagingHosts/app.mizune.host.json").exists())
        assertFalse(File(home, "Library/Application Support/Google/Chrome/NativeMessagingHosts/app.mizune.host.json").exists())
    }

    @Test fun linuxUsesConfigAndMozillaFolders() {
        File(home, ".config/google-chrome").mkdirs()
        val registration = BrowserRegistration(home, "linux", "/home/a/Mizune.AppImage", File(home, "unused"))
        assertEquals(listOf("Chrome", "Firefox"), registration.register())
        assertTrue(File(home, ".config/google-chrome/NativeMessagingHosts/app.mizune.host.json").isFile)
        assertTrue(File(home, ".mozilla/native-messaging-hosts/app.mizune.host.json").isFile)
    }

    @Test fun windowsPointsTheRegistryAtManifestFiles() {
        val calls = mutableListOf<List<String>>()
        val manifests = File(home, "AppData/Roaming/Mizune/browser")
        val registration = BrowserRegistration(home, "windows 11", "C:\\Users\\a\\AppData\\Local\\Mizune\\Mizune.exe", manifests) {
            calls += it
            true
        }

        assertEquals(listOf("Chrome", "Edge", "Brave", "Firefox"), registration.register())
        val firefox = calls.single { it[1].contains("Mozilla") }
        assertEquals(File(manifests, "app.mizune.host.gecko.json").path, firefox[firefox.indexOf("/d") + 1])
        assertTrue(File(manifests, "app.mizune.host.chromium.json").readText().contains("Mizune.exe"))

        calls.clear()
        registration.unregister()
        assertEquals(4, calls.count { it.first() == "delete" })
    }

    @Test fun aDevRunHasNothingToRegister() {
        val registration = BrowserRegistration(home, "mac os x", null, File(home, "unused"))
        assertFalse(registration.available)
        assertEquals(emptyList<String>(), registration.register())
    }
}
