/*
 * Copyright (C) 2026 Ishan
 * Android Port component of mGBA.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3.
 */
package org.mgba_emu.mgba.input.ui

import kotlin.math.min

/**
 * Single source of truth for the black side panels reserved in Classic mode.
 * Consumed by both the OpenGL viewport and the touch renderer.
 */
object ClassicLayoutGeometry {
    fun panelWidth(width: Int, height: Int): Int {
        if (width <= height || height <= 0) return 0
        return min(width * 0.20f, height * 0.30f).toInt().coerceAtLeast(0)
    }
}
