package lab.recovery.live

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.UiDevice

internal fun <T> withPhysicalTestCleanup(
    cleanup: PhysicalTestCleanup.() -> Unit,
    block: () -> T,
): T {
    var testFailure: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        testFailure = failure
        throw failure
    } finally {
        val steps = PhysicalTestCleanup(testFailure)
        steps.attempt("run test cleanup") { steps.cleanup() }
        steps.throwCleanupFailureIfTestPassed()
    }
}

internal class PhysicalTestCleanup(private val testFailure: Throwable?) {
    val testFailed get() = testFailure != null
    private var firstFailure = testFailure

    fun attempt(name: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            Log.e("PhysicalLocation", "Cleanup failed: $name", failure)
            val existing = firstFailure
            if (existing == null) firstFailure = failure
            else if (existing !== failure) existing.addSuppressed(failure)
        }
    }

    fun throwCleanupFailureIfTestPassed() {
        if (testFailure == null) firstFailure?.let { throw it }
    }
}

internal fun <T> runPhysicalLiveSession(
    resources: PhysicalLiveResources,
    start: () -> T,
    verify: (T) -> Unit,
    stopViaUi: () -> Long,
): Long = withPhysicalTestCleanup(cleanup = {
    if (testFailed) resources.stopAfterFailure(this)
}) {
    verify(start())
    stopViaUi()
}

internal class PhysicalLiveResources(private val context: Context, private val device: UiDevice) {
    fun foreground(): Boolean = serviceDump().contains("isForeground=true")

    fun hasService(): Boolean = serviceDump().let {
        it.contains(LiveTrackingService::class.java.simpleName) || it.contains("isForeground=true")
    }

    private fun serviceDump(): String =
        device.executeShellCommand("dumpsys activity services ${context.packageName}")

    fun registered(): Boolean {
        // The Samsung dump also repeats requests beneath dated history groups.
        // Read only the current GPS provider's immediate listener rows and never write the dump.
        val rows = device.executeShellCommand("dumpsys location").lines()
        val provider = rows.indexOfFirst { it.trim() == "gps provider:" }
        check(provider >= 0) { "GPS provider section missing" }
        fun indentation(line: String) = line.length - line.trimStart().length
        val section = rows.drop(provider + 1).takeWhile {
            it.isBlank() || indentation(it) > indentation(rows[provider])
        }
        val listeners = section.indexOfFirst { it.trim() == "listeners:" }
        if (listeners < 0) return false
        return section.drop(listeners + 1)
            .takeWhile { it.isBlank() || indentation(it) > indentation(section[listeners]) }
            .any {
                it.contains(context.packageName) &&
                    (it.contains("Request[") || it.contains("Request{")) && !it.trimStart().startsWith("[")
            }
    }

    fun stopAfterFailure(cleanup: PhysicalTestCleanup) {
        Log.w("PhysicalLocation", "Stopping Live service after a test failure")
        cleanup.attempt("wait for foreground service promotion before stopping") {
            // Android still enforces startForegroundService's promotion deadline if stopped too early.
            await("Live foreground startup did not settle") {
                LiveSessions.updates.value.phase != SessionPhase.STARTING || foreground()
            }
        }
        cleanup.attempt("request Live service stop") {
            context.stopService(Intent(context, LiveTrackingService::class.java))
        }
        cleanup.attempt("verify Live service and GPS listener release") {
            await("Abnormal Live cleanup did not release the service and GPS listener") {
                !hasService() && !registered()
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error(message)
    }
}
