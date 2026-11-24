package lab.recovery.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import lab.recovery.map.LandPolygon
import lab.recovery.map.LandUse
import lab.recovery.map.MapDefinition
import lab.recovery.map.MapLabel
import lab.recovery.map.MapVisualData
import lab.recovery.map.Point2D
import lab.recovery.map.OsmProvenance
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
    val catalog: ScenarioCatalog by lazy { readCatalog("extended", 2L) }
    val osmCatalog: ScenarioCatalog by lazy { readCatalog("osm", 3L) }

    fun readLegacy(path: String): Scenario {
        require(path == "development/DEMO.json" || legacyPath.matches(path))
        return FixtureReader.read(source.open(path))
    }

    fun read(id: String): Scenario = readDataset(id, "extended", catalog, FixtureProfile.STANDARD)

    fun readOsm(id: String): Scenario = readDataset(id, "osm", osmCatalog, FixtureProfile.OSM)

    private fun readDataset(id: String, directory: String, catalog: ScenarioCatalog, profile: FixtureProfile): Scenario = fixtureSchema {
        val entry = catalog.entries.singleOrNull { it.id == id } ?: error("Unknown scenario $id")
        val mapBytes = fixtureBytes(source.open("$directory/maps/${entry.mapId}.json"), profile.byteLimit)
        val map = parseMap(fixtureObject(mapBytes), entry.mapId, sha256(mapBytes), profile)
        val group = if (entry.evaluation) "evaluation" else "scenarios"
        val scenario = FixtureReader.read(source.open("$directory/$group/$id.json"), profile, map, profile.byteLimit - mapBytes.size)
        require(scenario.id == id && scenario.durationMs == entry.durationMs && scenario.title == entry.title.english)
        scenario
    }

    private fun readCatalog(directory: String, schema: Long): ScenarioCatalog = fixtureSchema {
        val root = fixtureObject(fixtureBytes(source.open("$directory/catalog.json"), 256 * 1024))
        require(root["schemaVersion"].integer() == schema)
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
    val osm = if (profile == FixtureProfile.OSM) osmProvenance(root) else null
    val schema = if (osm == null) 2 else 3
    require(root["schemaVersion"].integer() == schema.toLong() && root["mapId"].string() == id)
    val nodeRows = root.getAsJsonArray("nodes")
    val edgeRows = root.getAsJsonArray("edges")
    val graphLimit = if (osm == null) 512 else 12000
    require(nodeRows.size() in 2..graphLimit && edgeRows.size() in 1..graphLimit)
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
        val physicalId = row["physicalId"]?.string()?.identifier() ?: edge
        val road = Road(edge, points.map(JsonElement::point), structure == RoadStructure.TUNNEL,
            physicalId.takeIf { osm != null })
        edges += RoadEdge(road, row["start"].string().identifier(), row["end"].string().identifier())
        RoadVisual(edge, RoadClass.valueOf(row["roadClass"].string()), structure, row.level(),
            physicalId, row["name"]?.string()?.label())
    }
    val transitions = root.getAsJsonObject("transitions").entrySet().associate { (edge, destinations) ->
        require(destinations.asJsonArray.size() <= 16)
        edge to destinations.asJsonArray.map(JsonElement::string)
    }
    require(transitions.size <= edges.size)
    val network = RoadNetwork(nodes, edges, transitions)
    val polygonRows = root.getAsJsonArray("polygons")
    val labelRows = root.getAsJsonArray("labels")
    val vertexLimit = if (osm == null) 4096 else 260000
    require(polygonRows.size() <= (if (osm == null) 512 else 30000) && labelRows.size() <= 64)
    var vertices = 0
    fun ring(points: com.google.gson.JsonArray, minimum: Int): List<Point2D> {
        vertices += points.size()
        require(points.size() >= minimum && vertices <= vertexLimit)
        return points.map(JsonElement::point).also { require(it.distinct().size == it.size) }
    }
    val polygons = polygonRows.map {
        val row = it.asJsonObject
        val coordinates = ring(row.getAsJsonArray("points"), 3)
        val holes = row.getAsJsonArray("holes")?.map { ring(it.asJsonArray, 3) }.orEmpty()
        require(osm != null || holes.isEmpty())
        val id = row["id"]?.string()?.identifier()
        val parcel = row["parcelId"]?.string()?.identifier()
        LandPolygon(LandUse.valueOf(row["type"].string()), coordinates, id, parcel, holes)
    }
    val labels = labelRows.map {
        val row = it.asJsonObject
        MapLabel(row["text"].string().label(), row["point"].point())
    }
    val version = (root["visualVersion"]?.integer() ?: 1L).also { require(it in 1..2) }.toInt()
    val parcelIds = polygons.mapNotNull(LandPolygon::id)
    require(parcelIds.size == parcelIds.toSet().size)
    require(polygons.all { polygon ->
        if (polygon.type == LandUse.BUILDING) {
            polygon.parcelId in parcelIds || ((version == 1 || osm != null) && polygon.parcelId == null)
        } else polygon.parcelId == null
    })
    val paths = root.getAsJsonArray("walkways")?.map { element ->
        val points = element.asJsonArray
        vertices += points.size()
        require(points.size() in 2..(if (osm == null) 64 else 20000) && vertices <= vertexLimit)
        points.map(JsonElement::point).also { route ->
            require(route.zipWithNext().all { (a, b) -> a != b })
        }
    }.orEmpty()
    require(paths.size <= (if (osm == null) 64 else 15000))
    return MapDefinition(id, schema, network, MapVisualData(visuals, polygons, labels, paths, version), checksum, osm)
}

private fun osmProvenance(root: JsonObject): OsmProvenance {
    val data = root.getAsJsonObject("source")
    val projection = root.getAsJsonObject("projection")
    require(data["dataset"].string() == "OSM")
    val origin = projection.getAsJsonArray("origin").map(JsonElement::number)
    val bounds = projection.getAsJsonArray("bounds").map(JsonElement::number)
    require(origin.size == 2 && origin.all(Double::isFinite))
    require(bounds.size == 4 && bounds.all(Double::isFinite) && bounds[0] < bounds[2] && bounds[1] < bounds[3])
    val attribution = data["attribution"].string().also { require(it == "© OpenStreetMap contributors") }
    return OsmProvenance(attribution, data["license"].string().also { require(it == "ODbL-1.0") },
        data["licenseUrl"].string().also { require(it.startsWith("https://")) },
        data["url"].string().also { require(it.startsWith("https://")) },
        data["snapshot"].string(), data["sha256"].string().also { require(it.matches(Regex("[0-9a-f]{64}"))) },
        projection["crs"].string(), Point2D(origin[0], origin[1]), bounds)
}

private fun String.label() = also { require(isNotBlank() && length <= 80) }
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
