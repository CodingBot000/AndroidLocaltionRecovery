package lab.recovery.uimap

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection

/** Renders the static map into one viewport bitmap on low-heap Android 8 devices. */
internal class SoftwareMapLayer {
    private data class Frame(
        val camera: Projection,
        val style: MapStyle,
        val detail: Detail,
        val density: Float,
        val fontScale: Float,
        val direction: LayoutDirection,
    )

    private val scope = CanvasDrawScope()
    private var image: ImageBitmap? = null
    private var canvas: Canvas? = null
    private var previous: Frame? = null

    fun draw(
        target: DrawScope, map: MapGeometry, camera: Projection, style: MapStyle, detail: Detail,
        strokes: MapStrokeCache,
    ) = with(target) {
        val width = size.width.toInt()
        val height = size.height.toInt()
        val pixels = image?.takeIf { it.width == width && it.height == height }
            ?: ImageBitmap(width, height).also {
                image = it
                canvas = Canvas(it)
                previous = null
            }
        val frame = Frame(camera, style, detail, density, fontScale, layoutDirection)
        if (previous != frame) {
            pixels.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
            scope.draw(this, layoutDirection, checkNotNull(canvas), size) {
                drawMap(map, camera, style, detail, strokes)
            }
            previous = frame
        }
        drawImage(pixels)
    }
}
