package org.mgba_emu.mgba.renderer.gl

/** GLES 2.0 fragment filters, independent of the basic nearest/bilinear sampler. */
enum class GbaShaderFilter(val id: Int) {
    NONE(0),
    HQ2X(1),
    HQ4X_TWO_PASS(2),
    XBR2X(3),
    XBR4X(4),
    LCD3X(5);

    companion object {
        fun from(value: Int): GbaShaderFilter = entries.firstOrNull { it.id == value } ?: NONE
    }
}
