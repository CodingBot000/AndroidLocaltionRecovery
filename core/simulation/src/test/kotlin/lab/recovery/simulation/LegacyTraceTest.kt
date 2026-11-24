package lab.recovery.simulation

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LegacyTraceTest {
    @Test fun snapshotsDecisionsAndEvaluationMatchTheBaseline() = runBlocking {
        val root = File(checkNotNull(System.getProperty("fixtureRoot")))
        val fixtures = (listOf(File(root, "development/DEMO.json")) + File(root, "evaluation").listFiles().orEmpty())
            .filter { it.extension == "json" && it.nameWithoutExtension !in setOf("S17A", "S17B", "S17C") }.sortedBy(File::getName)
        assertEquals(20, fixtures.size)
        val gson = Gson()
        val traceHashes = fixtures.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            fun record(value: Any) {
                digest.update(gson.toJson(value).encodeToByteArray())
                digest.update(10)
            }
            val scenario = FixtureReader.read(file.inputStream())
            record(playToEnd(ReplaySession(scenario), onFrame = { record(it.update) }))
            digest.digest().joinToString("") { "%02x".format(it) } + "  " + scenario.id
        }
        val expected = File(root.parentFile, "docs/legacy-replay.sha256")
        assertEquals(expected.readLines(), traceHashes)
    }
}
