package lab.recovery.location

import lab.recovery.map.Point2D
data class LocationObservation(
    val sequence: Int,
    val id: String,
    val measuredAtMs: Long,
    val receivedAtMs: Long,
    val point: Point2D,
    val accuracyMeters: Double,
    val speedMps: Double?,
    val headingDegrees: Double?,
)

data class TrackingSettings(
    val goodAccuracy: Double = 20.0,
    val maximumAccuracy: Double = 60.0,
    val maximumDelayMs: Long = 3000,
    val maximumSpeed: Double = 45.0,
    val uncertaintyLimit: Double = 30.0,
    val signalLossMs: Long = 3000,
    val drMaximumMs: Long = 15000,
    val recoveryCount: Int = 3,
    val recoveryMeasuredGapMs: Long = 2000,
    val recoveryWaitMs: Long = 2000,
    val routeGapMs: Long = 2000,
    val routeCount: Int = 3,
    val tickMs: Long = 200,
    val searchRadius: Double = 60.0,
    val maximumMatchCost: Double = 0.75,
    val minimumMatchMargin: Double = 0.08,
    val minimumHeadingSpeed: Double = 1.0,
    val distanceWeight: Double = 0.65,
    val headingWeight: Double = 0.20,
    val continuityWeight: Double = 0.10,
    val planWeight: Double = 0.05,
) {
    init {
        require(tickMs > 0 && signalLossMs > 0 && drMaximumMs > 0 && recoveryWaitMs > 0)
        require(recoveryCount >= 1 && routeCount >= 1 && searchRadius > 0 && distanceWeight > 0)
    }
}

enum class SignalState { ACQUIRING, TRACKING, DEGRADED, LOST, RECOVERING }
enum class PositionSource { NONE, OBSERVED, MAP_MATCHED, DEAD_RECKONED, LAST_KNOWN }
enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
enum class RouteStatus { UNKNOWN, ON_ROUTE, SUSPECTED_OFF_ROUTE, OFF_ROUTE }
enum class ObservationDecision { ACCEPTED, REJECTED, LOW_QUALITY, RECOVERY_PENDING }
enum class RecoveryOutcome { CONTRIBUTED, EXPIRED, CLEARED, UNRESOLVED }
enum class Reason {
    ACCEPTED, INVALID_ID, INVALID_COORDINATE, INVALID_TIME, INVALID_ACCURACY, INVALID_SPEED,
    INVALID_HEADING, FUTURE_MEASUREMENT, DUPLICATE_ID, MEASUREMENT_ORDER, DELIVERY_DELAY,
    LOW_QUALITY, ACCURACY_EXCEEDED, IMPLAUSIBLE_MOVE, RECOVERY_ROAD_DISCONTINUITY, RECOVERY_PENDING,
    RECOVERY_TIMEOUT, RECOVERY_GAP, RECOVERY_CLEARED, RECOVERY_CONTRIBUTED, RECOVERY_UNRESOLVED,
    SIGNAL_LOST, SIGNAL_RECOVERED, AMBIGUOUS, NO_MATCH, DR_STARTED, DR_RESUMED, DR_LIMIT,
    DR_NO_ANCHOR, DR_BRANCH, DR_ROUTE_END, DR_REALIGNED, REROUTE_RECOMMENDED, ROUTE_RETURNED,
    ROUTE_CHANGED, RUN_COMPLETED,
}

data class DeadReckoningAnchor(
    val position: Point2D,
    val roadId: String,
    val speedMps: Double,
    val measuredAtMs: Long,
    val roadOffset: Double,
)

data class TrackingSnapshot(
    val nowMs: Long = 0,
    val signal: SignalState = SignalState.ACQUIRING,
    val position: Point2D? = null,
    val source: PositionSource = PositionSource.NONE,
    val confidence: Confidence = Confidence.UNKNOWN,
    val roadId: String? = null,
    val route: RouteStatus = RouteStatus.UNKNOWN,
    val acceptedPosition: Point2D? = null,
    val lastAcceptedAtMs: Long? = null,
    val lastObservation: LocationObservation? = null,
    val lastReceivedAtMs: Long? = null,
    val measurementWatermarkMs: Long? = null,
    val lastDecision: Reason? = null,
    val drUnavailable: Reason? = null,
    val recoveryCandidates: Int = 0,
)

sealed interface EngineInput {
    val timeMs: Long

    data class Observation(val value: LocationObservation) : EngineInput {
        override val timeMs get() = value.receivedAtMs
    }
    data class Tick(override val timeMs: Long) : EngineInput
}

data class DecisionEvent(
    val runId: Long,
    val timeMs: Long,
    val reason: Reason,
    val before: SignalState,
    val after: SignalState,
    val observation: LocationObservation? = null,
    val decision: ObservationDecision? = null,
    val recoveryOutcome: RecoveryOutcome? = null,
    val match: MatchResult? = null,
    val position: Point2D? = null,
    val value: Double? = null,
)

data class EngineUpdate(val snapshot: TrackingSnapshot, val events: List<DecisionEvent>)
