package lab.recovery.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class LiveRealDeviceSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<LiveActivity>()

    @Test fun startsReceivesFreshGpsAndStops() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realLocation") == "true")
        assumeTrue(Build.HARDWARE !in setOf("ranchu", "goldfish"))
        assertEquals(PackageManager.PERMISSION_GRANTED, rule.activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        rule.waitUntil(12_000) { rule.activity.hasWindowFocus() }
        rule.onNodeWithTag("live-map").performClick()
        rule.onNodeWithTag("live-map-O10").performClick()
        rule.waitUntil(12_000) { rule.activity.hasWindowFocus() }

        rule.runOnUiThread { rule.activity.window.addFlags(FLAG_KEEP_SCREEN_ON) }
        val before = SystemClock.elapsedRealtimeNanos()
        try {
            rule.onNodeWithTag("live-start").performClick()
            rule.waitUntil(20_000) {
                LiveSessions.updates.value.phase == SessionPhase.ACTIVE && isForeground()
            }
            try {
                rule.waitUntil(120_000) {
                    LiveSessions.updates.value.reading?.fix?.let { fix ->
                        fix.provider == "gps" && !fix.mock && fix.measuredNs >= before
                    } == true
                }
            } catch (timeout: ComposeTimeoutException) {
                val state = LiveSessions.updates.value
                throw AssertionError(
                    "No fresh GPS reading. Callbacks: ${state.received}, processed: ${state.processed}, " +
                        "status: ${state.reading?.status}, issue: ${state.issue}",
                    timeout,
                )
            }
        } finally {
            rule.runOnIdle {
                LiveSessions.stop(rule.activity)
                rule.activity.window.clearFlags(FLAG_KEEP_SCREEN_ON)
            }
            rule.waitUntil(12_000) {
                LiveSessions.updates.value.phase == SessionPhase.IDLE && !isForeground()
            }
            assertNull(LiveSessions.updates.value.reading)
        }
    }

    private fun isForeground(): Boolean = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys activity services lab.recovery.live")
    ).bufferedReader().use { it.readText().contains("isForeground=true") }
}
