package app.mizune.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tells Chrome, Edge, Brave, Vivaldi, Chromium and Firefox where Mizune is, so the browser
 * extension can start it. Each browser reads a small JSON manifest naming the app: from a
 * folder of its own on macOS and Linux, and from a path in the registry on Windows.
 */
class BrowserRegistration(
    private val home: File = File(System.getProperty("user.home")),
    private val os: String = System.getProperty("os.name").lowercase(),
    /** The app's own launcher, which browsers run; null if there's none (a dev run without its script). */
    private val executable: String? = defaultExecutable(),
    private val manifestDir: File = File(AppDirs.data, "browser"),
    private val registry: (List<String>) -> Boolean = ::runReg,
) {
    private val isMac get() = os.contains("mac")
    private val isWindows get() = os.contains("win")

    /** Whether there's a launcher to register at all (not in a dev run). */
    val available: Boolean get() = executable != null

    /** Writes the manifests for every browser that's installed; returns the browsers it registered with. */
    fun register(): List<String> {
        val path = executable ?: return emptyList()
        return if (isWindows) registerWindows(path) else registerFiles(path)
    }

    /** Takes the manifests out again, from every browser. */
    fun unregister() {
        if (isWindows) {
            WINDOWS_KEYS.forEach { (_, key) -> registry(listOf("delete", "HKCU\\$key\\$NAME", "/f")) }
            manifestDir.listFiles()?.forEach { it.delete() }
        } else {
            folders().forEach { File(it.dir, "$NAME.json").delete() }
        }
    }

    private fun registerFiles(path: String): List<String> {
        val all = folders()
        // Brave reads Chrome's folder before its own, so a manifest there (even one left by
        // something else) would win: with Brave installed, Chrome's gets one too
        val installed = all.filter { it.installed }.toSet()
        val brave = installed.any { it.browser == "Brave" }
        return all.filter { it in installed || (brave && it.browser == "Chrome") }.mapNotNull { folder ->
            runCatching {
                folder.dir.mkdirs()
                File(folder.dir, "$NAME.json").writeText(manifest(path, folder.gecko).toString(2))
                folder.browser.takeIf { folder in installed }
            }.getOrNull()
        }
    }

    private fun registerWindows(path: String): List<String> {
        manifestDir.mkdirs()
        val chromium = File(manifestDir, "$NAME.chromium.json").apply { writeText(manifest(path, gecko = false).toString(2)) }
        val gecko = File(manifestDir, "$NAME.gecko.json").apply { writeText(manifest(path, gecko = true).toString(2)) }
        return WINDOWS_KEYS.mapNotNull { (browser, key) ->
            val file = if (browser == "Firefox") gecko else chromium
            browser.takeIf { registry(listOf("add", "HKCU\\$key\\$NAME", "/ve", "/t", "REG_SZ", "/d", file.path, "/f")) }
        }
    }

    private fun manifest(path: String, gecko: Boolean): JSONObject = JSONObject()
        .put("name", NAME)
        .put("description", "Mizune, for its browser extension")
        .put("path", path)
        .put("type", "stdio")
        .apply {
            if (gecko) put("allowed_extensions", JSONArray(listOf(BrowserHost.GECKO_ID)))
            else put("allowed_origins", JSONArray(listOf("chrome-extension://${BrowserHost.CHROMIUM_ID}/")))
        }

    private class Folder(val browser: String, val dir: File, val gecko: Boolean, val always: Boolean = false) {
        val installed get() = always || dir.parentFile.isDirectory
    }

    private fun folders(): List<Folder> {
        if (isMac) {
            val support = File(home, "Library/Application Support")
            return listOf(
                "Chrome" to "Google/Chrome", "Chrome Beta" to "Google/Chrome Beta", "Chromium" to "Chromium",
                "Edge" to "Microsoft Edge", "Brave" to "BraveSoftware/Brave-Browser", "Vivaldi" to "Vivaldi",
                "Arc" to "Arc/User Data",
            ).map { (browser, dir) -> Folder(browser, File(support, "$dir/NativeMessagingHosts"), gecko = false) } +
                // Firefox and its forks (Zen, LibreWolf, Floorp) all read Mozilla's folder
                Folder("Firefox", File(support, "Mozilla/NativeMessagingHosts"), gecko = true, always = true)
        }
        val config = System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
        return listOf(
            "Chrome" to "google-chrome", "Chrome Beta" to "google-chrome-beta", "Chromium" to "chromium",
            "Edge" to "microsoft-edge", "Brave" to "BraveSoftware/Brave-Browser", "Vivaldi" to "vivaldi",
        ).map { (browser, dir) -> Folder(browser, File(config, "$dir/NativeMessagingHosts"), gecko = false) } +
            Folder("Firefox", File(home, ".mozilla/native-messaging-hosts"), gecko = true, always = true)
    }

    companion object {
        const val NAME = "app.mizune.host"

        /** Where each browser looks on Windows, under HKEY_CURRENT_USER. */
        private val WINDOWS_KEYS = listOf(
            "Chrome" to "Software\\Google\\Chrome\\NativeMessagingHosts",
            "Edge" to "Software\\Microsoft\\Edge\\NativeMessagingHosts",
            "Brave" to "Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts",
            "Firefox" to "Software\\Mozilla\\NativeMessagingHosts",
        )

        /**
         * The packaged app's launcher: jpackage names it in jpackage.app-path. An AppImage runs
         * from a mount that changes every launch, so there it's the AppImage file itself. A dev
         * run has the script from the writeBrowserHost task.
         */
        fun defaultExecutable(): String? =
            System.getenv("APPIMAGE")?.takeIf { File(it).isFile }
                ?: System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }
                ?: System.getProperty("mizune.browserHost")?.takeIf { File(it).canExecute() }

        private fun runReg(args: List<String>): Boolean = runCatching {
            ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().run {
                inputStream.readBytes()
                waitFor() == 0
            }
        }.getOrDefault(false)
    }
}
