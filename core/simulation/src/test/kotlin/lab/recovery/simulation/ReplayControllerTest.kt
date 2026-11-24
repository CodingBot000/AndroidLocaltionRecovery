package lab.recovery.simulation

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayControllerTest {
    private val fixtures = File(checkNotNull(System.getProperty("fixtureRoot")))
    private val repository = ScenarioRepository { File(fixtures, it).inputStream() }

    @Test fun aStepProcessesAllEventsAtTheNextScheduledTimestamp() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        ReplayController(this, "L01", repository::read, dispatcher, dispatcher).use { controller ->
            testScheduler.runCurrent()
            assertTrue(controller.send(ReplayIntent.Step))
            testScheduler.runCurrent()
            assertEquals(Playback.PAUSED, controller.state.value.playback)
            assertEquals(0L, controller.state.value.snapshot.lastAcceptedAtMs)
            assertTrue(controller.send(ReplayIntent.Step))
            testScheduler.runCurrent()
            assertEquals(200L, controller.state.value.snapshot.nowMs)
        }
    }

    @Test fun pauseAndRateChangePreserveElapsedFractionOfDeadline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = object : ReplayClock {
            override fun nowMs() = testScheduler.currentTime
            override suspend fun await(delayMs: Long) = delay(delayMs)
        }
        ReplayController(this, "L01", repository::read, dispatcher, dispatcher, clock).use { controller ->
            val scheduler = testScheduler
            scheduler.runCurrent()
            controller.send(ReplayIntent.PlayPause)
            scheduler.runCurrent()
            scheduler.advanceTimeBy(100)
            controller.send(ReplayIntent.PlayPause)
            scheduler.runCurrent()
            scheduler.advanceTimeBy(5000)
            assertEquals(0L, controller.state.value.snapshot.nowMs)
            controller.send(ReplayIntent.Speed(2f))
            controller.send(ReplayIntent.PlayPause)
            scheduler.runCurrent()
            scheduler.advanceTimeBy(49)
            scheduler.runCurrent()
            assertEquals(0L, controller.state.value.snapshot.nowMs)
            scheduler.advanceTimeBy(1)
            scheduler.runCurrent()
            assertEquals(200L, controller.state.value.snapshot.nowMs)
            scheduler.advanceTimeBy(50)
            controller.send(ReplayIntent.Speed(1f))
            scheduler.runCurrent()
            scheduler.advanceTimeBy(99)
            scheduler.runCurrent()
            assertEquals(200L, controller.state.value.snapshot.nowMs)
            scheduler.advanceTimeBy(1)
            scheduler.runCurrent()
            assertEquals(400L, controller.state.value.snapshot.nowMs)
        }
    }

    @Test fun resetOnCompletionPreventsAnEarlierEvaluationFromBeingPublished() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = repository.read("L01")
        val tiny = loaded.copy(durationMs = 0L, observations = loaded.observations.take(1))
        ReplayController(this, "L01", { tiny }, dispatcher, dispatcher).use { controller ->
            testScheduler.runCurrent()
            val firstRun = controller.state.value.runId
            launch(start = CoroutineStart.UNDISPATCHED) {
                controller.state.first { it.playback == Playback.COMPLETE && it.evaluation == null }
                assertTrue(controller.send(ReplayIntent.Reset))
            }
            assertTrue(controller.send(ReplayIntent.Step))
            testScheduler.advanceUntilIdle()
            with(controller.state.value) {
                assertEquals(Playback.READY, playback)
                assertTrue(runId > firstRun)
                assertNull(evaluation)
                assertTrue(logs.isEmpty())
            }
        }
    }

    @Test fun overdueBatchesRetainEveryEventAndResetInvalidatesAnOldCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = object : ReplayClock {
            private var lateBy = 0L
            override fun nowMs() = testScheduler.currentTime + lateBy
            override suspend fun await(delayMs: Long) {
                delay(delayMs)
                lateBy += 9000
            }
        }
        ReplayController(this, "DEMO_LONG", repository::read, dispatcher, dispatcher, clock).use { controller ->
            val scheduler = testScheduler
            scheduler.runCurrent()
            val expected = playToEnd(ReplaySession(repository.read("DEMO_LONG")))
            controller.send(ReplayIntent.Speed(5f))
            controller.send(ReplayIntent.PlayPause)
            scheduler.advanceUntilIdle()
            val completed = controller.state.value
            assertEquals(expected, completed.evaluation)
            assertEquals(180_000L, completed.snapshot.nowMs)
            assertEquals(1, completed.logs.count { it.reason.name == "RUN_COMPLETED" })
            controller.send(ReplayIntent.Reset)
            controller.send(ReplayIntent.Select("L03"))
            scheduler.advanceUntilIdle()
            val restarted = controller.state.value
            assertEquals("L03", restarted.selected)
            assertTrue(restarted.runId > completed.runId)
            assertNull(restarted.evaluation)
            assertTrue(restarted.logs.isEmpty())
            assertTrue(restarted.trails.isEmpty())
        }
    }
}
