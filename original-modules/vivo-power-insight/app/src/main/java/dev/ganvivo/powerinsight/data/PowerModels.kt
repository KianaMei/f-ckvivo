package dev.ganvivo.powerinsight.data

import org.json.JSONArray
import org.json.JSONObject

data class PowerSnapshot(
    val id: Long = 0L,
    val capturedAtMs: Long,
    val dateStr: String,               // yyyy-MM-dd
    val capturedTimeLabel: String,     // yyyy-MM-dd HH:mm:ss
    val deviceModel: String,
    val romVersion: String,
    val dataRangeStart: String?,
    val dataRangeEnd: String?,
    val note: String = "",
    val overview: DailyOverview,
    val appUsages: List<AppPowerUsage>,
    val hourlyItems: List<HourlyPowerItem>,
    val trafficSummary: TrafficSummary,
    val batteryHealth: BatteryHealthInfo,
    val chargingDetail: ChargingDetail,
    val temperatureSummary: TemperatureSummary,
    val standbySummary: StandbySummary
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("capturedAtMs", capturedAtMs)
        put("dateStr", dateStr)
        put("capturedTimeLabel", capturedTimeLabel)
        put("deviceModel", deviceModel)
        put("romVersion", romVersion)
        putOpt("dataRangeStart", dataRangeStart)
        putOpt("dataRangeEnd", dataRangeEnd)
        put("note", note)
        put("overview", overview.toJson())
        put("appUsages", JSONArray().apply { appUsages.forEach { put(it.toJson()) } })
        put("hourlyItems", JSONArray().apply { hourlyItems.forEach { put(it.toJson()) } })
        put("trafficSummary", trafficSummary.toJson())
        put("batteryHealth", batteryHealth.toJson())
        put("chargingDetail", chargingDetail.toJson())
        put("temperatureSummary", temperatureSummary.toJson())
        put("standbySummary", standbySummary.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): PowerSnapshot = PowerSnapshot(
            id = o.optLong("id", 0L),
            capturedAtMs = o.optLong("capturedAtMs", 0L),
            dateStr = o.optString("dateStr", ""),
            capturedTimeLabel = o.optString("capturedTimeLabel", ""),
            deviceModel = o.optString("deviceModel", ""),
            romVersion = o.optString("romVersion", ""),
            dataRangeStart = o.optString("dataRangeStart").takeIf { it.isNotBlank() && it != "null" },
            dataRangeEnd = o.optString("dataRangeEnd").takeIf { it.isNotBlank() && it != "null" },
            note = o.optString("note", ""),
            overview = DailyOverview.fromJson(o.optJSONObject("overview") ?: JSONObject()),
            appUsages = o.optJSONArray("appUsages").toList { AppPowerUsage.fromJson(it) },
            hourlyItems = o.optJSONArray("hourlyItems").toList { HourlyPowerItem.fromJson(it) },
            trafficSummary = TrafficSummary.fromJson(o.optJSONObject("trafficSummary") ?: JSONObject()),
            batteryHealth = BatteryHealthInfo.fromJson(o.optJSONObject("batteryHealth") ?: JSONObject()),
            chargingDetail = ChargingDetail.fromJson(o.optJSONObject("chargingDetail") ?: JSONObject()),
            temperatureSummary = TemperatureSummary.fromJson(o.optJSONObject("temperatureSummary") ?: JSONObject()),
            standbySummary = StandbySummary.fromJson(o.optJSONObject("standbySummary") ?: JSONObject())
        )
    }
}

