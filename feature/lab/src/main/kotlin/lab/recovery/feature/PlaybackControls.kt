package lab.recovery.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.recovery.simulation.PlaybackSpeeds

@Composable
internal fun PlaybackControls(state: UiState, onIntent: (UiIntent) -> Unit) {
    val controllable = state.playback in setOf(Playback.READY, Playback.PLAYING, Playback.PAUSED)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { onIntent(UiIntent.PlayPause) }, enabled = controllable,
                modifier = Modifier.weight(1f).testTag("play-pause"), colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) {
                Text(if (state.playback == Playback.PLAYING) stringResource(R.string.pause) else stringResource(R.string.play))
            }
            OutlinedButton(onClick = { onIntent(UiIntent.Step) }, enabled = controllable,
                modifier = Modifier.testTag("step")) { Text(stringResource(R.string.step)) }
            TextButton(onClick = { onIntent(UiIntent.Reset) }, modifier = Modifier.testTag("reset")) { Text(stringResource(R.string.reset)) }
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
