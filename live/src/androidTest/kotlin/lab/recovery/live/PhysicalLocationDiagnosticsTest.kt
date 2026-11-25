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
    private val resources = PhysicalLiveResources(instrumentation.targetContext, device)

    @Test fun observesLiveAndDirectGpsSequentially() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            selectMap("O10")
            val live = GnssObservation()
            observe(scenario, live) {
                liveSession {
                    observeFor(live) { LiveSessions.updates.value.reading?.fix?.let(::freshGps) == true }
                    val state = LiveSessions.updates.value
                    Log.i(TAG, "live callbacks=${state.received} processed=${state.processed} dropped=${state.dropped} " +
                        "reading=${state.reading?.status} issue=${state.issue} ${live.summary()}")
                }
            }

            val probe = GnssObservation()
            observe(scenario, probe, direct = true) {
                await { resources.registered() }
                observeFor(probe) { probe.freshCallbacks > 0 }
                Log.i(TAG, "direct callbackCount=${probe.callbacks} fresh=${probe.freshCallbacks} ${probe.summary()}")
            }
            await { !resources.registered() && !resources.foreground() }
        }
    }

    @Test fun releasesTwentyPhysicalSessions() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use {
            var previousToken = 0L
            repeat(20) { cycle ->
                if (cycle == 0) selectMap("O01")
                if (cycle == 10) selectMap("O10")
                val measure = cycle in setOf(0, 4, 9, 14, 19)
                val stopMs = liveSession { times ->
                    val state = LiveSessions.updates.value
                    assertTrue("Session token must advance", state.token > previousToken)
                    previousToken = state.token
                    Log.i(TAG, "cycle=${cycle + 1} map=${state.mapId} foregroundMs=${times.first} " +
                        "activeMs=${times.second} callbacks=${state.received} dropped=${state.dropped}")
                    if (measure) noteMemory(cycle + 1, "active")
                }
                Log.i(TAG, "cycle=${cycle + 1} stopMs=$stopMs")
                if (measure) noteMemory(cycle + 1, "stopped")
            }
        }
    }

    @Test fun retainsPhysicalSessionAcrossRecreationHomeAndScreenOff() {
        requirePhysicalOptIn()
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            selectMap("O10")
            var screenOffRequested = false
            withPhysicalTestCleanup(cleanup = {
                if (testFailed && screenOffRequested) attempt("wake screen after failure") { device.wakeUp() }
            }) {
                val stopMs = liveSession {
                    val active = LiveSessions.updates.value
                    repeat(3) {
                        scenario.recreate()
                        await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && resources.foreground() && resources.registered() }
                        assertEquals(active.token, LiveSessions.updates.value.token)
                    }
                    device.pressHome()
                    await { device.currentPackageName != instrumentation.targetContext.packageName }
                    screenOffRequested = true
                    device.sleep()
                    await { !device.isScreenOn }
                    val sleepStarted = SystemClock.elapsedRealtime()
                    val before = LiveSessions.updates.value.received
                    while (SystemClock.elapsedRealtime() - sleepStarted < 300_000) {
                        assertTrue("Screen turned on during the observation", !device.isScreenOn)
                        assertTrue("Foreground registration was lost", resources.foreground() && resources.registered())
                        assertEquals(active.token, LiveSessions.updates.value.token)
                        SystemClock.sleep(15_000)
                    }
                    val state = LiveSessions.updates.value
                    Log.i(TAG, "screenOffMs=${SystemClock.elapsedRealtime() - sleepStarted} callbacks=${state.received - before}")

                    device.wakeUp()
                    val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
                    if (!keyguard.isDeviceSecure) device.executeShellCommand("wm dismiss-keyguard")
                    if (keyguard.isKeyguardLocked) {
                        Log.i(TAG, "unlockRequired=true")
                        await(90_000) { !keyguard.isKeyguardLocked }
                    }
                    device.executeShellCommand("am start -n lab.recovery.live/.LiveActivity -f 0x20000000")
                    assertEquals(active.token, LiveSessions.updates.value.token)
                }
                Log.i(TAG, "sameTokenAfterUnlock=true stopMs=$stopMs")
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
        await { resources.foreground() }
        val promoted = SystemClock.elapsedRealtime() - before
        await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && resources.registered() }
        return promoted to (SystemClock.elapsedRealtime() - before)
    }

    private fun stop(): Long {
        val before = SystemClock.elapsedRealtime()
        click("live-stop")
        await { LiveSessions.updates.value.phase == SessionPhase.IDLE && !resources.foreground() && !resources.registered() }
        assertNull(LiveSessions.updates.value.reading)
        return SystemClock.elapsedRealtime() - before
    }

    private fun liveSession(verify: (Pair<Long, Long>) -> Unit): Long =
        runPhysicalLiveSession(resources, ::start, verify, ::stop)

    @SuppressLint("MissingPermission")
    private fun observe(
        scenario: ActivityScenario<LiveActivity>, observer: GnssObservation, direct: Boolean = false, block: () -> Unit,
    ) {
        val manager = instrumentation.targetContext.getSystemService(LocationManager::class.java)
        val listener = LocationListener { position -> observer.onLocation(position) }
        var keepScreenRequested = false
        var statusAttempted = false
        var updatesAttempted = false
        withPhysicalTestCleanup(cleanup = {
            if (updatesAttempted) attempt("remove direct GPS listener") { manager.removeUpdates(listener) }
            if (statusAttempted) attempt("unregister GNSS observer") { manager.unregisterGnssStatusCallback(observer) }
            if (keepScreenRequested) attempt("clear keep-screen-on flag") {
                scenario.onActivity { it.window.clearFlags(FLAG_KEEP_SCREEN_ON) }
            }
        }) {
            scenario.onActivity { activity ->
                keepScreenRequested = true
                activity.window.addFlags(FLAG_KEEP_SCREEN_ON)
                statusAttempted = true
                assertTrue("GNSS status registration failed", manager.registerGnssStatusCallback(activity.mainExecutor, observer))
                if (direct) {
                    updatesAttempted = true
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
                }
            }
            block()
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
