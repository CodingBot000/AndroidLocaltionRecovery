package lab.recovery.uimap

import lab.recovery.map.Point2D
import lab.recovery.map.SpatialGrid
import lab.recovery.map.WorldBounds

internal class SpatialIndex<T>(items: List<T>, checkpoint: () -> Unit = {}, bounds: (T) -> Bounds) {
    private val world = SpatialGrid(items, 192.0, { bounds(it).worldBounds() }, checkpoint)

    fun visible(area: Bounds): List<T> = world.query(area.worldBounds())
}

private fun Bounds.worldBounds() = WorldBounds(minX, minY, maxX, maxY)

internal fun Point2D.bounds() = Bounds(x, y, x, y)

/** Visual-only Ramer–Douglas–Peucker with pinned endpoints and a bounded work stack. */
internal fun simplifyLine(points: List<Point2D>, tolerance: Double): List<Point2D> {
    if (points.size <= 2) return points
    val kept = BooleanArray(points.size)
    kept[0] = true
    kept[points.lastIndex] = true
    val work = ArrayDeque<IntRange>()
    work.add(0..points.lastIndex)
    val threshold = tolerance * tolerance
    while (work.isNotEmpty()) {
        val (first, last) = work.removeLast().let { it.first to it.last }
        val a = points[first]
        val b = points[last]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        var split = -1
        var max = threshold
        for (index in first + 1 until last) {
            val point = points[index]
            val x = point.x - a.x
            val y = point.y - a.y
            val fraction = if (squared == 0.0) 0.0 else ((x * dx + y * dy) / squared).coerceIn(0.0, 1.0)
            val ex = x - dx * fraction
            val ey = y - dy * fraction
            val distance = ex * ex + ey * ey
            if (distance > max) {
                max = distance
                split = index
            }
        }
        if (split >= 0) {
            kept[split] = true
            work.add(first..split)
            work.add(split..last)
        }
    }
    return points.filterIndexed { index, _ -> kept[index] }
}
