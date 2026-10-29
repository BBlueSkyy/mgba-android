package org.mgba_emu.mgba.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveStatePixelsTest {
    @Test fun convertsLittleEndianRgbaToAndroidArgb() {
        // RGBA byte sequence 0x22 red, 0x33 green, 0x44 blue, 0xFF alpha
        assertEquals(0xFF223344.toInt(), SaveStateStore.rgbaToArgb(0xFF443322.toInt()))
    }

    @Test fun forcesOpaqueThumbnailPixels() {
        assertEquals(0xFF000000.toInt(), SaveStateStore.rgbaToArgb(0))
    }

    @Test fun preservesWhite() {
        assertEquals(-1, SaveStateStore.rgbaToArgb(-1))
    }
}
