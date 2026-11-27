package app.mizune.anki

/**
 * A card's audio as Ogg Opus, as the apps make it, from what a browser records: Firefox records
 * Ogg already; Chrome records WebM, whose Opus packets are copied into Ogg pages here unchanged.
 */
object OggOpus {
    /** [recording] as Ogg Opus, or null if it's neither Ogg nor WebM with Opus in it. */
    fun from(recording: ByteArray): ByteArray? = when {
        recording.startsWith("OggS") -> recording
        recording.size > 4 && recording[0] == 0x1A.toByte() && recording[1] == 0x45.toByte() && recording[2] == 0xDF.toByte() && recording[3] == 0xA3.toByte() -> fromWebm(recording)
        else -> null
    }

    private fun ByteArray.startsWith(text: String) = size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    // ---- WebM -------------------------------------------------------------------------------

    private const val SEGMENT = 0x18538067
    private const val TRACKS = 0x1654AE6B
    private const val TRACK_ENTRY = 0xAE
    private const val TRACK_NUMBER = 0xD7
    private const val CODEC_ID = 0x86
    private const val CODEC_PRIVATE = 0x63A2
    private const val AUDIO = 0xE1
    private const val CHANNELS = 0x9F
    private const val CLUSTER = 0x1F43B675
    private const val BLOCK_GROUP = 0xA0
    private const val BLOCK = 0xA1
    private const val SIMPLE_BLOCK = 0xA3
    private const val DISCARD_PADDING = 0x75A2

    /** The elements whose children are read; everything else is skipped or read whole. */
    private val MASTERS = setOf(SEGMENT, TRACKS, TRACK_ENTRY, AUDIO, CLUSTER, BLOCK_GROUP)

    private class Track(var number: Long = -1, var codec: String = "", var head: ByteArray? = null, var channels: Int = 1)

    private fun fromWebm(data: ByteArray): ByteArray? {
        val tracks = mutableListOf<Track>()
        val packets = mutableListOf<Pair<Long, ByteArray>>()
        // nanoseconds of the last block that are only the encoder's padding
        var discard = 0L
        var track: Track? = null
        var at = 0
        while (at < data.size) {
            val id = vint(data, at, keepMarker = true) ?: break
            val size = vint(data, at + id.length, keepMarker = false) ?: break
            val start = at + id.length + size.length
            // a recording still going on writes its segment and clusters without a size
            val end = if (size.unknown) data.size else minOf(data.size.toLong(), start + size.value).toInt()
            when (id.value.toInt()) {
                TRACK_ENTRY -> track = Track().also { tracks += it }
                TRACK_NUMBER -> track?.number = uint(data, start, end)
                CODEC_ID -> track?.codec = data.copyOfRange(start, end).decodeToString()
                CODEC_PRIVATE -> track?.head = data.copyOfRange(start, end)
                CHANNELS -> track?.channels = uint(data, start, end).toInt()
                SIMPLE_BLOCK, BLOCK -> block(data, start, end)?.let { packets += it }
                DISCARD_PADDING -> discard = int(data, start, end)
            }
            at = if (id.value.toInt() in MASTERS) start else end
        }
        val opus = tracks.firstOrNull { it.codec == "A_OPUS" } ?: return null
        val frames = packets.filter { it.first == opus.number }.map { it.second }
        if (frames.isEmpty()) return null
        return write(opus.head?.takeIf { it.startsWith("OpusHead") } ?: head(opus.channels), frames, trim = (discard * 48 / 1_000_000).coerceAtLeast(0))
    }

    /** A block's track and its frame: MediaRecorder writes one frame a block, without lacing. */
    private fun block(data: ByteArray, start: Int, end: Int): Pair<Long, ByteArray>? {
        val track = vint(data, start, keepMarker = false) ?: return null
        val flags = data.getOrNull(start + track.length + 2)?.toInt() ?: return null
        if (flags and 0x06 != 0) return null
        val frame = start + track.length + 3
        return if (frame < end) track.value to data.copyOfRange(frame, end) else null
    }

    private class Vint(val value: Long, val length: Int, val unknown: Boolean)

