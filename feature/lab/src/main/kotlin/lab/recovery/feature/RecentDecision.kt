package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.recovery.location.DecisionEvent
import lab.recovery.location.ObservationDecision
import lab.recovery.location.Reason

internal fun List<DecisionEvent>.recentDecision(runId: Long): DecisionEvent? =
    lastOrNull { it.runId == runId && it.reason.summaryResource() != null }

@Composable
internal fun DecisionSummary(event: DecisionEvent?, open: () -> Unit, showResults: (() -> Unit)?, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            val label = event?.reason?.summaryResource()
            Text(
                stringResource(label ?: R.string.no_key_decision),
                color = if (event?.decision == ObservationDecision.REJECTED) Coral else Ink,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("recent-decision").semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (event != null) Text(
                "${time(event.timeMs)} · ${event.reason.name}",
                color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("recent-decision-code"),
            )
        }
        TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp).testTag("diagnostics-toggle")) {
            Text(stringResource(R.string.show_diagnostics))
        }
        if (showResults != null) TextButton(onClick = showResults, modifier = Modifier.testTag("open-results")) {
            Text(stringResource(R.string.open_results))
        }
    }
}

@StringRes
internal fun Reason.summaryResource(): Int? = when (this) {
    Reason.SIGNAL_LOST -> R.string.event_signal_lost
    Reason.SIGNAL_RECOVERED -> R.string.event_signal_recovered
    Reason.DR_STARTED, Reason.DR_RESUMED -> R.string.event_dr
    Reason.DR_LIMIT, Reason.DR_BRANCH, Reason.DR_ROUTE_END, Reason.DR_NO_ANCHOR -> estimationLabel()
    Reason.IMPLAUSIBLE_MOVE -> R.string.event_impossible_move
    Reason.LOW_QUALITY -> R.string.event_low_quality
    Reason.ACCURACY_EXCEEDED -> R.string.event_accuracy_exceeded
    Reason.AMBIGUOUS -> R.string.event_ambiguous
    Reason.NO_MATCH -> R.string.event_no_match
    Reason.RECOVERY_PENDING -> R.string.event_candidate_pending
    Reason.RECOVERY_TIMEOUT, Reason.RECOVERY_GAP -> R.string.event_candidate_timeout
    Reason.RECOVERY_ROAD_DISCONTINUITY -> R.string.event_candidate_discontinuity
    Reason.REROUTE_RECOMMENDED -> R.string.event_departure
    Reason.ROUTE_RETURNED -> R.string.event_route_return
    else -> null
}
