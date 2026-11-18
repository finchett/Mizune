package app.mizune.subs

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** The text tracks inside [fileName], read through [channel]; see [SubtitleFiles.hasReadableTracks]. */
fun SubtitleFiles.extractEmbedded(fileName: String, channel: FileChannel): List<SubtitleTrack> =
    if (isMp4(fileName)) Mp4SubtitleExtractor(channel).extract() else MatroskaSubtitleExtractor(channel).extract()

/** Embedded text tracks, extracted once per file version and kept as JSON in [dir]. */
class EmbeddedSubtitleCache(private val dir: File) {
    init {
        dir.mkdirs()
    }

    /** [identity] must change when the file does, e.g. "uri|length|modified". */
    fun load(identity: String, extract: () -> List<SubtitleTrack>): List<SubtitleTrack> {
        val cacheFile = File(dir, cacheKey(identity) + ".json")
        if (cacheFile.exists()) {
            runCatching { return fromJson(cacheFile.readText()) }
        }
        val tracks = extract()
        cacheFile.writeText(toJson(tracks))
        return tracks
    }

    /** Where the text read from a picture track is kept; [identity] must name the file version and the track. */
    fun lineStore(identity: String): LineStore = FileLineStore(File(dir, cacheKey(identity) + ".lines.json"))

    private fun cacheKey(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun toJson(tracks: List<SubtitleTrack>): String {
        val array = JSONArray()
        for (track in tracks) {
            val cues = JSONArray()
            for (cue in track.cues) {
                cues.put(JSONArray().put(cue.start).put(cue.end).put(cue.text))
            }
            array.put(
                JSONObject()
                    .put("name", track.name)
                    .put("language", track.language ?: JSONObject.NULL)
                    .put("cues", cues)
            )
        }
        return array.toString()
    }

    private fun fromJson(json: String): List<SubtitleTrack> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val cues = obj.getJSONArray("cues")
            SubtitleTrack(
                name = obj.getString("name"),
                language = if (obj.isNull("language")) null else obj.getString("language"),
                cues = (0 until cues.length()).map { j ->
                    val c = cues.getJSONArray(j)
                    Cue(c.getDouble(0), c.getDouble(1), c.getString(2))
                },
            )
        }
    }
}
