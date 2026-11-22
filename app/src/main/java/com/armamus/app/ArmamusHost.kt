package com.armamus.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Owns the WebView (the page with the whole player). It lives as long as the process lives, NOT as long as the screen:
 * when the app is swiped away from recents while music is playing, the screen is destroyed but the page keeps playing.
 */
object ArmamusHost {

    @SuppressLint("StaticFieldLeak")
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var bridge: ArmamusMedia3Bridge? = null

    /** the screen that is on right now (null when the app was swiped away but music still plays) */
    @Volatile
    var activity: Activity? = null

    /** the screen is visible: the page gets frequent time updates only then */
    @Volatile
    var visible = false

    /** height of the status bar / cutout and of the navigation bar in CSS pixels (the page is drawn under them) */
    @Volatile
    var insetTop = 0f

    @Volatile
    var insetBottom = 0f

    /** set by the page right before it opens the "folder" picker */
    @Volatile
    var folderMode = false

    private const val PAGE_URL = "https://appassets.androidplatform.net/assets/index.html"

    /** helpers for the page: window.ArmamusApp */
    class AppBridge(private val app: Context) {
        /** "top,bottom" in CSS pixels, the page reads it once at start */
        @JavascriptInterface
        fun insets(): String = "$insetTop,$insetBottom"

        @JavascriptInterface
        fun armFolder() {
            folderMode = true
        }

        /** real Android share for plain text */
        @JavascriptInterface
        fun shareText(title: String, text: String) {
            ArmamusShare.sendText(app, title, text)
        }

        /** real Android share for the audio file the person picked. false = this file is not known here (the page sends it itself) */
        @JavascriptInterface
        fun shareUri(name: String, size: Long, mime: String, title: String): Boolean {
            val uri = ArmamusFiles.find(name, size) ?: return false
            Thread { ArmamusShare.sendUri(app, uri, name, mime, title) }.start()
            return true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(act: Activity): WebView {
        activity = act
        val existing = web
        if (existing != null) {
            wrapper?.setBaseContext(act)
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }

        val app = act.applicationContext
        val wrap = MutableContextWrapper(act)
        val w = WebView(wrap)

        // Keep the page's renderer at normal priority when the app is in the background,
        // otherwise Android slows it down and the page cannot handle track changes
        if (Build.VERSION.SDK_INT >= 26) {
            w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        // Serves app/src/main/assets/* over a secure https origin (needed for IndexedDB, wasm, blob:)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            // Links to other sites (WhatsApp, Telegram, ...) open in the phone's own apps/browser
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                openExternal(app, request.url)
                return true
            }

            // The page process died (memory): build a new WebView instead of crashing the whole app
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone(view)
                return true
            }
        }

        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
        }

        val b = ArmamusMedia3Bridge(app, w)
        bridge = b
        w.addJavascriptInterface(b, "ArmamusNative")
        w.addJavascriptInterface(AppBridge(app), "ArmamusApp")

        web = w
        wrapper = wrap
        w.loadUrl(PAGE_URL)
        return w
    }

    /** the screen is going away */
    fun onActivityDestroyed(act: Activity) {
        if (activity === act) activity = null
        visible = false
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        wrapper?.setBaseContext(act.applicationContext)
        w.webChromeClient = null
        // music is playing (or the screen is only being rebuilt): keep the page alive
        val keep = act.isChangingConfigurations || ArmamusPlayer.isPlayingAny()
        if (!keep) destroy()
    }

    fun destroyIfDetached() {
        if (activity == null) destroy()
    }

    fun destroy() {
        val w = web ?: return
        web = null
        wrapper = null
        bridge?.detach()
        bridge = null
        (w.parent as? ViewGroup)?.removeView(w)
        try {
            w.destroy()
        } catch (e: Exception) {
            // already gone
        }
    }

    private fun onRendererGone(view: WebView) {
        val act = activity
        (view.parent as? ViewGroup)?.removeView(view)
        try {
            view.destroy()
        } catch (e: Exception) {
            // already gone
        }
        bridge?.detach()
        bridge = null
        web = null
        wrapper = null
        act?.recreate()
    }

    fun openExternal(ctx: Context, uri: Uri) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // no app can handle this link
        }
    }
}
