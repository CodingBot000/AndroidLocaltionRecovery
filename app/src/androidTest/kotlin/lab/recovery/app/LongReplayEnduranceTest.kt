package lab.recovery.app

import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@LargeTest
class LongReplayEnduranceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun fullLengthAtOneTimesOfflineAndReset() {
        val model = ViewModelProvider(rule.activity)[LabViewModel::class.java]
        rule.waitUntil(15_000) {
            model.state.value.playback == Playback.READY && model.state.value.choices.any { it.id == "DEMO_LONG" }
        }
        model.send(UiIntent.Select(model.state.value.choices.first { it.id == "DEMO_LONG" }))
        rule.waitUntil(10_000) {
            model.state.value.playback == Playback.READY && model.state.value.scenario?.id == "DEMO_LONG"
        }
        val duration = checkNotNull(model.state.value.scenario).durationMs
        assertEquals("DEMO_LONG", model.state.value.selected.id)
        assertEquals(1f, model.state.value.speed, 0f)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun setting(name: String): String =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("settings get global $name")).bufferedReader().use { it.readText().trim() }
        assertEquals("1", setting("airplane_mode_on"))
        assertEquals("0", setting("wifi_on"))

        fun note(phase: String) {
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val state = model.state.value
            Log.i("RecoveryEndurance", "$phase timeMs=${state.snapshot.nowMs} totalPssKiB=${memory.totalPss} " +
                "backlogMs=${state.replay.backlogMs} logs=${state.logs.size} trail=${state.trails.sumOf { it.points.size }}")
        }
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("captureMaps") != "true") return
            rule.waitForIdle()
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val directory = checkNotNull(rule.activity.getExternalFilesDir("maps"))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        note("ready")
        val start = SystemClock.elapsedRealtime()
        model.send(UiIntent.PlayPause)
        rule.waitUntil(96_000) {
            model.state.value.snapshot.nowMs >= 87_000 &&
                model.state.value.snapshot.source == PositionSource.DEAD_RECKONED
        }
        note("estimated")
        capture("long-follow-dr")
        rule.waitUntil(20_000) { model.state.value.snapshot.drUnavailable == Reason.DR_LIMIT }
        note("limit")
        capture("long-follow-limit")
        rule.waitUntil(duration / 2 + 15_000) { model.state.value.evaluation != null }
        val elapsed = SystemClock.elapsedRealtime() - start
        val finished = model.state.value
        assertEquals(duration, finished.snapshot.nowMs)
        assertTrue("elapsed versus virtual time: $elapsed", elapsed in duration - 1000..duration + 15_000)
        assertEquals(1, finished.logs.count { it.reason == Reason.RUN_COMPLETED })
        assertEquals(157, finished.evaluation!!.observations)
        assertTrue(finished.logs.size <= 500)
        assertTrue(finished.trails.groupBy { it.source }.values.all { group -> group.sumOf { it.points.size } <= 1000 })
        Log.i("RecoveryEndurance", "completed in ${elapsed} ms at 1×")
        note("complete")

        repeat(3) {
            val run = model.state.value.runId
            model.send(UiIntent.Reset)
            rule.waitUntil(5_000) { model.state.value.runId > run && model.state.value.playback == Playback.READY }
            assertTrue(model.state.value.logs.isEmpty())
            assertTrue(model.state.value.trails.isEmpty())
            assertNull(model.state.value.evaluation)
            model.send(UiIntent.PlayPause)
            rule.waitUntil(5_000) { model.state.value.snapshot.nowMs >= 400L }
            model.send(UiIntent.PlayPause)
            rule.waitUntil(5_000) { model.state.value.playback == Playback.PAUSED }
        }
        note("after-three-resets")
        rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag("overview"))
        rule.onNodeWithTag("overview").performClick()
    }
}
