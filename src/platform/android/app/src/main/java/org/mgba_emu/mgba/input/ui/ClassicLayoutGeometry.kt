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
 * Shared geometry and background palette for the Classic side panels.
 * Consumed by both the OpenGL viewport and the touch renderer.
 */
object ClassicLayoutGeometry {
    // Color.rgb(75, 34, 140) used by the opaque Classic shell.
    val panelColorArgb = 0xFF4B228C.toInt()

    /** Letterbox/pillarbox regions match the shell only in Classic landscape. */
    fun backgroundColorArgb(classicMode: Boolean, width: Int, height: Int): Int =
        if (classicMode && width > height && height > 0) panelColorArgb
        else 0xFF000000.toInt()

    fun panelWidth(width: Int, height: Int): Int {
        if (width <= height || height <= 0) return 0
        return min(width * 0.20f, height * 0.30f).toInt().coerceAtLeast(0)
    }
}
