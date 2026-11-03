package com.nuvio.tv.data.local

import com.nuvio.tv.core.sync.shouldExcludePreferenceFromProfileSettingsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiScaleTest {
    @Test fun rangeIsSixtyToOneHundredTwentyWithDefaultUnchanged() {
        assertEquals(listOf(60, 70, 80, 90, 100, 110, 120), UiScale.OPTIONS_PERCENT)
        assertEquals(100, UiScale.DEFAULT_PERCENT)
    }

    @Test fun storedValuesOutsideTheOptionsFallBackToDefault() {
        assertEquals(60, UiScale.orDefault(60))
        assertEquals(120, UiScale.orDefault(120))
        assertEquals(100, UiScale.orDefault(null))
        assertEquals(100, UiScale.orDefault(150))
        assertEquals(100, UiScale.orDefault(55))
    }

    @Test fun interfaceZoomStaysOnTheDevice() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("theme_settings", UiScale.PREFERENCE_KEY))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("theme_settings", "selected_font"))
    }
}
