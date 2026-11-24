package lab.recovery.map

import kotlin.math.atan2
import kotlin.math.hypot

data class Point2D(val x: Double, val y: Double) {
    val finite get() = x.isFinite() && y.isFinite()
    fun distance(other: Point2D): Double = hypot(x - other.x, y - other.y)
    fun interpolate(other: Point2D, fraction: Double) =
        Point2D(x + (other.x - x) * fraction, y + (other.y - y) * fraction)
}

data class Segment(val index: Int, val start: Point2D, val end: Point2D) {
    val length = start.distance(end)
    val heading = (Math.toDegrees(atan2(end.x - start.x, end.y - start.y)) + 360) % 360

    fun project(point: Point2D): Point2D {
        val x = (end.x - start.x) / length
        val y = (end.y - start.y) / length
        val fraction = (((point.x - start.x) * x + (point.y - start.y) * y) / length).coerceIn(0.0, 1.0)
        return start.interpolate(end, fraction)
    }
}

data class Road(
    val id: String,
    val points: List<Point2D>,
    val tunnel: Boolean = false,
    val physicalId: String? = null,
) {
    init { require(points.size >= 2) }

    private val minX = points.minOf { it.x }
    private val minY = points.minOf { it.y }
    private val maxX = points.maxOf { it.x }
    private val maxY = points.maxOf { it.y }

    fun withinBounds(point: Point2D, radius: Double): Boolean =
        point.x >= minX - radius && point.x <= maxX + radius &&
            point.y >= minY - radius && point.y <= maxY + radius

    val segments = points.zipWithNext().mapIndexed { index, (from, to) -> Segment(index, from, to) }
    private val starts = segments.runningFold(0.0) { sum, segment -> sum + segment.length }
    val length = starts.last()

    init {
        require(id.isNotBlank() && points.size >= 2 && points.all(Point2D::finite))
        require(length.isFinite() && segments.all { it.length > 0 })
    }

    fun offset(segment: Segment, position: Point2D): Double =
        starts[segment.index] + segment.start.distance(position)

    fun at(offset: Double): Point2D {
        var remaining = offset
        for (segment in segments) {
            if (remaining <= segment.length) return segment.start.interpolate(segment.end, remaining / segment.length)
            remaining -= segment.length
        }
        return points.last()
    }
}

