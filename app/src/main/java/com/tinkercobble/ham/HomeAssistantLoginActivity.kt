package com.tinkercobble.ham

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** A separate, full-screen Home Assistant authorization page. No credentials pass through HAM UI. */
class HomeAssistantLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTHORIZE_URL = "authorize_url"
        const val EXTRA_REDIRECT_SCHEME = "redirect_scheme"
        const val EXTRA_CALLBACK_URL = "callback_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authorizeUrl = intent.getStringExtra(EXTRA_AUTHORIZE_URL)
        val redirectScheme = intent.getStringExtra(EXTRA_REDIRECT_SCHEME)
        if (authorizeUrl == null || !authorizeUrl.startsWith("http") || redirectScheme.isNullOrBlank()) {
            finish()
            return
        }
        val initialOrigin = android.net.Uri.parse(authorizeUrl)
        setContent {
            MaterialTheme {
                val webView = remember {
                    WebView(this).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.isForMainFrame && request.url.scheme == redirectScheme) {
                                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CALLBACK_URL, request.url.toString()))
                                    finish()
                                    return true
                                }
                                if (request.url.scheme == "http" &&
                                    (initialOrigin.scheme != "http" || request.url.host != initialOrigin.host || request.url.port != initialOrigin.port)) {
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(authorizeUrl)
                    }
                }
                DisposableEffect(webView) { onDispose {
                    webView.clearHistory()
                    webView.clearCache(true)
                    webView.destroy()
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                } }
                Column(Modifier.fillMaxSize()) {
                    Text("Home Assistant sign in", Modifier.padding(start = 16.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = ::finish, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
