package lab.recovery.uimap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ObservationPlacementTest {
    @Test fun visiblePointKeepsItsProjectionAndOffscreenPointGetsAnEdgeMarker() {
        val viewport = Size(100f, 200f)
        val visible = Offset(1f, 4f)
        assertEquals(RawPlacement(visible, false), rawPlacement(visible, viewport, 10f))
        assertEquals(RawPlacement(Offset(90f, 190f), true), rawPlacement(Offset(120f, 240f), viewport, 10f))
        assertTrue(markerOverlaps(visible, visible, 12f))
        assertFalse(markerOverlaps(visible, Offset(44f, 20f), 12f))
    }

    @Test fun legendUsesTheRendererPaletteForEachThemeAndDistinguishesRawAndRejected() {
        for ((dark, style) in listOf(false to MapStyle.Day, true to MapStyle.Night)) {
            for (symbol in MapSymbol.entries) assertEquals(style.symbolColor(symbol), symbol.color(dark))
            assertNotEquals(MapSymbol.GPS.glyph, MapSymbol.REJECTED.glyph)
            assertNotEquals(MapSymbol.GPS.color(dark), MapSymbol.REJECTED.color(dark))
            assertEquals(style.truth, MapSymbol.TRUTH.color(dark))
        }
    }
}
