package com.armamus.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: ArmamusMedia3Bridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // set by the page right before it opens the "folder" picker
    @Volatile
    private var folderMode = false

    private val audioExt = Regex(
        "\\.(mp3|flac|m4a|aac|wav|ogg|opus|wma|alac|ac3|eac3|ec3|ac4|mp4|aiff|aif|ape|wv|mka|dsf|dff)$",
        RegexOption.IGNORE_CASE
    )

    // Handles the file picker opened by <input type="file"> in index.html
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        filePathCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        )
        filePathCallback = null
    }

    // System folder picker: every song inside the chosen folder (with subfolders) goes to the page as a file list
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb != null) {
            if (tree == null) cb.onReceiveValue(null) else scanTree(tree, cb)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** window.ArmamusApp: small helpers that need the activity */
    inner class AppBridge {
        @JavascriptInterface
        fun armFolder() {
            folderMode = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Android 13+: needed to show the media notification
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        setContentView(web)

        // Keep the page's renderer at normal priority when the app is in the background,
        // otherwise Android slows it down and the page cannot handle track changes
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Serves app/src/main/assets/* over a secure https origin (needed for IndexedDB, wasm, blob:)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            // Links to other sites (WhatsApp, Telegram, ...) open in the phone's own apps/browser
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                openExternal(request.url)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // window.open(...) used by the share buttons
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url)
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                if (folderMode) {
                    folderMode = false
                    return try {
                        treePicker.launch(null)
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        callback.onReceiveValue(null)
                        true
                    }
                }
                return try {
                    chooser.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
        }

        bridge = ArmamusMedia3Bridge(this, web)
        web.addJavascriptInterface(bridge, "ArmamusNative")
        web.addJavascriptInterface(AppBridge(), "ArmamusApp")

        // Back gesture / button: first let the page close its top screen (lyrics, full player, sheets, pages);
        // only when nothing is open does the app go to the background (music keeps playing)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript(
                    "(function(){try{return window.__amBack?window.__amBack():false}catch(e){return false}})()"
                ) { result ->
                    if (result != "true") moveTaskToBack(true)
                }
            }
        })

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun scanTree(tree: Uri, cb: ValueCallback<Array<Uri>>) {
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // the temporary permission is enough for this session
        }
        Thread {
            val found = ArrayList<Uri>()
            try {
                walkTree(tree, DocumentsContract.getTreeDocumentId(tree), found, 0)
            } catch (e: Exception) {
                // return what was found so far
            }
            runOnUiThread {
                if (found.isEmpty()) {
                    cb.onReceiveValue(null)
                    web.evaluateJavascript(
                        "window.__amToast&&window.__amToast('Музыка в папке не найдена')", null
                    )
                } else {
                    cb.onReceiveValue(found.toTypedArray())
                }
            }
        }.start()
    }

    private fun walkTree(tree: Uri, docId: String, out: MutableList<Uri>, depth: Int) {
        if (depth > 15) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val folders = ArrayList<String>()
        val cursor = contentResolver.query(children, cols, null, null, null)
        if (cursor != null) {
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (id == null) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(id)
                    } else if (mime.startsWith("audio/") || audioExt.containsMatchIn(name)) {
                        out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            }
        }
        for (f in folders) walkTree(tree, f, out, depth + 1)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            // no app can handle this link
        }
    }

    override fun onDestroy() {
        // Dolby playback belongs to the foreground service; only the page link is cut here
        bridge.detach()
        web.destroy()
        super.onDestroy()
    }
}
