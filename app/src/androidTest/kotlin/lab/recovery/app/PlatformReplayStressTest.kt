package lab.recovery.app

import android.os.Debug
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import lab.recovery.feature.LabViewModel
import lab.recovery.feature.Playback
import lab.recovery.feature.UiIntent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlatformReplayStressTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun twentyMapChangesAndReplaysDropOldWorkAcrossRecreation() {
        val model = ViewModelProvider(rule.activity)[LabViewModel::class.java]
        rule.waitUntil(10_000) { model.state.value.choices.any { it.id == "OL01" } }
        repeat(20) { cycle ->
            val previous = model.state.value.runId
            val scenario = if (cycle % 2 == 0) "OL01" else "ODEMO_LONG"
            val choice = model.state.value.choices.single { it.id == scenario }
            model.send(UiIntent.Select(choice))
            rule.waitUntil(15_000) {
                val current = model.state.value
                current.runId > previous && current.scenario?.id == scenario && current.playback == Playback.READY &&
                    rule.onAllNodesWithTag("camera-mode").fetchSemanticsNodes().isNotEmpty()
            }
            val run = model.state.value.runId
            assertTrue(model.state.value.logs.isEmpty())
            assertNull(model.state.value.evaluation)
            model.send(UiIntent.PlayPause)
            rule.waitUntil(6_000) { model.state.value.runId == run && model.state.value.snapshot.nowMs >= 400L }
            model.send(UiIntent.PlayPause)
            rule.waitUntil(5_000) { model.state.value.playback == Playback.PAUSED }
            if (cycle % 5 == 4) {
                rule.activityRule.scenario.recreate()
                assertEquals(run, ViewModelProvider(rule.activity)[LabViewModel::class.java].state.value.runId)
            }
            if (cycle in setOf(0, 1, 18, 19)) {
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                Log.i("PlatformReplayStress", "cycle=$cycle scenario=$scenario pss_kib=${memory.totalPss}")
            }
        }
        val previous = model.state.value.runId
        model.send(UiIntent.Reset)
        rule.waitUntil(10_000) {
            model.state.value.runId > previous && model.state.value.playback == Playback.READY
        }
        assertEquals(0L, model.state.value.snapshot.nowMs)
        assertTrue(model.state.value.logs.isEmpty())
        assertTrue(model.state.value.trails.isEmpty())
    }
}
