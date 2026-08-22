package com.jai.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CognitiveMemoryDb(context: Context) : SQLiteOpenHelper(context, "jai_memory.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cognitive_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT,
                content TEXT,
                novelty_score REAL,
                timestamp INTEGER
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_decisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action_type TEXT,
                user_approved INTEGER,
                context_tag TEXT
            )
        """.trimIndent())

        // Persistent key-value state for dynamic WebViews / Micro-Apps
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS webview_app_state (
                app_id TEXT PRIMARY KEY,
                state_json TEXT,
                last_updated INTEGER
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS webview_app_state (
                    app_id TEXT PRIMARY KEY,
                    state_json TEXT,
                    last_updated INTEGER
                )
            """.trimIndent())
        }
    }

    fun saveAppState(appId: String, stateJson: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("app_id", appId)
            put("state_json", stateJson)
            put("last_updated", System.currentTimeMillis())
        }
        db.insertWithOnConflict("webview_app_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.close()
    }

    fun getAppState(appId: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT state_json FROM webview_app_state WHERE app_id = ?",
            arrayOf(appId)
        )
        var state: String? = null
        if (cursor.moveToFirst()) {
            state = cursor.getString(0)
        }
        cursor.close()
        db.close()
        return state
    }
}

