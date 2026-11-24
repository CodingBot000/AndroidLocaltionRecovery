package lab.recovery.location

import org.junit.Assert.*
import org.junit.Test

class TrackingTest {
    private val main = Road("A", listOf(Point2D(0.0, 0.0), Point2D(1000.0, 0.0), Point2D(1500.0, 0.0)))
    private val parallel = Road("P", listOf(Point2D(0.0, 24.0), Point2D(1500.0, 24.0)))
    private val graph = RoadGraph(listOf(main, parallel), listOf("A"))
    private val single = RoadGraph(listOf(main), listOf("A"))

    private fun obs(t: Long, x: Double = 0.0, y: Double = 0.0, accuracy: Double = 6.0,
                    received: Long = t, id: String = "g$t", speed: Double? = 20.0, heading: Double? = 90.0) =
        LocationObservation(t.toInt(), id, t, received, Point2D(x, y), accuracy, speed, heading)
    private fun TrackingEngine.observe(value: LocationObservation) = step(EngineInput.Observation(value))
    private fun TrackingEngine.tick(t: Long) = step(EngineInput.Tick(t))
    private fun EngineUpdate.has(reason: Reason) = events.any { it.reason == reason }

    @Test fun geometryAndHeadingWrap() {
        val segment = Segment(0, Point2D(0.0, 0.0), Point2D(10.0, 0.0))
        assertEquals(Point2D(10.0, 0.0), segment.project(Point2D(15.0, 8.0)))
        assertEquals(Point2D(3.0, 0.0), segment.project(Point2D(3.0, -4.0)))
        assertEquals(2.0, angleDifference(359.0, 1.0), 0.0)
        assertEquals(Point2D(1250.0, 0.0), main.at(1250.0))
        assertThrows(IllegalArgumentException::class.java) { Road("bad", listOf(segment.start, segment.start)) }
    }

    @Test fun missingDirectionAndRoadTieStayAmbiguous() {
        val matcher = MapMatcher(graph, TrackingSettings())
        assertEquals(Reason.AMBIGUOUS, matcher.match(obs(0, 30.0, 12.0, speed = null), null).reason)
        val same = MapMatcher(RoadGraph(listOf(main, main.copy(id = "B")), listOf("A")),
            TrackingSettings(planWeight = 0.0))
        val tie = same.match(obs(0, 1000.0), null)
        assertEquals(listOf("A", "B"), tie.candidates.map { it.roadId })
        assertTrue(tie.candidates.all { it.segment == 0 })
        assertEquals(Reason.AMBIGUOUS, tie.reason)
        assertEquals(Reason.NO_MATCH, matcher.match(obs(0, 40.0, 200.0), null).reason)
    }

    @Test fun matchRadiusAndMaximumCostUseInclusiveBoundaries() {
        val matcher = MapMatcher(single, TrackingSettings(searchRadius = 60.0, maximumMatchCost = 1.0, planWeight = 0.0))
        assertNotNull(matcher.match(obs(0, 20.0, 60.0, heading = null), null).confirmed)
        assertEquals(Reason.NO_MATCH, matcher.match(obs(0, 20.0, 60.01, heading = null), null).reason)
        val capped = MapMatcher(single, TrackingSettings(searchRadius = 60.0, maximumMatchCost = 0.5, planWeight = 0.0))
        assertNotNull(capped.match(obs(0, 20.0, 30.0, heading = null), null).confirmed)
        assertEquals(Reason.NO_MATCH, capped.match(obs(0, 20.0, 30.01, heading = null), null).reason)
    }

    @Test fun validatesDataAndKeepsRejectedObservationsOutOfFix() {
        val engine = TrackingEngine(single)
        assertTrue(engine.observe(obs(0, accuracy = 20.0)).has(Reason.ACCEPTED))
        assertTrue(engine.observe(obs(500, y = Double.NaN)).has(Reason.INVALID_COORDINATE))
        assertTrue(engine.observe(obs(600, speed = Double.POSITIVE_INFINITY)).has(Reason.INVALID_SPEED))
        assertTrue(engine.observe(obs(700, received = 650)).has(Reason.FUTURE_MEASUREMENT))
        assertTrue(engine.observe(obs(800, accuracy = 60.0)).has(Reason.LOW_QUALITY))
        assertTrue(engine.observe(obs(900, accuracy = 60.01)).has(Reason.ACCURACY_EXCEEDED))
        val jumped = engine.observe(obs(1000, 500.0))
        assertTrue(jumped.has(Reason.IMPLAUSIBLE_MOVE))
        assertEquals(Point2D(0.0, 0.0), jumped.snapshot.acceptedPosition)
        assertEquals(0L, jumped.snapshot.lastAcceptedAtMs)
        assertTrue(engine.observe(obs(2000, 40.0)).has(Reason.ACCEPTED))
    }

