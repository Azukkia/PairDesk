package io.github.azukkia.pairdesk.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewGeometryTest {
    private val eps = 1e-3

    private fun assertPoint(expected: Pair<Double, Double>, actual: RemotePoint?) {
        requireNotNull(actual) { "expected a point" }
        assertEquals(expected.first, actual.x, eps, "x")
        assertEquals(expected.second, actual.y, eps, "y")
    }

    @Test
    fun `portrait phone letterboxes a landscape screen above and below`() {
        val g = ViewGeometry(1080f, 2400f, 1920, 1080)
        val p = g.placement(ViewZoom.FIT)
        assertEquals(0f, p.left)
        assertEquals(1080f, p.width)
        assertEquals(607.5f, p.height)
        assertEquals(896.25f, p.top)
        assertPoint(0.5 to 0.5, g.toRemote(ViewZoom.FIT, 540f, 1200f))
        assertPoint(0.0 to 0.0, g.toRemote(ViewZoom.FIT, 0f, 896.25f))
        assertPoint(1.0 to 1.0, g.toRemote(ViewZoom.FIT, 1080f, 1503.75f))
        // In the black bars: nothing, unless clamped (a drag leaving the picture).
        assertNull(g.toRemote(ViewZoom.FIT, 540f, 100f))
        assertPoint(0.5 to 0.0, g.toRemote(ViewZoom.FIT, 540f, 100f, clamp = true))
        assertPoint(0.5 to 1.0, g.toRemote(ViewZoom.FIT, 540f, 2300f, clamp = true))
    }

    @Test
    fun `landscape phone letterboxes a 16-9 screen left and right`() {
        val g = ViewGeometry(2400f, 1080f, 1920, 1080)
        val p = g.placement(ViewZoom.FIT)
        assertEquals(240f, p.left)
        assertEquals(0f, p.top)
        assertEquals(1920f, p.width)
        assertPoint(0.5 to 0.5, g.toRemote(ViewZoom.FIT, 1200f, 540f))
        assertPoint(0.25 to 0.75, g.toRemote(ViewZoom.FIT, 240f + 480f, 810f))
        assertNull(g.toRemote(ViewZoom.FIT, 100f, 540f))
        val v = g.toView(ViewZoom.FIT, 0.25, 0.75)
        assertEquals(720f, v.x, 1e-3f)
        assertEquals(810f, v.y, 1e-3f)
    }

    @Test
    fun `coordinates are rounded to 4 decimals like viewer js`() {
        val g = ViewGeometry(3000f, 3000f, 3000, 3000)
        // 1000 / 3000 = 0.33333… → 0.3333 ; 2000 / 3000 → 0.6667
        assertPoint(0.3333 to 0.6667, g.toRemote(ViewZoom.FIT, 1000f, 2000f))
        assertEquals(0.3333, g.toRemote(ViewZoom.FIT, 1000f, 2000f)!!.x)
        assertEquals(0.6667, g.toRemote(ViewZoom.FIT, 1000f, 2000f)!!.y)
        assertEquals(0.1235, RemotePoint.round4(0.12346))
        assertEquals(1.0, RemotePoint.round4(0.99996))
    }

    @Test
    fun `zooming keeps the point under the fingers and maps touches through the zoom`() {
        val g = ViewGeometry(2400f, 1080f, 1920, 1080)
        val z = g.zoomAround(ViewZoom.FIT, 1200f, 540f, 2f)
        assertEquals(2f, z.zoom)
        assertTrue(z.isZoomed)
        val p = g.placement(z)
        assertEquals(-720f, p.left, 1e-3f)
        assertEquals(-540f, p.top, 1e-3f)
        assertEquals(3840f, p.width, 1e-3f)
        assertPoint(0.5 to 0.5, g.toRemote(z, 1200f, 540f))
        assertPoint(0.1875 to 0.25, g.toRemote(z, 0f, 0f))
        // Zoom is limited to 1x–5x.
        assertEquals(ViewZoom.MAX, g.zoomAround(z, 1200f, 540f, 50f).zoom)
        assertEquals(ViewZoom.MIN, g.zoomAround(z, 1200f, 540f, 0.1f).zoom)
        assertFalse(g.zoomAround(z, 1200f, 540f, 0.1f).isZoomed)
    }

    @Test
    fun `pinch anchors the picture point that was under the fingers`() {
        val g = ViewGeometry(2400f, 1080f, 1920, 1080)
        val start = g.zoomAround(ViewZoom.FIT, 1200f, 540f, 2f)
        val (ax, ay) = g.toNormalized(start, 1000f, 500f)!!
        // The fingers spread (zoom 3) and moved 50 px to the right.
        val z = g.anchored(start.zoom * 1.5f, ax, ay, 1050f, 500f)
        assertEquals(3f, z.zoom)
        val (bx, by) = g.toNormalized(z, 1050f, 500f)!!
        assertEquals(ax, bx, 1e-4)
        assertEquals(ay, by, 1e-4)
    }

    @Test
    fun `panning never shows past the edges of the picture`() {
        val g = ViewGeometry(2400f, 1080f, 1920, 1080)
        val z = g.zoomAround(ViewZoom.FIT, 1200f, 540f, 2f)
        val left = g.panBy(z, 100_000f, 100_000f)
        assertPoint(0.0 to 0.0, g.toRemote(left, 0f, 0f))
        val right = g.panBy(z, -100_000f, -100_000f)
        assertPoint(1.0 to 1.0, g.toRemote(right, 2400f, 1080f))
        // Not zoomed: the picture stays centered whatever the finger does.
        assertEquals(g.placement(ViewZoom.FIT), g.placement(g.panBy(ViewZoom.FIT, 300f, 300f)))
    }

    @Test
    fun `ensureVisible pans just enough to keep the cursor inside the margin`() {
        val g = ViewGeometry(2400f, 1080f, 1920, 1080)
        val z = g.zoomAround(ViewZoom.FIT, 1200f, 540f, 2f)
        val moved = g.ensureVisible(z, 0.95, 0.5, 48f)
        assertEquals(2352f, g.toView(moved, 0.95, 0.5).x, 1e-2f)
        // Already visible: unchanged.
        assertEquals(z, g.ensureVisible(z, 0.5, 0.5, 48f))
        // Not zoomed: nothing to do.
        assertEquals(ViewZoom.FIT, g.ensureVisible(ViewZoom.FIT, 0.99, 0.99, 48f))
    }

    @Test
    fun `zoom state survives a rotation`() {
        val landscape = ViewGeometry(2400f, 1080f, 1920, 1080)
        val z = landscape.normalize(landscape.zoomAround(ViewZoom.FIT, 1800f, 540f, 3f))
        val portrait = ViewGeometry(1080f, 2400f, 1920, 1080)
        val pz = portrait.normalize(z)
        assertEquals(3f, pz.zoom)
        assertEquals(z.centerX, pz.centerX, 1e-4f)
        // The zoomed picture (1822 px high) is smaller than the portrait view: centered.
        assertEquals(0.5f, pz.centerY, 1e-4f)
    }

    @Test
    fun `nothing is mapped before the first frame`() {
        val g = ViewGeometry(1080f, 2400f, 0, 0)
        assertFalse(g.isReady)
        assertNull(g.toRemote(ViewZoom.FIT, 10f, 10f, clamp = true))
        assertEquals(ViewZoom.FIT, g.normalize(ViewZoom(3f, 0.2f, 0.2f)))
    }
}
