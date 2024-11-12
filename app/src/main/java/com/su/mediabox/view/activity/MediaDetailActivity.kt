package com.su.mediabox.view.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import com.su.mediabox.R
import com.su.mediabox.config.Api
import com.su.mediabox.databinding.ActivityMediaDetailBinding
import com.su.mediabox.pluginapi.action.DetailAction
import com.su.mediabox.pluginapi.action.PlayAction
import com.su.mediabox.util.*
import com.su.mediabox.util.Util.setTransparentStatusBar
import com.su.mediabox.view.dialog.ShareDialogFragment
import com.su.mediabox.util.coil.CoilUtil.loadGaussianBlurCover
import com.su.mediabox.viewmodel.MediaDetailViewModel
import com.su.mediabox.view.adapter.type.dynamicGrid
import com.su.mediabox.view.adapter.type.initTypeList
import com.su.mediabox.view.adapter.type.typeAdapter

class MediaDetailActivity : BasePluginActivity() {

    private val mBinding by viewBind(ActivityMediaDetailBinding::inflate)
    private val viewModel by viewModels<MediaDetailViewModel>()
    private var isClick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTransparentStatusBar(window, isDark = false)

        logD("获取VM", "@${viewModel}")

        consumeAction<DetailAction>()?.also {
            viewModel.partUrl = it.url
        }

        //详情数据列表
        mBinding.rvDetailActivityInfo.dynamicGrid().initTypeList { }

        //下拉刷新
        mBinding.srlDetailActivity.setOnRefreshListener {

            viewModel.getMediaDetailData()
        }

        mBinding.atbDetailActivityToolbar.run {
            setBackButtonClickListener { finish() }
            // 分享
            setButtonEnable(0, false)
            setButtonClickListener(0) {
                ShareDialogFragment()
                    .setShareContent(if (viewModel.partUrl.startsWith("http")) viewModel.partUrl else Api.MAIN_URL + viewModel.partUrl)
                    .show(supportFragmentManager, "share_dialog")
            }
            addButton(null)
            // 收藏
            setButtonEnable(1, false)
            setButtonClickListener(1) {
                isClick = true
                viewModel.switchFavState()
            }

            //收藏状态
            viewModel.isFavVideo.observe(this@MediaDetailActivity) {
                if (it) {
                    setButtonDrawable(1, R.drawable.ic_star_white_24_skin)
                    if (isClick)
                        getString(R.string.favorite_succeed).showToast()
                } else {
                    setButtonDrawable(1, R.drawable.ic_star_border_white_24)
                    if (isClick)
                        getString(R.string.remove_favorite_succeed).showToast()
                }
                isClick = false
            }

        }

        //详情数据
        viewModel.videoData.observe(this, Observer {
            mBinding.srlDetailActivity.isRefreshing = false
            mBinding.atbDetailActivityToolbar.apply {
                setButtonEnable(0, true)
                setButtonEnable(1, true)
            }

            if (viewModel.cover.isBlank()) return@Observer
            //高斯模糊封面背景
            mBinding.ivDetailActivityBackground.loadGaussianBlurCover(viewModel.cover, this)
            //标题
            mBinding.atbDetailActivityToolbar.titleText = viewModel.title
            //详情数据
            mBinding.rvDetailActivityInfo.typeAdapter().submitList(it.second)

            //嵌入当前视频名称
            mBinding.rvDetailActivityInfo.typeAdapter().setTag(viewModel.title)
        })

        mBinding.srlDetailActivity.isRefreshing = true
        viewModel.getMediaDetailData()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mBinding.rvDetailActivityInfo.typeAdapter().notifyDataSetChanged()
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        //主动向下一级路由目标提供一些信息
        intent?.apply {
            consumeAction<PlayAction>()?.apply {
                coverUrl = viewModel.cover
                detailPartUrl = viewModel.partUrl
                videoName = viewModel.title
                logD("当前播放动作", "vm(@${viewModel}) videoName=${viewModel.title}")
                logD("传递播放动作", formatMemberField(), false)
                putAction(this)
            }
        }
        super.startActivity(intent, options)
    }
}
