package dev.ganvivo.powerinsight.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CollectionBundle(
    val snapshot: PowerSnapshot,
    val historicalDailyTrends: List<DailyTrendRecord>,
    val warnings: List<String>
)

// 通过 Root 只读采集 OriginOS 7 底层功耗/充电/温度全量数据：
// 1. /data/system/pie/YYYYMMDD-PIE-*.db（via /system/bin/sqlite3 -readonly -json）
// 2. /data/user/0/com.vivo.pie/cache_databases/YYYY-MM-DD/overviewBatteryLife.bin（内存解码）
// 3. /sys/class/power_supply/battery、/sys/class/fuelsummary、/sys/class/cms_class
class PiePowerCollector(private val context: Context) {

    private data class FgSpan(val pkg: String, val startMs: Long, val endMs: Long)

    private data class StateRow(
        val timeMs: Long,
        val formattedTime: String,
        val level: Int,
        val levelUi: Int,
        val voltageMv: Int,
        val tempC: Int,
        val charge: Int,
        val screen: Int,
        val currentMa: Int,
        val adapterType: Int,
        val chargeFullMah: Int
    )

    private val pm: PackageManager = context.packageManager
    private val appNameCache = HashMap<String, String>()

    suspend fun collectToday(note: String = ""): Result<CollectionBundle> {
        val nowMs = System.currentTimeMillis()
        val nowDt = LocalDateTime.now()
        val todayYmd = nowDt.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val todayIso = nowDt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val capturedLabel = nowDt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val script = buildReadOnlyBatchScript(todayYmd)
        val exec = RootShell.runScript(script, timeoutSec = 30L)
        if (!exec.isSuccess && exec.stdout.isBlank()) {
            val detail = exec.stderr.ifBlank { "未获得 Root 权限或 su 调用失败（exit=${exec.exitCode}）" }
            return Result.failure(IllegalStateException(detail))
        }

        val sections = parseSections(exec.stdout)
        val warnings = mutableListOf<String>()

        val meta = parseKvLines(sections["META"].orEmpty())
        val deviceModel = meta["marketname"].orEmpty()
            .ifBlank { meta["hostname"].orEmpty().replace('-', ' ') }
            .ifBlank { meta["model"].orEmpty() }
            .ifBlank { android.os.Build.MODEL }
        val romVersion = meta["rom"].orEmpty().ifBlank { android.os.Build.DISPLAY }
        val dbPath = meta["db_path"].orEmpty()
        if (dbPath.isBlank()) {
            warnings += "未在 /data/system/pie/ 找到今日 PIE 数据库，仅显示实时 sysfs 数据"
        }

        val sysfs = parseKvLines(sections["SYSFS"].orEmpty())
        val fuelItems = parseFuelSummaryPairs(
            sections["FUEL_ITEM"].orEmpty(),
            sections["FUEL_DATA"].orEmpty(),
            sections["FUEL_ITEM_EX"].orEmpty(),
            sections["FUEL_DATA_EX"].orEmpty()
        )

        val gasGaugeRows = parseJsonArray(sections["PIE_GAS_GAUGE"])
        val battStateRows = parseJsonArray(sections["PIE_BATTERY_STATE"])
        val thermalTempRows = parseJsonArray(sections["PIE_THERMAL_TEMP"])
        val thermalChargeRows = parseJsonArray(sections["PIE_THERMAL_CHARGE"])
        val foregroundRows = parseJsonArray(sections["PIE_FOREGROUND"])
        val brightnessRows = parseJsonArray(sections["PIE_BRIGHTNESS"])
        val wifiTrafficRows = parseJsonArray(sections["PIE_WIFI_TRAFFIC"])
        val modemTrafficRows = parseJsonArray(sections["PIE_MODEM_TRAFFIC"])
        val dailyHealthRows = parseJsonArray(sections["PIE_DAILY_HEALTH"])
        val standbyRows = parseJsonArray(sections["PIE_STANDBY"])
        val netWakeupRows = parseJsonArray(sections["PIE_NET_WAKEUP"])
        val wakeupTotalRows = parseJsonArray(sections["PIE_WAKEUP_TOTAL"])

        // 0. 解析时间覆盖范围
        var dataRangeStart: String? = null
        var dataRangeEnd: String? = null
        if (battStateRows.length() > 0) {
            dataRangeStart = battStateRows.optJSONObject(0)?.optString("formatted_start_time")
                ?.takeIf { it.isNotBlank() }
            dataRangeEnd = battStateRows.optJSONObject(battStateRows.length() - 1)
                ?.optString("formatted_start_time")?.takeIf { it.isNotBlank() }
        } else if (gasGaugeRows.length() > 0) {
            dataRangeStart = gasGaugeRows.optJSONObject(0)?.optString("formatted_start_time")
                ?.takeIf { it.isNotBlank() }
            dataRangeEnd = gasGaugeRows.optJSONObject(gasGaugeRows.length() - 1)
                ?.optString("formatted_end_time")?.takeIf { it.isNotBlank() }
        }

        // 1. 汇总亮度分钟桶（用于计算全天平均亮度 & 各应用平均亮度）
        val brightnessMinuteBuckets = HashMap<Long, Pair<Long, Long>>() // minuteIdx -> (weightedNitSec, totalSec)
        var totalBrightWeighted = 0.0
        var totalBrightDurationMs = 0L
        for (i in 0 until brightnessRows.length()) {
            val row = brightnessRows.optJSONObject(i) ?: continue
            val bri = row.optInt("brightness", -1)
            val dur = row.optLong("duration", 0L)
            val st = row.optLong("start_time", 0L)
            val et = row.optLong("end_time", 0L)
            if (bri in 1..3000 && dur in 1L..86_400_000L) {
                totalBrightWeighted += bri.toDouble() * dur
                totalBrightDurationMs += dur
            }
            if (bri in 1..3000 && et > st && et - st <= 86_400_000L) {
                var m = st / 60_000L
                val endM = et / 60_000L
                while (m <= endM) {
                    val mStart = m * 60_000L
                    val overlapSec = (min(et, mStart + 60_000L) - max(st, mStart)) / 1000L
                    if (overlapSec > 0L) {
                        val prev = brightnessMinuteBuckets[m]
                        brightnessMinuteBuckets[m] = if (prev != null) {
                            (prev.first + bri.toLong() * overlapSec) to (prev.second + overlapSec)
                        } else {
                            (bri.toLong() * overlapSec) to overlapSec
                        }
                    }
                    m++
                }
            }
        }
        val avgBrightnessNit = if (totalBrightDurationMs > 0L) {
            totalBrightWeighted / totalBrightDurationMs
        } else null

        // 2. 构建前台应用时间段（同时用于计算应用平均亮度 & 关联温度采样的前台应用）
        val fgSpans = ArrayList<FgSpan>(foregroundRows.length())
        val fgSpansByPkg = HashMap<String, MutableList<FgSpan>>()
        for (i in 0 until foregroundRows.length()) {
            val row = foregroundRows.optJSONObject(i) ?: continue
            val pkg = row.optString("app", "")
            val st = row.optLong("start_time", 0L)
            val et = row.optLong("end_time", 0L)
            if (pkg.isNotBlank() && pkg != "screen_off" && et > st) {
                val span = FgSpan(pkg, st, et)
                fgSpans.add(span)
                fgSpansByPkg.getOrPut(pkg) { mutableListOf() }.add(span)
            }
        }
        fgSpans.sortBy { it.startMs }

        // 3. 解析 ① 应用耗电明细 + ② 按小时分布 + 总体续航概览
        var totalDrainPct = 0
        var totalChargePct = 0
        var drainDurationMs = 0L
        var screenOnDurationMs = 0L
        var screenOnDrainPct = 0
        var screenOffDurationMs = 0L
        var screenOffDrainPct = 0
        var totalGasGaugeMah = 0.0

        class AppAcc(val pkg: String) {
            var fgMs = 0L
            var gasGaugeMah = 0.0
            var drainPct = 0
            var count = 0
        }
        val appAccMap = LinkedHashMap<String, AppAcc>()

        class HourAcc(val hour: Int) {
            var chargePct = 0
            var dischargePct = 0
            var screenOnMs = 0L
            var screenOffMs = 0L
            var chargeDurationMs = 0L
            var hasRecords = false
        }
        val hourAccs = Array(24) { HourAcc(it) }

        for (i in 0 until gasGaugeRows.length()) {
            val row = gasGaugeRows.optJSONObject(i) ?: continue
            val dur = row.optLong("duration", 0L)
            if (dur <= 0L || dur > 86_400_000L) continue
            val charge = row.optInt("charge", 0)
            val screen = row.optInt("screen", 0)
            val levelUi = row.optInt("level_ui", 0)
            val gasGauge = row.optDouble("gas_gauge", 0.0)
            val app = row.optString("app", "")
            val startStr = row.optString("formatted_start_time", "")

            val hourIdx = parseHourFromFormatted(startStr)
            if (hourIdx in 0..23) {
                val ha = hourAccs[hourIdx]
                ha.hasRecords = true
                if (screen == 1) ha.screenOnMs += dur else ha.screenOffMs += dur
                if (charge == 1) {
                    ha.chargeDurationMs += dur
                    if (levelUi < 0) ha.chargePct += abs(levelUi)
                } else {
                    if (levelUi > 0) ha.dischargePct += levelUi
                }
            }

            if (charge == 0) {
                drainDurationMs += dur
                if (levelUi > 0) totalDrainPct += levelUi
                if (gasGauge > 0.0) totalGasGaugeMah += gasGauge
                if (screen == 1) {
                    screenOnDurationMs += dur
                    if (levelUi > 0) screenOnDrainPct += levelUi
                    if (app.isNotBlank() && app != "screen_off") {
                        val acc = appAccMap.getOrPut(app) { AppAcc(app) }
                        acc.fgMs += dur
                        if (gasGauge > 0.0) acc.gasGaugeMah += gasGauge
                        if (levelUi > 0) acc.drainPct += levelUi
                        acc.count++
                    }
                } else {
                    screenOffDurationMs += dur
                    if (levelUi > 0) screenOffDrainPct += levelUi
                }
            } else {
                if (levelUi < 0) totalChargePct += abs(levelUi)
            }
        }

        // 若某小时 raw_battery_state 有充电但 gas_gauge 记为 0，做一次 state 兜底校验
        val states = ArrayList<StateRow>(battStateRows.length())
        for (i in 0 until battStateRows.length()) {
            val r = battStateRows.optJSONObject(i) ?: continue
            states.add(
                StateRow(
                    timeMs = r.optLong("start_time", 0L),
                    formattedTime = r.optString("formatted_start_time", ""),
                    level = r.optInt("level", 0),
                    levelUi = r.optInt("level_ui", 0),
                    voltageMv = r.optInt("voltage", 0),
                    tempC = r.optInt("temperature", -999),
                    charge = r.optInt("charge", 0),
                    screen = r.optInt("screen", 0),
                    currentMa = r.optInt("current", 0),
                    adapterType = r.optInt("adapter_type", 0),
                    chargeFullMah = r.optInt("charge_full", 0)
                )
            )
        }
        // 检查每小时是否有 battery_state 充电但 gas_gauge 无记录的情况
        for (i in 1 until states.size) {
            val prev = states[i - 1]
            val cur = states[i]
            val h = parseHourFromFormatted(cur.formattedTime)
            if (h in 0..23 && !hourAccs[h].hasRecords) {
                hourAccs[h].hasRecords = true
                if (cur.charge == 1 && cur.levelUi > prev.levelUi) {
                    hourAccs[h].chargePct += (cur.levelUi - prev.levelUi)
                } else if (cur.charge == 0 && prev.levelUi > cur.levelUi) {
                    hourAccs[h].dischargePct += (prev.levelUi - cur.levelUi)
                }
            }
        }

        // 计算所有应用的平均亮度（与 ScreenOnAppPowerSkillImpl 同算法）
        val appUsages = appAccMap.values
            .filter { it.fgMs > 0L }
            .map { acc ->
                val hours = acc.fgMs / 3_600_000.0
                val avgMa = if (hours > 0.0) acc.gasGaugeMah / hours else 0.0
                val rate = if (hours > 0.0) acc.drainPct / hours else 0.0
                val appBright = computeAppAvgBrightness(fgSpansByPkg[acc.pkg], brightnessMinuteBuckets)
                AppPowerUsage(
                    packageName = acc.pkg,
                    appName = resolveAppName(acc.pkg),
                    foregroundDurationMs = acc.fgMs,
                    drainPct = acc.drainPct,
                    drainRatePctPerHour = rate,
                    avgCurrentMa = avgMa,
                    avgBrightnessNit = appBright,
                    gasGaugeMah = acc.gasGaugeMah,
                    recordCount = acc.count
                )
            }
            .sortedWith(compareByDescending<AppPowerUsage> { it.drainPct }.thenByDescending { it.foregroundDurationMs })

        val currentHour = nowDt.hour
        val hourlyItems = hourAccs
            .filter { it.hour <= currentHour || it.hasRecords }
            .map { ha ->
                val endLabel = if (ha.hour == currentHour && dataRangeEnd != null && dataRangeEnd.length >= 16) {
                    dataRangeEnd.substring(11, 16)
                } else {
                    String.format("%02d:00", (ha.hour + 1) % 24)
                }
                val label = String.format("%02d:00–%s", ha.hour, endLabel)
                val status = inferHourStatus(ha.chargePct, ha.chargeDurationMs, ha.dischargePct, ha.screenOnMs, ha.screenOffMs, ha.hasRecords)
                HourlyPowerItem(
                    hour = ha.hour,
                    label = label,
                    chargePct = ha.chargePct,
                    dischargePct = ha.dischargePct,
                    screenOnMs = ha.screenOnMs,
                    screenOffMs = ha.screenOffMs,
                    chargeDurationMs = ha.chargeDurationMs,
                    statusText = status,
                    hasRecords = ha.hasRecords
                )
            }

        val drainHours = drainDurationMs / 3_600_000.0
        val screenOnHours = screenOnDurationMs / 3_600_000.0
        val screenOffHours = screenOffDurationMs / 3_600_000.0
        val overview = DailyOverview(
            totalDrainPct = totalDrainPct,
            totalChargePct = totalChargePct,
            drainDurationMs = drainDurationMs,
            drainRatePctPerHour = if (drainHours > 0.0) totalDrainPct / drainHours else 0.0,
            screenOnDurationMs = screenOnDurationMs,
            screenOnDrainPct = screenOnDrainPct,
            screenOnDrainRatePctPerHour = if (screenOnHours > 0.0) screenOnDrainPct / screenOnHours else 0.0,
            screenOffDurationMs = screenOffDurationMs,
            screenOffDrainPct = screenOffDrainPct,
            screenOffDrainRatePctPerHour = if (screenOffHours > 0.0) screenOffDrainPct / screenOffHours else 0.0,
            avgBrightnessNit = avgBrightnessNit,
            totalGasGaugeMah = totalGasGaugeMah
        )

        // 4. 解析 ④ 网络流量消耗
        class TrafficAcc(val pkg: String) {
            var wifiFg = 0L
            var wifiBg = 0L
            var modFg = 0L
            var modBg = 0L
        }
        val trafficMap = LinkedHashMap<String, TrafficAcc>()
        for (i in 0 until wifiTrafficRows.length()) {
            val r = wifiTrafficRows.optJSONObject(i) ?: continue
            val pkg = r.optString("name", "")
            if (pkg.isBlank()) continue
            val acc = trafficMap.getOrPut(pkg) { TrafficAcc(pkg) }
            acc.wifiFg += max(0L, r.optLong("fg_bytes", 0L))
            acc.wifiBg += max(0L, r.optLong("bg_bytes", 0L))
        }
        for (i in 0 until modemTrafficRows.length()) {
            val r = modemTrafficRows.optJSONObject(i) ?: continue
            val pkg = r.optString("name", "")
            if (pkg.isBlank()) continue
            val acc = trafficMap.getOrPut(pkg) { TrafficAcc(pkg) }
            acc.modFg += max(0L, r.optLong("fg_bytes", 0L))
            acc.modBg += max(0L, r.optLong("bg_bytes", 0L))
        }
        val trafficApps = trafficMap.values
            .map {
                AppTrafficItem(
                    packageName = it.pkg,
                    appName = resolveAppName(it.pkg),
                    wifiFgBytes = it.wifiFg,
                    wifiBgBytes = it.wifiBg,
                    mobileFgBytes = it.modFg,
                    mobileBgBytes = it.modBg
                )
            }
            .filter { it.totalBytes > 0L }
            .sortedByDescending { it.totalBytes }

        val wifiTotal = trafficApps.sumOf { it.wifiFgBytes + it.wifiBgBytes }
        val modTotal = trafficApps.sumOf { it.mobileFgBytes + it.mobileBgBytes }
        val fgTotal = trafficApps.sumOf { it.fgBytes }
        val bgTotal = trafficApps.sumOf { it.bgBytes }
        val trafficSummary = TrafficSummary(
            totalBytes = wifiTotal + modTotal,
            wifiTotalBytes = wifiTotal,
            mobileTotalBytes = modTotal,
            foregroundTotalBytes = fgTotal,
            backgroundTotalBytes = bgTotal,
            apps = trafficApps
        )

        // 5. 解析 ⑤ 电池健康度 & 循环次数（PIE daily_battery_info + 实时 sysfs + fuelsummary 对齐项三保险）
        val pieHealthObj = dailyHealthRows.optJSONObject(0)
        val sysSoh = sysfs["fuel_soh"]?.toDoubleOrNull()?.takeIf { it in 1.0..100.0 }
        val sysSohHealth = sysfs["fuel_soh_health"]?.toDoubleOrNull()?.takeIf { it in 1.0..100.0 }
        val fuelUsableSoh = (fuelItems["ID_INT__USABLE_SOH"] ?: sysfs["cms_usable_soh"])?.toIntOrNull()
            ?.takeIf { it in 10..1000 }?.let { it / 10.0 }
        val fuelTrueSoh = fuelItems["ID_INT__2P_TRUE_SOH"]?.toIntOrNull()
            ?.takeIf { it in 10..1000 }?.let { it / 10.0 }
        val sysCycle = (sysfs["fuel_cycle"] ?: sysfs["bat_cycle"] ?: fuelItems["ID_INT__CYCLE"])
            ?.toIntOrNull()?.takeIf { it >= 0 }
        val sysFullMah = sysfs["fuel_cap_mah"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: states.lastOrNull()?.chargeFullMah?.takeIf { it > 0 }

        val pieSoh = pieHealthObj?.optDouble("battery_health_level", -1.0)?.takeIf { it in 1.0..100.0 }
        val pieUsableSoh = pieHealthObj?.optDouble("usable_battery_health_level", -1.0)?.takeIf { it in 1.0..100.0 }
        val pieTrueSoh = pieHealthObj?.optDouble("true_battery_health_level", -1.0)?.takeIf { it in 1.0..100.0 }
        val pieCycle = pieHealthObj?.optInt("battery_cycle_count", -1)?.takeIf { it >= 0 }
        val pieCapMah = pieHealthObj?.optInt("capacity_mah", -1)?.takeIf { it > 0 }
        val pieFullMah = pieHealthObj?.optInt("battery_full_capacity", -1)?.takeIf { it > 0 }
        val pieHealthTime = pieHealthObj?.optString("formatted_start_time")?.takeIf { it.isNotBlank() }

        val batteryHealth = BatteryHealthInfo(
            healthPct = sysSoh ?: pieSoh ?: sysSohHealth,
            usableHealthPct = fuelUsableSoh ?: pieUsableSoh ?: sysSohHealth,
            trueHealthPct = fuelTrueSoh ?: pieTrueSoh ?: sysSoh,
            cycleCount = sysCycle ?: pieCycle,
            fullCapacityMah = sysFullMah ?: pieFullMah,
            designCapacityMah = pieCapMah,
            recordTime = pieHealthTime ?: capturedLabel,
            sourceLabel = when {
                sysSoh != null && pieHealthObj != null -> "内核 fuelsummary + PIE 每日健康表"
                sysSoh != null -> "内核 /sys/class/fuelsummary 实时节点"
                pieHealthObj != null -> "PIE daily_battery_info"
                else -> "未读取到"
            }
        )

        // 6. 解析 ⑥ 详细充电数据（含充电时段 + 具体协议名 + VBUS/IBUS + 快充 + 实时电压/电流）
        val sessions = buildChargingSessions(states, thermalChargeRows)
        val rawVoltage = sysfs["bat_voltage_now"]?.toLongOrNull()
        val liveVoltageV = when {
            rawVoltage != null && rawVoltage > 100_000L -> rawVoltage / 1_000_000.0
            rawVoltage != null && rawVoltage > 0L -> rawVoltage / 1_000.0
            else -> states.lastOrNull()?.voltageMv?.takeIf { it > 0 }?.let { it / 1_000.0 }
        }
        val rawCurrent = sysfs["bat_current_now"]?.toLongOrNull()
        val liveCurrentMa = when {
            rawCurrent != null && abs(rawCurrent) > 50_000L -> rawCurrent / 1_000.0
            rawCurrent != null -> rawCurrent.toDouble()
            else -> states.lastOrNull()?.currentMa?.toDouble()
        }
        val liveSoc = sysfs["bat_capacity"]?.toIntOrNull() ?: states.lastOrNull()?.levelUi
        val liveStatus = translateBatteryStatus(sysfs["bat_status"])
        val liveBatTempC = sysfs["bat_temp"]?.toIntOrNull()?.let { it / 10.0 }
            ?: states.lastOrNull()?.tempC?.takeIf { it in -40..100 }?.toDouble()
        val liveBoardTempC = (sysfs["bat_board_temp"] ?: sysfs["cms_board_temp"])?.toIntOrNull()
            ?.let { if (abs(it) > 120) it / 10.0 else it.toDouble() }
            ?: sysfs["fuel_board_temp"]?.toDoubleOrNull()

        val vbusV = sysfs["cms_chg_voltage"]?.toDoubleOrNull()?.takeIf { it > 500.0 }?.let { it / 1000.0 }
        val ibusMa = sysfs["cms_ibus"]?.toDoubleOrNull()?.takeIf { it >= 0.0 }
        val inputPowerW = if (vbusV != null && ibusMa != null && ibusMa > 0.0) {
            (vbusV * ibusMa) / 1000.0
        } else null

        val vfcpMaxW = fuelItems["ID_INT__VFCP_POWER_MAX"]?.toIntOrNull()
            ?.takeIf { it > 0 }?.let { mapVfcpToActualPower(it) }
        val ppsMaxW = fuelItems["ID_INT__PPS_POWER_MAX"]?.toIntOrNull()?.takeIf { it > 0 }
        val ufcsMaxW = fuelItems["ID_INT__UFCS_POWER_MAX"]?.toIntOrNull()?.takeIf { it > 0 }
        val supportedProtocolsSummary = listOfNotNull(
            vfcpMaxW?.let { "vivo VFCP 闪充 ${it}W" },
            ppsMaxW?.let { "USB PD PPS ${it}W" },
            ufcsMaxW?.let { "UFCS 融合快充 ${it}W" }
        ).joinToString(" · ").ifBlank { null }

        val rawProtoCode = fuelItems["ID_BYTE__VFCS_PROTOCOL"]
        val effectiveProtoCode = resolveEffectiveProtocolCode(rawProtoCode, fuelItems)
        val bc12Type = fuelItems["ID_BYTE__BC1P2_TYPE"]
        val pd9v = fuelItems["ID_BIT__PD_9V"] == "1"
        val termBit = fuelItems["ID_BIT__TERM"] == "1"
        val fchgBit = fuelItems["ID_BIT__FCHG"]?.toIntOrNull()
        val fchgEn = fuelItems["ID_BYTE__FCHG_EN"]?.toIntOrNull()
        val adptPowerFuel = fuelItems["ID_BYTE__ADPT_POWER"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val adapterPower = sysfs["cms_adapter_power"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: adptPowerFuel
        val adapterPowerMax = sysfs["cms_adapter_power_max"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val deviceMaxPower = vfcpMaxW?.toDouble()
        val cvIbusStart = fuelItems["ID_INT__CV_IBUS_START"]?.toIntOrNull()
        val isPluggedOrCharging = sysfs["bat_status"]?.equals("Charging", true) == true ||
            sysfs["bat_status"]?.equals("Full", true) == true
        val protoName = buildDetailedProtocolName(
            effectiveCode = effectiveProtoCode,
            rawCode = rawProtoCode,
            bc12Type = bc12Type,
            pd9v = pd9v,
            statusRaw = sysfs["bat_status"],
            termBit = termBit,
            adapterPowerW = adapterPower,
            inputPowerW = inputPowerW,
            vbusV = vbusV,
            vfcpMaxW = vfcpMaxW,
            ufcsMaxW = ufcsMaxW,
            ppsMaxW = ppsMaxW
        )
        val fastChargeActive: Boolean? = if (isPluggedOrCharging) {
            (fchgBit == 1) || (fchgEn == 1) || (effectiveProtoCode in setOf("1", "2", "3", "4", "5", "8")) || ((adapterPower ?: 0.0) >= 18.0)
        } else if (rawProtoCode != null) {
            false
        } else null

        val voterNote = buildChargeVoterNote(
            dischgVoter = fuelItems["ID_BUFF__DISCHG_VOTER"],
            fchgFccVoter = fuelItems["ID_BUFF__FCHG_FCC_VOTER"],
            disfchgVoter = fuelItems["ID_BUFF__DISFCHG_VOTER"]
        )

        val chargingDetail = ChargingDetail(
            todayTotalChargePct = max(totalChargePct, sessions.sumOf { it.chargedPct }),
            currentSoc = liveSoc,
            currentStatus = liveStatus,
            currentVoltageV = liveVoltageV,
            currentMa = liveCurrentMa,
            currentBatteryTempC = liveBatTempC,
            currentBoardTempC = liveBoardTempC,
            protocolName = protoName,
            protocolRawCode = effectiveProtoCode ?: rawProtoCode,
            fastChargeActive = fastChargeActive,
            adapterPowerW = adapterPower,
            adapterPowerMaxW = adapterPowerMax,
            deviceMaxPowerW = deviceMaxPower,
            cvTrickleActive = cvIbusStart?.let { it > 0 },
            usbType = mapUsbChgType(bc12Type, sysfs["bat_chg_type"] ?: sysfs["cms_chg_type"] ?: sysfs["fuel_chg_type"]),
            vbusV = vbusV,
            ibusMa = ibusMa,
            inputPowerW = inputPowerW,
            supportedProtocolsSummary = supportedProtocolsSummary,
            chargeVoterNote = voterNote,
            sessions = sessions
        )

        // 7. 解析 ⑦ 温度采样全表（对齐充放电状态 + 前台应用 + 区分充电/放电区间）
        val temperatureSummary = buildTemperatureSummary(thermalTempRows, states, fgSpans)

        // 8. 解析待机与唤醒摘要
        val standbySummary = buildStandbySummary(standbyRows, netWakeupRows, wakeupTotalRows)

        // 9. 解析历史 overviewBatteryLife.bin（提供近 7~9 天开箱即用趋势）
        val binTrends = parseHistoricalBins(sections["PIE_HIST_BINS"].orEmpty())

        val snapshot = PowerSnapshot(
            capturedAtMs = nowMs,
            dateStr = todayIso,
            capturedTimeLabel = capturedLabel,
            deviceModel = deviceModel,
            romVersion = romVersion,
            dataRangeStart = dataRangeStart,
            dataRangeEnd = dataRangeEnd,
            note = note,
            overview = overview,
            appUsages = appUsages,
            hourlyItems = hourlyItems,
            trafficSummary = trafficSummary,
            batteryHealth = batteryHealth,
            chargingDetail = chargingDetail,
            temperatureSummary = temperatureSummary,
            standbySummary = standbySummary
        )

        return Result.success(
            CollectionBundle(
                snapshot = snapshot,
                historicalDailyTrends = binTrends,
                warnings = warnings
            )
        )
    }

    private fun buildReadOnlyBatchScript(todayYmd: String): String = """
        echo "<<<SECTION:META>>>"
        echo "marketname=${'$'}(getprop ro.product.marketname)"
        echo "hostname=${'$'}(getprop net.hostname)"
        echo "model=${'$'}(getprop ro.product.model)"
        echo "rom=${'$'}(getprop ro.vivo.os.build.display.id)"
        DB=${'$'}(ls -1 /data/system/pie/${todayYmd}-PIE-*.db 2>/dev/null | head -n 1)
        if [ -z "${'$'}DB" ]; then
          DB=${'$'}(ls -1t /data/system/pie/*-PIE-*.db 2>/dev/null | head -n 1)
        fi
        echo "db_path=${'$'}DB"

        echo "<<<SECTION:SYSFS>>>"
        read_node() {
          if [ -r "${'$'}2" ]; then
            val=${'$'}(head -n 1 "${'$'}2" 2>/dev/null | tr -d '\r\n')
            echo "${'$'}1=${'$'}val"
          fi
        }
        read_node bat_capacity /sys/class/power_supply/battery/capacity
        read_node bat_status /sys/class/power_supply/battery/status
        read_node bat_voltage_now /sys/class/power_supply/battery/voltage_now
        read_node bat_current_now /sys/class/power_supply/battery/current_now
        read_node bat_temp /sys/class/power_supply/battery/temp
        read_node bat_cycle /sys/class/power_supply/battery/cycle_count
        read_node bat_chg_type /sys/class/power_supply/battery/chg_type
        read_node bat_board_temp /sys/class/power_supply/battery/primary_board_temp
        read_node cms_chg_type /sys/class/cms_class/chg_type
        read_node cms_chg_voltage /sys/class/cms_class/chg_voltage
        read_node cms_ibus /sys/class/cms_class/ibus
        read_node cms_usable_soh /sys/class/cms_class/usable_soh
        read_node cms_adapter_power /sys/class/cms_class/adapter_power
        read_node cms_adapter_power_max /sys/class/cms_class/adapter_power_max
        read_node cms_board_temp /sys/class/cms_class/primary_board_temp
        read_node fuel_soh /sys/class/fuelsummary/soh
        read_node fuel_soh_health /sys/class/fuelsummary/soh_from_battery_health
        read_node fuel_cycle /sys/class/fuelsummary/cycle
        read_node fuel_cap_mah /sys/class/fuelsummary/capacity_mah_from_battery_health
        read_node fuel_board_temp /sys/class/fuelsummary/board_temp
        read_node fuel_chg_type /sys/class/fuelsummary/charge_type

        echo "<<<SECTION:FUEL_ITEM>>>"
        cat /sys/class/fuelsummary/item 2>/dev/null
        echo ""
        echo "<<<SECTION:FUEL_DATA>>>"
        cat /sys/class/fuelsummary/data 2>/dev/null
        echo ""
        echo "<<<SECTION:FUEL_ITEM_EX>>>"
        cat /sys/class/fuelsummary/item_ex 2>/dev/null
        echo ""
        echo "<<<SECTION:FUEL_DATA_EX>>>"
        cat /sys/class/fuelsummary/data_ex 2>/dev/null
        echo ""

        if [ -n "${'$'}DB" ] && [ -r "${'$'}DB" ]; then
          echo "<<<SECTION:PIE_GAS_GAUGE>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT start_time, end_time, formatted_start_time, formatted_end_time, charge, screen, duration, gas_gauge, level, level_ui, app FROM raw_battery_gas_gauge ORDER BY start_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_BATTERY_STATE>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT start_time, formatted_start_time, gas_gauge, level, level_ui, voltage, temperature, charge, screen, current, adapter_type, charge_full FROM raw_battery_state ORDER BY start_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_THERMAL_TEMP>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT temperature_time, formatted_temperature_time, shell_temperature, front_temperature, back_temperature, ambient_temperature, board_temperature, battery_temperature, ap_temperature, charge_type FROM thermal_temperature_info ORDER BY temperature_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_THERMAL_CHARGE>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT start_time, end_time, formatted_period, is_full_charge, adapter_type, cable_id, charge_mode, charge_type, vbus, ibus, vbat, ibat FROM thermal_charge_info ORDER BY id ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_FOREGROUND>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT app, start_time, end_time, formatted_start_time, formatted_end_time FROM raw_foreground ORDER BY start_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_BRIGHTNESS>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT start_time, end_time, brightness, duration, charge, type FROM raw_screen_brightness WHERE brightness > 0 AND brightness <= 3000 ORDER BY start_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_WIFI_TRAFFIC>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT name, SUM(foreground_tx_bytes + foreground_rx_bytes) AS fg_bytes, SUM(background_tx_bytes + background_rx_bytes) AS bg_bytes FROM detailed_applications_wifi_data GROUP BY name;" 2>/dev/null

          echo "<<<SECTION:PIE_MODEM_TRAFFIC>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT name, SUM(foreground_tx_bytes + foreground_rx_bytes) AS fg_bytes, SUM(background_tx_bytes + background_rx_bytes) AS bg_bytes FROM detailed_applications_modem GROUP BY name;" 2>/dev/null

          echo "<<<SECTION:PIE_DAILY_HEALTH>>>"
          H_OUT=${'$'}(sqlite3 -readonly -json "${'$'}DB" "SELECT battery_cycle_count, battery_health_level, usable_battery_health_level, true_battery_health_level, battery_full_capacity, capacity_mah, formatted_start_time FROM daily_battery_info ORDER BY start_time DESC LIMIT 1;" 2>/dev/null)
          if [ -z "${'$'}H_OUT" ] || [ "${'$'}H_OUT" = "[]" ]; then
            C_DB=${'$'}(ls -1t /data/user/0/com.vivo.pie/cache_databases/*-PIE-*.db 2>/dev/null | head -n 1)
            if [ -n "${'$'}C_DB" ] && [ -r "${'$'}C_DB" ]; then
              sqlite3 -readonly -json "${'$'}C_DB" "SELECT battery_cycle_count, battery_health_level, usable_battery_health_level, true_battery_health_level, battery_full_capacity, capacity_mah, formatted_start_time FROM daily_battery_info ORDER BY start_time DESC LIMIT 1;" 2>/dev/null
            fi
          else
            printf '%s\n' "${'$'}H_OUT"
          fi

          echo "<<<SECTION:PIE_STANDBY>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT formatted_start_time, formatted_end_time, duration, battery_gas_gauge FROM raw_screen_off_section ORDER BY start_time ASC;" 2>/dev/null

          echo "<<<SECTION:PIE_NET_WAKEUP>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT package_name_local AS pkg, COUNT(*) AS cnt FROM raw_net_wakeup GROUP BY package_name_local ORDER BY cnt DESC LIMIT 10;" 2>/dev/null

          echo "<<<SECTION:PIE_WAKEUP_TOTAL>>>"
          sqlite3 -readonly -json "${'$'}DB" "SELECT COUNT(*) AS cnt FROM raw_wake_up_source;" 2>/dev/null
        fi

        echo "<<<SECTION:PIE_HIST_BINS>>>"
        for d in /data/user/0/com.vivo.pie/cache_databases/20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]; do
          f="${'$'}d/overviewBatteryLife.bin"
          if [ -r "${'$'}f" ]; then
            dt=${'$'}(basename "${'$'}d")
            b64=${'$'}(base64 "${'$'}f" 2>/dev/null | tr -d '\r\n')
            if [ -n "${'$'}b64" ]; then
              echo "${'$'}dt:${'$'}b64"
            fi
          fi
        done
    """.trimIndent()

    private fun parseSections(raw: String): Map<String, String> {
        val map = LinkedHashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in raw.lineSequence()) {
            if (line.startsWith("<<<SECTION:") && line.endsWith(">>>")) {
                val name = line.removePrefix("<<<SECTION:").removeSuffix(">>>")
                current = StringBuilder()
                map[name] = current
            } else {
                current?.append(line)?.append('\n')
            }
        }
        return map.mapValues { it.value.toString().trim() }
    }

    private fun parseKvLines(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1).trim()
                if (v.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    private fun parseFuelSummaryPairs(
        item: String,
        data: String,
        itemEx: String,
        dataEx: String
    ): Map<String, String> {
        // item 与 item_ex 在 4096 字节处可能截断于单个键名中间，必须直接拼接（同 vivo ChargeInfo.java）
        val fullItem = (item.replace("\r", "").replace("\n", "") +
            itemEx.replace("\r", "").replace("\n", "")).trim()
        val fullData = (data.replace("\r", "").replace("\n", "") +
            dataEx.replace("\r", "").replace("\n", "")).trim()
        if (fullItem.isBlank() || fullData.isBlank()) return emptyMap()
        val keys = fullItem.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val vals = fullData.split(",").map { it.trim() }
        val n = min(keys.size, vals.size)
        val out = LinkedHashMap<String, String>(n)
        for (i in 0 until n) {
            out[keys[i]] = vals[i]
        }
        return out
    }

    private fun parseJsonArray(text: String?): JSONArray {
        val t = text?.trim().orEmpty()
        if (t.isBlank() || !t.startsWith("[")) return JSONArray()
        return try {
            JSONArray(t)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun parseHourFromFormatted(s: String): Int {
        if (s.length < 13) return -1
        return s.substring(11, 13).toIntOrNull() ?: -1
    }

    private fun computeAppAvgBrightness(
        spans: List<FgSpan>?,
        buckets: Map<Long, Pair<Long, Long>>
    ): Double? {
        if (spans.isNullOrEmpty() || buckets.isEmpty()) return null
        var weightedSum = 0.0
        var totalSec = 0L
        for (sp in spans) {
            val st = sp.startMs
            val et = sp.endMs
            if (et <= st) continue
            var m = st / 60_000L
            val endM = et / 60_000L
            while (m <= endM) {
                val bucket = buckets[m]
                if (bucket != null && bucket.second > 0L) {
                    val mStart = m * 60_000L
                    val overlapSec = (min(et, mStart + 60_000L) - max(st, mStart)) / 1000L
                    if (overlapSec > 0L) {
                        val avgNitInMin = bucket.first.toDouble() / bucket.second.toDouble()
                        weightedSum += avgNitInMin * overlapSec
                        totalSec += overlapSec
                    }
                }
                m++
            }
        }
        return if (totalSec > 0L) weightedSum / totalSec else null
    }

    private fun inferHourStatus(
        chargePct: Int,
        chargeMs: Long,
        dischargePct: Int,
        screenOnMs: Long,
        screenOffMs: Long,
        hasRecords: Boolean
    ): String {
        if (!hasRecords) return "该时段暂无记录"
        val screenOnMin = screenOnMs / 60_000.0
        val chargeMin = chargeMs / 60_000.0
        return when {
            chargePct > 0 && screenOnMin >= 10.0 -> "边充边用"
            chargePct > 0 || chargeMin >= 5.0 -> "充电中"
            screenOnMin < 1.0 && screenOffMs > 0L -> "灭屏待机"
            dischargePct >= 8 -> "高耗电亮屏"
            screenOnMin >= 25.0 -> "亮屏使用"
            screenOnMin > 0.0 && screenOffMs > 0L -> "亮灭混合"
            else -> "常规运行"
        }
    }

    private fun resolveAppName(pkg: String): String {
        if (pkg.isBlank()) return "系统"
        if (pkg == "screen_off") return "息屏待机"
        appNameCache[pkg]?.let { return it }
        val resolved = try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: pkg
        } catch (_: Exception) {
            pkg
        }
        appNameCache[pkg] = resolved
        return resolved
    }

    private fun parseHistoricalBins(sectionText: String): List<DailyTrendRecord> {
        if (sectionText.isBlank()) return emptyList()
        val list = mutableListOf<DailyTrendRecord>()
        for (line in sectionText.lineSequence()) {
            val sep = line.indexOf(':')
            if (sep <= 0) continue
            val dateStr = line.substring(0, sep).trim()
            val b64 = line.substring(sep + 1).trim()
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                DataInputStream(ByteArrayInputStream(bytes)).use { dis ->
                    val ver = dis.readInt()
                    if (ver != 1) return@use
                    /* startSoc */ dis.readInt()
                    /* endSoc */ dis.readInt()
                    val totalDrainPct = dis.readInt()
                    val totalChargePct = dis.readInt()
                    val drainDurationMs = dis.readLong()
                    val screenOnDurationMs = dis.readLong()
                    val screenOnDrainPct = dis.readInt()
                    val screenOffDurationMs = dis.readLong()
                    val screenOffDrainPct = dis.readInt()
                    val drainHrs = drainDurationMs / 3_600_000.0
                    val rate = if (drainHrs > 0.0) totalDrainPct / drainHrs else 0.0
                    list.add(
                        DailyTrendRecord(
                            dateStr = dateStr,
                            totalDrainPct = totalDrainPct,
                            totalChargePct = totalChargePct,
                            drainDurationMs = drainDurationMs,
                            drainRatePctPerHour = rate,
                            screenOnDurationMs = screenOnDurationMs,
                            screenOnDrainPct = screenOnDrainPct,
                            screenOffDurationMs = screenOffDurationMs,
                            screenOffDrainPct = screenOffDrainPct,
                            source = "pie_bin"
                        )
                    )
                }
            } catch (_: Exception) {
                // ignore malformed bin
            }
        }
        return list.sortedBy { it.dateStr }
    }

    private fun translateBatteryStatus(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "charging" -> "充电中"
        "discharging" -> "放电中"
        "full" -> "已充满（接通电源）"
        "not charging" -> "未充电"
        null, "" -> null
        else -> raw
    }

    private fun resolveEffectiveProtocolCode(rawCode: String?, fuelItems: Map<String, String>): String? {
        val trimmed = rawCode?.trim()
        if (trimmed == "7" || trimmed == "8") return trimmed
        // 若已充满/温控退出快充，状态机可能回退到 6(CHG)，参照 vivo ChargeInfo 反向识别已握手的快充协议
        val disfchgVoter = fuelItems["ID_BUFF__DISFCHG_VOTER"]?.trim().orEmpty()
        if (disfchgVoter.isNotEmpty() && disfchgVoter != "0") {
            val vfcpR = fuelItems["ID_INT__VFCP_CP_EX_REASON_L"]?.toIntOrNull() ?: 0
            val ufcsR = fuelItems["ID_INT__UFCS_CP_EX_REASON_L"]?.toIntOrNull() ?: 0
            val ppsR = fuelItems["ID_INT__PPS_CP_EX_REASON_L"]?.toIntOrNull() ?: 0
            val pdR = fuelItems["ID_INT__PD_CP_EX_REASON_L"]?.toIntOrNull() ?: 0
            val qcR = fuelItems["ID_INT__QC_CP_EX_REASON_L"]?.toIntOrNull() ?: 0
            fun handshaked(r: Int) = r != 0 && (r and 2) == 0 && (r and 32) == 0
            when {
                handshaked(vfcpR) -> return "1"
                handshaked(ufcsR) -> return "2"
                handshaked(ppsR) -> return "3"
                handshaked(pdR) -> return "4"
                handshaked(qcR) -> return "5"
            }
        }
        return trimmed
    }

    private fun buildDetailedProtocolName(
        effectiveCode: String?,
        rawCode: String?,
        bc12Type: String?,
        pd9v: Boolean,
        statusRaw: String?,
        termBit: Boolean,
        adapterPowerW: Double?,
        inputPowerW: Double?,
        vbusV: Double?,
        vfcpMaxW: Int?,
        ufcsMaxW: Int?,
        ppsMaxW: Int?
    ): String? {
        val isDischarging = statusRaw?.equals("Discharging", true) == true
        val isFull = statusRaw?.equals("Full", true) == true || termBit
        if (isDischarging && (effectiveCode.isNullOrBlank() || effectiveCode == "0" || effectiveCode == "6")) {
            return "未接入电源（放电中）"
        }

        val powerTag = when {
            adapterPowerW != null && adapterPowerW > 0.0 -> " · 握手 ${adapterPowerW.toInt()}W"
            inputPowerW != null && inputPowerW >= 0.5 -> String.format(java.util.Locale.US, " · 输入 %.1fW", inputPowerW)
            else -> ""
        }
        val stateSuffix = when {
            isFull && effectiveCode != rawCode -> "（已充满/保护退出快充）"
            isFull -> "（已充满/保持电量）"
            else -> ""
        }

        val base = when (effectiveCode) {
            "1" -> "vivo FlashCharge 私有超快闪充 (VFCP${vfcpMaxW?.let { " 最高${it}W" } ?: ""})"
            "2" -> "UFCS 融合快充协议${ufcsMaxW?.let { " (最高${it}W)" } ?: ""}"
            "3" -> "USB PD 3.0 PPS 可编程快充${ppsMaxW?.let { " (最高${it}W)" } ?: ""}"
            "4" -> if (pd9v) "USB Power Delivery 9V 快充 (PD)" else "USB Power Delivery 快充 (PD)"
            "5" -> "Qualcomm Quick Charge 高压快充 (QC)"
            "6" -> {
                val vStr = vbusV?.let { String.format(java.util.Locale.US, "%.1fV", it) } ?: "5V"
                when (bc12Type?.trim()) {
                    "1" -> "USB BC1.2 SDP 标准数据口充电 ($vStr 电脑/USB口)"
                    "2" -> "USB BC1.2 CDP 增强充电口 ($vStr)"
                    "3" -> "USB BC1.2 DCP 常规有线充电器 ($vStr)"
                    "4" -> "常规 5V 浮空口有线充电 (Float $vStr)"
                    else -> "常规有线充电 (BC1.2 / $vStr)"
                }
            }
            "7" -> "Qi 标准普通无线充电 (WLS)"
            "8" -> "vivo 私有无线闪充 (FlashCharge WLS)"
            "0" -> if (isDischarging) "未接入电源（放电中）" else "电源已接入（常规供电 / 协议待握手）"
            null, "" -> return null
            else -> "未知协议码 ($effectiveCode)"
        }
        return "$base$powerTag$stateSuffix"
    }

    private fun buildChargeVoterNote(
        dischgVoter: String?,
        fchgFccVoter: String?,
        disfchgVoter: String?
    ): String? {
        val tags = mutableListOf<String>()
        if (fchgFccVoter?.contains("KEEP_CHG_SOC", true) == true || dischgVoter?.contains("USER_SET_CHG", true) == true) {
            tags += "已触发电池充电上限保护（保持电量）"
        }
        if (fchgFccVoter?.contains("CABLE_POWER", true) == true) {
            tags += "线缆限流（非标配线缆）"
        } else if (fchgFccVoter?.contains("CABLE_R", true) == true) {
            tags += "线缆阻抗偏高限流"
        }
        if (disfchgVoter?.contains("TEMP", true) == true || fchgFccVoter?.contains("THERMAL", true) == true) {
            tags += "温控限制快充电流"
        }
        return tags.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun mapUsbChgType(bc12: String?, raw: String?): String? {
        val bcName = when (bc12?.trim()) {
            "1" -> "USB SDP（电脑/标准数据口）"
            "2" -> "USB CDP（数据+充电下行口）"
            "3" -> "DCP（标准有线充电器）"
            "4" -> "Float（非标准浮空充电口）"
            else -> null
        }
        if (bcName != null) return bcName
        return when (raw?.trim()) {
            "0" -> "无外部电源连接"
            "1" -> "USB SDP（电脑/标准数据口）"
            "2" -> "USB CDP（充电下行口）"
            "3" -> "DCP（标准充电器）"
            "4" -> "快充适配器"
            "5" -> "无线充电底座"
            null, "" -> null
            else -> raw
        }
    }

    private fun mapAdapterType(type: Int): String = when (type) {
        0 -> "未知/标准"
        1 -> "有线 USB / 标充"
        2 -> "DCP 适配器"
        4, 8 -> "快充 / 闪充"
        else -> "类型 $type"
    }

    private fun mapVfcpToActualPower(code: Int): Int = when (code) {
        44 -> 44
        62, 63, 66 -> 66
        76, 77, 80 -> 80
        85, 87 -> 90
        96, 97, 98, 100 -> 100
        115, 120 -> 120
        195 -> 200
        else -> code
    }

    private data class ThermalChargePoint(
        val timeMs: Long,
        val vbusMv: Int,
        val ibusMa: Int,
        val vbatMv: Int,
        val ibatMa: Int
    )

    private fun buildChargingSessions(
        states: List<StateRow>,
        thermalChargeRows: JSONArray
    ): List<ChargingSession> {
        val tcPoints = ArrayList<ThermalChargePoint>(thermalChargeRows.length())
        for (i in 0 until thermalChargeRows.length()) {
            val r = thermalChargeRows.optJSONObject(i) ?: continue
            val t = r.optLong("end_time", r.optLong("start_time", 0L))
            tcPoints.add(
                ThermalChargePoint(
                    timeMs = t,
                    vbusMv = r.optInt("vbus", 0),
                    ibusMa = r.optInt("ibus", 0),
                    vbatMv = r.optInt("vbat", 0),
                    ibatMa = r.optInt("ibat", 0)
                )
            )
        }

        val sessions = mutableListOf<ChargingSession>()
        var segStartIdx = -1
        var prevChgTime = 0L

        fun closeSegment(startIdx: Int, endIdx: Int) {
            if (startIdx < 0 || endIdx < startIdx) return
            val s0 = states[startIdx]
            val s1 = states[endIdx]
            val stMs = s0.timeMs
            val etMs = s1.timeMs
            val durMs = max(0L, etMs - stMs)
            val startSoc = s0.levelUi
            val endSoc = s1.levelUi
            val chargedPct = max(0, endSoc - startSoc)

            var maxVolt = 0
            var bestAdapter = 0
            for (k in startIdx..endIdx) {
                val v = states[k].voltageMv
                if (v > maxVolt) maxVolt = v
                val a = states[k].adapterType
                if (a > bestAdapter) bestAdapter = a
            }

            var peakVbus = 0
            var peakIbus = 0
            var peakPowerW = 0.0
            for (tc in tcPoints) {
                if (tc.timeMs in (stMs - 60_000L)..(etMs + 60_000L)) {
                    if (tc.vbusMv > peakVbus) peakVbus = tc.vbusMv
                    if (tc.ibusMa > peakIbus) peakIbus = tc.ibusMa
                    val w = (tc.vbusMv.toDouble() * tc.ibusMa.toDouble()) / 1_000_000.0
                    if (w > peakPowerW) peakPowerW = w
                }
            }

            sessions.add(
                ChargingSession(
                    startTime = s0.formattedTime.takeLast(8),
                    endTime = s1.formattedTime.takeLast(8),
                    durationMs = durMs,
                    startSoc = startSoc,
                    endSoc = endSoc,
                    chargedPct = chargedPct,
                    peakVoltageMv = maxVolt.takeIf { it > 0 },
                    peakVbusMv = peakVbus.takeIf { it > 0 },
                    peakIbusMa = peakIbus.takeIf { it > 0 },
                    peakPowerW = peakPowerW.takeIf { it > 0.2 },
                    adapterTypeLabel = mapAdapterType(bestAdapter)
                )
            )
        }

        for (i in states.indices) {
            val s = states[i]
            val isChg = s.charge == 1
            val t = s.timeMs
            if (isChg) {
                if (segStartIdx == -1) {
                    segStartIdx = i
                } else if (t - prevChgTime > 240_000L) {
                    closeSegment(segStartIdx, i - 1)
                    segStartIdx = i
                }
                prevChgTime = t
            } else if (segStartIdx != -1) {
                closeSegment(segStartIdx, i - 1)
                segStartIdx = -1
            }
        }
        if (segStartIdx != -1) {
            closeSegment(segStartIdx, states.lastIndex)
        }
        return sessions
    }

    private fun buildStandbySummary(
        standbyRows: JSONArray,
        netWakeupRows: JSONArray,
        wakeupTotalRows: JSONArray
    ): StandbySummary {
        var longCnt = 0
        var longMs = 0L
        var shortCnt = 0
        var shortMs = 0L
        for (i in 0 until standbyRows.length()) {
            val r = standbyRows.optJSONObject(i) ?: continue
            val d = r.optLong("duration", 0L)
            if (d <= 0L || d > 86_400_000L) continue
            if (d >= 1_800_000L) {
                longCnt++
                longMs += d
            } else {
                shortCnt++
                shortMs += d
            }
        }
        val netList = mutableListOf<NetWakeupApp>()
        for (i in 0 until netWakeupRows.length()) {
            val r = netWakeupRows.optJSONObject(i) ?: continue
            val pkg = r.optString("pkg", "").ifBlank { continue }
            val cnt = r.optInt("cnt", 0)
            if (cnt > 0) netList.add(NetWakeupApp(pkg, resolveAppName(pkg), cnt))
        }
        val totalWakeups = wakeupTotalRows.optJSONObject(0)?.optInt("cnt", 0) ?: 0
        return StandbySummary(
            longStandbyCount = longCnt,
            longStandbyDurationMs = longMs,
            shortStandbyCount = shortCnt,
            shortStandbyDurationMs = shortMs,
            totalWakeupCount = totalWakeups,
            topNetWakeups = netList
        )
    }

    private fun buildTemperatureSummary(
        thermalTempRows: JSONArray,
        states: List<StateRow>,
        fgSpans: List<FgSpan>
    ): TemperatureSummary {
        fun findNearestState(ts: Long): StateRow? {
            if (states.isEmpty()) return null
            var lo = 0
            var hi = states.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (states[mid].timeMs < ts) lo = mid + 1 else hi = mid
            }
            val c1 = states[lo]
            val c0 = if (lo > 0) states[lo - 1] else c1
            val best = if (abs(c0.timeMs - ts) <= abs(c1.timeMs - ts)) c0 else c1
            return if (abs(best.timeMs - ts) <= 120_000L) best else null
        }

        fun findForegroundPkg(ts: Long): String? {
            if (fgSpans.isEmpty()) return null
            var lo = 0
            var hi = fgSpans.lastIndex
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (fgSpans[mid].startMs <= ts) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (ans >= 0 && ts <= fgSpans[ans].endMs) {
                return fgSpans[ans].pkg
            }
            return null
        }

        fun decodeTenthC(raw: Int): Double? = if (raw in -300..1000 && raw != 0) raw / 10.0 else null

        val samples = ArrayList<TemperatureSample>()
        if (thermalTempRows.length() > 0) {
            for (i in 0 until thermalTempRows.length()) {
                val r = thermalTempRows.optJSONObject(i) ?: continue
                val ts = r.optLong("temperature_time", 0L)
                val fullLbl = r.optString("formatted_temperature_time", "")
                if (ts <= 0L || fullLbl.length < 19) continue
                val batC = decodeTenthC(r.optInt("battery_temperature", 0))
                val shellC = decodeTenthC(r.optInt("shell_temperature", 0))
                val boardC = decodeTenthC(r.optInt("board_temperature", 0))
                val apC = decodeTenthC(r.optInt("ap_temperature", 0))
                if (batC == null && shellC == null && boardC == null) continue

                val st = findNearestState(ts)
                val isChg = (st?.let { it.charge == 1 }) ?: (r.optInt("charge_type", 0) > 0)
                val soc = st?.levelUi
                val fgPkg = findForegroundPkg(ts)
                val fgName = when {
                    fgPkg != null -> resolveAppName(fgPkg)
                    st != null && st.screen == 0 -> "息屏待机"
                    else -> "桌面 / 锁屏"
                }

                samples.add(
                    TemperatureSample(
                        timestampMs = ts,
                        timeLabel = fullLbl.substring(11, 19),
                        fullTimeLabel = fullLbl,
                        batteryTempC = batC ?: st?.tempC?.takeIf { t -> t in -30..100 }?.toDouble(),
                        shellTempC = shellC,
                        boardTempC = boardC,
                        apTempC = apC,
                        isCharging = isChg,
                        soc = soc,
                        foregroundPackage = fgPkg,
                        foregroundAppName = fgName
                    )
                )
            }
        } else {
            for (st in states) {
                val t = st.tempC
                if (t !in -30..100) continue
                val ts = st.timeMs
                val lbl = st.formattedTime
                val fgPkg = findForegroundPkg(ts)
                samples.add(
                    TemperatureSample(
                        timestampMs = ts,
                        timeLabel = if (lbl.length >= 19) lbl.substring(11, 19) else lbl,
                        fullTimeLabel = lbl,
                        batteryTempC = t.toDouble(),
                        shellTempC = null,
                        boardTempC = null,
                        apTempC = null,
                        isCharging = st.charge == 1,
                        soc = st.levelUi,
                        foregroundPackage = fgPkg,
                        foregroundAppName = fgPkg?.let { resolveAppName(it) }
                            ?: if (st.screen == 0) "息屏待机" else "桌面 / 锁屏"
                    )
                )
            }
        }

        fun calcStats(values: List<Double>): TempRangeStats {
            if (values.isEmpty()) return TempRangeStats(null, null, null, 0)
            return TempRangeStats(
                minC = values.minOrNull(),
                maxC = values.maxOrNull(),
                avgC = values.average(),
                count = values.size
            )
        }

        val chgSamples = samples.filter { it.isCharging }
        val disSamples = samples.filter { !it.isCharging }

        return TemperatureSummary(
            chargeBatteryRange = calcStats(chgSamples.mapNotNull { it.batteryTempC }),
            dischargeBatteryRange = calcStats(disSamples.mapNotNull { it.batteryTempC }),
            chargeShellRange = calcStats(chgSamples.mapNotNull { it.shellTempC }),
            dischargeShellRange = calcStats(disSamples.mapNotNull { it.shellTempC }),
            chargeBoardRange = calcStats(chgSamples.mapNotNull { it.boardTempC }),
            dischargeBoardRange = calcStats(disSamples.mapNotNull { it.boardTempC }),
            samples = samples
        )
    }
}
