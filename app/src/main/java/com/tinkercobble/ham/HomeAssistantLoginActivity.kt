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
        const val EXTRA_REDIRECT_URI = "redirect_uri"
        const val EXTRA_CALLBACK_URL = "callback_url"
        const val EXTRA_AUTH_ERROR = "auth_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authorizeUrl = intent.getStringExtra(EXTRA_AUTHORIZE_URL) ?: run { finish(); return }
        val initialOrigin = android.net.Uri.parse(authorizeUrl)
        val redirect = intent.getStringExtra(EXTRA_REDIRECT_URI)?.let(android.net.Uri::parse)
            ?: run { finish(); return }
        if (initialOrigin.scheme !in listOf("http", "https") || initialOrigin.host.isNullOrBlank() ||
            redirect.scheme.isNullOrBlank() || redirect.authority.isNullOrBlank() ||
            redirect.path.orEmpty().isNotEmpty()) {
            finish()
            return
        }
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
                                if (request.isForMainFrame && request.url.scheme == redirect.scheme) {
                                    if (request.url.authority == redirect.authority &&
                                        request.url.path == redirect.path && request.url.fragment == null) {
                                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CALLBACK_URL, request.url.toString()))
                                    } else {
                                        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_AUTH_ERROR,
                                            "Home Assistant returned to an unexpected address"))
                                    }
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
