package com.dsp220.pro

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initNewPipeExtractor()
        initMediaController()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // 1. Mendaftarkan jembatan JavaScript dan mengirimkan instance webView ke AndroidBridge
        webView.addJavascriptInterface(AndroidBridge(webView), "AndroidBridge")

        // Memuat file HTML kamu
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun initNewPipeExtractor() {
        try {
            // Inisialisasi dasar NewPipe Extractor
            NewPipe.init(Downloader.getInstance())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getFileName(uri: Uri): String {
        var result = "Lagu Lokal"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) result = cursor.getString(index)
            }
        }
        return result
    }
}

// 2. Kelas AndroidBridge yang aman (menggunakan Handler Main Looper agar WebView tidak macet)
class AndroidBridge(private val webView: WebView) {

    @JavascriptInterface
    fun searchYouTube(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()

                // Kirim hasil balik ke JavaScript di Main Thread
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript("onSearchSuccess('$query')", null)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage?.replace("'", "\\'") ?: "Pencarian gagal"
                // Kirim error balik ke JavaScript di Main Thread
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript("onSearchFailed('$errorMsg')", null)
                }
            }
        }
    }

    @JavascriptInterface
    fun extractYouTube(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val streamExtractor = ServiceList.YouTube.getStreamExtractor(url)
                streamExtractor.fetchPage()

                val audioUrl = streamExtractor.audioStreams.firstOrNull()?.url ?: ""

                // Kirim hasil ekstraksi audio balik ke JavaScript di Main Thread
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript("onExtractionSuccess('$audioUrl')", null)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage?.replace("'", "\\'") ?: "Ekstraksi gagal"
                // Kirim error ekstraksi balik ke JavaScript di Main Thread
                Handler(Looper.getMainLooper()).post {
                    webView.evaluateJavascript("onExtractionFailed('$errorMsg')", null)
                }
            }
        }
    }
}
