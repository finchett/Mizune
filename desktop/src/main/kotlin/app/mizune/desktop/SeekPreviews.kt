package app.mizune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import app.mizune.desktop.mpv.MpvLib
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures of [video] for over the seek bar, made as they're wanted: a second, headless mpv opens
 * the file on the first one asked for, seeks to the keyframe nearest each point and draws that
 * frame small with mpv's software renderer, off the player's own threads. Points are taken in
 * steps ([stepOf]); only the latest one asked for is made, and the last [KEEP] are kept. It
 * closes again once none has been asked for in a while, so nothing runs while the seek bar isn't
 * pointed at.
 */
class SeekPreviews(private val video: File) {
    private val lib = MpvLib.INSTANCE
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "seek-previews").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val memory = object : LinkedHashMap<Int, ImageBitmap>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>) = size > KEEP
    }
    private var wanted: Wanted? = null
    private var making = false
    private var closing: ScheduledFuture<*>? = null
    @Volatile private var closed = false

    private class Wanted(val step: Int, val seconds: Double, val width: Int)

    /** Bumped when a picture is made, so what shows them looks again. */
    var made by mutableLongStateOf(0L)
        private set

    /** Whether its mpv is open: from the first picture asked for until a while after the last. */
    val isOpen: Boolean get() = handle != null

    /** Whether a picture asked for is still being made. */
    val isMaking: Boolean get() = synchronized(this) { making }

    /** The step a point [seconds] into a video [duration] seconds long falls in. */
    fun stepOf(seconds: Double, duration: Double): Int = floor(seconds / stepLength(duration)).toInt().coerceAtLeast(0)

    /** The picture for [step], if it has been made. */
    fun cached(step: Int): ImageBitmap? = synchronized(memory) { memory[step] }

    /**
     * Asks for the picture of [step] ([stepOf] [duration]), [width] pixels wide. Replaces any
     * asked for before that isn't being made yet. [made] changes when it's there.
     */
    fun request(step: Int, duration: Double, width: Int) {
        if (closed || cached(step) != null) return
        val seconds = (step + 0.5) * stepLength(duration)
        synchronized(this) {
            wanted = Wanted(step, seconds.coerceAtMost(max(0.0, duration - 0.5)), width)
            if (making) return
            making = true
        }
        // closed meanwhile, and the worker with it
        runCatching { worker.execute(::makeWanted) }
    }

    /** Stops making pictures and lets mpv go. */
    fun close() {
        if (closed) return
        closed = true
        worker.execute(::shut)
        worker.shutdown()
    }

    private fun stepLength(duration: Double) = max(MIN_STEP, duration / STEPS)

    // --- on the worker ------------------------------------------------------------------------

    @Volatile private var handle: Pointer? = null
    private var renderContext: Pointer? = null
    private var aspect = 16.0 / 9.0
    private val wake = Semaphore(0)
    // kept referenced so JNA doesn't free the callback while mpv holds it
    private val updateCallback = MpvLib.UpdateCallback { wake.release() }

    private fun makeWanted() {
        closing?.cancel(false)
        while (!closed) {
            val next = synchronized(this) {
                wanted.also { wanted = null } ?: run { making = false; null }
            } ?: break
            if (cached(next.step) != null) continue
            val image = runCatching { make(next) }.onFailure {
                System.err.println("seek preview: ${it.message}")
                shut()
            }.getOrNull() ?: continue
            synchronized(memory) { memory[next.step] = image }
            Snapshot.withMutableSnapshot { made++ }
        }
        if (!closed) closing = worker.schedule(::shut, IDLE_SECONDS, TimeUnit.SECONDS)
    }

    private fun make(wanted: Wanted): ImageBitmap? {
        val handle = handle ?: open() ?: return null
        val width = wanted.width.coerceIn(16, 1024)
        val height = (width / aspect).roundToInt().coerceAtLeast(2)
        wake.drainPermits()
        if (lib.mpv_command(handle, arrayOf("seek", wanted.seconds.toString(), "absolute+keyframes", null)) < 0) return null
        var drawn: Any? = awaitRestart(handle, width, height) ?: return null
        // the frame from there may come just after mpv says it's there
        if (drawn === NOTHING_DRAWN) drawn = awaitFrame(width, height)
        return drawn as? ImageBitmap
    }

    /** Opens [video] paused in a headless mpv, waits for its first frame and returns the handle. */
    private fun open(): Pointer? {
        val handle = lib.mpv_create() ?: return null
        listOf(
            "config" to "no", "terminal" to "no", "load-scripts" to "no", "ytdl" to "no",
            "input-default-bindings" to "no", "osd-level" to "0",
            "vo" to "libmpv", "pause" to "yes", "keep-open" to "always",
            "audio" to "no", "sub" to "no", "sub-auto" to "no", "audio-file-auto" to "no",
            // a frame or two, decoded in software on one thread, so playback keeps the machine
            "hwdec" to "no", "vd-lavc-threads" to "1", "vd-lavc-skiploopfilter" to "all",
            "hr-seek" to "no", "cache" to "no", "demuxer-readahead-secs" to "0",
            "demuxer-max-bytes" to "4MiB", "demuxer-max-back-bytes" to "0",
        ).forEach { (name, value) -> lib.mpv_set_option_string(handle, name, value) }
        if (lib.mpv_initialize(handle) < 0) {
            lib.mpv_terminate_destroy(handle)
            return null
        }
        val out = arrayOfNulls<Pointer>(1)
        val api = Memory(3).apply { setString(0, "sw") }
        if (lib.mpv_render_context_create(out, handle, renderParams(MpvLib.RENDER_PARAM_API_TYPE to api)) < 0) {
            lib.mpv_terminate_destroy(handle)
            return null
        }
        this.handle = handle
        renderContext = out[0]!!.also { lib.mpv_render_context_set_update_callback(it, updateCallback, null) }
        lib.mpv_command(handle, arrayOf("loadfile", video.path, null))
        if (awaitRestart(handle, 16, 16, OPEN_TIMEOUT_MS) == null) {
            shut()
            return null
        }
        val width = property(handle, "dwidth")?.toDoubleOrNull() ?: 0.0
        val height = property(handle, "dheight")?.toDoubleOrNull() ?: 0.0
        if (width > 0 && height > 0) aspect = width / height
        return handle
    }

    /**
     * Waits for mpv to have the picture at the point it's gone to, drawing each frame that comes
     * meanwhile at [width] × [height], as mpv holds a new frame back until it's drawn. Returns
     * the last drawn, [NOTHING_DRAWN] if none came, or null if mpv can't get there.
     */
    private fun awaitRestart(handle: Pointer, width: Int, height: Int, timeoutMs: Long = SEEK_TIMEOUT_MS): Any? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var drawn: Any = NOTHING_DRAWN
        while (System.currentTimeMillis() < deadline) {
            // mpv_event: { int event_id; int error; uint64 reply_userdata; void *data; }
            val event = lib.mpv_wait_event(handle, 0.01)
            if (wake.tryAcquire()) newFrame(width, height)?.let { drawn = it }
            when (event.getInt(0)) {
                MpvLib.EVENT_PLAYBACK_RESTART -> return drawn
                MpvLib.EVENT_END_FILE, MpvLib.EVENT_SHUTDOWN -> return null
            }
        }
        return null
    }

    /** Draws mpv's next frame at [width] × [height]; the one it has, if none comes in time. */
    private fun awaitFrame(width: Int, height: Int): ImageBitmap? {
        val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (wake.tryAcquire(10, TimeUnit.MILLISECONDS)) newFrame(width, height)?.let { return it }
        }
        return draw(width, height)
    }

    /** Draws the frame mpv has, if it's a new one. */
    private fun newFrame(width: Int, height: Int): ImageBitmap? {
        val context = renderContext ?: return null
        return if (lib.mpv_render_context_update(context) and MpvLib.RENDER_UPDATE_FRAME != 0L) draw(width, height) else null
    }

    private fun draw(width: Int, height: Int): ImageBitmap? {
        val context = renderContext ?: return null
        // "rgb0" is R,G,B and a byte left as it is: Skia's RGB_888X (see MpvPlayer)
        val bitmap = Bitmap().apply { allocPixels(ImageInfo(width, height, ColorType.RGB_888X, ColorAlphaType.OPAQUE)) }
        val pixmap = bitmap.peekPixels() ?: return null
        val size = Memory(8).apply { setInt(0, width); setInt(4, height) }
        val stride = Memory(8).apply { setLong(0, pixmap.rowBytes.toLong()) }
        val format = Memory(5).apply { setString(0, "rgb0") }
        val noWait = Memory(4).apply { setInt(0, 0) }
        val result = lib.mpv_render_context_render(
            context,
            renderParams(
                MpvLib.RENDER_PARAM_SW_SIZE to size,
                MpvLib.RENDER_PARAM_SW_FORMAT to format,
                MpvLib.RENDER_PARAM_SW_STRIDE to stride,
                MpvLib.RENDER_PARAM_SW_POINTER to Pointer(pixmap.addr),
                RENDER_PARAM_BLOCK_FOR_TARGET_TIME to noWait,
            ),
        )
        if (result < 0) return null
        bitmap.notifyPixelsChanged()
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    private fun property(handle: Pointer, name: String): String? {
        val value = lib.mpv_get_property_string(handle, name) ?: return null
        return try { value.getString(0, MpvLib.UTF_8) } finally { lib.mpv_free(value) }
    }

    /** Lets mpv go; the next picture asked for opens it again. */
    private fun shut() {
        closing = null
        renderContext?.let {
            lib.mpv_render_context_set_update_callback(it, null, null)
            lib.mpv_render_context_free(it)
        }
        renderContext = null
        handle?.let(lib::mpv_terminate_destroy)
        handle = null
    }

    /** A zero-terminated mpv_render_param array: { int type; void *data; } per entry. */
    private fun renderParams(vararg entries: Pair<Int, Pointer>): Memory {
        val memory = Memory(16L * (entries.size + 1)).apply { clear() }
        entries.forEachIndexed { i, (type, data) ->
            memory.setInt(16L * i, type)
            memory.setPointer(16L * i + 8, data)
        }
        memory.setInt(16L * entries.size, MpvLib.RENDER_PARAM_INVALID)
        return memory
    }

    private companion object {
        /** Pictures kept: about a seek bar's width of them at 192 points. */
        const val KEEP = 48
        /** A video is taken in about this many steps, none shorter than [MIN_STEP] seconds. */
        const val STEPS = 600.0
        const val MIN_STEP = 2.0
        /** How long mpv stays open with no picture asked for. */
        const val IDLE_SECONDS = 20L
        const val OPEN_TIMEOUT_MS = 8_000L
        const val SEEK_TIMEOUT_MS = 3_000L
        const val FRAME_TIMEOUT_MS = 1_000L
        /** What [awaitRestart] gives when no frame came while it waited. */
        val NOTHING_DRAWN = Any()
        /** mpv_render_param_type MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME */
        const val RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
    }
}
