package org.mgba_emu.mgba.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStateTest {
    @Test fun fastForwardDoesNotChangeGameKeys() {
        val state = InputState()
        state.press(GbaKey.A)
        state.setFastForwardHeld(true)
        assertTrue(state.isFastForwardHeld())
        assertEquals(GbaKey.A, state.current())
        state.setFastForwardHeld(false)
        assertFalse(state.isFastForwardHeld())
        assertEquals(GbaKey.A, state.current())
    }

    @Test fun fastForwardDefaultsOff() {
        val state = InputState()
        assertFalse(state.isFastForwardHeld())
        assertEquals(0, state.current())
    }
}
