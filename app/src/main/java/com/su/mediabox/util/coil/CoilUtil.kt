package com.su.mediabox.util.coil

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.util.CoilUtils
import coil.util.DebugLogger
import com.su.mediabox.App
import com.su.mediabox.R
import com.su.mediabox.config.Api
import com.su.mediabox.config.Api.Companion.MAIN_URL
import com.su.mediabox.net.okhttpClient
import com.su.mediabox.util.Util.toEncodedUrl
import com.su.mediabox.util.debug
import com.su.mediabox.util.logE
import com.su.mediabox.pluginapi.Constant
import okhttp3.OkHttpClient
import java.net.URL

object CoilUtil {
    private val imageLoaderBuilder = ImageLoader.Builder(App.context)
        .crossfade(400)
        .apply { debug { logger(DebugLogger()) } }

    init {
        setOkHttpClient(okhttpClient)
    }

    fun setOkHttpClient(okHttpClient: OkHttpClient) {
        imageLoaderBuilder.okHttpClient(
            okHttpClient.newBuilder().cache(CoilUtils.createDefaultCache(App.context)).build()
        ).build().apply { Coil.setImageLoader(this) }
    }

    fun ImageView.loadImage(
        url: String,
        builder: ImageRequest.Builder.() -> Unit = {},
    ) {
        if (url.isEmpty()) {
            logE("loadImage", "cover image url must not be null or empty")
            return
        }

        this.load(url, builder = builder)
    }

    fun ImageView.loadImage(
        url: String,
        referer: String? = null,
        @DrawableRes placeholder: Int = 0,
        @DrawableRes error: Int = R.drawable.ic_warning_main_color_3_24_skin
    ) = runCatching {
        // 是本地drawable
        url.toIntOrNull()?.let { drawableResId ->
            load(drawableResId) {
                placeholder(placeholder)
                error(error)
            }
            return@runCatching
        }

        // 是网络图片

        if (tag == url)
            return@runCatching

        var amendReferer = referer ?: MAIN_URL
        if (!amendReferer.startsWith(MAIN_URL))
            amendReferer = MAIN_URL//"http://www.yhdm.io/"
        if (referer == MAIN_URL || referer == MAIN_URL) amendReferer += "/"

        loadImage(url) {
            placeholder(placeholder)
            error(error)
            addHeader("Referer", referer ?: Api.refererProcessor?.processor(url) ?: "")
            addHeader("Host", URL(url).host)
            addHeader("Accept", "*/*")
            addHeader("Accept-Encoding", "gzip, deflate")
            addHeader("Connection", "keep-alive")
            addHeader("User-Agent", Constant.Request.getRandomUserAgent())
            listener { _, _ ->
                tag = url
            }
        }
    }

    fun ImageView.loadGaussianBlurCover(
        url: String,
        context: Context,
        radius: Float = 25F,
        sampling: Float = 2F,
        dark: Float = 0.7F
    ) = loadImage(url) {
        transformations(DarkBlurTransformation(context, radius, sampling, dark))
    }


    fun clearMemoryDiskCache() {
        App.context.imageLoader.memoryCache.clear()
        CoilUtils.createDefaultCache(App.context).delete()
    }
}