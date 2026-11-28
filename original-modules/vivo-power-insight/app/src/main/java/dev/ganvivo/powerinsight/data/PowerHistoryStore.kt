package dev.ganvivo.powerinsight.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * 本地持久化存储（应用私有内部库 power_insight.db）：
 * 1. snapshots：保存每一次采集的完整耗电/流量/充电/温度快照（支持同天多次、跨天长期留存、备注、对比、导出）
 * 2. daily_archive：沉淀每日趋势汇总（融合 PIE .bin 与每日采集），突破系统仅保留 9 天的限制，真正支撑近 7 天/30 天对比
 */
class PowerHistoryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at_ms INTEGER NOT NULL,
                date_str TEXT NOT NULL,
                captured_time_label TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                total_drain_pct INTEGER NOT NULL,
                total_charge_pct INTEGER NOT NULL,
                drain_rate_pct_per_hour REAL NOT NULL,
                screen_on_duration_ms INTEGER NOT NULL,
                current_soc INTEGER,
                health_pct REAL,
                cycle_count INTEGER,
                max_charge_temp_c REAL,
                max_discharge_temp_c REAL,
                total_traffic_bytes INTEGER NOT NULL,
                payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_snapshots_time ON snapshots(captured_at_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE daily_archive (
                date_str TEXT PRIMARY KEY,
                total_drain_pct INTEGER NOT NULL,
                total_charge_pct INTEGER NOT NULL,
                drain_duration_ms INTEGER NOT NULL,
                drain_rate_pct_per_hour REAL NOT NULL,
                screen_on_duration_ms INTEGER NOT NULL,
                screen_on_drain_pct INTEGER NOT NULL,
                screen_off_duration_ms INTEGER NOT NULL,
                screen_off_drain_pct INTEGER NOT NULL,
                source TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 initial schema
    }

    fun saveSnapshot(snapshot: PowerSnapshot): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("captured_at_ms", snapshot.capturedAtMs)
            put("date_str", snapshot.dateStr)
            put("captured_time_label", snapshot.capturedTimeLabel)
            put("note", snapshot.note)
            put("total_drain_pct", snapshot.overview.totalDrainPct)
            put("total_charge_pct", snapshot.overview.totalChargePct)
            put("drain_rate_pct_per_hour", snapshot.overview.drainRatePctPerHour)
            put("screen_on_duration_ms", snapshot.overview.screenOnDurationMs)
            snapshot.chargingDetail.currentSoc?.let { put("current_soc", it) } ?: putNull("current_soc")
            snapshot.batteryHealth.healthPct?.let { put("health_pct", it) } ?: putNull("health_pct")
            snapshot.batteryHealth.cycleCount?.let { put("cycle_count", it) } ?: putNull("cycle_count")
            snapshot.temperatureSummary.chargeBatteryRange.maxC?.let { put("max_charge_temp_c", it) }
                ?: putNull("max_charge_temp_c")
            snapshot.temperatureSummary.dischargeBatteryRange.maxC?.let { put("max_discharge_temp_c", it) }
                ?: putNull("max_discharge_temp_c")
            put("total_traffic_bytes", snapshot.trafficSummary.totalBytes)
            put("payload_json", snapshot.toJson().toString())
        }
        val rowId = db.insert("snapshots", null, cv)

        // 同步更新当日每日归档
        upsertDailyTrend(
            DailyTrendRecord(
                dateStr = snapshot.dateStr,
                totalDrainPct = snapshot.overview.totalDrainPct,
                totalChargePct = snapshot.overview.totalChargePct,
                drainDurationMs = snapshot.overview.drainDurationMs,
                drainRatePctPerHour = snapshot.overview.drainRatePctPerHour,
                screenOnDurationMs = snapshot.overview.screenOnDurationMs,
                screenOnDrainPct = snapshot.overview.screenOnDrainPct,
                screenOffDurationMs = snapshot.overview.screenOffDurationMs,
                screenOffDrainPct = snapshot.overview.screenOffDrainPct,
                source = "pie_db"
            )
        )
        return rowId
    }

    fun upsertDailyTrends(records: List<DailyTrendRecord>) {
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in records) {
                upsertDailyTrendInternal(db, r)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertDailyTrend(record: DailyTrendRecord) {
        upsertDailyTrendInternal(writableDatabase, record)
    }

    private fun upsertDailyTrendInternal(db: SQLiteDatabase, r: DailyTrendRecord) {
        val cv = ContentValues().apply {
            put("date_str", r.dateStr)
            put("total_drain_pct", r.totalDrainPct)
            put("total_charge_pct", r.totalChargePct)
            put("drain_duration_ms", r.drainDurationMs)
            put("drain_rate_pct_per_hour", r.drainRatePctPerHour)
            put("screen_on_duration_ms", r.screenOnDurationMs)
            put("screen_on_drain_pct", r.screenOnDrainPct)
            put("screen_off_duration_ms", r.screenOffDurationMs)
            put("screen_off_drain_pct", r.screenOffDrainPct)
            put("source", r.source)
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict("daily_archive", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listSnapshotMetas(limit: Int = 200): List<SnapshotMeta> {
        val out = mutableListOf<SnapshotMeta>()
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT id, captured_at_ms, date_str, captured_time_label, note,
                   total_drain_pct, total_charge_pct, drain_rate_pct_per_hour,
                   screen_on_duration_ms, current_soc, health_pct, cycle_count,
                   max_charge_temp_c, max_discharge_temp_c, total_traffic_bytes
            FROM snapshots
            ORDER BY captured_at_ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SnapshotMeta(
                        id = c.getLong(0),
                        capturedAtMs = c.getLong(1),
                        dateStr = c.getString(2),
                        capturedTimeLabel = c.getString(3),
                        note = c.getString(4) ?: "",
                        totalDrainPct = c.getInt(5),
                        totalChargePct = c.getInt(6),
                        drainRatePctPerHour = c.getDouble(7),
                        screenOnDurationMs = c.getLong(8),
                        currentSoc = if (c.isNull(9)) null else c.getInt(9),
                        healthPct = if (c.isNull(10)) null else c.getDouble(10),
                        cycleCount = if (c.isNull(11)) null else c.getInt(11),
                        maxChargeTempC = if (c.isNull(12)) null else c.getDouble(12),
                        maxDischargeTempC = if (c.isNull(13)) null else c.getDouble(13),
                        totalTrafficBytes = c.getLong(14)
                    )
                )
            }
        }
        return out
    }

    fun loadSnapshot(id: Long): PowerSnapshot? {
        readableDatabase.rawQuery(
            "SELECT id, note, payload_json FROM snapshots WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val rowId = c.getLong(0)
            val note = c.getString(1) ?: ""
            val json = c.getString(2)
            return PowerSnapshot.fromJson(JSONObject(json)).copy(id = rowId, note = note)
        }
    }

    fun loadLatestSnapshot(): PowerSnapshot? {
        readableDatabase.rawQuery(
            "SELECT id, note, payload_json FROM snapshots ORDER BY captured_at_ms DESC LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val rowId = c.getLong(0)
            val note = c.getString(1) ?: ""
            val json = c.getString(2)
            return PowerSnapshot.fromJson(JSONObject(json)).copy(id = rowId, note = note)
        }
    }

    fun updateSnapshotNote(id: Long, note: String) {
        val cv = ContentValues().apply { put("note", note) }
        writableDatabase.update("snapshots", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteSnapshot(id: Long) {
        writableDatabase.delete("snapshots", "id = ?", arrayOf(id.toString()))
    }

    fun listDailyTrends(limit: Int = 60): List<DailyTrendRecord> {
        val out = mutableListOf<DailyTrendRecord>()
        readableDatabase.rawQuery(
            """
            SELECT date_str, total_drain_pct, total_charge_pct, drain_duration_ms,
                   drain_rate_pct_per_hour, screen_on_duration_ms, screen_on_drain_pct,
                   screen_off_duration_ms, screen_off_drain_pct, source
            FROM daily_archive
            ORDER BY date_str DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DailyTrendRecord(
                        dateStr = c.getString(0),
                        totalDrainPct = c.getInt(1),
                        totalChargePct = c.getInt(2),
                        drainDurationMs = c.getLong(3),
                        drainRatePctPerHour = c.getDouble(4),
                        screenOnDurationMs = c.getLong(5),
                        screenOnDrainPct = c.getInt(6),
                        screenOffDurationMs = c.getLong(7),
                        screenOffDrainPct = c.getInt(8),
                        source = c.getString(9)
                    )
                )
            }
        }
        return out
    }

    companion object {
        private const val DB_NAME = "power_insight.db"
        private const val DB_VERSION = 1
    }
}
