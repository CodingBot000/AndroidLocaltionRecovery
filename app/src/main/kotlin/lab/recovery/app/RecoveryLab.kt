package lab.recovery.app

import lab.recovery.map.Road
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.recovery.location.DecisionEvent
import lab.recovery.location.ObservationDecision
import lab.recovery.location.PositionSource
import lab.recovery.location.SignalState
import lab.recovery.simulation.Distribution
import lab.recovery.simulation.Evaluation
import lab.recovery.simulation.PlaybackSpeeds
import java.util.Locale

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
    val state by model.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Color(0xFF0B3023), surface = Panel, onSurface = Ink,
        background = Background, onBackground = Ink, outline = Rule, error = Coral,
        secondary = Sky, onSurfaceVariant = Muted,
    )) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                LabScreen(state, model::send)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LabScreen(state: UiState, onIntent: (UiIntent) -> Unit) {
    var expandedLogs by remember(state.runId) { mutableStateOf(false) }
    val logs = if (expandedLogs) state.logs.asReversed() else state.logs.takeLast(6).asReversed()
    LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().testTag("lab-screen").semantics { testTagsAsResourceId = true },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
                Text("Location Recovery Lab", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Synthetic GPS playback", color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                ScenarioMenu(state.selected, onIntent)
                Text(
                    text = stringResource(state.selected.description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp)
                        .testTag("scenario-description")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            Column(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth()
                    .border(1.dp, Rule, RoundedCornerShape(20.dp)).background(Panel, RoundedCornerShape(20.dp)).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    val statusColor = when (state.snapshot.signal) {
                        SignalState.TRACKING -> Mint
                        SignalState.LOST -> Coral
                        SignalState.DEGRADED, SignalState.RECOVERING -> Amber
                        SignalState.ACQUIRING -> Muted
                    }
                    Text(state.snapshot.signal.name, color = statusColor, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("signal"))
                    val duration = state.scenario?.durationMs ?: 0L
                    Text("${time(state.snapshot.nowMs)} / ${time(duration)}", color = Muted,
                        style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("virtual-time"))
                }
                Spacer(Modifier.height(10.dp))
                TrackingMap(state, Modifier.fillMaxWidth().height(202.dp))
                MapLegend(state.showTruth, { onIntent(UiIntent.ToggleTruth) })
                val duration = state.scenario?.durationMs ?: 0L
                val progress = if (duration > 0) state.snapshot.nowMs.toFloat() / duration else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                    color = Mint, trackColor = Rule, gapSize = 0.dp, drawStopIndicator = {})
                Spacer(Modifier.height(12.dp))
                PlaybackControls(state, onIntent)
            }
        }
        state.error?.let { error ->
            item { Text(error, color = Coral, modifier = Modifier.padding(horizontal = 18.dp).testTag("error")) }
        }
        item { Diagnostics(state, Modifier.padding(horizontal = 18.dp)) }
        state.evaluation?.let { evaluation ->
            item { EvaluationPanel(evaluation, Modifier.padding(horizontal = 18.dp)) }
        }
        if (state.playback == Playback.COMPLETE && state.evaluation == null) {
            item { Text("Calculating results…", color = Muted, modifier = Modifier.padding(horizontal = 18.dp)) }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Decision log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (state.logs.size > 6) TextButton(onClick = { expandedLogs = !expandedLogs }) {
                    Text(if (expandedLogs) "Show recent" else "Show all ${state.logs.size}")
                }
            }
        }
        if (logs.isEmpty()) {
            item { Text("Play or step to see engine decisions.", color = Muted, modifier = Modifier.padding(horizontal = 18.dp)) }
        }
        items(logs) { event -> LogLine(event, Modifier.padding(horizontal = 18.dp)) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ScenarioMenu(selected: ScenarioChoice, onIntent: (UiIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("scenario-menu")) {
            Text(if (selected.id == "DEMO") selected.title else "${selected.id} · ${selected.title}",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", color = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            scenarioChoices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text("${choice.id} · ${choice.title}") },
                    onClick = { open = false; onIntent(UiIntent.Select(choice)) },
                    modifier = Modifier.testTag("scenario-${choice.id}"),
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(state: UiState, onIntent: (UiIntent) -> Unit) {
    val controllable = state.playback in setOf(Playback.READY, Playback.PLAYING, Playback.PAUSED)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { onIntent(UiIntent.PlayPause) }, enabled = controllable,
                modifier = Modifier.weight(1f).testTag("play-pause"), colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) {
                Text(if (state.playback == Playback.PLAYING) "Pause" else "Play")
            }
            OutlinedButton(onClick = { onIntent(UiIntent.Step) }, enabled = controllable,
                modifier = Modifier.testTag("step")) { Text("Step") }
            TextButton(onClick = { onIntent(UiIntent.Reset) }, modifier = Modifier.testTag("reset")) { Text("Reset") }
        }
        val speedLabel = stringResource(R.string.playback_speed)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(speedLabel, color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(
                formatSpeed(state.speed),
                color = Mint,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("speed-value"),
            )
        }
        Slider(
            value = state.speed,
            onValueChange = { onIntent(UiIntent.Speed(PlaybackSpeeds.snap(it))) },
            valueRange = PlaybackSpeeds.range,
            steps = PlaybackSpeeds.values.size - 2,
            modifier = Modifier.fillMaxWidth()
                .testTag("speed-slider")
                .semantics { contentDescription = speedLabel },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatSpeed(PlaybackSpeeds.range.start), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(formatSpeed(PlaybackSpeeds.range.endInclusive), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatSpeed(speed: Float): String = speed.toString().removeSuffix(".0") + "×"

@Composable
private fun Diagnostics(state: UiState, modifier: Modifier) {
    val snapshot = state.snapshot
    val obs = snapshot.lastObservation
    Column(modifier.fillMaxWidth()) {
        Text("Position diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Metric("Source / confidence", "${snapshot.source.label()} / ${snapshot.confidence.name.lowercase()}", "position-source")
        Metric("Road / route", "${snapshot.roadId ?: "—"} / ${snapshot.route.name.lowercase().replace('_', ' ')}")
        Metric("Last received accuracy", obs?.accuracyMeters?.takeIf { it.isFinite() }?.let { "${number(it)} m" } ?: "—")
        Metric("Measurement → receipt", obs?.let { "${it.receivedAtMs - it.measuredAtMs} ms" } ?: "—")
        Metric("Time since last fix", snapshot.lastAcceptedAtMs?.let { "${snapshot.nowMs - it} ms" } ?: "—")
        Metric("Last decision", snapshot.lastDecision?.name ?: "—")
        snapshot.drUnavailable?.let { Metric("Estimation unavailable", it.name) }
    }
}

@Composable
private fun EvaluationPanel(result: Evaluation, modifier: Modifier) {
    var expanded by remember(result) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().border(1.dp, Rule, RoundedCornerShape(16.dp)).padding(12.dp).testTag("evaluation")) {
        Text("Run results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Metric("Observations", result.observations.toString(), "evaluation-count")
        Metric("False rejection / invalid acceptance", "${result.falseRejection.display()} / ${result.invalidAcceptance.display()}")
        Metric("Low-quality use", result.lowQualityUse.display())
        Metric("Match accuracy / coverage", "${result.matchAccuracy.display()} / ${result.matchCoverage.display()}")
        Metric("Mean raw / accepted error", "${number(result.rawErrorMeters.mean)} / ${number(result.acceptedErrorMeters.mean)} m")
        Metric("Mean DR error", "${number(result.drErrorMeters.mean)} m")
        Metric("Recovery delay", result.recoveryDelayMs.takeIf { it.isNotEmpty() }?.joinToString { "$it ms" } ?: "N/A")
        Metric("Route departure alerts", result.departureCount.toString())
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide full results" else "View full results") }
        if (expanded) {
            Metric("Fixture / schema", "${result.scenarioId} / ${result.schemaVersion}")
            Text(result.sha256, color = Muted, style = MaterialTheme.typography.bodySmall)
            Metric("Unfinished recoveries", result.incompleteRecoveries.toString())
            Metric("Departure false alarms / misses", "${result.departureFalsePositives} / ${result.departureMisses}")
            Metric("Ambiguous / without match", "${result.ambiguousMatches.display()} / ${result.missingMatches.display()}")
            DistributionRow("Raw error", result.rawErrorMeters)
            DistributionRow("Accepted error", result.acceptedErrorMeters)
            DistributionRow("DR error", result.drErrorMeters)
            DistributionRow("Raw error on accepted fixes", result.pairedRawErrorMeters)
            Metric("Signal loss delay", result.lossDelayMs.takeIf { it.isNotEmpty() }?.joinToString { "$it ms" } ?: "N/A")
            Metric("Re-alignment distance", result.realignmentMeters.takeIf { it.isNotEmpty() }?.joinToString { "${number(it)} m" } ?: "N/A")
            Metric("Departure detection delay", result.departureDelayMs.takeIf { it.isNotEmpty() }?.joinToString { "$it ms" } ?: "N/A")
            result.availabilityRatios.forEach { (source, count) -> Metric(source.label(), count.display()) }
            result.recoveryOutcomes.forEach { (outcome, count) -> Metric(outcome.name.lowercase(), count.toString()) }
            result.decisions.forEach { (label, decisions) ->
                Text(label.name, style = MaterialTheme.typography.labelMedium, color = Muted)
                decisions.forEach { (decision, count) -> Metric(decision.name.lowercase().replace('_', ' '), count.toString()) }
            }
            Metric("Tick / signal timeout", "${result.settings.tickMs} / ${result.settings.signalLossMs} ms")
        }
    }
}

@Composable
private fun DistributionRow(label: String, distribution: Distribution) {
    Metric("$label (n=${distribution.count})", "${number(distribution.median)} median / ${number(distribution.p95)} p95 / ${number(distribution.maximum)} max")
}

@Composable
private fun Metric(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(value, modifier = if (tag == null) Modifier else Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LogLine(event: DecisionEvent, modifier: Modifier) {
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

internal fun PositionSource.label() = when (this) {
    PositionSource.NONE -> "No fix"
    PositionSource.OBSERVED -> "Observed"
    PositionSource.MAP_MATCHED -> "Map matched"
    PositionSource.DEAD_RECKONED -> "Dead reckoned"
    PositionSource.LAST_KNOWN -> "Last known"
}
private fun time(milliseconds: Long): String = String.format(Locale.ROOT, "%02d:%04.1f", milliseconds / 60000, milliseconds % 60000 / 1000.0)
private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