data class DailyOverview(
    val totalDrainPct: Int,
    val totalChargePct: Int,
    val drainDurationMs: Long,
    val drainRatePctPerHour: Double,
    val screenOnDurationMs: Long,
    val screenOnDrainPct: Int,
    val screenOnDrainRatePctPerHour: Double,
    val screenOffDurationMs: Long,
    val screenOffDrainPct: Int,
    val screenOffDrainRatePctPerHour: Double,
    val avgBrightnessNit: Double?,
    val totalGasGaugeMah: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("totalDrainPct", totalDrainPct)
        put("totalChargePct", totalChargePct)
        put("drainDurationMs", drainDurationMs)
        put("drainRatePctPerHour", drainRatePctPerHour)
        put("screenOnDurationMs", screenOnDurationMs)
        put("screenOnDrainPct", screenOnDrainPct)
        put("screenOnDrainRatePctPerHour", screenOnDrainRatePctPerHour)
        put("screenOffDurationMs", screenOffDurationMs)
        put("screenOffDrainPct", screenOffDrainPct)
        put("screenOffDrainRatePctPerHour", screenOffDrainRatePctPerHour)
        putOpt("avgBrightnessNit", avgBrightnessNit)
        put("totalGasGaugeMah", totalGasGaugeMah)
    }

    companion object {
        fun fromJson(o: JSONObject): DailyOverview = DailyOverview(
            totalDrainPct = o.optInt("totalDrainPct"),
            totalChargePct = o.optInt("totalChargePct"),
            drainDurationMs = o.optLong("drainDurationMs"),
            drainRatePctPerHour = o.optDouble("drainRatePctPerHour", 0.0),
            screenOnDurationMs = o.optLong("screenOnDurationMs"),
            screenOnDrainPct = o.optInt("screenOnDrainPct"),
            screenOnDrainRatePctPerHour = o.optDouble("screenOnDrainRatePctPerHour", 0.0),
            screenOffDurationMs = o.optLong("screenOffDurationMs"),
            screenOffDrainPct = o.optInt("screenOffDrainPct"),
            screenOffDrainRatePctPerHour = o.optDouble("screenOffDrainRatePctPerHour", 0.0),
            avgBrightnessNit = o.optNullableDouble("avgBrightnessNit"),
            totalGasGaugeMah = o.optDouble("totalGasGaugeMah", 0.0)
        )
    }
}

data class AppPowerUsage(
    val packageName: String,
    val appName: String,
    val foregroundDurationMs: Long,
    val drainPct: Int,
    val drainRatePctPerHour: Double,
    val avgCurrentMa: Double,
    val avgBrightnessNit: Double?,
    val gasGaugeMah: Double,
    val recordCount: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("foregroundDurationMs", foregroundDurationMs)
        put("drainPct", drainPct)
        put("drainRatePctPerHour", drainRatePctPerHour)
        put("avgCurrentMa", avgCurrentMa)
        putOpt("avgBrightnessNit", avgBrightnessNit)
        put("gasGaugeMah", gasGaugeMah)
        put("recordCount", recordCount)
    }

    companion object {
        fun fromJson(o: JSONObject): AppPowerUsage = AppPowerUsage(
            packageName = o.optString("packageName"),
            appName = o.optString("appName"),
            foregroundDurationMs = o.optLong("foregroundDurationMs"),
            drainPct = o.optInt("drainPct"),
            drainRatePctPerHour = o.optDouble("drainRatePctPerHour", 0.0),
            avgCurrentMa = o.optDouble("avgCurrentMa", 0.0),
            avgBrightnessNit = o.optNullableDouble("avgBrightnessNit"),
            gasGaugeMah = o.optDouble("gasGaugeMah", 0.0),
            recordCount = o.optInt("recordCount")
        )
    }
}

data class HourlyPowerItem(
    val hour: Int,                  // 0..23
    val label: String,              // 08:00–09:00
    val chargePct: Int,
    val dischargePct: Int,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val chargeDurationMs: Long,
    val statusText: String,
    val hasRecords: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hour", hour)
        put("label", label)
        put("chargePct", chargePct)
        put("dischargePct", dischargePct)
        put("screenOnMs", screenOnMs)
        put("screenOffMs", screenOffMs)
        put("chargeDurationMs", chargeDurationMs)
        put("statusText", statusText)
        put("hasRecords", hasRecords)
    }

    companion object {
        fun fromJson(o: JSONObject): HourlyPowerItem = HourlyPowerItem(
            hour = o.optInt("hour"),
            label = o.optString("label"),
            chargePct = o.optInt("chargePct"),
            dischargePct = o.optInt("dischargePct"),
            screenOnMs = o.optLong("screenOnMs"),
            screenOffMs = o.optLong("screenOffMs"),
            chargeDurationMs = o.optLong("chargeDurationMs"),
            statusText = o.optString("statusText"),
            hasRecords = o.optBoolean("hasRecords", true)
        )
    }
}

