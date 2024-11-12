package com.su.mediabox.view.viewcomponents

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.su.mediabox.App
import com.su.mediabox.databinding.FragmentViewPagerBinding
import com.su.mediabox.databinding.ViewComponentViewPagerBinding
import com.su.mediabox.pluginapi.data.BaseData
import com.su.mediabox.pluginapi.data.ViewPagerData
import com.su.mediabox.util.PluginIO
import com.su.mediabox.util.toLiveData
import com.su.mediabox.view.adapter.type.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.properties.Delegates

/**
 * 多页面视图组件
 * //TODO 还存在很大问题：Page和父RV间事件分发被阻塞
 */
class ViewPagerViewHolder private constructor(private val binding: ViewComponentViewPagerBinding) :
    TypeViewHolder<ViewPagerData>(binding.root) {

    /**
     * <bindingAdapter.hac,Page列表>
     */
    private val pagesPool = mutableMapOf<Int, List<Page>>()
    private val tlm = TabLayoutMediator(
        binding.vcViewPagerTabs,
        binding.vcViewPagerPages.getViewPager()
    ) { tab, pos ->
        tab.text = currentData?.pageLoaders?.get(pos)?.pageName(pos) ?: "标签"
    }

    private var currentData: ViewPagerData? = null

    constructor(parent: ViewGroup) : this(
        ViewComponentViewPagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    ) {
        binding.apply {
            vcViewPagerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabSelected(tab: TabLayout.Tab?) {}

                //点击当前Tab刷新
                override fun onTabReselected(tab: TabLayout.Tab?) {
                    pagesPool[bindingAdapter.hashCode()]?.get(vcViewPagerTabs.selectedTabPosition)
                        ?.initData()
                }
            })
        }

    }

    override fun onBind(data: ViewPagerData) {
        super.onBind(data)
        currentData = data
        binding.vcViewPagerPages.apply {
            setOffscreenPageLimit(data.pageLoaders.size)
            setAdapter(
                ViewPageAdapter(
                    context as FragmentActivity,
                    getPages(bindingAdapter.hashCode(), data.pageLoaders)
                )
            )
            setCurrentItem(data.defaultPage.coerceAtMost(data.pageLoaders.size - 1))
            tlm.apply {
                if (!isAttached)
                    attach()
            }
        }
    }

    /**
     * 不同bindingAdapter（被复用）使用对应唯一实例
     */
    private fun getPages(hashCode: Int, loaders: List<ViewPagerData.PageLoader>): List<Page> {
        pagesPool[hashCode]?.also {
            return it
        }
        val pages = mutableListOf<Page>()
        loaders.forEachIndexed { page, loader ->
            pages.add(Page(loader, page))
        }
        pagesPool[hashCode] = pages
        return pages
    }

    class Page : Fragment {

        private val vm by viewModels<PageViewModel>()

        //系统重建
        constructor()
        constructor(loader: ViewPagerData.PageLoader, page: Int) {
            this.loader = loader
            this.page = page
        }

        private var loader: ViewPagerData.PageLoader? = null
        private var page: Int? = null

        private lateinit var binding: FragmentViewPagerBinding
        private var isLoaded = true


        override fun onAttach(context: Context) {
            super.onAttach(context)
            //不通过setArguments以节省复杂数据的序列化转换消耗
            vm.bindData(loader, page)
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            binding = FragmentViewPagerBinding.inflate(layoutInflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            binding.apply {
                root.dynamicGrid().initTypeList { }

                vm.pageDataLiveData.observe(viewLifecycleOwner) {
                    root.submitList(it)
                }
            }
        }

        //懒加载
        override fun onResume() {
            super.onResume()
            if (isLoaded) {
                isLoaded = false
                initData()
            }
        }

        fun initData() {
            vm.getData()
        }
    }

    private class ViewPageAdapter(fragmentActivity: FragmentActivity, val pages: List<Page>) :
        FragmentStateAdapter(fragmentActivity) {

        override fun getItemCount(): Int = pages.size

        override fun createFragment(position: Int) = pages[position]
    }

    class PageViewModel : ViewModel() {

        private var pageLoader: ViewPagerData.PageLoader? = null
        private var page: Int = 0

        fun bindData(pageLoader: ViewPagerData.PageLoader?, page: Int?) {
            this.pageLoader = pageLoader
            page?.also { this.page = it }
        }

        private val _pageDataLiveData: MutableLiveData<List<BaseData>?> =
            MutableLiveData<List<BaseData>?>()
        val pageDataLiveData = _pageDataLiveData.toLiveData()

        fun getData() {
            viewModelScope.launch(Dispatchers.PluginIO) {
                _pageDataLiveData.postValue(pageLoader?.loadData(page))
            }
        }

    }
}