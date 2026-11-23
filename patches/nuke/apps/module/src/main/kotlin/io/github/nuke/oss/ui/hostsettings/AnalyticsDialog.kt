package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsReport
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LOCAL_ANALYTICS = "LocalAnalytics"

// Reads what the local analysis database already holds. Nothing here depends on the host: the numbers come from the
// file the feature writes in the host's private storage, so the dialog works while the feature is off.
@Composable
internal fun AnalyticsDialog(directory: File?, title: String, onDismiss: () -> Unit) {
    var summary by remember { mutableStateOf<AnalyticsSummary?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(directory) {
        summary = withContext(Dispatchers.IO) {
            directory?.let { AnalyticsReport.read(File(it, DATABASE), ZoneId.systemDefault()) }
        }
        loaded = true
    }
    // Built during composition, where resources are available; the export action only reads them.
    val exportLines = summary?.let { reportLines(it) }.orEmpty()
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        actions = {
            NukeDialogTextButton(
                stringResource(R.string.host_analytics_export),
                {
                    if (exportLines.isEmpty()) return@NukeDialogTextButton
                    scope.launch {
                        val name = "analytics-" + System.currentTimeMillis()
                        exported = withContext(Dispatchers.IO) {
                            AnalyticsImageExport.export(context, title, exportLines, name)
                        } ?: ""
                    }
                },
                Modifier.weight(1f),
            )
            NukeDialogTextButton(stringResource(R.string.host_analytics_close), onDismiss, Modifier.weight(1f))
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val report = summary
            when {
                !loaded -> OptionsBodyText(stringResource(R.string.host_analytics_loading))
                report == null || !report.hasData -> OptionsBodyText(stringResource(R.string.host_analytics_empty))
                else -> {
                    OptionsBodyText(stringResource(R.string.host_analytics_today, report.todayViews, duration(report.todayVisibleMs)))
                    OptionsBodyText(stringResource(R.string.host_analytics_week, report.weekViews, duration(report.weekVisibleMs)))
                    OptionsBodyText(stringResource(R.string.host_analytics_authors, report.authors))
                    if (report.hours.isNotEmpty()) {
                        OptionsBodyText(stringResource(R.string.host_analytics_hours))
                        OptionsBodyText(report.hours.joinToString("   ") { (hour, views) -> "%02d:00  ×%d".format(hour, views) })
                    }
                    exported?.let { path ->
                        OptionsBodyText(
                            if (path.isEmpty()) stringResource(R.string.host_analytics_export_failed)
                            else stringResource(R.string.host_analytics_exported, path),
                        )
                    }
                }
            }
        }
    }
}

// The same numbers the dialog shows, as plain lines for the image.
@Composable
private fun reportLines(report: AnalyticsSummary): List<String> = buildList {
    add(stringResource(R.string.host_analytics_today, report.todayViews, duration(report.todayVisibleMs)))
    add(stringResource(R.string.host_analytics_week, report.weekViews, duration(report.weekVisibleMs)))
    add(stringResource(R.string.host_analytics_authors, report.authors))
    if (report.hours.isNotEmpty()) {
        add(stringResource(R.string.host_analytics_hours))
        add(report.hours.joinToString("   ") { (hour, views) -> "%02d:00  x%d".format(hour, views) })
    }
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    for (day in report.dayRows()) {
        add(format.format(Instant.ofEpochMilli(day.day * 86_400_000L).atZone(ZoneId.of("UTC")).toLocalDate()) + "   " + duration(day.visibleMs))
    }
}

// h:mm, so a long day stays readable without a resource per unit.
private fun duration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return "%d:%02d".format(minutes / 60, minutes % 60)
}

private const val DATABASE = "analytics.db"
