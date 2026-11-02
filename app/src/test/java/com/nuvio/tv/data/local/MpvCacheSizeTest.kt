package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvCacheSizeTest {
    @Test fun defaultsKeepTheFormerFixedCache() {
        assertEquals(64L * 1024 * 1024, MpvCacheSize.bytes(MpvCacheSize.DEFAULT_FORWARD_MB))
        assertEquals(64L * 1024 * 1024, MpvCacheSize.bytes(MpvCacheSize.DEFAULT_BACK_MB))
        assertEquals(PlayerSettings().mpvCacheForwardMb, MpvCacheSize.DEFAULT_FORWARD_MB)
        assertEquals(PlayerSettings().mpvCacheBackMb, MpvCacheSize.DEFAULT_BACK_MB)
    }

    @Test fun storedValuesOutsideTheOptionsFallBackToDefaults() {
        assertEquals(256, MpvCacheSize.forwardOrDefault(256))
        assertEquals(64, MpvCacheSize.forwardOrDefault(null))
        assertEquals(64, MpvCacheSize.forwardOrDefault(100_000))
        assertEquals(0, MpvCacheSize.backOrDefault(0))
        assertEquals(64, MpvCacheSize.backOrDefault(-1))
    }

    @Test fun largeBytesDoNotOverflow() {
        assertEquals(1024L * 1024 * 1024, MpvCacheSize.bytes(1024))
    }

    @Test fun minutesAtReferenceBitrate() {
        assertEquals(1.79, MpvCacheSize.minutesAt(64), 0.01)
        assertEquals(7.16, MpvCacheSize.minutesAt(256), 0.01)
    }

    @Test fun warnsAboveAQuarterOfDeviceRam() {
        assertFalse(MpvCacheSize.isHighForDevice(128, 2048))
        assertTrue(MpvCacheSize.isHighForDevice(1024 + 64, 2048))
        assertFalse(MpvCacheSize.isHighForDevice(4096, 0))
    }
}
