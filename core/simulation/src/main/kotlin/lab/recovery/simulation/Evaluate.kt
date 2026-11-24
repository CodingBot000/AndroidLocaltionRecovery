package lab.recovery.simulation

import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private val malformed = mapOf(
    "S17A" to FixtureError.ROAD_GEOMETRY,
    "S17B" to FixtureError.REPLAY_TIME,
    "S17C" to FixtureError.SCHEMA,
)

data class FixtureFailure(val file: String, val expected: FixtureError?, val actual: FixtureError?)
data class EvaluationRun(val evaluations: List<Evaluation>, val loadChecks: List<FixtureFailure>)

fun main(args: Array<String>) = runBlocking {
    val directory = File(args.getOrElse(0) { "fixtures/evaluation" })
    val extended = File(directory, "catalog.json").isFile
    val osm = extended && directory.name == "osm"
    val files = if (directory.isFile) listOf(directory) else directory.listFiles { file -> file.extension == "json" }?.sorted()
    require(!files.isNullOrEmpty()) { "No fixture JSON files in $directory" }
    val evaluations = mutableListOf<Evaluation>()
    val loadChecks = mutableListOf<FixtureFailure>()
    if (extended) {
        val root = checkNotNull(directory.absoluteFile.parentFile)
        val repository = ScenarioRepository { File(root, it).inputStream() }
        val catalog = if (osm) repository.osmCatalog else repository.catalog
        val ids = catalog.entries.map(CatalogEntry::id).toSet()
        val prefix = if (osm) "O" else ""
        val userIds = (1..17).map { prefix + "L" + it.toString().padStart(2, '0') }.toSet() + (prefix + "DEMO_LONG")
        require(ids.containsAll(userIds))
        val userEntries = catalog.entries.filterNot(CatalogEntry::evaluation)
        val independent = catalog.entries.filter(CatalogEntry::evaluation)
        require(userEntries.map(CatalogEntry::id).containsAll(userIds))
        require(catalog.maps.size >= 10 && userEntries.map(CatalogEntry::mapId).toSet() == catalog.maps.keys)
        require(independent.map(CatalogEntry::mapId).toSet() == catalog.maps.keys)
        catalog.entries.forEach {
            evaluations += playToEnd(ReplaySession(if (osm) repository.readOsm(it.id) else repository.read(it.id)))
        }
    } else for (file in files) {
        val expectedError = malformed[file.nameWithoutExtension]
        try {
            val scenario = FixtureReader.read(file.inputStream())
            if (expectedError != null) loadChecks += FixtureFailure(file.name, expectedError, null)
            evaluations += playToEnd(ReplaySession(scenario))
        } catch (error: InvalidFixture) {
            loadChecks += FixtureFailure(file.name, expectedError, error.error)
        }
    }
    val result = EvaluationRun(evaluations, loadChecks)
    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    val tree = gson.toJsonTree(result).asJsonObject
    if (!extended) tree.getAsJsonArray("evaluations").forEach { it.asJsonObject.remove("extended") }
    val json = gson.toJson(tree)
    val markdown = createReport(result)
    if (args.size >= 2) File(args[1]).apply { parentFile?.mkdirs(); writeText(json + "\n") }
    if (args.size >= 3) File(args[2]).apply { parentFile?.mkdirs(); writeText(markdown) }
    print(markdown)
    if (loadChecks.any { it.expected != it.actual } || evaluations.any { eval -> eval.checks.any { !it.passed } || eval.extended?.passed == false }) {
        exitProcess(1)
    }
}

private fun createReport(result: EvaluationRun): String = buildString {
    appendLine("# 가상 위치 평가 결과")
    appendLine()
    appendLine("합성 fixture의 JVM 재생 결과다. 비율은 건수/표본 수로 표시한다. N/A는 분모 또는 표본이 없음을 뜻한다.")
    appendLine()
    appendLine("| 시나리오 | 입력 | 오거부 | 오채택 | 저품질 사용 | 매칭 정확도 | 매칭 커버리지 | 원시 평균 m | 채택 평균 m | DR 평균 m | 복구 지연 ms | 이탈 권고 | 검증 |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---|")
    result.evaluations.forEach { e ->
        appendLine("| ${e.scenarioId} | ${e.observations} | ${e.falseRejection.display()} | ${e.invalidAcceptance.display()} | " +
            "${e.lowQualityUse.display()} | ${e.matchAccuracy.display()} | ${e.matchCoverage.display()} | ${e.rawErrorMeters.mean.format()} | " +
            "${e.acceptedErrorMeters.mean.format()} | ${e.drErrorMeters.mean.format()} | " +
            "${e.recoveryDelayMs.takeIf(List<Long>::isNotEmpty)?.joinToString() ?: "N/A"} | ${e.departureCount} | " +
            "${e.checks.count { it.passed }}/${e.checks.size} |")
    }
    appendLine()
    val extended = result.evaluations.filter { it.extended != null }
    if (extended.isNotEmpty()) {
        if (extended.first().schemaVersion == 3) {
            appendLine("OL 및 ODEMO_LONG은 OSM 도로 위의 앱 가상 주행이며 OE는 별도의 평가 사례다.")
        } else {
            appendLine("L 및 DEMO_LONG은 앱 주행이며 E는 별도 시드·장애 시각의 평가 사례다.")
        }
        appendLine()
        extended.filter { it.extended?.passed == false }.forEach {
            appendLine("- ${it.scenarioId} FAIL final signal ${it.extended?.finalSignal}, expected ${it.extended?.expectedFinalSignal}")
        }
    }
    result.loadChecks.forEach { check ->
        appendLine("- ${check.file} 로딩 검사: ${check.actual ?: "unexpectedly runnable"} (${if (check.actual == check.expected) "PASS" else "FAIL"})")
    }
    result.evaluations.forEach { e ->
        e.checks.filterNot(RegressionCheck::passed).forEach { check ->
            appendLine("- ${e.scenarioId} FAIL ${check.expected.reason} @ ${check.expected.timeMs ?: "all"}: expected ${check.expected.count}, actual ${check.actual}")
        }
    }
    appendLine()
    appendLine("분포의 중간값은 중앙 표본의 평균으로, p95는 nearest-rank 방식으로 계산한다. 원시·채택·DR은 표본 범위가 다르며 같은 관측의 비교는 JSON의 paired 항목을 사용한다.")
    appendLine("가상 입력을 재생하여 측정했으며 실제 GNSS 정확도나 단말 처리량을 뜻하지 않는다. JSON 원본은 fixture 체크섬, 엔진 설정, 표본 수와 분포, 복구·이탈 지표를 포함한다.")
}

private fun Double?.format(): String = this?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "N/A"
