package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import lab.recovery.simulation.PlaybackSpeeds

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun PlaybackControls(playback: Playback, speed: Float, onIntent: (UiIntent) -> Unit) {
    val controllable = when (playback) {
        Playback.READY, Playback.PLAYING, Playback.PAUSED -> true
        else -> false
    }
    var speedOpen by remember { mutableStateOf(false) }
    val speedLabel = stringResource(R.string.playback_speed)
    val compactPadding = PaddingValues(horizontal = 10.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onIntent(UiIntent.PlayPause) }, enabled = controllable,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("play-pause"),
            contentPadding = compactPadding, colors = ButtonDefaults.buttonColors(containerColor = Mint),
        ) {
            Text(if (playback == Playback.PLAYING) stringResource(R.string.pause) else stringResource(R.string.play))
        }
        OutlinedButton(onClick = { onIntent(UiIntent.Step) }, enabled = controllable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("step"), contentPadding = compactPadding) {
            Text(stringResource(R.string.step))
        }
        TextButton(onClick = { onIntent(UiIntent.Reset) }, modifier = Modifier.heightIn(min = 48.dp).testTag("reset"),
            contentPadding = compactPadding) {
            Text(stringResource(if (playback == Playback.COMPLETE) R.string.replay_reset else R.string.reset))
        }
        OutlinedButton(onClick = { speedOpen = true }, contentPadding = compactPadding,
            modifier = Modifier.heightIn(min = 48.dp).testTag("speed-button")
                .semantics {
                    contentDescription = speedLabel
                    stateDescription = formatSpeed(speed)
                }) {
            Text("${formatSpeed(speed)} ▾")
        }
    }
    if (speedOpen) AlertDialog(
        onDismissRequest = { speedOpen = false },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(speedLabel) },
        text = {
            Column {
                Text(formatSpeed(speed), color = Mint, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("speed-value"))
                Slider(
                    value = speed,
                    onValueChange = { onIntent(UiIntent.Speed(PlaybackSpeeds.snap(it))) },
                    valueRange = PlaybackSpeeds.range,
                    steps = PlaybackSpeeds.values.size - 2,
                    modifier = Modifier.fillMaxWidth().testTag("speed-slider")
                        .semantics { contentDescription = speedLabel },
                )
            }
        },
        dismissButton = {
            val fastest = PlaybackSpeeds.range.endInclusive
            TextButton(onClick = {
                onIntent(UiIntent.Speed(fastest))
                speedOpen = false
            }, modifier = Modifier.testTag("speed-five-times")) { Text(formatSpeed(fastest)) }
        },
        confirmButton = {
            TextButton(onClick = { speedOpen = false }, modifier = Modifier.testTag("speed-close")) {
                Text(stringResource(R.string.osm_close))
            }
        },
    )
}

@StringRes
internal fun Playback.labelResource(): Int = when (this) {
    Playback.LOADING -> R.string.replay_loading
    Playback.READY -> R.string.replay_ready
    Playback.PLAYING -> R.string.replay_playing
    Playback.PAUSED -> R.string.replay_paused
    Playback.COMPLETE -> R.string.replay_complete
    Playback.ERROR -> R.string.replay_error
}

private fun formatSpeed(speed: Float): String = speed.toString().removeSuffix(".0") + "×"
