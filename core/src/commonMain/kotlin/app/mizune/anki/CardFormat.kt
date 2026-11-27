package app.mizune.anki

import app.mizune.parseJson
import app.mizune.string
import app.mizune.toJson
import kotlinx.serialization.json.JsonObject

/** What goes into one field of a card. */
enum class CardSource(val label: String) {
    None("Nothing"),
    Sentence("Sentence"),
    SentenceBold("Sentence, word in bold"),
    Translation("Translation (peek language)"),
    SentenceAudio("Sentence audio"),
    Screenshot("Screenshot"),
    Word("Word"),
    WordFurigana("Word with reading, as furigana"),
    Reading("Reading"),
    WordAsSeen("Word as it appears in the line"),
    PitchAccent("Pitch accent graph"),
    PitchNumber("Pitch accent number"),
    Definition("Definition, first dictionary"),
    ShortDefinition("Definition, short: a, b or c"),
    AllDefinitions("Definitions, all dictionaries"),
    Source("Episode and time"),
}

/** A note type and what fills each of its fields. Fields not listed are left empty. */
data class CardFormat(val noteType: String, val fields: Map<String, CardSource>) {

    /** The fields as JSON, {"field": "CardSource name"}, as the apps keep them in their settings. */
    fun toJson(): String = toJson(fields.mapValues { it.value.name }).toString()

    companion object {
        fun fromJson(noteType: String, json: String): CardFormat {
            val fields = parseJson(json) as? JsonObject ?: JsonObject(emptyMap())
            return CardFormat(
                noteType,
                fields.keys.associateWith { key ->
                    CardSource.entries.firstOrNull { it.name == fields.string(key) } ?: CardSource.None
                },
            )
        }
    }
}

/** Card formats that are known field by field, and a best guess for any other note type. */
object CardFormats {

    /**
     * mpvacious's "Japanese sentences+" (Sentences+). SentFurigana and the pitch number stay
     * empty, as mpvacious leaves them: the AJT Japanese add-on fills them in when the note is added.
     */
    val JapaneseSentencesPlus = CardFormat(
        "Japanese sentences+",
        mapOf(
            "SentKanji" to CardSource.SentenceBold,
            "SentFurigana" to CardSource.None,
            "SentEng" to CardSource.Translation,
            "SentAudio" to CardSource.SentenceAudio,
            "VocabKanji" to CardSource.Word,
            "VocabFurigana" to CardSource.WordFurigana,
            "VocabPitchPattern" to CardSource.PitchAccent,
            "VocabPitchNum" to CardSource.None,
            "VocabDef" to CardSource.ShortDefinition,
            "VocabAudio" to CardSource.None,
            "Image" to CardSource.Screenshot,
            "Notes" to CardSource.Source,
            "MakeProductionCard" to CardSource.None,
            "Focus" to CardSource.None,
        ),
    )

    /**
     * Lapis (github.com/donkuri/lapis), and Kiku, which is Lapis with two fields more. Its
     * Is…Card fields are switches, left off for a word card; DefinitionPicture, Hint and the
     * frequencies are the user's or Yomitan's.
     */
    val Lapis = CardFormat(
        "Lapis",
        mapOf(
            "Expression" to CardSource.Word,
            "ExpressionFurigana" to CardSource.WordFurigana,
            "ExpressionReading" to CardSource.Reading,
            "ExpressionAudio" to CardSource.None,
            "SelectionText" to CardSource.None,
            "MainDefinition" to CardSource.Definition,
            "DefinitionPicture" to CardSource.None,
            "Sentence" to CardSource.SentenceBold,
            "SentenceFurigana" to CardSource.None,
            "SentenceAudio" to CardSource.SentenceAudio,
            "Picture" to CardSource.Screenshot,
            "Glossary" to CardSource.AllDefinitions,
            "Hint" to CardSource.None,
            "IsWordAndSentenceCard" to CardSource.None,
            "IsClickCard" to CardSource.None,
            "IsSentenceCard" to CardSource.None,
            "IsAudioCard" to CardSource.None,
            "PitchPosition" to CardSource.PitchNumber,
            "PitchCategories" to CardSource.None,
            "Frequency" to CardSource.None,
            "FreqSort" to CardSource.None,
            "MiscInfo" to CardSource.Source,
        ),
    )

