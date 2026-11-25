package lab.recovery.app

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import lab.recovery.location.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhysicalOsmEnduranceTest {
    @Test fun replaysThirtyMinutesOfO10AtOneTimes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalEndurance") == "true")
        assumeTrue(Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertEquals("0", device.executeShellCommand("settings get global wifi_on").trim())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: LabViewModel
            scenario.onActivity {
                it.window.addFlags(FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(it)[LabViewModel::class.java]
            }
            await(20_000) {
                model.state.value.scenario?.id == "ODEMO_LONG" && model.state.value.playback == Playback.READY
            }
            assertTrue("O10 has not rendered", device.wait(Until.hasObject(By.res("camera-mode")), 10_000))
            assertEquals(180_000L, model.state.value.scenario?.durationMs)
            assertEquals(1f, model.state.value.speed, 0f)
            var elapsedTotal = 0L

            repeat(10) { index ->
                val cycle = index + 1
                val id = model.state.value.runId
                note(cycle, "ready", model)
                val start = SystemClock.elapsedRealtime()
                model.send(UiIntent.PlayPause)
                var nextSample = start + 45_000
                await(195_000) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextSample) {
                        note(cycle, "playing", model)
                        nextSample = now + 45_000
                    }
                    model.state.value.evaluation != null
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                val finished = model.state.value
                assertTrue("Cycle $cycle wall time at 1×: $elapsed", elapsed in 179_000..195_000)
                assertEquals(id, finished.runId)
                assertEquals(180_000L, finished.snapshot.nowMs)
                assertEquals(1, finished.logs.count { it.reason == Reason.RUN_COMPLETED })
                assertTrue(finished.logs.size <= 500)
                assertTrue(finished.trails.groupBy { it.source }.values.all { group -> group.sumOf { it.points.size } <= 1000 })
                elapsedTotal += elapsed
                note(cycle, "completed", model)
                model.send(UiIntent.Reset)
                await(15_000) { model.state.value.runId > id && model.state.value.playback == Playback.READY }
                val reset = model.state.value
                assertEquals(0L, reset.snapshot.nowMs)
                assertTrue(reset.logs.isEmpty() && reset.trails.isEmpty())
                assertNull(reset.evaluation)
                note(cycle, "reset", model)
            }
            Log.i(TAG, "totalReplayWallMs=$elapsedTotal cycles=10")
            SystemClock.sleep(60_000)
            note(10, "idle60s", model)
            SystemClock.sleep(60_000)
            note(10, "idle120s", model)
        }
    }

    private fun note(cycle: Int, phase: String, model: LabViewModel) {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val state = model.state.value
        val runtime = Runtime.getRuntime()
        val javaUsedKiB = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        Log.i(TAG, "cycle=$cycle phase=$phase virtualMs=${state.snapshot.nowMs} totalPssKiB=${memory.totalPss} " +
            "javaPssKiB=${memory.dalvikPss} nativePssKiB=${memory.nativePss} " +
            "javaUsedKiB=$javaUsedKiB nativeAllocatedKiB=${Debug.getNativeHeapAllocatedSize() / 1024} " +
            "graphicsKiB=${memory.getMemoryStat("summary.graphics")} backlogMs=${state.replay.backlogMs} " +
            "logs=${state.logs.size} trail=${state.trails.sumOf { it.points.size }}")
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Replay did not settle within ${timeoutMs}ms")
    }

    private companion object { const val TAG = "PhysicalOsmEndurance" }
}
