package dev.ganvivo.powerinsight.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ganvivo.powerinsight.data.DailyTrendRecord
import dev.ganvivo.powerinsight.data.PowerSnapshot
import dev.ganvivo.powerinsight.data.SnapshotMeta
import dev.ganvivo.powerinsight.ui.components.DailyTrendChart
import dev.ganvivo.powerinsight.ui.components.EmptyNoticeCard
import dev.ganvivo.powerinsight.ui.components.KeyValueLine
import dev.ganvivo.powerinsight.ui.components.MetricCard
import dev.ganvivo.powerinsight.ui.components.SectionTitle
import dev.ganvivo.powerinsight.ui.components.StatusPill
import dev.ganvivo.powerinsight.ui.components.TempSensorChannel
import dev.ganvivo.powerinsight.ui.components.TemperatureCurveChart
import dev.ganvivo.powerinsight.util.UnitFormatter
import java.util.Locale

@Composable
fun ChargeAndHealthScreen(
    snapshot: PowerSnapshot,
    contentPadding: PaddingValues
) {
    val bh = snapshot.batteryHealth
    val chg = snapshot.chargingDetail

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
        // ⑤ 电池健康度 & 循环次数
        item {
            SectionTitle(
                badge = "⑤",
                title = "电池健康度与充电循环",
                subtitle = "数据来源：${bh.sourceLabel}"
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    label = "电池健康度 SOH",
                    value = bh.healthPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: UnitFormatter.NO_RECORD,
                    supporting = bh.usableHealthPct?.let { "可用健康度 ${String.format(Locale.US, "%.1f%%", it)}" }
                        ?: "内核实时健康读数",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "累计充电循环",
                    value = bh.cycleCount?.let { "$it 次" } ?: UnitFormatter.NO_RECORD,
                    supporting = bh.fullCapacityMah?.let { "当前满充 $it mAh" } ?: "实时循环计数",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    KeyValueLine("真实健康度 (True SOH)", bh.trueHealthPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: UnitFormatter.NO_RECORD)
                    KeyValueLine("满充估算容量", bh.fullCapacityMah?.let { "$it mAh" } ?: UnitFormatter.NO_RECORD)
                    KeyValueLine("标称/设计容量", bh.designCapacityMah?.let { "$it mAh" } ?: UnitFormatter.NO_RECORD)
                    KeyValueLine("记录时间", bh.recordTime ?: UnitFormatter.NO_RECORD)
                }
            }
        }

        // ⑥ 详细充电数据
        item {
            SectionTitle(
                badge = "⑥",
                title = "详细充电数据与实时协议",
                subtitle = "读取 /sys/class/fuelsummary、/sys/class/power_supply/battery 与 PIE 充电表"
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    label = "今日累计充入",
                    value = "${chg.todayTotalChargePct}%",
                    supporting = "共 ${chg.sessions.size} 个充电时段",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "当前电量 / 电压",
                    value = UnitFormatter.formatVoltageV(chg.currentVoltageV),
                    supporting = "电量 ${chg.currentSoc?.let { "$it%" } ?: UnitFormatter.NO_RECORD} · ${chg.currentStatus ?: UnitFormatter.NO_RECORD}",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前充电协议",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = chg.protocolName ?: UnitFormatter.NO_RECORD,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!chg.chargeVoterNote.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        StatusPill(
                            text = chg.chargeVoterNote,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(6.dp))
                    KeyValueLine(
                        "快充状态",
                        chg.fastChargeActive?.let { if (it) "已握手快充" else "未处于快充" } ?: UnitFormatter.NO_RECORD
                    )
                    KeyValueLine("USB / 接口识别类型", chg.usbType ?: UnitFormatter.NO_RECORD)
                    KeyValueLine(
                        "总线输入 VBUS / IBUS",
                        if (chg.vbusV != null || chg.ibusMa != null) {
                            "${UnitFormatter.formatVoltageV(chg.vbusV)} · ${UnitFormatter.formatCurrentMa(chg.ibusMa)}" +
                                (chg.inputPowerW?.let { String.format(Locale.US, " (约 %.1f W)", it) } ?: "")
                        } else UnitFormatter.NO_RECORD
                    )
                    KeyValueLine("电芯实时电流", UnitFormatter.formatCurrentMa(chg.currentMa))
                    KeyValueLine("适配器握手功率 / 额定上限", formatPowerPair(chg.adapterPowerW, chg.adapterPowerMaxW))
                    KeyValueLine(
                        "本机支持协议上限",
                        chg.supportedProtocolsSummary ?: UnitFormatter.formatPowerW(chg.deviceMaxPowerW)
                    )
                    KeyValueLine(
                        "CV 恒压涓流阶段",
                        chg.cvTrickleActive?.let { if (it) "已进入涓流 (CV)" else "未进入" } ?: UnitFormatter.NO_RECORD
                    )
                    KeyValueLine("实时电池温度 / 主板温度", "${UnitFormatter.formatTemp(chg.currentBatteryTempC)} / ${UnitFormatter.formatTemp(chg.currentBoardTempC)}")
                }
            }
        }

        // 充电时段分布
        item {
            Text(
                text = "今日充电时段分布（${chg.sessions.size} 段）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (chg.sessions.isEmpty()) {
            item { EmptyNoticeCard("今日充电时段：${UnitFormatter.NO_RECORD}") }
        } else {
            items(chg.sessions) { s ->
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
                            Text(
                                text = "${s.startTime} – ${s.endTime}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            StatusPill(
                                text = "+${s.chargedPct}% (${s.startSoc}%→${s.endSoc}%)",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "持续 ${UnitFormatter.formatDurationMs(s.durationMs)}  ·  类型 ${s.adapterTypeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val peakLine = listOfNotNull(
                            s.peakVoltageMv?.let { String.format(Locale.US, "峰值电芯 %.3f V", it / 1000.0) },
                            s.peakVbusMv?.let { String.format(Locale.US, "VBUS %.2f V", it / 1000.0) },
                            s.peakIbusMa?.let { "IBUS $it mA" },
                            s.peakPowerW?.let { String.format(Locale.US, "约 %.1f W", it) }
                        ).joinToString(" · ")
                        if (peakLine.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = peakLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class TempFilter(val label: String) {
    ALL("全部"),
    CHARGING("充电阶段"),
    DISCHARGING("放电阶段"),
    HOT("偏高 ≥38℃")
}

@Composable
fun TemperatureScreen(
    snapshot: PowerSnapshot,
    contentPadding: PaddingValues
) {
    val ts = snapshot.temperatureSummary
    var channel by remember { mutableStateOf(TempSensorChannel.BATTERY) }
    var filter by remember { mutableStateOf(TempFilter.ALL) }

    val (chgRange, disRange) = when (channel) {
        TempSensorChannel.BATTERY -> ts.chargeBatteryRange to ts.dischargeBatteryRange
        TempSensorChannel.SHELL -> ts.chargeShellRange to ts.dischargeShellRange
        TempSensorChannel.BOARD -> ts.chargeBoardRange to ts.dischargeBoardRange
    }

    val filteredSamples = remember(ts.samples, channel, filter) {
        ts.samples.filter { s ->
            val v = when (channel) {
                TempSensorChannel.BATTERY -> s.batteryTempC
                TempSensorChannel.SHELL -> s.shellTempC
                TempSensorChannel.BOARD -> s.boardTempC
            }
            when (filter) {
                TempFilter.ALL -> true
                TempFilter.CHARGING -> s.isCharging
                TempFilter.DISCHARGING -> !s.isCharging
                TempFilter.HOT -> (v ?: 0.0) >= 38.0
            }
        }.asReversed() // newest first in table for quick glance
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
            SectionTitle(
                badge = "⑦",
                title = "全天温度采样记录",
                subtitle = "直接展示具体温度数值，每 ~30 秒采样并关联充放电状态与当时前台应用（共 ${ts.samples.size} 条）"
            )
        }

        // Sensor selector
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TempSensorChannel.entries.forEachIndexed { idx, ch ->
                    SegmentedButton(
                        selected = channel == ch,
                        onClick = { channel = ch },
                        shape = SegmentedButtonDefaults.itemShape(idx, TempSensorChannel.entries.size)
                    ) {
                        Text(ch.label)
                    }
                }
            }
        }

        // Charge vs Discharge Range Summary
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    label = "充电阶段${channel.label}区间",
                    value = formatTempRange(chgRange.minC, chgRange.maxC),
                    supporting = "均值 ${UnitFormatter.formatTemp(chgRange.avgC)} · ${chgRange.count} 次采样",
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    valueColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "放电阶段${channel.label}区间",
                    value = formatTempRange(disRange.minC, disRange.maxC),
                    supporting = "均值 ${UnitFormatter.formatTemp(disRange.avgC)} · ${disRange.count} 次采样",
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    valueColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Temperature Curve Chart
        item {
            TemperatureCurveChart(samples = ts.samples, channel = channel)
        }

        // Filter chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TempFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) }
                    )
                }
            }
        }

        // Table header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("时间", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(68.dp))
                    Text("温度", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Text("状态", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(54.dp))
                    Text("前台应用", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
        }

        if (filteredSamples.isEmpty()) {
            item { EmptyNoticeCard("当前筛选条件下：${UnitFormatter.NO_RECORD}") }
        } else {
            items(filteredSamples, key = { it.timestampMs }) { s ->
                val mainTemp = when (channel) {
                    TempSensorChannel.BATTERY -> s.batteryTempC
                    TempSensorChannel.SHELL -> s.shellTempC
                    TempSensorChannel.BOARD -> s.boardTempC
                }
                val isHot = (mainTemp ?: 0.0) >= 38.0
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(68.dp)
                        )
                        Column(modifier = Modifier.width(72.dp)) {
                            Text(
                                text = UnitFormatter.formatTemp(mainTemp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isHot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            val sub = when (channel) {
                                TempSensorChannel.BATTERY -> s.shellTempC?.let { "壳 ${UnitFormatter.formatTempShort(it)}" }
                                TempSensorChannel.SHELL -> s.batteryTempC?.let { "电 ${UnitFormatter.formatTempShort(it)}" }
                                TempSensorChannel.BOARD -> s.batteryTempC?.let { "电 ${UnitFormatter.formatTempShort(it)}" }
                            }
                            if (sub != null) {
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        BoxStatusMini(isCharging = s.isCharging, modifier = Modifier.width(54.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.foregroundAppName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            s.soc?.let {
                                Text(
                                    text = "电量 $it%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxStatusMini(isCharging: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        StatusPill(
            text = if (isCharging) "充电" else "放电",
            containerColor = if (isCharging) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (isCharging) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
fun TrendAndHistoryScreen(
    currentSnapshot: PowerSnapshot,
    viewingHistoryId: Long?,
    trends: List<DailyTrendRecord>,
    historyMetas: List<SnapshotMeta>,
    onSelectSnapshot: (Long) -> Unit,
    onReturnToLive: () -> Unit,
    onDeleteSnapshot: (Long) -> Unit,
    onRenameSnapshot: (Long, String) -> Unit,
    contentPadding: PaddingValues
) {
    var subTab by remember { mutableIntStateOf(0) } // 0 = ③ 趋势对比, 1 = 多次历史快照
    var trendWindowDays by remember { mutableIntStateOf(7) }
    var compareMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var editingMeta by remember { mutableStateOf<SnapshotMeta?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var showCompareDialog by remember { mutableStateOf(false) }

    val last7 = trends.take(7)
    val last30 = trends.take(30)
    val avg7 = last7.takeIf { it.isNotEmpty() }?.map { it.drainRatePctPerHour }?.average()
    val avg30 = last30.takeIf { it.isNotEmpty() }?.map { it.drainRatePctPerHour }?.average()
    val windowList = if (trendWindowDays == 7) last7 else last30

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
                    Text("③ 7天/30天趋势")
                }
                SegmentedButton(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) {
                    Text("历史快照 (${historyMetas.size})")
                }
            }
        }

        if (subTab == 0) {
            item {
                SectionTitle(
                    badge = "③",
                    title = "近 7 天与近 30 天耗电趋势对比",
                    subtitle = "自动融合系统 PIE 缓存 + 本应用永久每日归档（当前累计 ${trends.size} 天记录，随使用持续突破 9 天限制）"
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        label = "今日掉电速率",
                        value = UnitFormatter.formatRatePctPerHour(currentSnapshot.overview.drainRatePctPerHour),
                        supporting = "放出 ${currentSnapshot.overview.totalDrainPct}% · 充入 ${currentSnapshot.chargingDetail.todayTotalChargePct}%",
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "近 7 天日均",
                        value = UnitFormatter.formatRatePctPerHour(avg7),
                        supporting = "实有 ${last7.size} 天记录",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        label = "近 30 天日均",
                        value = UnitFormatter.formatRatePctPerHour(avg30),
                        supporting = "实有 ${last30.size} 天记录",
                        modifier = Modifier.weight(1f)
                    )
                    val delta7 = if (avg7 != null) currentSnapshot.overview.drainRatePctPerHour - avg7 else null
                    MetricCard(
                        label = "今日 vs 7天",
                        value = delta7?.let { UnitFormatter.formatSignedDelta(it, "%/h") } ?: UnitFormatter.NO_RECORD,
                        supporting = when {
                            delta7 == null -> "数据不足"
                            delta7 < -0.2 -> "今日较近期更省电"
                            delta7 > 0.2 -> "今日耗电强于近期平均"
                            else -> "与近期均值基本持平"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = trendWindowDays == 7,
                        onClick = { trendWindowDays = 7 },
                        label = { Text("近 7 天 (${last7.size}天)") }
                    )
                    FilterChip(
                        selected = trendWindowDays == 30,
                        onClick = { trendWindowDays = 30 },
                        label = { Text("近 30 天 (${last30.size}天)") }
                    )
                }
            }

            item {
                DailyTrendChart(records = windowList)
            }

            items(windowList, key = { it.dateStr }) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (d.dateStr == currentSnapshot.dateStr) "${d.dateStr} · 今日" else d.dateStr,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = UnitFormatter.formatRatePctPerHour(d.drainRatePctPerHour),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "放出 ${d.totalDrainPct}% · 充入 ${d.totalChargePct}% · 亮屏 ${UnitFormatter.formatDurationMs(d.screenOnDurationMs)} · 灭屏 ${UnitFormatter.formatDurationMs(d.screenOffDurationMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // 多次历史快照记录 & 对比
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "历史采集快照",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "每次点击「存为历史快照」都会永久保留一份完整静态存档，点击即可回看或勾选任意两条进行对比",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (compareMode && selectedIds.size == 2) {
                                showCompareDialog = true
                            } else {
                                compareMode = !compareMode
                                selectedIds = emptySet()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                !compareMode -> "对比"
                                selectedIds.size == 2 -> "查看对比"
                                else -> "已选 ${selectedIds.size}/2"
                            }
                        )
                    }
                }
            }

            // 当前模式清晰状态条（彻底消除「实时 vs 历史」困惑）
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (viewingHistoryId == null) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (viewingHistoryId == null) {
                                    "● 当前处于「实时数据」模式"
                                } else {
                                    "当前正在回看历史快照（非实时）"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (viewingHistoryId == null) {
                                    "主界面展示系统最新采集值；点下方任意快照可回看当时状态，不会覆盖你的实时数据"
                                } else {
                                    "所有页面展示的都是该快照存档时的静态数据，随时可切回实时"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (viewingHistoryId != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onReturnToLive) {
                                Text("切回实时", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (historyMetas.isEmpty()) {
                item { EmptyNoticeCard("还没有历史快照，在实时模式下点右下角「存为历史快照」即可永久保留一份完整记录") }
            } else {
                items(historyMetas, key = { it.id }) { meta ->
                    val isViewing = viewingHistoryId != null && meta.id == viewingHistoryId
                    val isChecked = meta.id in selectedIds
                    Card(
                        onClick = {
                            if (compareMode) {
                                selectedIds = when {
                                    isChecked -> selectedIds - meta.id
                                    selectedIds.size < 2 -> selectedIds + meta.id
                                    else -> selectedIds
                                }
                            } else {
                                onSelectSnapshot(meta.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isViewing) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (compareMode) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIds = when {
                                            !checked -> selectedIds - meta.id
                                            selectedIds.size < 2 -> selectedIds + meta.id
                                            else -> selectedIds
                                        }
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = meta.capturedTimeLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isViewing) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusPill(
                                            text = "正在回看",
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        )
                                    }
                                }
                                if (meta.note.isNotBlank()) {
                                    Text(
                                        text = "备注：${meta.note}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "放出 ${meta.totalDrainPct}% (${UnitFormatter.formatRatePctPerHour(meta.drainRatePctPerHour)}) · 充入 ${meta.totalChargePct}% · 亮屏 ${UnitFormatter.formatDurationMs(meta.screenOnDurationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "流量 ${UnitFormatter.formatBytes(meta.totalTrafficBytes)} · 电量 ${meta.currentSoc?.let { "$it%" } ?: "--"} · 最高温 充${UnitFormatter.formatTempShort(meta.maxChargeTempC)}/放${UnitFormatter.formatTempShort(meta.maxDischargeTempC)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!compareMode) {
                                Column {
                                    IconButton(onClick = {
                                        editingMeta = meta
                                        noteDraft = meta.note
                                    }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "备注")
                                    }
                                    IconButton(onClick = { onDeleteSnapshot(meta.id) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit note dialog
    editingMeta?.let { target ->
        AlertDialog(
            onDismissRequest = { editingMeta = null },
            title = { Text("编辑快照备注") },
            text = {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text("例如：外出5G重度使用 / 换充电头测试") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSnapshot(target.id, noteDraft.trim())
                    editingMeta = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingMeta = null }) { Text("取消") }
            }
        )
    }

    // Compare 2 snapshots dialog
    if (showCompareDialog && selectedIds.size == 2) {
        val pair = historyMetas.filter { it.id in selectedIds }.sortedBy { it.capturedAtMs }
        if (pair.size == 2) {
            val a = pair[0]
            val b = pair[1]
            AlertDialog(
                onDismissRequest = { showCompareDialog = false },
                title = { Text("双快照指标对比") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "A（较早）：${a.capturedTimeLabel}${if (a.note.isNotBlank()) " · ${a.note}" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "B（较新）：${b.capturedTimeLabel}${if (b.note.isNotBlank()) " · ${b.note}" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        HorizontalDivider()
                        KeyValueLine("总放出电量", "${a.totalDrainPct}% → ${b.totalDrainPct}% (${signedInt(b.totalDrainPct - a.totalDrainPct)}%)")
                        KeyValueLine("综合掉电速率", "${UnitFormatter.formatRatePctPerHour(a.drainRatePctPerHour)} → ${UnitFormatter.formatRatePctPerHour(b.drainRatePctPerHour)}")
                        KeyValueLine("总充入电量", "${a.totalChargePct}% → ${b.totalChargePct}%")
                        KeyValueLine("亮屏时长", "${UnitFormatter.formatDurationMs(a.screenOnDurationMs)} → ${UnitFormatter.formatDurationMs(b.screenOnDurationMs)}")
                        KeyValueLine("今日总流量", "${UnitFormatter.formatBytes(a.totalTrafficBytes)} → ${UnitFormatter.formatBytes(b.totalTrafficBytes)}")
                        KeyValueLine("循环次数", "${a.cycleCount ?: "--"} → ${b.cycleCount ?: "--"}")
                        KeyValueLine("放电最高电池温", "${UnitFormatter.formatTempShort(a.maxDischargeTempC)} → ${UnitFormatter.formatTempShort(b.maxDischargeTempC)}")
                        KeyValueLine("充电最高电池温", "${UnitFormatter.formatTempShort(a.maxChargeTempC)} → ${UnitFormatter.formatTempShort(b.maxChargeTempC)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCompareDialog = false }) { Text("关闭") }
                }
            )
        }
    }
}

private fun formatPowerPair(curW: Double?, maxW: Double?): String {
    if (curW == null && maxW == null) return UnitFormatter.NO_RECORD
    return "${UnitFormatter.formatPowerW(curW)} / ${UnitFormatter.formatPowerW(maxW)}"
}

private fun signedInt(v: Int): String = if (v > 0) "+$v" else "$v"
