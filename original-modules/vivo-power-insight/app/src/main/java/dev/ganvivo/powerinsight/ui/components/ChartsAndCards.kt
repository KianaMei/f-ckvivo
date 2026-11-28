package dev.ganvivo.powerinsight.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ganvivo.powerinsight.data.DailyTrendRecord
import dev.ganvivo.powerinsight.data.HourlyPowerItem
import dev.ganvivo.powerinsight.data.TemperatureSample
import dev.ganvivo.powerinsight.util.UnitFormatter
import java.util.Locale
import kotlin.math.max

@Composable
fun SectionTitle(
    badge: String,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    supporting: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!supporting.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun KeyValueLine(
    label: String,
    value: String,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = highlightColor
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun EmptyNoticeCard(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/**
 * ② 今日按小时充放电柱状图（放出 = primary / 充入 = tertiary，支持点击高亮某小时）
 */
@Composable
fun HourlyPowerChart(
    items: List<HourlyPowerItem>,
    selectedHour: Int?,
    onSelectHour: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val dischargeColor = MaterialTheme.colorScheme.primary
    val chargeColor = MaterialTheme.colorScheme.tertiary
    val selectedBg = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    if (items.isEmpty()) {
        EmptyNoticeCard("今日暂无按小时充放电记录")
        return
    }

    val maxPct = max(
        10,
        items.maxOf { max(it.dischargePct, it.chargePct) }
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(dischargeColor, "放出电量%")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(chargeColor, "充入电量%")
                }
                Text(
                    text = "峰值 ${maxPct}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val h = size.height - 8.dp.toPx()
                    val w = size.width
                    // 3 horizontal grid lines
                    for (step in 0..2) {
                        val y = h * step / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                        )
                    }
                    val count = items.size
                    val slotW = w / count
                    val barW = (slotW * 0.36f).coerceIn(3.dp.toPx(), 12.dp.toPx())
                    items.forEachIndexed { idx, item ->
                        val cx = slotW * idx + slotW / 2f
                        if (selectedHour == item.hour) {
                            drawRoundRect(
                                color = selectedBg,
                                topLeft = Offset(slotW * idx + 1.dp.toPx(), 0f),
                                size = Size(slotW - 2.dp.toPx(), h),
                                cornerRadius = CornerRadius(6.dp.toPx())
                            )
                        }
                        if (item.dischargePct > 0) {
                            val bh = (item.dischargePct.toFloat() / maxPct) * h
                            drawRoundRect(
                                color = dischargeColor,
                                topLeft = Offset(cx - barW - 1.dp.toPx(), h - bh),
                                size = Size(barW, bh.coerceAtLeast(3.dp.toPx())),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }
                        if (item.chargePct > 0) {
                            val bh = (item.chargePct.toFloat() / maxPct) * h
                            drawRoundRect(
                                color = chargeColor,
                                topLeft = Offset(cx + 1.dp.toPx(), h - bh),
                                size = Size(barW, bh.coerceAtLeast(3.dp.toPx())),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                val labelStep = if (items.size > 16) 3 else if (items.size > 10) 2 else 1
                items.forEachIndexed { idx, item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onSelectHour(if (selectedHour == item.hour) null else item.hour)
                            }
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val show = idx % labelStep == 0 || idx == items.lastIndex || selectedHour == item.hour
                        Text(
                            text = if (show) String.format("%02d", item.hour) else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selectedHour == item.hour) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedHour == item.hour) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * ③ 近7天 / 近30天 每日掉电速率趋势柱线图
 */
@Composable
fun DailyTrendChart(
    records: List<DailyTrendRecord>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        EmptyNoticeCard("暂无足够历史天数数据，采集后将自动沉淀每日记录")
        return
    }
    val ordered = records.reversed() // chronological left -> right
    val barColor = MaterialTheme.colorScheme.primary
    val avgLineColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val avgRate = ordered.map { it.drainRatePctPerHour }.average()
    val maxRate = max(2.0, ordered.maxOf { it.drainRatePctPerHour } * 1.15)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(barColor, "日均掉电速率 (%/h)")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(avgLineColor, String.format(Locale.US, "均值 %.2f", avgRate))
                }
                Text(
                    text = "${ordered.size} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) {
                val h = size.height - 6.dp.toPx()
                val w = size.width
                for (step in 0..2) {
                    val y = h * step / 2f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                }

                val slotW = w / ordered.size
                val barW = (slotW * 0.56f).coerceIn(4.dp.toPx(), 24.dp.toPx())
                ordered.forEachIndexed { idx, r ->
                    val ratio = (r.drainRatePctPerHour / maxRate).toFloat().coerceIn(0f, 1f)
                    val bh = ratio * h
                    val left = slotW * idx + (slotW - barW) / 2f
                    drawRoundRect(
                        color = barColor.copy(alpha = if (idx == ordered.lastIndex) 1f else 0.72f),
                        topLeft = Offset(left, h - bh),
                        size = Size(barW, bh.coerceAtLeast(3.dp.toPx())),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                }

                // Average reference dashed line
                val avgY = h - (avgRate / maxRate).toFloat().coerceIn(0f, 1f) * h
                drawLine(
                    color = avgLineColor,
                    start = Offset(0f, avgY),
                    end = Offset(w, avgY),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                val step = if (ordered.size > 14) 5 else if (ordered.size > 8) 2 else 1
                ordered.forEachIndexed { idx, r ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val show = idx % step == 0 || idx == ordered.lastIndex
                        Text(
                            text = if (show) r.dateStr.takeLast(5) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

enum class TempSensorChannel(val label: String) {
    BATTERY("电池温度"),
    SHELL("机身壳温"),
    BOARD("主板温度")
}

/**
 * ⑦ 全天温度采样曲线图（充电点用 tertiary，放电点用 primary，标出 40℃ 高温参考线）
 */
@Composable
fun TemperatureCurveChart(
    samples: List<TemperatureSample>,
    channel: TempSensorChannel,
    modifier: Modifier = Modifier
) {
    val points = samples.mapNotNull { s ->
        val v = when (channel) {
            TempSensorChannel.BATTERY -> s.batteryTempC
            TempSensorChannel.SHELL -> s.shellTempC
            TempSensorChannel.BOARD -> s.boardTempC
        }
        if (v != null) Triple(s, v, s.isCharging) else null
    }

    if (points.size < 2) {
        EmptyNoticeCard("${channel.label}：${UnitFormatter.NO_RECORD}")
        return
    }

    val minVal = (points.minOf { it.second } - 2.0).coerceAtLeast(0.0)
    val maxVal = max(points.maxOf { it.second } + 2.0, minVal + 6.0)
    val disColor = MaterialTheme.colorScheme.primary
    val chgColor = MaterialTheme.colorScheme.tertiary
    val hotColor = MaterialTheme.colorScheme.error
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    // Downsample slightly for crisp rendering if > 240 points while keeping peaks
    val renderPts = if (points.size > 240) {
        val stride = points.size / 200
        points.filterIndexed { i, _ -> i % stride == 0 || i == points.lastIndex }
    } else points

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(chgColor, "充电段")
                    Spacer(Modifier.width(12.dp))
                    LegendDot(disColor, "放电段")
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "%.1f ~ %.1f ℃",
                        points.minOf { it.second },
                        points.maxOf { it.second }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val h = size.height - 8.dp.toPx()
                val w = size.width
                for (step in 0..2) {
                    val y = h * step / 2f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    )
                }

                // 40℃ warning reference line if in range
                if (40.0 in minVal..maxVal) {
                    val y40 = h - ((40.0 - minVal) / (maxVal - minVal)).toFloat() * h
                    drawLine(
                        color = hotColor.copy(alpha = 0.65f),
                        start = Offset(0f, y40),
                        end = Offset(w, y40),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                    )
                }

                val tMin = renderPts.first().first.timestampMs.toDouble()
                val tSpan = max(1.0, renderPts.last().first.timestampMs.toDouble() - tMin)

                fun ptOffset(idx: Int): Offset {
                    val (s, v, _) = renderPts[idx]
                    val x = (((s.timestampMs - tMin) / tSpan) * w).toFloat()
                    val y = h - (((v - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f) * h)
                    return Offset(x, y)
                }

                val strokeW = 2.4.dp.toPx()
                for (i in 1 until renderPts.size) {
                    val p0 = ptOffset(i - 1)
                    val p1 = ptOffset(i)
                    val segColor = if (renderPts[i].third) chgColor else disColor
                    drawLine(
                        color = segColor,
                        start = p0,
                        end = p1,
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = renderPts.first().first.timeLabel.take(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = renderPts[renderPts.size / 2].first.timeLabel.take(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = renderPts.last().first.timeLabel.take(5),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SocGaugeRing(
    soc: Int?,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isCharging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val sweep = ((soc ?: 0).coerceIn(0, 100) / 100f) * 280f

    Box(modifier = modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = 130f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = activeColor,
                startAngle = 130f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = soc?.let { "$it%" } ?: "--",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (isCharging) "充电中" else "电量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
