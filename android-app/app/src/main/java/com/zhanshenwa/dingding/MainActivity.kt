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
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var uploadDialog: AlertDialog? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(243, 128, 32)
        window.navigationBarColor = Color.rgb(255, 253, 249)

        webView = WebView(this)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.setSupportZoom(false)
        webView.settings.builtInZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                val chooserIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(chooserIntent, REQUEST_FILE_PICKER)
                return true
            }
        }
        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        })

        setContentView(webView)
        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> getSingleUri(intent)
            Intent.ACTION_SEND_MULTIPLE -> getMultipleUris(intent).firstOrNull()
            else -> null
        } ?: return

        if (intent.action == Intent.ACTION_SEND_MULTIPLE && getMultipleUris(intent).size > 1) {
            Toast.makeText(this, "1.0 版本先上传分享的第一个文件", Toast.LENGTH_LONG).show()
        }
        webView.postDelayed({ uploadSharedFile(uri) }, 450)
    }

    private fun uploadSharedFile(uri: Uri) {
        if (uploadDialog?.isShowing == true) return
        uploadDialog = AlertDialog.Builder(this)
            .setTitle("丁丁快传")
            .setMessage("正在上传文件…")
            .setCancelable(false)
            .create()
        uploadDialog?.show()

        executor.execute {
            try {
                val result = upload(uri)
                runOnUiThread { showUploadSuccess(result) }
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

    private fun showUploadSuccess(result: UploadResult) {
        uploadDialog?.dismiss()
        val text = TextView(this).apply {
            text = "文件：${result.fileName}\n\n取件码：${result.code}\n\n${result.link}"
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(text, ViewGroup.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("上传成功")
            .setView(scroll)
            .setPositiveButton("复制链接") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("丁丁快传链接", result.link))
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

    @Suppress("DEPRECATION")
    private fun getSingleUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun getMultipleUris(intent: Intent): ArrayList<Uri> {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: arrayListOf()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_PICKER) {
            val callback = fileChooserCallback ?: return
            fileChooserCallback = null
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
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
