package lab.recovery.feature

import lab.recovery.location.LocationObservation
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.SignalState
import lab.recovery.location.TrackingSettings
import lab.recovery.location.TrackingSnapshot
import lab.recovery.map.Point2D
import org.junit.Assert.*
import org.junit.Test

class DriveReadoutTest {
    private val settings = TrackingSettings(drMaximumMs = 12_000, recoveryCount = 4)
    private val observation = LocationObservation(1, "fix", 10_000, 10_200, Point2D(0.0, 0.0), 4.0, 6.0, null)
    private val tracking = TrackingSnapshot(nowMs = 10_600, signal = SignalState.TRACKING,
        source = PositionSource.MAP_MATCHED, lastAcceptedAtMs = 10_000,
        lastObservation = observation, lastDecision = Reason.ACCEPTED)

    @Test fun activeEstimationUsesTheMeasurementAndSessionLimit() {
        val lost = tracking.copy(nowMs = 17_000, signal = SignalState.LOST, source = PositionSource.DEAD_RECKONED)
        val active = lost.driveReadout(settings)
        assertEquals(7_000L, active.fixAgeMs)
        assertEquals(5_000L, active.drRemainingMs)
        assertFalse(active.currentAccuracy)
        assertEquals(4.0, active.accuracyMeters)
        assertNull(active.speedMps)

        val stopped = lost.copy(source = PositionSource.LAST_KNOWN, drUnavailable = Reason.DR_BRANCH)
        assertNull(stopped.driveReadout(settings).drRemainingMs)
        assertNull(lost.copy(nowMs = 22_000).driveReadout(settings).drRemainingMs)
    }

    @Test fun onlyRecentAcceptedTrackingProvidesCurrentAccuracyAndSpeed() {
        assertTrue(tracking.driveReadout(settings).currentAccuracy)
        assertEquals(6.0, tracking.driveReadout(settings).speedMps)
        assertFalse(tracking.copy(nowMs = 11_401).driveReadout(settings).currentAccuracy)
        assertFalse(tracking.copy(lastDecision = Reason.IMPLAUSIBLE_MOVE).driveReadout(settings).currentAccuracy)
        assertNull(tracking.copy(lastObservation = observation.copy(accuracyMeters = Double.NaN)).driveReadout(settings).accuracyMeters)
        assertNull(TrackingSnapshot().driveReadout(settings).fixAgeMs)
    }

    @Test fun recoveryDenominatorUsesConfiguredCandidateCount() {
        val pending = tracking.copy(signal = SignalState.RECOVERING, source = PositionSource.LAST_KNOWN, recoveryCandidates = 2)
        assertEquals(2 to 4, pending.driveReadout(settings).recoveryCandidates)
        assertNull(pending.copy(signal = SignalState.TRACKING).driveReadout(settings).recoveryCandidates)
    }
}
