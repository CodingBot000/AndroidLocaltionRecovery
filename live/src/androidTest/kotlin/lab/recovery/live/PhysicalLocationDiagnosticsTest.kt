package lab.recovery.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PhysicalLocationDiagnosticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun observesLiveAndDirectGpsSequentially() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            selectMap("O10")
            val live = GnssObservation()
            observe(scenario, live) {
                start()
                await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && foreground() && registered() }
                try {
                    observeFor(live) { LiveSessions.updates.value.reading?.fix?.let(::freshGps) == true }
                    val state = LiveSessions.updates.value
                    Log.i(TAG, "live callbacks=${state.received} processed=${state.processed} dropped=${state.dropped} " +
                        "reading=${state.reading?.status} issue=${state.issue} ${live.summary()}")
                } finally {
                    stop()
                }
            }

            val probe = GnssObservation()
            observe(scenario, probe, direct = true) {
                await { registered() }
                observeFor(probe) { probe.freshCallbacks > 0 }
                Log.i(TAG, "direct callbackCount=${probe.callbacks} fresh=${probe.freshCallbacks} ${probe.summary()}")
            }
            await { !registered() && !foreground() }
        }
    }

    @Test fun releasesTwentyPhysicalSessions() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use {
            var previousToken = 0L
            repeat(20) { cycle ->
                if (cycle == 0) selectMap("O01")
                if (cycle == 10) selectMap("O10")
                val times = start()
                val measure = cycle in setOf(0, 4, 9, 14, 19)
                try {
                    val state = LiveSessions.updates.value
                    assertTrue("Session token must advance", state.token > previousToken)
                    previousToken = state.token
                    Log.i(TAG, "cycle=${cycle + 1} map=${state.mapId} foregroundMs=${times.first} " +
                        "activeMs=${times.second} callbacks=${state.received} dropped=${state.dropped}")
                    if (measure) noteMemory(cycle + 1, "active")
                } finally {
                    Log.i(TAG, "cycle=${cycle + 1} stopMs=${stop()}")
                    if (measure) noteMemory(cycle + 1, "stopped")
                }
            }
        }
    }

    @Test fun retainsPhysicalSessionAcrossRecreationHomeAndScreenOff() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            selectMap("O10")
            start()
            val active = LiveSessions.updates.value
            try {
                repeat(3) {
                    scenario.recreate()
                    await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && foreground() && registered() }
                    assertEquals(active.token, LiveSessions.updates.value.token)
                }
                device.pressHome()
                await { device.currentPackageName != instrumentation.targetContext.packageName }
                device.sleep()
                await { !device.isScreenOn }
                val sleepStarted = SystemClock.elapsedRealtime()
                val before = LiveSessions.updates.value.received
                while (SystemClock.elapsedRealtime() - sleepStarted < 300_000) {
                    assertTrue("Screen turned on during the observation", !device.isScreenOn)
                    assertTrue("Foreground registration was lost", foreground() && registered())
                    assertEquals(active.token, LiveSessions.updates.value.token)
                    SystemClock.sleep(15_000)
                }
                val state = LiveSessions.updates.value
                Log.i(TAG, "screenOffMs=${SystemClock.elapsedRealtime() - sleepStarted} callbacks=${state.received - before}")
            } finally {
                device.wakeUp()
                val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
                if (!keyguard.isDeviceSecure) device.executeShellCommand("wm dismiss-keyguard")
                if (keyguard.isKeyguardLocked) {
                    Log.i(TAG, "unlockRequired=true")
                    await(90_000) { !keyguard.isKeyguardLocked }
                }
                device.executeShellCommand("am start -n lab.recovery.live/.LiveActivity -f 0x20000000")
                assertEquals(active.token, LiveSessions.updates.value.token)
                Log.i(TAG, "sameTokenAfterUnlock=true stopMs=${stop()}")
            }
        }
    }

    private fun noteMemory(cycle: Int, phase: String) {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val javaUsedKiB = (runtime.totalMemory() - runtime.freeMemory()) / 1024
        Log.i(TAG, "memCycle=$cycle phase=$phase totalPssKiB=${memory.totalPss} " +
            "javaPssKiB=${memory.dalvikPss} nativePssKiB=${memory.nativePss} " +
            "javaUsedKiB=$javaUsedKiB nativeAllocatedKiB=${Debug.getNativeHeapAllocatedSize() / 1024}")
    }

    private fun requirePhysicalOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realLocation") == "true")
        assumeTrue(Build.HARDWARE !in setOf("ranchu", "goldfish"))
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        assertEquals(PackageManager.PERMISSION_GRANTED,
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    private fun selectMap(id: String) {
        click("live-map")
        click("live-map-$id")
    }

    private fun click(tag: String) {
        val item = device.wait(Until.findObject(By.res(tag).enabled(true)), 12_000)
        checkNotNull(item) { "Enabled control was not found: $tag" }.click()
    }

    private fun start(): Pair<Long, Long> {
        val before = SystemClock.elapsedRealtime()
        click("live-start")
        await { foreground() }
        val promoted = SystemClock.elapsedRealtime() - before
        await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && registered() }
        return promoted to (SystemClock.elapsedRealtime() - before)
    }

    private fun stop(): Long {
        val before = SystemClock.elapsedRealtime()
        click("live-stop")
        await { LiveSessions.updates.value.phase == SessionPhase.IDLE && !foreground() && !registered() }
        assertNull(LiveSessions.updates.value.reading)
        return SystemClock.elapsedRealtime() - before
    }

    private fun foreground(): Boolean =
        device.executeShellCommand("dumpsys activity services lab.recovery.live").contains("isForeground=true")

    private fun registered(): Boolean {
        // The Samsung dump also repeats requests beneath dated history groups.
        // Read only the current GPS provider's immediate listener rows and never write the dump.
        val rows = device.executeShellCommand("dumpsys location").lines()
        val provider = rows.indexOfFirst { it.trim() == "gps provider:" }
        check(provider >= 0) { "GPS provider section missing" }
        fun indentation(line: String) = line.length - line.trimStart().length
        val section = rows.drop(provider + 1).takeWhile {
            it.isBlank() || indentation(it) > indentation(rows[provider])
        }
        val listeners = section.indexOfFirst { it.trim() == "listeners:" }
        if (listeners < 0) return false
        return section.drop(listeners + 1)
            .takeWhile { it.isBlank() || indentation(it) > indentation(section[listeners]) }
            .any {
                it.contains("lab.recovery.live") &&
                    (it.contains("Request[") || it.contains("Request{")) && !it.trimStart().startsWith("[")
            }
    }

    @SuppressLint("MissingPermission")
    private fun observe(
        scenario: ActivityScenario<LiveActivity>, observer: GnssObservation, direct: Boolean = false, block: () -> Unit,
    ) {
        val manager = instrumentation.targetContext.getSystemService(LocationManager::class.java)
        val listener = LocationListener { position -> observer.onLocation(position) }
        var statusRegistered = false
        var listenerRegistered = false
        try {
            scenario.onActivity { activity ->
                activity.window.addFlags(FLAG_KEEP_SCREEN_ON)
                statusRegistered = manager.registerGnssStatusCallback(activity.mainExecutor, observer)
                assertTrue("GNSS status registration failed", statusRegistered)
                if (direct) {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
                    listenerRegistered = true
                }
            }
            block()
        } finally {
            scenario.onActivity { activity ->
                if (listenerRegistered) manager.removeUpdates(listener)
                if (statusRegistered) manager.unregisterGnssStatusCallback(observer)
                activity.window.clearFlags(FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun observeFor(observer: GnssObservation, fresh: GnssObservation.() -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!observer.fresh() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
    }

    private fun await(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Physical device state did not settle in ${timeoutMs}ms")
    }

    private class GnssObservation : GnssStatus.Callback() {
        private val startNs = SystemClock.elapsedRealtimeNanos()
        @Volatile private var started = 0
        @Volatile private var epochs = 0
        @Volatile private var visibleMax = 0
        @Volatile private var usedMax = 0
        @Volatile private var firstFixMs: Int? = null
        @Volatile var callbacks = 0
            private set
        @Volatile var freshCallbacks = 0
            private set

        override fun onStarted() { ++started }
        override fun onFirstFix(ttffMillis: Int) { if (firstFixMs == null) firstFixMs = ttffMillis }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            ++epochs
            visibleMax = maxOf(visibleMax, status.satelliteCount)
            usedMax = maxOf(usedMax, (0 until status.satelliteCount).count(status::usedInFix))
        }

        fun freshGps(fix: RawFix): Boolean = fix.provider == "gps" && !fix.mock && fix.measuredNs >= startNs

        fun onLocation(location: Location) {
            ++callbacks
            if (location.provider == "gps" && !location.isMock && location.elapsedRealtimeNanos >= startNs) ++freshCallbacks
        }

        fun summary(): String = "gnssStarted=$started epochs=$epochs visibleMax=$visibleMax usedMax=$usedMax firstFixMs=$firstFixMs"
    }

    private companion object { const val TAG = "PhysicalLocation" }
}