    /** EBML's variable-length integer at [at]: an element ID with its marker bit, or a size without. */
    private fun vint(data: ByteArray, at: Int, keepMarker: Boolean): Vint? {
        val first = data.getOrNull(at)?.toInt()?.and(0xFF) ?: return null
        if (first == 0) return null
        val length = first.countLeadingZeroBits() - 23
        if (at + length > data.size) return null
        var value = if (keepMarker) first.toLong() else (first and (0xFF shr length)).toLong()
        var allOnes = value == (0xFF shr length).toLong()
        for (i in 1 until length) {
            val byte = data[at + i].toInt() and 0xFF
            value = (value shl 8) or byte.toLong()
            allOnes = allOnes && byte == 0xFF
        }
        return Vint(value, length, unknown = !keepMarker && allOnes)
    }

    private fun int(data: ByteArray, start: Int, end: Int): Long {
        if (start >= end) return 0
        val negative = data[start] < 0
        return uint(data, start, end).let { if (negative) it - (1L shl (8 * (end - start))) else it }
    }

    private fun uint(data: ByteArray, start: Int, end: Int): Long {
        var value = 0L
        for (i in start until end) value = (value shl 8) or (data[i].toLong() and 0xFF)
        return value
    }

    // ---- Ogg --------------------------------------------------------------------------------

    /** An OpusHead for a track that came without one: 48 kHz, Chrome's usual pre-skip. */
    private fun head(channels: Int): ByteArray =
        "OpusHead".encodeToByteArray() + byteArrayOf(1, channels.toByte()) + le(312, 2) + le(48_000, 4) + byteArrayOf(0, 0, 0)

    private val tags: ByteArray = "OpusTags".encodeToByteArray() + le(6, 4) + "Mizune".encodeToByteArray() + le(0, 4)

    /** [frames] in Ogg pages; the last [trim] samples are only padding, which the last page's granule leaves out. */
    private fun write(head: ByteArray, frames: List<ByteArray>, trim: Long): ByteArray {
        val serial = 0x4D697A75 // "Mizu"
        val out = Output()
        var sequence = 0
        out.page(listOf(head), granule = 0, serial, sequence++, first = true, last = false)
        out.page(listOf(tags), granule = 0, serial, sequence++, first = false, last = false)
        var granule = 0L
        var page = mutableListOf<ByteArray>()
        var segments = 0
        var pageSamples = 0
        frames.forEachIndexed { i, frame ->
            val lacing = frame.size / 255 + 1
            if (page.isNotEmpty() && (segments + lacing > 255 || pageSamples >= 48_000)) {
                out.page(page, granule, serial, sequence++, first = false, last = false)
                page = mutableListOf()
                segments = 0
                pageSamples = 0
            }
            val samples = samples(frame)
            granule += samples
            pageSamples += samples
            segments += lacing
            page += frame
            if (i == frames.lastIndex) out.page(page, granule - minOf(trim, samples.toLong()), serial, sequence++, first = false, last = true)
        }
        return out.bytes()
    }

    /** How many 48 kHz samples an Opus packet holds, from its TOC byte (RFC 6716, 3.1). */
    private fun samples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> packet.getOrNull(1)?.toInt()?.and(0x3F) ?: 0
        }
        return frame * count
    }

    private class Output {
        private val chunks = mutableListOf<ByteArray>()

        fun page(packets: List<ByteArray>, granule: Long, serial: Int, sequence: Int, first: Boolean, last: Boolean) {
            val lacing = packets.flatMap { p -> List(p.size / 255) { 255 } + (p.size % 255) }
            val header = "OggS".encodeToByteArray() + byteArrayOf(0, ((if (first) 2 else 0) or (if (last) 4 else 0)).toByte()) +
                le(granule, 8) + le(serial.toLong(), 4) + le(sequence.toLong(), 4) + le(0, 4) +
                byteArrayOf(lacing.size.toByte()) + ByteArray(lacing.size) { lacing[it].toByte() }
            val page = packets.fold(header) { acc, p -> acc + p }
            val crc = crc(page)
            for (i in 0 until 4) page[22 + i] = (crc ushr (8 * i)).toByte()
            chunks += page
        }

        fun bytes(): ByteArray {
            val out = ByteArray(chunks.sumOf { it.size })
            var at = 0
            for (c in chunks) {
                c.copyInto(out, at)
                at += c.size
            }
            return out
        }
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
    private fun le(value: Int, bytes: Int) = le(value.toLong(), bytes)

    private val CRC_TABLE = IntArray(256) { n ->
        var r = n shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg's CRC-32: not the zip one, but unreflected and starting from zero. */
    internal fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
