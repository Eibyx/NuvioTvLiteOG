package com.nuvio.tv.ui.screens.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Runs Mobile's AniList OAuth request on the TV itself. AniList redirects to the client's
 * registered app scheme, which is intercepted here and never leaves the process.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AniListWebAuthDialog(url: String, onCallback: (String) -> Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val webView = remember(url) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                private fun intercept(target: String): Boolean {
                    if (target.startsWith("http://") || target.startsWith("https://")) return false
                    // Consume every non-web navigation; only the registered redirect is acted on.
                    onCallback(target)
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.isForMainFrame && intercept(request.url.toString())
                override fun onPageStarted(view: WebView, started: String, favicon: Bitmap?) {
                    if (intercept(started)) view.stopLoading() else super.onPageStarted(view, started, favicon)
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (canGoBack()) goBack() else onDismiss()
                    true
                } else keyCode == KeyEvent.KEYCODE_BACK
            }
            loadUrl(url)
            requestFocus()
        }
    }
    DisposableEffect(webView) {
        onDispose { webView.stopLoading(); webView.destroy() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
