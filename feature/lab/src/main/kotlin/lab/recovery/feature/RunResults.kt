package lab.recovery.feature

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lab.recovery.simulation.Distribution
import lab.recovery.simulation.Evaluation

internal data class PairedErrors(val rawMean: Double, val acceptedMean: Double, val count: Int)

internal fun pairedErrors(raw: Distribution, accepted: Distribution): PairedErrors? {
    val rawMean = raw.mean?.takeIf(Double::isFinite) ?: return null
    val acceptedMean = accepted.mean?.takeIf(Double::isFinite) ?: return null
    if (raw.count == 0 || raw.count != accepted.count) return null
    return PairedErrors(rawMean, acceptedMean, raw.count)
}

internal fun List<Long>.recoveryDelayRange(): LongRange? =
    minOrNull()?.let { it..maxOrNull()!! }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ResultsDialog(result: Evaluation, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.simulation_results)) },
        text = {
            EvaluationPanel(result, Modifier.verticalScroll(rememberScrollState()).semantics {
                testTagsAsResourceId = true
            }, showTitle = false)
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.osm_close)) } },
    )
}

@Composable
internal fun EvaluationPanel(result: Evaluation, modifier: Modifier, showTitle: Boolean = true) {
    var expanded by remember(result) { mutableStateOf(false) }
    val delayRange = result.recoveryDelayMs.recoveryDelayRange()
    val delay = when {
        delayRange == null -> "N/A"
        result.recoveryDelayMs.size == 1 -> "${delayRange.first} ms"
        else -> stringResource(R.string.result_delay_range, delayRange.first, delayRange.last, result.recoveryDelayMs.size)
    }
    Column(modifier.fillMaxWidth().border(1.dp, Rule, RoundedCornerShape(16.dp)).padding(12.dp).testTag("evaluation")) {
        if (showTitle) Text(stringResource(R.string.simulation_results),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Metric(stringResource(R.string.result_observations), result.observations.toString(), "evaluation-count")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultFigure(stringResource(R.string.result_accuracy), result.matchAccuracy.display())
            ResultFigure(stringResource(R.string.result_coverage), result.matchCoverage.display())
            ResultFigure(stringResource(R.string.result_recovery), delay)
            ResultFigure(stringResource(R.string.result_departures), result.departureCount.toString())
        }
        Metric(stringResource(R.string.result_rejection), result.falseRejection.display())
        Metric(stringResource(R.string.result_acceptance), result.invalidAcceptance.display())
        pairedErrors(result.pairedRawErrorMeters, result.pairedAcceptedErrorMeters)?.let {
            Metric(stringResource(R.string.result_paired, it.count), "${number(it.rawMean)} → ${number(it.acceptedMean)} m")
        }
        if (result.checks.isNotEmpty()) {
            Metric(stringResource(R.string.result_checks), "${result.checks.count { it.passed }}/${result.checks.size}")
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("evaluation-details")) {
            Text(stringResource(if (expanded) R.string.result_hide_details else R.string.result_details))
        }
        if (expanded) ResultDetails(result)
    }
}

@Composable
private fun ResultFigure(label: String, value: String) {
    Column(Modifier.widthIn(min = 96.dp)) {
        Text(value, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ResultDetails(result: Evaluation) {
    Metric(stringResource(R.string.result_fixture), "${result.scenarioId} / ${result.schemaVersion}")
    Text(result.sha256, color = Muted, style = MaterialTheme.typography.bodySmall)
    Metric(stringResource(R.string.result_low_quality), result.lowQualityUse.display())
    Metric(stringResource(R.string.result_unfinished), result.incompleteRecoveries.toString())
    Metric(stringResource(R.string.result_departure_detail), "${result.departureFalsePositives} / ${result.departureMisses}")
    Metric(stringResource(R.string.result_match_detail), "${result.ambiguousMatches.display()} / ${result.missingMatches.display()}")
    DistributionRow(stringResource(R.string.result_raw_error), result.rawErrorMeters)
    DistributionRow(stringResource(R.string.result_accepted_error), result.acceptedErrorMeters)
    DistributionRow(stringResource(R.string.result_dr_error), result.drErrorMeters)
    DistributionRow(stringResource(R.string.result_paired_raw), result.pairedRawErrorMeters)
    DistributionRow(stringResource(R.string.result_paired_accepted), result.pairedAcceptedErrorMeters)
    Metric(stringResource(R.string.result_recovery), result.recoveryDelayMs.milliseconds())
    Metric(stringResource(R.string.result_loss_delay), result.lossDelayMs.milliseconds())
    Metric(stringResource(R.string.result_realignment),
        result.realignmentMeters.takeIf { it.isNotEmpty() }?.joinToString { "${number(it)} m" } ?: "N/A")
    Metric(stringResource(R.string.result_departure_delay), result.departureDelayMs.milliseconds())
    result.availabilityRatios.forEach { (source, count) -> Metric(stringResource(source.labelResource()), count.display()) }
    result.recoveryOutcomes.forEach { (outcome, count) -> Metric(outcome.name, count.toString()) }
    result.decisions.forEach { (label, decisions) ->
        Text(label.name, style = MaterialTheme.typography.labelMedium, color = Muted)
        decisions.forEach { (decision, count) -> Metric(decision.name, count.toString()) }
    }
    Text(stringResource(R.string.decision_log), style = MaterialTheme.typography.labelMedium, color = Muted)
    result.reasonCounts.forEach { (reason, count) -> if (count > 0) Metric(reason.name, count.toString()) }
    Metric(stringResource(R.string.result_tick_loss), "${result.settings.tickMs} / ${result.settings.signalLossMs} ms")
}

@Composable
private fun DistributionRow(label: String, distribution: Distribution) {
    Metric("$label (n=${distribution.count})",
        stringResource(R.string.result_distribution, number(distribution.mean), number(distribution.median),
            number(distribution.p95), number(distribution.maximum)))
}

private fun List<Long>.milliseconds(): String = takeIf { it.isNotEmpty() }?.joinToString { "$it ms" } ?: "N/A"
