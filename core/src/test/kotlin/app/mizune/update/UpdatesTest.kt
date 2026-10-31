package app.mizune.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdatesTest {
    // signed with the release key, as scripts/release.sh signs a real one
    private val manifest = javaClass.getResource("/update/update.json")!!.readBytes()
    private val signature = javaClass.getResource("/update/update.json.sig")!!.readBytes()

    @Test
    fun acceptsTheReleaseKeysSignature() {
        assertTrue(Updates.verify(manifest, signature))
    }

    @Test
    fun refusesAnEditedManifest() {
        val edited = String(manifest).replace("0.5.0", "9.9.9").toByteArray()
        assertFalse(Updates.verify(edited, signature))
        assertFalse(Updates.verify(manifest, ByteArray(0)))
    }

    @Test
    fun readsTheAssets() {
        val release = Updates.parse(String(manifest))
        assertEquals("0.5.0", release.version)
        assertEquals("Mizune-0.5.0-macos-arm64.dmg", release.assets.getValue("macos-arm64").name)
        assertEquals(3L, release.assets.getValue("android-arm64").size)
        assertEquals("", release.notes)
    }

    @Test
    fun comparesVersionsByNumber() {
        assertTrue(Updates.isNewer("0.5.0", "0.4.0"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.3"))
        assertTrue(Updates.isNewer("1.0", "0.99.99"))
        assertFalse(Updates.isNewer("0.4.0", "0.4.0"))
        assertFalse(Updates.isNewer("0.4", "0.4.0"))
        assertFalse(Updates.isNewer("0.3.9", "0.4.0"))
    }

    @Test
    fun keepsAFileThatAlreadyMatches() {
        val dir = Files.createTempDirectory("updates").toFile()
        val file = File(dir, "a.dmg").apply { writeText("abc") }
        val asset = Updates.parse(String(manifest)).assets.getValue("macos-arm64")
        // the URL doesn't resolve, so this only passes if it isn't fetched again
        assertEquals(file, Updates.download(asset, file))
        dir.deleteRecursively()
    }
    @Test
    fun defersAnActiveDownloadAndCanRetry() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = ByteArray(200_000) { 42 }
        server.createContext("/asset") { exchange ->
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        val dir = Files.createTempDirectory("update-defer").toFile()
        try {
            val expected = File(dir, "expected").apply { writeBytes(bytes) }
            val asset = UpdateAsset(name = "download",
                url = "http://127.0.0.1:${server.address.port}/asset",
                size = bytes.size.toLong(), sha256 = Updates.sha256(expected),
            )
            val target = File(dir, "download")
            var allowed = true
            org.junit.Assert.assertThrows(Updates.DownloadDeferred::class.java) {
                Updates.download(asset, target, mayDownload = { allowed }) { allowed = false }
            }
            assertFalse(target.exists())
            assertTrue(File(target.path + ".part").length() < bytes.size)
            Updates.download(asset, target)
            assertTrue(Updates.downloaded(asset, target))
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }
}
