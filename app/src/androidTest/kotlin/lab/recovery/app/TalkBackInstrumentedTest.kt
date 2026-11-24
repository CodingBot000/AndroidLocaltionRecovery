package lab.recovery.app

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import lab.recovery.feature.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TalkBackInstrumentedTest {
    @Test fun mapAndReplayRespondToAccessibilityFocusAndActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assumeTrue("Enable TalkBack on the device to run this test", manager.isTouchExplorationEnabled &&
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
                .any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" })

        ActivityScenario.launch(MainActivity::class.java).use {
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            val screen = AccessibleLab(automation)
            val context = instrumentation.targetContext
            val follow = context.getString(R.string.camera_follow)
            val overview = context.getString(R.string.camera_overview)
            val free = context.getString(R.string.camera_free)
            val play = context.getString(R.string.play)
            screen.waitFor("camera-mode") { it.text?.toString() == follow }
            screen.focus("map-status")
            screen.focusAndClick("overview")
            screen.waitFor("camera-mode") { it.text?.toString() == overview }
            screen.focusAndClick("zoom-in")
            screen.waitFor("camera-mode") { it.text?.toString() == free }
            screen.focusAndClick("recenter")
            screen.waitFor("camera-mode") { it.text?.toString() == follow }

            screen.focusAndClick("step")
            screen.waitFor("signal") { it.text?.toString() == "TRACKING" }
            screen.focusAndClick("step")
            screen.waitFor("virtual-time") { it.text?.toString() == "00:00.2 / 03:00.0" }
            screen.focusAndClick("play-pause")
            screen.waitFor("virtual-time") {
                it.text?.toString()?.substringBefore(" / ")?.let { time -> time >= "00:00.8" } == true
            }
            screen.focusAndClick("play-pause")
            screen.waitFor("play-pause") {
                (0 until it.childCount).any { i -> it.getChild(i)?.text?.toString() == play }
            }
            assertTrue(manager.isTouchExplorationEnabled)
        }
    }

    private class AccessibleLab(private val automation: UiAutomation) {
        fun waitFor(tag: String, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5000
            do {
                val root = automation.rootInActiveWindow
                val node = root?.let(::descendants)?.firstOrNull { it.viewIdResourceName == tag }
                if (node != null && node.isVisibleToUser && condition(node)) return node
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            val visible = automation.rootInActiveWindow?.let(::descendants)
                ?.filter { it.isVisibleToUser }?.mapNotNull { it.viewIdResourceName }?.toList()
            error("Accessibility node $tag did not reach the expected state. Visible tags: $visible")
        }

        private fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { yieldAll(descendants(it)) }
            }
        }

        fun focus(tag: String) = perform(tag, click = false)

        fun focusAndClick(tag: String) = perform(tag, click = true)

        private fun perform(tag: String, click: Boolean) {
            val event = automation.executeAndWaitForEvent({
                val node = waitFor(tag) { it.isEnabled && (!click || it.isClickable) }
                if (node.isAccessibilityFocused) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                }
                assertTrue("Accessibility focus for $tag", node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                if (click) {
                    assertTrue("Accessibility click for $tag", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
            }, { event ->
                event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED &&
                    event.source?.viewIdResourceName == tag
            }, 5000)
            assertEquals(tag, event.source?.viewIdResourceName)
        }
    }
}
