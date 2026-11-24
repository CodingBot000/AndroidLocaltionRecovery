package lab.recovery.app

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OsmOfflineSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun defaultAndOfflineVectorMapsRenderWithAttribution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capture = InstrumentationRegistry.getArguments().getString("captureOsmMaps") == "true"
        if (capture) {
            fun setting(name: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("settings get global $name")).bufferedReader().use { it.readText().trim() }
            assertEquals("1", setting("airplane_mode_on"))
            assertEquals("0", setting("wifi_on"))
        }
        val model = ViewModelProvider(rule.activity)[LabViewModel::class.java]
        rule.waitUntil(20_000) {
            model.state.value.scenario?.id == "ODEMO_LONG" &&
                model.state.value.choices.any { it.id == "OL01" }
        }
        rule.onNodeWithTag("osm-attribution").assertIsDisplayed().performClick()
        rule.onNodeWithTag("osm-source-details").assertIsDisplayed()
        rule.onNodeWithTag("osm-source-close").performClick()

        val checks = if (capture) listOf(
            Triple("O01", "OL01", 24_000), Triple("O02", "OL02", 28_000), Triple("O03", "OL07", 54_000),
            Triple("O04", "OL10", 35_000), Triple("O05", "OL13", 43_000), Triple("O06", "OL08", 56_000),
            Triple("O07", "OL03", 52_000), Triple("O08", "OL09", 37_000), Triple("O09", "OL05", 23_000),
            Triple("O10", "ODEMO_LONG", 28_000),
        ) else listOf(Triple("O01", "OL01", 1_000))
        for ((map, id, at) in checks) {
            val choice = model.state.value.choices.single { it.id == id }
            model.send(UiIntent.Select(choice))
            rule.waitUntil(20_000) {
                model.state.value.playback == Playback.READY && model.state.value.scenario?.id == id
            }
            val scenario = checkNotNull(model.state.value.scenario)
            assertEquals(map, scenario.map?.id)
            assertEquals("ODbL-1.0", scenario.map?.osm?.license)
            assertTrue(scenario.map!!.visual.polygons.isNotEmpty())
            for (batch in (0L..at.toLong() step 200).chunked(60)) {
                repeat(batch.size) { model.send(UiIntent.Step) }
                try {
                    rule.waitUntil(12_000) {
                        model.state.value.playback == Playback.PAUSED && model.state.value.snapshot.nowMs == batch.last()
                    }
                } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    throw IllegalStateException("$id step expected ${batch.last()}, actual ${model.state.value.snapshot.nowMs}, " +
                        "playback ${model.state.value.playback}, error ${model.state.value.error}", timeout)
                }
            }
            val snapshot = model.state.value.snapshot
            rule.onNodeWithTag("tracking-map").assertIsDisplayed()
            rule.onNodeWithTag("osm-attribution").assertIsDisplayed()
            if (capture) {
                capture("$map-follow")
                repeat(2) { rule.onNodeWithTag("zoom-out").performClick() }
                capture("$map-mid")
            }
            rule.onNodeWithTag("overview").performClick()
            rule.onNodeWithTag("osm-attribution").assertIsDisplayed()
            if (capture) capture("$map-overview")
            if (map == "O01" || map == "O10") {
                rule.onNodeWithTag("recenter").performClick()
                rule.onNodeWithTag("lab-screen").performScrollToNode(hasTestTag("map-theme"))
                rule.onNodeWithTag("map-theme").performClick()
                if (capture) capture("$map-night")
                rule.onNodeWithTag("map-theme").performClick()
            }
            assertEquals(snapshot, model.state.value.snapshot)
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val activity = rule.activity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val view = activity.window.decorView
                view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                view.invalidate()
            }
            assertTrue(frame.await(4, TimeUnit.SECONDS))
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = checkNotNull(activity.getExternalFilesDir("osm"))
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
