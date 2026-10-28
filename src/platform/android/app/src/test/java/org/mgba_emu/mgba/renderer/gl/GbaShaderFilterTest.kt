package org.mgba_emu.mgba.renderer.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class GbaShaderFilterTest {
    @Test fun idsMatchTheGraphicsMenu() {
        val names = listOf(
            GbaShaderFilter.NONE,
            GbaShaderFilter.HQ2X,
            GbaShaderFilter.HQ4X_TWO_PASS,
            GbaShaderFilter.XBR2X,
            GbaShaderFilter.XBR4X,
            GbaShaderFilter.LCD3X
        )
        names.forEachIndexed { index, mode ->
            assertEquals(index, mode.id)
            assertEquals(mode, GbaShaderFilter.from(index))
        }
    }

    @Test fun unknownModeFallsBackToNone() {
        assertEquals(GbaShaderFilter.NONE, GbaShaderFilter.from(-1))
        assertEquals(GbaShaderFilter.NONE, GbaShaderFilter.from(999))
    }
}
