package com.zhanshenwa.dingding

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var loadingMessage: TextView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var nativeUploadFromPicker = false
    private var uploadDialog: AlertDialog? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(243, 128, 32)
        window.navigationBarColor = Color.rgb(255, 253, 249)

        val root = FrameLayout(this)
        webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        loadingOverlay = createLoadingOverlay()
        root.addView(loadingOverlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        configureWebView()
        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
        handleIncomingShare(intent)
    }

    private fun createLoadingOverlay(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(255, 253, 249))
            setPadding(40, 40, 40, 40)
        }
        val title = TextView(this).apply {
            text = "丁丁快传"
            textSize = 28f
            setTextColor(Color.rgb(243, 128, 32))
            gravity = Gravity.CENTER
        }
        loadingMessage = TextView(this).apply {
            text = "正在打开…"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 20)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        panel.addView(loadingMessage, LinearLayout.LayoutParams(-1, -2))
        panel.addView(progress, LinearLayout.LayoutParams(-2, -2))
        return panel
    }

    private fun configureWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                loadingMessage.text = "正在打开…"
                loadingOverlay.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                loadingOverlay.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    loadingMessage.text = "网络连接失败，请检查网络后重试"
                    loadingOverlay.visibility = View.VISIBLE
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                loadingMessage.text = "网络连接失败，请检查网络后重试"
                loadingOverlay.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                nativeUploadFromPicker = true
                val chooserIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(chooserIntent, REQUEST_FILE_PICKER)
                    true
                } catch (error: Exception) {
                    nativeUploadFromPicker = false
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(getSingleUri(intent))
            Intent.ACTION_SEND_MULTIPLE -> getMultipleUris(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        if (uris.size > 1) {
            Toast.makeText(this, "正在上传 ${uris.size} 个文件", Toast.LENGTH_SHORT).show()
        }
        webView.postDelayed({ uploadFiles(uris) }, 450)
    }

    private fun uploadFiles(uris: List<Uri>) {
        if (uris.isEmpty() || uploadDialog?.isShowing == true) return
        uploadDialog = AlertDialog.Builder(this)
            .setTitle("丁丁快传")
            .setMessage("正在上传 ${uris.size} 个文件…")
            .setCancelable(false)
            .create()
        uploadDialog?.show()

        executor.execute {
            try {
                val results = uris.map { upload(it) }
                runOnUiThread { showUploadSuccess(results) }
            } catch (error: Exception) {
                runOnUiThread {
                    uploadDialog?.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("上传失败")
                        .setMessage(error.message ?: "网络请求失败")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun upload(uri: Uri): UploadResult {
        val fileName = queryDisplayName(uri) ?: "shared-file"
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val boundary = "----Dingding-${UUID.randomUUID()}"
        val connection = (URL("$BASE_URL/api").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            useCaches = false
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                val safeFileName = fileName.replace("\"", "'")
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$safeFileName\"\r\n")
                output.writeBytes("Content-Type: $mimeType\r\n\r\n")
                contentResolver.openInputStream(uri)?.use { input ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                } ?: throw IOException("无法读取共享文件")
                output.writeBytes("\r\n--$boundary--\r\n")
                output.flush()
            }

            val status = connection.responseCode
            val responseStream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("服务器返回 HTTP $status")

            val link = Regex("\\\"link\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(responseBody)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: throw IOException("服务器返回内容无法识别")
            val code = Regex("\\\"code\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(responseBody)?.groupValues?.get(1).orEmpty()
            return UploadResult(fileName, link, code)
        } finally {
            connection.disconnect()
        }
    }

    private fun showUploadSuccess(results: List<UploadResult>) {
        uploadDialog?.dismiss()
        val message = results.joinToString("\n\n") {
            "文件：${it.fileName}\n取件码：${it.code}\n${it.link}"
        }
        val text = TextView(this).apply {
            text = message
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(text, ViewGroup.LayoutParams(-1, -2))
        }
        val firstLink = results.firstOrNull()?.link.orEmpty()
        AlertDialog.Builder(this)
            .setTitle("上传成功")
            .setView(scroll)
            .setPositiveButton("复制第一个链接") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("丁丁快传链接", firstLink))
                Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("打开网页") { _, _ -> webView.loadUrl(BASE_URL) }
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extractUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val result = ArrayList<Uri>()
        val clipData = data.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                result.add(clipData.getItemAt(index).uri)
            }
        } else {
            data.data?.let(result::add)
        }
        result.forEach { uri ->
            try {
                if (Build.VERSION.SDK_INT >= 19) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: Exception) {
                // Some providers grant temporary access only.
            }
        }
        return result.distinct()
    }

    @Suppress("DEPRECATION")
    private fun getSingleUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun getMultipleUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: arrayListOf()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE_PICKER) return

        val callback = fileChooserCallback
        fileChooserCallback = null
        val uris = if (resultCode == RESULT_OK) extractUris(data) else emptyList()
        if (nativeUploadFromPicker) {
            nativeUploadFromPicker = false
            callback?.onReceiveValue(null)
            if (uris.isNotEmpty()) uploadFiles(uris)
        } else {
            callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        uploadDialog?.dismiss()
        executor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    private data class UploadResult(
        val fileName: String,
        val link: String,
        val code: String
    )

    companion object {
        private const val BASE_URL = "https://dd.hxzz.uno"
        private const val REQUEST_FILE_PICKER = 1001
    }
}