    @Test fun motionBelowHeadingThresholdIgnoresUnstableHeading() {
        val engine = TrackingEngine(single)
        assertTrue(engine.observe(obs(0, 25.0, speed = 0.0, heading = null)).has(Reason.ACCEPTED))
        val slow = engine.observe(obs(1000, 25.5, speed = 0.5, heading = 270.0))
        assertTrue(slow.has(Reason.ACCEPTED))
        assertEquals(PositionSource.MAP_MATCHED, slow.snapshot.source)
        assertEquals(Point2D(25.5, 0.0), slow.snapshot.position)
    }

    @Test fun confirmedDepartureSurvivesOutageAndResumesWithoutAnotherAlert() {
        val engine = TrackingEngine(graph)
        val first = engine.observe(obs(0, 30.0, 24.0))
        assertEquals(RouteStatus.SUSPECTED_OFF_ROUTE, first.snapshot.route)
        engine.observe(obs(1000, 50.0, 24.0))
        val beforeLoss = engine.observe(obs(2000, 70.0, 24.0))
        assertTrue(beforeLoss.has(Reason.REROUTE_RECOMMENDED))
        assertEquals(RouteStatus.UNKNOWN, engine.tick(5000).snapshot.route)
        engine.observe(obs(5500, 130.0, 24.0))
        engine.observe(obs(6500, 140.0, 24.0))
        val recovered = engine.observe(obs(7500, 150.0, 24.0))
        assertEquals(RouteStatus.OFF_ROUTE, recovered.snapshot.route)
        assertTrue(recovered.has(Reason.SIGNAL_RECOVERED))
        assertFalse(recovered.has(Reason.REROUTE_RECOMMENDED))
    }

