package dev.ganvivo.powerinsight.export

import dev.ganvivo.powerinsight.data.DailyTrendRecord
import dev.ganvivo.powerinsight.data.PowerSnapshot
import dev.ganvivo.powerinsight.util.UnitFormatter
import java.util.Locale

object ReportExporter {

    fun toJsonString(snapshot: PowerSnapshot): String = snapshot.toJson().toString(2)

    fun toHtmlReport(snapshot: PowerSnapshot, trends: List<DailyTrendRecord>): String {
        val ov = snapshot.overview
        val bh = snapshot.batteryHealth
        val chg = snapshot.chargingDetail
        val ts = snapshot.temperatureSummary
        val tr = snapshot.trafficSummary

        val last7 = trends.take(7)
        val last30 = trends.take(30)
        val avg7 = if (last7.isNotEmpty()) last7.map { it.drainRatePctPerHour }.average() else null
        val avg30 = if (last30.isNotEmpty()) last30.map { it.drainRatePctPerHour }.average() else null

        val sb = StringBuilder(32768)
        sb.append(
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>耗电洞察报告 · ${esc(snapshot.dateStr)}</title>
            <style>
              :root{
                --bg:#0f1413;--sf:#171e1d;--sf2:#1f2826;--bd:rgba(255,255,255,.09);
                --t1:#e8f1ef;--t2:#9cb0ac;--p:#6ee7b7;--pb:rgba(110,231,183,.14);
                --w:#fbbf24;--wb:rgba(251,191,36,.15);--d:#f87171;--db:rgba(248,113,113,.15);
                --c:#60a5fa;--cb:rgba(96,165,250,.15);
              }
              *{box-sizing:border-box;margin:0;padding:0}
              body{background:var(--bg);color:var(--t1);font-family:system-ui,-apple-system,'PingFang SC','Noto Sans SC',sans-serif;font-size:14px;line-height:1.55;padding:24px 14px 48px}
              .wrap{max-width:920px;margin:0 auto}
              h1{font-size:26px;font-weight:700;margin-bottom:4px}
              .sub{color:var(--t2);font-size:13px;margin-bottom:20px}
              h2{font-size:18px;font-weight:650;margin:28px 0 10px;display:flex;align-items:center;gap:8px}
              h2 span{background:var(--pb);color:var(--p);font-size:12px;padding:2px 8px;border-radius:999px}
              .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px}
              .card{background:var(--sf);border:1px solid var(--bd);border-radius:16px;padding:14px}
              .k{font-size:12px;color:var(--t2)}
              .v{font-size:22px;font-weight:700;margin-top:4px}
              .m{font-size:12px;color:var(--t2);margin-top:4px}
              table{width:100%;border-collapse:collapse;margin-top:8px;font-size:13px}
              th,td{padding:8px 10px;border-bottom:1px solid var(--bd);text-align:left}
              th{color:var(--t2);font-weight:600;background:var(--sf2);position:sticky;top:0}
              .tbl-wrap{background:var(--sf);border:1px solid var(--bd);border-radius:16px;overflow-x:auto}
              .pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;font-weight:600}
              .pill.chg{background:var(--pb);color:var(--p)}
              .pill.dis{background:var(--cb);color:var(--c)}
              .pill.hot{background:var(--db);color:var(--d)}
            </style>
            </head>
            <body><div class="wrap">
            <h1>耗电洞察详细报告</h1>
            <div class="sub">${esc(snapshot.deviceModel)} · ${esc(snapshot.romVersion)} · 采集时间 ${esc(snapshot.capturedTimeLabel)} · 数据区间 ${esc(snapshot.dataRangeStart ?: UnitFormatter.NO_RECORD)} ~ ${esc(snapshot.dataRangeEnd ?: UnitFormatter.NO_RECORD)}</div>
            """.trimIndent()
        )

        // 00 概览
        sb.append(
            """
            <div class="grid">
              <div class="card"><div class="k">今日总放出</div><div class="v">${ov.totalDrainPct}%</div><div class="m">速率 ${UnitFormatter.formatRatePctPerHour(ov.drainRatePctPerHour)}</div></div>
              <div class="card"><div class="k">今日总充入</div><div class="v">${chg.todayTotalChargePct}%</div><div class="m">当前 ${chg.currentSoc?.let { "$it%" } ?: UnitFormatter.NO_RECORD}</div></div>
              <div class="card"><div class="k">亮屏时长 / 掉电</div><div class="v">${UnitFormatter.formatDurationMs(ov.screenOnDurationMs)}</div><div class="m">${ov.screenOnDrainPct}% · ${UnitFormatter.formatRatePctPerHour(ov.screenOnDrainRatePctPerHour)}</div></div>
              <div class="card"><div class="k">灭屏时长 / 掉电</div><div class="v">${UnitFormatter.formatDurationMs(ov.screenOffDurationMs)}</div><div class="m">${ov.screenOffDrainPct}% · ${UnitFormatter.formatRatePctPerHour(ov.screenOffDrainRatePctPerHour)}</div></div>
              <div class="card"><div class="k">电池健康 / 循环</div><div class="v">${bh.healthPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: UnitFormatter.NO_RECORD}</div><div class="m">循环 ${bh.cycleCount?.let { "${it} 次" } ?: UnitFormatter.NO_RECORD}</div></div>
              <div class="card"><div class="k">实时电压 / 协议</div><div class="v">${UnitFormatter.formatVoltageV(chg.currentVoltageV)}</div><div class="m">${esc(chg.protocolName ?: UnitFormatter.NO_RECORD)}</div></div>
            </div>
            """.trimIndent()
        )

        // ① 应用耗电明细
        sb.append("<h2><span>①</span>应用耗电明细（全部 ${snapshot.appUsages.size} 个应用）</h2>")
        if (snapshot.appUsages.isEmpty()) {
            sb.append("<div class='card'>${UnitFormatter.NO_RECORD}</div>")
        } else {
            sb.append("<div class='tbl-wrap'><table><thead><tr><th>应用</th><th>前台时长</th><th>掉电量</th><th>掉电速率</th><th>平均电流</th><th>平均亮度</th></tr></thead><tbody>")
            for (a in snapshot.appUsages) {
                val bri = a.avgBrightnessNit?.let { String.format(Locale.US, "%.0f nit", it) } ?: UnitFormatter.NO_RECORD
                val drainStr = if (a.drainPct > 0) "${a.drainPct}%" else "<1%"
                sb.append("<tr><td><b>${esc(a.appName)}</b><br><span style='color:var(--t2);font-size:11px'>${esc(a.packageName)}</span></td>")
                sb.append("<td>${UnitFormatter.formatDurationMs(a.foregroundDurationMs)}</td>")
                sb.append("<td>$drainStr</td>")
                sb.append("<td>${UnitFormatter.formatRatePctPerHour(a.drainRatePctPerHour)}</td>")
                sb.append("<td>${UnitFormatter.formatCurrentMa(a.avgCurrentMa)}</td>")
                sb.append("<td>$bri</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }

        // ② 按小时时间分布
        sb.append("<h2><span>②</span>今日按小时时间分布（含充电时段）</h2>")
        sb.append("<div class='tbl-wrap'><table><thead><tr><th>时段</th><th>充入电量</th><th>放出电量</th><th>亮屏时长</th><th>灭屏时长</th><th>状态</th></tr></thead><tbody>")
        for (h in snapshot.hourlyItems) {
            sb.append("<tr><td>${esc(h.label)}</td><td>${h.chargePct}%</td><td>${h.dischargePct}%</td>")
            sb.append("<td>${UnitFormatter.formatDurationMs(h.screenOnMs)}</td>")
            sb.append("<td>${UnitFormatter.formatDurationMs(h.screenOffMs)}</td>")
            sb.append("<td>${esc(h.statusText)}</td></tr>")
        }
        sb.append("</tbody></table></div>")

        // ③ 近7天 / 近30天趋势对比
        sb.append("<h2><span>③</span>近 7 天与近 30 天耗电趋势对比</h2>")
        sb.append("<div class='grid'>")
        sb.append("<div class='card'><div class='k'>今日掉电速率</div><div class='v'>${UnitFormatter.formatRatePctPerHour(ov.drainRatePctPerHour)}</div></div>")
        sb.append("<div class='card'><div class='k'>近 7 天日均（实有 ${last7.size} 天）</div><div class='v'>${UnitFormatter.formatRatePctPerHour(avg7)}</div></div>")
        sb.append("<div class='card'><div class='k'>近 30 天日均（实有 ${last30.size} 天）</div><div class='v'>${UnitFormatter.formatRatePctPerHour(avg30)}</div></div>")
        sb.append("</div>")
        if (last30.isNotEmpty()) {
            sb.append("<div class='tbl-wrap' style='margin-top:10px'><table><thead><tr><th>日期</th><th>掉电速率</th><th>放出</th><th>充入</th><th>亮屏时长</th><th>灭屏时长</th></tr></thead><tbody>")
            for (d in last30) {
                sb.append("<tr><td>${esc(d.dateStr)}</td><td>${UnitFormatter.formatRatePctPerHour(d.drainRatePctPerHour)}</td>")
                sb.append("<td>${d.totalDrainPct}%</td><td>${d.totalChargePct}%</td>")
                sb.append("<td>${UnitFormatter.formatDurationMs(d.screenOnDurationMs)}</td><td>${UnitFormatter.formatDurationMs(d.screenOffDurationMs)}</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }

        // ④ 网络流量消耗
        sb.append("<h2><span>④</span>网络流量消耗</h2>")
        sb.append("<div class='grid'>")
        sb.append("<div class='card'><div class='k'>今日总流量</div><div class='v'>${UnitFormatter.formatBytes(tr.totalBytes)}</div><div class='m'>WiFi ${UnitFormatter.formatBytes(tr.wifiTotalBytes)} · 移动 ${UnitFormatter.formatBytes(tr.mobileTotalBytes)}</div></div>")
        sb.append("<div class='card'><div class='k'>前台流量</div><div class='v'>${UnitFormatter.formatBytes(tr.foregroundTotalBytes)}</div></div>")
        sb.append("<div class='card'><div class='k'>后台流量</div><div class='v'>${UnitFormatter.formatBytes(tr.backgroundTotalBytes)}</div><div class='m'>占比 ${String.format(Locale.US, "%.2f%%", tr.backgroundRatioPct)}</div></div>")
        sb.append("</div>")
        if (tr.apps.isNotEmpty()) {
            sb.append("<div class='tbl-wrap' style='margin-top:10px'><table><thead><tr><th>应用</th><th>总流量</th><th>前台流量</th><th>后台流量</th><th>WiFi(前/后)</th><th>移动(前/后)</th></tr></thead><tbody>")
            for (a in tr.apps) {
                sb.append("<tr><td>${esc(a.appName)}</td><td>${UnitFormatter.formatBytes(a.totalBytes)}</td>")
                sb.append("<td>${UnitFormatter.formatBytes(a.fgBytes)}</td><td>${UnitFormatter.formatBytes(a.bgBytes)}</td>")
                sb.append("<td>${UnitFormatter.formatBytes(a.wifiFgBytes)} / ${UnitFormatter.formatBytes(a.wifiBgBytes)}</td>")
                sb.append("<td>${UnitFormatter.formatBytes(a.mobileFgBytes)} / ${UnitFormatter.formatBytes(a.mobileBgBytes)}</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }

        // ⑤ 电池健康度 & 循环次数
        sb.append("<h2><span>⑤</span>电池健康度与充电循环</h2>")
        sb.append("<div class='grid'>")
        sb.append("<div class='card'><div class='k'>电池健康度 SOH</div><div class='v'>${bh.healthPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: UnitFormatter.NO_RECORD}</div></div>")
        sb.append("<div class='card'><div class='k'>累计充电循环</div><div class='v'>${bh.cycleCount?.let { "$it 次" } ?: UnitFormatter.NO_RECORD}</div></div>")
        sb.append("<div class='card'><div class='k'>估算满充容量</div><div class='v'>${bh.fullCapacityMah?.let { "$it mAh" } ?: UnitFormatter.NO_RECORD}</div></div>")
        sb.append("</div>")

        // ⑥ 详细充电数据
        sb.append("<h2><span>⑥</span>详细充电数据</h2>")
        sb.append("<div class='grid'>")
        sb.append("<div class='card'><div class='k'>今日累计充入</div><div class='v'>${chg.todayTotalChargePct}%</div></div>")
        sb.append("<div class='card'><div class='k'>当前电量 / 状态</div><div class='v'>${chg.currentSoc?.let { "$it%" } ?: UnitFormatter.NO_RECORD}</div><div class='m'>${esc(chg.currentStatus ?: UnitFormatter.NO_RECORD)}</div></div>")
        val busInfo = if (chg.vbusV != null || chg.ibusMa != null) {
            "总线 ${UnitFormatter.formatVoltageV(chg.vbusV)} / ${UnitFormatter.formatCurrentMa(chg.ibusMa)}" +
                (chg.inputPowerW?.let { String.format(Locale.US, " (%.1fW)", it) } ?: "")
        } else {
            "电流 ${UnitFormatter.formatCurrentMa(chg.currentMa)}"
        }
        sb.append("<div class='card'><div class='k'>当前电池电压</div><div class='v'>${UnitFormatter.formatVoltageV(chg.currentVoltageV)}</div><div class='m'>$busInfo</div></div>")
        val protoMeta = listOfNotNull(
            "快充：${chg.fastChargeActive?.let { if (it) "已激活" else "未激活" } ?: UnitFormatter.NO_RECORD}",
            chg.chargeVoterNote,
            chg.supportedProtocolsSummary?.let { "支持 $it" }
        ).joinToString(" · ")
        sb.append("<div class='card'><div class='k'>充电协议与快充</div><div class='v' style='font-size:15px'>${esc(chg.protocolName ?: UnitFormatter.NO_RECORD)}</div><div class='m'>${esc(protoMeta)}</div></div>")
        sb.append("</div>")
        if (chg.sessions.isNotEmpty()) {
            sb.append("<div class='tbl-wrap' style='margin-top:10px'><table><thead><tr><th>充电时段</th><th>时长</th><th>电量变化</th><th>充入</th><th>峰值电压 / 功率</th><th>类型</th></tr></thead><tbody>")
            for (s in chg.sessions) {
                val vp = listOfNotNull(
                    s.peakVoltageMv?.let { String.format(Locale.US, "%.3fV", it / 1000.0) },
                    s.peakPowerW?.let { String.format(Locale.US, "%.1fW", it) }
                ).joinToString(" · ").ifBlank { UnitFormatter.NO_RECORD }
                sb.append("<tr><td>${esc(s.startTime)}–${esc(s.endTime)}</td><td>${UnitFormatter.formatDurationMs(s.durationMs)}</td>")
                sb.append("<td>${s.startSoc}% → ${s.endSoc}%</td><td>+${s.chargedPct}%</td><td>$vp</td><td>${esc(s.adapterTypeLabel)}</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }

        // ⑦ 温度记录
        sb.append("<h2><span>⑦</span>温度记录（共 ${ts.samples.size} 次采样）</h2>")
        sb.append("<div class='grid'>")
        sb.append("<div class='card'><div class='k'>充电阶段电池温度区间</div><div class='v' style='font-size:18px'>${formatRange(ts.chargeBatteryRange.minC, ts.chargeBatteryRange.maxC)}</div><div class='m'>均值 ${UnitFormatter.formatTemp(ts.chargeBatteryRange.avgC)} · ${ts.chargeBatteryRange.count} 点</div></div>")
        sb.append("<div class='card'><div class='k'>放电阶段电池温度区间</div><div class='v' style='font-size:18px'>${formatRange(ts.dischargeBatteryRange.minC, ts.dischargeBatteryRange.maxC)}</div><div class='m'>均值 ${UnitFormatter.formatTemp(ts.dischargeBatteryRange.avgC)} · ${ts.dischargeBatteryRange.count} 点</div></div>")
        sb.append("<div class='card'><div class='k'>充电阶段壳温区间</div><div class='v' style='font-size:18px'>${formatRange(ts.chargeShellRange.minC, ts.chargeShellRange.maxC)}</div><div class='m'>均值 ${UnitFormatter.formatTemp(ts.chargeShellRange.avgC)}</div></div>")
        sb.append("<div class='card'><div class='k'>放电阶段壳温区间</div><div class='v' style='font-size:18px'>${formatRange(ts.dischargeShellRange.minC, ts.dischargeShellRange.maxC)}</div><div class='m'>均值 ${UnitFormatter.formatTemp(ts.dischargeShellRange.avgC)}</div></div>")
        sb.append("</div>")
        if (ts.samples.isNotEmpty()) {
            sb.append("<div class='tbl-wrap' style='margin-top:10px'><table><thead><tr><th>时间点</th><th>电池温度</th><th>壳温</th><th>主板温度</th><th>充/放电</th><th>前台应用</th></tr></thead><tbody>")
            for (s in ts.samples) {
                val badge = if (s.isCharging) "<span class='pill chg'>充电</span>" else "<span class='pill dis'>放电</span>"
                sb.append("<tr><td>${esc(s.timeLabel)}</td><td>${UnitFormatter.formatTemp(s.batteryTempC)}</td>")
                sb.append("<td>${UnitFormatter.formatTemp(s.shellTempC)}</td><td>${UnitFormatter.formatTemp(s.boardTempC)}</td>")
                sb.append("<td>$badge</td><td>${esc(s.foregroundAppName)}</td></tr>")
            }
            sb.append("</tbody></table></div>")
        }

        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun formatRange(minC: Double?, maxC: Double?): String {
        if (minC == null || maxC == null) return UnitFormatter.NO_RECORD
        return String.format(Locale.US, "%.1f ℃ ~ %.1f ℃", minC, maxC)
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
