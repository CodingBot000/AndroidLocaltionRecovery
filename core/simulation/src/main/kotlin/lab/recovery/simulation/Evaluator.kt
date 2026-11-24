package lab.recovery.simulation

import lab.recovery.location.DecisionEvent
import lab.recovery.location.ObservationDecision
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import lab.recovery.location.RecoveryOutcome
import lab.recovery.location.TrackingSettings
import lab.recovery.location.TrackingSnapshot
import kotlin.math.ceil

data class Ratio(val numerator: Int, val denominator: Int) {
    val fraction: Double? get() = if (denominator == 0) null else numerator.toDouble() / denominator
    fun display(): String = if (denominator == 0) "N/A" else "$numerator/$denominator"
}

data class Distribution(val count: Int, val mean: Double?, val median: Double?, val p95: Double?, val maximum: Double?)
data class RegressionCheck(val expected: Expectation, val actual: Int) {
    val passed get() = expected.count == actual
}

data class Evaluation(
    val scenarioId: String,
    val schemaVersion: Int,
    val sha256: String,
    val seed: Long?,
    val settings: TrackingSettings,
    val observations: Int,
    val falseRejection: Ratio,
    val invalidAcceptance: Ratio,
    val lowQualityUse: Ratio,
    val decisions: Map<TruthLabel, Map<ObservationDecision, Int>>,
    val pendingByLabel: Map<TruthLabel, Int>,
    val recoveryOutcomes: Map<RecoveryOutcome, Int>,
    val matchAccuracy: Ratio,
    val matchCoverage: Ratio,
    val ambiguousMatches: Ratio,
    val missingMatches: Ratio,
    val rawErrorMeters: Distribution,
    val acceptedErrorMeters: Distribution,
    val pairedRawErrorMeters: Distribution,
    val pairedAcceptedErrorMeters: Distribution,
    val drErrorMeters: Distribution,
    val availability: Map<PositionSource, Int>,
    val availabilityRatios: Map<PositionSource, Ratio>,
    val lossDelayMs: List<Long>,
    val recoveryDelayMs: List<Long>,
    val incompleteRecoveries: Int,
    val realignmentMeters: List<Double>,
    val departureCount: Int,
    val departureFalsePositives: Int,
    val departureMisses: Int,
    val departureDelayMs: List<Long>,
    val reasonCounts: Map<Reason, Int>,
    val checks: List<RegressionCheck>,
    val extended: ExtendedEvaluation? = null,
)

data class ExtendedEvaluation(
    val mapId: String,
    val mapSchema: Int,
    val mapChecksum: String,
    val routeSchema: Int,
    val routeChecksum: String,
    val expectedFinalSignal: SignalState?,
    val finalSignal: SignalState?,
    val unexpectedRecoveries: Int,
) {
    val passed get() = expectedFinalSignal == null || expectedFinalSignal == finalSignal
}

class Evaluator(private val scenario: Scenario, private val settings: TrackingSettings = TrackingSettings()) {
    private val firstDecisions = linkedMapOf<Int, DecisionEvent>()
    private val outcomes = linkedMapOf<Int, RecoveryOutcome>()
    private val counts = Reason.entries.associateWith { 0 }.toMutableMap()
    private val assertions = IntArray(scenario.expected.size)
    private val drError = mutableListOf<Double>()
    private val availability = PositionSource.entries.associateWith { 0 }.toMutableMap()
    private val loss = mutableListOf<Long>()
    private val recovered = mutableListOf<Long>()
    private val fallbackRecovery = mutableListOf<Long>()
    private val realignment = mutableListOf<Double>()
    private val departures = mutableListOf<Long>()
    private var finalSignal: SignalState? = null

    fun recordCompletion(snapshot: TrackingSnapshot) {
        finalSignal = snapshot.signal
    }

    fun recordTick(snapshot: TrackingSnapshot) {
        availability[snapshot.source] = availability.getValue(snapshot.source) + 1
        if (snapshot.source == PositionSource.DEAD_RECKONED) {
            val truth = scenario.truthAt(snapshot.nowMs)
            if (truth != null && snapshot.position != null) drError += truth.position.distance(snapshot.position!!)
        }
    }

    fun recordEvents(events: List<DecisionEvent>) {
        for (event in events) {
            val sequence = event.observation?.sequence
            if (sequence != null && event.decision != null && firstDecisions.putIfAbsent(sequence, event) != null) continue
            if (sequence != null && event.recoveryOutcome != null && outcomes.putIfAbsent(sequence, event.recoveryOutcome!!) != null) continue
            counts[event.reason] = counts.getValue(event.reason) + 1
            scenario.expected.forEachIndexed { index, expected ->
                if (event.reason == expected.reason && (expected.timeMs == null || event.timeMs == expected.timeMs)) {
                    assertions[index]++
                }
            }
            when (event.reason) {
                Reason.SIGNAL_LOST -> event.value?.let { loss += it.toLong() }
                Reason.SIGNAL_RECOVERED -> {
                    recovered += event.timeMs
                    fallbackRecovery += event.value?.toLong() ?: 0L
                }
                Reason.DR_REALIGNED -> event.value?.let(realignment::add)
                Reason.REROUTE_RECOMMENDED -> departures += event.timeMs
                else -> Unit
            }
        }
    }

