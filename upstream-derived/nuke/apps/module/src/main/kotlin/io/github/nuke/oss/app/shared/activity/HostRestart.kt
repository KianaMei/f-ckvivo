package io.github.nuke.oss.app.shared.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper

// Relaunches the host on its own entry Activity and ends this process. The process must end right after the launch
// request returns, in the same main-thread task: the system keeps an Activity it has not started yet and starts it in a
// new process once this one dies, but an Activity that already resumed here has no saved state and is dropped with the
// process, so the host would just close. The pause comes before the request instead, giving writes queued in the
// background a moment to finish. If the request fails the process still ends and the user opens the host again.
internal fun restartHost(activity: Activity, onLaunchFailed: (RuntimeException) -> Unit = {}) {
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                ?: throw ActivityNotFoundException("No launch intent for ${activity.packageName}")
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (error: ActivityNotFoundException) {
            onLaunchFailed(error)
        } catch (error: SecurityException) {
            onLaunchFailed(error)
        }
        processExit.exit()
    }, SETTLE_DELAY_MILLIS)
}

private const val SETTLE_DELAY_MILLIS = 300L
