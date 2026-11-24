package lab.recovery.benchmark

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

    @Test fun demoReplay() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            killProcess()
            startActivityAndWait()
            assertTrue("Demo replay must use the default 1× speed",
                device.wait(Until.hasObject(By.res("speed-value").text("1×")), UI_TIMEOUT_MS))
            assertTrue("Demo replay must start at zero time",
                device.wait(Until.hasObject(By.res("virtual-time").text("00:00.0 / 00:15.0")), UI_TIMEOUT_MS))
            assertTrue("Demo fixture must be ready",
                device.wait(Until.hasObject(By.res("play-pause").enabled(true)), UI_TIMEOUT_MS))
        },
    ) {
        device.findObject(By.res("play-pause").enabled(true)).click()
        assertTrue("Demo replay must reach its virtual end time",
            device.wait(Until.hasObject(By.res("virtual-time").text("00:15.0 / 00:15.0")), REPLAY_TIMEOUT_MS))
    }

    private companion object {
        const val TARGET_PACKAGE = "lab.recovery.app.benchmark"
        const val ITERATIONS = 7
        const val UI_TIMEOUT_MS = 5_000L
        const val REPLAY_TIMEOUT_MS = 25_000L
    }
}
