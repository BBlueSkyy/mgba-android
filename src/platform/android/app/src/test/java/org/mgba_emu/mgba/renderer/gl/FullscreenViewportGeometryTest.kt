package org.mgba_emu.mgba.renderer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenViewportGeometryTest {
    @Test fun landscapeCropsTopAndBottomWithoutStretching() {
        val v = FullscreenViewportGeometry.cover(240, 160, 1866, 800)
        assertEquals(1866, v.width)
        assertTrue(v.height > 800)
        assertEquals(0, v.x)
        assertTrue(v.y < 0)
        assertTrue(kotlin.math.abs(v.width.toDouble() / v.height - 1.5) < 0.002)
    }

    @Test fun portraitCropsSides() {
        val v = FullscreenViewportGeometry.cover(240, 160, 1080, 1920)
        assertEquals(1920, v.height)
        assertTrue(v.width > 1080)
        assertTrue(v.x < 0)
        assertEquals(0, v.y)
    }

    @Test fun equalAspectUsesWholeSurface() {
        assertEquals(FullscreenViewportGeometry.Viewport(0, 0, 960, 640),
            FullscreenViewportGeometry.cover(240, 160, 960, 640))
    }

    @Test fun supportsGbAspectAndRotation() {
        val v = FullscreenViewportGeometry.cover(160, 144, 1920, 1080)
        assertTrue(v.width >= 1920)
        assertTrue(v.height >= 1080)
    }
}
