package lab.recovery.uimap

import lab.recovery.map.Point2D
import org.junit.Assert.*
import org.junit.Test

class CameraTest {
    @Test fun worldRoundTripAndNorthPointsUp() {
        val camera = Projection(Point2D(-30.0, 500.0), 2.5, 300.0, 510.0)
        val sample = Point2D(-42.0, 540.0)
        assertEquals(sample, camera.world(camera.screen(sample)))
        assertTrue(camera.screen(sample).y < 255)
    }

    @Test fun pinchKeepsWorldPointUnderMovingGestureAndClampsScale() {
        val initial = Projection(Point2D(0.0, 0.0), 2.0, 300.0, 500.0)
        val anchor = Point2D(48.0, 110.0)
        val shift = Point2D(20.0, -12.0)
        val adjusted = initial.manipulated(anchor, shift, 10.0, 0.2, 4.0)
        assertEquals(4.0, adjusted.scale, 0.0)
        assertEquals(Point2D(68.0, 98.0), adjusted.screen(initial.world(anchor)))
        val bounds = Bounds(-10.0, -20.0, 10.0, 20.0)
        assertTrue(bounds.fit(0.0, 0.0, 20.0) > 0)
        assertEquals(Point2D(10.0, -20.0), bounds.clamp(Point2D(100.0, -100.0)))
    }
}
