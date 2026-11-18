package app.mizune.dictionary

import app.mizune.array
import app.mizune.long
import app.mizune.parseJson
import app.mizune.string
import app.mizune.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Imports Yomitan/Yomichan dictionary zips (term banks and term meta banks). */
class YomitanImporter(private val database: DictionaryDatabase) {
    private val sql = database.connection

    data class Progress(val file: String, val terms: Int, val meta: Int)

    class ImportException(message: String) : Exception(message)

    fun import(fileName: String, onProgress: (Progress) -> Unit, open: () -> ZipSource): DictionaryInfo {
        val dictId = sql.insert(
            "INSERT INTO dictionaries (title, priority) VALUES (?, ?)",
            fileName.removeSuffix(".zip"),
            nextPriority(),
        )

        var title: String? = null
        var revision = ""
        var format = 3
        var termCount = 0
        var metaCount = 0
        var sawBank = false

        try {
            readZip(open()) { path, read ->
                val name = path.substringAfterLast('/')
                when {
                    name == "index.json" -> {
                        val index = parseJson(read().decodeToString()) as? JsonObject ?: throw ImportException("$fileName has a broken index.json")
                        title = index.string("title").ifEmpty { null }
                        revision = index.string("revision")
                        format = (index["format"].long() ?: index["version"].long() ?: 3L).toInt()
                    }
                    name.startsWith("term_bank_") && name.endsWith(".json") -> {
                        sawBank = true
                        termCount += insertTerms(dictId, bank(read, name), format)
                        onProgress(Progress(name, termCount, metaCount))
                    }
                    name.startsWith("term_meta_bank_") && name.endsWith(".json") -> {
                        sawBank = true
                        metaCount += insertMeta(dictId, bank(read, name))
                        onProgress(Progress(name, termCount, metaCount))
                    }
                }
            }
            if (title == null && !sawBank) throw ImportException("$fileName is not a Yomitan dictionary")
            if (!sawBank) throw ImportException("$fileName has no term or frequency data (kanji-only dictionaries are not supported yet)")
        } catch (e: Exception) {
            database.delete(dictId)
            throw e
        }

        val finalTitle = title ?: fileName.removeSuffix(".zip")
        // re-importing a dictionary replaces the old copy
        sql.query("SELECT id FROM dictionaries WHERE title = ? AND id != ?", finalTitle, dictId) { it.long(0) }
            .forEach(database::delete)

        sql.execute(
            "UPDATE dictionaries SET title = ?, revision = ?, term_count = ?, meta_count = ?, complete = 1 WHERE id = ?",
            finalTitle, revision, termCount, metaCount, dictId,
        )
        return database.dictionaries().first { it.id == dictId }
    }

    private fun nextPriority(): Int =
        sql.query("SELECT COALESCE(MAX(priority), -1) + 1 FROM dictionaries") { it.int(0) }.firstOrNull() ?: 0

    private fun bank(read: () -> ByteArray, name: String): JsonArray =
        parseJson(read().decodeToString()) as? JsonArray ?: throw ImportException("$name in the dictionary isn't readable")

    private fun insertTerms(dictId: Long, bank: JsonArray, format: Int): Int {
        sql.transaction {
            sql.prepare(
                "INSERT INTO terms (dict_id, expression, reading, definition_tags, rules, score, glossary, sequence, term_tags) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                for (item in bank) {
                    val row = item as? JsonArray ?: continue
                    val expression = row.string(0)
                    if (expression.isEmpty()) continue
                    val reading = row.string(1).ifEmpty { expression }

                    val glossary: JsonArray
                    val sequence: Long
                    val termTags: String
                    if (format == 1) {
                        // v1: [expression, reading, tags, rules, score, ...glossary strings]
                        glossary = JsonArray(row.drop(5))
                        sequence = 0
                        termTags = ""
                    } else {
                        glossary = row.array(5) ?: JsonArray(emptyList())
                        sequence = row.getOrNull(6).long() ?: 0
                        termTags = row.string(7)
                    }

                    statement.execute(
                        dictId, expression, reading,
                        row.string(2), row.string(3), row.getOrNull(4).long() ?: 0,
                        glossary.toString(), sequence, termTags,
                    )
                }
            }
        }
        return bank.size
    }

    private fun insertMeta(dictId: Long, bank: JsonArray): Int {
        sql.transaction {
            sql.prepare("INSERT INTO term_meta (dict_id, expression, mode, data) VALUES (?, ?, ?, ?)").use { statement ->
                for (item in bank) {
                    val row = item as? JsonArray ?: continue
                    val expression = row.string(0)
                    val mode = row.string(1)
                    if (expression.isEmpty() || mode.isEmpty()) continue
                    // a string goes in without its quotes, anything else as JSON
                    statement.execute(dictId, expression, mode, row.getOrNull(2).text() ?: "")
                }
            }
        }
        return bank.size
    }
}
