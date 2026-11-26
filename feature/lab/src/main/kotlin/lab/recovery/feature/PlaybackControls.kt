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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.recovery.simulation.PlaybackSpeeds

@Composable
internal fun PlaybackControls(playback: Playback, speed: Float, onIntent: (UiIntent) -> Unit) {
    val controllable = when (playback) {
        Playback.READY, Playback.PLAYING, Playback.PAUSED -> true
        else -> false
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { onIntent(UiIntent.PlayPause) }, enabled = controllable,
                modifier = Modifier.weight(1f).testTag("play-pause"), colors = ButtonDefaults.buttonColors(containerColor = Mint),
            ) {
                Text(if (playback == Playback.PLAYING) stringResource(R.string.pause) else stringResource(R.string.play))
            }
            OutlinedButton(onClick = { onIntent(UiIntent.Step) }, enabled = controllable,
                modifier = Modifier.testTag("step")) { Text(stringResource(R.string.step)) }
            TextButton(onClick = { onIntent(UiIntent.Reset) }, modifier = Modifier.testTag("reset")) {
                Text(stringResource(if (playback == Playback.COMPLETE) R.string.replay_reset else R.string.reset))
            }
        }
        val speedLabel = stringResource(R.string.playback_speed)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(when (playback) {
                Playback.LOADING -> R.string.replay_loading
                Playback.READY -> R.string.replay_ready
                Playback.PLAYING -> R.string.replay_playing
                Playback.PAUSED -> R.string.replay_paused
                Playback.COMPLETE -> R.string.replay_complete
                Playback.ERROR -> R.string.replay_error
            }), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("playback-state"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(speedLabel, color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(formatSpeed(speed), color = Mint, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("speed-value"))
            }
        }
        Slider(
            value = speed,
            onValueChange = { onIntent(UiIntent.Speed(PlaybackSpeeds.snap(it))) },
            valueRange = PlaybackSpeeds.range,
            steps = PlaybackSpeeds.values.size - 2,
            modifier = Modifier.fillMaxWidth()
                .testTag("speed-slider")
                .semantics { contentDescription = speedLabel },
        )
    }
}

private fun formatSpeed(speed: Float): String = speed.toString().removeSuffix(".0") + "×"
