package app.mizune.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import app.mizune.desktop.mpv.MpvLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The seek bar's pictures come from a headless mpv: one of the test pattern, the size asked for
 * and not blank. With MIZUNE_TEST_VIDEO set, it also prints how long pictures across that video
 * take. Skips itself where there's no libmpv.
 */
class SeekPreviewsTest {
    @Test(timeout = 60_000)
    fun makesAPicture() {
        assumeTrue("no libmpv here", runCatching { MpvLib.INSTANCE }.isSuccess)
        val video = Files.createTempFile("testsrc", ".mkv").toFile().apply { deleteOnExit() }
        javaClass.getResourceAsStream("/testsrc.mkv")!!.use { input -> video.outputStream().use { input.copyTo(it) } }
        val previews = SeekPreviews(video)
        try {
            val step = previews.stepOf(1.0, 2.0)
            val image = previews.await(step, 2.0, 192)
            assertEquals(192, image.width)
            // the pattern is 16:9
            assertEquals(108, image.height)
            val bitmap = image.asSkiaBitmap()
            val bytes = bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes, 0, 0)!!
            assertTrue("the picture is blank", bytes.count { it != 0.toByte() } > bytes.size / 10)
        } finally {
            previews.close()
        }
    }

    @Test(timeout = 600_000)
    fun timesAVideo() {
        val video = System.getenv("MIZUNE_TEST_VIDEO")?.let(::File)
        assumeTrue("set MIZUNE_TEST_VIDEO", video?.isFile == true)
        val previews = SeekPreviews(video!!)
        try {
            val duration = durationOf(video)
            val times = (0 until 20).map { i ->
                val started = System.nanoTime()
                previews.await(previews.stepOf(duration * (i + 0.5) / 20, duration), duration, 384)
                (System.nanoTime() - started) / 1_000_000
            }
            println("seek previews: first ${times.first()} ms, then ${times.drop(1).average().toInt()} ms on average, at most ${times.drop(1).max()} ms")
        } finally {
            previews.close()
        }
    }

    private fun durationOf(video: File): Double {
        val process = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.path).start()
        return process.inputStream.bufferedReader().readText().trim().toDoubleOrNull() ?: 60.0
    }

    private fun SeekPreviews.await(step: Int, duration: Double, width: Int): ImageBitmap {
        request(step, duration, width)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            cached(step)?.let { return it }
            Thread.sleep(5)
        }
        error("no picture for step $step")
    }
}