    fun result(checkCancellation: () -> Unit = {}): Evaluation {
        val matrix = TruthLabel.entries.associateWith { label ->
            val decisions = scenario.labels.filterValues { it == label }.keys.mapNotNull { firstDecisions[it]?.decision }
            ObservationDecision.entries.associateWith { type -> decisions.count { it == type } }
        }
        fun rate(label: TruthLabel, decision: ObservationDecision) = Ratio(
            matrix.getValue(label).getValue(decision), scenario.labels.values.count { it == label },
        )
        val raw = mutableListOf<Double>()
        val accepted = mutableListOf<Double>()
        val pairedRaw = mutableListOf<Double>()
        var knownRoads = 0
        var confirmed = 0
        var correct = 0
        var ambiguous = 0
        for (observation in scenario.observations) {
            checkCancellation()
            val truth = scenario.truthAt(observation.measuredAtMs) ?: continue
            val event = firstDecisions[observation.sequence]
            val road = event?.match?.confirmed?.roadId.takeIf { event?.decision == ObservationDecision.ACCEPTED }
            if (truth.roadId != null) {
                knownRoads++
                if (road != null) {
                    confirmed++
                    if (road == truth.roadId) correct++
                } else if (event?.match?.reason == Reason.AMBIGUOUS) ambiguous++
            }
            val rawError = observation.point.distance(truth.position)
            if (rawError.isFinite()) raw += rawError
            val position = event?.position
            if (position != null && rawError.isFinite()) {
                accepted += position.distance(truth.position)
                pairedRaw += rawError
            }
        }
        val expectedIntervals = departureIntervals()
        val departureDelays = mutableListOf<Long>()
        var falseDepartures = 0
        val usedIntervals = mutableSetOf<Int>()
        for (time in departures) {
            val index = expectedIntervals.indexOfFirst { time in it }
            if (index >= 0 && usedIntervals.add(index)) {
                departureDelays += time - expectedIntervals[index].first
            } else falseDepartures++
        }
        val recoveryStarts = scenario.expectedRecoveryAtMs
        val recoveryDelays = if (recoveryStarts.isEmpty()) fallbackRecovery else recoveryStarts.mapIndexedNotNull { index, start ->
            val next = recoveryStarts.getOrElse(index + 1) { Long.MAX_VALUE }
            recovered.firstOrNull { it in start until next }?.let { it - start }
        }
        checkCancellation()
        val availabilityTotal = availability.values.sum()
        return Evaluation(
            scenario.id, scenario.version, scenario.checksum, scenario.seed, settings, scenario.labels.size,
            rate(TruthLabel.NORMAL, ObservationDecision.REJECTED),
            rate(TruthLabel.INVALID, ObservationDecision.ACCEPTED),
            rate(TruthLabel.LOW_QUALITY, ObservationDecision.ACCEPTED),
            matrix, matrix.mapValues { it.value.getValue(ObservationDecision.RECOVERY_PENDING) },
            RecoveryOutcome.entries.associateWith { type -> outcomes.values.count { it == type } },
            Ratio(correct, confirmed), Ratio(confirmed, knownRoads), Ratio(ambiguous, knownRoads),
            Ratio(knownRoads - confirmed - ambiguous, knownRoads),
            distribution(raw), distribution(accepted), distribution(pairedRaw), distribution(accepted),
            distribution(drError), availability.toMap(), availability.mapValues { Ratio(it.value, availabilityTotal) },
            loss.toList(), recoveryDelays,
            if (recoveryStarts.isEmpty()) (loss.size - recovered.size).coerceAtLeast(0) else recoveryStarts.size - recoveryDelays.size,
            realignment.toList(),
            departures.size, falseDepartures, expectedIntervals.size - usedIntervals.size, departureDelays,
            counts.filterValues { it > 0 }, scenario.expected.mapIndexed { index, expectation ->
                RegressionCheck(expectation, assertions[index])
            },
            scenario.map?.let {
                ExtendedEvaluation(it.id, it.version, checkNotNull(it.checksum), checkNotNull(scenario.routeVersion),
                    sha256(scenario.roads.plannedRoute.joinToString("\n").encodeToByteArray()),
                    scenario.expectedFinalSignal, finalSignal, recovered.size - recoveryDelays.size)
            },
        )
    }

    private fun departureIntervals(): List<LongRange> {
        val intervals = mutableListOf<LongRange>()
        var start: Long? = null
        for (truth in scenario.groundTruth) {
            val offRoute = truth.roadId?.let { it !in scenario.roads.plannedRoute } == true
            if (offRoute && start == null) start = truth.timeMs
            if (!offRoute && start != null) {
                intervals += start until truth.timeMs
                start = null
            }
        }
        if (start != null) intervals += start..scenario.durationMs
        return intervals
    }
}

private fun distribution(samples: List<Double>): Distribution {
    if (samples.isEmpty()) return Distribution(0, null, null, null, null)
    val sorted = samples.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
    return Distribution(sorted.size, sorted.average(), median, p95, sorted.last())
}
