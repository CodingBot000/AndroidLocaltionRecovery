package lab.recovery.map

import java.util.BitSet
import kotlin.math.floor

data class WorldBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    fun intersects(other: WorldBounds): Boolean =
        maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY
}

/** A world-space grid with deterministic, source-ordered results and a bounded side list for wide entries. */
class SpatialGrid<T>(
    private val items: List<T>,
    private val cellMeters: Double,
    bounds: (T) -> WorldBounds,
    checkpoint: () -> Unit = {},
) {
    private val boxes = items.mapIndexed { index, item ->
        if (index % 256 == 0) checkpoint()
        bounds(item)
    }
    private val cells = mutableMapOf<Long, MutableList<Int>>()
    private val wide = mutableListOf<Int>()

    init {
        require(cellMeters.isFinite() && cellMeters > 0)
        boxes.forEachIndexed { index, box ->
            if (index % 256 == 0) checkpoint()
            val (left, bottom, right, top) = range(box)
            if (tooManyCells(left, bottom, right, top, 32L)) wide += index
            else for (x in left..right) for (y in bottom..top) {
                cells.getOrPut(key(x, y)) { mutableListOf() }.add(index)
            }
        }
    }

    fun query(area: WorldBounds): List<T> {
        if (items.isEmpty()) return emptyList()
        val (left, bottom, right, top) = range(area)
        if (tooManyCells(left, bottom, right, top, cells.size * 2L + 64L)) {
            return items.filterIndexed { index, _ -> boxes[index].intersects(area) }
        }
        val indices = BitSet(items.size)
        for (index in wide) indices.set(index)
        for (x in left..right) for (y in bottom..top) {
            cells[key(x, y)]?.forEach(indices::set)
        }
        return buildList {
            var index = indices.nextSetBit(0)
            while (index >= 0) {
                if (boxes[index].intersects(area)) add(items[index])
                index = indices.nextSetBit(index + 1)
            }
        }
    }

    private data class Cells(val left: Int, val bottom: Int, val right: Int, val top: Int)

    private fun range(box: WorldBounds) =
        Cells(cell(box.minX), cell(box.minY), cell(box.maxX), cell(box.maxY))

    private fun tooManyCells(left: Int, bottom: Int, right: Int, top: Int, limit: Long): Boolean =
        right.toLong() - left + 1L > limit / (top.toLong() - bottom + 1L)

    private fun cell(meters: Double) = floor(meters / cellMeters).toInt()
    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
}
