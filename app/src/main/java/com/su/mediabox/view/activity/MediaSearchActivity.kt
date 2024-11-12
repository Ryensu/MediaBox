package com.su.mediabox.view.activity

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import com.microsoft.appcenter.analytics.Analytics
import com.su.mediabox.R
import com.su.mediabox.bean.MediaSearchHistory
import com.su.mediabox.database.getAppDataBase
import com.su.mediabox.databinding.ActivitySearchBinding
import com.su.mediabox.databinding.ItemSearchHistory1Binding
import com.su.mediabox.plugin.PluginManager
import com.su.mediabox.pluginapi.action.SearchAction
import com.su.mediabox.util.*
import com.su.mediabox.util.Util.showKeyboard
import com.su.mediabox.viewmodel.MediaSearchViewModel
import com.su.mediabox.view.adapter.type.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaSearchActivity : BasePluginActivity() {

    companion object {
        private val searchDataViewMapList = DataViewMapList().apply {
            registerDataViewMap<MediaSearchHistory, SearchHistoryViewHolder>()
            addAll(TypeAdapter.globalDataViewMap)
        }
    }

    val mBinding by viewBind(ActivitySearchBinding::inflate)
    val viewModel by viewModels<MediaSearchViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Analytics.trackEvent("功能：媒体搜索")

        mBinding.run {
            //列表
            rvSearchActivity
                .dynamicGrid()
                .initTypeList(searchDataViewMapList) {
                    emptyData = null
                    vHCreateDSL<SearchHistoryViewHolder> {
                        setOnClickListener(itemView) { pos ->
                            bindingTypeAdapter.getData<MediaSearchHistory>(pos)?.also {
                                val keyWork = it.title.trim()
                                //注意这里因为复用问题所以不能直接通过内部类调用变量
                                (bindingContext.toComponentActivity() as? MediaSearchActivity)?.apply {
                                    mBinding.etSearchActivitySearch.apply {
                                        text = keyWork
                                        setSelection(keyWork.length)
                                    }
                                    showLoading()
                                    viewModel.getSearchData(keyWork)
                                }
                            }
                        }
                    }
                }
            //上拉刷新
            srlSearchActivity.setEnableRefresh(false)
            srlSearchActivity.setOnLoadMoreListener {
                showLoading()
                viewModel.getSearchData()
            }
            //搜索框
            mBinding.etSearchActivitySearch.apply {
                isEdit = true
                setEnterListener {
                    isEdit = false
                    viewModel.getSearchData(text.toString())
                }
                addTextChangedListener {
                    if (it.isNullOrEmpty())
                        viewModel.showSearchHistory()
                }
                //标识当前插件
                PluginManager.currentLaunchPlugin.observe(this@MediaSearchActivity) {
                    it?.apply {
                        setNavIcon(icon)
                    }
                }
                setNavListener {
                    //TODO 切换插件
                }
            }

            viewModel.showSearchHistory()
            etSearchActivitySearch.showKeyboard()
        }

        viewModel.showState.observe(this) {
            mBinding.apply {
                srlSearchActivity.finishLoadMore()
                etSearchActivitySearch.isEdit = true
                srlSearchActivity.setEnableLoadMore(false)
                if (etSearchActivitySearch.text.isNotEmpty())
                    etSearchActivitySearch.hideKeyboard()
            }
            when (it) {
                MediaSearchViewModel.ShowState.KEYWORD -> {
                    mBinding.rvSearchActivity.typeAdapter().submitList(viewModel.resultData) {
                        mBinding.tvSearchActivityTip.gone()
                    }
                }
                MediaSearchViewModel.ShowState.RESULT -> {
                    mBinding.rvSearchActivity.typeAdapter().submitList(viewModel.resultData) {
                        val size = viewModel.resultData?.size ?: 0
                        when {
                            size == 0 -> getString(R.string.no_result_info).showToast()
                            viewModel.lastLoadSize == 0 -> getString(R.string.no_more_info).showToast()
                            else -> mBinding.srlSearchActivity.setEnableLoadMore(true)
                        }
                        mBinding.tvSearchActivityTip.apply {
                            visible()
                            text = getString(R.string.search_activity_tip, viewModel.mKeyWord, size)
                        }
                        mBinding.layoutSearchActivityLoading.visibility = View.GONE
                    }
                }
                MediaSearchViewModel.ShowState.FAILED -> {
                    mBinding.srlSearchActivity.setEnableLoadMore(true)
                    mBinding.layoutSearchActivityLoading.visibility = View.GONE
                }
                else -> {}
            }
        }

        consumeAction<SearchAction>()?.also {
            viewModel.getSearchData(it.keyWork)
        }
    }

    private fun showLoading() {
        mBinding.layoutSearchActivityLoading.apply {
            smartInflate()
            visibility = View.VISIBLE
        }
    }

    private class SearchHistoryViewHolder private constructor(private val binding: ItemSearchHistory1Binding) :
        TypeViewHolder<MediaSearchHistory>(binding.root) {

        companion object {
            private val coroutineScope by lazy(LazyThreadSafetyMode.NONE) {
                CoroutineScope(Dispatchers.IO)
            }
        }

        private var data: MediaSearchHistory? = null

        constructor(parent: ViewGroup) : this(
            ItemSearchHistory1Binding.inflate(LayoutInflater.from(parent.context), parent, false)
        ) {
            setOnClickListener(binding.ivSearchHistory1Delete) {
                coroutineScope.launch {
                    data?.title?.also {
                        getAppDataBase().searchDao().deleteSearchHistory(it)
                    }
                }
            }
        }

        override fun onBind(data: MediaSearchHistory) {
            this.data = data
            binding.tvSearchHistory1Title.text = data.title
        }
    }
}
