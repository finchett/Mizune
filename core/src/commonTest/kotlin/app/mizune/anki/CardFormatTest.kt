package app.mizune.anki

import app.mizune.anki.CardSource.AllDefinitions
import app.mizune.anki.CardSource.Definition
import app.mizune.anki.CardSource.None
import app.mizune.anki.CardSource.PitchAccent
import app.mizune.anki.CardSource.Reading
import app.mizune.anki.CardSource.Screenshot
import app.mizune.anki.CardSource.SentenceAudio
import app.mizune.anki.CardSource.SentenceBold
import app.mizune.anki.CardSource.Source
import app.mizune.anki.CardSource.Translation
import app.mizune.anki.CardSource.Word
import app.mizune.anki.CardSource.WordFurigana
import kotlin.test.Test
import kotlin.test.assertEquals

/** Note types people mine with, field by field, as their makers publish them. */
class CardFormatTest {

    private fun format(vararg fields: String) = CardFormats.forNoteType("Mining", fields.toList()).fields

    private val lapis = CardFormats.Lapis.fields.keys.toList()

    @Test fun lapis() {
        assertEquals(CardFormats.Lapis.fields, format(*lapis.toTypedArray()))
        assertEquals("Lapis", CardFormats.known(lapis)?.noteType)
    }

    @Test fun kikuIsLapisWithTwoMore() {
        val kiku = lapis.toMutableList().apply {
            add(indexOf("ExpressionAudio") + 1, "RelatedExpression")
            add(indexOf("SentenceFurigana") + 1, "SentenceTranslation")
        }
        val fields = format(*kiku.toTypedArray())
        assertEquals("Lapis", CardFormats.known(kiku)?.noteType)
        assertEquals(Translation, fields["SentenceTranslation"])
        assertEquals(None, fields["RelatedExpression"])
        assertEquals(None, fields["IsSentenceCard"])
        assertEquals(Screenshot, fields["Picture"])
        assertEquals(None, fields["DefinitionPicture"])
    }

    @Test fun jpMiningNote() {
        // 0.11 as the docs list it, with the tags field some versions have
        val names = CardFormats.JpMiningNote.fields.keys.toMutableList().apply { add(indexOf("FrequencySort") + 1, "YomichanWordTags") }
        val fields = format(*names.toTypedArray())
        assertEquals(Word, fields["Key"])
        assertEquals(Word, fields["Word"])
        assertEquals(WordFurigana, fields["WordReading"])
        assertEquals(None, fields["YomichanWordTags"])
        assertEquals(None, fields["IsTargetedSentenceCard"])
        assertEquals(None, fields["PAShowInfo"])
    }

    @Test fun senren() {
        assertEquals(CardFormats.Senren.fields, format(*CardFormats.Senren.fields.keys.toTypedArray()))
    }

    @Test fun migaku() {
        assertEquals(
            mapOf("Sentence" to SentenceBold, "Translation" to Translation, "Target Word" to Word, "Definitions" to Definition,
                "Image" to Screenshot, "Sentence Audio" to SentenceAudio, "Word Audio" to None),
            format("Sentence", "Translation", "Target Word", "Definitions", "Image", "Sentence Audio", "Word Audio"),
        )
        assertEquals(Word, format("Target Word", "Sentence", "Word Audio", "Sentence Audio", "Translation", "Definitions", "Image")["Target Word"])
    }

    @Test fun kaishi() {
        assertEquals(
            mapOf("Word" to Word, "Word Reading" to Reading, "Word Meaning" to Definition, "Word Furigana" to WordFurigana,
                "Word Audio" to None, "Sentence" to SentenceBold, "Sentence Meaning" to Translation, "Sentence Furigana" to None,
                "Sentence Audio" to SentenceAudio, "Notes" to Source, "Pitch Accent" to PitchAccent, "Pitch Accent Notes" to None,
                "Picture" to Screenshot),
            format("Word", "Word Reading", "Word Meaning", "Word Furigana", "Word Audio", "Sentence", "Sentence Meaning",
                "Sentence Furigana", "Sentence Audio", "Notes", "Pitch Accent", "Pitch Accent Notes", "Picture"),
        )
    }

    @Test fun animecards() {
        assertEquals(
            mapOf("Word" to Word, "Reading" to Reading, "Glossary" to Definition, "Sentence" to SentenceBold, "Hint" to None,
                "Picture" to Screenshot, "Audio" to None, "SentenceAudio" to SentenceAudio, "Graph" to PitchAccent),
            format("Word", "Reading", "Glossary", "Sentence", "Hint", "Picture", "Audio", "SentenceAudio", "Graph"),
        )
    }

    @Test fun ajattToolsMinedSentences() {
        // Japanese sentences with a few fields more: its format, and guesses for the rest
        val fields = format("SentKanji", "SentFurigana", "SentEng", "SentAudio", "MorphManFocus", "VocabKanji", "VocabFurigana",
            "VocabPitchPattern", "VocabPitchNum", "VocabDef", "VocabDefMonolingual", "VocabAudio", "Image", "Notes", "Source", "MakeProductionCard")
        assertEquals("Japanese sentences+", CardFormats.known(fields.keys.toList())?.noteType)
        assertEquals(SentenceBold, fields["SentKanji"])
        assertEquals(CardFormats.JapaneseSentencesPlus.fields["VocabDef"], fields["VocabDef"])
        assertEquals(None, fields["VocabDefMonolingual"])
        assertEquals(None, fields["MorphManFocus"])
        assertEquals(None, fields["MakeProductionCard"])
    }

    @Test fun aSourceFieldOverNotes() {
        val fields = format("Word", "Sentence", "Notes", "Source")
        assertEquals(None, fields["Notes"])
        assertEquals(Source, fields["Source"])
        assertEquals(Source, format("Word", "Sentence", "Notes")["Notes"])
    }

    @Test fun ankisOwn() {
        assertEquals(mapOf("Front" to Word, "Back" to Definition), format("Front", "Back"))
        assertEquals(None, format("Front", "Back", "Add Reverse")["Add Reverse"])
        // a first field is never left empty: Anki won't add the note
        assertEquals(mapOf("Text" to Word, "Back Extra" to None), format("Text", "Back Extra"))
    }

    @Test fun anEarlierGuessGivesWay() {
        val names = listOf("Word", "IsSentenceCard", "Glossary", "Picture")
        // as 1.3 guessed it: the switch got the sentence
        val former = CardFormat("Mining", mapOf("Word" to Word, "IsSentenceCard" to SentenceBold, "Glossary" to Definition, "Picture" to Screenshot))
        assertEquals(None, CardFormats.fill(former, "Mining", names).fields["IsSentenceCard"])
        // one changed since is kept as it is
        val changed = former.copy(fields = former.fields + ("Glossary" to AllDefinitions))
        assertEquals(changed.fields, CardFormats.fill(changed, "Mining", names).fields)
        // a known note type's saved fields are kept too
        val saved = CardFormats.Lapis.copy(fields = CardFormats.Lapis.fields + ("Hint" to Reading))
        assertEquals(Reading, CardFormats.fill(saved, "Lapis", lapis).fields["Hint"])
    }
}