    /**
     * jp-mining-note 0.11 (JP Mining Note). Key, the first field, is the word too. Its pitch fields
     * take its own markup and AJT Japanese's, so they're left for those to fill; the PA… and Is…
     * fields are switches.
     */
    val JpMiningNote = CardFormat(
        "JP Mining Note",
        mapOf(
            "Key" to CardSource.Word,
            "Word" to CardSource.Word,
            "WordReading" to CardSource.WordFurigana,
            "PAOverride" to CardSource.None,
            "PAOverrideText" to CardSource.None,
            "AJTWordPitch" to CardSource.None,
            "PrimaryDefinition" to CardSource.Definition,
            "PrimaryDefinitionPicture" to CardSource.None,
            "Sentence" to CardSource.SentenceBold,
            "SentenceReading" to CardSource.None,
            "AltDisplay" to CardSource.None,
            "AltDisplayPASentenceCard" to CardSource.None,
            "AdditionalNotes" to CardSource.Source,
            "IsSentenceCard" to CardSource.None,
            "IsClickCard" to CardSource.None,
            "IsHoverCard" to CardSource.None,
            "IsTargetedSentenceCard" to CardSource.None,
            "PAShowInfo" to CardSource.None,
            "PATestOnlyWord" to CardSource.None,
            "PADoNotTest" to CardSource.None,
            "PASeparateWordCard" to CardSource.None,
            "PASeparateSentenceCard" to CardSource.None,
            "SeparateClozeDeletionCard" to CardSource.None,
            "Hint" to CardSource.None,
            "HintNotHidden" to CardSource.None,
            "Picture" to CardSource.Screenshot,
            "WordAudio" to CardSource.None,
            "SentenceAudio" to CardSource.SentenceAudio,
            "PAGraphs" to CardSource.None,
            "PAPositions" to CardSource.None,
            "PASilence" to CardSource.None,
            "WordReadingHiragana" to CardSource.Reading,
            "FrequenciesStylized" to CardSource.None,
            "FrequencySort" to CardSource.None,
            "SecondaryDefinition" to CardSource.None,
            "ExtraDefinitions" to CardSource.None,
            "UtilityDictionaries" to CardSource.None,
            "Comment" to CardSource.None,
        ),
    )

    /** Senren (github.com/BrenoAqua/Senren), 5.x. sentenceCard and audioCard are switches; notes are the user's. */
    val Senren = CardFormat(
        "Senren",
        mapOf(
            "word" to CardSource.Word,
            "reading" to CardSource.Reading,
            "sentence" to CardSource.SentenceBold,
            "sentenceFurigana" to CardSource.None,
            "sentenceTranslation" to CardSource.Translation,
            "sentenceCard" to CardSource.None,
            "audioCard" to CardSource.None,
            "notes" to CardSource.None,
            "selectionText" to CardSource.None,
            "definition" to CardSource.Definition,
            "wordAudio" to CardSource.None,
            "sentenceAudio" to CardSource.SentenceAudio,
            "picture" to CardSource.Screenshot,
            "glossary" to CardSource.AllDefinitions,
            "pitchAccents" to CardSource.None,
            "pitchPositions" to CardSource.PitchNumber,
            "pitchCategories" to CardSource.None,
            "frequencies" to CardSource.None,
            "freqSort" to CardSource.None,
            "miscInfo" to CardSource.Source,
            "dictionaryPreference" to CardSource.None,
        ),
    )

    val presets = listOf(JapaneseSentencesPlus, Lapis, JpMiningNote, Senren)

