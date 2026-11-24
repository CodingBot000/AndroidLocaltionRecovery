package lab.recovery.feature

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import lab.recovery.simulation.Evaluation
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
    @StringRes val description: Int,
    val path: String = "evaluation/$id.json",
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

data class UiState(val replay: ReplayState, val selected: ScenarioChoice, val showTruth: Boolean, val error: String?) {
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
) : ViewModel() {
    private val byId = scenarioChoices.associateBy(ScenarioChoice::id)
    private val showTruth = MutableStateFlow(true)
    private val commandError = MutableStateFlow<String?>(null)
    private val controller = ReplayController(viewModelScope, scenarioChoices.first().id,
        { load(byId.getValue(it)) }, worker, io, clock)

    val state = combine(controller.state, showTruth, commandError) { replay, truth, error ->
        UiState(replay, byId.getValue(replay.selected), truth, replay.error ?: error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        UiState(controller.state.value, scenarioChoices.first(), true, null))

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
