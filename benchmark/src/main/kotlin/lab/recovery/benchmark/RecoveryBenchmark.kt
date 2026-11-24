package lab.recovery.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecoveryBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test fun demoReplay() = replay("DEMO", "00:15.0", end = "00:15\\.0", map = null, timeout = 25_000)

    @Test fun longMapReplay() = replay("DEMO_LONG", "03:00.0", end = "00:4[5-9]\\.[0-9]", map = "M10", timeout = 60_000)

    private fun replay(scenario: String, duration: String, end: String, map: String?, timeout: Long) = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            killProcess()
            startActivityAndWait(Intent(Intent.ACTION_MAIN).setClassName(TARGET_PACKAGE, "lab.recovery.app.MainActivity")
                .putExtra("benchmarkScenario", scenario))
            assertTrue("Expected selected scenario $scenario",
                device.wait(Until.hasObject(By.textStartsWith("$scenario ·")), UI_TIMEOUT_MS))
            assertTrue("Expected $duration fixture at zero time and 1× playback",
                device.wait(Until.hasObject(By.res("virtual-time").text("00:00.0 / $duration").descEndsWith("1×")), UI_TIMEOUT_MS))
            if (map != null) assertTrue("Expected map $map",
                device.wait(Until.hasObject(By.res("map-status").textStartsWith("$map ·")), UI_TIMEOUT_MS))
            assertTrue("Playback controls must be ready",
                device.wait(Until.hasObject(By.res("play-pause").enabled(true)), UI_TIMEOUT_MS))
        },
    ) {
        device.findObject(By.res("play-pause").enabled(true)).click()
        val endTime = java.util.regex.Pattern.compile("$end / " + java.util.regex.Pattern.quote(duration))
        assertTrue("Expected replay to reach $end at 1×",
            device.wait(Until.hasObject(By.res("virtual-time").text(endTime).descEndsWith("1×")), timeout))
    }

    private companion object {
        const val TARGET_PACKAGE = "lab.recovery.app.benchmark"
        const val ITERATIONS = 7
        const val UI_TIMEOUT_MS = 5_000L
    }
}