    @Test fun duplicateAndTemporalRejectionsHaveStableWatermark() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0))
        assertTrue(engine.observe(obs(1000,id = "g0")).has(Reason.DUPLICATE_ID))
        val accepted = engine.observe(obs(700, 10.0, received = 1000))
        assertEquals(700L, accepted.snapshot.measurementWatermarkMs)
        assertEquals(700L, accepted.snapshot.lastAcceptedAtMs)
        assertTrue(engine.observe(obs(700, id = "other", received = 1100)).has(Reason.MEASUREMENT_ORDER))
        assertTrue(engine.observe(obs(1800, received = 4801)).has(Reason.DELIVERY_DELAY))
        assertTrue(engine.observe(obs(1900, received = 4900)).has(Reason.ACCEPTED))
    }

    @Test fun observationsAtTimeoutRunFirstWhenManuallySequenced() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0))
        assertEquals(SignalState.TRACKING, engine.tick(2999).snapshot.signal)
        assertTrue(engine.observe(obs(3000, 60.0)).has(Reason.ACCEPTED))
        assertFalse(engine.tick(3000).has(Reason.SIGNAL_LOST))
        assertTrue(engine.tick(6100).has(Reason.SIGNAL_LOST))
    }

    @Test fun pendingCandidatesAreIndependentOfLostTimerAndExactDeadlineKeepsChain() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0))
        engine.tick(3000)
        val first = engine.observe(obs(5000, 100.0))
        assertEquals(0L, first.snapshot.lastAcceptedAtMs)
        assertEquals(1, engine.tick(6900).snapshot.recoveryCandidates)
        assertTrue(engine.observe(obs(7000, 140.0)).has(Reason.RECOVERY_PENDING))
        assertEquals(2, engine.tick(7000).snapshot.recoveryCandidates)
        val recovered = engine.observe(obs(8000, 160.0))
        assertEquals(8_000L, recovered.snapshot.lastAcceptedAtMs)
        assertEquals(2, recovered.events.count { it.recoveryOutcome == RecoveryOutcome.CONTRIBUTED })
    }

    @Test fun recoveryRejectionsPreserveOrClearAccordingToType() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0))
        engine.tick(3000)
        engine.observe(obs(5000, 100.0))
        val duplicate = engine.observe(obs(5500, 105.0, id = "g5000"))
        assertTrue(duplicate.has(Reason.DUPLICATE_ID))
        assertEquals(1, duplicate.snapshot.recoveryCandidates)
        val reverse = engine.observe(obs(4800, 105.0, received = 5600))
        assertTrue(reverse.has(Reason.MEASUREMENT_ORDER))
        assertEquals(1, reverse.snapshot.recoveryCandidates)
        val poor = engine.observe(obs(6000, 110.0, accuracy = 35.0))
        assertTrue(poor.has(Reason.LOW_QUALITY))
        assertTrue(poor.has(Reason.RECOVERY_CLEARED))
        assertEquals(SignalState.LOST, poor.snapshot.signal)
        assertEquals(0L, poor.snapshot.lastAcceptedAtMs)
    }

    @Test fun lateTickAndOverlappingRecoveryViolations() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0))
        engine.tick(3000)
        engine.observe(obs(5000, received = 5100))
        assertFalse(engine.tick(7000).has(Reason.RECOVERY_TIMEOUT))
        val fresh = engine.observe(obs(7075, 200.0, received = 7150))
        assertEquals(1, fresh.events.count { it.reason == Reason.RECOVERY_TIMEOUT })
        assertFalse(fresh.has(Reason.RECOVERY_GAP))
        assertTrue(fresh.has(Reason.RECOVERY_PENDING))
        assertEquals(1, fresh.snapshot.recoveryCandidates)
    }

    @Test fun pendingRecoveryDoesNotReplaceAndDrStopsAtLimit() {
        val engine = TrackingEngine(single)
        engine.observe(obs(0, 30.0, speed = 20.0))
        assertEquals(Point2D(90.0, 0.0), engine.tick(3000).snapshot.position)
        engine.observe(obs(4000, 75.0))
        assertEquals(Point2D(130.0, 0.0), engine.tick(5000).snapshot.position)
        assertEquals(1, engine.tick(5800).snapshot.recoveryCandidates)
        assertTrue(engine.tick(6000).has(Reason.RECOVERY_TIMEOUT))
        val ended = engine.tick(15000)
        assertTrue(ended.has(Reason.DR_LIMIT))
        assertEquals(PositionSource.LAST_KNOWN, ended.snapshot.source)
        assertEquals(Point2D(30.0, 0.0), ended.snapshot.position)
    }

    @Test fun drZeroMissingAndDerivedSpeedAreDistinct() {
        val parked = TrackingEngine(single)
        parked.observe(obs(0, 10.0, speed = 0.0))
        assertEquals(PositionSource.DEAD_RECKONED, parked.tick(3000).snapshot.source)
        assertEquals(Point2D(10.0, 0.0), parked.tick(3300).snapshot.position)
        val missing = TrackingEngine(single)
        missing.observe(obs(0, 10.0, speed = null))
        assertEquals(PositionSource.LAST_KNOWN, missing.tick(3000).snapshot.source)
        val derived = TrackingEngine(single)
        derived.observe(obs(0, 10.0, speed = null))
        derived.observe(obs(1000, 20.0, speed = null))
        assertEquals(Point2D(50.0, 0.0), derived.tick(4000).snapshot.position)
    }

    @Test fun ambiguousNewFixInvalidatesOlderDrAnchor() {
        val engine = TrackingEngine(graph)
        engine.observe(obs(0, 25.0))
        engine.observe(obs(1000, 45.0))
        val unpaired = engine.observe(obs(2000, 65.0, 18.0, heading = null))
        assertEquals(PositionSource.OBSERVED, unpaired.snapshot.source)
        assertTrue(unpaired.has(Reason.AMBIGUOUS))
        val lost = engine.tick(5000)
        assertEquals(Point2D(65.0, 18.0), lost.snapshot.position)
        assertEquals(PositionSource.LAST_KNOWN, lost.snapshot.source)
        assertTrue(lost.has(Reason.DR_NO_ANCHOR))
    }

    @Test fun drFollowsOnlyUnambiguousConnectedRoads() {
        val a = Road("A", listOf(Point2D(0.0, 0.0), Point2D(100.0, 0.0)))
        val b = Road("B", listOf(Point2D(100.0, 0.0), Point2D(200.0, 0.0)))
        val d = Road("D", listOf(Point2D(100.0, 0.0), Point2D(100.0, 80.0)))
        val anchor = DeadReckoningAnchor(Point2D(90.0, 0.0), "A", 20.0, 0, 90.0)
        assertEquals(Point2D(150.0, 0.0), estimate(anchor, RoadGraph(listOf(a,b), listOf("A","B")), 3000, TrackingSettings()).position)
        assertEquals(Reason.DR_BRANCH, estimate(anchor, RoadGraph(listOf(a,b,d), listOf("A","B")), 3000, TrackingSettings()).reason)
        assertEquals(Reason.DR_ROUTE_END, estimate(anchor, RoadGraph(listOf(a), listOf("A")), 3000, TrackingSettings()).reason)
    }

    @Test fun routeEpisodeSurvivesUnknownAndRequiresThreeConsecutiveReturnFixes() {
        val route = RouteDetector(graph, TrackingSettings())
        assertNull(route.observe("P", 0))
        assertEquals(RouteStatus.SUSPECTED_OFF_ROUTE, route.status)
        assertNull(route.observe("P", 1000))
        assertEquals(Reason.REROUTE_RECOMMENDED, route.observe("P", 2000))
        route.breakContinuity(true)
        assertEquals(RouteStatus.UNKNOWN, route.status)
        assertNull(route.observe("P", 5000))
        assertEquals(RouteStatus.OFF_ROUTE, route.status)
        assertNull(route.observe("A", 6000))
        assertNull(route.observe("A", 7000))
        assertEquals(RouteStatus.OFF_ROUTE, route.status)
        route.observe(null, 8000)
        assertEquals(RouteStatus.UNKNOWN, route.status)
        assertNull(route.observe("A", 9000))
        assertNull(route.observe("A", 10000))
        assertEquals(Reason.ROUTE_RETURNED, route.observe("A", 11000))
        route.observe("P", 12000)
        route.observe("P", 13000)
        assertEquals(Reason.REROUTE_RECOMMENDED, route.observe("P", 14000))
    }
}
