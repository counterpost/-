package com.counterpost.workouttimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF0E1114.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/app.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    /** Called from app.html as window.Android. */
    inner class Bridge {
        @JavascriptInterface
        fun start(cfg: String) {
            TimerService.markStarting()
            val i = Intent(this@MainActivity, TimerService::class.java)
                .setAction(TimerService.ACTION_START)
                .putExtra(TimerService.EXTRA_CFG, cfg)
            startForegroundService(i)
        }

        @JavascriptInterface
        fun cmd(action: String) {
            if (!TimerService.isAlive) return
            startService(Intent(this@MainActivity, TimerService::class.java).setAction(action))
        }

        @JavascriptInterface
        fun state(): String = TimerService.stateJson
    }
}
