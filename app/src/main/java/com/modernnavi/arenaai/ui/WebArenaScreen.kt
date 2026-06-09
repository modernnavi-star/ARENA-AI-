package com.modernnavi.arenaai.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

private class ArenaAndroidBridge(
    private val context: Context,
    private val onSignOut: () -> Unit
) {
    @JavascriptInterface
    fun signOut() {
        onSignOut()
    }

    @JavascriptInterface
    fun saveFileBase64(fileName: String, mimeType: String, base64: String) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            writeToDownloads(fileName.safeFileName(), mimeType.ifBlank { "application/octet-stream" }, bytes)
            showToast("Saved $fileName to Downloads")
        }.onFailure {
            showToast("Save failed: ${it.localizedMessage ?: "unknown error"}")
        }
    }

    @JavascriptInterface
    fun savePdf(fileName: String, title: String, text: String) {
        runCatching {
            val pdf = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 18f
                isFakeBoldText = true
            }
            val pageWidth = 595
            val pageHeight = 842
            val margin = 36f
            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            var y = margin + 10f
            canvas.drawText(title.take(80), margin, y, titlePaint)
            y += 30f

            fun newPage() {
                pdf.finishPage(page)
                pageNumber += 1
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = margin
            }

            text.split('\n').forEach { rawLine ->
                val wrapped = rawLine.wrapForPdf(maxChars = 68).ifEmpty { listOf("") }
                wrapped.forEach { line ->
                    if (y > pageHeight - margin) newPage()
                    canvas.drawText(line, margin, y, paint)
                    y += 18f
                }
                y += 4f
            }
            pdf.finishPage(page)
            val output = java.io.ByteArrayOutputStream()
            pdf.writeTo(output)
            pdf.close()
            writeToDownloads(fileName.safeFileName().ensurePdfExtension(), "application/pdf", output.toByteArray())
            showToast("PDF saved to Downloads")
        }.onFailure {
            showToast("PDF failed: ${it.localizedMessage ?: "unknown error"}")
        }
    }

    private fun writeToDownloads(fileName: String, mimeType: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArenaAI")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create download file")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ArenaAI")
            dir.mkdirs()
            FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
        }
    }

    private fun showToast(message: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
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
                addJavascriptInterface(ArenaAndroidBridge(context, onSignOut), "ArenaAndroid")
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

private fun String.safeFileName(): String = replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "arena-file" }
private fun String.ensurePdfExtension(): String = if (lowercase().endsWith(".pdf")) this else "$this.pdf"
private fun String.wrapForPdf(maxChars: Int): List<String> {
    if (length <= maxChars) return listOf(this)
    val words = split(Regex("\\s+"))
    val lines = mutableListOf<String>()
    var current = ""
    words.forEach { word ->
        if ((current.length + word.length + 1) > maxChars) {
            if (current.isNotBlank()) lines += current
            current = word
        } else {
            current = if (current.isBlank()) word else "$current $word"
        }
    }
    if (current.isNotBlank()) lines += current
    return lines
}
