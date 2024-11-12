package com.su.mediabox.plugin

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.afollestad.materialdialogs.MaterialDialog
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import com.su.mediabox.App
import com.su.mediabox.R
import com.su.mediabox.database.destroyInstance
import com.su.mediabox.database.getAppDataBaseFileName
import com.su.mediabox.model.PluginInfo
import com.su.mediabox.plugin.PluginPreferenceImpl.checkKeyExist
import com.su.mediabox.plugin.PluginPreferenceImpl.get
import com.su.mediabox.plugin.PluginPreferenceImpl.prefDataStoreFile
import com.su.mediabox.plugin.PluginPreferenceImpl.set
import com.su.mediabox.pluginapi.Constant
import com.su.mediabox.pluginapi.IPluginFactory
import com.su.mediabox.pluginapi.components.IBasePageDataComponent
import com.su.mediabox.pluginapi.components.IMediaUpdateDataComponent
import com.su.mediabox.util.*
import com.su.mediabox.util.Text.githubProxy
import com.su.mediabox.util.Util.getSignatures
import com.su.mediabox.view.adapter.type.TypeAdapter
import dalvik.system.PathClassLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File


object PluginManager {

    private const val TAG = "PluginManager"

    val appApiVersion by unsafeLazy {
        val appInfo: ApplicationInfo = App.context.packageManager
            .getApplicationInfo(
                App.context.packageName,
                PackageManager.GET_META_DATA
            )
        appInfo.metaData.getInt("media_plugin_api_version") ?: -1
    }

    private val pluginFactoryPool = mutableMapOf<String, IPluginFactory>()
    private val componentPool =
        mutableMapOf<String, MutableMap<Class<out IBasePageDataComponent>, IBasePageDataComponent>>()

    /**
     * 最低支持的插件API版本
     */
    const val minPluginApiVersion = 1

    const val pluginFileSuffix = ".mpp"

    const val PLUGIN_DIR_NAME = "plugins"
    val pluginDir = App.context.getExternalFilesDir(PLUGIN_DIR_NAME)!!

    init {
        PluginPackageObserver.startWatching()
    }

    /**
     * Map<packageName,[PluginInfo]>
     */
    private val pluginDataFlow = MutableStateFlow<Map<String, PluginInfo>?>(null)
    private val _currentLaunchPlugin = MutableLiveData<PluginInfo?>()
    private val pluginIntent = Intent(Constant.PLUGIN_DEBUG_ACTION)
    private val pluginWorkScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    val pluginFlow: Flow<List<PluginInfo>> = pluginDataFlow
        //等待插件扫描完毕(null表示还没扫描完)
        .filter {
            it != null
        }
        .map {
            it!!.values.toList()
        }
        .flowOn(Dispatchers.Default)

    val pluginLiveData: LiveData<List<PluginInfo>> = pluginFlow.asLiveData()

    val currentLaunchPlugin = _currentLaunchPlugin.toLiveData().apply {
        observeForever {
            //每次退出插件
            TypeAdapter.globalTypeRecycledViewPool.clear()
        }
    }

    fun queryPluginInfo(packageName: String) = pluginDataFlow.value?.get(packageName)

    fun scanPlugin() {
        val packageManager = App.context.packageManager
        pluginWorkScope.launch {
            //首先清空所有组件缓存
            pluginFactoryPool.clear()
            componentPool.clear()

            val plugins = mutableMapOf<String, PluginInfo>()
            //内部安装的插件，如果有外部相同包名的则会被覆盖以方便调试
            pluginDir.listFiles()?.apply {
                logD("内部插件数量", "$size")
            }?.forEach { pluginPackage ->
                val path = pluginPackage.absolutePath
                if (path.endsWith(pluginFileSuffix)) {
                    logD("内部插件", path)
                    parsePluginInfo(path)?.also { plugins[it.packageName] = it }
                }
            }
            //扫描已安装的，只在debug模式下有效以方便调试
            debug {
                packageManager.queryIntentActivities(pluginIntent, 0).apply {
                    logD("外部插件数量", "$size")
                }.forEach { info ->
                    val path = info.activityInfo.applicationInfo.sourceDir
                    logD("外部插件", path)
                    parsePluginInfo(path)?.also {
                        it.isExternalPlugin = true
                        plugins[it.packageName] = it
                    }
                }
            }
            logI("插件扫描完毕", "数量:${plugins.size}")
            pluginDataFlow.value = plugins
        }
    }

    fun parsePluginInfo(pluginPackagePath: String): PluginInfo? {
        App.context.packageManager.getPackageArchiveInfo(
            pluginPackagePath,
            PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES
        )?.apply {
            //部分系统（尤其是低版本）上存在加载资源失败的bug
            applicationInfo.apply {
                if (publicSourceDir.isNullOrEmpty())
                    publicSourceDir = pluginPackagePath
                if (sourceDir.isNullOrEmpty())
                    sourceDir = pluginPackagePath
            }
            return parsePluginInfo(this)
        }
        return null
    }

