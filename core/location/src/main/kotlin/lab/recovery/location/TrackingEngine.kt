package lab.recovery.location

import lab.recovery.map.Point2D
import lab.recovery.map.RoadGraph
class TrackingEngine(
    private val graph: RoadGraph,
    private val settings: TrackingSettings = TrackingSettings(),
    private val runId: Long = 1,
) {
    private data class Candidate(val observation: LocationObservation, val match: MatchResult)

    private val matcher = MapMatcher(graph, settings)
    private val routeDetector = RouteDetector(graph, settings)
    private val ids = mutableSetOf<String>()
    private val recovery = mutableListOf<Candidate>()
    private val events = mutableListOf<DecisionEvent>()
    private var accepted: Candidate? = null
    private var anchor: DeadReckoningAnchor? = null
    private var latest: LocationObservation? = null
    private var watermark: Long? = null
    private var lastDecision: Reason? = null
    private var signal = SignalState.ACQUIRING
    private var lastEstimate = Estimate()
    private var now = 0L
    private var finished = false

    fun step(input: EngineInput): EngineUpdate {
        check(!finished)
        require(input.timeMs >= now)
        now = input.timeMs
        events.clear()
        when (input) {
            is EngineInput.Observation -> observe(input.value)
            is EngineInput.Tick -> tick()
        }
        updateEstimate()
        return EngineUpdate(snapshot(), events.toList())
    }

    fun complete(): EngineUpdate {
        check(!finished)
        finished = true
        events.clear()
        resolveRecovery(RecoveryOutcome.UNRESOLVED, Reason.RECOVERY_UNRESOLVED)
        event(Reason.RUN_COMPLETED)
        return EngineUpdate(snapshot(), events.toList())
    }

    private fun observe(observation: LocationObservation) {
        val before = signal
        latest = observation
        val invalidFormat = formatError(observation)
        if (invalidFormat != null) {
            reject(observation, invalidFormat, before, breaksRecovery = true)
            return
        }
        val timeError = when {
            observation.measuredAtMs > now -> Reason.FUTURE_MEASUREMENT
            !ids.add(observation.id) -> Reason.DUPLICATE_ID
            watermark?.let { observation.measuredAtMs <= it } == true -> Reason.MEASUREMENT_ORDER
            else -> null
        }
        if (timeError != null) {
            reject(observation, timeError, before, breaksRecovery = false)
            return
        }

        watermark = observation.measuredAtMs
        if (now - observation.measuredAtMs > settings.maximumDelayMs) {
            reject(observation, Reason.DELIVERY_DELAY, before, breaksRecovery = false)
            return
        }
        if (observation.accuracyMeters > settings.goodAccuracy) {
            val reason = if (observation.accuracyMeters <= settings.maximumAccuracy) {
                Reason.LOW_QUALITY
            } else Reason.ACCURACY_EXCEEDED
            reject(observation, reason, before, breaksRecovery = true)
            return
        }

        val recovering = signal == SignalState.LOST || signal == SignalState.RECOVERING
        if (recovering) checkRecoveryGap(observation)
        val previous = recovery.lastOrNull() ?: accepted
        if (previous != null && !withinMovement(previous.observation, observation, settings)) {
            reject(observation, Reason.IMPLAUSIBLE_MOVE, before, breaksRecovery = true)
            return
        }

        val match = matcher.match(observation, accepted?.match?.confirmed?.roadId?.takeUnless { recovering })
        if (!recovering) {
            accept(Candidate(observation, match), before, recovering = false)
            return
        }

        val priorCandidate = recovery.lastOrNull()
        val priorMatch = priorCandidate?.match?.confirmed
        val newMatch = match.confirmed
        if (priorCandidate != null && priorMatch != null && newMatch != null &&
            !recoveryRoadContinuous(graph, priorMatch, newMatch,
                movementLimit(priorCandidate.observation, observation, settings))
        ) {
            reject(observation, Reason.RECOVERY_ROAD_DISCONTINUITY, before, breaksRecovery = true)
            return
        }
        val candidate = Candidate(observation, match)
        if (recovery.size + 1 >= settings.recoveryCount) {
            accept(candidate, before, recovering = true)
        } else {
            signal = SignalState.RECOVERING
            recovery += candidate
            decision(Reason.RECOVERY_PENDING, observation, ObservationDecision.RECOVERY_PENDING, before, match)
        }
    }

    private fun checkRecoveryGap(observation: LocationObservation) {
        val previous = recovery.lastOrNull()?.observation ?: return
        when {
            now - previous.receivedAtMs > settings.recoveryWaitMs ->
                abandonRecovery(RecoveryOutcome.EXPIRED, Reason.RECOVERY_TIMEOUT)
            observation.measuredAtMs - previous.measuredAtMs > settings.recoveryMeasuredGapMs ->
                abandonRecovery(RecoveryOutcome.CLEARED, Reason.RECOVERY_GAP)
        }
    }

    private fun reject(obs: LocationObservation, reason: Reason, before: SignalState, breaksRecovery: Boolean) {
        when (signal) {
            SignalState.TRACKING, SignalState.DEGRADED -> {
                signal = SignalState.DEGRADED
                routeDetector.breakContinuity(unknown = accepted?.match?.confirmed == null)
            }
            SignalState.RECOVERING -> if (breaksRecovery) abandonRecovery(RecoveryOutcome.CLEARED, Reason.RECOVERY_CLEARED)
            else -> Unit
        }
        val result = if (reason == Reason.LOW_QUALITY) ObservationDecision.LOW_QUALITY else ObservationDecision.REJECTED
        decision(reason, obs, result, before)
    }

    private fun accept(candidate: Candidate, before: SignalState, recovering: Boolean) {
        val observation = candidate.observation
        val estimatedPosition = if (recovering) currentEstimate().position else null
        val match = candidate.match
        val confirmed = match.confirmed
        val previous = accepted?.observation
        val elapsed = previous?.let { observation.measuredAtMs - it.measuredAtMs }
        val velocity = observation.speedMps ?: if (previous != null && elapsed != null && elapsed > 0) {
            previous.point.distance(observation.point) / (elapsed / 1000.0)
        } else null
        anchor = if (confirmed != null && confirmed.roadId in graph.plannedRoute &&
            velocity != null && velocity in 0.0..settings.maximumSpeed
        ) {
            DeadReckoningAnchor(confirmed.position, confirmed.roadId, velocity, observation.measuredAtMs, confirmed.offset)
        } else null
        accepted = candidate
        signal = SignalState.TRACKING
        decision(Reason.ACCEPTED, observation, ObservationDecision.ACCEPTED, before, match, acceptedPosition())
        match.reason?.let { event(it, observation, match = match) }
        if (recovering) {
            val recoveryStarted = recovery.firstOrNull()?.observation?.receivedAtMs ?: now
            resolveRecovery(RecoveryOutcome.CONTRIBUTED, Reason.RECOVERY_CONTRIBUTED, before)
            event(Reason.SIGNAL_RECOVERED, observation, before = before, value = (now - recoveryStarted).toDouble())
            estimatedPosition?.let { previousPosition ->
                event(Reason.DR_REALIGNED, observation, value = previousPosition.distance(acceptedPosition()!!))
            }
        }
        val previousStatus = routeDetector.status
        val routeEvent = routeDetector.observe(confirmed?.roadId, observation.measuredAtMs, confirmed)
        if (routeEvent != null) event(routeEvent, observation)
        else if (previousStatus != routeDetector.status) event(Reason.ROUTE_CHANGED, observation)
    }

    private fun tick() {
        val last = accepted?.observation
        if ((signal == SignalState.TRACKING || signal == SignalState.DEGRADED) && last != null &&
            now - last.measuredAtMs >= settings.signalLossMs
        ) {
            val before = signal
            signal = SignalState.LOST
            routeDetector.breakContinuity(unknown = true)
            event(Reason.SIGNAL_LOST, last, before = before, value = (now - last.measuredAtMs).toDouble())
        }
        val candidate = recovery.lastOrNull()?.observation
        if (signal == SignalState.RECOVERING && candidate != null && now - candidate.receivedAtMs >= settings.recoveryWaitMs) {
            abandonRecovery(RecoveryOutcome.EXPIRED, Reason.RECOVERY_TIMEOUT)
        }
    }

    private fun abandonRecovery(outcome: RecoveryOutcome, reason: Reason) {
        val before = signal
        signal = SignalState.LOST
        resolveRecovery(outcome, reason, before)
    }

    private fun resolveRecovery(outcome: RecoveryOutcome, reason: Reason, before: SignalState = signal) {
        recovery.forEach { event(reason, it.observation, before, outcome) }
        recovery.clear()
    }

    private fun currentEstimate(): Estimate =
        if (signal == SignalState.LOST || signal == SignalState.RECOVERING) estimate(anchor, graph, now, settings)
        else Estimate()

    private fun updateEstimate() {
        val estimate = currentEstimate()
        if (estimate.position != null && lastEstimate.position == null) {
            event(Reason.DR_STARTED, position = estimate.position)
        } else if (estimate.reason != null && estimate.reason != lastEstimate.reason) {
            event(estimate.reason)
        }
        lastEstimate = estimate
    }

    private fun acceptedPosition(): Point2D? = accepted?.let { it.match.confirmed?.position ?: it.observation.point }

    private fun snapshot(): TrackingSnapshot {
        val source = when {
            lastEstimate.position != null -> PositionSource.DEAD_RECKONED
            accepted == null -> PositionSource.NONE
            signal == SignalState.LOST || signal == SignalState.RECOVERING -> PositionSource.LAST_KNOWN
            accepted?.match?.confirmed != null -> PositionSource.MAP_MATCHED
            else -> PositionSource.OBSERVED
        }
        return TrackingSnapshot(
            nowMs = now,
            signal = signal,
            position = lastEstimate.position ?: acceptedPosition(),
            source = source,
            confidence = when (source) {
                PositionSource.MAP_MATCHED -> Confidence.HIGH
                PositionSource.OBSERVED -> Confidence.MEDIUM
                PositionSource.DEAD_RECKONED, PositionSource.LAST_KNOWN -> Confidence.LOW
                PositionSource.NONE -> Confidence.UNKNOWN
            },
            roadId = lastEstimate.road ?: accepted?.match?.confirmed?.roadId,
            route = routeDetector.status,
            acceptedPosition = acceptedPosition(),
            lastAcceptedAtMs = accepted?.observation?.measuredAtMs,
            lastObservation = latest,
            lastReceivedAtMs = latest?.receivedAtMs,
            measurementWatermarkMs = watermark,
            lastDecision = lastDecision,
            drUnavailable = lastEstimate.reason,
            recoveryCandidates = recovery.size,
        )
    }

    private fun decision(
        reason: Reason, obs: LocationObservation, result: ObservationDecision, before: SignalState,
        match: MatchResult? = null, position: Point2D? = null,
    ) {
        lastDecision = reason
        events += DecisionEvent(runId, now, reason, before, signal, obs, result, match = match, position = position)
    }

    private fun event(
        reason: Reason, obs: LocationObservation? = null, before: SignalState = signal,
        outcome: RecoveryOutcome? = null, match: MatchResult? = null, position: Point2D? = null, value: Double? = null,
    ) {
        events += DecisionEvent(runId, now, reason, before, signal, obs, recoveryOutcome = outcome,
            match = match, position = position, value = value)
    }
}
