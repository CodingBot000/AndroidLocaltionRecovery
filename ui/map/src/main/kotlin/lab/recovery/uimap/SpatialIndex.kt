package lab.recovery.uimap

import lab.recovery.map.Point2D
import kotlin.math.floor

/** Fixed world-space cells preserve source drawing order. Very large shapes use a small side list. */
internal class SpatialIndex<T>(
    private val items: List<T>,
    private val bounds: (T) -> Bounds,
) {
    private val cells = mutableMapOf<Long, MutableList<Int>>()
    private val wide = mutableListOf<Int>()

    init {
        items.forEachIndexed { index, item ->
            val (left, bottom, right, top) = range(bounds(item))
            if ((right - left + 1L) * (top - bottom + 1L) > 32) wide += index
            else for (x in left..right) for (y in bottom..top) {
                cells.getOrPut(key(x, y)) { mutableListOf() }.add(index)
            }
        }
    }

    fun visible(area: Bounds): List<T> {
        if (items.isEmpty()) return emptyList()
        val (left, bottom, right, top) = range(area)
        if ((right - left + 1L) * (top - bottom + 1L) > cells.size * 2L + 64) {
            return items.filter { bounds(it).intersects(area) }
        }
        val indices = java.util.BitSet(items.size)
        for (index in wide) indices.set(index)
        for (x in left..right) for (y in bottom..top) {
            cells[key(x, y)]?.forEach { indices.set(it) }
        }
        return buildList {
            var index = indices.nextSetBit(0)
            while (index >= 0) {
                val item = items[index]
                if (bounds(item).intersects(area)) add(item)
                index = indices.nextSetBit(index + 1)
            }
        }
    }

    private data class Cells(val left: Int, val bottom: Int, val right: Int, val top: Int)
    private fun range(box: Bounds) = Cells(cell(box.minX), cell(box.minY), cell(box.maxX), cell(box.maxY))
    private fun cell(meters: Double) = floor(meters / 192).toInt()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffff)
}

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
