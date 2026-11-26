package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import lab.recovery.location.LocationObservation
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.location.RouteStatus
import lab.recovery.location.SignalState
import lab.recovery.location.TrackingSettings
import lab.recovery.location.TrackingSnapshot

private const val RECENT_OBSERVATION_MS = 1_200L

internal fun TrackingSnapshot.recentObservation(): LocationObservation? = lastObservation?.takeIf {
    it.receivedAtMs in 0..nowMs && nowMs - it.receivedAtMs <= RECENT_OBSERVATION_MS
}

internal data class DriveReadout(
    val fixAgeMs: Long?,
    val drRemainingMs: Long?,
    val recoveryCandidates: Pair<Int, Int>?,
    val accuracyMeters: Double?,
    val currentAccuracy: Boolean,
    val speedMps: Double?,
)

internal fun TrackingSnapshot.driveReadout(settings: TrackingSettings): DriveReadout {
    val age = lastAcceptedAtMs?.takeIf { it in 0..nowMs }?.let { nowMs - it }
    // When estimation is active the anchor is the last accepted measurement.
    val remaining = age?.takeIf { source == PositionSource.DEAD_RECKONED && it < settings.drMaximumMs }
        ?.let { settings.drMaximumMs - it }
    val current = recentObservation()?.takeIf {
        signal == SignalState.TRACKING && it.measuredAtMs == lastAcceptedAtMs &&
            lastDecision == Reason.ACCEPTED
    }
    return DriveReadout(
        fixAgeMs = age,
        drRemainingMs = remaining,
        recoveryCandidates = if (signal == SignalState.RECOVERING) recoveryCandidates to settings.recoveryCount else null,
        accuracyMeters = lastObservation?.accuracyMeters?.takeIf { it.isFinite() && it >= 0 },
        currentAccuracy = current != null,
        speedMps = current?.speedMps?.takeIf { it.isFinite() && it >= 0 },
    )
}

@Composable
internal fun DriveHud(ui: State<UiState>, settings: TrackingSettings, modifier: Modifier) {
    val state = ui.value
    val snapshot = state.snapshot
    val readout = snapshot.driveReadout(settings)
    val statusColor = when (snapshot.signal) {
        SignalState.TRACKING -> Mint
        SignalState.LOST -> Coral
        SignalState.DEGRADED, SignalState.RECOVERING -> Amber
        SignalState.ACQUIRING -> Muted
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(snapshot.signal.labelResource()), color = statusColor,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).testTag("signal").semantics { liveRegion = LiveRegionMode.Polite })
            val progress = "${time(snapshot.nowMs)} / ${time(state.scenario?.durationMs ?: 0L)}"
            Text(progress, color = Ink, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("virtual-time").semantics {
                    contentDescription = progress + " · " + state.speed.toString().removeSuffix(".0") + "×"
                })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val source = stringResource(snapshot.source.labelResource())
            Text(state.scenario?.map?.id?.let { "$it · $source" } ?: source,
                color = if (snapshot.source == PositionSource.DEAD_RECKONED) Amber else Ink,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("map-status"))
            Text(stringResource(snapshot.route.labelResource()),
                color = if (snapshot.route == RouteStatus.OFF_ROUTE) Coral else Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("route-status"))
        }
        val items = buildList {
            add(readout.fixAgeMs?.let { stringResource(R.string.fix_age, number(it / 1000.0)) }
                ?: stringResource(R.string.no_valid_fix))
            readout.recoveryCandidates?.let { (count, target) -> add(stringResource(R.string.recovery_progress, count, target)) }
            readout.drRemainingMs?.let { add(stringResource(R.string.dr_time_remaining, number(it / 1000.0))) }
            if (snapshot.source == PositionSource.LAST_KNOWN) {
                snapshot.drUnavailable?.let { add(stringResource(it.estimationLabel())) }
            }
            readout.speedMps?.let { add(stringResource(R.string.accepted_speed, number(it))) }
            readout.accuracyMeters?.let {
                add(stringResource(if (readout.currentAccuracy) R.string.current_accuracy else R.string.last_accuracy, number(it)))
            }
        }
        Text(items.joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("tracking-detail"))
    }
}

@StringRes
internal fun SignalState.labelResource(): Int = when (this) {
    SignalState.ACQUIRING -> R.string.signal_acquiring
    SignalState.TRACKING -> R.string.signal_tracking
    SignalState.DEGRADED -> R.string.signal_degraded
    SignalState.LOST -> R.string.signal_lost
    SignalState.RECOVERING -> R.string.signal_recovering
}

@StringRes
internal fun PositionSource.labelResource(): Int = when (this) {
    PositionSource.NONE -> R.string.position_none
    PositionSource.OBSERVED -> R.string.position_observed
    PositionSource.MAP_MATCHED -> R.string.position_matched
    PositionSource.DEAD_RECKONED -> R.string.position_estimated
    PositionSource.LAST_KNOWN -> R.string.position_last
}

@StringRes
internal fun RouteStatus.labelResource(): Int = when (this) {
    RouteStatus.UNKNOWN -> R.string.route_unknown
    RouteStatus.ON_ROUTE -> R.string.route_on
    RouteStatus.SUSPECTED_OFF_ROUTE -> R.string.route_suspected
    RouteStatus.OFF_ROUTE -> R.string.route_off
}

@StringRes
internal fun Reason.estimationLabel(): Int = when (this) {
    Reason.DR_LIMIT -> R.string.dr_limit
    Reason.DR_BRANCH -> R.string.dr_branch
    Reason.DR_ROUTE_END -> R.string.dr_route_end
    Reason.DR_NO_ANCHOR -> R.string.dr_no_anchor
    else -> R.string.dr_unavailable
}