data class DailyTrendRecord(
    val dateStr: String,           // yyyy-MM-dd
    val totalDrainPct: Int,
    val totalChargePct: Int,
    val drainDurationMs: Long,
    val drainRatePctPerHour: Double,
    val screenOnDurationMs: Long,
    val screenOnDrainPct: Int,
    val screenOffDurationMs: Long,
    val screenOffDrainPct: Int,
    val source: String             // "pie_db" | "pie_bin" | "snapshot"
)

data class TrafficSummary(
    val totalBytes: Long,
    val wifiTotalBytes: Long,
    val mobileTotalBytes: Long,
    val foregroundTotalBytes: Long,
    val backgroundTotalBytes: Long,
    val apps: List<AppTrafficItem>
) {
    val backgroundRatioPct: Double
        get() = if (totalBytes > 0L) backgroundTotalBytes * 100.0 / totalBytes else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("totalBytes", totalBytes)
        put("wifiTotalBytes", wifiTotalBytes)
        put("mobileTotalBytes", mobileTotalBytes)
        put("foregroundTotalBytes", foregroundTotalBytes)
        put("backgroundTotalBytes", backgroundTotalBytes)
        put("apps", JSONArray().apply { apps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): TrafficSummary = TrafficSummary(
            totalBytes = o.optLong("totalBytes"),
            wifiTotalBytes = o.optLong("wifiTotalBytes"),
            mobileTotalBytes = o.optLong("mobileTotalBytes"),
            foregroundTotalBytes = o.optLong("foregroundTotalBytes"),
            backgroundTotalBytes = o.optLong("backgroundTotalBytes"),
            apps = o.optJSONArray("apps").toList { AppTrafficItem.fromJson(it) }
        )
    }
}

data class AppTrafficItem(
    val packageName: String,
    val appName: String,
    val wifiFgBytes: Long,
    val wifiBgBytes: Long,
    val mobileFgBytes: Long,
    val mobileBgBytes: Long
) {
    val fgBytes: Long get() = wifiFgBytes + mobileFgBytes
    val bgBytes: Long get() = wifiBgBytes + mobileBgBytes
    val totalBytes: Long get() = fgBytes + bgBytes
    val bgRatioPct: Double get() = if (totalBytes > 0L) bgBytes * 100.0 / totalBytes else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("wifiFgBytes", wifiFgBytes)
        put("wifiBgBytes", wifiBgBytes)
        put("mobileFgBytes", mobileFgBytes)
        put("mobileBgBytes", mobileBgBytes)
    }

    companion object {
        fun fromJson(o: JSONObject): AppTrafficItem = AppTrafficItem(
            packageName = o.optString("packageName"),
            appName = o.optString("appName"),
            wifiFgBytes = o.optLong("wifiFgBytes"),
            wifiBgBytes = o.optLong("wifiBgBytes"),
            mobileFgBytes = o.optLong("mobileFgBytes"),
            mobileBgBytes = o.optLong("mobileBgBytes")
        )
    }
}

data class BatteryHealthInfo(
    val healthPct: Double?,
    val usableHealthPct: Double?,
    val trueHealthPct: Double?,
    val cycleCount: Int?,
    val fullCapacityMah: Int?,
    val designCapacityMah: Int?,
    val recordTime: String?,
    val sourceLabel: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("healthPct", healthPct)
        putOpt("usableHealthPct", usableHealthPct)
        putOpt("trueHealthPct", trueHealthPct)
        putOpt("cycleCount", cycleCount)
        putOpt("fullCapacityMah", fullCapacityMah)
        putOpt("designCapacityMah", designCapacityMah)
        putOpt("recordTime", recordTime)
        put("sourceLabel", sourceLabel)
    }

    companion object {
        fun fromJson(o: JSONObject): BatteryHealthInfo = BatteryHealthInfo(
            healthPct = o.optNullableDouble("healthPct"),
            usableHealthPct = o.optNullableDouble("usableHealthPct"),
            trueHealthPct = o.optNullableDouble("trueHealthPct"),
            cycleCount = o.optNullableInt("cycleCount"),
            fullCapacityMah = o.optNullableInt("fullCapacityMah"),
            designCapacityMah = o.optNullableInt("designCapacityMah"),
            recordTime = o.optString("recordTime").takeIf { it.isNotBlank() && it != "null" },
            sourceLabel = o.optString("sourceLabel", "")
        )
    }
}

