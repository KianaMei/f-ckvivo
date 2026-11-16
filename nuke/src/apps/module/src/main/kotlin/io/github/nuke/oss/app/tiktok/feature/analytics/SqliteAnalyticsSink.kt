package io.github.nuke.oss.app.tiktok.feature.analytics

import android.database.sqlite.SQLiteDatabase
import java.io.File

// The local analysis database: one file in the host's private storage, read and written only from the analytics thread.
// Every statement is best-effort; a failure is swallowed so a damaged file can never break the feed.
internal class SqliteAnalyticsSink(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnalyticsSink {
    private var database: SQLiteDatabase? = open()

    private fun open(): SQLiteDatabase? = try {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).also { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS visits(id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, hour INTEGER NOT NULL, aweme TEXT NOT NULL, author TEXT NOT NULL, source TEXT NOT NULL, visible_ms INTEGER NOT NULL, ended_at INTEGER NOT NULL, end_reason TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS daily(day INTEGER PRIMARY KEY, views INTEGER NOT NULL, visible_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS hours(day INTEGER NOT NULL, hour INTEGER NOT NULL, views INTEGER NOT NULL, PRIMARY KEY(day, hour))")
            db.execSQL("CREATE TABLE IF NOT EXISTS ranks(day INTEGER NOT NULL, kind TEXT NOT NULL, item TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(day, kind, item))")
            db.execSQL("CREATE TABLE IF NOT EXISTS actions(id INTEGER PRIMARY KEY AUTOINCREMENT, day INTEGER NOT NULL, feature TEXT NOT NULL, stage TEXT NOT NULL, ts INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS collection(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, started INTEGER NOT NULL)")
        }
    } catch (_: RuntimeException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    override fun visit(visit: AnalyticsVisit) = write { db ->
        db.execSQL(
            "INSERT INTO visits(day, hour, aweme, author, source, visible_ms, ended_at, end_reason) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(visit.day, visit.hour, visit.aweme, visit.author, visit.source, visit.visibleMs, visit.endedAt, visit.endReason),
        )
        db.execSQL("INSERT OR IGNORE INTO daily(day, views, visible_ms) VALUES(?, 0, 0)", arrayOf<Any>(visit.day))
        db.execSQL("UPDATE daily SET views = views + 1, visible_ms = visible_ms + ? WHERE day = ?", arrayOf<Any>(visit.visibleMs, visit.day))
        db.execSQL("INSERT OR IGNORE INTO hours(day, hour, views) VALUES(?, ?, 0)", arrayOf<Any>(visit.day, visit.hour))
        db.execSQL("UPDATE hours SET views = views + 1 WHERE day = ? AND hour = ?", arrayOf<Any>(visit.day, visit.hour))
    }

    override fun rank(day: Long, kind: String, key: String) = write { db ->
        db.execSQL("INSERT OR IGNORE INTO ranks(day, kind, item, n) VALUES(?, ?, ?, 0)", arrayOf<Any>(day, kind, key))
        db.execSQL("UPDATE ranks SET n = n + 1 WHERE day = ? AND kind = ? AND item = ?", arrayOf<Any>(day, kind, key))
    }

    override fun action(day: Long, feature: String, stage: String) = write { db ->
        db.execSQL("INSERT INTO actions(day, feature, stage, ts) VALUES(?, ?, ?, ?)", arrayOf<Any>(day, feature, stage, clock()))
    }

    override fun collection(day: Long, started: Boolean) = write { db ->
        db.execSQL("INSERT INTO collection(ts, started) VALUES(?, ?)", arrayOf<Any>(clock(), if (started) 1 else 0))
    }

    override fun close() {
        try {
            database?.close()
        } catch (_: RuntimeException) {
        }
        database = null
    }

    private inline fun write(block: (SQLiteDatabase) -> Unit) {
        val db = database ?: return
        try {
            db.beginTransaction()
            try {
                block(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (_: RuntimeException) {
        }
    }
}
