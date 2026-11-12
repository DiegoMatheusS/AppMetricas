package br.com.medidor.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable document slots. A restore can replace projects and history in one transaction. */
internal class LocalDocuments(context: Context, private val namespace: String) {
    private val helper = helper(context.applicationContext)
    private val database get() = helper.writableDatabase

    init {
        require(namespace.matches(Regex("[a-zA-Z0-9_]{1,80}")))
        transaction {
            val imported = database.rawQuery("SELECT 1 FROM migrations WHERE namespace=?", arrayOf(namespace))
                .use { it.moveToFirst() }
            if (!imported) {
                val legacy = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
                legacy.all.forEach { (key, value) ->
                    if (value is String) database.execSQL(
                        "INSERT OR IGNORE INTO documents(namespace,name,value) VALUES(?,?,?)",
                        arrayOf(namespace, key, value))
                }
                database.execSQL("INSERT INTO migrations(namespace) VALUES(?)", arrayOf(namespace))
            }
        }
    }

    fun getString(key: String, default: String?): String? = database.rawQuery(
        "SELECT value FROM documents WHERE namespace=? AND name=?", arrayOf(namespace, key)
    ).use { if (it.moveToFirst()) it.getString(0) else default }

    fun contains(key: String) = getString(key, null) != null

    val all: Map<String, String> get() = database.rawQuery(
        "SELECT name,value FROM documents WHERE namespace=?", arrayOf(namespace)
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) } }

    fun <T> transaction(block: () -> T): T {
        val db = database
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    fun edit() = Editor()

    inner class Editor {
        private var clear = false
        private val changes = linkedMapOf<String, String?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun remove(key: String) = putString(key, null)
        fun clear() = apply { clear = true }
        internal fun write(db: SQLiteDatabase) {
            if (clear) db.delete("documents", "namespace=?", arrayOf(namespace))
            changes.forEach { (key, value) ->
                if (value == null) db.delete("documents", "namespace=? AND name=?", arrayOf(namespace, key))
                else db.execSQL("INSERT OR REPLACE INTO documents(namespace,name,value) VALUES(?,?,?)",
                    arrayOf(namespace, key, value))
            }
        }
        fun commit(): Boolean = commitTogether(this)
        fun apply() { check(commit()) { "Não foi possível salvar os dados neste aparelho." } }
    }

    fun commitTogether(vararg editors: Editor): Boolean = try {
        transaction { editors.forEach { it.write(database) } }
        true
    } catch (_: android.database.SQLException) { false }

    private class Database(context: Context) : SQLiteOpenHelper(context, "metricas_documents.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE documents(namespace TEXT NOT NULL,name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 128),value TEXT NOT NULL,PRIMARY KEY(namespace,name))")
            db.execSQL("CREATE TABLE migrations(namespace TEXT PRIMARY KEY NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private val helpers = mutableMapOf<String, Database>()
        @Synchronized private fun helper(context: Context): Database =
            helpers.getOrPut(context.getDatabasePath("metricas_documents.db").absolutePath) { Database(context) }
    }
}
