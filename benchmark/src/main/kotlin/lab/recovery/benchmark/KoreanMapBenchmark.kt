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
import java.util.regex.Pattern

class KoreanMapBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun rawGeometry() = measure(raw = true)

    @Test fun indexedGeometry() = measure(raw = false)

    private fun measure(raw: Boolean) = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 1,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait(Intent(Intent.ACTION_MAIN).setClassName(TARGET, "lab.recovery.app.MainActivity")
            .putExtra("benchmarkRawMap", raw))
        assertTrue(device.wait(Until.hasObject(By.res("map-status").textStartsWith("O10 ·")), 15_000))
        assertTrue(device.wait(Until.hasObject(By.res("play-pause").enabled(true)), 15_000))
        device.findObject(By.res("play-pause").enabled(true)).click()
        val time = { seconds: String ->
            device.wait(Until.hasObject(By.res("virtual-time")
                .text(Pattern.compile("00:$seconds\\.[0-9] / 03:00\\.0")).descEndsWith("1×")), 15_000)
        }
        assertTrue("Drive must reach 4 seconds", time("0[4-5]"))
        repeat(2) { device.findObject(By.res("zoom-out")).click() }
        assertTrue("Drive must reach 8 seconds", time("0[8-9]"))
        device.findObject(By.res("overview")).click()
        assertTrue("Drive must reach 12 seconds", time("1[2-3]"))
    }

    private companion object {
        const val TARGET = "lab.recovery.app.benchmark"
    }
}
