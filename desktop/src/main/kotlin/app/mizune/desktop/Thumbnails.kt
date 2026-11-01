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

    /** A thumbnail and the position it was taken at: 0 for a video's own frame, which isn't taken again. */
    private class Frame(val image: ImageBitmap, val at: Double)

    /** Bumped when a video's thumbnail appears, so only that card recomposes. */
    val versions = mutableStateMapOf<String, Long>()

    /**
     * The thumbnail if it is in memory; otherwise null, and [request] or [requestLeftOff] will
     * fetch or make it. [leftOff] for the frame from where the video was left, else its own.
     */
    fun cached(video: File, leftOff: Boolean = false): ImageBitmap? = synchronized(memory) { memory[keyOf(video, leftOff)]?.image }

    /** Loads [video]'s own thumbnail from disk, or generates it from [start] (an mpv position). It's kept as it is from then on. */
    fun request(video: File, start: String) = load(video, leftOff = false) {
        fileFor(video, 0.0).also { if (!it.exists()) generate(video, it, start) }
    }

    /**
     * Loads or generates the frame [at] seconds into [video], where it was left, for Continue
     * watching. It's kept beside the video's own thumbnail, and taken again once the video has
     * been watched more than [CLOSE] seconds past it.
     */
    fun requestLeftOff(video: File, at: Double) {
        if (synchronized(memory) { memory[keyOf(video, true)] }?.let { close(it.at, at) } == true) return
        load(video, leftOff = true) {
            val taken = leftOffFrames(video)
            taken.firstOrNull { close(atOf(it), at) } ?: fileFor(video, at).also { file ->
                generate(video, file, at.toString())
                // the old frame stays until the new one is there
                if (file.exists()) taken.forEach(File::delete)
            }
        }
    }

    private fun load(video: File, leftOff: Boolean, frame: () -> File) {
        val key = keyOf(video, leftOff)
        synchronized(queued) { if (!queued.add(key)) return }
        worker.execute {
            try {
                val file = frame()
                val image = runCatching { Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
                    ?: return@execute
                synchronized(memory) { memory[key] = Frame(image, atOf(file)) }
                Snapshot.withMutableSnapshot { versions[video.path] = System.nanoTime() }
            } finally {
                synchronized(queued) { queued.remove(key) }
            }
        }
    }

    private fun keyOf(video: File, leftOff: Boolean) = if (leftOff) video.path + "@" else video.path

    private fun close(a: Double, b: Double) = kotlin.math.abs(a - b) <= CLOSE

    // a replaced or re-encoded file gets a new thumbnail
    private fun nameOf(video: File) = hash("${video.path}|${video.length()}|${video.lastModified()}")

    /** Where a frame taken [at] seconds in goes: the position follows an @ in its name; 0 is the video's own. */
    private fun fileFor(video: File, at: Double) =
        File(directory, nameOf(video) + (if (at > 0) "@${at.toLong()}" else "") + ".jpg")

    private fun leftOffFrames(video: File): List<File> {
        val name = nameOf(video)
        return directory.listFiles { file -> file.name.startsWith("$name@") }.orEmpty().toList()
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
