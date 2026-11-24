package lab.recovery.feature

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ScenarioMenu(selected: ScenarioChoice, entries: List<ScenarioChoice>, onIntent: (UiIntent) -> Unit) {
    val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("scenario-menu")) {
            Text("${selected.id} · ${selected.catalog?.title?.forLanguage(language) ?: selected.title}",
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾", color = Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.groupBy { it.path.isEmpty() }.forEach { (extended, group) ->
                Text(stringResource(if (extended) R.string.extended_drives else R.string.quick_cases),
                    color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
                group.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${choice.id} · ${choice.catalog?.title?.forLanguage(language) ?: choice.title}")
                            choice.catalog?.let {
                                Text("${it.mapId} · ${it.category.forLanguage(language)} · ${time(it.durationMs)}",
                                    style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                    },
                    onClick = { open = false; onIntent(UiIntent.Select(choice)) },
                    modifier = Modifier.testTag("scenario-${choice.id}"),
                )
                }
            }
        }
    }
}
