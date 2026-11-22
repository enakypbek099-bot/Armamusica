package com.armamus.app // <- change to your package name

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: ArmamusMedia3Bridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        bridge = ArmamusMedia3Bridge(this, web)
        web.addJavascriptInterface(bridge, "ArmamusNative")
        web.loadUrl("file:///android_asset/index.html") // index.html -> app/src/main/assets/
    }

    override fun onDestroy() {
        bridge.release()
        web.destroy()
        super.onDestroy()
    }
}
