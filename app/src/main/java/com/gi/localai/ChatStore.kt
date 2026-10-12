package com.gi.localai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatLine(val id: Long, val role: String, val body: String, val time: Long)

class ChatStore(context: Context) : SQLiteOpenHelper(context, "gi_local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, body TEXT NOT NULL, time INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE prompts(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, prompt TEXT NOT NULL)")
        db.execSQL("INSERT INTO prompts(name,prompt) VALUES('G.I Default','You are G.I, a helpful local AI assistant. Reply in the user''s language.')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun add(role: String, body: String) {
        writableDatabase.execSQL("INSERT INTO messages(role,body,time) VALUES(?,?,?)",
            arrayOf(role, body, System.currentTimeMillis()))
    }
    fun all(): List<ChatLine> {
        val result = mutableListOf<ChatLine>()
        readableDatabase.rawQuery("SELECT id,role,body,time FROM messages ORDER BY id", null).use { c ->
            while (c.moveToNext()) result += ChatLine(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return result
    }
    fun clear() = writableDatabase.delete("messages", null, null)
    fun prompts(): List<Pair<String,String>> {
        val result = mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT name,prompt FROM prompts ORDER BY id", null).use { c ->
            while (c.moveToNext()) result += c.getString(0) to c.getString(1)
        }
        return result
    }
    fun savePrompt(name: String, prompt: String) {
        writableDatabase.execSQL("INSERT INTO prompts(name,prompt) VALUES(?,?)", arrayOf(name,prompt))
    }
}
