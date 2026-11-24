package lab.recovery.uimap

import lab.recovery.map.Point2D

enum class CameraMode { FOLLOW, OVERVIEW, FREE }

internal data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val center get() = Point2D((minX + maxX) / 2, (minY + maxY) / 2)

    fun fit(width: Double, height: Double, padding: Double): Double = minOf(
        (width - 2 * padding).coerceAtLeast(1.0) / (maxX - minX).coerceAtLeast(1.0),
        (height - 2 * padding).coerceAtLeast(1.0) / (maxY - minY).coerceAtLeast(1.0),
    )

    fun clamp(center: Point2D) = Point2D(center.x.coerceIn(minX, maxX), center.y.coerceIn(minY, maxY))

    companion object {
        fun from(points: List<Point2D>) =
            Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}

internal data class Projection(val center: Point2D, val scale: Double, val width: Double, val height: Double) {
    fun screen(point: Point2D) = Point2D(width / 2 + (point.x - center.x) * scale, height / 2 - (point.y - center.y) * scale)
    fun world(point: Point2D) = Point2D(center.x + (point.x - width / 2) / scale, center.y - (point.y - height / 2) / scale)

    fun manipulated(anchor: Point2D, pan: Point2D, zoom: Double, minScale: Double, maxScale: Double): Projection {
        val nextScale = (scale * zoom).coerceIn(minScale, maxScale)
        val fixed = world(anchor)
        val nextCenter = Point2D(
            fixed.x - (anchor.x + pan.x - width / 2) / nextScale,
            fixed.y + (anchor.y + pan.y - height / 2) / nextScale,
        )
        return copy(center = nextCenter, scale = nextScale)
    }
}
