package lab.recovery.feature

import lab.recovery.map.Point2D
import lab.recovery.map.Road
import lab.recovery.map.RoadGraph
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import lab.recovery.location.*
import lab.recovery.simulation.Scenario
import lab.recovery.simulation.TruthLabel
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LabViewModelTest {
    private val main = Road("A", listOf(Point2D(0.0, 0.0), Point2D(500.0, 0.0)))
    private fun scenario(id: String, count: Int = 6): Scenario {
        val observations = (0 until count).map { i ->
            LocationObservation(i, "o$i", 200L * i, 200L * i, Point2D(i / 4.0, 0.0), 5.0, null, null)
        }
        return Scenario(id, id, 1, "test", count * 200L,
            RoadGraph(listOf(main), listOf("A")), observations, emptyList(), observations.associate { it.sequence to TruthLabel.NORMAL }, emptyList())
    }

    @Test fun serialReplayPauseResetChangeAndClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val vm = LabViewModel({ scenario(it.id) }, dispatcher, dispatcher)
            store.put("lab", vm)
            testScheduler.runCurrent()
            assertEquals(Playback.READY, vm.state.value.playback)
            vm.send(UiIntent.Step)
            testScheduler.runCurrent()
            assertEquals(Playback.PAUSED, vm.state.value.playback)
            assertEquals(SignalState.TRACKING, vm.state.value.snapshot.signal)
            vm.send(UiIntent.PlayPause)
            testScheduler.runCurrent()
            vm.send(UiIntent.PlayPause)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(3000)
            assertEquals(0L, vm.state.value.snapshot.nowMs)
            val run = vm.state.value.runId
            vm.send(UiIntent.Reset)
            vm.send(UiIntent.Select(scenarioChoices[1]))
            vm.send(UiIntent.Speed(5f))
            testScheduler.runCurrent()
            assertTrue(vm.state.value.runId > run)
            assertEquals("S01", vm.state.value.scenario?.id)
            vm.send(UiIntent.PlayPause)
            testScheduler.advanceUntilIdle()
            assertEquals(Playback.COMPLETE, vm.state.value.playback)
            assertEquals(6, vm.state.value.evaluation!!.observations)
            store.clear()
            vm.send(UiIntent.Reset)
            testScheduler.advanceUntilIdle()
            assertEquals(Playback.COMPLETE, vm.state.value.playback)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun uiLimitsDoNotDropEvaluationInputs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val store = ViewModelStore()
        try {
            val vm = LabViewModel({ scenario(it.id, 1100) }, dispatcher, dispatcher, clock = object : lab.recovery.simulation.ReplayClock {
                override fun nowMs() = testScheduler.currentTime
                override suspend fun await(delayMs: Long) = Unit
            })
            store.put("lab", vm)
            testScheduler.runCurrent()
            vm.send(UiIntent.PlayPause)
            testScheduler.advanceUntilIdle()
            assertEquals(500, vm.state.value.logs.size)
            assertEquals(1000, vm.state.value.trails.filter { it.source == PositionSource.MAP_MATCHED }.sumOf { it.points.size })
            assertEquals(1100, vm.state.value.evaluation?.observations)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