    /** Presets as earlier versions had them, so a setup nobody changed follows the new ones. */
    private val formerPresets = listOf(
        // before the short definition was the default
        JapaneseSentencesPlus.copy(fields = JapaneseSentencesPlus.fields + ("VocabDef" to CardSource.Definition)),
    )

    /** [saved], or the current preset if [saved] is an earlier version of it left as it was. */
    fun upgrade(saved: CardFormat): CardFormat {
        val former = formerPresets.firstOrNull { it.fields == saved.fields } ?: return saved
        return presets.first { it.noteType == former.noteType }.copy(noteType = saved.noteType)
    }

    /**
     * Where to get Japanese sentences(+): Ajatt-Tools' example deck on AnkiWeb, which brings the
     * note type with it (named "Japanese sentences"), and the note type's own page.
     */
    const val SENTENCES_DECK_URL = "https://ankiweb.net/shared/info/1557722832"
    const val SENTENCES_SOURCE_URL = "https://github.com/Ajatt-Tools/AnkiNoteTypes/tree/main/templates/Japanese%20sentences"

    /** Names the presets go by: mpvacious users often keep a "+" copy of Japanese sentences. */
    private val presetNames = listOf("Japanese sentences+", "Japanese sentences")

    /** The note type to start with: a known one if the collection has it. */
    fun pick(noteTypes: List<String>): String? =
        presetNames.firstNotNullOfOrNull { name -> noteTypes.firstOrNull { it.equals(name, ignoreCase = true) } }

    /**
     * The preset for a note type with [fieldNames], whatever it's called: one whose first field is
     * the same, and most of whose fields it has, as versions of a note type add and drop a few.
     */
    fun known(fieldNames: List<String>): CardFormat? = presets.firstOrNull { preset ->
        preset.fields.keys.first() == fieldNames.firstOrNull() && preset.fields.keys.count { it in fieldNames } * 4 >= preset.fields.size * 3
    }

    /** A preset's sources for the fields it knows, otherwise a mapping guessed from field names. */
    fun forNoteType(noteType: String, fieldNames: List<String>): CardFormat {
        val preset = known(fieldNames)
        return CardFormat(noteType, guessed(fieldNames, preset?.fields.orEmpty()))
    }

    /**
     * Each field's source: [given]'s, or guessed from its name, each source in one field at most
     * unless [given] says so. The first field is never left empty: Anki won't add a note without it.
     */
    private fun guessed(fieldNames: List<String>, given: Map<String, CardSource>): Map<String, CardSource> {
        val used = fieldNames.mapNotNull { given[it] }.toMutableSet()
        // a field named for where the line's from takes it ahead of one for notes, wherever they are
        val sourced = fieldNames.any { it !in given && words(it).any { word -> word in SOURCE_WORDS } }
        val fields = fieldNames.associateWith { name ->
            given[name] ?: guess(name, notesAreSource = !sourced).takeIf { it == CardSource.None || used.add(it) } ?: CardSource.None
        }.toMutableMap()
        val first = fieldNames.firstOrNull()
        if (first != null && fields[first] == CardSource.None) {
            fields[first] = listOf(CardSource.Word, CardSource.SentenceBold).firstOrNull { it !in used } ?: CardSource.Word
        }
        return fields
    }

    /**
     * What fills each of [fieldNames]: as [saved] has it where it still fits, otherwise suggested.
     * A guess an earlier version saved, and nobody changed, gives way to today's.
     */
    fun fill(saved: CardFormat?, noteType: String, fieldNames: List<String>): CardFormat {
        val suggested = forNoteType(noteType, fieldNames).fields
        val kept = saved?.takeUnless { known(fieldNames) == null && it.fields == formerGuess(fieldNames) }
        return CardFormat(noteType, fieldNames.associateWith { kept?.fields?.get(it) ?: suggested.getValue(it) })
    }

