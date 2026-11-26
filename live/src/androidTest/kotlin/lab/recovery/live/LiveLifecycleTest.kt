package lab.recovery.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LiveLifecycleTest {
    // Queue composition work for the test clock instead of resuming it on the map worker.
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val rule = createAndroidComposeRule<LiveActivity>(effectContext = StandardTestDispatcher())
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var mockProvider = false

    @Before fun setup() {
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        InstrumentationRegistry.getArguments().getString("expectedHeapMiB")?.toLong()?.let {
            assertEquals("ART heap limit in bytes", it * 1024 * 1024, Runtime.getRuntime().maxMemory())
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, rule.activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        if (Build.VERSION.SDK_INT >= 30) {
            shell("appops set com.android.shell android:mock_location allow")
            shell("cmd location providers add-test-provider gps --requiresSatellite")
            mockProvider = true
            shell("cmd location providers set-test-provider-enabled gps true")
        }
    }

    @After fun cleanup() {
        rule.runOnIdle { LiveSessions.stop(rule.activity) }
        if (mockProvider) {
            shell("cmd location providers remove-test-provider gps")
            shell("appops set com.android.shell android:mock_location default")
        }
    }

    @Test fun foregroundSessionSurvivesHomeScreenOffAndActivityRecreation() {
        rule.onNodeWithTag("live-mode").assertIsDisplayed()
        choose("O01", "OL01")
        val before = SystemClock.elapsedRealtime()
        start()
        assertForeground(true)
        val promoted = SystemClock.elapsedRealtime()
        waitPhase(SessionPhase.ACTIVE)
        val active = LiveSessions.updates.value
        assertEquals(Precision.FINE, active.precision)
        expectLabel("live-phase", R.string.live_phase_active)
        expectLabel("live-precision", R.string.live_permission_fine)
        assertTrue(active.token > 0)
        assertListener(true)
        if (mockProvider) {
            inject("37.5275,126.922")
            rule.waitUntil(12_000) { LiveSessions.updates.value.reading?.status == FixStatus.TRACKING }
            assertTrue(LiveSessions.updates.value.reading?.fix?.mock == true)
            expectLabel("live-fix-source", R.string.live_source_emulator_mock)
            Log.i("LiveVerification", "simulated_fix_latency_from_start_ms=${SystemClock.elapsedRealtime() - before}")
            SystemClock.sleep(1_100) // Respect the framework registration interval.
            inject("36.36,127.39")
            rule.waitUntil(12_000) { LiveSessions.updates.value.reading?.status == FixStatus.OUTSIDE_COVERAGE }
            assertNull(LiveSessions.updates.value.reading?.tracking)
            expectLabel("live-fix-status", R.string.live_fix_outside)
        }
        repeat(3) {
            rule.activityRule.scenario.recreate()
            assertEquals(active.token, LiveSessions.updates.value.token)
            assertForeground(true)
            assertListener(true)
        }

        rule.waitUntil(12_000) { rule.activity.hasWindowFocus() }
        assertEquals(rule.activity.packageName, device.currentPackageName)
        val launcher = checkNotNull(device.launcherPackageName)
        val pressedHome = device.pressHome()
        assertTrue("Home launcher appeared after pressing HOME (reported success: $pressedHome)",
            device.wait(Until.hasObject(By.pkg(launcher)), 12_000))
        assertForeground(true)
        assertListener(true)
        device.sleep()
        assertFalse(device.isScreenOn)
        assertForeground(true)
        assertListener(true)
        device.wakeUp()
        shell("wm dismiss-keyguard")
        shell("am start -n lab.recovery.live/.LiveActivity -f 0x20000000")
        rule.waitUntil(12_000) { rule.activity.hasWindowFocus() }
        assertEquals(active.token, LiveSessions.updates.value.token)
        val stop = SystemClock.elapsedRealtime()
        rule.onNodeWithTag("live-stop").performClick()
        assertReleased()
        Log.i("LiveVerification", "first_foreground_poll_ms=${promoted - before} stop_to_release_ms=${SystemClock.elapsedRealtime() - stop}")
    }

    private fun expectLabel(tag: String, label: Int) {
        val text = rule.activity.getString(label)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(tag).assertTextEquals(text)
    }

    @Test fun twentyStartStopCyclesAndTwoMapSizesReleaseListenerAndForegroundService() {
        val startTimes = mutableListOf<Long>()
        val stopTimes = mutableListOf<Long>()
        val snapshots = mutableListOf<String>()
        val fixTimes = mutableListOf<Long>()
        repeat(20) { cycle ->
            if (cycle == 0) choose("O01")
            if (cycle == 10) choose("O10")
            val before = SystemClock.elapsedRealtime()
            start()
            waitPhase(SessionPhase.ACTIVE)
            startTimes += SystemClock.elapsedRealtime() - before
            assertForeground(true)
            assertListener(true)
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("camera-mode").fetchSemanticsNodes().isNotEmpty()
            }
            if (mockProvider) {
                inject(if (cycle < 10) "37.5275,126.922" else "37.544,127.054")
                rule.waitUntil(10_000) { LiveSessions.updates.value.latestFixProcessingNs != null }
                fixTimes += checkNotNull(LiveSessions.updates.value.latestFixProcessingNs)
            }
            if (cycle in setOf(0, 9, 10, 19)) {
                val summary = shell("dumpsys meminfo lab.recovery.live").lineSequence()
                    .filter { it.contains("TOTAL PSS:") || it.trimStart().startsWith("Views:") }
                    .joinToString(" ") { it.trim() }
                snapshots += "$cycle $summary"
            }
            val stop = SystemClock.elapsedRealtime()
            rule.onNodeWithTag("live-stop").performClick()
            assertReleased()
            stopTimes += SystemClock.elapsedRealtime() - stop
        }
        Log.i("LiveVerification", "twenty_start_to_active_ms=$startTimes stop_to_release_ms=$stopTimes")
        Log.i("LiveVerification", "fix_processing_ns=$fixTimes")
        Log.i("LiveVerification", "memory_snapshots=$snapshots")
    }

    @Test fun disabledProviderTerminatesAndRequiresAnExplicitRestart() {
        org.junit.Assume.assumeTrue(mockProvider)
        choose("O01")
        start()
        waitPhase(SessionPhase.ACTIVE)
        shell("cmd location providers set-test-provider-enabled gps false")
        waitPhase(SessionPhase.ERROR)
        assertEquals(LiveIssue.PROVIDER_DISABLED, LiveSessions.updates.value.issue)
        assertForeground(false)
        assertListener(false)
        shell("cmd location providers set-test-provider-enabled gps true")
        assertForeground(false)
        start()
        waitPhase(SessionPhase.ACTIVE)
        rule.onNodeWithTag("live-stop").performClick()
        assertReleased()
    }

    private fun start() {
        val start = hasTestTag("live-start") and isEnabled()
        rule.waitUntil(12_000) {
            rule.activity.hasWindowFocus() &&
                rule.onAllNodes(start).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        rule.onNode(start).performClick()
    }

    private fun choose(map: String, route: String? = null) {
        rule.waitUntil(10_000) {
            rule.onNodeWithTag("live-map").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        }
        rule.onNodeWithTag("live-map").performClick()
        rule.onNodeWithTag("live-map-$map").performClick()
        if (route != null) {
            rule.onNodeWithTag("live-route").performClick()
            rule.onNodeWithTag("live-route-$route").performClick()
        }
    }

    private fun inject(position: String) = shell("cmd location providers set-test-provider-location gps --location $position --accuracy 3")

    private fun waitPhase(phase: SessionPhase) {
        try {
            rule.waitUntil(20_000) { LiveSessions.updates.value.phase == phase }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val actual = LiveSessions.updates.value
            throw AssertionError("Expected $phase, observed ${actual.phase}, issue ${actual.issue}", timeout)
        }
    }

    private fun assertForeground(expected: Boolean) {
        rule.waitUntil(10_000) {
            shell("dumpsys activity services lab.recovery.live").contains("isForeground=true") == expected
        }
    }

    private fun assertReleased() {
        waitPhase(SessionPhase.IDLE)
        assertForeground(false)
        assertListener(false)
        assertNull(LiveSessions.updates.value.reading)
    }

    private fun assertListener(expected: Boolean) {
        rule.waitUntil(10_000) { listenerPresent() == expected }
    }

    private fun listenerPresent(): Boolean {
        // Avoid the historical event log. Android versions label that section differently.
        val current = shell("dumpsys location").substringBefore("Event Log:").substringBefore("  Event log:")
        return current.lineSequence().any { line ->
            line.contains("lab.recovery.live") && line.contains("Request[") &&
                !line.contains("+registration") && !line.contains("-registration") &&
                !line.contains("delivered location")
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
