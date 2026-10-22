package com.nuvio.tv.core.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

/** Lite ships without the Sentry SDK, so requests pass through untouched. */
class SentryNetworkBreadcrumbInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
