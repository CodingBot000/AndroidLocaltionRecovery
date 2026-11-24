package lab.recovery.live

import lab.recovery.location.EngineInput
import lab.recovery.location.LocationObservation
import lab.recovery.location.TrackingEngine
import lab.recovery.location.TrackingSettings
import lab.recovery.location.TrackingSnapshot
import lab.recovery.map.Point2D
import lab.recovery.map.RoadGraph

internal enum class Precision { NONE, COARSE, FINE }
internal enum class FixStatus {
    WAITING, COARSE_ONLY, NO_ROUTE, OUTSIDE_COVERAGE, PROJECTION_UNAVAILABLE,
    LOW_QUALITY, INVALID_FIX, STALE_FIX, TRACKING,
}
internal enum class LiveIssue {
    PERMISSION_REQUIRED, PERMISSION_CHANGED, LOCATION_DISABLED, PROVIDER_MISSING,
    PROVIDER_DISABLED, PROVIDER_TEMPORARY, START_REJECTED, MAP_UNAVAILABLE, INTERRUPTED,
}
internal enum class SessionPhase { IDLE, STARTING, ACTIVE, STOPPING, ERROR }

internal data class RawFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val speed: Double?,
    val bearing: Double?,
    val measuredNs: Long,
    val receivedNs: Long,
    val provider: String?,
    val mock: Boolean,
)

internal data class LiveReading(
    val status: FixStatus,
    val fix: RawFix? = null,
    val point: Point2D? = null,
    val tracking: TrackingSnapshot? = null,
    val trackingEpoch: Long = 0,
)

internal data class LiveState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val token: Long = 0,
    val mapId: String? = null,
    val routeId: String? = null,
    val precision: Precision = Precision.NONE,
    val issue: LiveIssue? = null,
    val reading: LiveReading? = null,
    val map: LiveMap? = null,
    val received: Long = 0,
    val processed: Long = 0,
    val dropped: Long = 0,
    val latestFixProcessingNs: Long? = null,
)

internal interface LocationSource {
    fun precision(): Precision
    fun issue(precision: Precision): LiveIssue?
    fun register(precision: Precision, fix: (RawFix) -> Unit, issue: (LiveIssue) -> Unit)
    fun unregister()
}

/** Accessed serially by one service-owned coroutine, with monotonic clock input supplied by its caller. */
internal class LiveSession(
    private val startNs: Long,
    private val precision: Precision,
    private val projection: OsmProjection?,
    private val graph: RoadGraph?,
    private val settings: TrackingSettings = TrackingSettings(),
) {
    private var engine: TrackingEngine? = null
    private var watermarkMs: Long? = null
    private var inputMs = 0L
    private var sequence = 0
    private var epoch = 0L
    private var reading = LiveReading(if (precision == Precision.COARSE) FixStatus.COARSE_ONLY else FixStatus.WAITING)

    fun onFix(fix: RawFix): LiveReading {
        val coordinatesValid = fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0
        if (!coordinatesValid || fix.accuracy?.let { !it.isFinite() || it < 0 } == true ||
            fix.speed?.let { !it.isFinite() || it < 0 } == true ||
            fix.bearing?.let { !it.isFinite() || it < 0 || it >= 360 } == true) {
            return reset(FixStatus.INVALID_FIX, null)
        }
        if (fix.measuredNs < startNs || fix.receivedNs < fix.measuredNs) return stale()
        val measured = millis(fix.measuredNs)
        val received = millis(fix.receivedNs)
        if (received < inputMs || received - measured > settings.maximumDelayMs ||
            watermarkMs?.let { measured <= it } == true) return stale()
        watermarkMs = measured
        if (precision == Precision.COARSE) return reset(FixStatus.COARSE_ONLY, fix)
        val transform = projection ?: return reset(FixStatus.PROJECTION_UNAVAILABLE, fix)
        val point = transform.inCoverage(fix.longitude, fix.latitude) ?: return reset(FixStatus.OUTSIDE_COVERAGE, fix)
        val accuracy = fix.accuracy ?: return reset(FixStatus.LOW_QUALITY, fix, point)
        if (accuracy > settings.goodAccuracy) return reset(FixStatus.LOW_QUALITY, fix, point)
        val route = graph ?: return reset(FixStatus.NO_ROUTE, fix, point)

        if (reading.fix?.mock != fix.mock || sequence == Int.MAX_VALUE) engine = null
        val active = engine ?: TrackingEngine(route, settings, ++epoch, monotonicObservationIds = true).also {
            sequence = 0
            engine = it
        }
        inputMs = received
        val observation = LocationObservation(++sequence, "live-$sequence", measured, received, point, accuracy, fix.speed, fix.bearing)
        return LiveReading(FixStatus.TRACKING, fix, point, active.step(EngineInput.Observation(observation)).snapshot, epoch)
            .also { reading = it }
    }

    fun tick(nowNs: Long): LiveReading {
        val active = engine ?: return reading
        inputMs = maxOf(inputMs, millis(nowNs))
        return reading.copy(tracking = active.step(EngineInput.Tick(inputMs)).snapshot).also { reading = it }
    }

    private fun millis(nanos: Long) = (nanos - startNs) / 1_000_000

    private fun stale(): LiveReading = if (engine == null) reset(FixStatus.STALE_FIX, null) else reading

    private fun reset(status: FixStatus, fix: RawFix?, point: Point2D? = null): LiveReading {
        engine = null
        return LiveReading(status, fix, point).also { reading = it }
    }
}
