package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent

/**
 * Number keys jump through the video: 0 is the start, 5 the middle, 9 is 90 %.
 * Adapted from Nuvio Arc (ysosrs123/NuvioTV-Fork, GPL-3.0), where it is the default job of the number keys.
 */
internal object PlayerNumberKeyJump {
    fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

    /** Position for a number key, or null when the key is not a number or the stream cannot be jumped through. */
    fun targetPositionMs(keyCode: Int, durationMs: Long, isLive: Boolean): Long? {
        val digit = digitOf(keyCode) ?: return null
        if (isLive || durationMs <= 0L) return null
        return durationMs * digit / 10
    }
}
