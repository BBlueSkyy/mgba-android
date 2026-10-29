/*
 * Copyright (C) 2026 mGBA Android contributors.
 * Distributed under GNU GPL version 3.
 */
package org.mgba_emu.mgba.renderer.gl

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Aspect-preserving viewport calculation for either complete-image display
 * (fit) or optional center-cropped zoom (cover). Negative viewport origins
 * are legal in GLES2 for the optional cover mode.
 */
object FullscreenViewportGeometry {
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Fit the entire native framebuffer inside the display.
     * Never crops or stretches the game's 3:2 (GBA) or 10:9 (GB) image.
     */
    fun fit(contentWidth: Int, contentHeight: Int,
            surfaceWidth: Int, surfaceHeight: Int): Viewport {
        require(contentWidth > 0 && contentHeight > 0)
        require(surfaceWidth > 0 && surfaceHeight > 0)
        val scale = min(
            surfaceWidth.toDouble() / contentWidth,
            surfaceHeight.toDouble() / contentHeight
        )
        val w = (contentWidth * scale).toInt().coerceIn(1, surfaceWidth)
        val h = (contentHeight * scale).toInt().coerceIn(1, surfaceHeight)
        return Viewport((surfaceWidth - w) / 2, (surfaceHeight - h) / 2, w, h)
    }

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
