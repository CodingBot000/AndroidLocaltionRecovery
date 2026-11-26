package lab.recovery.feature

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.recovery.location.TrackingSettings

internal val Background = Color(0xFF111A27)
internal val Panel = Color(0xFF1B293B)
internal val Rule = Color(0xFF465B71)
internal val Muted = Color(0xFFB9C9DA)
internal val Ink = Color(0xFFEDF3F9)
internal val Mint = Color(0xFF94E1CF)
internal val Amber = Color(0xFFFFCA83)
internal val Coral = Color(0xFFFF9F91)
internal val Sky = Color(0xFF9ACAF0)

@Composable
fun RecoveryLab(model: LabViewModel, optimizedMap: Boolean = true, onMapReady: (Long) -> Unit = {}) {
    val state = model.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Color(0xFF0B3023), surface = Panel, onSurface = Ink,
        background = Background, onBackground = Ink, outline = Rule, error = Coral,
        secondary = Sky, onSurfaceVariant = Muted,
    )) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentAlignment = Alignment.TopCenter) {
                LabScreen(state, model::send, model.trackingSettings, (maxHeight * 0.55f).coerceAtLeast(230.dp), optimizedMap, onMapReady)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LabScreen(state: State<UiState>, onIntent: (UiIntent) -> Unit, settings: TrackingSettings, mapHeight: Dp, optimizedMap: Boolean, onMapReady: (Long) -> Unit) {
    val selection by remember(state) { derivedStateOf { state.value.selected } }
    val entries by remember(state) { derivedStateOf { state.value.choices } }
    val error by remember(state) { derivedStateOf { state.value.error } }
    val evaluation by remember(state) { derivedStateOf { state.value.evaluation } }
    val playback by remember(state) { derivedStateOf { state.value.playback } }
    val speed by remember(state) { derivedStateOf { state.value.speed } }
    val runId by remember(state) { derivedStateOf { state.value.runId } }
    val truth by remember(state) { derivedStateOf { state.value.showTruth } }
    val recentDecision by remember(state) { derivedStateOf {
        state.value.let { it.logs.recentDecision(it.runId) }
    } }
    var diagnostics by rememberSaveable(runId) { mutableStateOf(false) }
    var expandedDescription by remember(selection.id) { mutableStateOf(false) }
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
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.simulation_mode), color = Muted,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("simulation-mode"))
                    }
                    TextButton(onClick = { night = !night }, modifier = Modifier.testTag("map-theme")) {
                        Text(stringResource(if (night) R.string.map_night else R.string.map_day))
                    }
                }
                ScenarioMenu(selection, entries, onIntent)
                Text(
                    text = selection.catalog?.description?.forLanguage(language)
                        ?: selection.description.takeIf { it != 0 }?.let { stringResource(it) }.orEmpty(),
                    modifier = Modifier.fillMaxWidth().clickable(
                        onClickLabel = stringResource(if (expandedDescription) R.string.collapse_description else R.string.expand_description),
                    ) { expandedDescription = !expandedDescription }
                        .padding(start = 4.dp, top = 3.dp, end = 4.dp)
                        .testTag("scenario-description").semantics { liveRegion = LiveRegionMode.Polite },
                    maxLines = if (expandedDescription) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { DriveHud(state.value, settings, Modifier.padding(horizontal = 12.dp)) }
        item {
            DecisionSummary(recentDecision, { diagnostics = true }, Modifier.padding(horizontal = 12.dp))
        }
        item {
            TrackingMap(state.value, Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(mapHeight),
                night, optimizedMap, onMapReady)
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                LinearProgressIndicator(progress = {
                    val current = state.value
                    val duration = current.scenario?.durationMs ?: 0L
                    if (duration > 0) current.snapshot.nowMs.toFloat() / duration else 0f
                }, modifier = Modifier.fillMaxWidth(),
                    color = Mint, trackColor = Rule, gapSize = 0.dp, drawStopIndicator = {})
                PlaybackControls(playback, speed, onIntent)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                MapLegend(truth, night) { onIntent(UiIntent.ToggleTruth) }
            }
        }
        error?.let { item { Text(it, color = Coral, modifier = Modifier.padding(horizontal = 12.dp).testTag("error")) } }
        evaluation?.let { item { EvaluationPanel(it, Modifier.padding(horizontal = 12.dp)) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
    if (diagnostics) DiagnosticsDialog(state.value, settings) { diagnostics = false }
}
