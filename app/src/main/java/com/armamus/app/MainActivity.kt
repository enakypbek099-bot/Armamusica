package com.armamus.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val audioExt = Regex(
        "\\.(mp3|flac|m4a|aac|wav|ogg|opus|wma|alac|ac3|eac3|ec3|ac4|mp4|aiff|aif|ape|wv|mka|dsf|dff)$",
        RegexOption.IGNORE_CASE
    )

    // Handles the file picker opened by <input type="file"> in index.html
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        ArmamusHost.folderMode = false
        val uris = WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
        // remember where the files are: the player can then copy / share them directly
        ArmamusFiles.registerPicked(contentResolver, uris)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    // System folder picker: every song inside the chosen folder (with subfolders) goes to the page as a file list
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val cb = filePathCallback
        filePathCallback = null
        ArmamusHost.folderMode = false
        if (cb != null) {
            if (tree == null) cb.onReceiveValue(null) else scanTree(tree, cb)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            askIgnoreBatteryOptimizations()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFullscreen()

        // Android 13+: needed to show the media notification
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askIgnoreBatteryOptimizations()
        }

        // The page (WebView) lives in ArmamusHost: if music was playing when the app was swiped away,
        // we get the same running page back here.
        web = ArmamusHost.obtain(this)
        setContentView(web)
        watchInsets()

        web.webChromeClient = object : WebChromeClient() {
            // window.open(...) used by the share buttons
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        ArmamusHost.openExternal(this@MainActivity, r.url)
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
                if (ArmamusHost.folderMode) {
                    ArmamusHost.folderMode = false
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
    }

    /**
     * Fixed immersive profile (always on, no setting):
     *  - the page is drawn edge to edge: under the status bar, the navigation bar and the display cutout
     *  - the status bar and the navigation bar stay visible (transparent) and never hide by themselves
     *  - display cutout: content goes into the cutout area (no margins)
     * Called from onCreate, onResume and onWindowFocusChanged so the state is restored every time.
     */
    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = lp
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
    }

    /** Tells the page how much room the system bars / cutout take, so buttons never hide under them. */
    private fun watchInsets() {
        val content = findViewById<android.view.View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeOn = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // keyboard: lift the whole page above it
            v.setPadding(0, 0, 0, if (imeOn) imeBottom else 0)
            val d = resources.displayMetrics.density
            val top = bars.top / d
            val bottom = if (imeOn) 0f else bars.bottom / d
            ArmamusHost.insetTop = top
            ArmamusHost.insetBottom = bottom
            web.evaluateJavascript("window.__amInsets&&window.__amInsets($top,$bottom)", null)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /** Asks once to exclude the app from battery saving, so Android does not stop the music in the background. */
    private fun askIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val prefs = getSharedPreferences("armamus", MODE_PRIVATE)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("battAsked", false)) {
                prefs.edit().putBoolean("battAsked", true).apply()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            // the phone has no such screen
        }
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
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val folders = ArrayList<String>()
        val cursor = contentResolver.query(children, cols, null, null, null)
        if (cursor != null) {
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    if (id == null) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(id)
                    } else if (mime.startsWith("audio/") || audioExt.containsMatchIn(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out.add(uri)
                        ArmamusFiles.put(name, size, uri)
                    }
                }
            }
        }
        for (f in folders) walkTree(tree, f, out, depth + 1)
    }

    override fun onStart() {
        super.onStart()
        ArmamusHost.visible = true
    }

    override fun onStop() {
        ArmamusHost.visible = false
        super.onStop()
    }

    override fun onDestroy() {
        // The page is NOT destroyed here when music plays: it keeps playing after the app is swiped away.
        ArmamusHost.onActivityDestroyed(this)
        super.onDestroy()
    }
}
