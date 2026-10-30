/*
 * Copyright (C) 2026 Ishan
 * Android Port component of mGBA.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3.
 *
 * This program is distributed without any warranty. See the GNU General Public License for more details.
 */


package org.mgba_emu.mgba.input

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class InputState {
    private val mask = AtomicInteger(0)
    private val fastForwardHeld = AtomicBoolean(false)

    fun press(key: Int) {
        mask.updateAndGet { it or key }
    }

    fun release(key: Int) {
        mask.updateAndGet { it and key.inv() }
    }

    fun current(): Int = mask.get()

    // Not a GBA key: holding the on-screen speed button never reaches the ROM.
    fun setFastForwardHeld(held: Boolean) { fastForwardHeld.set(held) }
    fun isFastForwardHeld(): Boolean = fastForwardHeld.get()
}
