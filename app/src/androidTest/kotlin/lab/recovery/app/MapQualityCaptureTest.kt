package lab.recovery.app

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MapQualityCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun capturePortraitMaps() {
        val run = InstrumentationRegistry.getArguments().getString("mapQualityCapture")
        assumeTrue("Set mapQualityCapture to representatives or all", run in setOf("representatives", "all"))
        val model = ViewModelProvider(rule.activity)[LabViewModel::class.java]
        val scenes = listOf(
            Scene("M01", "L01", 11_000), Scene("M02", "L02", 19_000), Scene("M03", "L07", 51_000),
            Scene("M04", "L10", 38_000), Scene("M05", "L13", 35_000), Scene("M06", "L08", 39_000),
            Scene("M07", "L03", 29_000), Scene("M08", "L09", 44_000), Scene("M09", "L15", 32_000),
            Scene("M10", "DEMO_LONG", 29_000),
        )
        for (scene in scenes.filter { run == "all" || it.map in setOf("M01", "M10") }) {
            scroll("scenario-menu")
            rule.onNodeWithTag("scenario-menu").performClick()
            rule.onNodeWithTag("scenario-${scene.scenario}").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                model.state.value.playback == Playback.READY && model.state.value.scenario?.id == scene.scenario &&
                    rule.onAllNodesWithTag("camera-mode").fetchSemanticsNodes(false).isNotEmpty()
            }
            val detailed = scene.map in setOf("M01", "M10")
            if (detailed) capture("${scene.map}-initial")
            stepTo(model, scene.time)
            capture("${scene.map}-follow")
            rule.onNodeWithTag("overview").performClick()
            capture("${scene.map}-overview")
            rule.onNodeWithTag("recenter").performClick()
            if (detailed || scene.map == "M06") {
                scroll("map-theme")
                rule.onNodeWithTag("map-theme").performClick()
                capture("${scene.map}-night")
                rule.onNodeWithTag("map-theme").performClick()
            }
            if (scene.map == "M10") {
                val events = listOf(39_000L to "jump", 87_000L to "tunnel", 98_000L to "limit",
                    111_000L to "recovery", 146_000L to "branch")
                for ((time, label) in events) {
                    stepTo(model, time)
                    capture("M10-$label")
                }
            }
        }
    }

    private fun scroll(tag: String) {
        rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag(tag))
    }

    private fun stepTo(model: LabViewModel, time: Long) {
        val state = model.state.value
        val first = if (state.playback == Playback.READY) 0L else state.snapshot.nowMs + 200L
        for (batch in (first..time step 200).chunked(90)) {
            repeat(batch.size) { model.send(UiIntent.Step) }
            rule.waitUntil(10_000) { model.state.value.snapshot.nowMs == batch.last() }
        }
        assertTrue(model.state.value.playback == Playback.PAUSED)
        val expectedTime = String.format(Locale.ROOT, "%02d:%04.1f", time / 60_000, time % 60_000 / 1000.0)
        rule.waitUntil(10_000) {
            rule.onNodeWithTag("virtual-time").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.startsWith(expectedTime) == true
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val committed = CountDownLatch(1)
            val activity = rule.activity
            instrumentation.runOnMainSync {
                val view = activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                view.invalidate()
            }
            assertTrue("Wait for pixels to reach the compositor", committed.await(3, TimeUnit.SECONDS))
        }
        val bitmap = checkNotNull(automation.takeScreenshot())
        val folder = checkNotNull(rule.activity.getExternalFilesDir("map-quality"))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class Scene(val map: String, val scenario: String, val time: Long)
}