data class ChargingSession(
    val startTime: String,
    val endTime: String,
    val durationMs: Long,
    val startSoc: Int,
    val endSoc: Int,
    val chargedPct: Int,
    val peakVoltageMv: Int?,
    val peakVbusMv: Int?,
    val peakIbusMa: Int?,
    val peakPowerW: Double?,
    val adapterTypeLabel: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("startTime", startTime)
        put("endTime", endTime)
        put("durationMs", durationMs)
        put("startSoc", startSoc)
        put("endSoc", endSoc)
        put("chargedPct", chargedPct)
        putOpt("peakVoltageMv", peakVoltageMv)
        putOpt("peakVbusMv", peakVbusMv)
        putOpt("peakIbusMa", peakIbusMa)
        putOpt("peakPowerW", peakPowerW)
        put("adapterTypeLabel", adapterTypeLabel)
    }

    companion object {
        fun fromJson(o: JSONObject): ChargingSession = ChargingSession(
            startTime = o.optString("startTime"),
            endTime = o.optString("endTime"),
            durationMs = o.optLong("durationMs"),
            startSoc = o.optInt("startSoc"),
            endSoc = o.optInt("endSoc"),
            chargedPct = o.optInt("chargedPct"),
            peakVoltageMv = o.optNullableInt("peakVoltageMv"),
            peakVbusMv = o.optNullableInt("peakVbusMv"),
            peakIbusMa = o.optNullableInt("peakIbusMa"),
            peakPowerW = o.optNullableDouble("peakPowerW"),
            adapterTypeLabel = o.optString("adapterTypeLabel", "")
        )
    }
}

