package lab.recovery.uimap

import androidx.compose.ui.graphics.Color

enum class MapSymbol(val glyph: String) {
    FIX("➤"), GPS("○"), REJECTED("×"), DR("◇"), LAST("⊖"), TRUTH("□"),
    PLAN("━"), TRAIL("─"), OUTSIDE("›");

    fun color(dark: Boolean): Color = (if (dark) MapStyle.Night else MapStyle.Day).symbolColor(this)
}

fun mapLegendBackground(dark: Boolean): Color = (if (dark) MapStyle.Night else MapStyle.Day).background

internal fun MapStyle.symbolColor(symbol: MapSymbol): Color = when (symbol) {
    MapSymbol.FIX, MapSymbol.TRAIL -> driven
    MapSymbol.GPS, MapSymbol.OUTSIDE -> text
    MapSymbol.REJECTED -> raw
    MapSymbol.DR -> estimate
    MapSymbol.LAST -> muted
    MapSymbol.TRUTH -> truth
    MapSymbol.PLAN -> route
}
