package io.github.nuke.oss.app.shared.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.R

// A save runs on a background thread, so without something in the shade a sticker that takes a while and one that
// failed look the same: nothing happens. This posts one notification per save, moves its progress bar as bytes arrive,
// and leaves the outcome in the shade; the permission check keeps it silent when the host cannot post notifications.
internal class SaveNotification(private val context: Context) {
    // The strings live in the module's own package: the host context has no id for them, and getString on it throws.
    private val module = runCatching { context.createPackageContext(BuildConfig.APPLICATION_ID, 0) }.getOrNull()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val shown = manager?.let { runCatching { it.areNotificationsEnabled() }.getOrDefault(false) } ?: false

    fun text(id: Int, vararg args: Any): String =
        runCatching { module?.getString(id, *args) }.getOrNull().orEmpty()

    fun start(title: Int) {
        val target = manager?.takeIf { shown } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(CHANNEL, text(R.string.host_notice_media_channel), NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                target.createNotificationChannel(channel)
            }
            target.notify(ID, builder().setContentTitle(text(title)).setProgress(0, 0, true).build())
        }
    }

    fun progress(percent: Int) {
        val target = manager?.takeIf { shown } ?: return
        runCatching { target.notify(ID, builder().setProgress(100, percent.coerceIn(0, 100), false).build()) }
    }

    // Download finished but the sticker still has to be turned into a GIF, which takes a moment of its own.
    fun converting(message: String) {
        val target = manager?.takeIf { shown } ?: return
        runCatching { target.notify(ID, builder().setProgress(0, 0, true).setContentText(message).build()) }
    }

    fun done(message: String) {
        val target = manager?.takeIf { shown } ?: return
        runCatching { target.notify(ID, builder().setProgress(0, 0, false).setContentText(message).build()) }
    }

    fun fail(message: String) {
        done(message)
    }

    private fun builder(): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
    }

    private companion object {
        const val CHANNEL = "nuke_media"
        const val ID = 1200
    }
}
