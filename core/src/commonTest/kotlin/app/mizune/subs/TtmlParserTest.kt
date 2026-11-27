package app.mizune.subs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtmlParserTest {

    @Test
    fun imscWithTicksAndRubyKeepsTheBaseText() {
        val content = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
                xmlns:tts="http://www.w3.org/ns/ttml#styling" ttp:tickRate="10000000" xml:lang="ja">
              <head>
                <styling>
                  <style xml:id="ruby_container" tts:ruby="container"/>
                  <style xml:id="ruby_base" tts:ruby="base"/>
                  <style xml:id="ruby_text" tts:ruby="text"/>
                </styling>
              </head>
              <body>
                <div>
                  <p begin="15000000t" end="38000000t"><span style="ruby_container"><span style="ruby_base">駅</span><span style="ruby_text">えき</span></span>まで<br/>歩いて行こう</p>
                  <p begin="40000000t" end="52500000t">（<span tts:ruby="container"><span tts:ruby="base">雨</span><span tts:ruby="text">あめ</span></span>の音）</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = TtmlParser.parse(content)

        assertEquals(listOf(Cue(1.5, 3.8, "駅まで\n歩いて行こう"), Cue(4.0, 5.25, "（雨の音）")), cues)
    }

    @Test
    fun clockTimesAndBodyOffsets() {
        val content = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
              <body begin="10s">
                <div>
                  <p begin="00:00:01.250" end="00:00:02.500">一つ目</p>
                  <p begin="00:00:03:05" dur="1.5s">二つ目</p>
                  <p begin="500ms" end="00:00:00.900"></p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val cues = TtmlParser.parse(content)

        assertEquals(listOf(Cue(11.25, 12.5, "一つ目"), Cue(13.2, 14.7, "二つ目")), cues.map { it.copy(start = round(it.start), end = round(it.end)) })
    }

    @Test
    fun refusesDoctypesAndJunk() {
        assertTrue(TtmlParser.parse("""<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]><tt><body><p begin="1s" end="2s">&x;</p></body></tt>""").isEmpty())
        assertTrue(TtmlParser.parse("not xml at all").isEmpty())
    }

    @Test
    fun sidecarsEndingInTtmlUseIt() {
        val track = SubtitleFiles.parseSidecar(
            "Episode 1.ja.ttml",
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="2s">はい</p></body></tt>""",
        )
        assertEquals("ja", track?.language)
        assertEquals(listOf(Cue(1.0, 2.0, "はい")), track?.cues)
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 1000) / 1000.0
}