    fun parsePluginInfo(pluginPackageInfo: PackageInfo): PluginInfo? {
        val pluginApplicationInfo = pluginPackageInfo.applicationInfo
        val packageManager = App.context.packageManager
        //插件API版本标记
        val apiVersion =
            pluginApplicationInfo.metaData?.getInt("media_plugin_api_version", -1) ?: -1
        if (apiVersion == -1)
            return null
        //插件实现的组件工厂类(IComponentFactory)完整包名
        val apiImpl =
            pluginApplicationInfo.metaData?.getString("media_plugin_api_impl") ?: return null

        return PluginInfo(
            apiVersion,
            apiImpl,
            pluginApplicationInfo.packageName,
            pluginApplicationInfo.loadLabel(packageManager).toString(),
            pluginPackageInfo.versionName,
            pluginApplicationInfo.loadIcon(packageManager),
            pluginApplicationInfo.sourceDir,
            getSignatures(pluginPackageInfo)
        )
    }

    /**
     * 注意，如果不是在主线程调用，[currentLaunchPlugin]可能不会一下子就更新值
     */
    fun Context.launchPlugin(
        pluginInfo: PluginInfo?,
        isLaunchInitAction: Boolean = true,
        initialized: (() -> Unit)? = null
    ) {
        pluginInfo?.apply {
            if (!isEnable)
                return
            val isMain = Thread.currentThread() == mainLooper.thread
            if (isMain)
                _currentLaunchPlugin.value = this@apply
            pluginWorkScope.launch(Dispatchers.Main) {
                if (!isMain)
                    _currentLaunchPlugin.value = this@apply
                Analytics.trackEvent("plugin launch:$name")
                //此处Catch仅仅是为了显示启动插件时所抛出的异常，大部分是自排除的提醒性异常，比如用于提示插件兼容性
                runCatching {
                    acquirePluginFactory().apply {
                        pluginLaunch()
                        if (isLaunchInitAction)
                            initAction.go(this@launchPlugin)
                    }
                    initialized?.invoke()
                }.onFailure {
                    Crashes.trackError(it)
                    it.printStackTrace()
                    it.message?.showToast()
                    _currentLaunchPlugin.value = null
                }
            }
        }
    }

    /**
     * 安装插件
     *
     * @param pluginInfo 至少要保证包含有效[PluginInfo.id]
     */
    suspend fun installPlugin(
        fileUri: Uri,
        pluginInfo: PluginInfo
    ): File? {
        if (!pluginInfo.isEnable)
            return null
        return withContext(Dispatchers.IO) {
            logD("安装插件", "uri=$fileUri info=$pluginInfo")
            return@withContext fileUri.copyTo(File(pluginDir, pluginInfo.installedPluginName()))
                .apply {
                    App.context.getString(R.string.plugin_installer_hint_format, pluginInfo.name)
                        .showToast(Toast.LENGTH_LONG)
                }
        }
    }

