package com.su.mediabox.util.html.source.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.webkit.*
import com.su.mediabox.util.html.source.GettingCallback

@SuppressLint("SetJavaScriptEnabled", "ObsoleteSdkInt")
class GettingWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {
    private var gettingCallback: GettingCallback? = null

    private fun initWebSetting(context: Context) {
        clearFocus()
        val mWebSettings = settings
        mWebSettings.defaultTextEncodingName = "utf-8"
        mWebSettings.cacheMode = WebSettings.LOAD_DEFAULT
        mWebSettings.useWideViewPort = true
        mWebSettings.allowFileAccess = true
        mWebSettings.setSupportZoom(true)
        mWebSettings.allowContentAccess = true
        mWebSettings.javaScriptEnabled = true
        mWebSettings.domStorageEnabled = true
        mWebSettings.pluginState = WebSettings.PluginState.ON// 可以使用插件
        mWebSettings.setSupportMultipleWindows(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mWebSettings.mixedContentMode = 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            mWebSettings.mediaPlaybackRequiresUserGesture = true
        }
        if (Build.VERSION.SDK_INT >= 16) {
            mWebSettings.allowFileAccessFromFileURLs = true
            mWebSettings.allowUniversalAccessFromFileURLs = true
        }
        mWebSettings.javaScriptCanOpenWindowsAutomatically = true
        mWebSettings.loadsImagesAutomatically = false
        mWebSettings.databaseEnabled = true
        mWebSettings.setGeolocationDatabasePath(context.getDir("database", 0).path)
        mWebSettings.setGeolocationEnabled(true)
        val instance = CookieManager.getInstance()
        if (Build.VERSION.SDK_INT < 21) {
            CookieSyncManager.createInstance(context.applicationContext)
        }
        instance.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= 21) {
            instance.setAcceptThirdPartyCookies(this, true)
        }
    }

    fun setCallBack(callback: GettingCallback) {
        gettingCallback = callback
    }

    init {
        addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun htmlSource(html: String) {
                gettingCallback?.onGettingSuccess(this@GettingWebView, html)
            }
        }, "anime_html_source")
        initWebSetting(context)
    }
}