package lab.recovery.live

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

internal enum class ExitReason { ANR, CRASH, NATIVE_CRASH, LOW_MEMORY, USER_STOP, OTHER, UNKNOWN }

internal data class ExitSummary(val reason: ExitReason, val timestamp: Long, val importance: Int)

internal fun recentExits(context: Context): List<ExitSummary>? =
    if (Build.VERSION.SDK_INT >= 30) readExitHistory(context) else null

@RequiresApi(30)
private fun readExitHistory(context: Context): List<ExitSummary> {
    val manager = context.getSystemService(ActivityManager::class.java)
    return manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        .distinctBy { Triple(it.pid, it.timestamp, it.reason) }
        .map { info ->
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> ExitReason.ANR
                ApplicationExitInfo.REASON_CRASH -> ExitReason.CRASH
                ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.NATIVE_CRASH
                ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LOW_MEMORY
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.USER_STOP
                ApplicationExitInfo.REASON_UNKNOWN -> ExitReason.UNKNOWN
                else -> ExitReason.OTHER
            }
            ExitSummary(reason, info.timestamp, info.importance)
        }
}
