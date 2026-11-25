package lab.recovery.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import lab.recovery.map.MapDefinition
import lab.recovery.map.RoadGraph
import java.io.InputStream

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
    private data class LoadedMap(val path: String, val map: MapDefinition, val byteCount: Int)
    @Volatile private var lastMap: LoadedMap? = null
    val catalog: ScenarioCatalog by lazy { readCatalog("extended", 2L) }
    val osmCatalog: ScenarioCatalog by lazy { readCatalog("osm", 3L) }
    val challengeCatalog: ScenarioCatalog by lazy { readCatalog("osm/challenge", 3L) }

    fun readLegacy(path: String): Scenario {
        require(path == "development/DEMO.json" || legacyPath.matches(path))
        return FixtureReader.read(source.open(path))
    }

    fun read(id: String): Scenario = readDataset(id, "extended", catalog, FixtureProfile.STANDARD)

    fun readOsm(id: String): Scenario = readDataset(id, "osm", osmCatalog, FixtureProfile.OSM)

    fun readChallenge(id: String): Scenario =
        readDataset(id, "osm/challenge", challengeCatalog, FixtureProfile.OSM, mapDirectory = "osm")

    fun readOsmMap(id: String): MapDefinition = fixtureSchema {
        require(id in osmCatalog.maps)
        loadMap("osm/maps/$id.json", id, FixtureProfile.OSM).map
    }

    /** Exposes only the chosen plan to live callers, without reading observations or ground truth into the engine. */
    fun readOsmPlan(id: String, map: MapDefinition): RoadGraph = fixtureSchema {
        val entry = osmCatalog.entries.single { it.id == id && !it.evaluation && it.mapId == map.id }
        val root = fixtureObject(fixtureBytes(source.open("osm/scenarios/${entry.id}.json"), FixtureProfile.OSM.byteLimit))
        require(root["scenarioId"].string() == id && root["schemaVersion"].integer() == map.version.toLong())
        fixtureGraph(root, map)
    }

    private fun loadMap(path: String, id: String, profile: FixtureProfile): LoadedMap =
        lastMap?.takeIf { it.path == path } ?: run {
            val (map, byteCount) = readMapFixture(source.open(path), id, profile)
            LoadedMap(path, map, byteCount).also { lastMap = it }
        }

    private fun readDataset(
        id: String, directory: String, catalog: ScenarioCatalog, profile: FixtureProfile, mapDirectory: String = directory,
    ): Scenario = fixtureSchema {
        val entry = catalog.entries.singleOrNull { it.id == id } ?: error("Unknown scenario $id")
        val path = "$mapDirectory/maps/${entry.mapId}.json"
        val loaded = loadMap(path, entry.mapId, profile)
        val group = if (entry.evaluation) "evaluation" else if (directory == "osm/challenge") "development" else "scenarios"
        val scenario = FixtureReader.read(source.open("$directory/$group/$id.json"), profile, loaded.map, profile.byteLimit - loaded.byteCount)
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
                it.durationMs in if (directory == "osm/challenge") 90_000L..180_000L
                    else if (it.representative) 150_000L..180_000L else 90_000L..120_000L
        })
        require(entries.count { it.representative } == 1)
        ScenarioCatalog(maps, entries)
    }

    private companion object {
        val identifier = Regex("[A-Z][A-Z0-9_]{0,31}")
        val legacyPath = Regex("evaluation/S[0-9]{2}[A-Z]?\\.json")
    }
}

private fun JsonElement.boolean(): Boolean {
    require(isJsonPrimitive && asJsonPrimitive.isBoolean)
    return asBoolean
}
private fun JsonObject.localized(key: String) = LocalizedText(
    get(key).string().also { require(it.isNotBlank() && it.length <= 300) },
    get(key + "Ko").string().also { require(it.isNotBlank() && it.length <= 300) },
)
