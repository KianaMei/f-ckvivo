package dev.ganvivo.powerinsight.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ganvivo.powerinsight.data.AppPowerUsage
import dev.ganvivo.powerinsight.data.AppTrafficItem
import dev.ganvivo.powerinsight.data.DailyTrendRecord
import dev.ganvivo.powerinsight.data.PowerSnapshot
import dev.ganvivo.powerinsight.ui.components.EmptyNoticeCard
import dev.ganvivo.powerinsight.ui.components.HourlyPowerChart
import dev.ganvivo.powerinsight.ui.components.KeyValueLine
import dev.ganvivo.powerinsight.ui.components.MetricCard
import dev.ganvivo.powerinsight.ui.components.SectionTitle
import dev.ganvivo.powerinsight.ui.components.SocGaugeRing
import dev.ganvivo.powerinsight.ui.components.StatusPill
import dev.ganvivo.powerinsight.util.UnitFormatter
import java.util.Locale

@Composable
fun OverviewScreen(
    snapshot: PowerSnapshot,
    trends: List<DailyTrendRecord>,
    isWide: Boolean,
    isLiveMode: Boolean,
    contentPadding: PaddingValues
) {
    var selectedHour by remember(snapshot.id, snapshot.capturedAtMs) { mutableStateOf<Int?>(null) }
    val ov = snapshot.overview
    val chg = snapshot.chargingDetail
    val bh = snapshot.batteryHealth
    val ts = snapshot.temperatureSummary
    val sb = snapshot.standbySummary
    val last7Avg = trends.take(7).takeIf { it.isNotEmpty() }?.map { it.drainRatePctPerHour }?.average()
    val isChargingNow = chg.currentStatus?.contains("充电") == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Live Telemetry Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SocGaugeRing(soc = chg.currentSoc, isCharging = isChargingNow)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = snapshot.deviceModel.ifBlank { "vivo 设备" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            StatusPill(
                                text = if (isLiveMode) "● 实时" else "历史存档",
                                containerColor = if (isLiveMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                },
                                contentColor = if (isLiveMode) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                }
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isLiveMode) {
                                "实时刷新于 ${snapshot.capturedTimeLabel}"
                            } else {
                                "历史快照采集于 ${snapshot.capturedTimeLabel}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            StatusPill(
                                text = chg.currentStatus ?: UnitFormatter.NO_RECORD,
                                containerColor = if (isChargingNow) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (isChargingNow) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            chg.currentVoltageV?.let {
                                StatusPill(
                                    text = UnitFormatter.formatVoltageV(it),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            chg.currentBatteryTempC?.let {
                                StatusPill(
                                    text = UnitFormatter.formatTemp(it),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (!chg.protocolName.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "协议：${chg.protocolName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Core KPI Grid
        item {
            val cols = if (isWide) 3 else 2
            val tiles = listOf(
                Triple("今日总放出", "${ov.totalDrainPct}%", "速率 ${UnitFormatter.formatRatePctPerHour(ov.drainRatePctPerHour)}"),
                Triple("今日总充入", "${chg.todayTotalChargePct}%", "${chg.sessions.size} 次充电时段"),
                Triple("亮屏时长 / 掉电", UnitFormatter.formatDurationMs(ov.screenOnDurationMs), "${ov.screenOnDrainPct}% · ${UnitFormatter.formatRatePctPerHour(ov.screenOnDrainRatePctPerHour)}"),
                Triple("灭屏时长 / 掉电", UnitFormatter.formatDurationMs(ov.screenOffDurationMs), "${ov.screenOffDrainPct}% · ${UnitFormatter.formatRatePctPerHour(ov.screenOffDrainRatePctPerHour)}"),
                Triple(
                    "电池健康 / 循环",
                    bh.healthPct?.let { String.format(Locale.US, "%.0f%%", it) } ?: UnitFormatter.NO_RECORD,
                    "循环 ${bh.cycleCount?.let { "$it 次" } ?: UnitFormatter.NO_RECORD}"
                ),
                Triple(
                    "今日流量 / 7天均速",
                    UnitFormatter.formatBytes(snapshot.trafficSummary.totalBytes),
                    last7Avg?.let { "7天均速 ${UnitFormatter.formatRatePctPerHour(it)}" } ?: "持续记录以生成趋势"
                )
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(cols).forEach { rowTiles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowTiles.forEach { (k, v, s) ->
                            MetricCard(
                                label = k,
                                value = v,
                                supporting = s,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(cols - rowTiles.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Quick Temperature Overview Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "今日电池温度区间（共 ${ts.samples.size} 次采样）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    KeyValueLine(
                        label = "充电阶段温度区间",
                        value = formatTempRange(ts.chargeBatteryRange.minC, ts.chargeBatteryRange.maxC),
                        highlightColor = MaterialTheme.colorScheme.tertiary
                    )
                    KeyValueLine(
                        label = "放电阶段温度区间",
                        value = formatTempRange(ts.dischargeBatteryRange.minC, ts.dischargeBatteryRange.maxC),
                        highlightColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ② 今日按小时时间分布
        item {
            SectionTitle(
                badge = "②",
                title = "今日按小时时间分布",
                subtitle = "不排除充电时段，展示每小时充入、放出、亮屏时长、灭屏时长及状态（点击柱图筛选小时）"
            )
        }

        item {
            HourlyPowerChart(
                items = snapshot.hourlyItems,
                selectedHour = selectedHour,
                onSelectHour = { selectedHour = it }
            )
        }

        val displayedHours = if (selectedHour != null) {
            snapshot.hourlyItems.filter { it.hour == selectedHour }
        } else {
            snapshot.hourlyItems
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (displayedHours.isEmpty()) {
                        Text(
                            text = UnitFormatter.NO_RECORD,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        displayedHours.forEachIndexed { idx, h ->
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = h.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(
                                            text = h.statusText,
                                            containerColor = if (h.chargePct > 0 || h.chargeDurationMs > 0L) {
                                                MaterialTheme.colorScheme.tertiaryContainer
                                            } else if (h.dischargePct >= 8) {
                                                MaterialTheme.colorScheme.errorContainer
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            contentColor = if (h.chargePct > 0 || h.chargeDurationMs > 0L) {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            } else if (h.dischargePct >= 8) {
                                                MaterialTheme.colorScheme.onErrorContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            }
                                        )
                                    }
                                    Text(
                                        text = "充 +${h.chargePct}% · 放 -${h.dischargePct}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "亮屏 ${UnitFormatter.formatDurationMs(h.screenOnMs)}   ·   灭屏 ${UnitFormatter.formatDurationMs(h.screenOffMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (idx < displayedHours.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }

        // 待机与网络唤醒附加信息
        item {
            SectionTitle(
                badge = "附加",
                title = "待机分段与网络唤醒",
                subtitle = "来自 PIE raw_screen_off_section 与 raw_net_wakeup"
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeyValueLine("长待机 (≥30分钟)", "${sb.longStandbyCount} 段 · ${UnitFormatter.formatDurationMs(sb.longStandbyDurationMs)}")
                    KeyValueLine("短待机 (<30分钟)", "${sb.shortStandbyCount} 段 · ${UnitFormatter.formatDurationMs(sb.shortStandbyDurationMs)}")
                    KeyValueLine("总唤醒次数", "${sb.totalWakeupCount} 次")
                    if (sb.topNetWakeups.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Top 网络唤醒源",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        sb.topNetWakeups.take(5).forEach { nw ->
                            KeyValueLine(nw.appName, "${nw.count} 次")
                        }
                    }
                }
            }
        }
    }
}

private enum class AppSortMode(val label: String) {
    DRAIN("掉电量"),
    DURATION("前台时长"),
    CURRENT("平均电流"),
    RATE("掉电速率")
}

@Composable
fun AppsAndTrafficScreen(
    snapshot: PowerSnapshot,
    contentPadding: PaddingValues
) {
    var subTab by remember { mutableIntStateOf(0) } // 0 = ① 应用耗电, 1 = ④ 网络流量
    var search by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(AppSortMode.DRAIN) }

    val filteredApps = remember(snapshot.appUsages, search, sortMode) {
        val q = search.trim().lowercase()
        val base = if (q.isEmpty()) snapshot.appUsages else snapshot.appUsages.filter {
            it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        when (sortMode) {
            AppSortMode.DRAIN -> base.sortedWith(compareByDescending<AppPowerUsage> { it.drainPct }.thenByDescending { it.foregroundDurationMs })
            AppSortMode.DURATION -> base.sortedByDescending { it.foregroundDurationMs }
            AppSortMode.CURRENT -> base.sortedByDescending { it.avgCurrentMa }
            AppSortMode.RATE -> base.sortedByDescending { it.drainRatePctPerHour }
        }
    }

    val filteredTraffic = remember(snapshot.trafficSummary.apps, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) snapshot.trafficSummary.apps else snapshot.trafficSummary.apps.filter {
            it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) {
                    Text("① 应用耗电 (${snapshot.appUsages.size})")
                }
                SegmentedButton(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text("④ 网络流量 (${snapshot.trafficSummary.apps.size})")
                }
            }
        }

        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (subTab == 0) {
            item {
                SectionTitle(
                    badge = "①",
                    title = "全部有记录应用耗电明细",
                    subtitle = "共 ${snapshot.appUsages.size} 个应用（不截断）；带 * 为前台使用不足 5 分钟，电流受启动峰值影响仅供参考"
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppSortMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sortMode == mode,
                            onClick = { sortMode = mode },
                            label = { Text(mode.label, maxLines = 1) }
                        )
                    }
                }
            }

            if (filteredApps.isEmpty()) {
                item { EmptyNoticeCard("今日应用耗电：${UnitFormatter.NO_RECORD}") }
            } else {
                val maxDur = filteredApps.maxOf { it.foregroundDurationMs }.coerceAtLeast(1L)
                items(filteredApps, key = { it.packageName }) { app ->
                    AppUsageCard(app = app, maxDurationMs = maxDur)
                }
            }
        } else {
            val tr = snapshot.trafficSummary
            item {
                SectionTitle(
                    badge = "④",
                    title = "网络流量消耗明细",
                    subtitle = "今日总流量汇总及各应用 WiFi / 移动数据前后台明细"
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        label = "今日总流量",
                        value = UnitFormatter.formatBytes(tr.totalBytes),
                        supporting = "WiFi ${UnitFormatter.formatBytes(tr.wifiTotalBytes)}\n移动 ${UnitFormatter.formatBytes(tr.mobileTotalBytes)}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "前台 / 后台",
                        value = UnitFormatter.formatBytes(tr.foregroundTotalBytes),
                        supporting = "后台 ${UnitFormatter.formatBytes(tr.backgroundTotalBytes)}\n后台占比 ${String.format(Locale.US, "%.2f%%", tr.backgroundRatioPct)}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (filteredTraffic.isEmpty()) {
                item { EmptyNoticeCard("今日网络流量：${UnitFormatter.NO_RECORD}") }
            } else {
                items(filteredTraffic, key = { it.packageName }) { item ->
                    AppTrafficCard(item = item)
                }
            }
        }
    }
}

@Composable
private fun AppUsageCard(app: AppPowerUsage, maxDurationMs: Long) {
    val isShort = app.foregroundDurationMs < 300_000L
    val drainText = if (app.drainPct > 0) "${app.drainPct}%" else "<1%"
    val brightText = app.avgBrightnessNit?.let { String.format(Locale.US, "%.0f nit", it) } ?: UnitFormatter.NO_RECORD

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isShort) "${app.appName} *" else app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "掉电 $drainText",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = UnitFormatter.formatDurationMs(app.foregroundDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (app.foregroundDurationMs.toFloat() / maxDurationMs).coerceIn(0.02f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "速率 ${UnitFormatter.formatRatePctPerHour(app.drainRatePctPerHour)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "电流 ${UnitFormatter.formatCurrentMa(app.avgCurrentMa)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "亮度 $brightText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppTrafficCard(item: AppTrafficItem) {
    val fgRatio = if (item.totalBytes > 0L) (item.fgBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = UnitFormatter.formatBytes(item.totalBytes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            // Dual-color foreground vs background bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (fgRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(fgRatio.coerceAtLeast(0.01f))
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                val bgRatio = 1f - fgRatio
                if (bgRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(bgRatio.coerceAtLeast(0.01f))
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "前台 ${UnitFormatter.formatBytes(item.fgBytes)}  ·  后台 ${UnitFormatter.formatBytes(item.bgBytes)} (${String.format(Locale.US, "%.1f%%", item.bgRatioPct)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "WiFi：前 ${UnitFormatter.formatBytes(item.wifiFgBytes)} / 后 ${UnitFormatter.formatBytes(item.wifiBgBytes)}   |   移动：前 ${UnitFormatter.formatBytes(item.mobileFgBytes)} / 后 ${UnitFormatter.formatBytes(item.mobileBgBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatTempRange(minC: Double?, maxC: Double?): String {
    if (minC == null || maxC == null) return UnitFormatter.NO_RECORD
    return String.format(Locale.US, "%.1f ℃ ~ %.1f ℃", minC, maxC)
}
