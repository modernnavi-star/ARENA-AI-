package com.modernnavi.arenaai.ui

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.modernnavi.arenaai.data.ArenaUiState
import java.net.URLEncoder

private class ArenaAndroidBridge(private val onSignOut: () -> Unit) {
    @JavascriptInterface
    fun signOut() {
        onSignOut()
    }
}

@Composable
fun WebArenaScreen(
    state: ArenaUiState,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }
    val arenaUrl = remember(state.currentUser?.email, state.currentUser?.displayName) {
        val email = URLEncoder.encode(state.currentUser?.email.orEmpty(), "UTF-8")
        val name = URLEncoder.encode(state.currentUser?.displayName.orEmpty(), "UTF-8")
        "file:///android_asset/arena/index.html?email=$email&name=$name"
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        fileCallback?.onReceiveValue(emptyArray())
                        fileCallback = filePathCallback
                        fileLauncher.launch("*/*")
                        return true
                    }
                }
                addJavascriptInterface(ArenaAndroidBridge(onSignOut), "ArenaAndroid")
                loadUrl(arenaUrl)
            }
        },
        update = { webView ->
            if (webView.url == null || webView.url?.startsWith("file:///android_asset/arena/index.html") != true) {
                webView.loadUrl(arenaUrl)
            }
        }
    )
}
