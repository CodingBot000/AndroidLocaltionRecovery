package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import lab.recovery.simulation.CatalogEntry
import lab.recovery.simulation.Evaluation
import lab.recovery.simulation.ScenarioRepository
import lab.recovery.simulation.MonotonicReplayClock
import lab.recovery.simulation.ReplayClock
import lab.recovery.simulation.ReplayController
import lab.recovery.simulation.ReplayIntent
import lab.recovery.simulation.ReplayState
import lab.recovery.simulation.Scenario

typealias Playback = lab.recovery.simulation.Playback

data class ScenarioChoice(
    val id: String,
    val title: String,
    @param:StringRes val description: Int,
    val path: String = "evaluation/$id.json",
    val catalog: CatalogEntry? = null,
    val osm: Boolean = false,
)
val scenarioChoices = listOf(
    ScenarioChoice("DEMO", "Jump, loss & recovery", R.string.scenario_demo_description, "development/DEMO.json"),
    ScenarioChoice("S01", "Straight road & curve", R.string.scenario_s01_description),
    ScenarioChoice("S02", "GPS position jump", R.string.scenario_s02_description),
    ScenarioChoice("S03", "Stopped vehicle", R.string.scenario_s03_description),
    ScenarioChoice("S04", "Poor GPS accuracy", R.string.scenario_s04_description),
    ScenarioChoice("S05", "Duplicate & delayed GPS", R.string.scenario_s05_description),
    ScenarioChoice("S06", "Short signal loss", R.string.scenario_s06_description),
    ScenarioChoice("S07", "Long tunnel outage", R.string.scenario_s07_description),
    ScenarioChoice("S08", "Braking during outage", R.string.scenario_s08_description),
    ScenarioChoice("S09", "Unstable recovery", R.string.scenario_s09_description),
    ScenarioChoice("S10", "Parallel roads", R.string.scenario_s10_description),
    ScenarioChoice("S11", "True route departure", R.string.scenario_s11_description),
    ScenarioChoice("S12", "False departure", R.string.scenario_s12_description),
    ScenarioChoice("S13", "Return to route", R.string.scenario_s13_description),
    ScenarioChoice("S14", "No initial fix", R.string.scenario_s14_description),
    ScenarioChoice("S15", "Replay controls", R.string.scenario_s15_description),
    ScenarioChoice("S16", "Departure during outage", R.string.scenario_s16_description),
    ScenarioChoice("S17", "Invalid observations", R.string.scenario_s17_description),
)

data class UiState(
    val replay: ReplayState,
    val selected: ScenarioChoice,
    val showTruth: Boolean,
    val error: String?,
    val choices: List<ScenarioChoice> = scenarioChoices,
) {
    val runId get() = replay.runId
    val playback get() = replay.playback
    val speed get() = replay.speed
    val scenario get() = replay.scenario
    val snapshot get() = replay.snapshot
    val logs get() = replay.logs
    val trails get() = replay.trails
    val evaluation: Evaluation? get() = replay.evaluation
}

sealed interface UiIntent {
    data class Select(val choice: ScenarioChoice) : UiIntent
    data class Speed(val value: Float) : UiIntent
    data object PlayPause : UiIntent
    data object Step : UiIntent
    data object Reset : UiIntent
    data object ToggleTruth : UiIntent
}

class LabViewModel(
    load: (ScenarioChoice) -> Scenario,
    worker: CoroutineDispatcher = Dispatchers.Default,
    io: CoroutineDispatcher = Dispatchers.IO,
    clock: ReplayClock = MonotonicReplayClock,
    initial: ScenarioChoice = scenarioChoices.first(),
    catalogChoices: (() -> List<ScenarioChoice>)? = null,
) : ViewModel() {
    constructor(repository: ScenarioRepository, benchmarkScenario: String? = null) : this(
        load = { choice ->
            when {
                choice.path.isNotEmpty() -> repository.readLegacy(choice.path)
                choice.id == "OSM_DEFAULT" -> repository.readOsm(repository.osmCatalog.defaultId)
                choice.id.startsWith("O") -> repository.readOsm(choice.id)
                else -> repository.read(choice.id)
            }
        },
        initial = when (benchmarkScenario) {
            "DEMO" -> scenarioChoices.first()
            "DEMO_LONG" -> ScenarioChoice("DEMO_LONG", "DEMO_LONG", 0, "")
            else -> ScenarioChoice("OSM_DEFAULT", "OpenStreetMap", 0, "", osm = true)
        },
        catalogChoices = {
            repository.osmCatalog.entries.filterNot(CatalogEntry::evaluation).map {
                ScenarioChoice(it.id, it.title.english, 0, "", it, osm = true)
            } + repository.catalog.entries.filterNot(CatalogEntry::evaluation).map {
                ScenarioChoice(it.id, it.title.english, 0, "", it)
            }
        },
    )

    private val legacy = scenarioChoices.associateBy(ScenarioChoice::id)
    private val choices = MutableStateFlow(if (catalogChoices == null) scenarioChoices else listOf(initial) + scenarioChoices)
    private val showTruth = MutableStateFlow(true)
    private val commandError = MutableStateFlow<String?>(null)
    private val controller = ReplayController(viewModelScope, initial.id,
        { id -> load(legacy[id] ?: ScenarioChoice(id, id, 0, "")) }, worker, io, clock)
    internal val trackingSettings get() = controller.trackingSettings

    val state = combine(controller.state, showTruth, commandError, choices) { replay, truth, error, entries ->
        UiState(replay, entries.firstOrNull { it.id == replay.selected } ?: initial, truth, replay.error ?: error, entries)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        UiState(controller.state.value, initial, true, null, choices.value))

    init {
        if (catalogChoices != null) viewModelScope.launch {
            try {
                choices.value = withContext(io) { catalogChoices() } + scenarioChoices
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                commandError.value = error.message ?: "Unable to open the scenario catalog"
            }
        }
    }

    fun send(intent: UiIntent) {
        if (intent == UiIntent.ToggleTruth) {
            showTruth.value = !showTruth.value
            return
        }
        val request = when (intent) {
            is UiIntent.Select -> ReplayIntent.Select(intent.choice.id)
            is UiIntent.Speed -> ReplayIntent.Speed(intent.value)
            UiIntent.PlayPause -> ReplayIntent.PlayPause
            UiIntent.Step -> ReplayIntent.Step
            UiIntent.Reset -> ReplayIntent.Reset
            UiIntent.ToggleTruth -> return
        }
        commandError.value = if (controller.send(request)) null else "Replay controls busy. Try again."
    }

    override fun onCleared() = controller.close()
}
