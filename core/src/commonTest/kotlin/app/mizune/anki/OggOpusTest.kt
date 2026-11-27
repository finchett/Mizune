package app.mizune.anki

import app.mizune.testResource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OggOpusTest {
    private class Page(val flags: Int, val granule: Long, val sequence: Int, val packets: List<ByteArray>)

    /** The pages of [ogg], each checked against its CRC. */
    private fun pages(ogg: ByteArray): List<Page> {
        val pages = mutableListOf<Page>()
        var at = 0
        while (at < ogg.size) {
            assertEquals("OggS", ogg.copyOfRange(at, at + 4).decodeToString())
            fun le(offset: Int, bytes: Int) = (0 until bytes).fold(0L) { v, i -> v or ((ogg[at + offset + i].toLong() and 0xFF) shl (8 * i)) }
            val count = ogg[at + 26].toInt() and 0xFF
            val lacing = (0 until count).map { ogg[at + 27 + it].toInt() and 0xFF }
            val size = 27 + count + lacing.sum()
            val page = ogg.copyOfRange(at, at + size)
            val stored = le(22, 4).toInt()
            for (i in 22 until 26) page[i] = 0
            assertEquals(stored, OggOpus.crc(page), "page ${pages.size}'s CRC")
            val packets = mutableListOf<ByteArray>()
            var body = at + 27 + count
            var packet = 0
            for (l in lacing) {
                packet += l
                if (l < 255) {
                    packets += ogg.copyOfRange(body, body + packet)
                    body += packet
                    packet = 0
                }
            }
            pages += Page(ogg[at + 5].toInt(), le(6, 8), le(18, 4).toInt(), packets)
            at += size
        }
        return pages
    }

    private fun check(webm: String, channels: Int, seconds: Double) {
        val ogg = OggOpus.from(testResource("recordings/$webm"))!!
        val pages = pages(ogg)
        assertEquals(List(pages.size) { it }, pages.map { it.sequence })
        assertEquals(2, pages[0].flags, "the first page begins the stream")
        assertEquals("OpusHead", pages[0].packets.single().copyOfRange(0, 8).decodeToString())
        assertEquals(channels, pages[0].packets.single()[9].toInt())
        assertEquals("OpusTags", pages[1].packets.single().copyOfRange(0, 8).decodeToString())
        assertEquals(4, pages.last().flags, "the last page ends it")
        assertTrue(pages.drop(2).all { it.packets.isNotEmpty() })
        val samples = pages.last().granule
        assertTrue(samples in (seconds * 48_000).toLong()..(seconds * 48_000 + 2_000).toLong(), "$samples samples")
    }

    @Test fun webmBecomesOgg() = check("mono.webm", channels = 1, seconds = 1.0)

    @Test fun webmStillBeingRecordedHasNoSizes() = check("stereo-live.webm", channels = 2, seconds = 1.5)

    @Test fun oggStaysAsItIs() {
        val ogg = testResource("recordings/line.ogg")
        assertContentEquals(ogg, OggOpus.from(ogg))
    }

    @Test fun anythingElseIsRefused() {
        assertNull(OggOpus.from(ByteArray(0)))
        assertNull(OggOpus.from("RIFF....WAVE".encodeToByteArray()))
        assertNull(OggOpus.from(testResource("recordings/mono.webm").copyOfRange(0, 40)))
    }
}
