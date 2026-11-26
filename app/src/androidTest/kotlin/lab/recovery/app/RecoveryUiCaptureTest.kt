package lab.recovery.app

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import lab.recovery.simulation.ReplayFrame
import lab.recovery.simulation.ReplaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Captures the app's actual replay state. The independent cursor chooses frames to inspect. */
class RecoveryUiCaptureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun capturePositionRecovery() {
        val label = InstrumentationRegistry.getArguments().getString("captureRecoveryUi")
        assumeTrue(label == "baseline" || label == "candidate")
        val folder = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "recovery-ui-$label")
        assertTrue(folder.isDirectory || folder.mkdirs())
        val manifest = File(folder, "frames.tsv")
        manifest.writeText("image\tscenario\tvirtualMs\tsignal\tsource\troute\trecentReasons\n")
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var model: LabViewModel
            activity.onActivity {
                it.window.addFlags(FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(it)[LabViewModel::class.java]
            }
            await { model.state.value.scenario?.id == "ODEMO_LONG" && model.state.value.playback == Playback.READY }
            await { device.hasObject(By.res("osm-attribution")) }
            device.findObject(By.res("scenario-menu")).click()
            await { device.hasObject(By.textStartsWith("OL01 ·")) }
            capture(activity, folder, "scenario-menu")
            device.pressBack()
            sequence(model, "ODEMO_LONG", listOf("odemo-normal" to { frame -> frame.update.snapshot.nowMs >= 28_000L }),
                activity, folder, manifest)
            if (label == "baseline") {
                val screen = device.findObject(By.res("lab-screen"))
                repeat(3) { if (!device.hasObject(By.res("diagnostics-toggle"))) screen.swipe(androidx.test.uiautomator.Direction.UP, 0.65f) }
                device.findObject(By.res("diagnostics-toggle")).click()
                repeat(3) { if (!device.hasObject(By.res("position-source"))) screen.swipe(androidx.test.uiautomator.Direction.UP, 0.55f) }
                await { device.hasObject(By.res("position-source")) }
                capture(activity, folder, "diagnostics")
                device.findObject(By.res("diagnostics-toggle"))?.click()
            }
            sequence(model, "OL07", listOf(
                "ol07-tracking" to { f -> f.update.snapshot.nowMs >= 28_000L && f.update.snapshot.signal == SignalState.TRACKING },
                "ol07-estimating" to { f -> f.update.snapshot.nowMs >= 54_000L && f.update.snapshot.source == PositionSource.DEAD_RECKONED },
                "ol07-last-known" to { f -> f.update.snapshot.source == PositionSource.LAST_KNOWN && f.update.snapshot.drUnavailable == Reason.DR_LIMIT },
                "ol07-recovering" to { f -> f.update.snapshot.signal == SignalState.RECOVERING },
                "ol07-recovered" to { f -> f.update.events.any { it.reason == Reason.SIGNAL_RECOVERED } },
                "ol07-complete" to { f -> f.complete },
            ), activity, folder, manifest)
            sequence(model, "OL02", listOf("ol02-jump" to { f -> f.update.events.any { it.reason == Reason.IMPLAUSIBLE_MOVE } }),
                activity, folder, manifest)
            sequence(model, "OL10", listOf("ol10-ambiguous" to { f -> f.update.events.any { it.reason == Reason.AMBIGUOUS } }),
                activity, folder, manifest)
            sequence(model, "OL13", listOf(
                "ol13-departure" to { f -> f.update.events.any { it.reason == Reason.REROUTE_RECOMMENDED } },
                "ol13-return" to { f -> f.update.events.any { it.reason == Reason.ROUTE_RETURNED } },
            ), activity, folder, manifest)
        }
    }

    private fun sequence(
        model: LabViewModel,
        id: String,
        frames: List<Pair<String, (ReplayFrame) -> Boolean>>,
        activity: ActivityScenario<MainActivity>,
        folder: File,
        manifest: File,
    ) {
        val screen = device.findObject(By.res("lab-screen"))
        repeat(4) { if (!device.hasObject(By.res("scenario-menu"))) screen?.swipe(androidx.test.uiautomator.Direction.DOWN, 0.75f) }
        val previous = model.state.value.runId
        val choice = model.state.value.choices.single { it.id == id }
        model.send(UiIntent.Select(choice))
        await {
            val state = model.state.value
            state.runId > previous && state.scenario?.id == id && state.playback == Playback.READY
        }
        val oracle = ReplaySession(checkNotNull(model.state.value.scenario))
        val timeline = runBlocking { buildList { while (true) add(oracle.step() ?: break) } }
        var index = -1
        for ((name, matches) in frames) {
            val target = timeline.indexOfFirst(matches)
            if (target < 0) {
                manifest.appendText("$name\t$id\tNO_OBSERVED_FRAME\n")
                continue
            }
            check(target > index)
            for (end in ((index + 1)..target).chunked(48).map { it.last() }) {
                repeat(end - index) { model.send(UiIntent.Step) }
                if (end == 0) await { model.state.value.playback == Playback.PAUSED }
                else await { model.state.value.snapshot.nowMs == timeline[end].update.snapshot.nowMs }
                index = end
            }
            val frame = timeline[target]
            if (frame.complete) await { model.state.value.evaluation != null }
            val state = model.state.value
            assertEquals(frame.update.snapshot, state.snapshot)
            assertTrue(frame.update.events.all { expected ->
                state.logs.any { event -> event.timeMs == expected.timeMs && event.reason == expected.reason }
            })
            val observed = state.snapshot
            await {
                device.findObject(By.res("virtual-time"))?.text?.substringBefore(" / ") ==
                    "%02d:%04.1f".format(java.util.Locale.ROOT, observed.nowMs / 60_000, observed.nowMs % 60_000 / 1000.0)
            }
            capture(activity, folder, name)
            manifest.appendText("$name\t$id\t${observed.nowMs}\t${observed.signal}\t${observed.source}\t" +
                "${observed.route}\t${frame.update.events.joinToString { it.reason.name }}\n")
            if (frame.complete) {
                val result = device.findObject(By.res("open-results"))
                if (result != null) result.click()
                else {
                    val screen = device.findObject(By.res("lab-screen"))
                    repeat(4) { if (!device.hasObject(By.res("evaluation"))) screen?.swipe(androidx.test.uiautomator.Direction.UP, 0.7f) }
                }
                await { device.hasObject(By.res("evaluation")) }
                capture(activity, folder, "$name-results")
                if (result != null) device.pressBack()
            }
        }
    }

    private fun capture(activity: ActivityScenario<MainActivity>, folder: File, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            activity.onActivity {
                it.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                it.window.decorView.invalidate()
            }
            assertTrue("Frame committed before $name", committed.await(5, TimeUnit.SECONDS))
        }
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        error("The app did not reach the capture condition")
    }
}