    /**
     * @param confirmContext 如果提供则有确认
     */
    fun uninstallPlugin(
        pluginInfo: PluginInfo,
        confirmContext: Context? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (confirmContext != null) {
            if (!pluginInfo.isEnable)
                return
            MaterialDialog(confirmContext).show {
                title(res = R.string.plugin_manage_media_uninstall_title)
                message(res = R.string.plugin_manage_media_uninstall_msg)
                negativeButton(res = R.string.cancel) { }
                positiveButton(res = R.string.ok) {
                    pluginInfo.isEnable = false
                    uninstallPlugin(pluginInfo, onComplete = onComplete)
                }
            }
        } else {
            pluginWorkScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                Crashes.trackError(throwable)
                throwable.printStackTrace()
                pluginInfo.isEnable = true
            }) {
                //删除数据库
                val dbFile = App.context.getDatabasePath(pluginInfo.getAppDataBaseFileName())
                logD("数据库", dbFile.absolutePath)
                dbFile.parentFile?.listFiles()?.forEach {
                    if (it.name.startsWith(dbFile.name))
                        it.delete()
                }
                pluginInfo.destroyInstance()
                //删除DataStore键对存储
                pluginInfo.prefDataStoreFile().delete()
                //删除插件包
                pluginDir.listFiles()?.forEach {
                    if (it.name.contains(pluginInfo.packageName)) {
                        it.delete()
                        return@launch
                    }
                }
            }
            onComplete?.invoke()
        }
    }

    /**
     * 调用系统下载器下载插件
     *
     * @param pluginInfo 至少要保证包含有效[PluginInfo.sourcePath]（作为下载地址）
     * @param directInstall 直接下载安装，一般只用于官方仓库插件，不经安装器验证直接安装
     */
    fun downloadPlugin(pluginInfo: PluginInfo, directInstall: Boolean = false) = runCatching {
        val downloadManager =
            App.context.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
        val uri: Uri = Uri
            .parse(pluginInfo.sourcePath.githubProxy)
        val request = DownloadManager.Request(uri).apply {
            val fileName =
                "${pluginInfo.name}_${pluginInfo.packageName}_${pluginInfo.version}${pluginFileSuffix}"
            if (directInstall) {
                setDestinationInExternalFilesDir(App.context, PLUGIN_DIR_NAME, "tmp_$fileName")
            } else {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        downloadManager.enqueue(request)
    }.onFailure {
        logE(TAG, "downloadPlugin: error=${it.message} directInstall=$directInstall pluginInfo=$pluginInfo")
        "插件下载失败: ${it.message}".showToast()
        it.printStackTrace()
    }

    fun initPluginEnv() {
        _currentLaunchPlugin.value = null
    }

    /**
     * 获取组件工厂实例
     */
    @Throws(Exception::class)
    fun PluginInfo.acquirePluginFactory(): IPluginFactory =
        pluginFactoryPool[sourcePath] ?: run {

            logD("载入插件工厂", "最低支持：$minPluginApiVersion 当前插件API版本：$apiVersion")

            //API版本兼容检查
            if (apiVersion < minPluginApiVersion)
            //插件API版本过低
                throw RuntimeException(
                    App.context.getString(
                        R.string.plugin_api_version_too_low, apiVersion,
                        minPluginApiVersion
                    )
                )
            else if (apiVersion > appApiVersion)
            //插件API版本过高
                throw RuntimeException(
                    App.context.getString(
                        R.string.plugin_api_version_too_high, apiVersion,
                        appApiVersion
                    )
                )

            val pluginFile = File(sourcePath)
                .apply {
                    if (!exists() || !isFile)
                        throw RuntimeException(App.context.getString(R.string.plugin_not_exist))
                }

            val classLoader = PathClassLoader(pluginFile.path, App.context.classLoader)

            try {
                val clz = classLoader.loadClass(apiImpl)
                (clz.newInstance() as IPluginFactory).also {
                    pluginFactoryPool[sourcePath] = it
                }
            } catch (e: Exception) {
                throw RuntimeException(App.context.getString(R.string.plugin_init_error))
            }
        }

    fun acquirePluginFactory(): IPluginFactory =
        currentLaunchPlugin.value?.acquirePluginFactory()
            ?: throw RuntimeException(App.context.getString(R.string.plugin_run_error))

    @Throws(Exception::class)
    inline fun <reified T : IBasePageDataComponent> acquireComponent() =
        acquireComponent(T::class.java)

    @Throws(Exception::class)
    fun <T : IBasePageDataComponent> acquireComponent(clazz: Class<T>) =
        currentLaunchPlugin.value?.acquireComponent(clazz)
            ?: throw RuntimeException(App.context.getString(R.string.plugin_run_error))

    /**
     * 获取组件实例
     */
    @Throws(Exception::class)
    fun <T : IBasePageDataComponent> PluginInfo.acquireComponent(clazz: Class<T>): T {
        val isSingleton =
            clazz.isAnnotationPresent(IPluginFactory.SingletonComponent::class.java)
        if (isSingleton) {
            //被标注为单例组件，从组件池查找
            componentPool[sourcePath]?.get(clazz)?.also { return it as T }
        }
        return acquirePluginFactory().createComponent(clazz)?.also { component ->
            if (isSingleton)
            //存入组件库
                (componentPool[sourcePath]
                    ?: mutableMapOf<Class<out IBasePageDataComponent>, IBasePageDataComponent>()
                        .also {
                            componentPool[sourcePath] = it
                        })[clazz] = component
        }
            ?: throw RuntimeException(
                App.context.getString(
                    R.string.plugin_component_error,
                    clazz.simpleName
                )
            )
    }

    private fun PluginInfo.installedPluginName() = "mediabox_plugin_${id}${pluginFileSuffix}"

    /**
     * 插件包(安装/卸载)监听
     */
    private object PluginPackageObserver : FileObserver(
        pluginDir.absolutePath,
        MODIFY or CLOSE_WRITE or MOVED_FROM or MOVED_TO or DELETE or DELETE_SELF or MOVE_SELF
    ) {
        override fun onEvent(event: Int, path: String?) {
            path?.also {
                File(pluginDir, path).apply {
                    logD("监测到插件变动", "event=$event path=$absolutePath")
                    when {
                        //下载安装缓存
                        path.startsWith("tmp_") -> if (event == CLOSE_WRITE)
                            pluginWorkScope.launch {
                                logD("安装插件缓存", path)
                                try {
                                    parsePluginInfo(this@apply.absolutePath)?.also {
                                        FileUri.getUriByFile(this@apply, true)
                                            ?.let { it1 -> installPlugin(it1, it) }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    delete()
                                }
                            }
                        //插件变动
                        path.startsWith("mediabox_plugin_") && path.endsWith(pluginFileSuffix) -> {
                            logD("更新插件", path)
                            scanPlugin()
                        }
                    }
                }
            }
        }
    }

}