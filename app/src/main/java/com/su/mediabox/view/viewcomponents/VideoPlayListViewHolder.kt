package com.su.mediabox.view.viewcomponents

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.su.mediabox.R
import com.su.mediabox.bean.MediaHistory
import com.su.mediabox.config.Const
import com.su.mediabox.databinding.ItemAnimeEpisode2Binding
import com.su.mediabox.databinding.ItemHorizontalRecyclerView1Binding
import com.su.mediabox.pluginapi.data.EpisodeData
import com.su.mediabox.pluginapi.data.EpisodeListData
import com.su.mediabox.pluginapi.util.UIUtil.dp
import com.su.mediabox.pluginapi.action.PlayAction
import com.su.mediabox.util.*
import com.su.mediabox.util.Util.getResColor
import com.su.mediabox.view.activity.VideoMediaPlayActivity
import com.su.mediabox.view.adapter.type.*
import com.su.mediabox.view.episodeSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * 播放列表视图组件
 */
class VideoPlayListViewHolder private constructor(private val binding: ItemHorizontalRecyclerView1Binding) :
    TypeViewHolder<EpisodeListData>(binding.root) {

    var episodeDataList: List<EpisodeData>? = null
    private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) { itemView.viewLifeCycleCoroutineScope }
    private var lastEpisodeIndex: Int? = null

    //TODO 接入设置
    private val isShowHistory = true

    constructor(parent: ViewGroup) : this(
        ItemHorizontalRecyclerView1Binding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
    ) {
        binding.rvHorizontalRecyclerView1
            .linear(RecyclerView.HORIZONTAL)
            .initTypeList { }

        binding.ivHorizontalRecyclerView1More.setOnClickListener {
            episodeDataList?.also {
                bindingTypeAdapter.getTag<String>()?.also { name ->
                    episodeSheetDialog(bindingContext, name, it)?.show()
                }
            }
        }
    }

    override fun onBind(data: EpisodeListData) {
        super.onBind(data)
        coroutineScope.launch(Dispatchers.Default) {
            var list = data.playList
            runCatching {
                //尝试自动排序为集数顺序
                list = getCorrectEpisodeList(list)
            }
            episodeDataList = list

            withContext(Dispatchers.Main) {
                binding.rvHorizontalRecyclerView1.typeAdapter().apply {
                    setTag(list, Const.ViewComponent.EPISODE_LIST_TAG)
                    //自动定位
                    if (isShowHistory)
                        bindHistoryPlayInfo(bindingContext) {
                            setTag(it, Const.ViewComponent.HISTORY_INFO_TAG)
                            submitList(episodeDataList) {
                                jumpEpisode(this)
                            }
                        }
                    else
                        submitList(episodeDataList)
                }
            }
        }

        binding.ivHorizontalRecyclerView1More.apply {
            setImageDrawable(Util.getResDrawable(R.drawable.ic_keyboard_arrow_down_main_color_2_24_skin))
            imageTintList =
                ColorStateList.valueOf(getResColor(R.color.foreground_white_skin))
        }
    }

    /**
     * 自动跳转到历史剧集位置
     */
    private fun jumpEpisode(adapter: TypeAdapter) {
        binding.rvHorizontalRecyclerView1.apply {
            val target =
                adapter.getTag<MediaHistory>(Const.ViewComponent.HISTORY_INFO_TAG) ?: return
            coroutineScope.launch {
                episodeDataList?.forEachIndexed { index, data ->
                    //必须要保证EpisodeData.url有正确链接才支持自动定位和收藏
                    if (data.url == target.lastEpisodeUrl) {
                        logD("定位链接", target.lastEpisodeUrl ?: "")
                        //实际跳转的index应该前或者后一位（靠前前一位，靠后后一位），方便查看
                        val size = episodeDataList?.size ?: 0
                        val jumpIndex =
                            //在两端则直接跳转
                            if (index == 0 || index == size - 1) index
                            //在中间则根据情况向右偏移跳转方便查看集数
                            else index + 1
                        val jumpLength = (jumpIndex - (lastEpisodeIndex ?: 0)).absoluteValue
                        logD("定位跳转", "index=$index jumpIndex=$jumpIndex size=$size")
                        launch(Dispatchers.Main) {
                            //更新新位置
                            adapter.notifyItemChanged(index)
                            lastEpisodeIndex?.also {
                                //如果有上一个位置页更新
                                adapter.notifyItemChanged(it)
                            }
                            if (jumpLength <= 30)
                                smoothScrollToPosition(jumpIndex)
                            else
                            //过长直接跳转
                                scrollToPosition(jumpIndex)

                            lastEpisodeIndex = index
                        }

                        return@launch
                    }
                }
            }
        }
    }

    //剧集视图
    open class EpisodeViewHolder(protected val binding: ItemAnimeEpisode2Binding) :
        TypeViewHolder<EpisodeData>(binding.root) {

        constructor(parent: ViewGroup) : this(
            ItemAnimeEpisode2Binding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        ) {

            setOnClickListener(itemView) { pos ->
                bindingTypeAdapter.getData<EpisodeData>(pos)?.also {
                    val action = it.action
                    if (action is PlayAction) {
                        logD("开始播放动作", action.formatMemberField(), false)
                        //如果是跳转播放则填入播放列表
                        val playList =
                            bindingTypeAdapter.getTag<List<EpisodeData>>(Const.ViewComponent.EPISODE_LIST_TAG)
                        if (playList != null) {
                            action.extraData = playList
                        }
                    }
                    action?.go(bindingContext)
                }
            }

        }

        override fun onBind(data: EpisodeData) {
            val historyBean =
                bindingTypeAdapter.getTag<MediaHistory>(Const.ViewComponent.HISTORY_INFO_TAG)

            binding.tvAnimeEpisode2.apply {
                setTextColor(Color.WHITE)
            }.text = data.name

            binding.root.apply {
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                if (layoutParams is ViewGroup.MarginLayoutParams) {
                    (layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 6.dp, 8.dp, 6.dp)
                }

                if (data.url == historyBean?.lastEpisodeUrl) {
                    //有对应播放记录，高亮显示并置顶
                    setTextColor(getResColor(R.color.foreground_main_color_2_skin))
                } else {
                    setTextColor(getResColor(R.color.foreground_white_skin))
                }

                background =
                    Util.getResDrawable(R.drawable.shape_circle_corner_edge_white_ripper_5_skin)
            }
        }
    }
}