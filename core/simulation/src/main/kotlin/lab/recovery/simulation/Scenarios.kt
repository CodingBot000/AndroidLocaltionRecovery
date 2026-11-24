package lab.recovery.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import lab.recovery.location.LocationObservation
import lab.recovery.map.Point2D
import lab.recovery.location.Reason
import lab.recovery.map.Road
import lab.recovery.map.RoadGraph
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

enum class TruthLabel { NORMAL, INVALID, LOW_QUALITY }
enum class FixtureError { SIZE_LIMIT, SCHEMA, EVENT_LIMIT, ROAD_LIMIT, DURATION_LIMIT, ROAD_GEOMETRY, REPLAY_TIME }
enum class FixtureProfile(val byteLimit: Int, val observationLimit: Int, val segmentLimit: Int, val durationLimit: Long) {
    STANDARD(4 * 1024 * 1024, 5_000, 512, 15 * 60_000),
    STRESS(64 * 1024 * 1024, 100_000, 512, 120 * 60_000),
}
class InvalidFixture(val error: FixtureError, detail: String) : IllegalArgumentException("${error.name}: $detail")

data class TruthPoint(val timeMs: Long, val position: Point2D, val roadId: String?)
data class Expectation(val timeMs: Long?, val reason: Reason, val count: Int)
data class Scenario(
    val id: String,
    val title: String,
    val version: Int,
    val checksum: String,
    val durationMs: Long,
    val roads: RoadGraph,
    val observations: List<LocationObservation>,
    val groundTruth: List<TruthPoint>,
    val labels: Map<Int, TruthLabel>,
    val expected: List<Expectation>,
    val expectedRecoveryAtMs: List<Long> = emptyList(),
    val seed: Long? = null,
) {
    fun truthAt(timeMs: Long): TruthPoint? {
        val index = groundTruth.binarySearchBy(timeMs, selector = TruthPoint::timeMs)
        if (index >= 0) return groundTruth[index]
        val upper = -index - 1
        val start = groundTruth.getOrNull(upper - 1) ?: return null
        val end = groundTruth.getOrNull(upper) ?: return null
        val fraction = (timeMs - start.timeMs).toDouble() / (end.timeMs - start.timeMs)
        return TruthPoint(timeMs, start.position.interpolate(end.position, fraction), start.roadId)
    }
}

object FixtureReader {
    fun read(input: InputStream, profile: FixtureProfile = FixtureProfile.STANDARD): Scenario {
        val bytes = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > profile.byteLimit) {
                    throw InvalidFixture(FixtureError.SIZE_LIMIT, "${profile.byteLimit} bytes")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try {
            val root = JsonReader(bytes.inputStream().reader(Charsets.UTF_8)).use { reader ->
                reader.strictness = Strictness.STRICT
                val value = JsonParser.parseReader(reader).asJsonObject
                require(reader.peek() == JsonToken.END_DOCUMENT)
                value
            }
            return parse(root, sha256(bytes), profile)
        } catch (error: InvalidFixture) {
            throw error
        } catch (error: Exception) {
            throw InvalidFixture(FixtureError.SCHEMA, error.message ?: "Malformed JSON or required field")
        }
    }

    private fun parse(root: JsonObject, checksum: String, profile: FixtureProfile): Scenario {
        val schemaVersion = root["schemaVersion"].integer()
        require(schemaVersion == 1L)
        val version = schemaVersion.toInt()
        val duration = root["duration"].integer()
        if (duration !in 0..profile.durationLimit) throw InvalidFixture(FixtureError.DURATION_LIMIT, "${profile.durationLimit} ms")
        val roadRows = root.getAsJsonArray("roads")
        val segmentCount = roadRows.sumOf { (it.asJsonObject.getAsJsonArray("points").size() - 1).coerceAtLeast(0) }
        if (segmentCount > profile.segmentLimit) throw InvalidFixture(FixtureError.ROAD_LIMIT, "${profile.segmentLimit} segments")
        val graph = try {
            val roads = roadRows.map { row ->
                val road = row.asJsonObject
                val points = road.getAsJsonArray("points").map { point ->
                    require(point.asJsonArray.size() == 2)
                    Point2D(point.asJsonArray[0].number(), point.asJsonArray[1].number())
                }
                Road(road["id"].string(), points, road["tunnel"]?.asBoolean ?: false)
            }
            RoadGraph(roads, root.getAsJsonArray("plannedRoute").map(JsonElement::string))
        } catch (_: IllegalArgumentException) {
            throw InvalidFixture(FixtureError.ROAD_GEOMETRY, "Non-finite or repeated points, IDs or disconnected route")
        }
        val observations = root.getAsJsonArray("observations")
        if (observations.size() > profile.observationLimit) throw InvalidFixture(FixtureError.EVENT_LIMIT, "${profile.observationLimit} observations")
        val labels = mutableMapOf<Int, TruthLabel>()
        val inputs = observations.mapIndexed { order, element ->
            val row = element.asJsonObject
            val received = row["received"].integer()
            if (received !in 0..duration) throw InvalidFixture(FixtureError.REPLAY_TIME, "input $order")
            labels[order] = TruthLabel.valueOf(row["label"].string())
            LocationObservation(order, row["id"].string(), row["measured"].integer(), received,
                Point2D(row["x"].number(), row["y"].number()), row["accuracy"].number(),
                row["speed"].optionalNumber(), row["heading"].optionalNumber())
        }.sortedWith(compareBy(LocationObservation::receivedAtMs, LocationObservation::sequence))
        val truth = root.getAsJsonArray("groundTruth").map { element ->
            val row = element.asJsonObject
            TruthPoint(row["time"].integer(), Point2D(row["x"].number(), row["y"].number()),
                row["road"]?.takeUnless(JsonElement::isJsonNull)?.string())
        }
        require(truth.zipWithNext().all { (first, next) -> first.timeMs < next.timeMs })
        require(truth.all { it.timeMs in 0..duration && it.position.finite && (it.roadId == null || it.roadId in graph.byId) })
        val expected = root.getAsJsonArray("expected").map { element ->
            val row = element.asJsonObject
            Expectation(row["time"]?.takeUnless(JsonElement::isJsonNull)?.integer(),
                Reason.valueOf(row["reason"].string()), row["count"].integer().toInt().also { require(it >= 0) })
        }
        val recoveryTimes = root.getAsJsonArray("expectedRecoveryAtMs")?.map(JsonElement::integer).orEmpty()
        require(recoveryTimes.zipWithNext().all { (first, next) -> first < next } && recoveryTimes.all { it in 0..duration })
        return Scenario(root["scenarioId"].string().also { require(it.isNotBlank()) }, root["title"].string(),
            version, checksum, duration, graph, inputs, truth, labels, expected, recoveryTimes, root["seed"]?.integer())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun JsonElement.string(): String {
    require(isJsonPrimitive && asJsonPrimitive.isString)
    return asString
}
private fun JsonElement.number(): Double {
    require(isJsonPrimitive && asJsonPrimitive.isNumber)
    return asDouble
}
private fun JsonElement.integer(): Long {
    require(isJsonPrimitive && asJsonPrimitive.isNumber)
    return asBigDecimal.longValueExact()
}
private fun JsonElement?.optionalNumber(): Double? = this?.takeUnless(JsonElement::isJsonNull)?.number()
