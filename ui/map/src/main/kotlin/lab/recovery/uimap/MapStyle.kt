package lab.recovery.uimap

import androidx.compose.ui.graphics.Color
import lab.recovery.map.LandUse
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure

internal enum class Detail { DETAIL, MID, OVERVIEW }

internal fun mapDetail(mode: CameraMode, visibleMeters: Double): Detail = when {
    mode == CameraMode.OVERVIEW || visibleMeters >= 1500 -> Detail.OVERVIEW
    visibleMeters >= 650 -> Detail.MID
    else -> Detail.DETAIL
}

internal fun roadVisible(
    type: RoadClass,
    structure: RoadStructure,
    detail: Detail,
    planned: Boolean,
    visibleMeters: Double,
): Boolean =
    planned || structure != RoadStructure.SURFACE || when (detail) {
        Detail.DETAIL, Detail.MID -> true
        Detail.OVERVIEW -> type != RoadClass.LOCAL || visibleMeters < 1250
    }

internal data class RoadStyle(val fill: Color, val casing: Color, val fillDp: Float, val casingDp: Float)
internal data class LandStyle(val fill: Color, val edge: Color? = null)

internal data class MapStyle(
    val background: Color,
    val residential: Color,
    val commercial: Color,
    val industrial: Color,
    val building: LandStyle,
    val park: LandStyle,
    val water: LandStyle,
    val local: RoadStyle,
    val secondary: RoadStyle,
    val primary: RoadStyle,
    val highway: RoadStyle,
    val ramp: RoadStyle,
    val tunnel: RoadStyle,
    val route: Color,
    val routeHalo: Color,
    val driven: Color,
    val vehicle: Color,
    val estimate: Color,
    val raw: Color,
    val text: Color,
    val muted: Color,
    val labelHalo: Color,
    val truth: Color,
    val chrome: Color,
    val chromeText: Color,
    val school: LandStyle,
    val sport: LandStyle,
) {
    fun land(type: LandUse) = when (type) {
        LandUse.BLOCK, LandUse.RESIDENTIAL -> LandStyle(residential)
        LandUse.COMMERCIAL -> LandStyle(commercial)
        LandUse.INDUSTRIAL -> LandStyle(industrial)
        LandUse.BUILDING -> building
        LandUse.PARK -> park
        LandUse.WATER -> water
        LandUse.SCHOOL -> school
        LandUse.SPORT -> sport
        LandUse.PARKING -> LandStyle(industrial)
    }

    fun road(type: RoadClass, structure: RoadStructure): RoadStyle {
        val grade = when (type) {
            RoadClass.LOCAL -> local
            RoadClass.COLLECTOR -> secondary
            RoadClass.ARTERIAL -> primary
            RoadClass.EXPRESSWAY -> highway
            RoadClass.RAMP -> ramp
        }
        return if (structure == RoadStructure.TUNNEL) grade.copy(fill = tunnel.fill, casing = tunnel.casing) else grade
    }

    companion object {
        val Day = MapStyle(
            background = Color(0xFFF2F3EF), residential = Color(0xFFE8EBE5), commercial = Color(0xFFE6E7E4),
            industrial = Color(0xFFE3E5E8), building = LandStyle(Color(0xFFD3D7D4), Color(0xFFC3C9C5)),
            park = LandStyle(Color(0xFFCFE3BE), Color(0xFFBDD5A9)), water = LandStyle(Color(0xFFA7D3E9), Color(0xFF91C3DF)),
            local = RoadStyle(Color.White, Color(0xFFD3D8D3), 5f, 7f),
            secondary = RoadStyle(Color.White, Color(0xFFBEC7C6), 8f, 11f),
            primary = RoadStyle(Color(0xFFFFF4CF), Color(0xFFC5B88F), 12f, 16f),
            highway = RoadStyle(Color(0xFFFFE2A5), Color(0xFFC5A46C), 16f, 21f),
            ramp = RoadStyle(Color(0xFFFFF4CF), Color(0xFFC5B88F), 7f, 10f),
            tunnel = RoadStyle(Color(0xFFD5D7D5), Color(0xFFA4ADB0), 8f, 11f),
            route = Color(0xFF2569DF), routeHalo = Color.White, driven = Color(0xFF1374A8),
            vehicle = Color(0xFF087F49), estimate = Color(0xFFB46D10),
            raw = Color(0xFFC73836), text = Color(0xFF34424C), muted = Color(0xFF60717C), labelHalo = Color(0xFFF5F6F2),
            truth = Color(0xFF7252A0), chrome = Color(0xFFF8FAF7), chromeText = Color(0xFF34424C),
            school = LandStyle(Color(0xFFF1E5CC), Color(0xFFD9C79E)), sport = LandStyle(Color(0xFFD9E8C3), Color(0xFFAAC48D)),
        )
        val Night = MapStyle(
            background = Color(0xFF191F29), residential = Color(0xFF232C35), commercial = Color(0xFF29313A),
            industrial = Color(0xFF292F38), building = LandStyle(Color(0xFF35414D), Color(0xFF414C58)),
            park = LandStyle(Color(0xFF263D34), Color(0xFF345044)), water = LandStyle(Color(0xFF244A60), Color(0xFF335D71)),
            local = RoadStyle(Color(0xFF465464), Color(0xFF28323E), 5f, 7f),
            secondary = RoadStyle(Color(0xFF586879), Color(0xFF293642), 8f, 11f),
            primary = RoadStyle(Color(0xFF77828D), Color(0xFF35414E), 12f, 16f),
            highway = RoadStyle(Color(0xFF9A8B70), Color(0xFF4B443D), 16f, 21f),
            ramp = RoadStyle(Color(0xFF77828D), Color(0xFF35414E), 7f, 10f),
            tunnel = RoadStyle(Color(0xFF414D5B), Color(0xFF667584), 8f, 11f),
            route = Color(0xFF548FFE), routeHalo = Color(0xFF142439), driven = Color(0xFF6FC3F3),
            vehicle = Color(0xFF56D68B), estimate = Color(0xFFFFC47B),
            raw = Color(0xFFFF8C84), text = Color(0xFFE6EDF3), muted = Color(0xFFA6B4C2), labelHalo = Color(0xFF19222C),
            truth = Color(0xFFC9B4FF), chrome = Color(0xFF263241), chromeText = Color(0xFFE6EDF3),
            school = LandStyle(Color(0xFF3B332A), Color(0xFF675642)), sport = LandStyle(Color(0xFF304637), Color(0xFF49684F)),
        )
    }
}
