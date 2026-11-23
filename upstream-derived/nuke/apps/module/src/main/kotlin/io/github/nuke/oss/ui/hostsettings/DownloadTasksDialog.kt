package io.github.nuke.oss.ui.hostsettings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.media.DownloadConsoles
import io.github.nuke.oss.app.shared.media.DownloadTask
import io.github.nuke.oss.app.shared.media.TaskStage
import io.github.nuke.oss.app.shared.media.TaskState
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.tiktok.feature.feed.DownloadVideoWithName
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import kotlinx.coroutines.delay

internal const val DOWNLOAD_TASKS = "DownloadVideoWithName"

// What the queue is doing, and where the files go: the gallery folders by default, or a SAF folder the user picks here.
// The queue lives in the host process, so this reads it directly; nothing here touches the network or the gallery.
@Composable
internal fun DownloadTasksDialog(
    feature: HostFeature,
    title: String,
    onSaveOptions: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val console = DownloadConsoles.current()
    var tasks by remember { mutableStateOf(console?.snapshot().orEmpty()) }
    LaunchedEffect(console) {
        while (true) {
            tasks = console?.snapshot().orEmpty()
            delay(POLL_MILLIS)
        }
    }
    val tree = feature.options.firstOrNull { it.option.key == DownloadVideoWithName.TREE }?.saved as? String
    val saved = { feature.options.associate { it.option.key to it.saved } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        onSaveOptions(saved() + (DownloadVideoWithName.TREE to uri.toString()))
    }
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        actions = {
            NukeDialogTextButton(stringResource(R.string.host_download_pick_folder), { picker.launch(null) }, Modifier.weight(1f))
            NukeDialogTextButton(stringResource(R.string.host_download_clear), { console?.clearFinished() }, Modifier.weight(1f))
            NukeDialogTextButton(stringResource(R.string.manager_close), onDismiss, Modifier.weight(1f))
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionsBodyText(
                if (tree.isNullOrBlank()) stringResource(R.string.host_download_folder_default)
                else stringResource(R.string.host_download_folder_picked, tree),
            )
            if (!tree.isNullOrBlank()) {
                NukeDialogTextButton(
                    stringResource(R.string.host_download_folder_reset),
                    { onSaveOptions(saved() + (DownloadVideoWithName.TREE to "")) },
                )
            }
            if (tasks.isEmpty()) {
                OptionsBodyText(stringResource(R.string.host_download_empty))
                return@Column
            }
            for (task in tasks.asReversed()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        OptionsBodyText(task.name.ifBlank { task.id.toString() })
                        OptionsBodyText(state(task))
                    }
                    if (task.state == TaskState.QUEUED || task.state == TaskState.RUNNING) {
                        NukeDialogTextButton(stringResource(R.string.host_download_cancel), { console?.cancel(task.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun state(task: DownloadTask): String = when (task.state) {
    TaskState.QUEUED -> stringResource(R.string.host_download_queued)
    TaskState.RUNNING -> stringResource(R.string.host_download_running, (task.fraction * 100).toInt())
    TaskState.SUCCEEDED -> stringResource(R.string.host_download_succeeded)
    TaskState.CANCELLED -> stringResource(R.string.host_download_cancelled)
    TaskState.FAILED -> stringResource(
        R.string.host_download_failed,
        stringResource(
            when (task.stage) {
                TaskStage.RESOLVE -> R.string.host_download_stage_resolve
                TaskStage.SAVE -> R.string.host_download_stage_save
                else -> R.string.host_download_stage_download
            },
        ) + ": " + task.reason.orEmpty(),
    )
}

private const val POLL_MILLIS = 500L