data class ChargingDetail(
    val todayTotalChargePct: Int,
    val currentSoc: Int?,
    val currentStatus: String?,        // Charging / Discharging / Full / Not charging
    val currentVoltageV: Double?,
    val currentMa: Double?,
    val currentBatteryTempC: Double?,
    val currentBoardTempC: Double?,
    val protocolName: String?,         // 具体中文协议名（如 vivo FlashCharge 私有闪充 / USB BC1.2 SDP 标准数据口）
    val protocolRawCode: String?,
    val fastChargeActive: Boolean?,
    val adapterPowerW: Double?,
    val adapterPowerMaxW: Double?,
    val deviceMaxPowerW: Double?,
    val cvTrickleActive: Boolean?,
    val usbType: String?,
    val vbusV: Double? = null,
    val ibusMa: Double? = null,
    val inputPowerW: Double? = null,
    val supportedProtocolsSummary: String? = null,
    val chargeVoterNote: String? = null,
    val sessions: List<ChargingSession>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("todayTotalChargePct", todayTotalChargePct)
        putOpt("currentSoc", currentSoc)
        putOpt("currentStatus", currentStatus)
        putOpt("currentVoltageV", currentVoltageV)
        putOpt("currentMa", currentMa)
        putOpt("currentBatteryTempC", currentBatteryTempC)
        putOpt("currentBoardTempC", currentBoardTempC)
        putOpt("protocolName", protocolName)
        putOpt("protocolRawCode", protocolRawCode)
        putOpt("fastChargeActive", fastChargeActive)
        putOpt("adapterPowerW", adapterPowerW)
        putOpt("adapterPowerMaxW", adapterPowerMaxW)
        putOpt("deviceMaxPowerW", deviceMaxPowerW)
        putOpt("cvTrickleActive", cvTrickleActive)
        putOpt("usbType", usbType)
        putOpt("vbusV", vbusV)
        putOpt("ibusMa", ibusMa)
        putOpt("inputPowerW", inputPowerW)
        putOpt("supportedProtocolsSummary", supportedProtocolsSummary)
        putOpt("chargeVoterNote", chargeVoterNote)
        put("sessions", JSONArray().apply { sessions.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ChargingDetail = ChargingDetail(
            todayTotalChargePct = o.optInt("todayTotalChargePct"),
            currentSoc = o.optNullableInt("currentSoc"),
            currentStatus = o.optString("currentStatus").takeIf { it.isNotBlank() && it != "null" },
            currentVoltageV = o.optNullableDouble("currentVoltageV"),
            currentMa = o.optNullableDouble("currentMa"),
            currentBatteryTempC = o.optNullableDouble("currentBatteryTempC"),
            currentBoardTempC = o.optNullableDouble("currentBoardTempC"),
            protocolName = o.optString("protocolName").takeIf { it.isNotBlank() && it != "null" },
            protocolRawCode = o.optString("protocolRawCode").takeIf { it.isNotBlank() && it != "null" },
            fastChargeActive = if (o.has("fastChargeActive") && !o.isNull("fastChargeActive")) o.optBoolean("fastChargeActive") else null,
            adapterPowerW = o.optNullableDouble("adapterPowerW"),
            adapterPowerMaxW = o.optNullableDouble("adapterPowerMaxW"),
            deviceMaxPowerW = o.optNullableDouble("deviceMaxPowerW"),
            cvTrickleActive = if (o.has("cvTrickleActive") && !o.isNull("cvTrickleActive")) o.optBoolean("cvTrickleActive") else null,
            usbType = o.optString("usbType").takeIf { it.isNotBlank() && it != "null" },
            vbusV = o.optNullableDouble("vbusV"),
            ibusMa = o.optNullableDouble("ibusMa"),
            inputPowerW = o.optNullableDouble("inputPowerW"),
            supportedProtocolsSummary = o.optString("supportedProtocolsSummary").takeIf { it.isNotBlank() && it != "null" },
            chargeVoterNote = o.optString("chargeVoterNote").takeIf { it.isNotBlank() && it != "null" },
            sessions = o.optJSONArray("sessions").toList { ChargingSession.fromJson(it) }
        )
    }
}

data class TempRangeStats(
    val minC: Double?,
    val maxC: Double?,
    val avgC: Double?,
    val count: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        putOpt("minC", minC)
        putOpt("maxC", maxC)
        putOpt("avgC", avgC)
        put("count", count)
    }

    companion object {
        fun fromJson(o: JSONObject): TempRangeStats = TempRangeStats(
            minC = o.optNullableDouble("minC"),
            maxC = o.optNullableDouble("maxC"),
            avgC = o.optNullableDouble("avgC"),
            count = o.optInt("count")
        )
    }
}

data class TemperatureSample(
    val timestampMs: Long,
    val timeLabel: String,             // HH:mm:ss
    val fullTimeLabel: String,         // yyyy-MM-dd HH:mm:ss
    val batteryTempC: Double?,
    val shellTempC: Double?,
    val boardTempC: Double?,
    val apTempC: Double?,
    val isCharging: Boolean,
    val soc: Int?,
    val foregroundPackage: String?,
    val foregroundAppName: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("timeLabel", timeLabel)
        put("fullTimeLabel", fullTimeLabel)
        putOpt("batteryTempC", batteryTempC)
        putOpt("shellTempC", shellTempC)
        putOpt("boardTempC", boardTempC)
        putOpt("apTempC", apTempC)
        put("isCharging", isCharging)
        putOpt("soc", soc)
        putOpt("foregroundPackage", foregroundPackage)
        put("foregroundAppName", foregroundAppName)
    }

    companion object {
        fun fromJson(o: JSONObject): TemperatureSample = TemperatureSample(
            timestampMs = o.optLong("timestampMs"),
            timeLabel = o.optString("timeLabel"),
            fullTimeLabel = o.optString("fullTimeLabel"),
            batteryTempC = o.optNullableDouble("batteryTempC"),
            shellTempC = o.optNullableDouble("shellTempC"),
            boardTempC = o.optNullableDouble("boardTempC"),
            apTempC = o.optNullableDouble("apTempC"),
            isCharging = o.optBoolean("isCharging"),
            soc = o.optNullableInt("soc"),
            foregroundPackage = o.optString("foregroundPackage").takeIf { it.isNotBlank() && it != "null" },
            foregroundAppName = o.optString("foregroundAppName", "锁屏/桌面")
        )
    }
}

