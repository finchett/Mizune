package app.mizune.dictionary

import org.khronos.webgl.Int8Array

/**
 * [Sql] over SQLite compiled to WebAssembly (@sqlite.org/sqlite-wasm), for the browser
 * extension. [db] is an opened `sqlite3.oo1.DB`; in the extension's worker it keeps its file
 * in the origin's private file system.
 */
class SqliteWasmSql(private val db: dynamic) : Sql {
    private var depth = 0

    override fun execute(sql: String, vararg args: Any?) {
        statement(sql, args) { it.step() }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (Sql.Row) -> T): List<T> = statement(sql, args) { statement ->
        val row = object : Sql.Row {
            override fun long(index: Int): Long = integer(statement.get(index))
            override fun int(index: Int): Int = integer(statement.get(index)).toInt()
            override fun string(index: Int): String = statement.get(index)?.toString() ?: ""
        }
        buildList { while (statement.step() as Boolean) add(map(row)) }
    }

    override fun insert(sql: String, vararg args: Any?): Long {
        execute(sql, *args)
        return integer(db.selectValue("SELECT last_insert_rowid()"))
    }

    override fun prepare(sql: String): Sql.Prepared {
        val statement = db.prepare(sql)
        return object : Sql.Prepared {
            override fun execute(vararg args: Any?) {
                statement.reset(true)
                if (args.isNotEmpty()) statement.bind(values(args))
                statement.step()
            }

            override fun close() {
                statement.finalize()
            }
        }
    }

    // nested calls join the outer transaction, as on Android and the desktop
    override fun <T> transaction(block: () -> T): T {
        if (depth++ == 0) db.exec("BEGIN")
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            if (--depth == 0) db.exec(if (ok) "COMMIT" else "ROLLBACK")
        }
    }

    private fun <T> statement(sql: String, args: Array<out Any?>, use: (dynamic) -> T): T {
        val statement = db.prepare(sql)
        try {
            if (args.isNotEmpty()) statement.bind(values(args))
            return use(statement)
        } finally {
            statement.finalize()
        }
    }

    /** Kotlin's values as SQLite's JavaScript API takes them: a Long is an object of Kotlin's own in JavaScript. */
    private fun values(args: Array<out Any?>): Array<Any?> = Array(args.size) { i ->
        when (val value = args[i]) {
            is Long -> value.toDouble()
            is Boolean -> if (value) 1 else 0
            is ByteArray -> value.unsafeCast<Int8Array>()
            else -> value
        }
    }

    /** An integer column, which SQLite's API gives as a number, or as a BigInt past 2^53. */
    private fun integer(value: dynamic): Long = when (jsTypeOf(value)) {
        "number" -> (value as Double).toLong()
        "bigint" -> value.toString().toLong()
        "string" -> (value as String).toLongOrNull() ?: 0
        else -> 0
    }
}
