package dev.ganvivo.powerinsight.util

import java.util.Locale
import kotlin.math.abs

/**
 * 严格遵循用户约定的常用单位格式化规则：
 * - 时长：不超过60分钟用分钟，超过60分钟用小时并保留2位小数，超过24小时用天并保留2位小数
 * - 流量：不足1MB用KB，达到1MB用MB，达到1GB用GB，均保留2位小数
 * - 缺失数据：统一返回“该项暂无记录”，绝不编造数字
 */
object UnitFormatter {
    const val NO_RECORD = "该项暂无记录"

    fun formatDurationMs(ms: Long?): String {
        if (ms == null || ms < 0L) return NO_RECORD
        val minutes = ms / 60_000.0
        return formatDurationMinutes(minutes)
    }

    fun formatDurationMinutes(minutes: Double?): String {
        if (minutes == null || minutes.isNaN() || minutes < 0.0) return NO_RECORD
        return when {
            minutes < 60.0 -> String.format(Locale.US, "%.2f 分钟", minutes)
            minutes < 1440.0 -> String.format(Locale.US, "%.2f 小时", minutes / 60.0)
            else -> String.format(Locale.US, "%.2f 天", minutes / 1440.0)
        }
    }

    fun formatDurationCompactMs(ms: Long?): String {
        if (ms == null || ms < 0L) return "--"
        if (ms == 0L) return "0 分钟"
        return formatDurationMinutes(ms / 60_000.0)
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return NO_RECORD
        val kb = bytes / 1024.0
        val mb = bytes / (1024.0 * 1024.0)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return when {
            mb < 1.0 -> String.format(Locale.US, "%.2f KB", kb)
            gb < 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            else -> String.format(Locale.US, "%.2f GB", gb)
        }
    }

    fun formatTemp(tempC: Double?): String {
        if (tempC == null || tempC.isNaN() || tempC < -40.0 || tempC > 100.0) return NO_RECORD
        return String.format(Locale.US, "%.1f ℃", tempC)
    }

    fun formatTempShort(tempC: Double?): String {
        if (tempC == null || tempC.isNaN() || tempC < -40.0 || tempC > 100.0) return "--"
        return String.format(Locale.US, "%.1f℃", tempC)
    }

    fun formatVoltageV(volts: Double?): String {
        if (volts == null || volts.isNaN() || volts <= 0.0) return NO_RECORD
        return String.format(Locale.US, "%.3f V", volts)
    }

    fun formatCurrentMa(ma: Double?): String {
        if (ma == null || ma.isNaN()) return NO_RECORD
        return String.format(Locale.US, "%.1f mA", ma)
    }

    fun formatRatePctPerHour(rate: Double?): String {
        if (rate == null || rate.isNaN()) return NO_RECORD
        return String.format(Locale.US, "%.2f %%/h", rate)
    }

    fun formatPowerW(watts: Double?): String {
        if (watts == null || watts.isNaN() || watts <= 0.0) return NO_RECORD
        return String.format(Locale.US, "%.1f W", watts)
    }

    fun formatSignedDelta(delta: Double, unit: String): String {
        val sign = if (delta > 0.005) "+" else if (delta < -0.005) "-" else ""
        return String.format(Locale.US, "%s%.2f %s", sign, abs(delta), unit).trim()
    }
}
