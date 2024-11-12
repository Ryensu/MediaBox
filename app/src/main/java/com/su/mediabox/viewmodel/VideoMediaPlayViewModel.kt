package com.su.mediabox.viewmodel

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.get
import androidx.lifecycle.viewModelScope
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.su.mediabox.Pref
import com.su.mediabox.database.DatabaseOperations.insertHistoryData
import com.su.mediabox.database.DatabaseOperations.updateFavoriteData
import com.su.mediabox.pluginapi.data.VideoPlayMedia
import com.su.mediabox.pluginapi.components.IVideoPlayPageDataComponent
import com.su.mediabox.pluginapi.data.EpisodeData
import com.su.mediabox.util.*
import com.su.mediabox.view.activity.VideoMediaPlayActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.properties.Delegates

class VideoMediaPlayViewModel : ViewModel() {

    companion object {
        fun getCurrentPlayList(context: Context): List<EpisodeData>? {
            return if (context !is ComponentActivity)
                null
            else {
                ViewModelProvider(context).get<VideoMediaPlayViewModel>().playList
            }
        }
    }

    private val playComponent by lazyAcquireComponent<IVideoPlayPageDataComponent>()

    lateinit var detailPartUrl: String
    lateinit var coverUrl: String
    lateinit var videoName: String

    var currentPlayEpisodeUrl = ""
        private set

    var playList: List<EpisodeData>? = null
        private set

    private val _currentVideoPlayMedia = MutableLiveData<DataState<VideoPlayMedia>>()
    private val _currentDanmakuData = MutableLiveData<List<DanmakuItemData>?>()

    val currentVideoPlayMedia = _currentVideoPlayMedia.toLiveData()
    val currentDanmakuData = _currentDanmakuData.toLiveData()

    private val videoPlayMediaDispatcher =
        Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            _currentVideoPlayMedia.postValue(DataState.Failed(throwable))
        }

    private val episode2VideoInfoMap = mutableMapOf<String, VideoPlayMedia>()

    private var lastAutoJob: Job? = null

    fun init(playList: List<EpisodeData>) {
        this.playList = playList
    }

    fun playVideoMedia(episodeUrl: String = currentPlayEpisodeUrl) {
        if (episodeUrl.isNotBlank()) {
            _currentVideoPlayMedia.postValue(DataState.Loading)
            currentPlayEpisodeUrl = episodeUrl
            //开始解析
            viewModelScope.launch(videoPlayMediaDispatcher) {
                let {
                    var result = episode2VideoInfoMap[episodeUrl]
                    if (result == null) {
                        //一次动作在未完成工作下重复解析两次，降低因为网络问题解析失败概率
                        for (i in 0 until 2) {
                            result = playComponent.getVideoPlayMedia(episodeUrl)
                            if (result.videoPlayUrl.isNotBlank()) {
                                episode2VideoInfoMap[episodeUrl] = result
                                break
                            }
                        }
                    }
                    result ?: throw RuntimeException("无法解析出有效播放链接")
                }.also {
                    logD("视频解析结果", "剧集：${it.title} 链接：$${it.videoPlayUrl}")
                    if (it.videoPlayUrl.isBlank())
                        throw RuntimeException("无效播放链接")
                    //预解析
                    preloadVideo(episodeUrl)
                    // VideoPlayMedia("测试","file:///storage/emulated/0/Android/data/com.su.mediabox.debug/files/DownloadAnime/萌萌侵略者/GEfErSXSJIsA.mp4").also {
                    _currentVideoPlayMedia.postValue(DataState.Success(it))
                    //记录历史
                    viewModelScope.apply {
                        updateFavoriteData(detailPartUrl, episodeUrl, it.title)
                        insertHistoryData(detailPartUrl, episodeUrl, coverUrl, videoName, it.title)
                    }
                }
            }
        }
    }

    private fun preloadVideo(curEpisodeUrl: String) {
        if (!Pref.videoPreload.value)
            return
        playList?.let {
            logD("preloadVideo", "load start: cur=$curEpisodeUrl")
            lastAutoJob?.cancel()
            lastAutoJob = viewModelScope.launch(Dispatchers.IO) {
                //定位当前
                val startPre = it.indexOfFirst { it.url == curEpisodeUrl }
                if (startPre != -1) {
                    var endIndex = startPre + VideoMediaPlayActivity.DEFAULT_VIDEO_PRELOAD_SIZE
                    endIndex = min(endIndex, it.size - 1)
                    logD("preloadVideo", "start $startPre to $endIndex")
                    var realSize = 0
                    for (i in (startPre + 1)..endIndex) {
                        val episodeUrl = it[i].url
                        //TODO 因为内置的WebUtil内部WebView问题，暂时先串行解析
                        runCatching {
                            if (episode2VideoInfoMap[episodeUrl] == null) {
                                logD("preloadVideo", "load episodeUrl=$episodeUrl index=$i")
                                val result = playComponent.getVideoPlayMedia(episodeUrl)
                                if (result.videoPlayUrl.isNotBlank()) {
                                    logD(
                                        "preloadVideo",
                                        "load episodeUrl=$episodeUrl success result=$result"
                                    )
                                    episode2VideoInfoMap[episodeUrl] = result
                                    realSize++
                                }
                            }
                        }
                    }
                    logD("preloadVideo", "preload success: size=$realSize")
                }
            }
        }
    }

    suspend fun putDanmaku(danmaku: String, time: Long, color: Int, type: Int): Boolean {
        logD("发送弹幕", danmaku)
        return when (val dataState = currentVideoPlayMedia.value) {
            is DataState.Success -> {
                playComponent.putDanmaku(
                    videoName, dataState.data!!.title, currentPlayEpisodeUrl,
                    danmaku, time, color, type
                )
                true
            }

            else -> false
        }
    }

    fun initDanmakuData() {
        when (val dataState = currentVideoPlayMedia.value) {
            is DataState.Success -> {
                dataState.data?.apply {
                    logD("加载弹幕", "媒体:$videoName 集数:$title")
                    viewModelScope.launch(Dispatchers.PluginIO) {
                        playComponent.getDanmakuData(videoName, title, currentPlayEpisodeUrl)
                            ?.also {
                                logD("加载弹幕成功", "媒体:$videoName 集数:$title 数量:${it.size}")
                                _currentDanmakuData.postValue(it)
                            }
                    }
                }
            }

            else -> logD("加载弹幕失败", "当前无播放媒体")
        }
    }

    override fun onCleared() {
        playList = null
        super.onCleared()
    }
}