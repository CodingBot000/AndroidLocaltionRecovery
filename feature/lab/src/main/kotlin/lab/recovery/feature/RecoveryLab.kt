package lab.recovery.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.recovery.location.PositionSource
import lab.recovery.location.SignalState

internal val Background = Color(0xFF101716)
internal val Panel = Color(0xFF172120)
internal val Rule = Color(0xFF31403D)
internal val Muted = Color(0xFFABBDB5)
internal val Ink = Color(0xFFEAF5EF)
internal val Mint = Color(0xFFA3F2CB)
internal val Amber = Color(0xFFFFCA83)
internal val Coral = Color(0xFFFF9F91)
internal val Sky = Color(0xFF9ACAF0)

@Composable
fun RecoveryLab(model: LabViewModel) {
    val state = model.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Color(0xFF0B3023), surface = Panel, onSurface = Ink,
        background = Background, onBackground = Ink, outline = Rule, error = Coral,
        secondary = Sky, onSurfaceVariant = Muted,
    )) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter) {
                LabScreen(state, model::send, (maxHeight * 0.51f).coerceAtLeast(230.dp))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LabScreen(state: State<UiState>, onIntent: (UiIntent) -> Unit, mapHeight: Dp) {
    val selection by remember(state) { derivedStateOf { state.value.selected } }
    val entries by remember(state) { derivedStateOf { state.value.choices } }
    val error by remember(state) { derivedStateOf { state.value.error } }
    val evaluation by remember(state) { derivedStateOf { state.value.evaluation } }
    val runId by remember(state) { derivedStateOf { state.value.runId } }
    var expandedLogs by remember(runId) { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val logCount by remember(state) { derivedStateOf { state.value.logs.size } }
    var night by rememberSaveable { mutableStateOf(false) }
    val language = LocalConfiguration.current.locales[0].language
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().testTag("lab-screen")
            .semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { night = !night }, modifier = Modifier.testTag("map-theme")) {
                        Text(stringResource(if (night) R.string.map_night else R.string.map_day))
                    }
                }
                ScenarioMenu(selection, entries, onIntent)
                Text(
                    text = selection.catalog?.description?.forLanguage(language)
                        ?: selection.description.takeIf { it != 0 }?.let { stringResource(it) }.orEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 3.dp, end = 4.dp)
                        .testTag("scenario-description").semantics { liveRegion = LiveRegionMode.Polite },
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                val current = state.value
                DriveHud(current)
                Spacer(Modifier.height(5.dp))
                TrackingMap(current, Modifier.fillMaxWidth().height(mapHeight), night)
                MapLegend(current.showTruth, { onIntent(UiIntent.ToggleTruth) })
            }
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                val current = state.value
                val duration = current.scenario?.durationMs ?: 0L
                val progress = if (duration > 0) current.snapshot.nowMs.toFloat() / duration else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    color = Mint, trackColor = Rule, gapSize = 0.dp, drawStopIndicator = {})
                PlaybackControls(current, onIntent)
            }
        }
        error?.let { item { Text(it, color = Coral, modifier = Modifier.padding(horizontal = 12.dp).testTag("error")) } }
        item {
            OutlinedButton(onClick = { diagnostics = !diagnostics }, modifier = Modifier.padding(horizontal = 12.dp)
                .testTag("diagnostics-toggle")) {
                Text(stringResource(if (diagnostics) R.string.hide_diagnostics else R.string.show_diagnostics))
            }
        }
        if (diagnostics) {
            item { Diagnostics(state.value, Modifier.padding(horizontal = 12.dp)) }
            item {
                val logs = state.value.logs
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.decision_log), style = MaterialTheme.typography.titleMedium)
                    if (logs.size > 6) TextButton(onClick = { expandedLogs = !expandedLogs }) {
                        Text(if (expandedLogs) stringResource(R.string.recent_log) else stringResource(R.string.all_log, logs.size))
                    }
                }
                if (logs.isEmpty()) Text(stringResource(R.string.empty_log), color = Muted, modifier = Modifier.padding(horizontal = 12.dp))
            }
            items(if (expandedLogs) logCount else minOf(6, logCount)) { index ->
                val logs = state.value.logs
                logs.getOrNull(logs.lastIndex - index)?.let { LogLine(it, Modifier.padding(horizontal = 12.dp)) }
            }
        }
        evaluation?.let { item { EvaluationPanel(it, Modifier.padding(horizontal = 12.dp)) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DriveHud(state: UiState) {
    val snapshot = state.snapshot
    val statusColor = when (snapshot.signal) {
        SignalState.TRACKING -> Mint
        SignalState.LOST -> Coral
        SignalState.DEGRADED, SignalState.RECOVERING -> Amber
        SignalState.ACQUIRING -> Muted
    }
    val source = stringResource(when (snapshot.source) {
        PositionSource.NONE -> R.string.position_none
        PositionSource.OBSERVED -> R.string.position_observed
        PositionSource.MAP_MATCHED -> R.string.position_matched
        PositionSource.DEAD_RECKONED -> R.string.position_estimated
        PositionSource.LAST_KNOWN -> R.string.position_last
    })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(snapshot.signal.name, color = statusColor, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("signal"))
        val progress = "${time(snapshot.nowMs)} / ${time(state.scenario?.durationMs ?: 0L)}"
        Text(progress, color = Ink,
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("virtual-time").semantics {
                contentDescription = progress + " · " + state.speed.toString().removeSuffix(".0") + "×"
            })
    }
    val route = androidx.compose.ui.res.stringResource(when (snapshot.route) {
        lab.recovery.location.RouteStatus.UNKNOWN -> R.string.route_unknown
        lab.recovery.location.RouteStatus.ON_ROUTE -> R.string.route_on
        lab.recovery.location.RouteStatus.SUSPECTED_OFF_ROUTE -> R.string.route_suspected
        lab.recovery.location.RouteStatus.OFF_ROUTE -> R.string.route_off
    })
    val receivedAccuracy = snapshot.lastObservation?.accuracyMeters?.takeIf { it.isFinite() && it >= 0 }
    val speed = snapshot.lastObservation?.takeIf { it.measuredAtMs == snapshot.lastAcceptedAtMs && snapshot.lastDecision == lab.recovery.location.Reason.ACCEPTED }
        ?.speedMps?.takeIf { it.isFinite() && it >= 0 }
    val quality = stringResource(R.string.hud_accuracy, receivedAccuracy?.let { "${number(it)} m" } ?: "—")
    val velocity = stringResource(R.string.hud_speed, speed?.let { "${number(it)} m/s" } ?: "—")
    val candidates = if (snapshot.recoveryCandidates == 0) "" else
        " · " + stringResource(R.string.hud_candidates, snapshot.recoveryCandidates)
    Text("${state.scenario?.map?.id?.plus(" · ").orEmpty()}$source · $route$candidates",
        color = if (snapshot.route == lab.recovery.location.RouteStatus.OFF_ROUTE) Coral else Muted,
        style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("map-status"))
    Text("$velocity · $quality" + (snapshot.drUnavailable?.let { " · ${it.name}" } ?: ""),
        style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.testTag("tracking-detail"))
}
