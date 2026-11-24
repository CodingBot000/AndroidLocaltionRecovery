package lab.recovery.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lab.recovery.location.PositionSource
import lab.recovery.location.Reason
import lab.recovery.map.MapVisualData
import lab.recovery.uimap.MapMarker
import lab.recovery.uimap.MapCredits
import lab.recovery.uimap.MapOverlay
import lab.recovery.uimap.MapScene
import lab.recovery.uimap.MapStrings
import lab.recovery.uimap.MapTrail
import lab.recovery.uimap.MarkerKind
import lab.recovery.uimap.RoadMap

@Composable
internal fun TrackingMap(state: UiState, modifier: Modifier, dark: Boolean = false) {
    val scenario = state.scenario
    val source = scenario?.map?.osm
    var sourceOpen by remember(scenario) { mutableStateOf(false) }
    val scene = remember(scenario) {
        scenario?.let { MapScene(it.roads.network, it.roads.route, it.map?.visual ?: MapVisualData(), source?.bounds) }
    }
    val snapshot = state.snapshot
    val raw = snapshot.lastObservation?.takeIf {
        it.point.finite && snapshot.nowMs - it.receivedAtMs <= 1200
    }
    val trails = remember(state.trails) {
        state.trails.map { MapTrail(it.points, it.source == PositionSource.DEAD_RECKONED) }
    }
    val overlay = MapOverlay(
        vehicle = snapshot.position?.let { point ->
            val kind = when (snapshot.source) {
                PositionSource.DEAD_RECKONED -> MarkerKind.ESTIMATE
                PositionSource.LAST_KNOWN -> MarkerKind.LAST_KNOWN
                else -> MarkerKind.FIX
            }
            val heading = raw?.takeIf {
                snapshot.lastDecision == Reason.ACCEPTED && it.measuredAtMs == snapshot.lastAcceptedAtMs &&
                    (it.speedMps ?: 0.0) >= 1.0
            }?.headingDegrees
            MapMarker(point, kind, heading)
        },
        raw = raw?.point,
        accuracyMeters = raw?.accuracyMeters,
        lastKnown = snapshot.acceptedPosition,
        truth = if (state.showTruth) scenario?.truthAt(snapshot.nowMs)?.position else null,
        trails = trails,
    )
    val strings = MapStrings(stringResource(if (source == null) R.string.synthetic_map else R.string.osm_map), stringResource(R.string.camera_follow),
        stringResource(R.string.camera_overview), stringResource(R.string.camera_free),
        stringResource(R.string.camera_zoom_in), stringResource(R.string.camera_zoom_out), stringResource(R.string.map_tunnel))
    key(state.runId) {
        RoadMap(scene, overlay, dark, strings, modifier, source?.let { MapCredits(it.attribution) { sourceOpen = true } })
    }
    if (sourceOpen && source != null) {
        AlertDialog(onDismissRequest = { sourceOpen = false },
            title = { Text(stringResource(R.string.osm_data_source)) },
            text = { SelectionContainer {
                Text(listOf(source.attribution, source.license, source.licenseUrl, source.snapshot, source.sourceUrl)
                    .joinToString("\n"), modifier = Modifier.testTag("osm-source-details"))
            } },
            confirmButton = {
                TextButton(onClick = { sourceOpen = false }, modifier = Modifier.testTag("osm-source-close")) {
                    Text(stringResource(R.string.osm_close))
                }
            })
    }
}

@Composable
internal fun MapLegend(truthEnabled: Boolean, toggle: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)) {
        TextButton(onClick = toggle, modifier = Modifier.testTag("truth-toggle")) {
            Text(if (truthEnabled) stringResource(R.string.legend_truth) else stringResource(R.string.legend_truth_off), color = if (truthEnabled) Sky else Muted,
                style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(R.string.legend_raw), color = Coral, style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.legend_fix), color = Mint, style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.legend_dr), color = Amber, style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.legend_last), color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}
