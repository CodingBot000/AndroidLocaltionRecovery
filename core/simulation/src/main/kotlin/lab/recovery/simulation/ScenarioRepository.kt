package lab.recovery.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import lab.recovery.map.LandPolygon
import lab.recovery.map.LandUse
import lab.recovery.map.MapDefinition
import lab.recovery.map.MapLabel
import lab.recovery.map.MapVisualData
import lab.recovery.map.Point2D
import lab.recovery.map.Road
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadEdge
import lab.recovery.map.RoadNetwork
import lab.recovery.map.RoadNode
import lab.recovery.map.RoadStructure
import lab.recovery.map.RoadVisual
import java.io.InputStream
import kotlin.math.abs

fun interface FixtureSource {
    fun open(path: String): InputStream
}

data class LocalizedText(val english: String, val korean: String) {
    fun forLanguage(language: String) = if (language == "ko") korean else english
}

data class CatalogEntry(
    val id: String,
    val mapId: String,
    val durationMs: Long,
    val title: LocalizedText,
    val description: LocalizedText,
    val category: LocalizedText,
    val evaluation: Boolean,
    val representative: Boolean,
)

data class ScenarioCatalog(val maps: Map<String, LocalizedText>, val entries: List<CatalogEntry>) {
    val defaultId = entries.single { it.representative }.id
}

class ScenarioRepository(private val source: FixtureSource) {
    val catalog: ScenarioCatalog by lazy { readCatalog() }

    fun readLegacy(path: String): Scenario {
        require(path == "development/DEMO.json" || legacyPath.matches(path))
        return FixtureReader.read(source.open(path))
    }

    fun read(id: String): Scenario = fixtureSchema {
        val entry = catalog.entries.singleOrNull { it.id == id } ?: error("Unknown scenario $id")
        val profile = FixtureProfile.STANDARD
        val mapBytes = fixtureBytes(source.open("extended/maps/${entry.mapId}.json"), profile.byteLimit)
        val map = parseMap(fixtureObject(mapBytes), entry.mapId, sha256(mapBytes), profile)
        val group = if (entry.evaluation) "evaluation" else "scenarios"
        val scenario = FixtureReader.read(source.open("extended/$group/$id.json"), profile, map, profile.byteLimit - mapBytes.size)
        require(scenario.id == id && scenario.durationMs == entry.durationMs && scenario.title == entry.title.english)
        scenario
    }

    private fun readCatalog(): ScenarioCatalog = fixtureSchema {
        val root = fixtureObject(fixtureBytes(source.open("extended/catalog.json"), 256 * 1024))
        require(root["schemaVersion"].integer() == 2L)
        val mapRows = root.getAsJsonArray("maps")
        require(mapRows.size() in 1..64)
        val maps = mapRows.associate { row ->
            val map = row.asJsonObject
            val id = map["id"].string()
            require(identifier.matches(id))
            id to map.localized("name")
        }
        require(maps.size == mapRows.size())
        val rows = root.getAsJsonArray("entries")
        require(rows.size() in 1..256)
        val entries = rows.map { value ->
            val row = value.asJsonObject
            CatalogEntry(row["id"].string(), row["mapId"].string(), row["duration"].integer(), row.localized("title"),
                row.localized("description"), row.localized("kind"), row["evaluation"].boolean(), row["representative"].boolean())
        }
        require(entries.distinctBy { it.id }.size == entries.size)
        require(entries.all {
            identifier.matches(it.id) && it.mapId in maps && (!it.representative || !it.evaluation) &&
                it.durationMs in if (it.representative) 150_000L..180_000L else 90_000L..120_000L
        })
        require(entries.count { it.representative } == 1)
        ScenarioCatalog(maps, entries)
    }

    private companion object {
        val identifier = Regex("[A-Z][A-Z0-9_]{0,31}")
        val legacyPath = Regex("evaluation/S[0-9]{2}[A-Z]?\\.json")
    }
}

private fun parseMap(root: JsonObject, id: String, checksum: String, profile: FixtureProfile): MapDefinition {
    require(root["schemaVersion"].integer() == 2L && root["mapId"].string() == id)
    val nodeRows = root.getAsJsonArray("nodes")
    val edgeRows = root.getAsJsonArray("edges")
    require(nodeRows.size() in 2..512 && edgeRows.size() in 1..512)
    val nodes = nodeRows.map {
        val row = it.asJsonObject
        RoadNode(row["id"].string().identifier(), row["point"].point(), row.level())
    }
    var segments = 0
    val edges = mutableListOf<RoadEdge>()
    val visuals = edgeRows.map {
        val row = it.asJsonObject
        val edge = row["id"].string().identifier()
        val points = row.getAsJsonArray("points")
        segments += points.size() - 1
        if (segments > profile.segmentLimit) throw InvalidFixture(FixtureError.ROAD_LIMIT, "$segments segments")
        require(points.size() >= 2)
        val structure = RoadStructure.valueOf(row["structure"].string())
        val road = Road(edge, points.map(JsonElement::point), structure == RoadStructure.TUNNEL)
        edges += RoadEdge(road, row["start"].string().identifier(), row["end"].string().identifier())
        RoadVisual(edge, RoadClass.valueOf(row["roadClass"].string()), structure, row.level(),
            row["physicalId"]?.string()?.identifier() ?: edge)
    }
    val transitions = root.getAsJsonObject("transitions").entrySet().associate { (edge, destinations) ->
        require(destinations.asJsonArray.size() <= 16)
        edge to destinations.asJsonArray.map(JsonElement::string)
    }
    require(transitions.size <= edges.size)
    val network = RoadNetwork(nodes, edges, transitions)
    val polygonRows = root.getAsJsonArray("polygons")
    val labelRows = root.getAsJsonArray("labels")
    require(polygonRows.size() <= 512 && labelRows.size() <= 64)
    var vertices = 0
    val polygons = polygonRows.map {
        val row = it.asJsonObject
        val points = row.getAsJsonArray("points")
        vertices += points.size()
        require(points.size() >= 3 && vertices <= 4096)
        val coordinates = points.map(JsonElement::point)
        require(coordinates.distinct().size == coordinates.size)
        LandPolygon(LandUse.valueOf(row["type"].string()), coordinates)
    }
    val labels = labelRows.map {
        val row = it.asJsonObject
        MapLabel(row["text"].string().also { text -> require(text.length in 1..80) }, row["point"].point())
    }
    return MapDefinition(id, 2, network, MapVisualData(visuals, polygons, labels), checksum)
}

private fun JsonObject.level() = (get("level")?.integer() ?: 0L).also { require(it in -8..8) }.toInt()
private fun String.identifier() = also { require(length in 1..32 && all { char -> char.isLetterOrDigit() || char == '_' }) }
private fun JsonElement.boolean(): Boolean {
    require(isJsonPrimitive && asJsonPrimitive.isBoolean)
    return asBoolean
}
private fun JsonElement.point(): Point2D {
    require(asJsonArray.size() == 2)
    return Point2D(asJsonArray[0].number(), asJsonArray[1].number()).also {
        require(it.finite && abs(it.x) <= 100_000 && abs(it.y) <= 100_000)
    }
}
private fun JsonObject.localized(key: String) = LocalizedText(
    get(key).string().also { require(it.isNotBlank() && it.length <= 300) },
    get(key + "Ko").string().also { require(it.isNotBlank() && it.length <= 300) },
)