data class TemperatureSummary(
    val chargeBatteryRange: TempRangeStats,
    val dischargeBatteryRange: TempRangeStats,
    val chargeShellRange: TempRangeStats,
    val dischargeShellRange: TempRangeStats,
    val chargeBoardRange: TempRangeStats,
    val dischargeBoardRange: TempRangeStats,
    val samples: List<TemperatureSample>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("chargeBatteryRange", chargeBatteryRange.toJson())
        put("dischargeBatteryRange", dischargeBatteryRange.toJson())
        put("chargeShellRange", chargeShellRange.toJson())
        put("dischargeShellRange", dischargeShellRange.toJson())
        put("chargeBoardRange", chargeBoardRange.toJson())
        put("dischargeBoardRange", dischargeBoardRange.toJson())
        put("samples", JSONArray().apply { samples.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): TemperatureSummary = TemperatureSummary(
            chargeBatteryRange = TempRangeStats.fromJson(o.optJSONObject("chargeBatteryRange") ?: JSONObject()),
            dischargeBatteryRange = TempRangeStats.fromJson(o.optJSONObject("dischargeBatteryRange") ?: JSONObject()),
            chargeShellRange = TempRangeStats.fromJson(o.optJSONObject("chargeShellRange") ?: JSONObject()),
            dischargeShellRange = TempRangeStats.fromJson(o.optJSONObject("dischargeShellRange") ?: JSONObject()),
            chargeBoardRange = TempRangeStats.fromJson(o.optJSONObject("chargeBoardRange") ?: JSONObject()),
            dischargeBoardRange = TempRangeStats.fromJson(o.optJSONObject("dischargeBoardRange") ?: JSONObject()),
            samples = o.optJSONArray("samples").toList { TemperatureSample.fromJson(it) }
        )
    }
}

data class NetWakeupApp(
    val packageName: String,
    val appName: String,
    val count: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appName", appName)
        put("count", count)
    }

    companion object {
        fun fromJson(o: JSONObject): NetWakeupApp = NetWakeupApp(
            packageName = o.optString("packageName"),
            appName = o.optString("appName"),
            count = o.optInt("count")
        )
    }
}

data class StandbySummary(
    val longStandbyCount: Int,
    val longStandbyDurationMs: Long,
    val shortStandbyCount: Int,
    val shortStandbyDurationMs: Long,
    val totalWakeupCount: Int,
    val topNetWakeups: List<NetWakeupApp>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("longStandbyCount", longStandbyCount)
        put("longStandbyDurationMs", longStandbyDurationMs)
        put("shortStandbyCount", shortStandbyCount)
        put("shortStandbyDurationMs", shortStandbyDurationMs)
        put("totalWakeupCount", totalWakeupCount)
        put("topNetWakeups", JSONArray().apply { topNetWakeups.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): StandbySummary = StandbySummary(
            longStandbyCount = o.optInt("longStandbyCount"),
            longStandbyDurationMs = o.optLong("longStandbyDurationMs"),
            shortStandbyCount = o.optInt("shortStandbyCount"),
            shortStandbyDurationMs = o.optLong("shortStandbyDurationMs"),
            totalWakeupCount = o.optInt("totalWakeupCount"),
            topNetWakeups = o.optJSONArray("topNetWakeups").toList { NetWakeupApp.fromJson(it) }
        )
    }
}

data class SnapshotMeta(
    val id: Long,
    val capturedAtMs: Long,
    val dateStr: String,
    val capturedTimeLabel: String,
    val note: String,
    val totalDrainPct: Int,
    val totalChargePct: Int,
    val drainRatePctPerHour: Double,
    val screenOnDurationMs: Long,
    val currentSoc: Int?,
    val healthPct: Double?,
    val cycleCount: Int?,
    val maxChargeTempC: Double?,
    val maxDischargeTempC: Double?,
    val totalTrafficBytes: Long
)

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key, Double.NaN)
    return if (v.isNaN()) null else v
}

private fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

private inline fun <T> JSONArray?.toList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out.add(transform(it)) }
    }
    return out
}
