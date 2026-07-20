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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL

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

        webView.addJavascriptInterface(AndroidBridge(webView), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun initNewPipeExtractor() {
        try {
            NewPipe.init(AppDownloader())
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

// Downloader dengan Header Browser agar tidak diblokir YouTube
class AppDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        
        // Menambahkan User-Agent Chrome agar dikenali sebagai browser resmi
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        )

        request.headers().forEach { (key, values) ->
            values.forEach { value -> connection.addRequestProperty(key, value) }
        }

        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage ?: ""
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""

        return Response(responseCode, responseMessage, connection.headerFields, responseBody, request.url())
    }
}

class AndroidBridge(private val webView: WebView) {

    // Mengirim respon ke JavaScript dengan format JSON yang aman (bebas error string/karakter)
    private fun sendJsResponse(functionName: String, data: String) {
        Handler(Looper.getMainLooper()).post {
            val safeData = JSONObject.quote(data)
            val script = """
                (function() {
                    if (typeof $functionName === 'function') {
                        $functionName($safeData);
                    } else {
                        alert("$functionName: " + $safeData);
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        }
    }

    // Menampilkan Notifikasi Pop-up Toast di Layar HP jika terjadi masalah
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(webView.context, message, Toast.LENGTH_LONG).show()
        }
    }

    @JavascriptInterface
    fun searchYouTube(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()

                sendJsResponse("onSearchSuccess", query)
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Pencarian gagal / Masalah koneksi"
                showToast("Pencarian Gagal: $errorMsg")
                sendJsResponse("onSearchFailed", errorMsg)
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

                if (audioUrl.isNotEmpty()) {
                    sendJsResponse("onExtractionSuccess", audioUrl)
                } else {
                    val errorMsg = "Audio stream tidak ditemukan"
                    showToast(errorMsg)
                    sendJsResponse("onExtractionFailed", errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Ekstraksi gagal / Diblokir YouTube"
                showToast("Ekstraksi Gagal: $errorMsg")
                sendJsResponse("onExtractionFailed", errorMsg)
            }
        }
    }
}
