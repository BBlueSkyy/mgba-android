package org.mgba_emu.mgba.input.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicLayoutGeometryTest {
    @Test fun landscapeSidebarsLeaveSpaceForTheGame() {
        for ((width, height) in listOf(1920 to 1080, 1866 to 800, 800 to 360)) {
            val panel = ClassicLayoutGeometry.panelWidth(width, height)
            assertTrue(panel > 0)
            assertTrue(panel * 2 < width)
            assertTrue(width - 2 * panel > 0)
        }
    }

    @Test fun portraitFallsBackToStratoLayout() {
        assertEquals(0, ClassicLayoutGeometry.panelWidth(1080, 1920))
        assertEquals(0, ClassicLayoutGeometry.panelWidth(800, 800))
    }
}
