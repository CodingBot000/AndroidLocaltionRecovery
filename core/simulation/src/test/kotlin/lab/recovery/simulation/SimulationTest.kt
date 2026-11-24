package lab.recovery.simulation

import lab.recovery.map.Point2D
import lab.recovery.map.Road
import lab.recovery.map.RoadGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import lab.recovery.location.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationTest {
    private val root = File(checkNotNull(System.getProperty("fixtureRoot")))
    private fun fixture(path: String): Scenario = FixtureReader.read(root.resolve("$path.json").inputStream())

    @Test fun allIndependentFixturesAndCompositeConform() = runTest {
        val runnable = root.resolve("evaluation").listFiles()!!.filter { it.nameWithoutExtension !in setOf("S17A","S17B","S17C") } +
            listOf(root.resolve("development/DEMO.json"))
        assertTrue((1..17).all { i -> runnable.any { it.nameWithoutExtension == "S%02d".format(i) } })
        for (file in runnable) {
            val scenario = FixtureReader.read(file.inputStream())
            val result = playToEnd(ReplaySession(scenario))
            assertEquals(file.nameWithoutExtension, result.scenarioId)
            assertEquals(result.observations, result.decisions.values.sumOf { it.values.sum() })
            assertTrue("${file.name}: ${result.checks.filterNot(RegressionCheck::passed)}", result.checks.all(RegressionCheck::passed))
        }
    }

    @Test fun fixtureErrorsFailBeforeReplay() {
        for ((name, reason) in mapOf("S17A" to FixtureError.ROAD_GEOMETRY,
            "S17B" to FixtureError.REPLAY_TIME, "S17C" to FixtureError.SCHEMA)
        ) {
            val invalid = assertThrows(InvalidFixture::class.java) { fixture("evaluation/$name") }
            assertEquals(reason, invalid.error)
        }
        val oversized = ByteArray(FixtureProfile.STANDARD.byteLimit + 1)
        val limit = assertThrows(InvalidFixture::class.java) { FixtureReader.read(oversized.inputStream()) }
        assertEquals(FixtureError.SIZE_LIMIT, limit.error)
        val source = root.resolve("evaluation/S15.json").readText()
        val longFixture = source.replace("\"duration\":8150", "\"duration\":900001")
        assertEquals(FixtureError.DURATION_LIMIT, assertThrows(InvalidFixture::class.java) {
            FixtureReader.read(longFixture.byteInputStream())
        }.error)
    }

    @Test fun stepsAndPlaybackSpeedsProduceIdenticalFramesAndMetrics() = runTest {
        val scenario = fixture("evaluation/S15")
        val manual = ReplaySession(scenario)
        val frames = buildList { while (!manual.complete) add(manual.step()!!) }
        assertEquals(8150L, frames.last().update.snapshot.nowMs)
        assertTrue(frames.last().complete)
        assertEquals(1, frames.last().update.events.count { it.reason == Reason.RUN_COMPLETED })
        val result = manual.result()
        for (speed in PlaybackSpeeds.values) {
            val replayed = mutableListOf<ReplayFrame>()
            val before = testScheduler.currentTime
            val actual = playToEnd(ReplaySession(scenario), { speed }, { delay(it) }, replayed::add)
            assertEquals(frames, replayed)
            assertEquals(result, actual)
            val exactDelay = when (speed) {
                0.5f -> 16_300L
                1f -> 8_150L
                5f -> 1_630L
                else -> null
            }
            exactDelay?.let { assertEquals(it, testScheduler.currentTime - before) }
        }
        assertNull(manual.step())
    }

    @Test fun sameTimeInputsPrecedeTickAndCompletion() = runTest {
        val road = Road("A", listOf(Point2D(0.0, 0.0), Point2D(150.0, 0.0)))
        fun row(n: Int, id: String, t: Long) = LocationObservation(n, id, t, t, Point2D(0.0, 0.0), 2.0, 0.0, 0.0)
        val scenario = Scenario("UNIT", "Boundary", 1, "unit", 3000, RoadGraph(listOf(road), listOf("A")),
            listOf(row(0,"a",0),row(1,"b",3000),row(2,"c",3000)), emptyList(), emptyMap(), emptyList())
        val frames = mutableListOf<ReplayFrame>()
        playToEnd(ReplaySession(scenario), onFrame = frames::add)
        val last = frames.last()
        assertEquals(listOf(Reason.ACCEPTED, Reason.MEASUREMENT_ORDER, Reason.RUN_COMPLETED), last.update.events.map { it.reason })
        assertEquals(3000L, last.update.snapshot.lastAcceptedAtMs)
    }

    @Test fun metricsCompareIndependentLabelsAndCountEachInputOnce() {
        val road = Road("A", listOf(Point2D(0.0, 0.0), Point2D(150.0, 0.0)))
        val observations = (0..8).map {
            LocationObservation(it, "same-id", it * 100L, it * 100L + 100, Point2D(it * 10.0 + 2, 0.0), 5.0, null, null)
        }
        val labels = (0..8).associateWith {
            when (it) { in 0..4 -> TruthLabel.NORMAL; in 5..6 -> TruthLabel.INVALID; else -> TruthLabel.LOW_QUALITY }
        }
        val scenario = Scenario("UNIT", "Metrics", 1, "unit", 1000, RoadGraph(listOf(road), listOf("A")),
            observations, listOf(TruthPoint(0, Point2D(0.0,0.0), null), TruthPoint(1000, Point2D(100.0,0.0),"A")),
            labels, emptyList())
        val evaluator = Evaluator(scenario)
        val decisions = listOf(ObservationDecision.ACCEPTED, ObservationDecision.REJECTED,
            ObservationDecision.RECOVERY_PENDING, ObservationDecision.RECOVERY_PENDING, ObservationDecision.RECOVERY_PENDING,
            ObservationDecision.ACCEPTED, ObservationDecision.REJECTED, ObservationDecision.ACCEPTED, ObservationDecision.LOW_QUALITY)
        val events = observations.zip(decisions).map { (obs, decision) ->
            val reason = if (decision == ObservationDecision.REJECTED) Reason.IMPLAUSIBLE_MOVE else Reason.valueOf(decision.name)
            DecisionEvent(1, obs.receivedAtMs, reason, SignalState.TRACKING, SignalState.TRACKING,
                obs, decision, position = if (decision == ObservationDecision.ACCEPTED) Point2D(obs.sequence * 10.0,0.0) else null)
        }
        evaluator.recordEvents(events + events)
        listOf(RecoveryOutcome.CONTRIBUTED, RecoveryOutcome.EXPIRED, RecoveryOutcome.CLEARED).forEachIndexed { index, outcome ->
            val followup = events[index + 2].copy(decision = null, recoveryOutcome = outcome)
            evaluator.recordEvents(listOf(followup, followup))
        }
        val actual = evaluator.result()
        assertEquals(Ratio(1,5), actual.falseRejection)
        assertEquals(Ratio(1,2), actual.invalidAcceptance)
        assertEquals(Ratio(1,2), actual.lowQualityUse)
        assertEquals(3, actual.pendingByLabel[TruthLabel.NORMAL])
        assertEquals(1, actual.recoveryOutcomes[RecoveryOutcome.EXPIRED])
        assertEquals(2.0, actual.rawErrorMeters.mean!!, 0.0001)
        assertEquals(0.0, actual.pairedAcceptedErrorMeters.mean!!, 0.0001)
        assertEquals("N/A", actual.matchAccuracy.display())
        assertEquals("N/A", Evaluator(scenario.copy(labels = emptyMap())).result().falseRejection.display())
    }

    @Test fun stressProfileReplaysTwentyThousandDeterministicInputs() = runTest {
        val count = 20_000
        val rows = (0 until count).joinToString(",") { i ->
            """{"id":"o$i","measured":${i*200},"received":${i*200},"x":20,"y":0,"accuracy":4,"speed":0,"label":"NORMAL"}"""
        }
        val source = """{"schemaVersion":1,"scenarioId":"STRESS","title":"Stress","duration":${count*200},
            "roads":[{"id":"A","points":[[0,0],[200,0]]}],"plannedRoute":["A"],"observations":[$rows],
            "groundTruth":[{"time":0,"x":20,"y":0,"road":"A"},{"time":${count*200},"x":20,"y":0,"road":"A"}],"expected":[]}"""
        assertThrows(InvalidFixture::class.java) { FixtureReader.read(source.byteInputStream()) }
        val scenario = FixtureReader.read(source.byteInputStream(), FixtureProfile.STRESS)
        val first = playToEnd(ReplaySession(scenario))
        val second = playToEnd(ReplaySession(scenario))
        assertEquals(first, second)
        assertEquals(count, first.reasonCounts[Reason.ACCEPTED])
        assertEquals(1, first.reasonCounts[Reason.RUN_COMPLETED])
        assertEquals(count + 1, first.availability[PositionSource.MAP_MATCHED])
    }
}
