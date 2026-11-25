package lab.recovery.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PhysicalLocationCleanupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val resources = PhysicalLiveResources(context, device)

    @Before fun useDisposableEmulator() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun cleansUpWhenStartFailsAfterRequestingTheService() =
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            val failure = IllegalStateException("Injected failure during Start")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                runPhysicalLiveSession(
                    resources = resources,
                    start = {
                        scenario.onActivity {
                            LiveSessions.start(it, "O01", null)
                            assertEquals(SessionPhase.STARTING, LiveSessions.updates.value.phase)
                        }
                        throw failure
                    },
                    verify = { error("Start failed before verification") },
                    stopViaUi = { error("Start failed before UI Stop") },
                )
            })
            assertReleased(failure)
        }

    @Test fun cleansUpWithoutUiOnUnlockTimeout() =
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            val failure = IllegalStateException("Injected unlock timeout")
            try {
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    runPhysicalLiveSession(
                        resources = resources,
                        start = { startActive(scenario) },
                        verify = {
                            device.sleep()
                            await { !device.isScreenOn }
                            assertTrue(resources.foreground())
                            assertTrue(resources.registered())
                            throw failure
                        },
                        stopViaUi = { error("Unlock failed before UI Stop") },
                    )
                })
                assertReleased(failure)
            } finally {
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
            }
        }

    @Test fun cleansUpWhenUiStopCannotBeFound() =
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            val failure = IllegalStateException("Injected missing Stop control")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                runPhysicalLiveSession(resources, { startActive(scenario) }, {}, { throw failure })
            })
            assertReleased(failure)
        }

    @Test fun cleansUpWhenALifecycleAssertionFails() =
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            val failure = AssertionError("Injected lifecycle assertion failure")
            assertSame(failure, assertThrows(AssertionError::class.java) {
                runPhysicalLiveSession(resources, { startActive(scenario) }, { throw failure }, {
                    error("Verification failed before UI Stop")
                })
            })
            assertReleased(failure)
        }

    @Test fun normalUiStopReleasesServiceAndListener() =
        ActivityScenario.launch(LiveActivity::class.java).use { scenario ->
            runPhysicalLiveSession(resources, { startActive(scenario) }, {}, {
                val start = SystemClock.elapsedRealtime()
                checkNotNull(device.wait(Until.findObject(By.res("live-stop").enabled(true)), 12_000)).click()
                await { LiveSessions.updates.value.phase == SessionPhase.IDLE && !resources.hasService() && !resources.registered() }
                assertNull(LiveSessions.updates.value.reading)
                SystemClock.elapsedRealtime() - start
            })
            assertReleased()
        }

    @Test fun firstTestFailureSurvivesMultipleCleanupFailures() {
        val original = AssertionError("Injected test failure")
        val failedRemoval = IllegalStateException("Injected listener removal failure")
        val failedClear = IllegalStateException("Injected window cleanup failure")
        var observerRemoved = false
        val observed = assertThrows(AssertionError::class.java) {
            withPhysicalTestCleanup(cleanup = {
                attempt("remove listener") { throw failedRemoval }
                attempt("unregister observer") { observerRemoved = true }
                attempt("clear window flag") { throw failedClear }
            }) {
                throw original
            }
        }
        assertSame(original, observed)
        assertTrue(observerRemoved)
        assertEquals(listOf(failedRemoval, failedClear), observed.suppressed.toList())
    }

    @Test fun cleanupFailureFailsAnOtherwisePassingTest() {
        val cleanupFailure = IllegalStateException("Injected cleanup failure")
        val observed = assertThrows(IllegalStateException::class.java) {
            withPhysicalTestCleanup(cleanup = {
                attempt("cleanup") { throw cleanupFailure }
            }) { Unit }
        }
        assertSame(cleanupFailure, observed)
    }

    private fun startActive(scenario: ActivityScenario<LiveActivity>) {
        scenario.onActivity { LiveSessions.start(it, "O01", null) }
        await { LiveSessions.updates.value.phase == SessionPhase.ACTIVE && resources.foreground() && resources.registered() }
    }

    private fun assertReleased(failure: Throwable? = null) {
        assertTrue("Emergency cleanup reported a failure", failure?.suppressed.isNullOrEmpty())
        assertFalse(resources.hasService())
        assertFalse(resources.registered())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        do {
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Emulator Live service or registration did not settle")
    }
}
