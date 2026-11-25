package lab.recovery.live

import com.google.gson.JsonParser
import lab.recovery.map.MapDefinition
import lab.recovery.map.OsmProvenance
import lab.recovery.map.Point2D
import java.io.Reader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class ProjectionSample(val longitude: Double, val latitude: Double, val expected: Point2D)
internal data class ProjectionControl(
    val checksum: String,
    val bbox: List<Double>,
    val samples: List<ProjectionSample>,
) {
    companion object {
        fun read(reader: Reader): Map<String, ProjectionControl> =
            JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (id, value) ->
                val row = value.asJsonObject
                val samples = row.getAsJsonArray("samples").map { item ->
                    val numbers = item.asJsonArray.map { it.asDouble }
                    ProjectionSample(numbers[0], numbers[1], Point2D(numbers[2], numbers[3]))
                }
                id to ProjectionControl(row["checksum"].asString, row.getAsJsonArray("bbox").map { it.asDouble }, samples)
            }
    }
}

internal class OsmProjection private constructor(
    private val osm: OsmProvenance,
    private val geographicBounds: List<Double>,
) {
    fun inCoverage(longitude: Double, latitude: Double): Point2D? {
        if (longitude !in geographicBounds[0]..geographicBounds[2] ||
            latitude !in geographicBounds[1]..geographicBounds[3]) return null
        val point = local(longitude, latitude)
        // Allow only the audited map's centimetre rounding at the projected bounds.
        val (west, south, east, north) = osm.bounds
        return point.takeIf { it.x in (west - .01)..(east + .01) && it.y in (south - .01)..(north + .01) }
    }

    fun local(longitude: Double, latitude: Double): Point2D {
        val national = KoreaUnifiedGrid.project(longitude, latitude)
        return Point2D(national.x - osm.origin.x, national.y - osm.origin.y)
    }

    companion object {
        fun verified(map: MapDefinition, control: ProjectionControl?): OsmProjection? {
            val osm = map.osm ?: return null
            if (osm.projection != "EPSG:5179" || control == null || map.checksum != control.checksum ||
                control.samples.size != 6 || control.bbox.size != 4) return null
            val projection = OsmProjection(osm, control.bbox)
            return projection.takeIf {
                control.samples.all { sample ->
                    val point = it.local(sample.longitude, sample.latitude)
                    hypot(point.x - sample.expected.x, point.y - sample.expected.y) <= .5
                }
            }
        }
    }
}

/** EPSG:5179 on GRS 1980. Transverse Mercator series for the audited South Korean extents. */
internal object KoreaUnifiedGrid {
    private const val radius = 6_378_137.0
    private const val scale = .9996
    private const val inverseFlattening = 298.257222101
    private val f = 1 / inverseFlattening
    private val e2 = f * (2 - f)
    private val e4 = e2 * e2
    private val e6 = e4 * e2
    private val ep2 = e2 / (1 - e2)
    private val meridianOrigin = meridian(Math.toRadians(38.0))

    private fun meridian(phi: Double): Double = radius * (
        (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
            (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
            (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) - (35 * e6 / 3072) * sin(6 * phi)
        )

    fun project(longitude: Double, latitude: Double): Point2D {
        val phi = Math.toRadians(latitude)
        val n = radius / sqrt(1 - e2 * sin(phi).pow(2))
        val t = tan(phi).pow(2)
        val c = ep2 * cos(phi).pow(2)
        val a = cos(phi) * Math.toRadians(longitude - 127.5)
        val x = 1_000_000 + scale * n *
            (a + (1 - t + c) * a.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120)
        val y = 2_000_000 + scale * (meridian(phi) - meridianOrigin + n * tan(phi) *
            (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720))
        return Point2D(x, y)
    }
}
