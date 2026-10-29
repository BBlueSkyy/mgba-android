/*
 * Copyright (C) 2026 mGBA Android contributors.
 * Distributed under GNU GPL version 3.
 */
package org.mgba_emu.mgba.renderer.gl

import kotlin.math.ceil
import kotlin.math.max

/**
 * Aspect-preserving center crop. Negative viewport origins are legal in GLES2.
 * The returned rectangle fills the screen without stretching the image.
 */
object FullscreenViewportGeometry {
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    fun cover(contentWidth: Int, contentHeight: Int,
              surfaceWidth: Int, surfaceHeight: Int): Viewport {
        require(contentWidth > 0 && contentHeight > 0)
        require(surfaceWidth > 0 && surfaceHeight > 0)
        val scale = max(
            surfaceWidth.toDouble() / contentWidth,
            surfaceHeight.toDouble() / contentHeight
        )
        val w = ceil(contentWidth * scale).toInt().coerceAtLeast(surfaceWidth)
        val h = ceil(contentHeight * scale).toInt().coerceAtLeast(surfaceHeight)
        return Viewport((surfaceWidth - w) / 2, (surfaceHeight - h) / 2, w, h)
    }
}
