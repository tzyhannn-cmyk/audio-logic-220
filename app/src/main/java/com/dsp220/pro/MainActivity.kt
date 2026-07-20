package com.dsp220.pro

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfoItem
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

        WebView.setWebContentsDebuggingEnabled(true)

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
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("WebViewJS", "${consoleMessage.message()} -- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(webView, ::getMediaController), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun getMediaController(): MediaController? = mediaController

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
}

class AppDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        connection.instanceFollowRedirects = true
        
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

        val encoding = connection.contentEncoding
        val inputStream = if ("gzip".equals(encoding, ignoreCase = true) && stream != null) {
            java.util.zip.GZIPInputStream(stream)
        } else {
            stream
        }

        val responseBody = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

        return Response(responseCode, responseMessage, connection.headerFields, responseBody, request.url())
    }
}

class AndroidBridge(
    private val webView: WebView,
    private val getController: () -> MediaController?
) {

    private fun sendJsResponse(functionName: String, data: String) {
        Handler(Looper.getMainLooper()).post {
            val trimmedData = data.trim()
            val isJsonFormat = trimmedData.startsWith("{") || trimmedData.startsWith("[")
            val safeData = if (isJsonFormat) trimmedData else JSONObject.quote(data)

            val script = """
                (function() {
                    try {
                        if (typeof window['$functionName'] === 'function') {
                            window['$functionName']($safeData);
                        } else {
                            console.warn("Fungsi JS '$functionName' tidak ditemukan!");
                        }
                    } catch (err) {
                        console.error("Error eksekusi JS $functionName:", err);
                    }
                })();
            """.trimIndent()
            
            webView.evaluateJavascript(script, null)
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(webView.context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanYouTubeUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        return when {
            url.contains("youtu.be/") -> {
                val id = url.substringAfter("youtu.be/").substringBefore("?").substringBefore("&").trim()
                "https://www.youtube.com/watch?v=$id"
            }
            url.contains("watch?v=") -> {
                val id = url.substringAfter("watch?v=").substringBefore("&").trim()
                "https://www.youtube.com/watch?v=$id"
            }
            else -> url
        }
    }

    @JavascriptInterface
    fun searchYouTube(query: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                searchExtractor.fetchPage()

                val jsonArray = JSONArray()
                val items = searchExtractor.initialPage.items
                
                for (item in items) {
                    if (item is StreamInfoItem) {
                        val obj = JSONObject()
                        obj.put("title", item.name)
                        obj.put("url", item.url)
                        obj.put("uploader", item.uploaderName)
                        obj.put("duration", item.duration)
                        obj.put("thumbnail", item.thumbnails?.firstOrNull()?.url ?: "")
                        jsonArray.put(obj)
                    }
                }

                sendJsResponse("onSearchSuccess", jsonArray.toString())

            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Pencarian gagal / Masalah koneksi"
                showToast("Pencarian Gagal: $errorMsg")
                sendJsResponse("onSearchFailed", errorMsg)
            }
        }
    }

    @JavascriptInterface
    fun extractYouTube(rawUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cleanUrl = cleanYouTubeUrl(rawUrl)
                
                val streamExtractor = ServiceList.YouTube.getStreamExtractor(cleanUrl)
                streamExtractor.fetchPage()

                val audioStream = streamExtractor.audioStreams
                    .sortedByDescending { it.averageBitrate }
                    .firstOrNull()

                val audioUrl = audioStream?.url ?: ""

                if (audioUrl.isNotEmpty()) {
                    // Mengirim Objek JSON Lengkap (Audio URL, Judul, Uploader, Thumbnail)
                    val jsonResult = JSONObject().apply {
                        put("audioUrl", audioUrl)
                        put("title", streamExtractor.name ?: "YouTube Audio")
                        put("uploader", streamExtractor.uploaderName ?: "GoTube")
                        put("thumbnail", streamExtractor.thumbnails?.firstOrNull()?.url ?: "")
                    }
                    sendJsResponse("onExtractionSuccess", jsonResult.toString())
                } else {
                    val errorMsg = "Audio stream tidak ditemukan dari link tersebut"
                    showToast(errorMsg)
                    sendJsResponse("onExtractionFailed", errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Ekstraksi gagal. Pastikan link YouTube valid."
                showToast("Ekstraksi Gagal: $errorMsg")
                sendJsResponse("onExtractionFailed", errorMsg)
            }
        }
    }

    @JavascriptInterface
    fun playAudioNative(url: String, title: String, artist: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val controller = getController()
                if (controller != null) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(artist)
                                .build()
                        )
                        .build()
                    controller.setMediaItem(mediaItem)
                    controller.prepare()
                    controller.play()
                }
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Gagal memutar audio native: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun pauseAudioNative() {
        Handler(Looper.getMainLooper()).post {
            try {
                getController()?.pause()
            } catch (e: Exception) {
                Log.e("AndroidBridge", "Gagal pause audio native: ${e.message}")
            }
        }
    }
}
