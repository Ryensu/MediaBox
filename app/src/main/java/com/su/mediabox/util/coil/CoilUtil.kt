package com.su.mediabox.util.coil

import android.content.Context
import android.util.Base64
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.*
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.ImageRequest
import coil.request.Options
import coil.util.DebugLogger
import com.su.mediabox.App
import com.su.mediabox.R
import com.su.mediabox.config.Api
import com.su.mediabox.config.Api.Companion.MAIN_URL
import com.su.mediabox.net.okhttpClient
import com.su.mediabox.pluginapi.Constant
import com.su.mediabox.util.*
import okhttp3.OkHttpClient
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.net.URL

object CoilUtil {
    private val imageLoaderBuilder = ImageLoader.Builder(App.context)
        .crossfade(400)
        .apply {
            debug { logger(DebugLogger()) }
        }

    init {
        setOkHttpClient(okhttpClient)
    }

    fun setOkHttpClient(okHttpClient: OkHttpClient) {
        imageLoaderBuilder.okHttpClient(
            okHttpClient.newBuilder().build()
        ).build().apply { Coil.setImageLoader(this) }
    }

    fun ImageView.loadImage(
        urlOrBase64: String,
        referer: String? = null,
        @DrawableRes placeholder: Int = 0,
        @DrawableRes error: Int = R.drawable.ic_warning_main_color_3_24_skin,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = runCatching {
        if (urlOrBase64.isBlank()) {
            logE("loadImage", "图片来源有误！")
            return@runCatching
        }
        logD("加载图片","url=$urlOrBase64")
        val time = System.currentTimeMillis()
        when {
            urlOrBase64.startsWith("data:image") -> {
                //base64
                if (tag == urlOrBase64.hashCode()) {
                    logD("忽略加载", "time=$time base64-hashCode=${urlOrBase64.hashCode()}", false)
                    return@runCatching
                }
                logD("开始加载图片", "time=$time base64-hashCode==${urlOrBase64.hashCode()}")
                load(Base64FetcherFactory.obtainBase64Image(urlOrBase64)) {
                    fetcherFactory(Base64FetcherFactory)
                    listener { _, _ ->
                        logD(
                            "图片加载完毕",
                            "time=$time base64-hashCode==${urlOrBase64.hashCode()}", false
                        )
                        tag = urlOrBase64.hashCode()
                    }
                }
            }
            urlOrBase64.startsWith("http") -> {
                //是网络图片
                if (tag == urlOrBase64) {
                    logD("忽略加载", "time=$time base64-hashCode=${urlOrBase64.hashCode()}")
                    return@runCatching
                }
                logD("开始加载图片", "time=$time url=$urlOrBase64", false)
                load(urlOrBase64) {
                    builder()
                    placeholder(placeholder)
                    error(error)
                    (referer
                        ?: Util.withoutExceptionGet(showErrMsg = false) { Api.refererProcessor }
                            ?.processor(urlOrBase64))
                        ?.let {
                            addHeader("Referer", it)
                        }
//                    addHeader("Host", URL(urlOrBase64).host)
//                    addHeader("Accept", "*/*")
//                    addHeader("Accept-Encoding", "gzip, deflate")
//                    addHeader("Connection", "keep-alive")
                    addHeader("User-Agent", Constant.Request.getRandomUserAgent())
                    listener { _, _ ->
                        logD("图片加载完毕", "time=$time url=$urlOrBase64", false)
                        tag = urlOrBase64
                    }
                }
            }
            else -> logD("loadImage", "time=$time 加载失败:$urlOrBase64")
        }
    }.onFailure {
        logD("图片加载错误", "source:$urlOrBase64 msg:${it.message}")
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

    object Base64FetcherFactory : Fetcher.Factory<Base64FetcherFactory.Base64Image> {

        private val base64ImagePool = mutableMapOf<Int, Base64Image>()

        fun obtainBase64Image(base64: String) =
            base64ImagePool.getOrInit(base64.hashCode()) {
                Base64Image(base64)
            }

        class Base64Image internal constructor(val base64: String)

        override fun create(
            data: Base64Image,
            options: Options,
            imageLoader: ImageLoader
        ) =
            Fetcher {
                val imageByteArray = Base64.decode(data.base64.split(",")[1], Base64.DEFAULT)
                SourceResult(
                    source = ImageSource(
                        ByteArrayInputStream(imageByteArray).source().buffer(),
                        App.context
                    ),
                    mimeType = "base64",
                    dataSource = DataSource.DISK
                )
            }
    }

    fun clearMemoryDiskCache() {
        App.context.imageLoader.memoryCache?.clear()
    }
}