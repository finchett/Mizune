package app.mizune.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Pointer
import app.mizune.desktop.mpv.MpvLib
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Library thumbnails: one frame per video, decoded by a headless mpv (`--vo=image`) on a single
 * background thread and kept as JPEGs in the cache. Unlike Android, libmpv allows several
 * handles per process, so this never has to wait for the player.
 */
class Thumbnails(cacheDir: File) {
    private val directory = File(cacheDir, "thumbnails").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "thumbnails").apply { isDaemon = true } }
    private val queued = HashSet<String>()
    private val memory = object : LinkedHashMap<String, Frame>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Frame>) = size > 200
    }

    /** A thumbnail and the position it was taken at, 0 for one from a fifth of the way in. */
    private class Frame(val image: ImageBitmap, val at: Double)

    /** Bumped when a video's thumbnail appears, so only that card recomposes. */
    val versions = mutableStateMapOf<String, Long>()

    /** The thumbnail if it is in memory, from wherever it was taken; otherwise null, and [request] will fetch or make it. */
    fun cached(video: File): ImageBitmap? = synchronized(memory) { memory[video.path]?.image }

    /**
     * Loads [video]'s thumbnail from disk, or generates it: from [at] seconds in, where it was left
     * off, or a fifth of the way in for 0. One taken more than [CLOSE] seconds from [at] is taken
     * again, so a video watched further on shows where it's got to.
     */
    fun request(video: File, at: Double) {
        if (synchronized(memory) { memory[video.path] }?.let { close(it.at, at) } == true) return
        synchronized(queued) { if (!queued.add(video.path)) return }
        worker.execute {
            try {
                val taken = framesOf(video)
                val file = taken.firstOrNull { close(atOf(it), at) } ?: fileFor(video, at).also { file ->
                    generate(video, file, if (at > 0) at.toString() else "20%")
                    // the old frame stays until the new one is there
                    if (file.exists()) taken.forEach(File::delete)
                }
                val image = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
                    ?: return@execute
                synchronized(memory) { memory[video.path] = Frame(image, atOf(file)) }
                Snapshot.withMutableSnapshot { versions[video.path] = System.nanoTime() }
            } finally {
                synchronized(queued) { queued.remove(video.path) }
            }
        }
    }

    private fun close(a: Double, b: Double) = kotlin.math.abs(a - b) <= CLOSE

    // a replaced or re-encoded file gets a new thumbnail
    private fun keyOf(video: File) = hash("${video.path}|${video.length()}|${video.lastModified()}")

    /** Where a frame taken [at] seconds in goes: the position follows an @ in its name, none for 0. */
    private fun fileFor(video: File, at: Double) =
        File(directory, keyOf(video) + (if (at > 0) "@${at.toLong()}" else "") + ".jpg")

    private fun framesOf(video: File): List<File> {
        val key = keyOf(video)
        return directory.listFiles { file -> file.name == "$key.jpg" || file.name.startsWith("$key@") }.orEmpty().toList()
    }

    private fun atOf(file: File) = file.nameWithoutExtension.substringAfter('@', "0").toDoubleOrNull() ?: 0.0

    private fun generate(video: File, out: File, start: String) {
        val lib = MpvLib.INSTANCE
        val outputDir = File(directory, "gen").apply { deleteRecursively(); mkdirs() }
        val handle: Pointer = lib.mpv_create() ?: return
        try {
            listOf(
                "config" to "no", "terminal" to "no",
                "vo" to "image", "vo-image-outdir" to outputDir.path,
                "vo-image-format" to "jpg", "vo-image-jpeg-quality" to "82",
                "vf" to "scale=480:-2", "audio" to "no", "sub" to "no",
                "hwdec" to "no", "start" to start, "frames" to "1", "keep-open" to "no",
            ).forEach { (name, value) -> lib.mpv_set_option_string(handle, name, value) }
            if (lib.mpv_initialize(handle) < 0) return
            lib.mpv_command(handle, arrayOf("loadfile", video.path, null))
            val frame = waitForFrame(outputDir) ?: return
            frame.renameTo(out)
        } finally {
            lib.mpv_terminate_destroy(handle)
            outputDir.deleteRecursively()
        }
    }

    /** vo=image writes 00000001.jpg once the frame is decoded. */
    private fun waitForFrame(dir: File, timeoutMs: Long = 15_000): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val file = dir.listFiles()?.firstOrNull { it.length() > 0 }
            if (file != null) {
                // let the write finish before reading it
                var size = -1L
                while (size != file.length()) {
                    size = file.length()
                    Thread.sleep(60)
                }
                return file
            }
            Thread.sleep(80)
        }
        return null
    }

    private companion object {
        /** How far, in seconds, a frame may be from where the video was left before it's taken again; the phone's too. */
        const val CLOSE = 30.0
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
