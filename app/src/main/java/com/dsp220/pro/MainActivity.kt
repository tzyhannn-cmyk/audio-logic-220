package com.dsp220.pro

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
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
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private val localAudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val fileName = getFileName(uri)
                webView.evaluateJavascript("javascript:onLocalFileSelected('${uri}', '$fileName');", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
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
        
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
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

    private fun initNewPipeExtractor() {
        try {
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val connection = URL(request.url()).openConnection() as HttpURLConnection
                    connection.requestMethod = request.httpMethod() ?: "GET"
                    request.headers().forEach { (k, vs) -> vs.forEach { v -> connection.addRequestProperty(k, v) } }
                    val body = try { connection.inputStream.bufferedReader().use { it.readText() } } 
                               catch (e: Exception) { connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
                    return Response(connection.responseCode, connection.responseMessage, connection.headerFields, body, request.url())
                }
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun pickLocalFile() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            localAudioLauncher.launch(intent)
        }

        @JavascriptInterface
        fun searchYouTube(query: String) {
            Thread {
                try {
                    val searchExtractor = ServiceList.YouTube.getSearchExtractor(query)
                    searchExtractor.fetchPage()
                    val items = searchExtractor.initialPage.items
                    val jsonArray = JSONArray()

                    val limit = if (items.size > 15) 15 else items.size
                    for (i in 0 until limit) {
                        val item = items[i]
                        if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                            val videoObj = JSONObject().apply {
                                put("title", item.name ?: "Unknown")
                                put("uploader", item.uploaderName ?: "Unknown")
                                put("thumbnail", item.thumbnails?.firstOrNull()?.url ?: "")
                                put("url", item.url ?: "")
                            }
                            jsonArray.put(videoObj)
                        }
                    }
                    val safeJson = jsonArray.toString().replace("'", "\\'")
                    runOnUiThread { webView.evaluateJavascript("javascript:onSearchSuccess('$safeJson');", null) }
                } catch (e: Exception) {
                    val err = e.toString().replace("'", "\\'")
                    runOnUiThread { webView.evaluateJavascript("javascript:onSearchFailed('$err');", null) }
                }
            }.start()
        }

        @JavascriptInterface
        fun extractYouTube(url: String) {
            Thread {
                try {
                    val extractor = ServiceList.YouTube.getStreamExtractor(url)
                    extractor.fetchPage()
                    
                    val json = JSONObject().apply {
                        put("title", extractor.name ?: "Unknown")
                        put("uploader", extractor.uploaderName ?: "Unknown")
                        put("thumbnail", extractor.thumbnails?.firstOrNull()?.url ?: "")
                        put("audioUrl", extractor.audioStreams?.firstOrNull()?.url ?: "")
                        put("videoUrl", extractor.videoStreams?.firstOrNull()?.url ?: "")
                    }.toString().replace("'", "\\'")

                    runOnUiThread { webView.evaluateJavascript("javascript:onExtractionSuccess('$json');", null) }
                } catch (e: Exception) {
                    val err = e.toString().replace("'", "\\'")
                    runOnUiThread { webView.evaluateJavascript("javascript:onExtractionFailed('$err');", null) }
                }
            }.start()
        }

        @JavascriptInterface
        fun playAudioNative(url: String, title: String, artist: String) {
            runOnUiThread {
                mediaController?.let { player ->
                    val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
                    val item = MediaItem.Builder().setUri(Uri.parse(url)).setMediaMetadata(metadata).build()
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                }
            }
        }

        @JavascriptInterface
        fun pauseAudioNative() {
            runOnUiThread { mediaController?.pause() }
        }
    }
}
