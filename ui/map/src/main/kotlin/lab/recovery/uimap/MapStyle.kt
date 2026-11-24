package lab.recovery.uimap

import androidx.compose.ui.graphics.Color
import lab.recovery.map.LandUse
import lab.recovery.map.RoadClass

internal data class MapStyle(
    val background: Color,
    val block: Color,
    val building: Color,
    val park: Color,
    val water: Color,
    val casing: Color,
    val local: Color,
    val arterial: Color,
    val express: Color,
    val tunnel: Color,
    val route: Color,
    val label: Color,
) {
    fun land(use: LandUse) = when (use) {
        LandUse.BLOCK -> block
        LandUse.BUILDING -> building
        LandUse.PARK -> park
        LandUse.WATER -> water
    }
    fun road(type: RoadClass) = when (type) {
        RoadClass.LOCAL, RoadClass.COLLECTOR -> local
        RoadClass.ARTERIAL -> arterial
        RoadClass.EXPRESSWAY, RoadClass.RAMP -> express
    }

    companion object {
        val Day = MapStyle(Color(0xFFF1EFE4), Color(0xFFE5E1D2), Color(0xFFC8BFB2), Color(0xFFC8DBB4),
            Color(0xFFA2CBDE), Color(0xFF938E83), Color(0xFFFFFEFA), Color(0xFFFFEAB9), Color(0xFFFFD38B),
            Color(0xFF7C6B80), Color(0xFF224C96), Color(0xFF34443B))
        val Night = MapStyle(Color(0xFF17231F), Color(0xFF202D2B), Color(0xFF35403D), Color(0xFF314D36),
            Color(0xFF24475B), Color(0xFF161B1F), Color(0xFF566461), Color(0xFF82704F), Color(0xFFA47A4A),
            Color(0xFFD0ACD5), Color(0xFF7FAEFB), Color(0xFFD8E4D8))
    }
}