    /** Fields as versions up to 1.3 guessed them, by the words in their names, for [fill] to know them. */
    private fun formerGuess(fieldNames: List<String>): Map<String, CardSource> {
        val used = mutableSetOf<CardSource>()
        return fieldNames.associateWith { field ->
            val name = field.lowercase()
            fun has(vararg words: String) = words.any { it in name }
            val sentence = has("sent", "example", "context")
            val guess = when {
                has("audio", "sound") -> if (sentence) CardSource.SentenceAudio else CardSource.None
                has("image", "picture", "screenshot", "snapshot") -> CardSource.Screenshot
                has("pitch") -> if (has("num", "position")) CardSource.PitchNumber else CardSource.PitchAccent
                sentence && has("eng", "translation", "meaning") -> CardSource.Translation
                sentence && has("furigana", "reading") -> CardSource.None
                sentence -> CardSource.SentenceBold
                has("furigana") -> CardSource.WordFurigana
                has("reading", "kana") -> CardSource.Reading
                has("def", "meaning", "glossary", "english", "back") -> CardSource.Definition
                has("vocab", "word", "expression", "kanji", "front", "term") -> CardSource.Word
                has("note", "source", "misc") -> CardSource.Source
                else -> CardSource.None
            }
            guess.takeIf { it == CardSource.None || used.add(it) } ?: CardSource.None
        }
    }

    /** A field name's words, in lower case: "IsSentenceCard" is, sentence, card; "AJTWordPitch" ajt, word, pitch. */
    private fun words(field: String): List<String> =
        Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+").findAll(field).map { it.value.lowercase() }.toList()

    private val SOURCE_WORDS = setOf("source", "misc", "episode", "document")

    private fun guess(field: String, notesAreSource: Boolean): CardSource {
        val words = words(field)
        if (words.isEmpty()) return CardSource.None
        /** A word in the name starts with one of these. */
        fun has(vararg starts: String) = words.any { word -> starts.any { word.startsWith(it) } }
        fun hasWord(vararg exact: String) = words.any { it in exact }
        val sentence = has("sent", "example", "context") || hasWord("line")
        val definition = has("def", "meaning", "gloss")
        return when {
            // switches that turn on a kind of card, filled with anything: never a word, a sentence or the like
            words.first() in setOf("has", "use", "do") || words.last() == "card"
                || hasWord("is", "make", "show", "hide", "test", "enable", "disable", "separate", "flag", "override", "silence", "sort") -> CardSource.None
            // what's for the user to write, or for other tools (frequency lists, tags, hints) to fill
            has("freq", "tag", "hint", "comment", "selection", "secondary", "extra", "utility", "alt", "mono") -> CardSource.None
            has("audio", "sound", "voice") -> if (sentence) CardSource.SentenceAudio else CardSource.None
            // a picture of the definition is the user's, not the screenshot
            has("image", "img", "picture", "pic", "screenshot", "snapshot", "photo") -> if (definition) CardSource.None else CardSource.Screenshot
            has("pitch", "graph") || hasWord("pa") -> when {
                has("categor", "type") -> CardSource.None
                has("num", "position") -> CardSource.PitchNumber
                else -> CardSource.PitchAccent
            }
            sentence && (has("eng", "translat", "meaning")) -> CardSource.Translation
            sentence && has("furi", "reading", "kana", "hiragana") -> CardSource.None
            sentence -> CardSource.SentenceBold
            has("translat") -> CardSource.Translation
            has("furi") -> CardSource.WordFurigana
            has("reading", "kana", "hiragana", "yomi") -> CardSource.Reading
            definition || hasWord("english", "back") -> CardSource.Definition
            has("vocab", "word", "expression", "kanji", "term", "target") || hasWord("front", "key") -> CardSource.Word
            words.any { it in SOURCE_WORDS } || (notesAreSource && has("note")) -> CardSource.Source
            else -> CardSource.None
        }
    }
}
