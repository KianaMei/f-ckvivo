package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsReport
import io.github.nuke.oss.app.tiktok.feature.analytics.AnalyticsSummary
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LOCAL_ANALYTICS = "LocalAnalytics"

// Reads what the local analysis database already holds. Nothing here depends on the host: the numbers come from the
// file the feature writes in the host's private storage, so the dialog works while the feature is off.
@Composable
internal fun AnalyticsDialog(directory: File?, title: String, onDismiss: () -> Unit) {
    var summary by remember { mutableStateOf<AnalyticsSummary?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(directory) {
        summary = withContext(Dispatchers.IO) {
            directory?.let { AnalyticsReport.read(File(it, DATABASE), ZoneId.systemDefault()) }
        }
        loaded = true
    }
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        actions = {
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
                }
            }
        }
    }
}

// h:mm, so a long day stays readable without a resource per unit.
private fun duration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return "%d:%02d".format(minutes / 60, minutes % 60)
}

private const val DATABASE = "analytics.db"
