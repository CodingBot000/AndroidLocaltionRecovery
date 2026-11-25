package lab.recovery.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RefactorMapBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldO10() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(), startupMode = StartupMode.COLD, iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        ready("O10")
    }

    @Test fun switchO10ToO07() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = ITERATIONS,
        setupBlock = { killProcess(); startActivityAndWait(); ready("O10") },
    ) {
        val start = SystemClock.elapsedRealtimeNanos()
        select("OL03")
        ready("O07")
        Log.i("RefactorMapBenchmark", "switchO10ToO07Ms=${(SystemClock.elapsedRealtimeNanos()-start)/1e6}")
        select("ODEMO_LONG")
        ready("O10")
    }

    @Test fun rideO10() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = ITERATIONS,
        setupBlock = { killProcess(); startActivityAndWait(); ready("O10") },
    ) {
        device.findObject(By.res("play-pause").enabled(true)).click()
        awaitReplay(3.0)
        device.findObject(By.res("zoom-out")).click()
        device.findObject(By.res("overview")).click()
        awaitReplay(6.0)
        device.findObject(By.res("recenter")).click()
        awaitReplay(8.0)
    }

    private fun MacrobenchmarkScope.select(id: String) {
        device.executeShellCommand("am start -n $TARGET/lab.recovery.app.MainActivity -f 0x20000000 --es benchmarkSelect $id")
    }

    private fun MacrobenchmarkScope.awaitReplay(target: Double) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            val text = device.findObject(By.res("virtual-time"))?.text.orEmpty()
            val current = text.substringBefore(" / ").substringAfter(':').toDoubleOrNull()
            if (current != null && current >= target) return
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Replay did not reach $target s")
    }

    private fun MacrobenchmarkScope.ready(map: String) {
        assertTrue("Expected map $map", device.wait(Until.hasObject(By.res("map-status").textStartsWith("$map ·")), 20_000))
        assertTrue("Map must finish drawing", device.wait(Until.hasObject(By.res("osm-attribution")), 20_000))
        assertTrue("Playback must be available", device.wait(Until.hasObject(By.res("play-pause").enabled(true)), 20_000))
    }

    private companion object {
        const val TARGET = "lab.recovery.app.benchmark"
        const val ITERATIONS = 3
    }
}
