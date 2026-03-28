package com.apidebug.inspector.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class InspectorDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE traffic_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                request_url TEXT NOT NULL,
                request_method TEXT NOT NULL,
                request_headers TEXT NOT NULL,
                request_body_preview TEXT NOT NULL,
                request_body_path TEXT,
                response_status INTEGER,
                response_headers TEXT NOT NULL,
                response_body_preview TEXT NOT NULL,
                response_body_path TEXT,
                duration_ms INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                error_message TEXT,
                matched_rules TEXT NOT NULL,
                source TEXT NOT NULL,
                content_type TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS traffic_records")
            db.execSQL("DROP TABLE IF EXISTS rules")
            onCreate(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "api_debug_inspector.db"
        private const val DATABASE_VERSION = 1
    }
}
