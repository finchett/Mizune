package app.mizune.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** ffmpeg, when it's installed, hears the same audio in the Ogg as in the WebM it came from. */
class OggOpusDecodeTest {
    private val ffmpeg = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg").firstOrNull { File(it).canExecute() }

    private fun pcm(file: File): ByteArray {
        val process = ProcessBuilder(ffmpeg, "-v", "error", "-i", file.path, "-f", "s16le", "-").start()
        val bytes = process.inputStream.readBytes()
        check(process.waitFor() == 0) { process.errorStream.readBytes().decodeToString() }
        return bytes
    }

    @Test fun sameSamples() {
        assumeTrue(ffmpeg != null)
        val dir = Files.createTempDirectory("ogg").toFile()
        for (name in listOf("mono.webm", "stereo-live.webm")) {
            val webm = File(dir, name).apply { writeBytes(app.mizune.testResource("recordings/$name")) }
            val ogg = File(dir, "$name.ogg").apply { writeBytes(OggOpus.from(webm.readBytes())!!) }
            assertArrayEquals(name, pcm(webm), pcm(ogg))
        }
        dir.deleteRecursively()
    }
}
