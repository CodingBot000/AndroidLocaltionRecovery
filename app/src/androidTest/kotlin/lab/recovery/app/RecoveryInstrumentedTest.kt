package lab.recovery.app

import lab.recovery.feature.*

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import lab.recovery.simulation.ScenarioRepository
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import lab.recovery.simulation.FixtureReader
import lab.recovery.simulation.ReplaySession
import lab.recovery.simulation.playToEnd
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecoveryInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun model() = ViewModelProvider(rule.activity)[LabViewModel::class.java]
    private fun ready(model: LabViewModel, scenario: String) = rule.waitUntil(10_000) {
        model.state.value.playback == Playback.READY && model.state.value.scenario?.id == scenario
    }
    private fun scroll(tag: String) {
        rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag(tag))
    }

    private fun click(tag: String) {
        scroll(tag)
        rule.onNodeWithTag(tag).performClick()
    }

    private fun select(id: String) {
        click("scenario-menu")
        rule.onNodeWithTag("scenario-$id").performScrollTo().performClick()
    }
    private fun stepTo(model: LabViewModel, time: Long) {
        val first = if (model.state.value.playback == Playback.READY) 0 else model.state.value.snapshot.nowMs + 200
        for (tick in first..time step 200) model.send(UiIntent.Step)
        rule.waitUntil(10_000) { model.state.value.snapshot.nowMs == time }
    }

    @Test fun legacyDemoCompletesAndMatchesIndependentJvmReplay() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        scroll("tracking-map")
        rule.onNodeWithTag("tracking-map").assertIsDisplayed()
        rule.onNodeWithTag("tunnel-legend")
            .assertIsDisplayed()
            .assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.map_tunnel))
        setSpeed(model, 5f)
        click("play-pause")
        rule.waitUntil(15_000) { model.state.value.evaluation != null }
        val expected = runBlocking {
            val scenario = FixtureReader.read(rule.activity.assets.open("development/DEMO.json"))
            playToEnd(ReplaySession(scenario, runId = 42))
        }
        val actual = checkNotNull(model.state.value.evaluation)
        assertEquals(expected, actual)
        assertTrue(model.state.value.logs.any { it.reason == Reason.IMPLAUSIBLE_MOVE })
        assertTrue(model.state.value.logs.any { it.reason == Reason.SIGNAL_RECOVERED })
        assertTrue(model.state.value.logs.any { it.reason == Reason.REROUTE_RECOMMENDED })
        rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag("evaluation"))
        rule.onNodeWithTag("evaluation").assertIsDisplayed()
    }

    @Test fun scenarioDescriptionChangesBeforePlayback() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        rule.onNodeWithTag("scenario-description")
            .assertIsDisplayed()
            .assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.scenario_demo_description))
        select("S11")
        ready(model, "S11")
        rule.onNodeWithTag("scenario-description")
            .assertIsDisplayed()
            .assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.scenario_s11_description))
        select("S12")
        ready(model, "S12")
        rule.onNodeWithTag("scenario-description")
            .assertIsDisplayed()
            .assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.scenario_s12_description))
        assertEquals(0L, model.state.value.snapshot.nowMs)
    }

    @Test fun speedSliderSupportsHalfStepsAndHorizontalDrag() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        setSpeed(model, 1.5f)
        rule.onNodeWithTag("speed-value").assertTextEquals("1.5×")
        setSpeed(model, 0.5f)
        rule.onNodeWithTag("speed-value").assertTextEquals("0.5×")
        rule.onNodeWithTag("speed-slider").performTouchInput {
            swipeRight(startX = width * 0.03f, endX = width * 0.97f, durationMillis = 550)
        }
        rule.waitForIdle()
        assertEquals(5f, model.state.value.speed, 0f)
        rule.onNodeWithTag("speed-value").assertTextEquals("5×")
        assertEquals(0L, model.state.value.snapshot.nowMs)
    }

    @Test fun tenMapsRenderAndCameraControlsPreserveSession() {
        val model = model()
        ready(model, "DEMO_LONG")
        val representatives = listOf("L01", "L02", "L07", "L10", "L13", "L08", "L03", "L09", "L15", "DEMO_LONG")
        representatives.forEachIndexed { index, id ->
            select(id)
            ready(model, id)
            val state = model.state.value
            val mapId = "M" + (index + 1).toString().padStart(2, '0')
            assertEquals(mapId, state.scenario?.map?.id)
            assertTrue(state.scenario!!.roads.route.length > 0)
            assertTrue(state.scenario!!.map!!.visual.polygons.isNotEmpty())
            model.send(UiIntent.Step)
            rule.waitUntil(5_000) { model.state.value.playback == Playback.PAUSED }
            scroll("tracking-map")
            rule.onNodeWithTag("tracking-map").assertIsDisplayed()
            rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_follow))
            rule.onNodeWithTag("overview").performClick()
            rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_overview))
            capture(mapId)
            assertEquals(state.runId, model.state.value.runId)
        }
        val previous = model.state.value
        rule.onNodeWithTag("zoom-in").performClick()
        rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_free))
        rule.onNodeWithTag("recenter").performClick()
        rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_follow))
        rule.onNodeWithTag("tracking-map").performTouchInput {
            swipeRight(startX = width * 0.25f, endX = width * 0.73f, durationMillis = 250)
        }
        rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_free))
        click("map-theme")
        rule.onNodeWithTag("map-theme").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.map_night))
        click("truth-toggle")
        assertFalse(model.state.value.showTruth)
        assertEquals(previous.snapshot, model.state.value.snapshot)
        assertEquals(previous.runId, model.state.value.runId)
        click("reset")
        ready(model, "DEMO_LONG")
        scroll("tracking-map")
        rule.onNodeWithTag("camera-mode").assertTextEquals(rule.activity.getString(lab.recovery.feature.R.string.camera_follow))
    }

    @Test fun longDemoCompletesAndMatchesItsJvmEvaluation() {
        val model = model()
        ready(model, "DEMO_LONG")
        val scenario = model.state.value.scenario!!
        val expected = runBlocking {
            val repository = ScenarioRepository { rule.activity.assets.open(it) }
            playToEnd(ReplaySession(repository.read("DEMO_LONG")))
        }
        setSpeed(model, 5f)
        click("play-pause")
        rule.waitUntil(5_000) { model.state.value.playback == Playback.PLAYING }
        rule.waitUntil(scenario.durationMs / 5 + 15_000) { model.state.value.evaluation != null }
        assertEquals(expected, model.state.value.evaluation)
        assertTrue(model.state.value.logs.any { it.reason == Reason.SIGNAL_RECOVERED })
        assertTrue(model.state.value.logs.any { it.reason == Reason.REROUTE_RECOMMENDED })
        assertTrue(model.state.value.logs.any { it.reason == Reason.ROUTE_RETURNED })
        scroll("evaluation")
        rule.onNodeWithTag("evaluation").assertIsDisplayed()
        capture("long-results")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMaps") != "true") return
        rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = checkNotNull(rule.activity.getExternalFilesDir("maps"))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun setSpeed(model: LabViewModel, speed: Float) {
        rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag("speed-slider"))
        rule.onNodeWithTag("speed-slider").performSemanticsAction(SemanticsActions.SetProgress) { set ->
            assertTrue(set(speed))
        }
        rule.waitUntil(5_000) { model.state.value.speed == speed }
    }

    @Test fun jumpPreservesTheLastAcceptedFix() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        select("S02")
        ready(model, "S02")
        click("step")
        rule.waitUntil(5_000) { model.state.value.snapshot.signal == SignalState.TRACKING }
        stepTo(model, 3000)
        val state = model.state.value
        assertEquals(Reason.IMPLAUSIBLE_MOVE, state.snapshot.lastDecision)
        assertNotEquals(state.snapshot.lastObservation!!.point, state.snapshot.acceptedPosition)
        assertEquals(2000L, state.snapshot.lastAcceptedAtMs)
        scroll("tracking-map")
        rule.onNodeWithTag("tracking-map").assertIsDisplayed()
    }

    @Test fun longOutageShowsDrThenLastKnownAndRecovers() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        select("S07")
        ready(model, "S07")
        stepTo(model, 5000)
        assertEquals(PositionSource.DEAD_RECKONED, model.state.value.snapshot.source)
        stepTo(model, 17000)
        assertEquals(PositionSource.LAST_KNOWN, model.state.value.snapshot.source)
        assertEquals(Reason.DR_LIMIT, model.state.value.snapshot.drUnavailable)
        scroll("tracking-map")
        capture("dr-limit")
        stepTo(model, 24000)
        assertEquals(SignalState.TRACKING, model.state.value.snapshot.signal)
        assertTrue(model.state.value.logs.any { it.reason == Reason.SIGNAL_RECOVERED })
    }

    @Test fun portraitRecreationKeepsSessionAndRapidResetDropsTheOldRun() {
        val model = model()
        ready(model, "DEMO_LONG")
        select("DEMO")
        ready(model, "DEMO")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, rule.activity.requestedOrientation)
        click("step")
        rule.waitUntil(5_000) { model.state.value.snapshot.lastAcceptedAtMs != null }
        val previous = model.state.value
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertSame(model, model())
        assertEquals(previous.runId, model.state.value.runId)
        assertEquals(previous.snapshot, model.state.value.snapshot)
        click("play-pause")
        rule.waitUntil(5_000) { model.state.value.playback == Playback.PLAYING }
        click("play-pause")
        rule.waitUntil(5_000) { model.state.value.playback == Playback.PAUSED }
        model.send(UiIntent.Reset)
        model.send(UiIntent.Select(scenarioChoices.first { it.id == "S16" }))
        ready(model, "S16")
        assertTrue(model.state.value.runId > previous.runId)
        assertTrue(model.state.value.logs.isEmpty())
        assertNull(model.state.value.evaluation)
    }
}
