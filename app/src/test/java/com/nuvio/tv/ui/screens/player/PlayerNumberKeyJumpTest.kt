package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerNumberKeyJumpTest {
    @Test fun numberKeysJumpToTenthsOfTheVideo() {
        assertEquals(0L, PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_0, 1_000_000L, isLive = false))
        assertEquals(500_000L, PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_5, 1_000_000L, isLive = false))
        assertEquals(900_000L, PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_NUMPAD_9, 1_000_000L, isLive = false))
    }

    @Test fun otherKeysLiveStreamsAndUnknownDurationsAreLeftAlone() {
        assertNull(PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_DPAD_LEFT, 1_000_000L, isLive = false))
        assertNull(PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_3, 1_000_000L, isLive = true))
        assertNull(PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_3, 0L, isLive = false))
    }

    @Test fun longDurationsDoNotOverflow() {
        assertEquals(36_000_000L * 7 / 10, PlayerNumberKeyJump.targetPositionMs(KeyEvent.KEYCODE_7, 36_000_000L, isLive = false))
    }
}
