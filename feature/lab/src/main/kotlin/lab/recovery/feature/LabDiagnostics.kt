package lab.recovery.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import lab.recovery.location.DecisionEvent
import lab.recovery.location.ObservationDecision
import lab.recovery.location.TrackingSettings
import java.util.Locale

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun DiagnosticsDialog(state: UiState, settings: TrackingSettings, close: () -> Unit) {
    var expanded by remember(state.runId) { mutableStateOf(false) }
    val logs = state.logs
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.position_diagnostics)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).semantics { testTagsAsResourceId = true }) {
                item { Diagnostics(state, settings, Modifier) }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.decision_log), style = MaterialTheme.typography.titleMedium)
                        if (logs.size > 6) TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) stringResource(R.string.recent_log) else stringResource(R.string.all_log, logs.size))
                        }
                        if (logs.isEmpty()) Text(stringResource(R.string.empty_log), color = Muted)
                    }
                }
                items(if (expanded) logs.size else minOf(6, logs.size)) { index ->
                    LogLine(logs[logs.lastIndex - index], Modifier)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.osm_close)) } },
    )
}

@Composable
private fun Diagnostics(state: UiState, settings: TrackingSettings, modifier: Modifier) {
    val snapshot = state.snapshot
    val obs = snapshot.lastObservation
    Column(modifier.fillMaxWidth()) {
        Metric(stringResource(R.string.diagnostic_source_confidence),
            "${stringResource(snapshot.source.labelResource())} / ${snapshot.confidence.name}", "position-source")
        Metric(stringResource(R.string.diagnostic_road_route), "${snapshot.roadId ?: "—"} / ${stringResource(snapshot.route.labelResource())}")
        val readout = snapshot.driveReadout(settings)
        Metric(stringResource(R.string.diagnostic_received_accuracy), readout.accuracyMeters?.let { "${number(it)} m" } ?: "—")
        Metric(stringResource(R.string.diagnostic_delivery_delay),
            obs?.takeIf { it.measuredAtMs in 0..it.receivedAtMs }?.let { "${it.receivedAtMs - it.measuredAtMs} ms" } ?: "—")
        Metric(stringResource(R.string.diagnostic_fix_age), readout.fixAgeMs?.let { "$it ms" } ?: "—")
        Metric(stringResource(R.string.diagnostic_last_decision), snapshot.lastDecision?.name ?: "—")
        snapshot.drUnavailable?.let {
            Metric(stringResource(R.string.diagnostic_estimation), stringResource(it.estimationLabel()) + " · " + it.name)
        }
    }
}

@Composable
internal fun Metric(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, modifier = Modifier.weight(1f, fill = false).then(if (tag == null) Modifier else Modifier.testTag(tag)),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LogLine(event: DecisionEvent, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = Rule)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(time(event.timeMs), color = Muted, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            Text(event.reason.name, color = when (event.decision) {
                ObservationDecision.REJECTED -> Coral
                ObservationDecision.LOW_QUALITY, ObservationDecision.RECOVERY_PENDING -> Amber
                ObservationDecision.ACCEPTED -> Mint
                null -> Ink
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            event.observation?.let { Text(it.id, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun time(milliseconds: Long): String = String.format(Locale.ROOT, "%02d:%04.1f", milliseconds / 60000, milliseconds % 60000 / 1000.0)
internal fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
