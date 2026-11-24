package lab.recovery.uimap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import lab.recovery.map.Point2D
import lab.recovery.map.RoadClass
import lab.recovery.map.RoadStructure
import org.junit.Assert.*
import org.junit.Test

class MapPresentationTest {
    @Test fun followOffsetAndOverviewKeepOneScaleAfterResize() {
        val fix = Point2D(160.0, 140.0)
        for ((width, height) in listOf(320.0 to 680.0, 540.0 to 1100.0)) {
            val follow = Projection(fix, width / 365.0, width, height).followOffset()
            assertEquals(width / 2, follow.screen(fix).x, 1e-9)
            assertEquals(height * .61, follow.screen(fix).y, 1e-9)
            assertEquals(365.0, follow.visibleMeters, 1e-9)
            val route = Bounds(-50.0, -120.0, 2400.0, 610.0)
            val overview = Projection(route.center, route.fit(width, height, 20.0), width, height)
            for (point in listOf(Point2D(route.minX, route.minY), Point2D(route.maxX, route.maxY))) {
                val screen = overview.screen(point)
                assertTrue(screen.x in 19.99..width - 19.99 && screen.y in 19.99..height - 19.99)
                assertEquals(point.x, overview.world(screen).x, 1e-9)
                assertEquals(point.y, overview.world(screen).y, 1e-9)
            }
        }
    }

    @Test fun detailAndRoadOrderingPreserveContextAndStructures() {
        assertEquals(Detail.DETAIL, mapDetail(CameraMode.FOLLOW, 365.0))
        assertEquals(Detail.MID, mapDetail(CameraMode.FREE, 650.0))
        assertEquals(Detail.OVERVIEW, mapDetail(CameraMode.FREE, 1500.0))
        assertEquals(Detail.OVERVIEW, mapDetail(CameraMode.OVERVIEW, 800.0))
        assertTrue(roadVisible(RoadClass.LOCAL, RoadStructure.SURFACE, Detail.OVERVIEW, false, 820.0))
        assertFalse(roadVisible(RoadClass.LOCAL, RoadStructure.SURFACE, Detail.OVERVIEW, false, 1700.0))
        assertTrue(roadVisible(RoadClass.LOCAL, RoadStructure.SURFACE, Detail.OVERVIEW, true, 1700.0))
        assertTrue(roadVisible(RoadClass.LOCAL, RoadStructure.BRIDGE, Detail.OVERVIEW, false, 1700.0))
        assertTrue(roadOrder(RoadClass.LOCAL) < roadOrder(RoadClass.ARTERIAL))
        assertTrue(roadOrder(RoadClass.ARTERIAL) < roadOrder(RoadClass.EXPRESSWAY))
    }

    @Test fun clippedUprightLabelsRespectChromePriorityAndDuplicates() {
        val viewport = Rect(0f, 0f, 300f, 250f)
        val chrome = Rect(200f, 170f, 300f, 250f)
        assertEquals(Offset(0f, 30f) to Offset(300f, 30f),
            clipLabelSegment(Offset(-50f, 30f), Offset(350f, 30f), viewport))
        assertNull(clipLabelSegment(Offset(-10f, 20f), Offset(-10f, 40f), viewport))
        assertEquals(0f, uprightAngle(Offset(-250f, 0f)), 1e-6f)

        fun label(text: String, x: Float, y: Float, rank: Int, angle: Float = 0f) =
            LabelCandidate(text, Offset(x, y), angle, 64f, 20f, rank, rank == 0)
        val tunnel = label("Tunnel", 155f, 120f, 0)
        val duplicate = label("Tunnel", 45f, 110f, 0)
        val road = label("Side road", 165f, 128f, 4)
        val overlapsChrome = label("Bridge", 210f, 165f, 0, angle = 45f)
        val cropped = label("Edge", 10f, 10f, 2)
        val park = label("Park", 130f, 45f, 2)
        assertEquals(listOf(tunnel, park),
            arrangeLabels(listOf(road, park, duplicate, overlapsChrome, cropped, tunnel), viewport, listOf(chrome), 5))
    }
    @Test fun spatialIndexMatchesBoundsScanAndRenderingOrder() {
        val shapes = listOf(
            Bounds(-450.0, -450.0, 450.0, 450.0),
            Bounds(-1.0, -1.0, 0.0, 0.0),
            Bounds(190.0, 191.0, 200.0, 201.0),
            Bounds(800.0, 800.0, 821.0, 820.0),
            Bounds(-9000.0, -9000.0, 9000.0, 9000.0),
        )
        val index = SpatialIndex(shapes) { it }
        for (area in listOf(Bounds(-40.0, -40.0, 250.0, 250.0),
            Bounds(-30000.0, -30000.0, 30000.0, 30000.0), Bounds(192.0, 192.0, 192.0, 192.0))) {
            assertEquals(shapes.filter { it.intersects(area) }, index.visible(area))
        }
    }

    @Test fun renderingSimplificationPreservesEndpointsAndProtectsSourceGeometry() {
        val original = listOf(Point2D(0.0, 0.0), Point2D(5.0, .25), Point2D(10.0, 0.0), Point2D(10.0, 10.0))
        assertEquals(listOf(original[0], original[2], original[3]), simplifyLine(original, 1.0))
        assertEquals(Point2D(5.0, .25), original[1])
        assertEquals(original, simplifyLine(original, .1))
    }
}
