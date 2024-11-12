package com.su.mediabox.view.activity

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.viewModels
import androidx.core.view.forEach
import androidx.core.view.isVisible
import com.microsoft.appcenter.analytics.Analytics
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.model.VideoOptionModel
import com.shuyu.gsyvideoplayer.player.IPlayerManager
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING
import com.su.mediabox.Pref
import com.su.mediabox.R
import com.su.mediabox.databinding.ActivityVideoMediaPlayBinding
import com.su.mediabox.pluginapi.action.PlayAction
import com.su.mediabox.pluginapi.data.EpisodeData
import com.su.mediabox.util.*
import com.su.mediabox.util.Util.setFullScreen
import com.su.mediabox.viewmodel.VideoMediaPlayViewModel
import com.su.mediabox.view.component.player.VideoMediaPlayer
import com.su.mediabox.view.component.player.VideoPositionMemoryDbStore
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.lang.Long.max
import java.lang.Long.min

class VideoMediaPlayActivity : BasePluginActivity(),
    VideoMediaPlayer.PlayOperatingProxy {

    companion object {
        private const val DEFAULT_SEEK_LENGTH = 15000L
        const val DEFAULT_VIDEO_PRELOAD_SIZE = 5
    }

    private val mBinding by viewBind(ActivityVideoMediaPlayBinding::inflate)

    private lateinit var orientationUtils: OrientationUtils
    private val viewModel by viewModels<VideoMediaPlayViewModel>()

    //记录上次前台是否在播放
    private var lastForegroundIsPlaying = false

    private lateinit var action: PlayAction

    override val currentPlayEpisodeUrl: String get() = viewModel.currentPlayEpisodeUrl
    override fun playVideoMedia(episodeUrl: String) = viewModel.playVideoMedia(episodeUrl)
    override suspend fun putDanmaku(danmaku: String, time: Long, color: Int, type: Int) =
        viewModel.putDanmaku(danmaku, time, color, type)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Analytics.trackEvent("功能：视频播放")

        setFullScreen(window)

        consumeAction<PlayAction>()?.also { action ->
            this.action = action
            init()
            viewModel.apply {
                val playList = action.extraData as? List<EpisodeData>
                playList?.let { init(it) }
                detailPartUrl = action.detailPartUrl
                coverUrl = action.coverUrl
                videoName = action.videoName
            }

            mBinding.vmErrorRetry.setOnClickListener { viewModel.playVideoMedia() }

            viewModel.apply {
                //视频
                currentVideoPlayMedia.observe(this@VideoMediaPlayActivity) { dataState ->
                    when (dataState) {
                        is DataState.Loading ->
                            mBinding.vmLoadingLayer.apply {
                                forEach {
                                    it.isVisible = it != mBinding.vmErrorRetry
                                }
                                visible()
                            }

                        is DataState.Success -> dataState.data?.also {
                            mBinding.vmPlay.playVideo(
                                it.videoPlayUrl,
                                it.title, viewModel.videoName
                            )
                            mBinding.vmLoadingLayer.gone()
                            mBinding.vmErrorRetry.gone()
                        }

                        is DataState.Failed -> {
                            dataState.throwable?.message?.showToast()
                            mBinding.vmLoadingLayer.apply {
                                forEach {
                                    it.isVisible = it == mBinding.vmErrorRetry
                                }
                                visible()
                            }
                        }

                        else -> Unit
                    }
                }
                //弹幕
                currentDanmakuData.observe(this@VideoMediaPlayActivity) {
                    it?.also {
                        Analytics.trackEvent("功能：装载弹幕")
                        mBinding.vmPlay.setDanmakuData(it)
                    }
                }
            }

            viewModel.playVideoMedia(action.episodeUrl)
        } ?: run {
            "播放信息错误".showToast()
            finish()
        }

    }

    override fun onBackPressed() {
        if (viewModel.currentVideoPlayMedia.value is DataState.Failed && mBinding.vmLoadingLayer.isVisible)
            when (mBinding.vmPlay.currentState) {
                //在已有正常播放时解析失败返回则只是关闭解析提示层
                CURRENT_STATE_PLAYING, GSYVideoView.CURRENT_STATE_PAUSE -> mBinding.vmLoadingLayer.gone()
                else -> super.onBackPressed()
            }
        else
            super.onBackPressed()
    }

    private fun init() {
        mBinding.vmPlay.run {
            playOperatingProxy = this@VideoMediaPlayActivity
            //进度记忆
            playPositionMemoryStore = VideoPositionMemoryDbStore
            //设置旋转
            orientationUtils = OrientationUtils(this@VideoMediaPlayActivity, this).apply {
                isRotateWithSystem = !action.autoRotate
                if (screenType != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    resolveByClick()
                setLockClickListener { _, lock ->
                    isRotateWithSystem = lock || !action.autoRotate
                }
            }

            ivDownloadButton?.gone()
            fullscreenButton.gone()
            //是否开启自动旋转
            isRotateViewAuto = action.autoRotate
            //是否需要全屏锁定屏幕功能
            isIfCurrentIsFullscreen = true
            isNeedLockFull = true
            //设置触摸显示控制ui的消失时间
            dismissControlTime = 5000
            //是否可以滑动调整
            setIsTouchWiget(true)
            setVideoAllCallBack(object : GSYSampleCallBack() {
                override fun onPrepared(url: String?, vararg objects: Any?) {
                    super.onPrepared(url, *objects)
                    this@run.currentPlayer.seekRatio = this@run.currentPlayer.duration / 90_000f
                    //设置弹幕
                    viewModel.initDanmakuData()
                }

                override fun onAutoComplete(url: String?, vararg objects: Any?) {
                    //自动切换下一集
                    playNextEpisode()
                }
            })

            val playManager: Class<IPlayerManager> =
                Util.withoutExceptionGet { action.playerManager as? Class<IPlayerManager> }
                    ?:
                    //自定义默认解码器
                    Class.forName(Pref.playDefaultCore.value) as Class<IPlayerManager>
            PlayerFactory.setPlayManager(playManager)

            //TODO 硬解码开关
            GSYVideoType.enableMediaCodec()
        }

        val videoOptionModel =
            VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)
        GSYVideoManager.instance().optionModelList = listOf(videoOptionModel)
    }

    /**
     * 空格/回车/P - 暂停/播放
     * Left - 后退
     * Right - 前进
     * Shift+Left - 减速0.5
     * Shift+Right - 加速0.5
     * M - 静音
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        mBinding.vmPlay.apply {
            when (keyCode) {
                //播放/暂停
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_ENTER,
                    //在部分机器，比如WSA空格和回车键被占用
                KeyEvent.KEYCODE_P,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> gsyVideoManager.apply {
                    if (isPlaying) {
                        onVideoPause()
                    } else {
                        onVideoResume()
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ->
                    //减速
                    if (event?.isShiftPressed == true) {
                        speed -= 0.5F
                    }
                    //后退
                    else seekTo(
                        max(
                            DEFAULT_SEEK_LENGTH,
                            currentPositionWhenPlaying - DEFAULT_SEEK_LENGTH
                        )
                    )

                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD ->
                    //加速
                    if (event?.isShiftPressed == true) {
                        speed += 0.5F
                    }
                    //前进
                    else seekTo(
                        min(
                            duration.toLong(),
                            currentPositionWhenPlaying + DEFAULT_SEEK_LENGTH
                        )
                    )
                //静音
                KeyEvent.KEYCODE_M ->
                    GSYVideoManager.instance().apply {
                        isNeedMute = !isNeedMute
                    }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        orientationUtils.setIsPause(true)
        mBinding.vmPlay.currentPlayer.apply {
            lastForegroundIsPlaying = currentState == CURRENT_STATE_PLAYING
            onVideoPause()
        }
    }

    override fun onResume() {
        super.onResume()
        logD("恢复播放器", "上次播放状态:$lastForegroundIsPlaying")
        if (lastForegroundIsPlaying) {
            orientationUtils.setIsPause(false)
            mBinding.vmPlay.currentPlayer.onVideoResume()
        }
    }

    override fun onDestroy() {
        runCatching {
            mBinding.vmPlay.currentPlayer.release()
            mBinding.vmPlay.setVideoAllCallBack(null)
            GSYVideoManager.releaseAllVideos()
            orientationUtils.releaseListener()
        }
        super.onDestroy()
    }
}
