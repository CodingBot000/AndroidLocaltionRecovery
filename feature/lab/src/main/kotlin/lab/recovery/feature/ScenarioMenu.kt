package lab.recovery.feature

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val featuredScenarios = listOf("OL07", "OL02", "OL10", "OL13", "ODEMO_LONG")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ScenarioMenu(selected: ScenarioChoice, entries: List<ScenarioChoice>, onIntent: (UiIntent) -> Unit) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val language = config.locales[0].language
    val titleLines = if (config.screenWidthDp <= 360) 2 else 1
    var open by remember { mutableStateOf(false) }
    var featuredOpen by remember { mutableStateOf(false) }
    val featured = remember(entries) {
        val byId = entries.associateBy(ScenarioChoice::id)
        featuredScenarios.mapNotNull(byId::get)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("scenario-menu")) {
                Text(if (selected.id == "OSM_DEFAULT") selected.title else
                    "${selected.id} · ${selected.catalog?.title?.forLanguage(language) ?: selected.title}",
                    modifier = Modifier.weight(1f), maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                Text("▾", color = Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false },
                modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                entries.groupBy { choice ->
                    when {
                        choice.osm -> R.string.osm_drives
                        choice.path.isEmpty() -> R.string.extended_drives
                        else -> R.string.quick_cases
                    }
                }.forEach { (label, group) ->
                    Text(stringResource(label),
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
        if (featured.isNotEmpty()) Box {
            TextButton(onClick = { featuredOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("featured-menu")) {
                Text(stringResource(R.string.featured_demos))
            }
            DropdownMenu(expanded = featuredOpen, onDismissRequest = { featuredOpen = false },
                modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                for (choice in featured) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.catalog?.title?.forLanguage(language) ?: choice.title)
                                Text(choice.id + if (choice.id == "OL07") " · " + stringResource(R.string.featured_recommended) else "",
                                    style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        },
                        onClick = {
                            featuredOpen = false
                            onIntent(UiIntent.Select(choice))
                        },
                        trailingIcon = { if (selected.id == choice.id) Text("✓", color = Mint) },
                        modifier = Modifier.testTag("featured-${choice.id}").semantics { this.selected = selected.id == choice.id },
                    )
                }
            }
        }
    }
}
