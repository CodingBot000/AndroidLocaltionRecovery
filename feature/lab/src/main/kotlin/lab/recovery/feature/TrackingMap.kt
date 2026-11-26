package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.recovery.location.PositionSource
import lab.recovery.location.DecisionEvent
import lab.recovery.location.LocationObservation
import lab.recovery.location.ObservationDecision
import lab.recovery.location.Reason
import lab.recovery.map.MapVisualData
import lab.recovery.uimap.MapMarker
import lab.recovery.uimap.MapCredits
import lab.recovery.uimap.MapOverlay
import lab.recovery.uimap.MapScene
import lab.recovery.uimap.MapStrings
import lab.recovery.uimap.MapSymbol
import lab.recovery.uimap.MapTrail
import lab.recovery.uimap.MarkerKind
import lab.recovery.uimap.RoadMap
import lab.recovery.uimap.mapLegendBackground

@Composable
internal fun TrackingMap(
    ui: State<UiState>, modifier: Modifier, dark: Boolean, optimized: Boolean,
    onMapReady: (Long) -> Unit,
) {
    val state = ui.value
    val scenario = state.scenario
    val source = scenario?.map?.osm
    var sourceOpen by remember(scenario) { mutableStateOf(false) }
    val scene = remember(scenario) {
        scenario?.let { MapScene(it.roads.network, it.roads.route, it.map?.visual ?: MapVisualData(), source?.bounds, it.map?.checksum) }
    }
    val snapshot = state.snapshot
    val raw = snapshot.recentObservation()?.takeIf { it.point.finite }
    val rejected = remember(state.logs, raw) { raw?.let(state.logs::rejects) == true }
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
        rawRejected = rejected,
    )
    val strings = MapStrings(stringResource(if (source == null) R.string.synthetic_map else R.string.osm_map), stringResource(R.string.camera_follow),
        stringResource(R.string.camera_overview), stringResource(R.string.camera_free),
        stringResource(R.string.camera_zoom_in), stringResource(R.string.camera_zoom_out), stringResource(R.string.map_tunnel))
    RoadMap(scene, overlay, dark, strings, modifier,
        source?.let { MapCredits(it.attribution) { sourceOpen = true } }, optimized, state.runId, onMapReady)
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

internal fun List<DecisionEvent>.rejects(observation: LocationObservation): Boolean =
    lastOrNull { it.observation == observation && it.decision != null }?.decision == ObservationDecision.REJECTED

@Composable
internal fun MapLegend(truthEnabled: Boolean, dark: Boolean, toggle: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = toggle, modifier = Modifier.testTag("truth-toggle").semantics { selected = truthEnabled }) {
            LegendItem(MapSymbol.TRUTH, dark, if (truthEnabled) R.string.truth_visible else R.string.truth_hidden)
        }
        LegendItem(MapSymbol.FIX, dark)
        LegendItem(MapSymbol.GPS, dark)
        LegendItem(MapSymbol.REJECTED, dark)
        LegendItem(MapSymbol.DR, dark)
        LegendItem(MapSymbol.LAST, dark)
        TextButton(onClick = { details = true }, modifier = Modifier.testTag("map-legend")) {
            Text(stringResource(R.string.legend_details))
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.legend_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapSymbol.entries.forEach { LegendItem(it, dark) }
                Text(stringResource(R.string.legend_explanation), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.osm_close)) } },
    )
}

@Composable
private fun LegendItem(symbol: MapSymbol, dark: Boolean, @StringRes label: Int = symbol.labelResource()) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = mapLegendBackground(dark), shape = RoundedCornerShape(4.dp)) {
            Text(symbol.glyph, color = symbol.color(dark), modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@StringRes
private fun MapSymbol.labelResource(): Int = when (this) {
    MapSymbol.FIX -> R.string.map_symbol_fix
    MapSymbol.GPS -> R.string.map_symbol_gps
    MapSymbol.REJECTED -> R.string.map_symbol_rejected
    MapSymbol.DR -> R.string.map_symbol_dr
    MapSymbol.LAST -> R.string.map_symbol_last
    MapSymbol.TRUTH -> R.string.map_symbol_truth
    MapSymbol.PLAN -> R.string.map_symbol_plan
    MapSymbol.TRAIL -> R.string.map_symbol_trail
    MapSymbol.OUTSIDE -> R.string.map_symbol_outside
}
