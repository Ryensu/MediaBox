package com.su.mediabox.plugin

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.su.mediabox.App
import com.su.mediabox.bean.PluginInfo
import com.su.mediabox.pluginapi.AppUtil
import com.su.mediabox.pluginapi.Constant
import com.su.mediabox.view.activity.BasePluginActivity
import com.su.mediabox.pluginapi.IComponentFactory
import com.su.mediabox.pluginapi.components.IBaseComponent
import com.su.mediabox.util.Util.getSignatures
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object PluginManager : AppUtil.IRouteProcessor {

    private val componentFactoryPool = mutableMapOf<String, IComponentFactory>()
    private val componentPool =
        mutableMapOf<String, MutableMap<Class<out IBaseComponent>, IBaseComponent>>()

    /**
     * 最低支持的插件API版本
     */
    private const val minPluginApiVersion = 2

    private val _pluginLiveData = MutableLiveData<List<PluginInfo>>()
    private val pluginIntent = Intent(Constant.PLUGIN_ACTION)
    private val pluginWorkScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    val pluginLiveData: LiveData<List<PluginInfo>> = _pluginLiveData

    fun scanPlugin(packageManager: PackageManager) {
        pluginWorkScope.launch {
            val plugin = packageManager.queryIntentActivities(pluginIntent, 0).map {
                PluginInfo(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    it.activityInfo.applicationInfo.loadLabel(packageManager).toString(),
                    it.loadIcon(packageManager),
                    it.activityInfo.applicationInfo.sourceDir,
                    packageManager.getSignatures(it.activityInfo.packageName)
                )
            }
            _pluginLiveData.postValue(plugin)
        }
    }

    fun Activity.getPluginIndex() = intent.getIntExtra(BasePluginActivity.PLUGIN_INFO_INDEX, -1)

    fun Activity.getPluginInfo(): PluginInfo {
        runCatching {
            val index = getPluginIndex()
            if (index == -1)
                throw RuntimeException("插件索引错误")
            _pluginLiveData.value?.get(index)
                ?: throw RuntimeException("插件持久信息为空($index/${_pluginLiveData.value?.size ?: -1})")
        }.onSuccess {
            return it
        }.onFailure {
            it.printStackTrace()
            throw RuntimeException("插件信息读取错误：${it.message ?: "null"}")
        }
        throw RuntimeException()
    }

    fun Activity.getPluginName() = getPluginInfo().name
    fun Activity.getPluginPath() = getPluginInfo().sourcePath

    fun Intent.setPluginInfo(pluginInfoIndex: Int) =
        putExtra(BasePluginActivity.PLUGIN_INFO_INDEX, pluginInfoIndex)

    fun acquireComponentFactory() =
        AppRouteProcessor.currentActivity?.get()?.getPluginPath()
            ?.let { acquireComponentFactory(it) }
            ?: throw RuntimeException("当前未绑定插件")

    /**
     * 获取组件工厂实例
     */
    @Throws(Exception::class)
    fun acquireComponentFactory(pluginPath: String): IComponentFactory =
        componentFactoryPool[pluginPath] ?: run {
            val pluginFile = File(pluginPath)
                .apply {
                    if (!exists() || !isFile)
                        throw RuntimeException("插件不存在")
                }

            val classLoader = PathClassLoader(pluginFile.path, App.context.classLoader)
            val clz = classLoader.loadClass(Constant.PLUGIN_INIT_CLASS)

            (clz.newInstance() as IComponentFactory).also {
                //检查插件API版本
                val version = it.apiVersion
                if (version < minPluginApiVersion) {
                    throw RuntimeException("该插件API版本($version)过低，请联系作者升级API(当前支持最低$minPluginApiVersion)")
                }

                componentFactoryPool[pluginPath] = it
            }
        }

    @Throws(Exception::class)
    fun <T : IBaseComponent> acquireComponent(clazz: Class<T>) =
        AppRouteProcessor.currentActivity?.get()?.getPluginPath()
            ?.let {
                acquireComponent(it, clazz)
            } ?: throw RuntimeException("当前未绑定插件")

    /**
     * 获取组件实例
     */
    @Throws(Exception::class)
    fun <T : IBaseComponent> acquireComponent(pluginPath: String, clazz: Class<T>): T {
        val isSingleton =
            clazz.isAnnotationPresent(IComponentFactory.SingletonComponent::class.java)
        if (isSingleton) {
            //被标注为单例组件，从组件池查找
            componentPool[pluginPath]?.get(clazz)?.also { return it as T }
        }
        return acquireComponentFactory(pluginPath).createComponent(clazz)?.also { component ->
            if (isSingleton)
            //存入组件库
                (componentPool[pluginPath]
                    ?: mutableMapOf<Class<out IBaseComponent>, IBaseComponent>()
                        .also {
                            componentPool[pluginPath] = it
                        })[clazz] = component
        }
            ?: throw RuntimeException("当前插件未提供该组件")
    }

    override fun process(actionUrl: String): Boolean =
        AppRouteProcessor.process(actionUrl)

}