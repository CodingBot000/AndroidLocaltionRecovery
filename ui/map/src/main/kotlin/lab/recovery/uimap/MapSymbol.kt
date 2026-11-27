package lab.recovery.uimap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

enum class MapSymbol(val glyph: String) {
    FIX("➤"), GPS("○"), REJECTED("×"), DR("◇"), LAST("⊖"), TRUTH("□"),
    PLAN("━"), TRAIL("─"), OUTSIDE("›");

    fun color(dark: Boolean): Color = (if (dark) MapStyle.Night else MapStyle.Day).symbolColor(this)
}

fun mapLegendBackground(dark: Boolean): Color = (if (dark) MapStyle.Night else MapStyle.Day).background

@Composable
fun MapLegendMarker(symbol: MapSymbol, dark: Boolean) {
    val style = if (dark) MapStyle.Night else MapStyle.Day
    when (symbol) {
        MapSymbol.FIX -> LegendMarker { drawVehicle(center, 90.0, style) }
        MapSymbol.REJECTED -> LegendMarker { drawRawCross(center, style, overlapsVehicle = true) }
        MapSymbol.TRUTH -> {
            val stroke = rememberTruthMarkerStroke()
            LegendMarker { drawTruthMarker(center, style, overlapsVehicle = true, stroke = stroke) }
        }
        else -> Text(symbol.glyph, color = style.symbolColor(symbol), modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LegendMarker(drawMarker: DrawScope.() -> Unit) {
    Canvas(Modifier.size(20.dp)) { scale(.5f, pivot = center, block = drawMarker) }
}

@Composable
internal fun rememberTruthMarkerStroke(): Stroke {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        }
    }
}

internal fun MapStyle.symbolColor(symbol: MapSymbol): Color = when (symbol) {
    MapSymbol.FIX -> vehicle
    MapSymbol.TRAIL -> driven
    MapSymbol.GPS, MapSymbol.OUTSIDE -> text
    MapSymbol.REJECTED -> raw
    MapSymbol.DR -> estimate
    MapSymbol.LAST -> muted
    MapSymbol.TRUTH -> truth.copy(alpha = .5f)
    MapSymbol.PLAN -> route
}
