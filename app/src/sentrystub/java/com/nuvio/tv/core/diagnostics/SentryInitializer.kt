package com.nuvio.tv.core.diagnostics

import android.app.Application
import com.nuvio.tv.data.local.SentrySettingsDataStore

/** Lite ships without the Sentry SDK, so crash reporting never starts. */
object SentryInitializer {
    @Suppress("UNUSED_PARAMETER")
    fun start(application: Application, settings: SentrySettingsDataStore) = Unit
}
