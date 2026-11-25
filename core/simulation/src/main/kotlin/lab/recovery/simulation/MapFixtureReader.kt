package lab.recovery.simulation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import lab.recovery.map.LandPolygon
import lab.recovery.map.LandUse
import lab.recovery.map.MapDefinition
import lab.recovery.map.MapLabel
import lab.recovery.map.MapPoi
import lab.recovery.map.MapVisualData
import lab.recovery.map.OsmProvenance
import lab.recovery.map.PoiType
import lab.recovery.map.Point2D
import lab.recovery.map.Road
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadEdge
import lab.recovery.map.RoadNetwork
import lab.recovery.map.RoadNode
import lab.recovery.map.RoadStructure
import lab.recovery.map.RoadVisual
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.abs

internal fun readMapFixture(input: InputStream, id: String, profile: FixtureProfile): Pair<MapDefinition, Int> {
    val bytes = MapFixtureInput(input, profile.byteLimit)
    return JsonReader(bytes.reader(Charsets.UTF_8)).use { reader ->
        reader.strictness = Strictness.STRICT
        val map = MapFixtureReader(profile).read(reader, id)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        map.copy(checksum = bytes.checksum()) to bytes.count
    }
}

/** Counts and hashes the original bytes as JSON is decoded, including trailing whitespace. */
private class MapFixtureInput(input: InputStream, private val limit: Int) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            record(1)
            digest.update(value.toByte())
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = super.read(buffer, offset, minOf(length, limit - count + 1))
        if (read > 0) {
            record(read)
            digest.update(buffer, offset, read)
        }
        return read
    }

    private fun record(read: Int) {
        if (read > limit - count) throw InvalidFixture(FixtureError.SIZE_LIMIT, "$limit bytes")
        count += read
    }

    fun checksum(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private class MapFixtureReader(private val profile: FixtureProfile) {
    private val osm = profile == FixtureProfile.OSM
    private val edgeLimit = if (osm) 22000 else 512
    private val vertexLimit = if (osm) 260000 else 4096
    private var segments = 0
    private var vertices = 0

    fun read(reader: JsonReader, id: String): MapDefinition {
        val schema = if (osm) 3 else 2
        var mapId: String? = null
        var version: Long? = null
        var visualVersion = 1
        var source: JsonObject? = null
        var projection: JsonObject? = null
        var nodes: List<RoadNode>? = null
        val edges = mutableListOf<RoadEdge>()
        val roads = mutableListOf<RoadVisual>()
        var turns: Map<String, List<String>>? = null
        var polygons: List<LandPolygon>? = null
        var labels: List<MapLabel>? = null
        var walks = emptyList<List<Point2D>>()
        var rails = emptyList<List<Point2D>>()
        var pois = emptyList<MapPoi>()

        val sections = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(sections.add(field)) { "Duplicate map field: $field" }
            when (field) {
                "schemaVersion" -> version = reader.element().integer()
                "mapId" -> mapId = reader.element().string()
                "visualVersion" -> visualVersion = reader.element().integer().also { require(it in 1..2) }.toInt()
                "source" -> if (osm) source = reader.element().asJsonObject else reader.skipValue()
                "projection" -> if (osm) projection = reader.element().asJsonObject else reader.skipValue()
                "nodes" -> nodes = reader.rows(if (osm) 12000 else 512).map { node(it.asJsonObject) }.toList()
                "edges" -> reader.rows(edgeLimit).forEach { value ->
                    val (edge, visual) = edge(value.asJsonObject)
                    edges.add(edge)
                    roads.add(visual)
                }
                "transitions" -> turns = transitions(reader)
                "polygons" -> polygons = reader.rows(if (osm) 30000 else 512).map { polygon(it.asJsonObject) }.toList()
                "labels" -> labels = reader.rows(64).map {
                    val row = it.asJsonObject
                    MapLabel(row["text"].string().label(), row["point"].point())
                }.toList()
                "walkways" -> walks = lines(reader)
                "railways" -> rails = lines(reader)
                "pois" -> pois = reader.rows(4096).map {
                    val row = it.asJsonObject
                    MapPoi(PoiType.valueOf(row["type"].string()), row["point"].point(), row["name"]?.string()?.label())
                }.toList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(mapId == id && version == schema.toLong())
        val networkNodes = requireNotNull(nodes)
        val networkTurns = requireNotNull(turns)
        require(networkNodes.size >= 2 && edges.isNotEmpty() && networkTurns.size <= edges.size)
        val land = requireNotNull(polygons)
        val parcelIds = land.mapNotNull(LandPolygon::id).toSet()
        require(parcelIds.size == land.count { it.id != null })
        require(land.all {
            if (it.type == LandUse.BUILDING) {
                it.parcelId in parcelIds || ((visualVersion == 1 || osm) && it.parcelId == null)
            } else it.parcelId == null
        })
        require(osm || (rails.isEmpty() && pois.isEmpty()))
        val network = RoadNetwork(networkNodes, edges, networkTurns)
        val visual = MapVisualData(roads, land, requireNotNull(labels), walks, visualVersion, rails, pois)
        val provenance = if (osm) osmProvenance(requireNotNull(source), requireNotNull(projection)) else null
        return MapDefinition(id, schema, network, visual, osm = provenance)
    }

    private fun node(row: JsonObject) = RoadNode(row["id"].string().identifier(), row["point"].point(), row.level())

    private fun edge(row: JsonObject): Pair<RoadEdge, RoadVisual> {
        val id = row["id"].string().identifier()
        val points = row.getAsJsonArray("points")
        require(points.size() >= 2)
        segments += points.size() - 1
        if (segments > profile.segmentLimit) throw InvalidFixture(FixtureError.ROAD_LIMIT, "$segments segments")
        val structure = RoadStructure.valueOf(row["structure"].string())
        val physicalId = row["physicalId"]?.string()?.identifier() ?: id
        val road = Road(id, points.map(JsonElement::point), structure == RoadStructure.TUNNEL, physicalId.takeIf { osm })
        return RoadEdge(road, row["start"].string().identifier(), row["end"].string().identifier()) to
            RoadVisual(id, RoadClass.valueOf(row["roadClass"].string()), structure, row.level(),
                physicalId, row["name"]?.string()?.label())
    }

    private fun transitions(reader: JsonReader): Map<String, List<String>> = buildMap {
        reader.beginObject()
        while (reader.hasNext()) {
            require(size < edgeLimit)
            val id = reader.nextName()
            require(id !in this)
            put(id, reader.rows(16).map(JsonElement::string).toList())
        }
        reader.endObject()
    }

    private fun polygon(row: JsonObject): LandPolygon {
        val points = ring(row.getAsJsonArray("points"))
        val holes = row.getAsJsonArray("holes")?.map { ring(it.asJsonArray) }.orEmpty()
        val mid = row.getAsJsonArray("mid")?.map { ring(it.asJsonArray) }.orEmpty()
        val overview = row.getAsJsonArray("overview")?.map { ring(it.asJsonArray) }.orEmpty()
        require(osm || (holes.isEmpty() && mid.isEmpty() && overview.isEmpty()))
        val id = row["id"]?.string()?.identifier()
        val parcel = row["parcelId"]?.string()?.identifier()
        return LandPolygon(LandUse.valueOf(row["type"].string()), points, id, parcel, holes, mid, overview)
    }

    private fun ring(points: JsonArray): List<Point2D> =
        vertices(points, 3).also { require(it.distinct().size == it.size) }

    private fun vertices(points: JsonArray, minimum: Int): List<Point2D> {
        vertices += points.size()
        require(points.size() >= minimum && vertices <= vertexLimit)
        return points.map(JsonElement::point)
    }

    private fun lines(reader: JsonReader): List<List<Point2D>> =
        reader.rows(if (osm) 15000 else 64).map {
            val points = it.asJsonArray
            require(points.size() <= if (osm) 20000 else 64)
            vertices(points, 2).also { route ->
                require((1 until route.size).all { index -> route[index - 1] != route[index] })
            }
        }.toList()
}

private fun JsonReader.element(): JsonElement = JsonParser.parseReader(this)

/** Each iteration consumes one entry and releases its temporary JSON tree. */
private fun JsonReader.rows(limit: Int): Sequence<JsonElement> = sequence {
    beginArray()
    var count = 0
    while (hasNext()) {
        require(count++ < limit)
        yield(element())
    }
    endArray()
}

private fun osmProvenance(data: JsonObject, projection: JsonObject): OsmProvenance {
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
private fun JsonElement.point(): Point2D {
    require(asJsonArray.size() == 2)
    return Point2D(asJsonArray[0].number(), asJsonArray[1].number()).also {
        require(it.finite && abs(it.x) <= 100_000 && abs(it.y) <= 100_000)
    }
}
